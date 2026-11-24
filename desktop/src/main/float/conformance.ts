/* Compile-time proof that shared/float.ts and the main-process float modules agree.
 *
 * This file exists because of an awkward but real constraint: shared/float.ts CANNOT import
 * from src/main (tsconfig.web.json only includes src/renderer/** and shared/**, so such an
 * import breaks the typecheck for every renderer file that touches FloatApi). The two sides
 * therefore declare the same shapes independently, and nothing but this file stops them from
 * drifting apart.
 *
 * If these assertions ever fail, the fix is to update BOTH declarations — not to loosen the
 * assertions. A floating window and the main window disagreeing about the draft shape is a
 * silent data-loss bug: the text simply does not appear.
 *
 * No runtime code: it compiles and is discarded.
 */

import type {
	CaptureMode as SharedCaptureMode, CaptureTarget as SharedCaptureTarget,
	DraftAttachment as SharedAttachment, DraftState as SharedDraft,
	FLOAT as SharedFloatChannels, FloatModeState as SharedModeState, PaneRole as SharedRole,
} from '../../../shared/float';

import type { CaptureMode as MainCaptureMode, CaptureTarget as MainCaptureTarget } from './capture';
import type { PaneRole as MainRole } from './layout';
import type { DraftAttachment as MainAttachment, DraftState as MainDraft, FloatModeState as MainModeState } from './state';

// Bidirectional assignability. Writing both directions catches the case where one side widened
// a field to `unknown` — assignable one way, useless the other.
type Assert<A, B> = [A] extends [B] ? ([B] extends [A] ? true : never) : never;
const _roles: Assert<SharedRole, MainRole> = true;
const _modes: Assert<SharedCaptureMode, MainCaptureMode> = true;
const _attach: Assert<SharedAttachment, MainAttachment> = true;
const _draft: Assert<SharedDraft, MainDraft> = true;
const _modeState: Assert<SharedModeState, MainModeState> = true;
const _target: Assert<SharedCaptureTarget, MainCaptureTarget> = true;

// The channel strings must be unique, or two handlers silently share a channel.
const _chan: Assert<typeof SharedFloatChannels.state, 'float:state'> = true;

// Keep the assertions referenced so noUnusedLocals cannot delete the proof.
export const CONFORMANCE = [_roles, _modes, _attach, _draft, _modeState, _target, _chan] as const;