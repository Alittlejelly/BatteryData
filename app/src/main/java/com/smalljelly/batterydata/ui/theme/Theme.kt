package com.smalljelly.batterydata.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haooz.nexioui.material.NexioMaterial
import com.haooz.nexioui.material.NexioMaterialLevel
import com.haooz.nexioui.theme.NexioColorScheme
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.GlassMaterialLevel
import com.smalljelly.batterydata.data.BatterySettings

/**
 * 可选的材质档。
 *
 * NexioUI 的 `NexioMaterial.level` 只管库内部的玻璃（下拉 / 弹窗 / 按钮），而且
 * 三档的模糊只有 12dp / 8dp / 12dp 的差别，肉眼几乎分不出来 —— 所以只靠它切档，
 * 用户会觉得"除了关闭都没效果"。
 *
 * 因此每一档另外带一组我们自己的参数，作用在**始终可见**的面上：
 * - [bottomBlurDp]：底栏胶囊 / 悬浮圆按钮的模糊半径
 * - [surfaceAlphaScale]：这些面的底色不透明度倍率（越小越透，越大越奶白）
 * - [topBlurScale] / [topTintScale]：顶栏模糊带的半径与白色浓度倍率
 * - [panelBlurDp]：悬浮小面（更多菜单 / 材质预览块）的模糊半径。
 *   弹窗不再读这个值 —— 弹窗玻璃已全部交给 NexioUI 的 `OverlayDialog`，
 *   模糊半径由库的 `NexioMaterial.glassDialogBlurDp()` 决定。
 *
 * 三档的观感因此是：液态更通透、柔和居中、磨砂更厚更白，关闭全部实色无模糊。
 */
data class MaterialOption(
    val storage: String,
    val label: String,
    val description: String,
    val level: NexioMaterialLevel,
    val glassLevel: GlassMaterialLevel,
    val bottomBlurDp: Dp,
    val surfaceAlphaScale: Float,
    val topBlurScale: Float,
    val topTintScale: Float,
    val panelBlurDp: Dp,
)

val MaterialOptions = listOf(
    MaterialOption(
        storage = "soft",
        label = "柔和",
        description = "轻微磨砂，内容隐约可见",
        level = NexioMaterialLevel.Soft,
        glassLevel = GlassMaterialLevel.SOFT,
        bottomBlurDp = 12.dp,
        surfaceAlphaScale = 1f,
        topBlurScale = 1f,
        topTintScale = 1f,
        panelBlurDp = 12.dp,
    ),
    MaterialOption(
        storage = "liquid",
        label = "液态",
        description = "最通透，几乎不挡内容",
        level = NexioMaterialLevel.Liquid,
        glassLevel = GlassMaterialLevel.LIQUID,
        bottomBlurDp = 5.dp,
        surfaceAlphaScale = 0.5f,
        topBlurScale = 0.45f,
        topTintScale = 0.45f,
        panelBlurDp = 8.dp,
    ),
    MaterialOption(
        storage = "frosted",
        label = "磨砂",
        description = "厚模糊，像毛玻璃一样奶白",
        level = NexioMaterialLevel.Frosted,
        glassLevel = GlassMaterialLevel.FROSTED,
        bottomBlurDp = 28.dp,
        surfaceAlphaScale = 1.28f,
        topBlurScale = 2.2f,
        topTintScale = 2f,
        panelBlurDp = 24.dp,
    ),
    MaterialOption(
        storage = "none",
        label = "关闭",
        description = "不模糊，全部实色",
        level = NexioMaterialLevel.None,
        glassLevel = GlassMaterialLevel.NONE,
        bottomBlurDp = 0.dp,
        surfaceAlphaScale = 1.6f,
        topBlurScale = 0f,
        topTintScale = 5.5f,
        panelBlurDp = 0.dp,
    ),
)

fun materialOptionOf(storage: String): MaterialOption =
    MaterialOptions.firstOrNull { it.storage == storage } ?: MaterialOptions.first()

/**
 * 当前生效的材质档。用 Compose 的 SnapshotState 持有：设置页一改，
 * 所有读 [current] 的页面（顶栏、底栏、弹窗）立刻重组，不用重启。
 */
object AppMaterial {
    var current by mutableStateOf(MaterialOptions.first())
        private set

    fun apply(option: MaterialOption) {
        current = option
        NexioMaterial.level = option.level
        // 弹窗（贴底对话框）的玻璃模糊也走这里：库自带三档是 12 / 8 / 12dp，
        // 肉眼几乎分不出，用我们这组值（柔和 12 / 液态 8 / 磨砂 24）设置页才看得出来。
        NexioMaterial.dialogBlurDpOverride =
            if (option.level == NexioMaterialLevel.None) null else option.panelBlurDp.value
    }
}

