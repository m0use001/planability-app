/**
 * @material/web → React 18 包装层 · fields 族（方案 E′）。
 * 按族拆文件的原因：wrapped.ts 单文件出口会让任何页面 import 时把全部 38 个自定义元素
 * （含 Lit 运行时与样式，模块级副作用无法 tree-shake）拖进该 chunk——Chat 页实测
 * 344KB→950KB。页面只从用到的族导入。
 * 公共约定见 ./core.ts（React 18 布尔属性陷阱、事件映射规范）。
 */
import { opts } from './core';
import { MdFilledTextField } from '@material/web/textfield/filled-text-field.js';
import { MdOutlinedTextField } from '@material/web/textfield/outlined-text-field.js';

export const FilledTextFieldM3 = opts('md-filled-text-field', MdFilledTextField, {
	onInput: 'input', onChange: 'change',
});
export const OutlinedTextFieldM3 = opts('md-outlined-text-field', MdOutlinedTextField, {
	onInput: 'input', onChange: 'change',
});
