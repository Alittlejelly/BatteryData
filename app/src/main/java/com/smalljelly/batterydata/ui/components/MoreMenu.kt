package com.smalljelly.batterydata.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.haooz.softglass.backdrop.GlassBackdrop
import com.haooz.softglass.backdrop.GlassEffectScope
import com.haooz.softglass.backdrop.drawBackdrop
import com.haooz.softglass.backdrop.effects.blur
import com.haooz.softglass.backdrop.shadow.Shadow
import com.haooz.softglass.shape.ContinuousCapsule
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ── 尺寸与曲线：与 Nexio 的 LiquidGlassDropdownMenu 保持同一套数值 ───────────── */

/** 收起态直径，同时也是容器形变的起点尺寸 */
private val MoreButtonDiameter = 42.dp

/** 展开态面板宽度与圆角 */
private val MenuPanelWidth = 200.dp
private val MenuPanelCornerRadius = 25.dp

/** 菜单项：外层 8dp 内缩（圆角块不贴边），内部 14dp / 10.5dp，正文字号 15.6 */
private val MenuItemInsetHorizontal = 8.dp
private val MenuItemInsetVertical = 10.5.dp
private val MenuItemFontSize = 15.6.sp

/** 面板内容区上下留白（高亮块的高度基数要用它） */
private val MenuContentPadding = 8.dp

/** 菜单项数量：滑选按固定两项算 */
private const val MenuItemCount = 2

/** 退出时沿收回方向的回弹位移 */
private const val ExitBounceDp = 12f

/* ── 按住 / 拖拽的形变参数：与 Nexio 的 computeDragTransform 同一套数值 ───────── */

/** 沿拖动方向拉伸的比例（玻璃控件档）。乘的是 |cos|·dx / 短边 */
private const val DragStretchRatio = 0.012f

/** 位移阻尼：实际位移 = minDim·tanh(damping·d/minDim)，所以面板只跟一点点，像被"按住了" */
private const val DragTranslationDamping = 0.032f

/** 按压缩放：固定加上 pressScaleDp 高度（按面板高度折算比例，与面板大小无关） */
private const val DragPressScaleDp = 2f

/** 纵向拉伸的参考高度系数：展开态取 0.6·短边，拉伸才不至于被"长条"比例砍掉 */
private const val DragVerticalRefScale = 0.6f

/** 长条形控件对拉伸项的比例惩罚下限，避免一边被完全压掉 */
private const val DragAspectPenaltyFloor = 0.97f

/** 按压与回弹共用一条欠阻尼弹簧：按下会"鼓"一下，松手会过冲再落回 */
private val DragPressSpec = spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)
private val DragOffsetSpec = spring(
    dampingRatio = 0.5f,
    stiffness = 300f,
    visibilityThreshold = Offset(0.001f, 0.001f),
)

/** 顶部圆形玻璃按钮直径（与 Nexio 课表页顶栏按钮一致） */
val GlassMoreButtonSize = MoreButtonDiameter

/**
 * ⚠️ drawBackdrop 的 shape / effects / onDrawSurface / layerBlock 都是按引用参与节点更新比较的：
 * lambda 每帧新建会让节点每帧 invalidateDraw、重录采样层并重跑采样 —— RenderThread 上会触发
 * native 崩溃（SIGSEGV）。所有传给 drawBackdrop 的 lambda 必须 remember 成稳定引用，
 * 动画状态只能在绘制期（layerBlock / drawBehind）读取，不能在组合期读取。
 */

/**
 * 右上角圆形玻璃按钮（三点图标），样式参考 Nexio 课表页顶栏按钮的收起态：
 * 42dp 连续圆角正圆 + SoftGlass 采样模糊 + 主题玻璃底色。
 */
@Composable
fun GlassMoreButton(
    backdrop: GlassBackdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    materialAlpha: () -> Float = { 1f },
) {
    val dark = isSystemInDarkTheme()
    GlassCircleButton(
        backdrop = backdrop,
        onClick = onClick,
        modifier = modifier,
        materialAlpha = materialAlpha,
    ) {
        MoreDots(dark = dark)
    }
}

/**
 * 顶栏圆形玻璃按钮：加号。样式与三点按钮同一套，记录页有数据时出现在三点左边。
 */
