package com.smalljelly.batterydata.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 分段滑块（胶囊里一块会滑的高亮）：**点一下切换，也可以按住直接拖过去**。
 *
 * 首页实时曲线的「电流 / 电压」和详情页充电曲线的「功率 / 电流 / 电压」是同一个控件，
 * 所以抽到这里共用 —— 以前详情页那份只会响应点击，手感两边不一样。
 *
 * 交互细节：
 * - 超过 touchSlop 才算拖动，纯点击时高亮块是"滑过去"而不是"跳到手指处"；
 * - 拖动期间高亮中心贴住手指，松手弹簧吸附到选中格；
 * - 拖动全程消费事件，避免把纵向拖动漏给外层滚动容器。
 */
@Composable
fun SegmentedSlider(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    segmentWidth: Dp = 52.dp,
    segmentHeight: Dp = 28.dp,
    fontSize: TextUnit = 12.sp,
) {
    val colors = MiuixTheme.colorScheme
    val density = LocalDensity.current
    // 每格宽度直接用 dp 换算：轨道宽度就是"格数 × 格宽"，不用再量一次
    val segmentWidthPx = with(density) { segmentWidth.toPx() }
    val segmentCount = labels.size
    if (segmentCount == 0) return

    // 拖动期间触点 x 坐标（相对轨道）；null 表示未进入拖动状态
    var dragPx by remember { mutableStateOf<Float?>(null) }

    val safeIndex = selectedIndex.coerceIn(0, segmentCount - 1)

    // 拖动时指示块中心跟随手指；松手后弹簧动画吸附到选中项
    val targetFraction = dragPx?.let {
        (it / segmentWidthPx - 0.5f).coerceIn(0f, (segmentCount - 1).toFloat())
    } ?: safeIndex.toFloat()
    val indicatorFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(dampingRatio = 1f, stiffness = 420f),
        label = "segmentIndicator",
    )
    // 手指当前位置对应的段，用于拖动时实时高亮
    val hoverIndex = dragPx?.let {
        (it / segmentWidthPx).roundToInt().coerceIn(0, segmentCount - 1)
    } ?: safeIndex

    val currentSelected by rememberUpdatedState(safeIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.secondaryContainer)
            .padding(3.dp)
            .pointerInput(segmentCount, segmentWidthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val downX = down.position.x
                    var lastX = downX
                    var dragging = false
                    val slopPx = viewConfiguration.touchSlop
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) {
                            change.consume()
                            break
                        }
                        lastX = change.position.x
                        // 超过触摸 slop 才算拖动：纯点击时指示块原地做滑动动画，不跳到手指处
                        if (!dragging && abs(lastX - downX) > slopPx) {
                            dragging = true
                        }
                        if (dragging) {
                            dragPx = lastX
                        }
                        change.consume()
                    }
                    dragPx = null
                    // 纯点击取按下位置，拖动取最后位置
                    val index = ((if (dragging) lastX else downX) / segmentWidthPx)
                        .roundToInt()
                        .coerceIn(0, segmentCount - 1)
                    if (index != currentSelected) {
                        currentOnSelect(index)
                    }
                }
            },
    ) {
        // 跟手 / 吸附的指示块
        Box(
            modifier = Modifier
                .offset { IntOffset((indicatorFraction * segmentWidthPx).roundToInt(), 0) }
                .size(segmentWidth, segmentHeight)
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceContainer),
        )
        Row {
            labels.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier.size(segmentWidth, segmentHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = fontSize,
                        color = if (index == hoverIndex) {
                            colors.onSurfaceContainer
                        } else {
                            colors.onSurfaceVariantSummary
                        },
                    )
                }
            }
        }
    }
}
