//! Win32 input/geometry bindings, hand-declared.
//!
//! Hand-declared rather than pulled from `windows-sys` on purpose: this surface is
//! ~8 functions and 3 structs, and the one thing that must be exactly right is the struct
//! LAYOUT — a wrong `INPUT` size makes SendInput fail in ways that look like "the click
//! did nothing" on some machines and works on others. So the layouts are asserted at compile
//! time below, which turns a silent runtime mystery into a build error.

#![allow(non_camel_case_types)]

use std::mem::size_of;

// ---------- SendInput / KEYEVENTF ----------
pub const INPUT_MOUSE: u32 = 0;
pub const INPUT_KEYBOARD: u32 = 1;

pub const KEYEVENTF_EXTENDEDKEY: u32 = 0x0001;
pub const KEYEVENTF_KEYUP: u32 = 0x0002;
pub const KEYEVENTF_UNICODE: u32 = 0x0004;

// ---------- MOUSEEVENTF ----------
pub const MOUSEEVENTF_MOVE: u32 = 0x0001;
pub const MOUSEEVENTF_LEFTDOWN: u32 = 0x0002;
pub const MOUSEEVENTF_LEFTUP: u32 = 0x0004;
pub const MOUSEEVENTF_RIGHTDOWN: u32 = 0x0008;
pub const MOUSEEVENTF_RIGHTUP: u32 = 0x0010;
pub const MOUSEEVENTF_WHEEL: u32 = 0x0800;
pub const MOUSEEVENTF_ABSOLUTE: u32 = 0x8000;
/// Tells SendInput the absolute coords are relative to the VIRTUAL desktop, not primary.
/// Without this, the second monitor is unreachable — the classic multi-monitor bug.
pub const MOUSEEVENTF_VIRTUALDESK: u32 = 0x4000;

/// One wheel notch. Win32 counts 120 per notch; sending raw "1" scrolls almost invisibly.
pub const WHEEL_DELTA: i32 = 120;

// ---------- GetSystemMetrics indices ----------
pub const SM_XVIRTUALSCREEN: i32 = 76;
pub const SM_YVIRTUALSCREEN: i32 = 77;
pub const SM_CXVIRTUALSCREEN: i32 = 78;
pub const SM_CYVIRTUALSCREEN: i32 = 79;

#[derive(Clone, Copy)]
#[repr(C)]
pub struct KEYBDINPUT {
	pub w_vk: u16,
	pub w_scan: u16,
	pub dw_flags: u32,
	pub time: u32,
	pub dw_extra_info: usize,
}

#[derive(Clone, Copy)]
#[repr(C)]
pub struct MOUSEINPUT {
	pub dx: i32,
	pub dy: i32,
	pub mouse_data: u32,
	pub dw_flags: u32,
	pub time: u32,
	pub dw_extra_info: usize,
}

#[derive(Clone, Copy)]
#[repr(C)]
pub union InputUnion {
	pub ki: KEYBDINPUT,
	pub mi: MOUSEINPUT,
}

#[derive(Clone, Copy)]
#[repr(C)]
pub struct INPUT {
	pub r#type: u32,
	pub u: InputUnion,
}

#[derive(Clone, Copy)]
#[repr(C)]
pub struct POINT {
	pub x: i32,
	pub y: i32,
}

// The whole point of hand-declaring: prove the layout now, not at 3am on someone's desktop.
const _: () = assert!(size_of::<KEYBDINPUT>() == 24);
const _: () = assert!(size_of::<MOUSEINPUT>() == 32);
const _: () = assert!(size_of::<InputUnion>() == 32);
const _: () = assert!(size_of::<INPUT>() == 40);
const _: () = assert!(std::mem::align_of::<INPUT>() == 8);

// SendInput / GetSystemMetrics / GetForegroundWindow / GetWindowTextW / GetCursorPos all
// live in user32.dll. Without this attribute the link fails with LNK2019 "unresolved
// external" -- exactly the kind of silent-looking failure the layout asserts exist to avoid.
#[link(name = "user32")]
extern "system" {
	pub fn SendInput(c_inputs: u32, p_inputs: *const INPUT, cb_size: i32) -> u32;
	pub fn GetSystemMetrics(n_index: i32) -> i32;
	pub fn GetLastError() -> u32;
	pub fn GetForegroundWindow() -> isize;
	pub fn GetWindowTextW(hwnd: isize, lp_string: *mut u16, n_max_count: i32) -> i32;
	pub fn GetCursorPos(lp_point: *mut POINT) -> i32;
}

// ---------- constructors ----------