@Composable
fun GlassAddButton(
    backdrop: GlassBackdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    materialAlpha: () -> Float = { 1f },
) {
    val dark = isSystemInDarkTheme()
    val color = if (dark) Color.White else Color.Black
    GlassCircleButton(
        backdrop = backdrop,
        onClick = onClick,
        modifier = modifier,
        materialAlpha = materialAlpha,
    ) {
        Canvas(Modifier.size(17.dp)) {
            val stroke = 1.9.dp.toPx()
            val inset = stroke / 2f
            drawLine(
                color = color.copy(alpha = 0.9f),
                start = Offset(inset, size.height / 2f),
                end = Offset(size.width - inset, size.height / 2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color.copy(alpha = 0.9f),
                start = Offset(size.width / 2f, inset),
                end = Offset(size.width / 2f, size.height - inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 顶栏圆形玻璃按钮的通用版：任意 Miuix 图标，材质 / 两态 / 按压反馈与三点、加号完全一致。
 */
@Composable
fun GlassIconButton(
    backdrop: GlassBackdrop,
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    materialAlpha: () -> Float = { 1f },
) {
    val dark = isSystemInDarkTheme()
    val tint = if (dark) Color.White else Color.Black
    GlassCircleButton(
        backdrop = backdrop,
        onClick = onClick,
        modifier = modifier,
        materialAlpha = materialAlpha,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
            tint = tint.copy(alpha = 0.92f),
        )
    }
}

/**
 * 详情页左上角返回键：和顶栏按钮同一颗 42dp 玻璃圆钮，左箭头。
 * 和首页的三点按钮一样是两态：页面停在顶部时 [materialAlpha] 为 0 → 只有箭头、
 * 没有玻璃底；内容下滑后玻璃才淡入。之前这里夹了个 0.55 的保底，等于永远带着玻璃底，
 * 和记录页那两个按钮的状态对不上。
 */
@Composable
fun GlassBackButton(
    backdrop: GlassBackdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    materialAlpha: () -> Float = { 1f },
) {
    val dark = isSystemInDarkTheme()
    val tint = if (dark) Color.White else Color.Black
    GlassCircleButton(
        backdrop = backdrop,
        onClick = onClick,
        modifier = modifier,
        materialAlpha = materialAlpha,
    ) {
        Icon(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = "返回",
            modifier = Modifier
                .size(22.dp)
                .rotate(180f),
            tint = tint.copy(alpha = 0.92f),
        )
    }
}

/**
 * 42dp 玻璃圆钮的公共外壳。
 *
 * 常驻两份状态（对齐 Nexio 课表顶栏）：
 *  - 页面停在顶部：[materialAlpha] 为 0，只有图标，没有玻璃底、没有阴影；
 *  - 内容往下滚：玻璃和阴影一起淡入。
 * 所以这里玻璃是独立一层，图标在它外面 —— 材质淡出时图标不能跟着消失。
 */
@Composable
private fun GlassCircleButton(
    backdrop: GlassBackdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    materialAlpha: () -> Float,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val buttonShape = remember { ContinuousCapsule() }
    val shapeBlock: () -> Shape = remember(buttonShape) { { buttonShape } }
    val effectsBlock: GlassEffectScope.() -> Unit = remember { { blur(8.dp.toPx()) } }
    val containerColor = if (dark) {
        Color(0xFF242424).copy(alpha = 0.84f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.76f)
    }
    val surfaceBlock: DrawScope.() -> Unit = remember(containerColor) { { drawRect(containerColor) } }

    // 材质透明度在绘制期读（不参与组合），lambda 必须是稳定引用：
    // drawBackdrop 的 layerBlock / onDrawSurface 按引用比较，每帧新建会重录采样层
    val currentAlpha by rememberUpdatedState(materialAlpha)

    // 按住 / 拖拽形变：与展开面板同一套 Nexio 参数（computeDragTransform）
    val btnScope = rememberCoroutineScope()
    val btnPress = remember { Animatable(0f) }
    val btnSpring = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var btnDragRaw by remember { mutableStateOf(Offset.Zero) }
    var btnDragging by remember { mutableStateOf(false) }

    // 按住时即使页面在顶部（materialAlpha=0）也要把玻璃底顶出来——
    // Nexio 的按钮按住会显出材质，和展开面板的按压缩放是同一套反馈。
    val level: () -> Float = remember {
        { maxOf(currentAlpha(), btnPress.value).coerceIn(0f, 1f) }
    }
    val glassLayer: GraphicsLayerScope.() -> Unit = remember { { alpha = level() } }

    Box(
        modifier = modifier
            .size(MoreButtonDiameter)
            .graphicsLayer {
                clip = false
                val press = btnPress.value.coerceAtLeast(0f)
                val off = if (btnDragging) btnDragRaw else btnSpring.value
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val minDim = minOf(w, h).coerceAtLeast(1f)
                val pressScale = 1f + DragPressScaleDp.dp.toPx() / h * press
                val angle = atan2(off.y, off.x)
                val penaltyX = (w / h).coerceAtMost(DragAspectPenaltyFloor)
                val penaltyY = (h / w).coerceAtMost(DragAspectPenaltyFloor)
                scaleX = pressScale + DragStretchRatio * abs(cos(angle) * off.x / minDim) * penaltyX
                scaleY = pressScale + DragStretchRatio * abs(sin(angle) * off.y / minDim) * penaltyY
                translationX = minDim * tanh(DragTranslationDamping * off.x / minDim)
                translationY = minDim * tanh(DragTranslationDamping * off.y / minDim)
            }
            // 纯观察手势：不消费事件，clickable 照常收到点击
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    btnDragRaw = Offset.Zero
                    btnDragging = true
                    btnScope.launch {
                        btnSpring.stop()
                        btnPress.animateTo(1f, DragPressSpec)
                    }
                    var pointer = down.id
                    var axisLocked = false
                    var lockHorizontal = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer }
                            ?: event.changes.firstOrNull { it.pressed }
                        if (change == null || !change.pressed) break
                        pointer = change.id
                        btnDragRaw = change.position - down.position

                        // 按住按钮横向拖动时，锁定为横向并消费事件，阻止外层 Pager 翻页。
                        // 纵向小手抖不锁，clickable 照常能触发点击。
                        if (!axisLocked) {
                            val dx = abs(btnDragRaw.x)
                            val dy = abs(btnDragRaw.y)
                            if (maxOf(dx, dy) > viewConfiguration.touchSlop) {
                                axisLocked = true
                                lockHorizontal = dx > dy
                            }
                        }
                        if (lockHorizontal) change.consume()
                    }
                    val released = btnDragRaw
                    btnScope.launch { btnPress.animateTo(0f, DragPressSpec) }
                    btnScope.launch {
                        btnSpring.snapTo(released)
                        btnDragging = false
                        btnSpring.animateTo(Offset.Zero, DragOffsetSpec)
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shadow = null,
                    shape = shapeBlock,
                    effects = effectsBlock,
                    layerBlock = glassLayer,
                    onDrawSurface = surfaceBlock,
                ),
        )
        // 边缘光：玻璃和背景都是浅色时，只有一圈极细的高光才把圆"立"起来。
        // 不再画外投影——Nexio 那颗按钮几乎没有阴影，之前那圈径向柔光会变成灰色光晕。
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    val a = level()
                    if (a <= 0.004f) return@drawWithContent
                    drawCircle(
                        color = Color.White.copy(alpha = (if (dark) 0.12f else 0.85f) * a),
                        radius = size.minDimension / 2f - 0.5.dp.toPx(),
                        style = Stroke(1.dp.toPx()),
                    )
                },
        )
        // 图标常亮：材质的显隐不能带走它
        content()
    }
}

