package com.smalljelly.batterydata.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
 *   位移逻辑在 [PredictiveBackBox]，日志页等手写 Scaffold 的页面共用同一份。
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
    /** 宿主 [SlideInHost] 的进度；手势从这里写，位移也由它渲染 */
    slideProgress: SlideProgress = rememberSlideProgress(true),
    actions: (@Composable (backdropAlpha: Float, shadowAlpha: Float) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = NexioTheme.colorScheme
    val scrollState = rememberScrollState()
    // 短页面拖不动时，靠下拉位移把顶栏材质顶出来（和折叠滚动同一个连接）
    val (pullModifier, _) = rememberTopBarPull()

    // 退出时的位移由宿主的 SlideInHost 负责（它持有唯一进度），这里只补返回键/手势采集。
    // ⚠ 千万不能在这里再套一层位移：两个进度同时写 → 松手时闪一下
    PredictiveBackBox(
        onBack = onBack ?: {},
        enabled = onBack != null,
        progress = slideProgress,
    )

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