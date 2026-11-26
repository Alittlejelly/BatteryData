package com.smalljelly.batterydata.ui.components

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.dropdown.DropdownEntry
import com.haooz.nexioui.dropdown.DropdownItem
import com.haooz.nexioui.dropdown.DropdownMaskMode
import com.haooz.nexioui.dropdown.DropdownPanelDragSelectState
import com.haooz.nexioui.dropdown.OverlayDropdownPopup
import com.haooz.nexioui.icon.NexioIcons
import com.haooz.nexioui.icon.basic.ArrowRight
import com.haooz.nexioui.icon.basic.ArrowUpDown
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.smalljelly.batterydata.data.UpdateChecker
import com.smalljelly.batterydata.ui.theme.AppMaterial

/**
 * 「检查更新」行：标题 + 右侧状态（当前版本 / 正在检查 / 发现新版本 / 已是最新）+ 箭头。
 *
 * 放在关于页：更新是这个应用自己的事，归到"关于"比塞进设置更自然。
 */
@Composable
fun CheckUpdateCard(
    versionName: String,
    checking: Boolean,
    result: UpdateChecker.Result?,
    onClick: () -> Unit,
) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 不要按压波纹：卡片内缩的矩形波纹很突兀，反馈只靠右侧状态文案变化
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !checking,
                    onClick = onClick,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NexioText(text = "检查更新", fontSize = 16.sp, color = colors.onSurfaceContainer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                NexioText(
                    text = when {
                        checking -> "正在检查…"
                        result == null -> "当前 $versionName"
                        result.hasUpdate -> "发现新版本"
                        else -> "已是最新"
                    },
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = NexioIcons.Basic.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 「更新源」行：右侧是可下拉的胶囊。
 *
 * 下拉面板的收起态必须与胶囊实测尺寸零补偿贴合，归位才不会"缩到旁边再跳一下"，
 * 所以 [collapseSize] / [anchorOffset] 都由这里统一给。
 */
@Composable
fun UpdateSourceCard(
    source: UpdateChecker.Source,
    onPick: (UpdateChecker.Source) -> Unit,
    backdrop: com.haooz.softglass.backdrop.backdrops.LayerBackdrop,
) {
    val colors = NexioTheme.colorScheme
    var showMenu by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var fullyDismissed by remember { mutableStateOf(true) }
    var chipSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(showMenu) { if (showMenu) fullyDismissed = false }

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showMenu = true },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                NexioText(text = "更新源", fontSize = 16.sp, color = colors.onSurfaceContainer)
                // 锚点 = 这颗值胶囊：下拉收起态的矩形就是它，面板从胶囊里长开。
                // ⚠ 必须用一个独立的 Box 把它和浮层包在一起：OverlayDropdownPopup 也是
                // 这个 Row 的子节点，直接平铺会让 SpaceBetween 把它当成第三列，
                // 胶囊就被挤到中间去了（文案"移位"就是这么来的）。
                Box {
                    Row(
                        modifier = Modifier
                            .onGloballyPositioned { chipSize = it.size }
                            // 展开时随 fraction 迅速淡出 + 轻微模糊（绘制期读状态，逐帧不重组）
                            .graphicsLayer {
                                val a = if (fullyDismissed) 1f
                                    else if (showMenu) (1f - fraction / 0.18f).coerceIn(0f, 1f)
                                    else 0f
                                alpha = a
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val r = (1f - a) * 3f * density
                                    renderEffect = if (r > 0.1f) BlurEffect(r, r, TileMode.Clamp) else null
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NexioText(
                            text = source.label,
                            fontSize = 14.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.width(6.dp))
                        // 上下双箭头 = 这是下拉选择，不是跳转
                        Icon(
                            imageVector = NexioIcons.Basic.ArrowUpDown,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = colors.onSurfaceVariantSummary,
                        )
                    }

                    OverlayDropdownPopup(
                    entry = remember(source) {
                        DropdownEntry(
                            items = UpdateChecker.Source.entries.map { option ->
                                DropdownItem(
                                    text = option.label,
                                    selected = option == source,
                                    onClick = { onPick(option) },
                                )
                            },
                        )
                    },
                    show = showMenu,
                    onDismiss = { showMenu = false },
                    onDismissFinished = {
                        fullyDismissed = true
                        fraction = 0f
                    },
                    onFractionProgress = { fraction = it },
                    // 跟手滑选：按住面板上下滑动连续选择更新源
                    dragSelectState = remember { DropdownPanelDragSelectState(maskMode = DropdownMaskMode.Sliding) },
                    maxHeight = null,
                    dropdownColors = menuDropdownColors(),
                    // 挂到最外层 Scaffold 的弹层宿主：面板落在被采样的内容层之外
                    renderInRootScaffold = true,
                    liquidGlassBackdrop = if (AppMaterial.current.panelBlurDp > 0.dp) backdrop else null,
                    collapseSize = chipSize.takeIf { it.width > 0 && it.height > 0 },
                    // 收起态玻璃矩形与胶囊实测尺寸零补偿，交接时看不见位移
                    collapseExtra = DpSize.Zero,
                    anchorOffsetX = 0.dp,
                    anchorOffsetY = 0.dp,
                    collapseContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NexioText(
                                text = source.label,
                                fontSize = 14.sp,
                                color = colors.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = NexioIcons.Basic.ArrowUpDown,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = colors.onSurfaceVariantSummary,
                            )
                        }
                    },
                )
                }
            }
        }
    }
}