/** 竖排三点图标 */
@Composable
private fun MoreDots(dark: Boolean) {
    val color = if (dark) Color.White else Color.Black
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.8.dp),
    ) {
        repeat(3) {
            Box(
                Modifier
                    .size(4.6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.88f)),
            )
        }
    }
}

/**
 * 顶栏「更多」菜单：按钮与面板是**同一个容器**（形变），fraction = 0 时是 42dp 玻璃圆
 * （圆角 25dp 被自动钳成正圆），= 1 时长成 200dp × 内容高度的圆角面板。
 *
 * 与 Nexio 对齐的四个关键点：
 * 1. 两条进度：`fraction` 管尺寸/缩放（展开 spring(0.77, 220)，收起 spring(0.85, 650)），
 *    `originProgress` 管锚点从右上角向面板中心迁移（展开 spring(0.77, 420)，收起 tween 340ms
 *    的 cubic-bezier(0,0,0,1)），迁移比放大更快，所以是"先移向面板中心再长大"。
 * 2. 锚点补偿：translation = -PanelWidth·k / +PanelHeight·k，k = 0.5·p·(1-s)，
 *    保证形变过程中视觉中心平滑过渡，不会突然跳。
 * 3. 退出回弹：独立的欠阻尼 spring(0.52, 220) 从 1 掉到 0，取越过 0 的部分乘 12dp，
 *    沿收回方向（右上）先冲过头再弹回。
 * 4. 内容：按 width/PanelWidth 等比缩放，alpha 在 f∈[0.3, 0.7] 淡入，中途叠 6dp 模糊
 *    （清晰 → 模糊 → 清晰），所以文字是被"撑出来"的，不是干巴巴地出现。
 */
