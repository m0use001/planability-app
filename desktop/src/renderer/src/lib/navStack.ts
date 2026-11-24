/**
 * 全局层级栈 —— 直译手机 `app/NavState.kt` + `NavController`（spec §4.1/§4.2）。
 *
 * 每个一级 tab 一条栈；栈深 = 唯一深度真源，`DeckHost` 把「根两板 + 栈内每级」
 * 拼成横向纸条（deckSlot 几何，P0 已交付）。语义（与手机逐字同源）：
 *  - push 入栈（外部状态变化即触发抽动）；
 *  - pop 返回一级（Esc/返回钮；宿主负责带动画——纸条滑出路上内容不蒸由页面 lastRef 兜）；
 *  - popToRoot 清空（切 tab/深链重置，瞬删不播动画）；
 *  - popTo(depth) 跳层（面包屑；**瞬删——连续多次动画 pop 会互相打架**，手机文件库踩过）。
 *
 * 消费方：一级 tab 的「列表→详情类二级页」钻入（Home 子页/我的子页等，待 PC 有对应实体时接入）；
 * Hub 双板（记忆库/自动化/模型枢纽）是常驻 SplitBoards，不走本栈（spec §5）。
 */

/** 栈里的一级：key 稳定标识（相等时不触发失效），title 给返回栏/面包屑。 */
export interface NavPane {
	key: string;
	title: string;
}

export type NavAction =
	| { type: 'push'; pane: NavPane }
	| { type: 'pop' }
	| { type: 'popToRoot' }
	| { type: 'popTo'; depth: number };

/** 纯 reducer（node 单测直钉语义；同 key 的 push 视为幂等——状态等价不重复压栈）。 */
export function navReduce(stack: NavPane[], action: NavAction): NavPane[] {
	switch (action.type) {
		case 'push':
			// 同 key 幂等：深链重复触发不叠层（手机 NavPane key 语义）
			if (stack.length > 0 && stack[stack.length - 1].key === action.pane.key) { return stack; }
			return [...stack, action.pane];
		case 'pop':
			return stack.slice(0, -1);
		case 'popToRoot':
			return [];
		case 'popTo': {
			// depth = 要保留的层数；不足不动（跳层只往浅跳，手机 popTo 同语义）
			if (action.depth < 0 || action.depth >= stack.length) { return stack; }
			return stack.slice(0, action.depth);
		}
		default:
			return stack;
	}
}

/** 由栈算 DeckHost 的目标层级：根基两板（副板0+主板1）之上每 push 一级 +1。 */
export function navLevelOf(stack: NavPane[]): number {
	return 1 + stack.length;
}
