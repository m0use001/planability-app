/**
 * @material/web → React 18 包装层 · buttons 族（方案 E′）。
 * 按族拆文件的原因：wrapped.ts 单文件出口会让任何页面 import 时把全部 38 个自定义元素
 * （含 Lit 运行时与样式，模块级副作用无法 tree-shake）拖进该 chunk——Chat 页实测
 * 344KB→950KB。页面只从用到的族导入。
 * 公共约定见 ./core.ts（React 18 布尔属性陷阱、事件映射规范）。
 */
import { opts } from './core';
import { MdFilledButton } from '@material/web/button/filled-button.js';
import { MdFilledTonalButton } from '@material/web/button/filled-tonal-button.js';
import { MdOutlinedButton } from '@material/web/button/outlined-button.js';
import { MdTextButton } from '@material/web/button/text-button.js';
import { MdElevatedButton } from '@material/web/button/elevated-button.js';
import { MdIconButton } from '@material/web/iconbutton/icon-button.js';
import { MdFilledIconButton } from '@material/web/iconbutton/filled-icon-button.js';
import { MdFilledTonalIconButton } from '@material/web/iconbutton/filled-tonal-icon-button.js';
import { MdOutlinedIconButton } from '@material/web/iconbutton/outlined-icon-button.js';
import { MdFab } from '@material/web/fab/fab.js';
import { MdBrandedFab } from '@material/web/fab/branded-fab.js';

export const FilledButtonM3 = opts('md-filled-button', MdFilledButton);
export const FilledTonalButtonM3 = opts('md-filled-tonal-button', MdFilledTonalButton);
export const OutlinedButtonM3 = opts('md-outlined-button', MdOutlinedButton);
export const TextButtonM3 = opts('md-text-button', MdTextButton);
export const ElevatedButtonM3 = opts('md-elevated-button', MdElevatedButton);
export const IconButtonM3 = opts('md-icon-button', MdIconButton);
export const FilledIconButtonM3 = opts('md-filled-icon-button', MdFilledIconButton);
export const FilledTonalIconButtonM3 = opts('md-filled-tonal-icon-button', MdFilledTonalIconButton);
export const OutlinedIconButtonM3 = opts('md-outlined-icon-button', MdOutlinedIconButton);
export const FabM3 = opts('md-fab', MdFab);
export const BrandedFabM3 = opts('md-branded-fab', MdBrandedFab);
