package com.smalljelly.batterydata.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.softglass.backdrop.GlassBackdrop
import com.haooz.softglass.backdrop.GlassEffectScope
import com.haooz.softglass.backdrop.drawBackdrop
import com.haooz.softglass.backdrop.effects.blur
import com.haooz.softglass.backdrop.effects.runtimeShaderEffect
import com.haooz.softglass.platform.isRenderEffectSupported
import com.haooz.softglass.platform.isRuntimeShaderEffectSupported
import com.smalljelly.batterydata.data.UiDiagnostics
import java.util.concurrent.atomic.AtomicInteger

/**
 * 顶部「渐进模糊」带。
 *
 * 做法和 MiuiX / NexioSchedule 的顶栏材质一致：**不自己复制内容，直接让 SoftGlass 采样**。
 * 调用方把内容容器用 `Modifier.layerBackdrop(backdrop)` 录进 [GlassBackdrop]，
 * 这里再用 `drawBackdrop` 在顶栏区域把那份内容画一遍，中间挂一条 RenderEffect 链：
 *
 * - API 33+：AGSL 着色器，**模糊半径随 Y 从满值递减到 0**（见 [PROGRESSIVE_BLUR_AGSL]）。
 *   因为底端半径趋于 0，带子可以整条保持不透明，下沿和它下面的锐利内容严丝合缝 ——
 *   这正是"渐进"和"一条半透明毛玻璃"的区别：后者会把下面的锐利内容透上来，看起来又淡又灰。
 * - API 31–32：没有 AGSL 图像滤镜，退化成 `blur()` + 竖向渐变 DstIn。
 * - 更低：没有 RenderEffect，退化成表面色渐变遮罩。
 *
 * 采样位置由 SoftGlass 按两个节点的 LayoutCoordinates 自动算，不再依赖"内容层原点必须
 * 和顶栏原点重合"这类约定，滚动、回弹、顶栏折叠都不用手工补偿。
 */
