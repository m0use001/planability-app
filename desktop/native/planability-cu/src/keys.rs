//! Key-spec parsing — deliberately free of any FFI so it is unit-testable anywhere.
//!
//! Why a parser instead of a lookup table handed straight to Win32: the model writes
//! `"ctrl+shift+s"`, and the bugs that matter live in the *grammar* (is that `+` a
//! separator or the plus key?), not in the Win32 call. Keeping the grammar pure means
//! those bugs are caught by `cargo test` instead of by a stray click on someone's desktop.

/// wVk for the modifier we need to synthesize when the spec asks for a shifted character.
pub const VK_SHIFT: u16 = 0x10;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedKey {
	pub vk: u16,
	pub scan: u16,
	/// KEYEVENTF_EXTENDEDKEY — required or the OS misreads arrows/Del/Home on x64.
	pub extended: bool,
	/// Inject as a UTF-16 code unit rather than a physical key (non-ASCII input).
	pub unicode: bool,
	/// This character needs Shift held down.
	pub shift: bool,
}

impl ParsedKey {
	fn plain(vk: u16) -> Self {
		Self { vk, scan: 0, extended: false, unicode: false, shift: false }
	}
	fn ext(vk: u16) -> Self {
		Self { vk, scan: 0, extended: true, unicode: false, shift: false }
	}
	fn shifted(vk: u16) -> Self {
		Self { vk, scan: 0, extended: false, unicode: false, shift: true }
	}
	fn uni(unit: u16) -> Self {
		Self { vk: 0, scan: unit, extended: false, unicode: true, shift: false }
	}
}

/// A UTF-16 code unit injected via KEYEVENTF_UNICODE (used by the literal-text path).
pub fn unicode_unit(unit: u16) -> ParsedKey {
	ParsedKey::uni(unit)
}

/// Named keys, lowercase. Generated families (F1-F24, numpad digits) are handled in
/// `lookup_named` instead of being spelled out 34 times.
const NAMED: &[(&str, u16, bool)] = &[
	("backspace", 0x08, false), ("tab", 0x09, false),
	("enter", 0x0D, false), ("return", 0x0D, false),
	("shift", 0x10, false), ("ctrl", 0x11, false), ("control", 0x11, false),
	("alt", 0x12, false),
	("capslock", 0x14, false),
	("esc", 0x1B, false), ("escape", 0x1B, false),
	("space", 0x20, false),
	("pageup", 0x21, true), ("pgup", 0x21, true),
	("pagedown", 0x22, true), ("pgdn", 0x22, true),
	("end", 0x23, true), ("home", 0x24, true),
	("left", 0x25, true), ("up", 0x26, true), ("right", 0x27, true), ("down", 0x28, true),
	("printscreen", 0x2C, true), ("prtsc", 0x2C, true),
	("insert", 0x2D, true), ("ins", 0x2D, true),
	("delete", 0x2E, true), ("del", 0x2E, true),
	("win", 0x5B, true), ("super", 0x5B, true), ("meta", 0x5B, true),
	("lwin", 0x5B, true), ("rwin", 0x5C, true), ("apps", 0x5D, true),
	("numlock", 0x90, true), ("scrolllock", 0x91, true),
	("minus", 0xBD, false), ("equals", 0xBB, false),
	("lbracket", 0xDB, false), ("rbracket", 0xDD, false),
	("backslash", 0xDC, false), ("semicolon", 0xBA, false),
	("apostrophe", 0xDE, false), ("grave", 0xC0, false),
	("comma", 0xBC, false), ("period", 0xBE, false), ("slash", 0xBF, false),
	("add", 0x6B, false), ("subtract", 0x6D, false), ("multiply", 0x6A, false),
	("divide", 0x6F, true), ("decimal", 0x6E, false),
];

/// ASCII punctuation -> VK. Layout-independent, matching the letters/digits rule below.
const PUNCT: &[(char, u16)] = &[
	('-', 0xBD), ('=', 0xBB), ('[', 0xDB), (']', 0xDD), ('\\', 0xDC), (';', 0xBA),
	('\'', 0xDE), ('`', 0xC0), (',', 0xBC), ('.', 0xBE), ('/', 0xBF),
	('+', 0xBB), // needs shift; the caller adds it via ParsedKey::shift
];

