package com.smalljelly.batterydata.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.softglass.backdrop.GlassBackdrop
import com.haooz.softglass.backdrop.GlassEffectScope
import com.haooz.softglass.backdrop.drawBackdrop
import com.haooz.softglass.backdrop.effects.blur
import com.haooz.softglass.shape.ContinuousCapsule
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底栏页签图标 */
enum class BottomTabIcon { Battery, Records }

data class BottomTab(val label: String, val icon: BottomTabIcon)

/** 胶囊高度（与 Nexio 底栏一致） */
private val BarHeight = 56.dp

/** 选中滑块高度（比胶囊矮一圈，上下各留 4dp） */
private val SelectorHeight = 48.dp
private val SelectorInset = 4.dp

/** 每格占屏比，沿用 Nexio 的「3 格占 0.63」密度 */
private const val TabWidthFraction = 0.21f

private val IconSize = 23.dp
private val LabelSize = 10.5.sp

/**
 * 底部切换栏，按 Nexio 的 LiquidBottomTabs 重做：
 *
 * - 56dp 玻璃胶囊（SoftGlass 实时采样 + 背景色 tint），每格占屏 21%；
 * - 选中滑块的左右两条边**各自一条弹簧**：向目标移动时前缘更硬、后缘更软，
 *   位移途中宽度先被拉长再收回，就是 Nexio 那种橡皮筋手感；
 * - **按住可拖拽**：按下后超过 touchSlop 即转为跟手，滑块中心贴住手指，
 *   松手吸附到最近格并切换；只点不拖则直接滑过去；
 * - 按压时整条胶囊按压缩放（1.03），切换成功给一次触感反馈。
 */