@Composable
fun ProgressiveTopBlurBand(
    /** 内容容器上挂的 backdrop（`Modifier.layerBackdrop(backdrop)`） */
    backdrop: GlassBackdrop,
    /** 顶栏材质强度：0 = 完全隐藏，1 = 完全显现（通常由滚动量驱动） */
    materialAlpha: () -> Float,
    modifier: Modifier = Modifier,
    /** 诊断用页面名 */
    page: String = "band",
    /** 叠在模糊上的表面色浓度（0 = 纯模糊） */
    tintIntensity: Float = 0.18f,
    /**
     * 模糊半径倍率。带子越矮，`高度/6` 算出来的半径越小（下限 14dp），在只盖住
     * 一行按钮的居中标题栏上几乎看不出模糊 —— 那种页面把这个调到 1.6~2 补回来。
     */
    blurRadiusScale: Float = 1f,
    /** 自检模式下印在左上角的现场数字 */
    debugInfo: (() -> String)? = null,
) {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val tint = if (dark) Color(0xFF15161A) else Color(0xFFFFFFFF)
    val currentAlpha by rememberUpdatedState(materialAlpha)
    val engine = remember { BlurEngine.resolve() }
    val selfTest = UiDiagnostics.selfTest

    var bandHeightPx by remember { mutableIntStateOf(0) }
    val nominalRadiusPx = (bandHeightPx / 6f).coerceIn(
        with(density) { 14.dp.toPx() },
        with(density) { 26.dp.toPx() },
    ) * blurRadiusScale

    val shapeBlock: () -> Shape = remember { { RectangleShape } }

    // 每条带子用独立的 shader key：SoftGlass 的 shader 缓存按名字共享实例，
    // 多条带子（首页 / 记录 / 详情可能同时留在组合里）共用会互相覆盖 uniform。
    val shaderKey = remember { "BatteryProgressiveBlur_${shaderSeq.incrementAndGet()}" }
    val denoiseKey = remember(shaderKey) { "${shaderKey}_denoise" }

    // ⚠ drawBackdrop 的 shape / effects 按引用参与节点更新比较：必须 remember 成稳定引用，
    // 每帧新建 lambda 会让节点反复重建采样层（MoreMenu.kt 里踩过 native 崩溃）
    val noEffects: GlassEffectScope.() -> Unit = remember { {} }
    val agslEffects: GlassEffectScope.() -> Unit = remember(
        shaderKey,
        denoiseKey,
        tint,
        tintIntensity,
        blurRadiusScale,
    ) {
        {
            val scale = downsampleScale
            val w = size.width * scale
            val h = size.height * scale
            val radius = radiusPxFor(h) * blurRadiusScale
            // 采样不出界：镜像折回由 shader 自己处理，层不需要外扩
            padding = 0f
            runtimeShaderEffect(shaderKey, PROGRESSIVE_BLUR_AGSL, "content") {
                setFloatUniform("areaSize", w, h)
                setFloatUniform("maxRadius", radius)
                setFloatUniform("edgeFadeStart", 0.92f)
                setColorUniform("tint", tint)
                setFloatUniform("tintIntensity", tintIntensity)
            }
            runtimeShaderEffect(denoiseKey, PROGRESSIVE_DENOISE_AGSL, "content") {
                setFloatUniform("areaSize", w, h)
                setFloatUniform("maxRadius", radius)
            }
        }
    }
    val blurEffects: GlassEffectScope.() -> Unit = remember(blurRadiusScale) {
        {
            // Mirror：Clamp 会把顶边的采样钳到第一行，顶栏最上沿会被拉平成一条"没糊"的带子
            blur(radiusPxFor(size.height * downsampleScale) * blurRadiusScale, TileMode.Mirror)
            // Mirror 会把 padding 撑到 radius，外扩那圈录的是屏幕外（空），压回 0
            padding = 0f
        }
    }
    val fadeMask: Brush = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White,
                0.70f to Color.White,
                0.90f to Color.White.copy(alpha = 0.55f),
                1f to Color.Transparent,
            ),
        )
    }
    // 没有模糊能力时的兜底：表面色渐变（不留纯色硬块，也别糊成一片白）
    val maskGradient: Brush = remember(tint, dark) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to tint.copy(alpha = if (dark) 0.96f else 0.90f),
                0.55f to tint.copy(alpha = if (dark) 0.82f else 0.66f),
                1f to tint.copy(alpha = 0f),
            ),
        )
    }

    // 材质 alpha 为 0 时整条带子不画：省掉一次全屏 shader / blur，列表停在顶部时几乎零开销
    val alphaGate = Modifier.drawWithContent {
        if (currentAlpha() > 0.001f) drawContent()
    }

    val bandModifier = modifier
        .onSizeChanged { if (it.height != bandHeightPx) bandHeightPx = it.height }
        .drawWithContent {
            // 绘制期采样：随滚动每帧刷新，日志诊断页能导出这组数字
            probe(
                page = page,
                bandWidth = size.width.toInt(),
                bandHeight = size.height.toInt(),
                alpha = currentAlpha(),
                radiusPx = nominalRadiusPx,
                engine = engine.label,
            )
            drawContent()
        }

    if (selfTest) {
        // 自检：不加任何效果，把 SoftGlass 采样到的内容原样贴出来并描一圈红框。
        // 有内容 = backdrop 采样正常，问题在效果链；空的 = 内容没录进 backdrop。
        Box(
            bandModifier
                .drawWithContent {
                    drawContent()
                    drawRect(color = Color(0xFFE0433C), style = Stroke(width = 1.5.dp.toPx()))
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = shapeBlock,
                    // 顶栏是通栏矩形：默认的 `Shadow.Default`（黑 10%、6dp）会在矩形下沿
                    // 投出一条阴影 —— 上半截被糊层盖住、下半截露在页面上，交界处就是一条硬边。
                    shadow = null,
                    effects = noEffects,
                ),
        ) {
            debugInfo?.let { info ->
                BasicText(
                    text = info(),
                    style = TextStyle(color = Color(0xFFE0433C), fontSize = 9.sp, lineHeight = 11.sp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 2.dp, top = 2.dp),
                )
            }
        }
        return
    }

    when (engine) {
        BlurEngine.AGSL -> Box(
            bandModifier
                .graphicsLayer { alpha = currentAlpha().coerceIn(0f, 1f) }
                .then(alphaGate)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = shapeBlock,
                    // 通栏矩形不要投影：见 selfTest 分支的注释
                    shadow = null,
                    effects = agslEffects,
                ),
        )

        BlurEngine.BLUR -> Box(
            bandModifier
                .graphicsLayer {
                    alpha = currentAlpha().coerceIn(0f, 1f)
                    // DstIn 作用于整个 canvas 的 dest，不独立成层会把下面的内容一起擦掉
                    compositingStrategy = CompositingStrategy.Offscreen
                    clip = true
                }
                .drawWithContent {
                    drawContent()
                    drawRect(fadeMask, blendMode = BlendMode.DstIn)
                }
                .then(alphaGate)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = shapeBlock,
                    // 通栏矩形不要投影：见 selfTest 分支的注释
                    shadow = null,
                    effects = blurEffects,
                ),
        )

        BlurEngine.MASK -> Box(
            bandModifier
                .graphicsLayer { alpha = currentAlpha().coerceIn(0f, 1f) }
                .then(alphaGate)
                .background(maskGradient),
        )
    }
}

