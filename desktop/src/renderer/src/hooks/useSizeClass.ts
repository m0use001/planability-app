/**
 * 尺寸类 hook —— 对应手机 `rememberWindowSizeClass`，但判据是**内容容器实测宽**
 * （`useContainerWidth`），不是 `screenWidthDp`/`window.innerWidth`。
 *
 * 用法：挂在页面最外层元素上，拿到 `size` 后按三档分流：
 *   const [ref, size] = useSizeClass();
 *   <div ref={ref}> {size === 'expanded' ? 两板 : 单列} </div>
 */
import { useContainerWidth } from './useContainerWidth';
import { sizeClassOf, type SizeClass } from '../lib/sizing';

export function useSizeClass() {
	const [ref, width] = useContainerWidth<HTMLDivElement>();
	const size: SizeClass = sizeClassOf(width);
	return [ref, size, width] as const;
}
