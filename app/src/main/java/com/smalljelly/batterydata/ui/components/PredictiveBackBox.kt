package com.smalljelly.batterydata.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.haooz.nexioui.motion.SecondaryPageExitEasing
import com.haooz.nexioui.navigationback.rememberNavigationBack
import com.smalljelly.batterydata.ui.theme.AppMotion
import kotlinx.coroutines.launch

/**
 * 手势进度持有者：**唯一真相**是 `progress`（1 = 完全盖住 / 显示，0 = 完全滑出）。
 *
 * 为什么单独抽一层：之前 [PredictiveBackBox] 自己算位移、[SlideInHost] 又算一套，
 * 松手时两边同时往不同目标写 —— 画面先被一边拉回原位、又被另一边拉出去，看着就是"闪一下"；
 * 返回后 [SlideInHost] 的下层还停在 `-0.24×宽` 的视差位置没人归零，
 * 于是"底下的界面卡在屏幕一侧"。
 *
 * 现在只有这一个 [Animatable] 决定位移，[SlideInHost] 只负责渲染，手势只负责写值。
 */
@Stable
class SlideProgress(initial: Float) {
    internal val animatable = Animatable(initial)

    val value: Float get() = animatable.value

    suspend fun snapTo(target: Float) = animatable.snapTo(target.coerceIn(0f, 1f))

    suspend fun animateTo(target: Float, durationMs: Int) = animatable.animateTo(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(
            durationMs,
            easing = SecondaryPageExitEasing,
        ),
    )
}

/** 建一份进度状态（按初始可见性给初值，避免首帧闪） */
@Composable
fun rememberSlideProgress(visible: Boolean): SlideProgress =
    remember { SlideProgress(if (visible) 1f else 0f) }

/**
 * 预见式返回**手势采集**：把系统返回手势的进度写进 [SlideProgress]。
 *
 * 不再自己做位移 —— 位移由 [SlideInHost] 按这个唯一进度渲染。
 * 之前的实现里这里也算一套 `(1 - p) × 宽度`，和宿主的位移叠加，
 * 且松手时两边同时回弹到不同目标，这就是"退出闪一下"和"底下界面卡在一边"的来源。
 *
 * @param progress 宿主的进度（[SlideInHost] 的那一份）
 * @param progress 宿主的 [SlideProgress]（唯一进度源），手势直接写它，位移由 [SlideInHost] 渲染
 * @param enabled 是否接管返回手势；一级页传 false，交给宿主/系统处理
 */
@Composable
fun PredictiveBackBox(
    onBack: () -> Unit,
    progress: SlideProgress,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val predictiveBack = AppMotion.predictiveBack
    val scope = rememberCoroutineScope()
    val back = rememberNavigationBack()

    if (enabled) {
        if (predictiveBack) {
            back.handler(
                enabled = true,
                onBackCompleted = { onBack() },
                onBackCancelled = {
                    scope.launch { progress.animateTo(1f, SLIDE_OUT_MS) }
                },
            )
        } else {
            BackHandler { onBack() }
        }
    }

    // 手势期间把进度写进 progress。
    // backProgress()：手势期间 0..1，非手势期间为 null —— 用它判断起止，
    // 与 handler 共用同一份底层 state，不会各持一份导致跟手不动。
    LaunchedEffect(back, predictiveBack, progress) {
        if (!predictiveBack) return@LaunchedEffect
        var gestureBase = 1f
        var sawGesture = false
        snapshotFlow { back.backProgress() }.collect { gesture ->
            if (gesture == null) {
                sawGesture = false
                return@collect
            }
            if (!sawGesture) {
                sawGesture = true
                // 起点取当前值：入场没播完就滑返回时，从 0.6 接着跟手，不跳变
                gestureBase = progress.value
            }
            // 拖 0 保持原位，拖满整屏 p = 0（完全滑出）
            progress.snapTo(gestureBase * (1f - gesture))
        }
    }
}