/**
 * 同样的口径，但作用在降采样后的坐标里（SoftGlass 的 blur 半径按 downsampleScale 换算）
 */
private fun GlassEffectScope.radiusPxFor(areaHeightPx: Float): Float {
    val minPx = 14.dp.toPx() * downsampleScale
    val maxPx = 26.dp.toPx() * downsampleScale
    if (areaHeightPx <= 0f) return minPx
    return (areaHeightPx / 6f).coerceIn(minPx, maxPx)
}

/** 绘制期采样：只赋值，不分配、不 IO */
private fun probe(
    page: String,
    bandWidth: Int,
    bandHeight: Int,
    alpha: Float,
    radiusPx: Float,
    engine: String,
) {
    val sample = UiDiagnostics.band(page)
    sample.bandWidth = bandWidth
    sample.bandHeight = bandHeight
    sample.alpha = alpha
    sample.blurRadiusPx = radiusPx
    sample.engine = engine
    sample.drawCalls++
    sample.lastUpdateMs = System.currentTimeMillis()
}

/**
 * 渲染路径：按系统能力从高到低选。
 *
 * AGSL 那条会**先用真正的 RuntimeShader 试编译一次**——着色器是运行期编译的，
 * 语法有问题会直接抛异常崩掉进程；编译不过就退回模糊兜底，不至于整块顶栏消失。
 */
private enum class BlurEngine(val label: String) {
    AGSL("AGSL 渐进模糊（半径随 Y 递减）"),
    BLUR("模糊 + 渐变淡出（API 31–32）"),
    MASK("渐变遮罩（无模糊能力）");

    companion object {
        private val shadersCompile: Boolean by lazy {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                false
            } else {
                runCatching { android.graphics.RuntimeShader(PROGRESSIVE_BLUR_AGSL) }.isSuccess &&
                    runCatching { android.graphics.RuntimeShader(PROGRESSIVE_DENOISE_AGSL) }.isSuccess
            }
        }

        fun resolve(): BlurEngine = when {
            isRuntimeShaderEffectSupported() && shadersCompile -> AGSL
            isRenderEffectSupported() -> BLUR
            else -> MASK
        }
    }
}

/** 着色器名序号：SoftGlass 的 shader 缓存按名字索引，每条带子要各自一份 */
private val shaderSeq = AtomicInteger(0)
