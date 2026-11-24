import type { DataApi, SkillsApi } from '../../shared/ipc';
import type { ChatApi } from '../../shared/chat';
import type { FloatApi } from '../../shared/float';

/**
 * The renderer's view of the bridge, declared ONCE.
 *
 * This shape used to be written out in both `preload/index.ts` (as the annotation on the object
 * that gets exposed) and `preload/index.d.ts` (as the global `Window` augmentation). Adding a
 * fourth API group meant editing two files, and the half that is easy to forget is exactly the
 * half the renderer compiles against — so the mistake surfaces as a type error in someone else's
 * build rather than as a missing method.
 *
 * Type-only module: it must never reach the bundle, and `import type` guarantees it does not.
 */
export type PlanabilityApi = DataApi & {
	chat: ChatApi;
	float: FloatApi;
	skills: SkillsApi;
};