@Composable
fun MoreMenuOverlay(
    show: Boolean,
    anchorRightPx: Float,
    anchorTopPx: Float,
    pageBackdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onExitFinished: () -> Unit,
    onAbout: () -> Unit,
    onManualCapacity: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val panelWidthPx = with(density) { MenuPanelWidth.toPx() }
    val scope = rememberCoroutineScope()

    // 三条独立进度：尺寸 / 锚点迁移 / 退出回弹
    val fraction = remember { Animatable(0f) }
    val originProgress = remember { Animatable(0f) }
    val settleBounce = remember { Animatable(1f) }
    // 按住 / 拖拽：按压进度走 Animatable（要过冲），位移跟手走普通 state
    val pressProgress = remember { Animatable(0f) }
    val dragSpring = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    // 跟手原始位移。不用 Animatable.snapTo —— 每帧 launch 一次会堆积协程
    var dragRaw by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    // 手指当前落在第几项上（-1 = 不在任何一项上）：驱动滑选高亮
    var pressIndex by remember { mutableIntStateOf(-1) }
    // 最后一次落在某一项上的下标：滑出面板时高亮停在原地淡出，而不是被拉回第一项
    var highlightIndex by remember { mutableIntStateOf(0) }
    // 滑动手势已经消费了这次点击：防止滑回原项时 clickable 再触发一次
    var slideConsumedClick by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val currentOnAbout by rememberUpdatedState(onAbout)
    val currentOnManualCapacity by rememberUpdatedState(onManualCapacity)
    val currentOnExitFinished by rememberUpdatedState(onExitFinished)
    // 展开过才播退出动画；首帧直接归位
    var hasOpened by remember { mutableIntStateOf(0) }

    LaunchedEffect(show) {
        if (show) {
            hasOpened = 1
            launch {
                fraction.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.77f,
                        stiffness = 220f,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
            launch {
                originProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.77f,
                        stiffness = 420f,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
            settleBounce.snapTo(1f)
        } else if (hasOpened == 0) {
            fraction.snapTo(0f)
            originProgress.snapTo(0f)
            settleBounce.snapTo(0f)
        } else {
            val exitEasing = CubicBezierEasing(0f, 0f, 0f, 1f)
            launch {
                fraction.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.85f,
                        stiffness = 650f,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
            launch {
                settleBounce.snapTo(1f)
                settleBounce.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.52f,
                        stiffness = 220f,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
            // 等锚点回落跑完再收尾（不能立刻 snapTo，否则会取消上面两条动画）
            originProgress.animateTo(0f, tween(340, easing = exitEasing))
            fraction.snapTo(0f)
            originProgress.snapTo(0f)
            currentOnExitFinished()
        }
    }

    // 读 fraction 驱动尺寸；不钳到 1，保留 spring 末尾的过冲
    val f = fraction.value
    // 面板自然高度：内容按 200dp 全宽测一次
    var panelHeightPx by remember { mutableIntStateOf(0) }
    val targetHeight = if (panelHeightPx > 0) {
        with(density) { panelHeightPx.toDp() }
    } else {
        MoreButtonDiameter
    }
    val width = lerp(MoreButtonDiameter, MenuPanelWidth, f)
    val height = lerp(MoreButtonDiameter, targetHeight, f)
    val contentScale = (width.value / MenuPanelWidth.value).coerceAtLeast(0f)
    // 内容模糊：0 → 6dp → 0，中途最糊
    val contentBlur = 6.dp * (1f - abs(2f * f - 1f)).coerceIn(0f, 1f)

    // 模糊按 8 档量化：effects 只在档位变化时换引用，避免每帧重录采样层
    val blurStep = (f.coerceIn(0f, 1f) * 8f).roundToInt()
    val blurDp = 8f + 6f * (blurStep / 8f)

    val containerColor = if (dark) {
        Color(0xFF242424).copy(alpha = 0.84f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.76f)
    }
    val textColor = if (dark) Color(0xFFE8E4DE) else Color(0xFF1A1A1A)
    val colors = MiuixTheme.colorScheme

    // 稳定引用（见文件头注释）：只在档位变化时重建
    val panelShape = remember { ContinuousRoundedRectangle(MenuPanelCornerRadius) }
    val shapeBlock: () -> Shape = remember(panelShape) { { panelShape } }
    val effectsBlock: GlassEffectScope.() -> Unit = remember(blurStep) {
        { blur(blurDp.dp.toPx()) }
    }
    val surfaceBlock: DrawScope.() -> Unit = remember(containerColor) { { drawRect(containerColor) } }

    Box(Modifier.fillMaxSize()) {
        // 遮罩只要点击，不要颜色：Nexio 的菜单展开时页面不变暗，
        // 面板靠自身玻璃和一圈很淡的投影跟背景分开
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                },
        )

        // 形变容器：右上角钉在按钮的右上角，尺寸在 42dp ↔ 200dp 之间插值
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        x = (anchorRightPx - width.toPx()).roundToInt(),
                        y = anchorTopPx.roundToInt(),
                    )
                }
                .graphicsLayer {
                    // 锚点迁移补偿：右上角 → 正中心
                    val p = originProgress.value
                    val s = size.width / panelWidthPx
                    val k = 0.5f * p * (1f - s)
                    // 退出回弹：越过 0 的部分，沿收回方向（右上）
                    val pulse = (-settleBounce.value).coerceAtLeast(0f)
                    val bouncePx = pulse * ExitBounceDp.dp.toPx()

                    // 按住 / 拖动形变，公式照抄 Nexio 的 computeDragTransform：
                    // 按压缩放 + 沿拖动方向拉伸 + tanh 阻尼位移，三层叠在一起才有"整块被按住"的手感。
                    val press = pressProgress.value.coerceAtLeast(0f)
                    val off = if (dragging) dragRaw else dragSpring.value
                    val w = size.width.coerceAtLeast(1f)
                    val h = size.height.coerceAtLeast(1f)
                    val minDim = minOf(w, h).coerceAtLeast(1f)
                    val pressScale = 1f + DragPressScaleDp.dp.toPx() / h * press
                    val refY = minDim * (1f - (1f - DragVerticalRefScale) * fraction.value.coerceIn(0f, 1f))
                    val penaltyX = (w / h).coerceAtMost(DragAspectPenaltyFloor)
                    val penaltyY = (h / w).coerceAtMost(DragAspectPenaltyFloor)
                    val angle = atan2(off.y, off.x)
                    scaleX = pressScale +
                        DragStretchRatio * abs(cos(angle) * off.x / minDim) * penaltyX
                    scaleY = pressScale +
                        DragStretchRatio * abs(sin(angle) * off.y / refY.coerceAtLeast(1f)) * penaltyY
                    translationX = -panelWidthPx * k + bouncePx +
                        minDim * tanh(DragTranslationDamping * off.x / minDim)
                    translationY = targetHeight.toPx() * k - bouncePx +
                        minDim * tanh(DragTranslationDamping * off.y / minDim)
                }
                    .size(width = width, height = height)
                    // 按住 / 拖动：纯观察手势，不消费任何事件，菜单项的点击与滑选都不受影响
                    .pointerInput(Unit) {
                        val padPx = MenuContentPadding.toPx()
                        // 手指的 y 落在第几项上（面板局部坐标，两项等高）
                        fun indexAt(y: Float): Int {
                            val h = (size.height - padPx * 2f) / MenuItemCount
                            if (h <= 1f) return -1
                            val i = ((y - padPx) / h).toInt()
                            return if (i in 0 until MenuItemCount) i else -1
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            slideConsumedClick = false
                            val startIndex = indexAt(down.position.y)
                            pressIndex = startIndex
                            if (startIndex >= 0) highlightIndex = startIndex
                            dragRaw = Offset.Zero
                            dragging = true
                            // awaitEachGesture 是受限挂起作用域，动画得抛到外面跑
                            scope.launch {
                                dragSpring.stop()
                                pressProgress.animateTo(1f, DragPressSpec)
                            }
                            var pointer = down.id
                            var maxDist = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointer }
                                    ?: event.changes.firstOrNull { it.pressed }
                                if (change == null || !change.pressed) break
                                pointer = change.id
                                dragRaw = change.position - down.position
                                maxDist = maxOf(maxDist, dragRaw.getDistance())
                                // 滑到哪一项，高亮就挪到哪一项；换项时给一下震动反馈
                                val index = indexAt(change.position.y)
                                if (index >= 0 && index != highlightIndex) {
                                    highlightIndex = index
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove,
                                    )
                                }
                                pressIndex = index
                            }
                            val released = dragRaw
                            pressIndex = -1
                            val moved = maxDist > viewConfiguration.touchSlop
                            // 松手点是否还落在面板里：只有仍然在面板内才算"滑选"。
                            // 以前只看 highlightIndex（最后停在的有效项），手指滑出面板后它
                            // 仍停在原来那一项上 —— 于是"滑出去再松手"等于点了那一项。
                            val endPos = down.position + released
                            val releasedInside = endPos.x >= 0f && endPos.y >= 0f &&
                                endPos.x <= size.width.toFloat() && endPos.y <= size.height.toFloat()
                            // 松手：形变回弹到原位。按下点不回弹，所以是"弹回来"而不是"缩回去"
                            scope.launch { pressProgress.animateTo(0f, DragPressSpec) }
                            scope.launch {
                                dragSpring.snapTo(released)
                                dragging = false
                                dragSpring.animateTo(Offset.Zero, DragOffsetSpec)
                            }
                            // 滑动松手时在这里触发：用 highlightIndex（最后停留的项），
                            // 不用 pressIndex —— 后者在松手那一帧常是 -1（手指压在两项间隙或
                            // 面板 padding 上）。短按（未超过 touchSlop）不走这里，
                            // 由 MoreMenuItem 自己的 clickable 处理，两条路径不会重复触发。
                            // 但前提是松手点仍在面板内：滑出面板松手应当什么都不触发。
                            if (moved && releasedInside && highlightIndex >= 0) {
                                slideConsumedClick = true
                                when (highlightIndex) {
                                    0 -> currentOnAbout()
                                    1 -> currentOnManualCapacity()
                                }
                            }
                        }
                    }
                    // 真实的柔和投影（Nexio 那种很淡的一圈）；随展开进度长出来
                    .shadow(
                        elevation = (10f * f.coerceIn(0f, 1f)).dp,
                        shape = panelShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.06f),
                        spotColor = Color.Black.copy(alpha = 0.08f),
                    )
                    .drawBackdrop(
                        backdrop = pageBackdrop,
                        shadow = null,
                        shape = shapeBlock,
                        effects = effectsBlock,
                        onDrawSurface = surfaceBlock,
                    ),
            ) {
                // 内容层：与玻璃平级，不吃材质透明度
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(panelShape),
                ) {
                    // 高亮块和内容包在一起：跟着内容一起缩放/淡入，位置才对得上
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .requiredWidth(MenuPanelWidth)
                            .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                            .onSizeChanged { panelHeightPx = it.height }
                            .graphicsLayer {
                                // f < 0.3 全隐，f > 0.7 全显
                                alpha = ((f - 0.3f) / 0.4f).coerceIn(0f, 1f)
                                scaleX = contentScale
                                scaleY = contentScale
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                            .blur(contentBlur)
                            .padding(vertical = MenuContentPadding),
                    ) {
                        val padPx = with(density) { MenuContentPadding.toPx() }
                        val itemHeightPx = ((panelHeightPx - padPx * 2f) / MenuItemCount)
                            .coerceAtLeast(1f)
                        // 滑选高亮：跟着手指落在哪一项上滑动（Nexio 的 Sliding mask 同款）。
                        // 位置只看"最后一次落在的有效项"，滑出面板时它不再移动，只淡出 ——
                        // 之前 target 会退回 0，看起来像高亮自己跑回第一项。
                        val highlightY by animateFloatAsState(
                            targetValue = itemHeightPx * highlightIndex,
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 700f),
                            label = "menuHighlightY",
                        )
                        val highlightAlpha by animateFloatAsState(
                            targetValue = if (pressIndex >= 0) 1f else 0f,
                            animationSpec = tween(durationMillis = 140),
                            label = "menuHighlightAlpha",
                        )
                        if (highlightAlpha > 0.01f) {
                            Box(
                                Modifier
                                    .offset {
                                        androidx.compose.ui.unit.IntOffset(
                                            0,
                                            highlightY.roundToInt(),
                                        )
                                    }
                                    .fillMaxWidth()
                                    .padding(horizontal = MenuItemInsetHorizontal)
                                    .height(with(density) { itemHeightPx.toDp() })
                                    .clip(RoundedCornerShape(17.dp))
                                    .background(
                                        colors.secondaryContainer.copy(alpha = highlightAlpha),
                                    ),
                            )
                        }
                        Column(Modifier.fillMaxWidth()) {
                        MoreMenuItem(
                            text = "关于",
                            textColor = textColor,
                            icon = { InfoGlyph(textColor) },
                            onClick = { if (!slideConsumedClick) onAbout() },
                        )
                        MoreMenuItem(
                            text = "手动设置电池容量",
                            textColor = textColor,
                            icon = { BatteryGlyph(textColor) },
                            onClick = { if (!slideConsumedClick) onManualCapacity() },
                        )
                        }
                    }
                }

                // 收起态的三点：向后缩回右上角深处并淡出；退出时更早出现
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(MoreButtonDiameter)
                        .graphicsLayer {
                            val iconK = if (show) 2.5f else 1.7f
                            val p = (fraction.value * iconK).coerceIn(0f, 1f)
                            alpha = 1f - p
                            translationX = p * (MoreButtonDiameter.toPx() / 2f - width.toPx() / 2f)
                            translationY = p * (height.toPx() / 2f - MoreButtonDiameter.toPx() / 2f)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MoreDots(dark = dark)
                }
        }
    }
}

