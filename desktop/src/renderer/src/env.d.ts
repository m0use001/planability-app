/** Vite/electron-vite 在构建时注入 import.meta.env；这里只声明我们真正读的字段。
 * 用它做「调试按钮不进产物」的门（G 类 MEDIUM：压测×5 曾长期挂在正式界面上）。 */
interface ImportMeta {
	readonly env: {
		/** true 于 dev serve；`npm run build` 产物里为 false。 */
		readonly DEV: boolean;
	};
}
