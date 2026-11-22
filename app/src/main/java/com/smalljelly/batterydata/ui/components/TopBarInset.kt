package com.smalljelly.batterydata.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.haooz.nexioui.topappbar.CollapsibleTopAppBarDefaults
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 顶栏尺寸与「下拉出材质」的辅助。
 *
 * 顶栏本身已全部换成 NexioUI 的 `CollapsibleTopAppBar`（见 [NexioTopBar]），这里只剩两件
 * 库没有直接提供、但页面仍在用的东西：
 * - [pageTopBarHeight]：内容让位的兜底常数（Scaffold 首帧还没量到栏高时用）；
 * - [rememberTopBarPull]：短页面拖不动时，靠下拉位移把顶栏材质顶出来。
 */

/**
 * 顶栏总高度（状态栏 + 折叠高度 + 大标题块）。只作为让位的兜底值：
 * 正常情况下用 Scaffold 实测的 `innerPadding.top`。
 *
 * [hasLargeTitle] 传 false 时对应「只有按钮行」的居中标题型顶栏（日志详情）。
 */
@Composable
fun pageTopBarHeight(hasLargeTitle: Boolean = true): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        CollapsibleTopAppBarDefaults.CollapsedHeight +
        (if (hasLargeTitle) LargeTitleBlockHeight else 0.dp)

/** 大标题块的兜底高度（库里是运行时测量值，这里只在测量前用） */
private val LargeTitleBlockHeight: Dp = 62.dp

/** 下拖把材质顶出来的位移状态，阈值内 0..1。 */
@Stable
class TopBarPull internal constructor() {
    internal val offset = mutableFloatStateOf(0f)

    /** 0..1：内容被拖动的程度（位移量 / 阈值） */
    fun level(thresholdPx: Float): Float = (offset.floatValue / thresholdPx).coerceIn(0f, 1f)

    fun isActive(thresholdPx: Float): Boolean = offset.floatValue > thresholdPx
}

/**
 * 拖动位移监测：内容被拖离静止位置时累加位移，松手（fling 或空闲超时）归零。
 *
 * ⚠ 必须挂在 `.overScrollVertical()` **之后**（也就是更内层）：
 *   嵌套滚动从源头向外派发，内层先拿到增量；挂在外层只能拿到 0。
 */
@Composable
fun rememberTopBarPull(): Pair<Modifier, TopBarPull> {
    val state = remember { TopBarPull() }
    val scope = rememberCoroutineScope()
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