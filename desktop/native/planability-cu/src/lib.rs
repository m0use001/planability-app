//! M21 Computer Use — Windows input injection, exposed to the main process over Node-API.
//!
//! Why this module exists instead of a shell-out to PowerShell/`AutoHotkey`:
//!   - no interpreter dependency at runtime, no GPL/licence question (napi-rs is MIT),
//!   - Node-API means **no electron-rebuild** when Electron bumps its ABI — the exact
//!     treadmill `better-sqlite3` puts us on, which this feature has no need to inherit,
//!   - errors come back as structured values, so "the click silently did nothing" becomes a
//!     message the model can read and act on.
//!
//! SAFETY: every extern call is wrapped by a constructor here; no pointer from outside the
//! module ever reaches Win32. The event arrays are plain Vecs that outlive the call.

mod keys;
mod win;

use napi::{Error, Result};
use napi_derive::napi;

use keys::ParsedKey;

/// Turn one parsed key into a down or up event.
fn key_event(k: &ParsedKey, up: bool) -> win::INPUT {
	let mut flags = if up { win::KEYEVENTF_KEYUP } else { 0 };
	if k.extended {
		flags |= win::KEYEVENTF_EXTENDEDKEY;
	}
	if k.unicode {
		flags |= win::KEYEVENTF_UNICODE;
	}
	win::INPUT::keyboard(win::KEYBDINPUT::new(k.vk, k.scan, flags))
}

/// `"ctrl+shift+s"` -> press modifiers in written order, release in reverse.
///
/// Release order matters: releasing the target before its modifier is what makes a chord
/// register as a chord rather than as separate keystrokes.
fn key_events(spec: &str) -> Result<Vec<win::INPUT>> {
	let parsed = keys::parse_spec(spec).map_err(Error::from_reason)?;
	let shift = keys::needs_shift(&parsed);
	let mut ev = Vec::with_capacity(parsed.len() * 2 + 2);
	if shift {
		ev.push(win::INPUT::keyboard(win::KEYBDINPUT::new(keys::VK_SHIFT, 0, 0)));
	}
	for k in &parsed {
		ev.push(key_event(k, false));
	}
	for k in parsed.iter().rev() {
		ev.push(key_event(k, true));
	}
	if shift {
		ev.push(win::INPUT::keyboard(win::KEYBDINPUT::new(
			keys::VK_SHIFT,
			0,
			win::KEYEVENTF_KEYUP,
		)));
	}
	Ok(ev)
}

fn inject(ev: Vec<win::INPUT>) -> Result<()> {
	win::send_all(&ev).map_err(|e| {
		Error::from_reason(format!(
			"{e} 若目标程序以管理员身份运行，Windows 的 UIPI 会拦截注入——请改用非提权程序，或先关掉该窗口。"
		))
	})
}

/// Inject one key chord, e.g. `"enter"`, `"ctrl+s"`, `"ctrl+shift+p"`.
#[napi]
pub fn inject_key(keys_spec: String) -> Result<()> {
	inject(key_events(&keys_spec)?)
}

/// Type literal text as UTF-16 units (handles CJK and emoji, which VK codes cannot).
///
/// The tool layer prefers the clipboard path for user text — this exists so non-keyboard
/// layouts and IME-free direct injection stay available, not as the default.
#[napi]
pub fn inject_type_text(text: String) -> Result<()> {
	if text.is_empty() {
		return Ok(());
	}
	let mut ev = Vec::with_capacity(text.len() * 2);
	for unit in text.encode_utf16() {
		let k = keys::unicode_unit(unit);
		ev.push(key_event(&k, false));
		ev.push(key_event(&k, true));
	}
	inject(ev)
}

/// Move to a physical-pixel point and click once. Move and click ship as ONE SendInput
/// batch so the cursor cannot be moved by anything else between the two halves.
#[napi]
pub fn inject_click(x: i32, y: i32, button: String) -> Result<()> {
	let (down, up) = match button.to_ascii_lowercase().as_str() {
		"left" | "" => (win::MOUSEEVENTF_LEFTDOWN, win::MOUSEEVENTF_LEFTUP),
		"right" => (win::MOUSEEVENTF_RIGHTDOWN, win::MOUSEEVENTF_RIGHTUP),
		other => return Err(Error::from_reason(format!("不支持的鼠标键「{other}」（只支持 left/right）"))),
	};
	let (nx, ny) = win::normalize_absolute(x, y);
	let travel = win::MOUSEEVENTF_MOVE | win::MOUSEEVENTF_ABSOLUTE | win::MOUSEEVENTF_VIRTUALDESK;
	inject(vec![
		win::INPUT::mouse(win::MOUSEINPUT::new(nx, ny, 0, travel)),
		win::INPUT::mouse(win::MOUSEINPUT::new(0, 0, 0, down)),
		win::INPUT::mouse(win::MOUSEINPUT::new(0, 0, 0, up)),
	])
}

/// Scroll `notches` notches at a point. Positive scrolls up, matching cu_scroll's contract.
#[napi]
pub fn inject_scroll(x: i32, y: i32, notches: i32) -> Result<()> {
	if notches == 0 {
		return Err(Error::from_reason("滚轮格数不能为 0"));
	}
	let (nx, ny) = win::normalize_absolute(x, y);
	let travel = win::MOUSEEVENTF_MOVE | win::MOUSEEVENTF_ABSOLUTE | win::MOUSEEVENTF_VIRTUALDESK;
	let delta = notches.saturating_mul(win::WHEEL_DELTA);
	inject(vec![
		win::INPUT::mouse(win::MOUSEINPUT::new(nx, ny, 0, travel)),
		win::INPUT::mouse(win::MOUSEINPUT::new(0, 0, delta as u32, win::MOUSEEVENTF_WHEEL)),
	])
}

#[napi(object)]
pub struct VirtualScreen {
	pub x: i32,
	pub y: i32,
	pub width: i32,
	pub height: i32,
}

/// The virtual desktop rectangle in physical pixels. `x`/`y` are frequently negative on
/// multi-monitor setups, so callers must not clamp them to 0.
#[napi]
pub fn virtual_screen() -> VirtualScreen {
	let (x, y, w, h) = win::virtual_screen();
	VirtualScreen { x, y, width: w, height: h }
}

#[napi]
pub fn cursor_pos() -> Result<Vec<i32>> {
	match win::cursor_pos() {
		Some((x, y)) => Ok(vec![x, y]),
		None => Err(Error::from_reason("GetCursorPos 失败")),
	}
}

/// Title of the foreground window — including windows that are NOT ours.
///
/// `BrowserWindow.getAllWindows()` only enumerates our own windows, so without this the
/// tool layer's "target must be frontmost" guard had no way to check a third-party target.
/// That gap is the reason this function exists, not convenience.
#[napi]
pub fn foreground_title() -> Option<String> {
	win::foreground_title()
}

/// Version marker so the main process can tell "module present" from "module absent"
/// without a try/catch dance, and so a stale .node from an older build is detectable.
#[napi]
pub fn abi_version() -> u32 {
	2
}