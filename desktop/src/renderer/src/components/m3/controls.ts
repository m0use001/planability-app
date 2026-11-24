/**
 * @material/web → React 18 包装层 · controls 族（方案 E′）。
 * 按族拆文件的原因：wrapped.ts 单文件出口会让任何页面 import 时把全部 38 个自定义元素
 * （含 Lit 运行时与样式，模块级副作用无法 tree-shake）拖进该 chunk——Chat 页实测
 * 344KB→950KB。页面只从用到的族导入。
 * 公共约定见 ./core.ts（React 18 布尔属性陷阱、事件映射规范）。
 */
import { opts } from './core';
import { MdSwitch } from '@material/web/switch/switch.js';
import { MdCheckbox } from '@material/web/checkbox/checkbox.js';
import { MdRadio } from '@material/web/radio/radio.js';
import { MdSlider } from '@material/web/slider/slider.js';

export const SwitchM3 = opts('md-switch', MdSwitch, { onChange: 'change' });
export const CheckboxM3 = opts('md-checkbox', MdCheckbox, { onChange: 'change' });
export const RadioM3 = opts('md-radio', MdRadio, { onChange: 'change' });
export const SliderM3 = opts('md-slider', MdSlider, { onChange: 'change', onInput: 'input' });