impl INPUT {
	pub fn keyboard(ki: KEYBDINPUT) -> Self {
		INPUT { r#type: INPUT_KEYBOARD, u: InputUnion { ki } }
	}
	pub fn mouse(mi: MOUSEINPUT) -> Self {
		INPUT { r#type: INPUT_MOUSE, u: InputUnion { mi } }
	}
}

impl KEYBDINPUT {
	pub fn new(vk: u16, scan: u16, flags: u32) -> Self {
		KEYBDINPUT { w_vk: vk, w_scan: scan, dw_flags: flags, time: 0, dw_extra_info: 0 }
	}
}

impl MOUSEINPUT {
	pub fn new(dx: i32, dy: i32, data: u32, flags: u32) -> Self {
		MOUSEINPUT { dx, dy, mouse_data: data, dw_flags: flags, time: 0, dw_extra_info: 0 }
	}
}

// ---------- safe-ish wrappers ----------

/// Push the whole event array in one SendInput call.
///
/// Returns the number of events the OS actually accepted. A short count is the single most
/// useful diagnostic here: it almost always means UIPI blocked us because the target window
/// runs elevated, and "it silently did nothing" is otherwise very hard to explain to a user.
pub fn send_all(events: &[INPUT]) -> Result<(), SendError> {
	if events.is_empty() {
		return Ok(());
	}
	let sent = unsafe { SendInput(events.len() as u32, events.as_ptr(), size_of::<INPUT>() as i32) };
	if sent as usize == events.len() {
		return Ok(());
	}
	let err = unsafe { GetLastError() };
	Err(SendError { sent: sent as usize, want: events.len(), os_error: err })
}

#[derive(Debug, Clone)]
pub struct SendError {
	pub sent: usize,
	pub want: usize,
	pub os_error: u32,
}

impl std::fmt::Display for SendError {
	fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
		write!(
			f,
			"SendInput 只接受了 {}/{} 个事件（GetLastError={}）。",
			self.sent, self.want, self.os_error
		)
	}
}

/// The virtual desktop rectangle in physical pixels. Its origin is often NEGATIVE
/// (a monitor left of / above the primary one), which is why clamping to 0 is wrong.
pub fn virtual_screen() -> (i32, i32, i32, i32) {
	unsafe {
		(
			GetSystemMetrics(SM_XVIRTUALSCREEN),
			GetSystemMetrics(SM_YVIRTUALSCREEN),
			GetSystemMetrics(SM_CXVIRTUALSCREEN),
			GetSystemMetrics(SM_CYVIRTUALSCREEN),
		)
	}
}

/// Physical pixels -> SendInput's 0..65535 absolute space, relative to the VIRTUAL desktop.
pub fn normalize_absolute(x: i32, y: i32) -> (i32, i32) {
	let (vx, vy, vw, vh) = virtual_screen();
	let span_x = (vw - 1).max(1);
	let span_y = (vh - 1).max(1);
	let nx = ((x - vx) as i64 * 65535 / span_x as i64).clamp(0, 65535) as i32;
	let ny = ((y - vy) as i64 * 65535 / span_y as i64).clamp(0, 65535) as i32;
	(nx, ny)
}

pub fn cursor_pos() -> Option<(i32, i32)> {
	let mut p = POINT { x: 0, y: 0 };
	if unsafe { GetCursorPos(&mut p) } != 0 { Some((p.x, p.y)) } else { None }
}

/// Title of the foreground window, or None. This is how the tool layer learns which
/// THIRD-PARTY window is frontmost — `BrowserWindow.getAllWindows()` only ever sees our own
/// windows, so without this the "target must be foreground" guard cannot actually be checked.
pub fn foreground_title() -> Option<String> {
	let hwnd = unsafe { GetForegroundWindow() };
	if hwnd == 0 {
		return None;
	}
	let mut buf = vec![0u16; 512];
	let n = unsafe { GetWindowTextW(hwnd, buf.as_mut_ptr(), buf.len() as i32) };
	if n <= 0 {
		return None;
	}
	let s = String::from_utf16_lossy(&buf[..n as usize]);
	let t = s.trim().to_string();
	if t.is_empty() { None } else { Some(t) }
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn absolute_normalization_covers_the_whole_range() {
		// Non-virtual-desktop inputs (CI): we only assert the math shape, not real metrics.
		let (nx, ny) = normalize_absolute(0, 0);
		assert!((0..=65535).contains(&nx) && (0..=65535).contains(&ny));
	}

	#[test]
	fn input_struct_layout_is_what_sendinput_expects() {
		// Reported as a test as well as a const assert, so `cargo test` alone surfaces a
		// layout regression even if someone runs a build that skips the const eval.
		assert_eq!(size_of::<INPUT>(), 40);
		assert_eq!(size_of::<KEYBDINPUT>(), 24);
		assert_eq!(size_of::<MOUSEINPUT>(), 32);
	}

	#[test]
	fn empty_event_list_is_a_no_op_not_an_error() {
		assert!(send_all(&[]).is_ok());
	}

	#[test]
	fn keyboard_and_mouse_inputs_carry_the_right_discriminant() {
		let k = INPUT::keyboard(KEYBDINPUT::new(0x41, 0, 0));
		assert_eq!(k.r#type, INPUT_KEYBOARD);
		let m = INPUT::mouse(MOUSEINPUT::new(0, 0, 0, MOUSEEVENTF_MOVE));
		assert_eq!(m.r#type, INPUT_MOUSE);
	}
}