/**
 * NexioUI 的语义色板没有默认值，必须整份给全（40 个角色）。
 * 这里把品牌色（背景 / 卡片 / 文字）套上去，其余沿用库里的默认值——
 * 那些是开关、滑块、禁用态用的，改品牌色不需要动。
 */
private val LightColors = NexioColorScheme(
    primary = ChartBlue,
    onPrimary = Color.White,
    primaryContainer = ChartBlue,
    onPrimaryContainer = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightBackground,
    onSurface = LightTextPrimary,
    surfaceVariant = LightCard,
    onSurfaceVariant = LightTextSecondary,
    onSurfaceVariantSummary = LightTextSecondary,
    onSurfaceVariantActions = LightTextSecondary,
    outline = Color(0xFFD9D9D9),
    dividerLine = LightDivider,
    secondary = LightBackground,
    onSecondary = Color.White,
    onSecondaryVariant = LightTextPrimary,
    disabledSecondaryVariant = Color(0xFFF2F2F2),
    secondaryContainer = Color(0xFFEEEEF0),
    onSecondaryContainer = LightTextSecondary,
    tertiaryContainer = Color(0xFFEAF2FF),
    onTertiaryContainer = ChartBlue,
    secondaryVariant = Color(0xFFF0F0F0),
    onSurfaceSecondary = LightTextSecondary,
    surfaceContainer = LightCard,
    onSurfaceContainer = LightTextPrimary,
    onSurfaceContainerHigh = LightTextSecondary,
    onSurfaceContainerHighest = LightTextPrimary,
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    error = BatteryRed,
    disabledPrimarySlider = Color(0xFFB8CFF5),
    disabledPrimaryButton = Color(0xFFC2D9FF),
    disabledOnPrimaryButton = Color(0xFFFFFFFF),
    disabledSecondary = Color(0xFFF0F0F0),
    disabledOnPrimary = Color(0xFFF3F8FF),
    disabledOnSurface = Color(0xFFB2B2B2),
    disabledOnSecondaryVariant = Color(0xFFB2B2B2),
    sliderBackground = Color(0x0F000000),
    sliderKeyPoint = Color(0x4DA3B3CD),
    sliderKeyPointForeground = Color(0xFF6EB5FF),
)

private val DarkColors = NexioColorScheme(
    primary = ChartBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF338FE4),
    onPrimaryContainer = Color.White,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkBackground,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkCard,
    onSurfaceVariant = DarkTextSecondary,
    onSurfaceVariantSummary = DarkTextSecondary,
    onSurfaceVariantActions = DarkTextSecondary,
    outline = Color(0xFF404040),
    dividerLine = DarkDivider,
    secondary = DarkCard,
    onSecondary = Color.White,
    onSecondaryVariant = DarkTextPrimary,
    disabledSecondaryVariant = Color(0xFF404040),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = DarkTextSecondary,
    tertiaryContainer = Color(0xFF13233C),
    onTertiaryContainer = Color(0xFF7FB0FF),
    secondaryVariant = Color(0xFF2C2C2E),
    onSurfaceSecondary = DarkTextSecondary,
    surfaceContainer = DarkCard,
    onSurfaceContainer = DarkTextPrimary,
    onSurfaceContainerHigh = DarkTextSecondary,
    onSurfaceContainerHighest = DarkTextPrimary,
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    error = BatteryRed,
    disabledPrimarySlider = Color(0xFF44587C),
    disabledPrimaryButton = Color(0xFF253E64),
    disabledOnPrimaryButton = Color(0xFF677893),
    disabledSecondary = Color(0xFF3F3F3F),
    disabledOnPrimary = Color(0xFF677993),
    disabledOnSurface = Color(0xFF666666),
    disabledOnSecondaryVariant = Color(0xFF707170),
    sliderBackground = Color(0x26FFFFFF),
    sliderKeyPoint = Color(0x4D7A8AA6),
    sliderKeyPointForeground = Color(0xFF5DAAFF),
)

@Composable
fun BatteryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme) { if (darkTheme) DarkColors else LightColors }
    // 启动时把用户选的材质档套到 NexioUI + SoftGlass 上（只跑一次；应用内改档由设置页直接 apply）
    val context = LocalContext.current
    val storedOption = remember {
        materialOptionOf(BatterySettings(context.applicationContext).materialLevel())
    }
    LaunchedEffect(storedOption) { AppMaterial.apply(storedOption) }
    NexioTheme(isDark = darkTheme, colorScheme = colors, content = content)
}
