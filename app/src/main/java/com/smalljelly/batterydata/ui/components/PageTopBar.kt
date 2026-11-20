package com.smalljelly.batterydata.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.haooz.softglass.backdrop.GlassBackdrop
import com.smalljelly.batterydata.data.UiDiagnostics
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 记录页 / 详情页的顶栏：**有大标题，但高度是常数**。
 *
 * 为什么不直接用 Miuix 的 `TopAppBar`：
 * 它在大标题收起的过程中会把**自身高度**从展开态 lerp 到 52dp。而列表的顶部让位必须是
 * 常数（跟着变的话，滚一下内容会被"多推"一次），之前锁成展开态的最大值，收起后就凭空
 * 多出 66dp 空白 —— 那正是"顶部留白太多"的来源。
 *
 * 这里的做法：栏的总高度钉死，大标题块**留在栏内**、随滚动淡出。因为让位 = 栏高是常数，
 * 大标题让出来的那块区域正好被"已经滚上来"的内容填满，既没有空白，也不会二次位移。
 * 模糊带覆盖整条栏，所以模糊的高度也和原来一致，不会再变成一条 54dp 的窄条。
 *
 * 版式对齐 Miuix / MIUI 的大标题页：
 * - 第一行：左返回 / 右操作，标题居中，收起后才淡入；
 * - 第二行：大标题（[PageTopBarLargeTitle]）+ 副标题，左对齐，随滚动淡出并轻微上移。
 */
@Composable
fun PageTopBar(
    /** 收起后的小标题 */
    title: String,
    /**
     * 展开时的大标题（通常是更完整的说法，如「充电记录」）。
     * 传 null 则栏里只有第一行 —— 用于「居中标题」型页面（日志详情），
     * 栏高随之变成 状态栏 + 按钮行，模糊带也自动只盖这一段。
     */
    largeTitle: String?,
    backdrop: GlassBackdrop,
    /** 顶栏材质强度：0 = 完全隐藏，1 = 完全显现（滚动或拖动时淡入） */
    materialAlpha: () -> Float,
    /** 折叠进度 0..1：0 = 大标题完全展开，1 = 只剩第一行 */
    collapseProgress: () -> Float,
    /** 诊断日志里的页面名（home / records / detail） */
    page: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    /** 左侧按钮与屏幕边的距离 */
    startPadding: Dp = 16.dp,
    /** 右侧按钮与屏幕边的距离 */
    endPadding: Dp = 16.dp,
    navigation: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * 模糊带向下多盖出一段（默认 0）。栏子矮的页面（居中标题型）靠它把"渐进"的衰减
     * 拉长，看起来才是材质而不是一条淡影。
     *
     * ⚠ 它是**真实的栏高**：栏节点会高这么多，Scaffold 量到的 innerPadding 也跟着变大。
     * 调用方如果不想让正文整体下移，就在自己的 `contentPadding.top` 里减掉同一个值
     * （日志详情就是这么做的）。绝不能让带子画到节点外面 —— 那样带子整条都不画了。
     */
    blurOverhang: Dp = 0.dp,
    /** 模糊半径倍率，见 [ProgressiveTopBlurBand] */
    blurRadiusScale: Float = 1f,
    /** 表面色浓度，见 [ProgressiveTopBlurBand] */
    tintIntensity: Float = 0.18f,
    debugInfo: (() -> String)? = null,
) {
    val colors = MiuixTheme.colorScheme
    // 栏的实测高度：写进诊断，用来和列表的让位对比
    var barHeightPx by remember { mutableIntStateOf(0) }
    // 栏内容的理论高度（和让位用的常数完全一致），模糊带按它 + 溢出段铺开
    val barContentHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        PageTopBarRowHeight + (if (largeTitle != null) PageTopBarLargeTitleHeight else 0.dp)

    Box(
        modifier
            .fillMaxWidth()
            .zIndex(10f)
            .onSizeChanged {
                if (it.height != barHeightPx) {
                    barHeightPx = it.height
                    UiDiagnostics.band(page).barHeightPx = it.height
                }
            },
    ) {
        // 模糊带盖住整条栏（状态栏 + 按钮行 + 大标题块），按需多盖一段
        ProgressiveTopBlurBand(
            backdrop = backdrop,
            page = page,
            materialAlpha = materialAlpha,
            tintIntensity = tintIntensity,
            blurRadiusScale = blurRadiusScale,
            debugInfo = debugInfo,
            modifier = Modifier
                .fillMaxWidth()
                .height(barContentHeight + blurOverhang),
        )

        Column {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            // 第一行：按钮 + 收起态的小标题
            Box(
                modifier = Modifier.fillMaxWidth().height(PageTopBarRowHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.title3.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 76.dp)
                        // 小标题只在大标题收起后出现，读在绘制期，滚动不触发重组；
                        // 没有大标题时（居中标题型页面）它本来就是主角，常亮
                        .graphicsLayer {
                            alpha = if (largeTitle == null) 1f else collapseProgress().coerceIn(0f, 1f)
                        },
                )

                if (navigation != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = startPadding),
                    ) {
                        navigation()
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = endPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    content = actions,
                )
            }

            // 大标题块：高度固定，展开时露出、收起时让内容透上来
            if (largeTitle != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PageTopBarLargeTitleHeight)
                        .padding(horizontal = 20.dp)
                        .graphicsLayer {
                            val p = collapseProgress().coerceIn(0f, 1f)
                            alpha = 1f - p
                            // 轻微上移，收起时像被内容"顶"走，而不是原地消失
                            translationY = -p * 14.dp.toPx()
                        },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = largeTitle,
                        fontSize = PageTopBarLargeTitle,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MiuixTheme.textStyles.body2,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 顶栏第一行（按钮行）高度，不含状态栏 */
val PageTopBarRowHeight: Dp = 48.dp

/** 大标题块高度：容得下 30sp 大标题 + 12sp 副标题 */
val PageTopBarLargeTitleHeight: Dp = 62.dp

/** 大标题字号 */
private val PageTopBarLargeTitle = 30.sp

/**
 * 顶栏总高度（状态栏 + 按钮行 + 大标题块）。高度是常数，内容让位直接用这个值，
 * 不再去量顶栏、也不依赖 Scaffold 的 innerPadding —— 测量值晚一帧到就会让内容跳一下。
 *
 * [hasLargeTitle] 传 false 时对应「只有按钮行」的居中标题型顶栏（日志详情）。
 */
@Composable
fun pageTopBarHeight(hasLargeTitle: Boolean = true): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        PageTopBarRowHeight + (if (hasLargeTitle) PageTopBarLargeTitleHeight else 0.dp)

/**
 * 折叠进度：把滚动偏移换算成 0..1。
 *
 * 换算距离取「大标题块高度 + 8dp」——内容的顶部让位是 `栏高 + 8dp`，
 * 所以滚到这段距离时，第一张卡正好贴在按钮行的下边缘，大标题刚好完全收起，
 * 两者同时到位，中间不会露出一段空白。
 */
@Composable
fun rememberTopBarCollapseProgress(offsetPx: () -> Int): () -> Float {
    val distance = with(LocalDensity.current) {
        (PageTopBarLargeTitleHeight + 8.dp).toPx()
    }
    return remember(distance) { { (offsetPx() / distance).coerceIn(0f, 1f) } }
}