@Composable
fun BottomSwitcher(
    tabs: List<BottomTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val shape = remember { ContinuousCapsule() }
    val shapeBlock: () -> Shape = remember(shape) { { shape } }

    val glassColor = if (dark) {
        Color(0xFF242424).copy(alpha = 0.84f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.76f)
    }
    val effectsBlock: GlassEffectScope.() -> Unit = remember { { blur(12.dp.toPx()) } }
    val surfaceBlock: DrawScope.() -> Unit = remember(glassColor) { { drawRect(glassColor) } }

    // 按压态由手势自己维护（没有 clickable，interactionSource 收不到事件）
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 1.03f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "barPressScale",
    )

    // pointerInput 的 key 不变时，里面的闭包是**首次组合时**捕获的那一份，
    // 直接读 selected 会永远拿到初始值（0），于是「切到记录之后再也切不回电池」。
    // 用 rememberUpdatedState 保证手势里读到的永远是最新值。
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    BoxWithConstraints(modifier = modifier.height(BarHeight)) {
        val barWidth = maxWidth * (TabWidthFraction * tabs.size)
        val tabWidthPx = with(density) { (barWidth / tabs.size).toPx() }
        val insetPx = with(density) { SelectorInset.toPx() }
        val selectorWidthPx = tabWidthPx - insetPx * 2f

        val leftEdge = remember { Animatable(0f) }
        val rightEdge = remember { Animatable(tabWidthPx) }
        var placed by remember { mutableIntStateOf(0) }
        // 拖拽中的滑块中心（px）；null 表示没在拖
        var dragCenterPx by remember { mutableStateOf<Float?>(null) }

        val slotLeft: (Int) -> Float = { index -> index * tabWidthPx + insetPx }
        val slotRight: (Int) -> Float = { index -> (index + 1) * tabWidthPx - insetPx }

        LaunchedEffect(selected, tabWidthPx) {
            val targetLeft = slotLeft(selected)
            val targetRight = slotRight(selected)
            if (placed == 0) {
                leftEdge.snapTo(targetLeft)
                rightEdge.snapTo(targetRight)
                placed = 1
                return@LaunchedEffect
            }
            // 前缘更硬（先到）、后缘更软（后到）⇒ 途中宽度被拉长，落位后收回
            val lead = spring<Float>(dampingRatio = 0.82f, stiffness = 620f)
            val trail = spring<Float>(dampingRatio = 0.72f, stiffness = 300f)
            val movingRight = targetLeft > leftEdge.value
            coroutineScope {
                launch { rightEdge.animateTo(targetRight, if (movingRight) lead else trail) }
                launch { leftEdge.animateTo(targetLeft, if (movingRight) trail else lead) }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(barWidth)
                .height(BarHeight)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                // 手势挂在玻璃节点上：整条胶囊就是一条可拖的滑轨
                .pointerInput(tabs.size, tabWidthPx) {
                    awaitEachGesture {
                        var handled = false
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            var x = startX
                            var dragging = false
                            pressed = true
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUp()) {
                                    change.consume()
                                    break
                                }
                                x = change.position.x
                                if (!dragging && abs(x - startX) > viewConfiguration.touchSlop) {
                                    dragging = true
                                }
                                if (dragging) {
                                    // 滑块中心贴住手指，夹在滑轨内
                                    val minCenter = insetPx + selectorWidthPx / 2f
                                    val maxCenter =
                                        tabWidthPx * tabs.size - insetPx - selectorWidthPx / 2f
                                    dragCenterPx = x.coerceIn(minCenter, maxCenter)
                                }
                                change.consume()
                            }

                            pressed = false
                            val index = ((if (dragging) x else startX) / tabWidthPx)
                                .toInt()
                                .coerceIn(0, tabs.lastIndex)
                            val center = dragCenterPx
                            val selectedNow = currentSelected

                            // awaitEachGesture 是受限挂起作用域，动画必须抛到外面这个 scope 里跑
                            if (dragging && center != null) {
                                handled = true
                                scope.launch {
                                    // 先把两条边钉在手指位置（避免松手瞬间跳一下），再交给弹簧
                                    leftEdge.snapTo(center - selectorWidthPx / 2f)
                                    rightEdge.snapTo(center + selectorWidthPx / 2f)
                                    dragCenterPx = null
                                    if (index != selectedNow) {
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                        currentOnSelect(index)
                                    } else {
                                        // 拖了但没跨格：弹回当前格
                                        val lead = spring<Float>(0.82f, 620f)
                                        val trail = spring<Float>(0.72f, 300f)
                                        coroutineScope {
                                            launch { leftEdge.animateTo(slotLeft(selectedNow), trail) }
                                            launch { rightEdge.animateTo(slotRight(selectedNow), lead) }
                                        }
                                    }
                                }
                            } else if (index != selectedNow) {
                                handled = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnSelect(index)
                            }
                        } finally {
                            // 手势被取消（被上层抢走 / 页面切换）时也要清干净，
                            // 否则拖拽态残留会让滑块卡在两格之间
                            if (!handled) {
                                pressed = false
                                dragCenterPx = null
                            }
                        }
                    }
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = shapeBlock,
                    effects = effectsBlock,
                    onDrawSurface = surfaceBlock,
                ),
        ) {
            // 内容层裁成胶囊：冲过头的滑块不会露出圆角外
            Box(Modifier.matchParentSize().clip(shape)) {
                val dragCenter = dragCenterPx
                val indicatorLeft = if (dragCenter != null) {
                    dragCenter - selectorWidthPx / 2f
                } else {
                    leftEdge.value
                }
                val indicatorWidth = if (dragCenter != null) {
                    selectorWidthPx
                } else {
                    (rightEdge.value - leftEdge.value).coerceAtLeast(0f)
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(indicatorLeft.roundToInt(), insetPx.roundToInt()) }
                        .width(with(density) { indicatorWidth.toDp() })
                        .height(SelectorHeight)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (dark) {
                                Color.White.copy(alpha = 0.12f)
                            } else {
                                Color.White.copy(alpha = 0.95f)
                            },
                        ),
                )

                Row(
                    // 不要横向 padding：格子必须和滑轨等宽，图标文字才会落在滑块正中
                    // （加 padding 会让两格各往中间缩 2dp，看起来就是没居中）
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 拖拽时按"手指下方是哪一格"预高亮
                    val activeIndex = dragCenter?.let {
                        (it / tabWidthPx).toInt().coerceIn(0, tabs.lastIndex)
                    } ?: selected
                    tabs.forEachIndexed { index, tab ->
                        val active = index == activeIndex
                        val contentColor = if (active) {
                            colors.primary
                        } else {
                            colors.onSurfaceVariantSummary
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .semantics { role = Role.Tab },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            TabIcon(kind = tab.icon, color = contentColor)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = tab.label,
                                fontSize = LabelSize,
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                color = contentColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(kind: BottomTabIcon, color: Color) {
    Canvas(Modifier.size(IconSize)) {
        val w = size.width
        val h = size.height
        when (kind) {
            BottomTabIcon.Battery -> {
                val stroke = 1.7.dp.toPx()
                val bodyW = w * 0.76f
                val bodyH = h * 0.42f
                val left = w * 0.05f
                val top = (h - bodyH) / 2f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(bodyW, bodyH),
                    cornerRadius = CornerRadius(bodyH * 0.3f),
                    style = Stroke(width = stroke),
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left + bodyW + 1.2.dp.toPx(), top + bodyH * 0.3f),
                    size = Size(1.6.dp.toPx(), bodyH * 0.4f),
                    cornerRadius = CornerRadius(0.8.dp.toPx()),
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left + stroke * 1.6f, top + stroke * 1.6f),
                    size = Size(bodyW * 0.42f, bodyH - stroke * 3.2f),
                    cornerRadius = CornerRadius(bodyH * 0.12f),
                )
            }

            BottomTabIcon.Records -> {
                val dotRadius = 1.6.dp.toPx()
                val lineHeight = 2.2.dp.toPx()
                val rows = floatArrayOf(h * 0.24f, h * 0.5f, h * 0.76f)
                rows.forEach { centerY ->
                    drawCircle(
                        color = color,
                        radius = dotRadius,
                        center = Offset(w * 0.14f, centerY),
                    )
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(w * 0.36f, centerY - lineHeight / 2f),
                        size = Size(w * 0.52f, lineHeight),
                        cornerRadius = CornerRadius(lineHeight / 2f),
                    )
                }
            }
        }
    }
}
