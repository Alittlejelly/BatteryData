package com.smalljelly.batterydata.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale

/**
 * 把一张录好的卡片图层当图片画出来。
 *
 * 照 Nexio 的 `TransitionLayerImage`：不分配位图，直接重放绘制命令，
 * 所以放大时是「图层缩放」而不是「位图缩放」，边缘仍然干净。
 */
@Composable
fun TransitionLayerImage(
    layer: GraphicsLayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
) {
    val w = layer.size.width.toFloat()
    val h = layer.size.height.toFloat()
    val ratio = if (w > 0f && h > 0f) w / h else 1f
    val painter = remember(layer) {
        object : Painter() {
            override val intrinsicSize: Size
                get() = Size(w, h)

            override fun DrawScope.onDraw() {
                if (w <= 0f || h <= 0f) return
                scale(size.width / w, size.height / h, pivot = Offset.Zero) {
                    drawLayer(layer)
                }
            }
        }
    }
    Image(
        painter = painter,
        contentDescription = null,
        // 高度必须显式锁比例，否则在 fillMaxSize 的父容器里会被当成 0 或撑满
        modifier = modifier.aspectRatio(ratio).clipToBounds(),
        contentScale = contentScale,
    )
}
