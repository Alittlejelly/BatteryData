package com.smalljelly.batterydata.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.min
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import com.haooz.nexioui.icon.NexioIcons
import com.haooz.nexioui.icon.extended.Settings

/**
 * 底栏页签图标。
 *
 * NexioUI 的 [com.haooz.nexioui.bottombar.BottomBar] 只收 `ImageVector`，而它自带的图标集里
 * 没有这类通用图形：
 * - 「充电」用 miuix 的 `ListView` 补上；
 * - 「电池」miuix 与 NexioUI 都没有对应图标，只能按原先自绘的比例做成矢量；
 * - 「实时」用自绘闪电；
 * - 「设置」直接取 NexioUI 的 extended 图标。
 *
 * 颜色一律交给 `Icon` 的 tint，这里只用不透明黑，矢量本身不带语义色。
 */

/** 「充电」：清单图标，直接取 miuix 图标集 */
val RecordsTabIcon: ImageVector by lazy { MiuixIcons.ListView }

/** 「设置」：齿轮，取 NexioUI 扩展图标 */
val SettingsTabIcon: ImageVector by lazy { NexioIcons.Settings }

/** 「实时」：闪电，自绘矢量和电池图标同一套线条口径 */
val RealtimeTabIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "RealtimeTab",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(13.4f, 2.6f)
            lineTo(5.2f, 13.6f)
            horizontalLineTo(11.2f)
            lineTo(10.2f, 21.4f)
            lineTo(18.6f, 10.2f)
            horizontalLineTo(12.4f)
            close()
        }
        .build()
}

/** 「电池」：一节横向电池，右侧接线端，左侧电量块（两个图标集都没有，手绘矢量） */
val BatteryTabIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BatteryTab",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            roundedRect(left = 1.2f, top = 7.4f, right = 19.6f, bottom = 16.6f, radius = 2.8f)
        }
        .path(fill = SolidColor(Color.Black)) {
            roundedRect(left = 20.8f, top = 10.8f, right = 22.6f, bottom = 13.2f, radius = 1.2f)
        }
        .path(fill = SolidColor(Color.Black)) {
            roundedRect(left = 3.4f, top = 9.4f, right = 12.6f, bottom = 14.6f, radius = 1.2f)
        }
        .build()
}

/** PathBuilder 上的圆角矩形（ImageVector 的 pathData 没有现成的 roundRect） */
private fun PathBuilder.roundedRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
) {
    val r = min(radius, min((right - left) / 2f, (bottom - top) / 2f))
    moveTo(left + r, top)
    horizontalLineTo(right - r)
    arcToRelative(r, r, 0f, false, true, r, r)
    verticalLineTo(bottom - r)
    arcToRelative(r, r, 0f, false, true, -r, r)
    horizontalLineTo(left + r)
    arcToRelative(r, r, 0f, false, true, -r, -r)
    verticalLineTo(top + r)
    arcToRelative(r, r, 0f, false, true, r, -r)
    close()
}