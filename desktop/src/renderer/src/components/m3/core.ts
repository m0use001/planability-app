/**
 * @material/web → React 18 包装层的公共工厂（方案 E′）。
 *
 * 为什么必须有这层：React 18 对自定义元素只写**属性字符串**——`disabled={false}` 会写成
 * 属性 `disabled="false"`，Lit 端「属性存在=真」，结果是本来想禁用却变成启用（布尔陷阱）。
 * @lit/react 的 createComponent 按元素 property 读写并桥接事件，绕开整套坑。
 */
import * as React from 'react';
import { createComponent, type EventName } from '@lit/react';

// E 必须默认 {}：若签名是 Record<string,string>，不传 events 时 TS 会把 E 推成字符串索引签名，
// 组件的所有 props（value/children/…）都会被当成事件处理器（首版类型错误就是这个）。
export const opts = <I extends HTMLElement, E extends Record<string, EventName | string> = {}>(
	tagName: string, elementClass: new () => I, events?: E
) => createComponent({ react: React, tagName, elementClass, events });