fn lookup_named(name: &str) -> Option<ParsedKey> {
	for (n, vk, ext) in NAMED {
		if *n == name {
			return Some(if *ext { ParsedKey::ext(*vk) } else { ParsedKey::plain(*vk) });
		}
	}
	// F1..F24
	if let Some(rest) = name.strip_prefix('f') {
		if let Ok(n) = rest.parse::<u16>() {
			if (1..=24).contains(&n) {
				return Some(ParsedKey::plain(0x6F + n));
			}
		}
	}
	// numpad 0..9
	if let Some(rest) = name.strip_prefix("num") {
		if let Ok(n) = rest.parse::<u16>() {
			if n <= 9 {
				return Some(ParsedKey::plain(0x60 + n));
			}
		}
	}
	None
}

/// One character -> one key. ASCII letters/digits use their (layout-independent) VK;
/// uppercase additionally asks for Shift; anything else goes out as a UTF-16 unit.
fn char_to_key(c: char) -> ParsedKey {
	match c {
		'a'..='z' => ParsedKey::plain(c as u16 - 0x20),
		'A'..='Z' => ParsedKey::shifted(c as u16),
		'0'..='9' => ParsedKey::plain(c as u16),
		' ' => ParsedKey::plain(0x20),
		'\t' => ParsedKey::plain(0x09),
		'\n' | '\r' => ParsedKey::plain(0x0D),
		_ => {
			for (ch, vk) in PUNCT {
				if *ch == c {
					// The two shifted symbols get Shift; the rest are direct.
					return if c == '+' || c == '^' {
						ParsedKey::shifted(*vk)
					} else {
						ParsedKey::plain(*vk)
					};
				}
			}
			// Non-ASCII: inject the UTF-16 code unit. Surrogate pairs arrive as two units,
			// which is exactly how KEYEVENTF_UNICODE expects them.
			let mut units = [0u16; 2];
			for u in c.encode_utf16(&mut units) {
				// Only the first unit is returned; `parse_spec` handles the rest by
				// expanding into multiple ParsedKeys for astral characters.
				return ParsedKey::uni(*u);
			}
			unreachable!()
		}
	}
}

/// All UTF-16 units a character needs (2 for astral chars like emoji).
fn char_units(c: char) -> Vec<ParsedKey> {
	let mut buf = [0u16; 2];
	c.encode_utf16(&mut buf).iter().map(|u| ParsedKey::uni(*u)).collect()
}

/// Split a spec on `+`, with the trailing-`+` rule: in `"ctrl++"` the last `+` is the
/// plus KEY, not a separator. Getting this wrong is how "ctrl++" silently becomes "ctrl".
fn split_spec(spec: &str) -> Vec<String> {
	let chars: Vec<char> = spec.chars().collect();
	let mut tokens: Vec<String> = Vec::new();
	let mut cur = String::new();
	for (i, c) in chars.iter().enumerate() {
		if *c == '+' {
			if i + 1 == chars.len() {
				cur.push('+');
			} else {
				tokens.push(std::mem::take(&mut cur));
			}
		} else {
			cur.push(*c);
		}
	}
	if !cur.is_empty() {
		tokens.push(cur);
	}
	tokens
}

/// Parse `"ctrl+shift+s"` into an ordered key list (modifiers first, in written order).
pub fn parse_spec(spec: &str) -> Result<Vec<ParsedKey>, String> {
	// Check emptiness on a trimmed copy but split the RAW spec. Trimming first would turn
	// "ctrl+ " into "ctrl+", and the trailing-'+' rule would then swallow the ctrl.
	if spec.trim().is_empty() {
		return Err("按键为空".into());
	}
	let tokens = split_spec(spec);
	if tokens.is_empty() {
		return Err(format!("无法解析按键规格「{spec}」"));
	}
	let mut out: Vec<ParsedKey> = Vec::new();
	for tok in &tokens {
		// Trim each token, not just the whole spec: models write "ctrl + s".
		let t = tok.trim();
		if t.is_empty() {
			// A whitespace-only token is the space key itself -- "ctrl+ " is ctrl then space.
			// Silently dropping it would turn that chord into a bare ctrl.
			out.push(ParsedKey::plain(0x20));
			continue;
		}
		let lower = t.to_ascii_lowercase();
		if let Some(k) = lookup_named(&lower) {
			out.push(k);
			continue;
		}
		let mut it = t.chars();
		match (it.next(), it.next()) {
			(Some(c), None) if c.is_ascii() => out.push(char_to_key(c)),
			(Some(c), None) => out.extend(char_units(c)),
			_ => return Err(format!("无法识别的按键「{tok}」")),
		}
	}
	Ok(out)
}