@Composable
private fun MoreMenuItem(
    text: String,
    textColor: Color,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    // 按下高亮由外层的滑选块负责画，这里不再自己叠一层背景
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(
                horizontal = MenuItemInsetHorizontal + 14.dp,
                vertical = MenuItemInsetVertical,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                fontSize = MenuItemFontSize,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

/** 「关于」的图标：一个圈里一个 i */
@Composable
private fun InfoGlyph(color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val stroke = 1.6.dp.toPx()
        drawCircle(
            color = color,
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(stroke),
        )
        drawCircle(
            color = color,
            radius = 1.1.dp.toPx(),
            center = Offset(size.width / 2f, size.height * 0.33f),
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2f, size.height * 0.46f),
            end = Offset(size.width / 2f, size.height * 0.70f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 「手动设置电池容量」的图标：一节电池 */
@Composable
private fun BatteryGlyph(color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val stroke = 1.6.dp.toPx()
        val bodyW = size.width * 0.8f
        val bodyH = size.height * 0.46f
        val top = (size.height - bodyH) / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, top),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyW + 1.2.dp.toPx(), top + bodyH * 0.3f),
            size = Size(1.8.dp.toPx(), bodyH * 0.4f),
            cornerRadius = CornerRadius(0.9.dp.toPx()),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke * 1.8f, top + stroke * 1.8f),
            size = Size(bodyW * 0.42f, bodyH - stroke * 3.6f),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
        )
    }
}
