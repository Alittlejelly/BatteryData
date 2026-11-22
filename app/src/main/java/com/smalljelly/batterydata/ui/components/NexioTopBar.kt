package com.smalljelly.batterydata.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.haooz.nexioui.iconbutton.IconButton
import com.haooz.nexioui.material.NexioMaterialLevel
import com.haooz.nexioui.progressiveblurtopbar.ProgressiveBlurTopBar
import com.haooz.nexioui.topappbar.CollapsibleTopAppBar
import com.haooz.nexioui.topappbar.CollapsibleTopAppBarDefaults
import com.haooz.nexioui.topappbar.SharedScrollBehavior
import com.haooz.nexioui.topappbar.rememberSharedScrollBehavior
import com.haooz.nexioui.topbarmaterial.LocalTopBarMaterial
import com.haooz.nexioui.topbarmaterial.TopBarMaterialState
import com.haooz.nexioui.topbarmaterial.rememberTopBarMaterialState
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.backdrop.GlassBackdrop
import com.smalljelly.batterydata.ui.theme.AppMaterial
import kotlin.math.roundToInt

/**
 * 页面顶栏的共享状态：折叠进度、材质 alpha 和「内容顶部让位」都读它。
 *
 * 一页只能建一份 —— 顶栏、模糊带、内容内边距必须共用一个 [SharedScrollBehavior]，
 * 否则折叠量各算各的，内容会被推两次。
 */
@Stable
class NexioTopBarState internal constructor(
    val scrollBehavior: SharedScrollBehavior,
    val materialState: TopBarMaterialState,
) {
    /** 大标题块所需高度（px）：顶栏上报的实测高度减去折叠高度 */
    internal var largeTitleHeightPx by mutableFloatStateOf(0f)
}

/** 顶栏操作圆钮的直径：库的 `IconButton` 标准尺寸，下拉收起态也用同一颗 */
val TopBarActionSize: Dp = 42.dp

/**
 * 顶栏两态图标按钮（NexioUI 玻璃钮的统一封装）。
 *
 * NexioUI 的顶栏按钮有两种状态：页面顶部时材质 alpha=0，玻璃层全透 → 只剩图标（普通态）；
 * 内容滚出顶栏材质后 alpha=1 → 玻璃态（采样页面内容做模糊 + 折射 + 投影）。
 * [backdropAlpha] / [shadowAlpha] 由 [NexioTopBar] 的 navigation / actions 回调下发，
 * 就是顶栏材质 alpha，逐帧驱动两态切换。这里统一尺寸 / 图标大小 / 触感，各页只传图标与配色。
 */
@Composable
fun NexioIconButton(
    onClick: () -> Unit,
    backdrop: GlassBackdrop,
    icon: ImageVector,
    contentDescription: String?,
    backdropAlpha: Float,
    shadowAlpha: Float,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        backdrop = backdrop,
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        buttonHeight = TopBarActionSize,
        iconSize = 22.dp,
        iconTint = iconTint,
        backdropAlpha = backdropAlpha,
        shadowAlpha = shadowAlpha,
        performHapticFeedback = false,
        enabled = enabled,
    )
}

/**
 * 观察按压时长（不消费事件）：按下回调 true，抬起/取消回调 false。
 * 用在顶栏玻璃按钮上，把「正在拖按钮」上报给容器，容器据此临时禁用 Pager 横滑切页——
 * 在按下瞬间（手势还没产生位移）就禁掉，比按位移阈值拦截更稳，斜向拖拽也不会漏。
 * 按钮自身的拉伸/点击不受影响（这里只观察、不消费）。
 */
fun Modifier.reportPressTo(onChange: (Boolean) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onChange(true)
        try {
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.pressed })
        } finally {
            // 手势被取消也要复位，否则标志卡在 true 会永久禁用切页
            onChange(false)
        }
    }
}

/** 建一份页面顶栏状态（内部已包含滚动行为与材质状态） */
@Composable
fun rememberNexioTopBarState(): NexioTopBarState {
    val behavior = rememberSharedScrollBehavior()
    val material = rememberTopBarMaterialState(behavior)
    return remember(behavior, material) { NexioTopBarState(behavior, material) }
}

/**
 * 在子项上方追加一段随顶栏折叠收起的让位，**布局阶段**读取折叠高度，不触发重组。
 *
 * ⚠ 不能用库的 `collapsibleTopInset`：它从传入约束的 maxHeight 里减 extra，
 * 而滚动容器的强制重测量会传入「无穷大」哨兵（Int.MAX_VALUE），减完超出
 * Constraints 可表示范围 → IllegalArgumentException，启动直接闪退。
 * 这里的子项是固定高度 Spacer，根本不需要收窄子项约束 —— 原样测量，
 * 只在自己报告的高度上加 extra，任何约束下都安全。
 */
