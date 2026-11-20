package com.smalljelly.batterydata.data

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 顶栏渐进模糊的现场采样。
 *
 * 这块效果的排查一直是"凭感觉"：看不见模糊时，分不清是
 * ①顶栏模糊带尺寸是 0、②backdrop 没录到内容、③录到了但透明度/模糊没生效、
 * ④采到的是空白区域。所以这里在**绘制期**把每一条模糊带的关键数字记下来
 * （纯字段赋值，不分配、不 IO、不进组合），日志诊断页一次性导出成文本。
 *
 * 只在主线程读写：绘制、导出都在 UI 线程。
 */
object UiDiagnostics {

    /** 模糊是否可用：BlurEffect 需要 Android 12（API 31）以上 */
    val blurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 一条模糊带的现场 */
    class BandSample(val page: String) {
        /** 模糊带自身尺寸（px）：0 说明模糊带没参与布局 */
        var bandWidth: Int = 0
        var bandHeight: Int = 0

        /** 顶栏实测高度（px）：带子比它高出来的那部分是故意溢出到内容里的 */
        var barHeightPx: Int = 0

        /** 被画进顶栏的次数 */
        var drawCalls: Long = 0

        /** 当前不透明度与模糊半径 */
        var alpha: Float = 0f
        var blurRadiusPx: Float = 0f

        /** 实际走的是哪条渲染路径（AGSL 渐进 / 模糊 + 淡出 / 渐变遮罩） */
        var engine: String = ""

        /** 采样这一刻的滚动位置 */
        var scrollIndex: Int = 0
        var scrollOffset: Int = 0

        /** 列表顶部给顶栏让出的高度（px）：和 bandHeight 对不上就说明卡片钻进了顶栏 */
        var contentTopInsetPx: Int = 0

        var lastUpdateMs: Long = 0

        /** 自动判读：把"看不出模糊"的几种可能逐条排除 */
        fun verdict(): String = when {
            bandWidth <= 0 || bandHeight <= 0 -> "模糊带尺寸为 0，没参与布局"
            drawCalls <= 0L -> "模糊带从未被绘制"
            engine.startsWith("渐变遮罩") -> "系统不支持模糊（API < 31），只有渐变遮罩"
            alpha <= 0.01f ->
                "不透明度为 0：页面停在顶部且没有拖动。栏下此刻是空背景，没有东西可糊，" +
                    "属于「正常但看不到」—— 往上滑或拖动一下再看"
            barHeightPx > 0 && contentTopInsetPx in 1 until barHeightPx ->
                "列表顶部让位比顶栏矮：静止时卡片就钻进了顶栏（让位要 ≥ 顶栏高度 + 8dp）"
            else -> "参数正常，模糊应可见（若仍看不见，开自检模式对比）"
        }
    }

    private val samples = LinkedHashMap<String, BandSample>()

    /** 绘制期调用：同一个 page 只创建一次对象，之后都是字段覆盖 */
    fun band(page: String): BandSample = samples.getOrPut(page) { BandSample(page) }

    fun snapshot(): List<BandSample> = samples.values.toList()

    /**
     * 自检模式：模糊带不加任何效果，把 SoftGlass 采样到的内容原样贴出来并描一圈红框。
     * 有内容 = backdrop 采样正常，问题在效果链；还是空的 = 内容没录进 backdrop。
     * 用 snapshot state，切换后三页立刻跟着变。
     */
    var selfTest by mutableStateOf(false)
}
