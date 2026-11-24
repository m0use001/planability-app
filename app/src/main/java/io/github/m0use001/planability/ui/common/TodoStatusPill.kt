package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * v46.2：待办完成度**状态胶囊**（用户拍板：「直接参考手机的电池图标」）。
 *
 * 三态，与手机电池图标同语言：
 * - 没任何完成（未启用百分比/0%）→ 空胶囊轮廓（电量 0）
 * - 百分比完成 1..99 → 胶囊里嵌跑道进度条（浅色底轨 + 实色填充到对应宽度），**不打勾**
 * - 100% → **填充满 + 勾**（用户点名：实心胶囊里打勾；2026-11-14 再定「100% 才能打勾」）
 *
 * 未开百分比的待办没有"电量"可言，退回 `done` → 实心 + 勾。
 * 打卡交互不在这里：时间轴轻点块本身、计划页胶囊可点——调用方决定。
 */
@Composable
fun TodoStatusPill(
    done: Boolean,
    percentDone: Int,
    tint: Color,
    modifier: Modifier = Modifier
) {
    // v49：三态判定与填充比例**全部走 TodoProgress**（唯一裁决处，在 data 层）。
    // 此前这两行内联在这里，是「打卡后锁死 100%」「100→0 无变化」「50% 填充不足一半」
    // 三个 bug 的共同温床——逻辑在 composable 里，测试够不到也枚举不全。
    // 规则与穷举断言见 data/TodoProgress.kt + TodoProgressTest；SingleSourceTest 挡住复制。
    //
    // 2026-11-14 用户定稿「100% 才能打勾」：**两个信号分家**——
    // 勾 = 满（percent 到 100；没开百分比时看 done），填充 = 进度（percent，打卡不改它）。
    // 80% 打卡 → 八成电池、不打勾；100% → 满格 + 勾。
    val showCheck = io.github.m0use001.planability.data.TodoProgress.showsCheck(done = done, percentDone = percentDone)
    val showSolidChecked = io.github.m0use001.planability.data.TodoProgress.showsSolidChecked(done = done, percentDone = percentDone)
    val showProgressFill = io.github.m0use001.planability.data.TodoProgress.showsProgressFill(percentDone)
    Box(
        modifier
            // v57 批 2b（B 类）：三态翻转（空→进度→完成、完成→撤销）胶囊抖一下——
            // 这颗胶囊是待办状态的"电池图标"，翻转值得被看见。首组不抖由 wobbleOn 的
            // seen 门闩兜住；打卡型勾选的反馈仍以触感为主（调用点 Select），抖是叠加修饰。
            // 注意：计划原写的「宽度弹簧」不成立——胶囊尺寸固定 20×13dp 不随状态变，
            // 内部进度填充本来就走 Motion.quick() 弹簧（下方 animateFloatAsState）。
            .wobbleOn(trigger = if (showCheck) 2 else if (percentDone > 0) 1 else 0, scaleTo = 1.12f)
            .size(width = 20.dp, height = 13.dp)
            // 描边只在**没有勾**时画：border 的绘制在内容**之上**，有勾时留着会把挖出来的勾
            // 盖住（用户实测「勾被描边盖住」）。100% 满格也算「有勾」——它的填充已铺满，
            // 描边同样只剩副作用。部分进度（80% 打卡）没有勾，仍要这圈"电池外框"。
            .then(if (showCheck) Modifier else Modifier.border(1.5.dp, tint, RoundedCornerShape(50))),
        contentAlignment = Alignment.Center
    ) {
        if (showSolidChecked) {
            // 完全完成 = 实心胶囊上**挖孔勾**（用户：「挖孔+直角边缘的勾」）：
            // 用 Offscreen 合成层 + BlendMode.Clear 真把勾从实心胶囊里"镂空"——
            // 勾的颜色就是胶囊背后（时间轴块底/计划页卡片底）的原色，任何底色都不用再算对比色。
            // v47：打卡瞬间整颗淡入（只在进入组合时播一次），不是硬切。
            val enterAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                enterAlpha.animateTo(1f, io.github.m0use001.planability.ui.common.Motion.quick())
            }
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = enterAlpha.value
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(50))
                        .background(tint)
                )
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { punchCheckHole() }
            }
        } else if (showProgressFill) {
            // 电池式：整个胶囊内填充（浅底轨铺满内部 + 实色按比例从左填到右）。
            // 填充宽走弹簧动画——拖滑条时跟手连续变化，不跳格。
            // 比例的取法见 TodoProgress.fillFraction：「满」唯一等价于 100%。
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(50))
                    // 有勾（100%）时勾要和填充同层，才能用 BlendMode.Clear 把勾从
                    // 「轨道 + 填充」上真正挖出来——Clear 只作用于同一个合成层，
                    // 所以这两者必须整体进 Offscreen 层。（fillFraction(100)==1f，
                    // 勾挖出的孔落在满格填充上，视觉与实心态一致。）
                    .graphicsLayer {
                        if (showCheck) compositingStrategy = CompositingStrategy.Offscreen
                    }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(tint.copy(alpha = 0.15f))
                )
                val target = io.github.m0use001.planability.data.TodoProgress.fillFraction(percentDone)
                val frac by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = target,
                    animationSpec = io.github.m0use001.planability.ui.common.Motion.quick()
                )
                Box(
                    // 纯几何：frac 已经在 TodoProgress 里做过 0.96 的按比例缩短，
                    // **不要再减任何固定 padding**——那正是 v48.7 的错（50% 被减成三成）。
                    Modifier
                        .fillMaxWidth(frac.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(tint)
                )
                // 100%（唯一有勾的非实心态）→ 勾压在满格填充上（挖孔，颜色 = 块底/卡片底）。
                if (showCheck) {
                    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { punchCheckHole() }
                }
            }
        }
    }
}

/**
 * 从当前合成层里「挖」出勾（Material Icons 的 Check 轮廓，矢量 path 原样照搬，两笔夹角 90°）。
 *
 * 必须画在 [CompositingStrategy.Offscreen] 层里，BlendMode.Clear 才能把同一层里
 * 已经画好的实心胶囊 / 轨道 + 填充"镂空"——勾的颜色就是胶囊背后（时间轴块底、
 * 计划页卡片底）的原色，任何底色都不用再算对比色。
 *
 * 用户「勾大一点」：按**字形本身**的包围盒（x 3.41..21 / y 5.59..19）缩放，让可见勾占
 * 胶囊宽约 68%、高≈满高——不再按 24 视口框缩放（那样四周留白太多显小）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.punchCheckHole() {
    val glyphW = 17.59f
    val glyphH = 13.41f
    val k = size.width * 0.68f / glyphW
    val ox = (size.width - glyphW * k) / 2f - 3.41f * k
    val oy = (size.height - glyphH * k) / 2f - 5.59f * k
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(ox + 9f * k, oy + 16.17f * k)
        lineTo(ox + 4.83f * k, oy + 12f * k)
        lineTo(ox + 3.41f * k, oy + 13.41f * k)
        lineTo(ox + 9f * k, oy + 19f * k)
        lineTo(ox + 21f * k, oy + 7f * k)
        lineTo(ox + 19.59f * k, oy + 5.59f * k)
        close()
    }
    drawPath(path, color = Color.Black, blendMode = BlendMode.Clear)
}
