package com.smalljelly.batterydata.ui.components

import android.content.Context
import android.view.RoundedCorner
import android.view.WindowManager
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haooz.nexioui.motion.OobeQuartOutSoftStartEasing
import com.haooz.nexioui.motion.SecondaryPageExitEasing
import kotlin.math.roundToInt

/**
 * 侧滑载入：「新的一页」像一张卡片从右侧推进来，下面那一页被推开并压暗。
 *
 * 时序与位移全部照搬 NexioSchedule 的 AppNavHost（`NavLayerStack` + `PageLayer`）：
 * - 入场 600ms，`OobeQuartOutSoftStartEasing`（起步温和、中后段减速，尾段拖得更长）
 * - 出场 320ms，`SecondaryPageExitEasing`（起步更缓）
 * - 上层位移 `(1 - p) × 宽度`，下层视差位移 `-0.24 × p × 宽度`
 * - 下层压暗 `0.42 × p`；转场中上层裁成屏幕圆角，像卡片而不是整块面板
 *
 * 位移用 [Modifier.layout] 自己 place（和原实现一致）：`offset` 会把位移写进
 * constraints，子节点每帧真 re-measure（整页列表、Pager、玻璃采样全跟着重测，必掉帧）；
 * 这里只改摆放坐标，不重测。`p` 只在布局/绘制阶段读，不触发重组。
 *
 * 用法：`SlideInHost(visible = 有二级页) { 底层页 } overlay = { 二级页 }`。
 * 多级叠加就再套一层 —— 每一层各自推开自己的下层，正好是导航栈的观感。
 */
@Composable
fun SlideInHost(
    visible: Boolean,
    modifier: Modifier = Modifier,
    /** 外部进度（预见式返回手势直接写它）。不传则内部自建一份 */
    slideProgress: SlideProgress = rememberSlideProgress(visible),
    baseContent: @Composable () -> Unit,
    overlayContent: @Composable () -> Unit,
) {
    val progress = slideProgress
    var mounted by remember { mutableStateOf(visible) }
    val cornerRadius = rememberScreenCornerRadius()

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            progress.animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(SLIDE_IN_MS, easing = OobeQuartOutSoftStartEasing),
            )
        } else if (mounted) {
            // 退出：无论当前被手势拖到哪，都从**当前位置**续播到 0。
            // 以前这里和 PredictiveBackBox 各写一个位移，松手瞬间两边目标不同 → 闪一下
            progress.animateTo(0f, SLIDE_OUT_MS)
            mounted = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()

        // 下层：被顶开一个视差位移（越深的一页越靠左）
        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.place(
                            (-SLIDE_PARALLAX * progress.value * widthPx).roundToInt(),
                            0,
                        )
                    }
                },
        ) {
            baseContent()
        }

        // 压暗只盖被推开的那一页：上层自己不该被压暗，所以画在两者之间
        if (mounted || progress.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val p = progress.value
                        if (p < 0.999f) {
                            drawRect(Color.Black.copy(alpha = SLIDE_DIM * p))
                        }
                    },
            )
        }

        // 上层：从右侧推入；挂载期间一直按屏幕圆角裁，转场结束它刚好盖满整屏，看不出裁剪
        if (mounted) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.place(
                                ((1f - progress.value) * widthPx).roundToInt(),
                                0,
                            )
                        }
                    }
                    .then(
                        if (cornerRadius > 0.dp) {
                            Modifier.clip(RoundedCornerShape(cornerRadius))
                        } else {
                            Modifier
                        },
                    ),
            ) {
                overlayContent()
            }
        }
    }
}

/** 入场时长（NexioSchedule: NAV_ENTER_DURATION） */
internal const val SLIDE_IN_MS = 600

/**
 * 出场时长。NexioSchedule 是 320ms，但那是**手势已经拖走大半**时才播的剩余段；
 * 我们是"点返回 / 手势刚起步就松手"，整段从 1 播到 0，320ms 会显得唰一下就没了。
 * 放大到 400ms，起点速度更缓，尾巴更从容。
 */
internal const val SLIDE_OUT_MS = 400

/**
 * 侧滑载入的进/出场动画（给还在用 `AnimatedVisibility` 的宿主）。
 *
 * 和 [SlideInHost] 同一套时长与曲线：整页从右侧推入（位移 = 整屏宽度）、
 * 600ms 温和起步 + 长尾；出场 320ms 起步更缓。之前各页用的是
 * `spring { it / 5 }` + fade（只滑进 20% 宽度、还带淡入），观感完全不同。
 */
@Composable
fun navSlideEnter(): EnterTransition = slideInHorizontally(
    animationSpec = tween(SLIDE_IN_MS, easing = OobeQuartOutSoftStartEasing),
) { fullWidth -> fullWidth }

@Composable
fun navSlideExit(): ExitTransition = slideOutHorizontally(
    animationSpec = tween(SLIDE_OUT_MS, easing = SecondaryPageExitEasing),
) { fullWidth -> fullWidth }

/** 下层视差比例（NexioSchedule: NAV_PARALLAX） */
private const val SLIDE_PARALLAX = 0.24f

/** 下层压暗（NexioSchedule: NAV_BG_DIM） */
private const val SLIDE_DIM = 0.42f

/**
 * 屏幕物理圆角。用系统的圆角半径去裁转场中的页面，它才像一张卡片而不是直角面板。
 * 读不到（直角屏 / 老系统 / 分屏）就返回 0，调用方跳过裁剪。
 */
@Composable
internal fun rememberScreenCornerRadius(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val insets = wm?.currentWindowMetrics?.windowInsets
            val px = listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT)
                .mapNotNull { insets?.getRoundedCorner(it)?.radius?.toFloat() }
                .filter { it > 0f }
                .maxOrNull() ?: 0f
            with(density) { px.toDp() }
        }.getOrDefault(0.dp)
    }
}

/** 屏幕物理圆角的形状版本（预见式返回转场中裁页面用）；读不到返回 null */
@Composable
internal fun rememberScreenCornerShape(): RoundedCornerShape? {
    val radius = rememberScreenCornerRadius()
    return if (radius > 0.dp) RoundedCornerShape(radius) else null
}