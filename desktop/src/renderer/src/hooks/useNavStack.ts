/**
 * 层级栈 hook —— `lib/navStack.ts` reducer 的 React 包装（spec §4.1）。
 *
 * 用法（未来 Home/我的子页钻入接入时）：
 *   const nav = useNavStack('home');
 *   nav.push({ key: 'score', title: '专注统计' });
 *   // Esc/返回钮 → nav.pop()；面包屑点第 n 层 → nav.popTo(n)；切 tab → nav.popToRoot()
 */
import { useCallback, useState } from 'react';
import { navReduce, navLevelOf, type NavPane } from '../lib/navStack';

export function useNavStack(tab: string) {
	const [stack, setStack] = useState<NavPane[]>([]);
	const push = useCallback((pane: NavPane) => setStack(s => navReduce(s, { type: 'push', pane })), []);
	const pop = useCallback(() => setStack(s => navReduce(s, { type: 'pop' })), []);
	const popToRoot = useCallback(() => setStack(s => navReduce(s, { type: 'popToRoot' })), []);
	const popTo = useCallback((depth: number) => setStack(s => navReduce(s, { type: 'popTo', depth })), []);
	return { tab, stack, depth: stack.length, level: navLevelOf(stack), push, pop, popToRoot, popTo };
}
