/**
 * @material/web → React 18 包装层 · chips 族（方案 E′）。
 * 按族拆文件的原因：wrapped.ts 单文件出口会让任何页面 import 时把全部 38 个自定义元素
 * （含 Lit 运行时与样式，模块级副作用无法 tree-shake）拖进该 chunk——Chat 页实测
 * 344KB→950KB。页面只从用到的族导入。
 * 公共约定见 ./core.ts（React 18 布尔属性陷阱、事件映射规范）。
 */
import { opts } from './core';
import { MdAssistChip } from '@material/web/chips/assist-chip.js';
import { MdFilterChip } from '@material/web/chips/filter-chip.js';
import { MdInputChip } from '@material/web/chips/input-chip.js';
import { MdSuggestionChip } from '@material/web/chips/suggestion-chip.js';

export const AssistChipM3 = opts('md-assist-chip', MdAssistChip);
export const FilterChipM3 = opts('md-filter-chip', MdFilterChip, { onChange: 'change' });
export const InputChipM3 = opts('md-input-chip', MdInputChip);
export const SuggestionChipM3 = opts('md-suggestion-chip', MdSuggestionChip);
