package com.smalljelly.batterydata.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import com.haooz.nexioui.icon.basic.ArrowUpDown
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.ui.components.AppPage
import com.smalljelly.batterydata.ui.components.SlideProgress
import com.smalljelly.batterydata.ui.components.rememberSlideProgress
import com.smalljelly.batterydata.ui.components.menuDropdownColors
import com.smalljelly.batterydata.ui.theme.AppMaterial
import com.smalljelly.batterydata.ui.theme.MaterialOption
import com.smalljelly.batterydata.ui.theme.MaterialOptions
import com.smalljelly.batterydata.ui.theme.materialOptionOf

/**
 * 「外观与动画」二级页：视觉材质档 + 预见式返回动画。
 *
 * 这两项都是"看起来/摸起来"的设置，从关于页拆出来单独成页：
 * 关于页只留应用身份信息，功能型开关全部归到设置里。
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    slideProgress: SlideProgress = rememberSlideProgress(true),
) {
    val colors = NexioTheme.colorScheme
    val context = LocalContext.current
    val settings = remember { BatterySettings(context.applicationContext) }
    val backdrop = rememberLayerBackdrop()

    var materialLevel by remember { mutableStateOf(settings.materialLevel()) }
    var predictiveBack by remember { mutableStateOf(settings.predictiveBackEnabled()) }
    // 下拉展开进度：驱动右侧胶囊淡出 + 轻微模糊（和库的面板动画同步）
    var materialFraction by remember { mutableFloatStateOf(0f) }
    var materialFullyDismissed by remember { mutableStateOf(true) }
    var showMaterialMenu by remember { mutableStateOf(false) }
    var materialChipSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(showMaterialMenu) { if (showMaterialMenu) materialFullyDismissed = false }

    AppPage(
        title = "外观与动画",
        largeTitle = "外观与动画",
        backdrop = backdrop,
        onBack = onBack,
        slideProgress = slideProgress,
        horizontalPadding = 24.dp,
    ) {
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
                            onClick = { showMaterialMenu = true },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    NexioText(
                        text = "材质",
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    // 锚点 = 这颗材质胶囊（预览块 + 名称 + 双箭头），面板从它长大
                    Box {
                        Row(
                            modifier = Modifier
                                .onGloballyPositioned { materialChipSize = it.size }
                                .graphicsLayer {
                                    val f = materialFraction
                                    val a = if (materialFullyDismissed) 1f
                                        else if (showMaterialMenu) (1f - f / 0.18f).coerceIn(0f, 1f)
                                        else 0f
                                    alpha = a
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val r = (1f - a) * 3f * density
                                        renderEffect = if (r > 0.1f) {
                                            BlurEffect(r, r, TileMode.Clamp)
                                        } else {
                                            null
                                        }
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MaterialSwatch(materialOptionOf(materialLevel))
                            Spacer(Modifier.width(8.dp))
                            NexioText(
                                text = materialOptionOf(materialLevel).label,
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

                        OverlayDropdownPopup(
                            entry = remember(materialLevel) {
                                DropdownEntry(
                                    items = MaterialOptions.map { option ->
                                        DropdownItem(
                                            text = option.label,
                                            selected = option.storage == materialLevel,
                                            onClick = {
                                                materialLevel = option.storage
                                                settings.setMaterialLevel(option.storage)
                                                // 立刻套到 NexioUI + SoftGlass，所有页面同步重组
                                                AppMaterial.apply(option)
                                            },
                                        )
                                    },
                                )
                            },
                            show = showMaterialMenu,
                            onDismiss = { showMaterialMenu = false },
                            onDismissFinished = {
                                materialFullyDismissed = true
                                materialFraction = 0f
                            },
                            onFractionProgress = { materialFraction = it },
                            // 跟手滑选：按住面板上下滑动连续切换材质档
                            dragSelectState = remember { DropdownPanelDragSelectState(maskMode = DropdownMaskMode.Sliding) },
                            maxHeight = null,
                            dropdownColors = menuDropdownColors(),
                            // 挂到最外层 Scaffold 的弹层宿主，面板落在被采样的内容层之外
                            renderInRootScaffold = true,
                            // 「关闭」档不给采样层，库会跳过 RenderEffect 走不透明底
                            liquidGlassBackdrop = if (AppMaterial.current.panelBlurDp > 0.dp) backdrop else null,
                            collapseSize = materialChipSize.takeIf { it.width > 0 && it.height > 0 },
                            // 收起态玻璃矩形与胶囊实测尺寸零补偿：归位不会跳一下
                            collapseExtra = DpSize.Zero,
                            anchorOffsetX = 0.dp,
                            anchorOffsetY = 0.dp,
                            collapseContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MaterialSwatch(materialOptionOf(materialLevel))
                                    Spacer(Modifier.width(8.dp))
                                    NexioText(
                                        text = materialOptionOf(materialLevel).label,
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

        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        ) {
            ToggleRow(
                title = "预见式返回动画",
                summary = if (predictiveBack) {
                    "返回手势跟手：滑多少退多少，露出下面的页面"
                } else {
                    "已关闭：返回只播普通滑出动画"
                },
                checked = predictiveBack,
                onCheckedChange = {
                    predictiveBack = it
                    settings.setPredictiveBackEnabled(it)
                    // 同步到全局状态：所有页面的 AppPage 立刻换返回方式，不用重进页面
                    com.smalljelly.batterydata.ui.theme.AppMotion.applyPredictiveBack(it)
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        NexioText(
            text = "「关闭」材质不糊任何东西，纯实色；其余档位模糊由轻到重。预见式返回需要系统支持预测性返回手势。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

/**
 * 材质档的实时预览：一小块彩色渐变，按当前档位做模糊 + 半透明白色覆盖，
 * 直接反映"通透 / 奶白"的差别，不用切到首页去比对底栏。
 */
@Composable
internal fun MaterialSwatch(option: MaterialOption) {
    val dark = isSystemInDarkTheme()
    val shape = remember { RoundedCornerShape(5.dp) }
    // 弹窗卡是实心的，预览块只表达"背后糊到什么程度"
    val alpha = 1f
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 18.dp)
            .clip(shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4C8DFF), Color(0xFF34C759), Color(0xFFFFB020)),
                    ),
                )
                .then(
                    if (option.panelBlurDp > 0.dp) {
                        Modifier.blur(option.panelBlurDp * 0.4f)
                    } else {
                        Modifier
                    },
                ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background((if (dark) Color(0xFF1C1C1E) else Color.White).copy(alpha = alpha)),
        )
    }
}