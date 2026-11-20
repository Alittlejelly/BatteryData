package com.smalljelly.batterydata.ui.components

import android.content.Context
import android.os.Build
import android.view.RoundedCorner
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ── 参数照抄 Nexio 的 MorphTransition（课程详情共用那套） ────────────────────── */

/** 展开时长：缩放与裁切同步，位移稍快 */
private const val MorphOpenMillis = 540
private const val TransOpenMillis = 500

/** 收起时长：320ms，比展开干脆 */
private const val MorphExitMillis = 320
private const val TransExitMillis = 320

/** 展开：前快后慢，收尾绵（easeOutQuart） */
private val MorphOpenEase = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)

/** 收起：起步快、末段缓（easeOutCubic） */
private val MorphExitEase = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/** 位移展开：比缩放更陡，让卡片先「冲」到位（easeOutQuint） */
private val MorphTransOpenEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** 位移收起：回卡片时更贴身 */
private val MorphTransExitEase = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

/** 源卡片的圆角 */
private val StartCorner = 20.dp

/** 读不到屏幕物理圆角时的兜底（直角屏就不裁，保持 0） */
private const val ScreenCornerFallbackPx = 0f

/** 背景压暗上限（Nexio 用 0.5） */
private const val BgAlphaMax = 0.5f

/** 卡片 → 全屏的一帧状态 */
private data class MorphState(
    val bgAlpha: Float,
    val snapshotAlpha: Float,
    val contentAlpha: Float,
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
    val clipBottom: Float,
    val cornerRadiusPx: Float,
)

/**
 * 卡片 ⇄ 全屏的连贯形变，参数与实现照抄 Nexio 的 `rememberMorphTransition`。
 *
 * 核心不是「把页面塞进卡片里」，而是反过来：
 * **整页先按全屏布局好，再用 `graphicsLayer` 等比缩到卡片宽度**（scale = 卡片宽 / 屏宽，
 * 缩完宽度正好等于卡片），高度方向靠 `morphClip` 裁到卡片高度。打开时 scale 从卡片比例
 * 涨到 1、裁切高度从卡片高涨到全屏，于是画面是「同一张页面在原地长大」。
 *
 * [snapshot] 是卡片的图层快照：前 1/3 行程盖在页面之上（`1 - p*3` 淡出），
 * 保证第一帧看到的就是列表里那张卡片本身，而不是页面顶部。
 */
