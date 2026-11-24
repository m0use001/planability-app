import { useCallback, useEffect, useState } from 'react';
import type { CollectionName, Entity } from '@shared/types';

/** Live collection bound to the main-process store: every mutation round-trips to disk.
 *
 * G 类 MEDIUM：读失败曾与「还没有数据」同形（items 空 + loading 落地 → 页面显示空态，
 * 用户以为数据没了）。现在失败置 `error` 且**不清空已有 items**——上次成功的快照比一个
 * 谎言般的「空」有用；消费方把空态文案按 error 分叉即可。 */
export function useCollection<T extends Entity>(collection: CollectionName) {
	const [items, setItems] = useState<T[]>([]);
	const [loading, setLoading] = useState(true);
	const [error, setError] = useState(false);

	const refresh = useCallback(async () => {
		try {
			const list = await window.planability.list<T>(collection);
			list.sort((a, b) => b.updatedAt - a.updatedAt);
			setItems(list);
			setError(false);
		} catch (e) {
			console.error('load failed', e);
			setError(true);
		} finally {
			setLoading(false);
		}
	}, [collection]);

	useEffect(() => {
		void refresh();
	}, [refresh]);

	const upsert = useCallback(async (item: T): Promise<T> => {
		const rec = await window.planability.upsert(collection, item);
		await refresh();
		return rec;
	}, [collection, refresh]);

	const remove = useCallback(async (id: string): Promise<void> => {
		await window.planability.softDelete(collection, id);
		await refresh();
	}, [collection, refresh]);

	return { items, loading, error, refresh, upsert, remove };
}
