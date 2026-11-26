package com.smalljelly.batterydata.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haooz.nexioui.icon.NexioIcons
import com.haooz.nexioui.icon.basic.ArrowRight
import com.haooz.nexioui.overscroll.overScrollVertical
import com.haooz.nexioui.scaffold.Scaffold
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.backdrop.backdrops.LayerBackdrop
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.ui.theme.AppMotion
import com.haooz.nexioui.motion.SecondaryPageExitEasing
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * 全应用统一的页面骨架。
 *
 * **让位算法直接用 `Scaffold` 量出来的 `innerPadding.top`**，和记录页 / 日志页同一套。
 * 之前这里用的是 `NexioTopContentSpacer`（读顶栏上报的 `currentHeightPx` 自己加），
 * 那是另一条来源：两条来源只要差一点，页面顶部就会比记录页高出一截 ——
 * 电池容量页当初"大标题压在卡片上"、设置页"列表比别的页低"都是这么来的。
 * 现在栏多高就退多少，和记录页不可能不一致。
 *
 * 其余三件事照旧：
 * - **渐进式模糊顶栏**：内容铺满全屏、顶栏盖在上面，滚进栏下的内容才会被糊掉；
 *   采样层 `layerBackdrop` 必须写在 `background()` 之前。
 * - **滑动超出反馈**：`overScrollVertical()` 弹簧回弹 + 关掉 foundation 自带的 overscroll。
 * - **预见式返回**：跟手右移 + 缩小，露出下面那页；开关是全局状态 [AppMotion]。
 *
 * @param onBack 非 null 时这一页是二级页：接管返回手势，并在顶栏左侧画返回钮
 */
@Composable
fun AppPage(
    title: String,
    largeTitle: String? = title,
    state: NexioTopBarState = rememberNexioTopBarState(),
    backdrop: LayerBackdrop = rememberLayerBackdrop(),
    onBack: (() -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
    actions: (@Composable (backdropAlpha: Float, shadowAlpha: Float) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = NexioTheme.colorScheme
    // 读全局状态（不是 remember 一次）：设置里一关，这一页立刻退回普通返回
    val predictiveBack = AppMotion.predictiveBack
    val scrollState = rememberScrollState()
    // 短页面拖不动时，靠下拉位移把顶栏材质顶出来（和折叠滚动同一个连接）
    val (pullModifier, _) = rememberTopBarPull()
    // 预见式返回的手势进度 0..1（0 = 完全显示）。用 Animatable 而不是裸 Float：
    // 手势提交/取消时要从当前位置**平滑**回 0，不能瞬跳 —— 瞬跳就是那一帧"闪"。
    val backProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    if (onBack != null) {
        if (predictiveBack) {
            PredictiveBackHandler { events ->
                try {
                    events.collect { event -> backProgress.snapTo(event.progress) }
                } catch (cancel: CancellationException) {
                    // 手势取消（手指退回去）：从当前位置滑回 0，和手势本身接得上
                    scope.launch {
                        backProgress.animateTo(
                            0f,
                            tween(SLIDE_OUT_MS, easing = SecondaryPageExitEasing),
                        )
                    }
                    throw cancel
                }
                // 手势完成：先让宿主开始播退出动画，同时把跟手位移平滑收回，
                // 两段位移叠加是连续的（不会出现"从 40% 位置啪一下跳到 0"）
                onBack()
                scope.launch {
                    backProgress.animateTo(
                        0f,
                        tween(SLIDE_OUT_MS, easing = SecondaryPageExitEasing),
                    )
                }
            }
        } else {
            BackHandler { onBack() }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()

        // 手势位移套在最外层：顶栏和内容一起退，才不会出现"标题留在原地"的错位
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = backProgress.value.coerceIn(0f, 1f)
                    // 往右退 30% 宽度 + 缩到 0.94：像原生预测性返回那样掀起一角
                    translationX = p * widthPx * 0.3f
                    val s = 1f - 0.06f * p
                    scaleX = s
                    scaleY = s
                },
        ) {
            Scaffold(
                topBar = {
                    NexioTopBar(
                        state = state,
                        title = title,
                        largeTitle = largeTitle,
                        backdrop = backdrop,
                        navigation = onBack?.let { back ->
                            { backdropAlpha, shadowAlpha ->
                                // 圆形玻璃钮旋转 180° 当返回箭头（图标本身不支持下旋转参数）
                                Box(Modifier.rotate(180f)) {
                                    NexioIconButton(
                                        onClick = back,
                                        backdrop = backdrop,
                                        icon = NexioIcons.Basic.ArrowRight,
                                        contentDescription = "返回",
                                        backdropAlpha = backdropAlpha,
                                        shadowAlpha = shadowAlpha,
                                        iconTint = colors.onBackground,
                                    )
                                }
                            }
                        },
                        actions = actions,
                    )
                },
                containerColor = colors.background,
                contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            ) { innerPadding ->
                // 顶栏盖在内容上：内容从屏幕顶端铺到底，靠首项这段 Spacer 让开栏高。
                // 高度用 Scaffold 的实测值；第一帧还没量到（为 0）时退回常数，避免闪一下。
                val measuredTop = innerPadding.calculateTopPadding()
                val contentTopInset = (
                    if (measuredTop > 0.dp) measuredTop
                    else pageTopBarHeight(hasLargeTitle = largeTitle != null)
                    ) + 8.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // ★ 采样层必须包住页底色：layerBackdrop 只录它内层的内容，
                        // 底色写在它前面就被录在图层外 —— 顶栏玻璃会只剩一块灰板
                        .layerBackdrop(backdrop)
                        .background(colors.background)
                        .nestedScroll(state.scrollBehavior.nestedScrollConnection)
                        // 两端弹簧回弹（关掉 foundation 自带的 overscrollEffect，避免叠加）
                        .overScrollVertical()
                        // ⚠ 必须在内层：嵌套滚动由内向外派发，内层才拿得到"没消费掉"的增量
                        .then(pullModifier)
                        .verticalScroll(scrollState, overscrollEffect = null)
                        .padding(horizontal = horizontalPadding),
                ) {
                    // 让位放在滚动内容里（不能套在滚动容器外）：否则滚动视口被裁到栏下，
                    // 内容永远滚不进顶栏糊带，渐进模糊就永远糊不到东西
                    Spacer(Modifier.height(contentTopInset))
                    content()
                    // 底部留白延伸到导航栏下方：内容可以滚到屏幕底，末项又停在栏上
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding() + 24.dp,
                        ),
                    )
                }
            }
        }
    }
}