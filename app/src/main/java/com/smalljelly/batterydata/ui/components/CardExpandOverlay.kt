package com.smalljelly.batterydata.ui.components

import android.content.Context
import android.os.Build
import android.view.RoundedCorner
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.haooz.nexioui.motion.morphClip
import com.haooz.nexioui.motion.rememberMorphTransition
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.nexioui.window.ScreenCornerRadiiPx
import kotlinx.coroutines.flow.collectLatest

/** 源卡片的圆角 */
private val StartCorner = 20.dp

/** 读不到屏幕物理圆角时的兜底（直角屏就不裁，保持 0） */
private const val ScreenCornerFallbackPx = 0f

/**
 * 卡片 ⇄ 全屏的连贯形变。
 *
 * 形变本身全部交给 NexioUI 的 [rememberMorphTransition]：缩放 / 锚点迁移 / 底部裁切 /
 * 屏幕圆角 / 预测性返回手势都在库里算。这里只剩「宿主」该做的事 ——
 * 按 [state] 把整页等比缩到卡片位置、用 [morphClip] 裁到当前展开高度，
 * 再叠上卡片快照与压暗层。
 *
 * 核心不是「把页面塞进卡片里」，而是反过来：**整页先按全屏布局好，再用 `graphicsLayer`
 * 等比缩到卡片宽度**，高度方向靠裁切。打开时 scale 从卡片比例涨到 1、裁切高度从卡片高涨到全屏，
 * 于是画面是「同一张页面在原地长大」。
 *
 * [snapshot] 是卡片的图层快照：前 1/3 行程盖在页面之上，保证第一帧看到的就是列表里那张卡片本身。
 */
@Composable
fun CardExpandOverlay(
    visible: Boolean,
    origin: Rect?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    /** 被点卡片的图层快照（`GraphicsLayer`），没有也能播，只是开头会露页面顶部 */
    snapshot: GraphicsLayer? = null,
    /** 可选的外部进度，方便宿主读同一条值（页面背景虚化就靠它） */
    progress: Animatable<Float, AnimationVector1D>? = null,
    /**
     * 预见式返回是否开启（读设置的全局状态）。
     *
     * 卡片展开这套形变由库自己接管返回手势 —— 它的跟手预览是**独立于** AppPage 的，
     * 所以开关必须单独传进来，否则「关掉预见式动画」后从记录进详情、
     * 再按返回照样跟手缩回去，看起来就是开关没生效。
     */
    predictiveBack: () -> Boolean = { true },
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val colors = NexioTheme.colorScheme
    val currentDismissed by rememberUpdatedState(onDismissed)
    var mounted by remember { mutableStateOf(visible) }

    if (visible) mounted = true

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        // 没有来源信息时（比如从通知直接进来）就从中部一小块展开
        val from = origin ?: remember(screenW, screenH) {
            Rect(
                left = screenW * 0.08f,
                top = screenH * 0.3f,
                right = screenW * 0.92f,
                bottom = screenH * 0.4f,
            )
        }
        // 屏幕物理圆角：面板快铺满时按它裁，才不会变成一块浮在屏幕上的直角矩形
        val screenCornerPx = rememberScreenCornerRadiusPx()
        val cornerRadii = remember(screenCornerPx) { ScreenCornerRadiiPx.uniform(screenCornerPx) }

        val morph = rememberMorphTransition(
            cardLeft = from.left,
            cardTop = from.top,
            cardWidth = from.width,
            cardHeight = from.height,
            screenWidth = screenW,
            screenHeight = screenH,
            screenCornerRadius = cornerRadii,
            startCornerRadiusDp = StartCorner.value,
            onBackStart = {},
            // 库自己接管返回（含预测性返回）：收起动画播完才回这里
            onBack = { currentDismissed() },
            // 关闭「预见式返回」时，卡片形变本身也不再跟手，只做普通收起
            predictiveBackEnabled = predictiveBack,
        )

        // 宿主侧的外部进度：把库的进度镜像出去，页面背景虚化跟着同一条曲线
        if (progress != null) {
            LaunchedEffect(morph, progress) {
                snapshotFlow { morph.state.value.progress }.collectLatest { p ->
                    progress.snapTo(p)
                }
            }
        }

        // 可见性翻转 → 播退出形变；播完由 onBack 通知宿主卸载
        LaunchedEffect(visible) {
            if (!visible && mounted) morph.close()
        }

        if (!mounted) return@BoxWithConstraints

        val state = morph.state

        Box(
            Modifier
                .fillMaxSize()
                // 背景逐渐压暗（不做缩放）
                .drawBehind { drawRect(Color.Black.copy(alpha = state.value.bgAlpha)) }
                // 只在形变还没到位时拦截事件：展开中 / 收起中点面板外不该穿透到下层。
                // 完全展开后必须放行，否则详情页的纵向滑动会被这层无条件 consume 吃掉。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (state.value.progress < 0.999f) {
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
                        // 缩放锚点在屏幕中线上；预测性返回时库会把它往滑入侧偏
                        transformOrigin = TransformOrigin(s.anchorX, 0.5f)
                        scaleX = s.scale
                        scaleY = s.scale
                        translationX = s.translationX
                        translationY = s.translationY
                    }
                    // 裁剪形状由库按当前进度给（卡片圆角 → 屏幕圆角 → 直角，底部跟着长）
                    .morphClip(morph)
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