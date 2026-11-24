package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v57 平板适配：长内容列的**无条件宽度上限**。
 *
 * 为什么无条件（不做尺寸类分支）：手机宽度永远 <840dp，`widthIn(max)` 在手机上是天然 no-op；
 * 平板上不加限宽，单列长文本会铺满 1000dp+，一行 Markdown 拉出上百字符的行长——可读性崩坏。
 * 分支只有成本没有收益，所以调用点不需要任何状态判断。
 *
 * 配套的「居中」由调用点自己加（长页在父 Box `contentAlignment = TopCenter`；
 * 不在 Box 里的用 ChatScreen 那套 `fillMaxWidth().wrapContentWidth(CenterHorizontally).widthIn(max)`——
 * **次序必须是 widthIn 在 fillMaxWidth 之前或 wrapContentWidth 放松 min 之后**：
 * `fillMaxWidth()` 在前会把 minWidth 钉死成父宽，后面的 widthIn(max) 压不住
 * （v58 实测：计划页列宽 1168dp 纹丝不动，就是这个次序错误）。
 */
fun Modifier.adaptiveColumn(maxWidth: Dp = 840.dp): Modifier =
    widthIn(max = maxWidth).fillMaxWidth()
