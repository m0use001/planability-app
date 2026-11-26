package io.github.m0use001.planability.core

import io.github.m0use001.planability.shared.formatCn as sharedFormatCn
import io.github.m0use001.planability.shared.formatMonthDay as sharedFormatMonthDay
import io.github.m0use001.planability.shared.formatShort as sharedFormatShort
import io.github.m0use001.planability.shared.toDayKey as sharedToDayKey
import io.github.m0use001.planability.shared.toLocalDate as sharedToLocalDate
import java.time.LocalDate

/**
 * **M2 桥接**：真实现已迁入 `:shared`（jvmShared 源集，纯 java.time）。
 *
 * - `AppTime`（object）经 typealias 透明转发——全仓 79 个引用文件零改动；
 * - 顶层扩展函数（formatCn/formatShort/formatMonthDay/Long.toLocalDate/LocalDate.toDayKey）
 *   不能 typealias，这里同签名重声明、经 import 别名委托 shared 同名扩展——38 个使用文件零改动。
 * 搬迁彻底完成后删掉本文件，调用方改 import。
 */
internal typealias AppTime = io.github.m0use001.planability.shared.AppTime

fun LocalDate.formatCn(): String = sharedFormatCn()

fun LocalDate.formatShort(): String = sharedFormatShort()

fun LocalDate.formatMonthDay(): String = sharedFormatMonthDay()

fun Long.toLocalDate(): LocalDate = sharedToLocalDate()

fun LocalDate.toDayKey(): Long = sharedToDayKey()