/// Does the whole spec need Shift held across it?
pub fn needs_shift(keys: &[ParsedKey]) -> bool {
	keys.iter().any(|k| k.shift)
}

#[cfg(test)]
mod tests {
	use super::*;

	fn vk(spec: &str) -> Vec<u16> {
		parse_spec(spec).unwrap().iter().map(|k| k.vk).collect()
	}

	#[test]
	fn single_letters_map_to_uppercase_vk() {
		assert_eq!(vk("a"), vec![0x41]);
		assert_eq!(vk("z"), vec![0x5A]);
		// Uppercase asks for Shift instead of sending a different VK.
		let k = parse_spec("A").unwrap();
		assert_eq!(k[0].vk, 0x41);
		assert!(k[0].shift);
	}

	#[test]
	fn chords_keep_written_order() {
		assert_eq!(vk("ctrl+shift+s"), vec![0x11, 0x10, 0x53]);
		assert_eq!(vk("ctrl+c"), vec![0x11, 0x43]);
	}

	#[test]
	fn function_keys_are_generated_not_tabulated() {
		assert_eq!(vk("F1"), vec![0x70]);
		assert_eq!(vk("f12"), vec![0x7B]);
		assert_eq!(vk("f24"), vec![0x87]);
		// Out of range must NOT silently alias onto something else.
		assert!(parse_spec("f25").is_err());
		assert!(parse_spec("f0").is_err());
	}

	#[test]
	fn arrow_keys_are_marked_extended() {
		// Without KEYEVENTF_EXTENDEDKEY x64 sends the numpad arrow on some layouts.
		for name in ["left", "up", "right", "down", "home", "end", "del", "pgup"] {
			let k = parse_spec(name).unwrap();
			assert!(k[0].extended, "{name} must be extended");
		}
		assert!(!parse_spec("a").unwrap()[0].extended);
		assert!(!parse_spec("enter").unwrap()[0].extended);
	}

	#[test]
	fn trailing_plus_is_the_plus_key_not_a_separator() {
		// The grammar bug this file exists to prevent.
		assert_eq!(vk("ctrl++"), vec![0x11, 0xBB]);
		let k = parse_spec("ctrl++").unwrap();
		assert!(k[1].shift, "plus needs shift");
	}

	#[test]
	fn plus_in_the_middle_is_still_a_separator() {
		assert_eq!(vk("a+b"), vec![0x41, 0x42]);
	}

	#[test]
	fn digits_and_punctuation() {
		assert_eq!(vk("7"), vec![0x37]);
		assert_eq!(vk("slash"), vec![0xBF]);
		assert_eq!(vk("."), vec![0xBE]);
		assert_eq!(vk("space"), vec![0x20]);
	}

	#[test]
	fn non_ascii_becomes_unicode_units() {
		let k = parse_spec("中").unwrap();
		assert!(k[0].unicode);
		assert_eq!(k[0].scan, 0x4E2D);
		// Astral characters need two units, or the emoji arrives as mojibake.
		let e = parse_spec("😀").unwrap();
		assert_eq!(e.len(), 2);
		assert!(e.iter().all(|u| u.unicode));
	}

	#[test]
	fn empty_and_garbage_are_rejected() {
		assert!(parse_spec("").is_err());
		assert!(parse_spec("   ").is_err());
		assert!(parse_spec("ctrl+notakey").is_err());
		assert!(parse_spec("ab").is_err(), "multi-char token that is not named is a typo, not a key");
	}

	#[test]
	fn whitespace_around_tokens_is_tolerated() {
		assert_eq!(vk(" ctrl + s "), vec![0x11, 0x53]);
		// A whitespace-only token means the space KEY, not "nothing".
		assert_eq!(vk("ctrl+ "), vec![0x11, 0x20]);
	}

	#[test]
	fn needs_shift_only_when_a_character_demanded_it() {
		assert!(!needs_shift(&parse_spec("ctrl+s").unwrap()));
		assert!(needs_shift(&parse_spec("A").unwrap()));
	}
}