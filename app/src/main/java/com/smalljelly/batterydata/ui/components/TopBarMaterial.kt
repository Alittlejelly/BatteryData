package com.smalljelly.batterydata.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import kotlin.math.abs

/** 顶栏材质出现的阈值：内容滚过 10dp 才算"开始下滑"（与 Nexio 同一口径） */
private val TopBarMaterialThreshold = 10.dp

/**
 * 顶栏材质透明度（0 = 页面在顶部，1 = 已下滑）。
 *
 * 对齐 Nexio 课表的两态：停在顶部时顶栏没有材质，按钮只有图标、没有玻璃底和阴影；
 * 内容滚过 10dp 后，模糊带、按钮玻璃、阴影一起淡入。返回的是**绘制期读**的 lambda，
 * 逐帧变化只失效绘制，不进组合。
 *
 * 出现用较软的弹簧（约 0.5s），消失略快一点，和系统顶栏材质的手感一致。
 */
@Composable
fun rememberTopBarMaterialAlpha(scrolled: Boolean): () -> Float {
    val alpha = remember { Animatable(if (scrolled) 1f else 0f) }
    LaunchedEffect(scrolled) {
        alpha.animateTo(
            targetValue = if (scrolled) 1f else 0f,
            animationSpec = if (scrolled) {
                spring(dampingRatio = 1f, stiffness = 130f)
            } else {
                spring(dampingRatio = 1f, stiffness = 260f)
            },
        )
    }
    return remember { { alpha.value } }
}

/**
 * 首页用的版本：从 Miuix 的 ScrollBehavior 推断是否已经下滑。
 * 记录页 / 详情页的滚动体是 LazyColumn 和普通 ScrollState，直接用下面两个重载更稳。
 */
@Composable
fun rememberTopBarMaterialAlpha(scrollBehavior: ScrollBehavior): () -> Float {
    val density = LocalDensity.current
    val thresholdPx = with(density) { TopBarMaterialThreshold.toPx() }
    val scrolled by remember(scrollBehavior, thresholdPx) {
        derivedStateOf {
            val state = scrollBehavior.state
            // 两个信号取或：contentOffset 会被顶部回弹污染成正数（先夹到 0），
            // heightOffset 是大标题收起的位移，任一越过阈值就算"下滑"
            state.contentOffset.coerceAtMost(0f) < -thresholdPx || state.heightOffset < -thresholdPx
        }
    }
    return rememberTopBarMaterialAlpha(scrolled)
}

/** 列表版：第一条内容被顶走 / 首项已滚过阈值 */
@Composable
fun rememberTopBarMaterialAlpha(listState: LazyListState): () -> Float {
    val thresholdPx = with(LocalDensity.current) { TopBarMaterialThreshold.toPx() }
    val scrolled by remember(listState, thresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > thresholdPx
        }
    }
    return rememberTopBarMaterialAlpha(scrolled)
}

/**
 * 记录页专用：同时看两个信号。
 *
 * 只用 [listState] 时，列表内容不满一屏（一条记录）或顶在回弹态，首项偏移永远不过阈值，
 * 模糊带就不会出现；只用 [scrollBehavior] 时，大标题折叠和它同步，但 LazyColumn 的
 * contentOffset 有时不可靠。两个取或，任一越过阈值就算下滑。
 */
@Composable
fun rememberTopBarMaterialAlpha(
    scrollBehavior: ScrollBehavior,
    listState: LazyListState,
): () -> Float {
    val thresholdPx = with(LocalDensity.current) { TopBarMaterialThreshold.toPx() }
    val scrolled by remember(scrollBehavior, listState, thresholdPx) {
        derivedStateOf {
            val state = scrollBehavior.state
            val byBehavior = state.contentOffset.coerceAtMost(0f) < -thresholdPx ||
                state.heightOffset < -thresholdPx
            val byList = listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > thresholdPx
            byBehavior || byList
        }
    }
    return rememberTopBarMaterialAlpha(scrolled)
}

/** 普通滚动版（详情页那种 verticalScroll） */
@Composable
fun rememberTopBarMaterialAlpha(scrollValue: () -> Int): () -> Float =
    rememberTopBarMaterialAlpha(scrollValue, TopBarMaterialThreshold)