@Composable
fun CardExpandOverlay(
    visible: Boolean,
    origin: Rect?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    /** 被点卡片的图层快照（`GraphicsLayer`），没有也能播，只是开头会露页面顶部 */
    snapshot: GraphicsLayer? = null,
    /** 可选的外部进度，方便宿主读同一条值 */
    progress: Animatable<Float, AnimationVector1D>? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val colors = MiuixTheme.colorScheme
    val ownProgress = remember { Animatable(0f) }
    val anim = progress ?: ownProgress
    // 位移单独一条：比缩放更陡，卡片先冲到位
    val transY = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(visible) }
    val currentDismissed by rememberUpdatedState(onDismissed)

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            coroutineScope {
                launch {
                    anim.animateTo(1f, tween(MorphOpenMillis, easing = MorphOpenEase))
                }
                launch {
                    transY.animateTo(1f, tween(TransOpenMillis, easing = MorphTransOpenEase))
                }
            }
        } else if (mounted) {
            coroutineScope {
                launch {
                    anim.animateTo(0f, tween(MorphExitMillis, easing = MorphExitEase))
                }
                launch {
                    transY.animateTo(0f, tween(TransExitMillis, easing = MorphTransExitEase))
                }
            }
            mounted = false
            currentDismissed()
        }
    }

    if (!mounted) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        // 没有来源信息时（比如从通知直接进来）就从中部一小块展开
        val from = origin ?: Rect(
            left = screenW * 0.08f,
            top = screenH * 0.3f,
            right = screenW * 0.92f,
            bottom = screenH * 0.4f,
        )
        val startCornerPx = with(density) { StartCorner.toPx() }
        // 屏幕物理圆角：面板快铺满时按它裁，才不会变成一块浮在屏幕上的直角矩形
        val screenCornerPx = rememberScreenCornerRadiusPx()

        val screenWState = rememberUpdatedState(screenW)
        val screenHState = rememberUpdatedState(screenH)
        val fromState = rememberUpdatedState(from)
        val cornerState = rememberUpdatedState(startCornerPx)
        val screenCornerState = rememberUpdatedState(screenCornerPx)

        // 所有几何都在派生状态里算：绘制阶段才读取，不触发重组（详情页内容不会被每帧重建）
        val state = remember {
            derivedStateOf {
                val w = screenWState.value
                val h = screenHState.value
                val card = fromState.value
                val p = anim.value
                val ty = transY.value
                // 缩到卡片宽度：p=0 时 scale 正好让页面宽度等于卡片宽度
                val cardRatio = (card.width / w).coerceIn(0.05f, 1f)
                val scale = (cardRatio + (1f - cardRatio) * p).coerceAtLeast(0.05f)
                // 水平：让页面中心对齐卡片中心
                val tx = (card.left + card.width / 2f - w / 2f) * (1f - p)
                // 垂直：可见区域中心沿曲线从卡片中心落到屏幕中心
                val cardCenterY = card.top + card.height / 2f
                val screenCenterY = h / 2f
                val targetCenterY = cardCenterY + (screenCenterY - cardCenterY) * ty
                val translationY = targetCenterY - h / 2f * (1f - scale) -
                    (card.height + (h - card.height) * p) / 2f
                // 裁切高度：卡片高 → 全屏高。除以 scale 是因为剪裁在图层本地坐标里做
                val clipBottom = (card.height + (h - card.height) * p) / scale
                // 圆角三段（跟 Nexio 的 MorphClipShape 一致）：
                //   0 → 0.7  ：卡片圆角插值到屏幕物理圆角
                //   0.7 → 1  ：保持屏幕圆角 —— 这段面板还浮在屏幕上，直角会显得像块贴纸
                //   1        ：交给系统窗口自己裁（全屏时窗口本身就是屏幕圆角）
                val startCorner = cornerState.value
                val screenCorner = screenCornerState.value
                val cornerPx = when {
                    p >= 1f -> 0f
                    p <= 0.7f -> startCorner + (screenCorner - startCorner) * (p / 0.7f)
                    else -> screenCorner
                } / scale
                MorphState(
                    bgAlpha = p * BgAlphaMax,
                    // 快照前 1/3 行程淡出
                    snapshotAlpha = (1f - p * 3f).coerceIn(0f, 1f),
                    // 内容 p=0.1 开始淡入、0.6 满
                    contentAlpha = ((p - 0.1f) / 0.5f).coerceIn(0f, 1f),
                    scale = scale,
                    translationX = tx,
                    translationY = translationY,
                    clipBottom = clipBottom,
                    cornerRadiusPx = cornerPx,
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                // 背景逐渐压暗（不做缩放）
                .drawBehind { drawRect(Color.Black.copy(alpha = state.value.bgAlpha)) }
                // 只在形变还没到位时拦截事件：展开中 / 收起中点面板外不该穿透到下层。
                // 完全展开后（p≈1）必须放行，否则详情页的纵向滑动会被这层无条件 consume 吃掉，
                // 表现就是"有时滑不动"（动画刚结束那一小段时间最明显）。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (anim.value < 0.999f) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = state.value
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        scaleX = s.scale
                        scaleY = s.scale
                        translationX = s.translationX
                        translationY = s.translationY
                    }
                    .morphClip(state)
                    .background(colors.background),
            ) {
                // 卡片快照：开头完全顶着卡片的位置和大小，随缩放一起放大
                if (snapshot != null) {
                    val a = state.value.snapshotAlpha
                    if (a > 0.01f) {
                        TransitionLayerImage(
                            layer = snapshot,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = a },
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = state.value.contentAlpha },
                ) {
                    content()
                }
            }
        }
    }
}

/** 按当前形变状态裁切：宽度铺满，底部和圆角跟着进度长 */
private fun Modifier.morphClip(state: State<MorphState>): Modifier = drawWithContent {
    val s = state.value
    val bottom = s.clipBottom.coerceIn(0f, size.height)
    val corner = s.cornerRadiusPx.coerceAtLeast(0f)
    if (corner <= 0.5f && bottom >= size.height) {
        drawContent()
    } else {
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = bottom,
                    radiusX = corner,
                    radiusY = corner,
                ),
            )
        }
        clipPath(path) { this@drawWithContent.drawContent() }
    }
}

/**
 * 屏幕物理圆角（px）。Android 12+ 能从 WindowInsets 里读到四个角，
 * 读不到（直角屏 / 老系统 / 分屏）就返回 0，裁切保持原样。
 */
@Composable
private fun rememberScreenCornerRadiusPx(): Float {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ScreenCornerFallbackPx
        } else {
            runCatching {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val insets = wm?.currentWindowMetrics?.windowInsets
                listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT)
                    .mapNotNull { insets?.getRoundedCorner(it)?.radius?.toFloat() }
                    .filter { it > 0f }
                    .maxOrNull() ?: ScreenCornerFallbackPx
            }.getOrDefault(ScreenCornerFallbackPx)
        }
    }
}