private fun Modifier.collapsingTopSpace(scrollBehavior: SharedScrollBehavior): Modifier =
    layout { measurable, constraints ->
        val collapsedPx = CollapsibleTopAppBarDefaults.CollapsedHeight.roundToPx()
        val reportedPx = scrollBehavior.currentHeightPx
        val extraPx = if (reportedPx > 0f) {
            (reportedPx.roundToInt() - collapsedPx).coerceAtLeast(0)
        } else {
            0
        }
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height + extraPx) {
            placeable.place(0, extraPx)
        }
    }

/**
 * 滚动内容里的首个「顶栏让位」Spacer。
 *
 * 让位必须放进**滚动内容里**，不能套在滚动容器外面：外层 padding 会把滚动视口
 * 裁到糊带之下，内容永远滚不进顶栏糊带，渐进模糊就永远糊不到东西
 * （首页之前「顶部没有渐进模糊」的另一半原因）。记录页 / 详情页用
 * contentPadding / 首个 Spacer 让位，都是同一个道理。
 *
 * 高度 = 状态栏 + 折叠行 + 大标题展开段；展开段由 [collapsingTopSpace]
 * 在布局阶段随折叠进度收起。注意修饰符顺序：加高在外、height 在内，
 * 反了 extra 会被固定高度吃掉。
 */
@Composable
fun NexioTopContentSpacer(state: NexioTopBarState, modifier: Modifier = Modifier) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Spacer(
        modifier
            .collapsingTopSpace(state.scrollBehavior)
            .height(statusBar + CollapsibleTopAppBarDefaults.CollapsedHeight),
    )
}

/**
 * 顶栏：库的 [CollapsibleTopAppBar]（大标题折叠 + 材质）垫在 [ProgressiveBlurTopBar]
 * 的渐进模糊带上，两者共享同一个 [NexioTopBarState]。
 *
 * 糊带只盖「状态栏 + 折叠行」：大标题在带下方保持清晰，内容上滚进带里才被渐进糊掉。
 * 配套让位见 [NexioTopContentSpacer]（必须放在滚动内容里，不能套在滚动容器外）。
 */
@Composable
fun NexioTopBar(
    state: NexioTopBarState,
    title: String,
    largeTitle: String?,
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
    /** 左侧（返回键等）；拿到的是材质 alpha */
    navigation: (@Composable (backdropAlpha: Float, shadowAlpha: Float) -> Unit)? = null,
    /** 右侧操作区；拿到的是材质 alpha */
    actions: (@Composable (backdropAlpha: Float, shadowAlpha: Float) -> Unit)? = null,
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 渐进糊带只覆盖「状态栏 + 折叠行」：大标题在这条带**之下**，内容往上滚时才进入糊带、
    // 才会被 progressively 糊掉。之前把大标题区的实测高度也加进带高，
    // 糊带始终盖着整块标题，看起来就"没有渐进式模糊"。
    val bandHeight = statusBar + CollapsibleTopAppBarDefaults.CollapsedHeight
    // 库把 endAction 直接摆在一个 Box 里，多个按钮会叠在一起 —— 这里收进一行
    val actionRow: (@Composable (Float, Float) -> Unit)? = actions?.let { content ->
        { a, b ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content(a, b)
            }
        }
    }

    Box(modifier.fillMaxWidth().zIndex(10f)) {
        CompositionLocalProvider(LocalTopBarMaterial provides state.materialState) {
            ProgressiveBlurTopBar(
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                height = bandHeight,
                tintIntensity = 0.2f * AppMaterial.current.topTintScale,
                // 糊带常驻（库默认也是 1f）。之前喂的是材质 alpha，页面在顶部时它恒为 0，
                // 整条渐进式模糊就一直是透明的 —— 这才是"所有界面顶部模糊没了"的原因。
                blurAlpha = 1f,
                // 「关闭」档走无模糊的渐变遮罩：采样层补上底色后，AGSL 路径会真的糊出
                // 内容，违背该档"不模糊，全部实色"。遮罩观感与之前（强 tint 盖空采样）一致。
                useGradientMask = AppMaterial.current.level == NexioMaterialLevel.None,
            ) {
                CollapsibleTopAppBar(
                    title = title,
                    largeTitle = largeTitle ?: title,
                    showLargeTitle = largeTitle != null,
                    materialState = state.materialState,
                    scrollBehavior = state.scrollBehavior,
                    titleColor = NexioTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = { reported ->
                        // 上报的是不含状态栏的实测高度；减掉折叠高度就是大标题块
                        val px = reported.value - CollapsibleTopAppBarDefaults.CollapsedHeight.value
                        if (px > state.largeTitleHeightPx) state.largeTitleHeightPx = px
                    },
                    startAction = navigation,
                    endAction = actionRow,
                )
            }
        }
    }
}