/**
 * 指定起始偏移的滚动版：不是"一滚就糊"，而是等内容真正滑到顶栏底下才开始。
 * 详情页顶部有大标题留白，滚 10dp 时下面还是空的，模糊带必须等到第一张卡进栏才出现。
 */
@Composable
fun rememberTopBarMaterialAlpha(
    scrollValue: () -> Int,
    startAt: androidx.compose.ui.unit.Dp,
): () -> Float {
    val thresholdPx = with(LocalDensity.current) { startAt.toPx() }
    val scrolled by remember(thresholdPx) {
        derivedStateOf { scrollValue() > thresholdPx }
    }
    return rememberTopBarMaterialAlpha(scrolled)
}


/**
 * 「回弹位移」→ 顶栏材质。
 *
 * 为什么需要它：记录页 / 详情页的内容经常不足一屏（一条记录、两张卡），列表**根本没有
 * 滚动范围**。这时 `listState` 的偏移恒为 0，材质就永远不出现 —— 界面诊断里会看到
 * `滚动位置：偏移 0 / 不透明度 0.00`，而画面确实是"栏下一片空白"。
 *
 * 但 Miuix 的 `overScrollVertical` 在拖动时会把内容**真实位移**（graphicsLayer translation），
 * 内容其实滑进了栏下、也真的被采样到了，只是没人告诉顶栏"该上场了"。
 * 所以这里把**列表没消费掉的垂直增量**累起来（那正是回弹位移，单位 px），
 * 拖动期间维持、松手后归零，材质跟着淡入淡出。
 *
 * ⚠ 必须挂在 `.overScrollVertical()` **之后**（也就是更内层）：
 *   嵌套滚动从源头向外派发，内层先拿到增量；挂在外层只能拿到 0。
 */
@Stable
class TopBarPull internal constructor() {
    internal val offset = mutableFloatStateOf(0f)

    /** 0..1：内容被拖动的程度（位移量 / 阈值） */
    fun level(thresholdPx: Float): Float = (offset.floatValue / thresholdPx).coerceIn(0f, 1f)

    fun isActive(thresholdPx: Float): Boolean = offset.floatValue > thresholdPx
}

@Composable
fun rememberTopBarPull(): Pair<Modifier, TopBarPull> {
    val state = remember { TopBarPull() }
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { 8.dp.toPx() }
    val connection = remember(state) {
        object : NestedScrollConnection {
            private var idle: Job? = null

            private fun set(value: Float) {
                state.offset.floatValue = value
                // 没有 fling 的慢速松手不会有 onPostFling，靠空闲超时兜底归零
                idle?.cancel()
                idle = scope.launch {
                    delay(160)
                    state.offset.floatValue = 0f
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (abs(available.y) > 0.5f) {
                    // 累加绝对值：左右方向无所谓，只要内容被拖出了静止位置就算
                    set((state.offset.floatValue + abs(available.y)).coerceAtMost(4_000f))
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 松手后内容开始回弹，材质同步淡出（空闲超时也兜了一层）
                state.offset.floatValue = 0f
                return Velocity.Zero
            }
        }
    }
    val modifier = remember(connection) { Modifier.nestedScroll(connection) }
    return modifier to state
}

/**
 * 列表 + 拖动位移 两路取最大：
 * 长列表靠 `listState` 偏移（滚过 10dp 后稳定亮着），短列表靠回弹位移（拖着就有）。
 */
@Composable
fun rememberTopBarMaterialAlpha(listState: LazyListState, pull: TopBarPull): () -> Float {
    val thresholdPx = with(LocalDensity.current) { TopBarMaterialThreshold.toPx() }
    val pullThresholdPx = with(LocalDensity.current) { 8.dp.toPx() }
    val scrolled by remember(listState, pull, thresholdPx, pullThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > thresholdPx ||
                pull.isActive(pullThresholdPx)
        }
    }
    val animated = rememberTopBarMaterialAlpha(scrolled)
    // 拖动时材质要立刻跟手，所以再和"瞬时位移"取最大值，不等 0.5s 的弹簧
    return remember(animated, pull, pullThresholdPx) {
        { maxOf(animated(), pull.level(pullThresholdPx)) }
    }
}
