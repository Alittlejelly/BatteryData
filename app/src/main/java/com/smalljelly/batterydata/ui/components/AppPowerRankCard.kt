package com.smalljelly.batterydata.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.smalljelly.batterydata.data.AppPowerUsage
import java.util.Locale

/**
 * 应用耗电排行卡片：每个应用一行「图标 + 名称 + 百分比 + 进度条」。
 *
 * 形态参考 MIUI 省电与电池页那张排行图，但用我们自己的组件和配色：
 * 进度条是圆角胶囊 + 渐变填充，和实时页的电量条同一套视觉语言。
 *
 * 数据口径是**前台时长占比**（见 [com.smalljelly.batterydata.data.AppPowerUsageReader]），
 * 不是系统的 mAh —— 那个要签名权限，第三方拿不到。这个区别在卡片底部有一句说明。
 */
@Composable
fun AppPowerRankCard(
    entries: List<AppPowerUsage>,
    modifier: Modifier = Modifier,
    hasPermission: Boolean = true,
    onRequestPermission: () -> Unit = {},
) {
    val colors = NexioTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NexioText(
                text = "应用耗电排行",
                style = NexioTheme.textStyles.title3,
                color = colors.onSurfaceContainer,
            )
            NexioText(
                text = "前台时长",
                fontSize = 11.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(14.dp))

        when {
            !hasPermission -> {
                NexioText(
                    text = "需要在系统设置里开启「使用情况访问」才能统计应用占用屏幕的时间。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                // 路径写清楚：HyperOS 上这个入口藏在特殊权限里，用户自己找很容易迷路
                NexioText(
                    text = "路径：设置 → 隐私保护 → 特殊权限 → 使用情况访问 → 电池数据",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariantSummary.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(12.dp))
                com.haooz.nexioui.button.FlatTextButton(
                    text = "去设置里开启",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRequestPermission,
                )
            }

            entries.isEmpty() -> {
                NexioText(
                    text = "这段时间还没有应用使用记录。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }

            else -> {
                entries.forEachIndexed { index, entry ->
                    RankRow(entry = entry, rank = index + 1)
                    if (index != entries.lastIndex) Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** 单行：图标 + 名称 / 百分比 + 进度条 */
@Composable
private fun RankRow(entry: AppPowerUsage, rank: Int) {
    val colors = NexioTheme.colorScheme
    val context = LocalContext.current
    // 行业配色按包名定，同一应用每次颜色一致
    val tint = remember(entry.packageName) { accentFor(entry.packageName) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = entry.packageName, tint = tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                NexioText(
                    text = entry.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                NexioText(
                    text = String.format(Locale.US, "%.2f%%", entry.percent * 100f),
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.onSurfaceVariantSummary.copy(alpha = 0.14f)),
            ) {
                val fraction = entry.percent.coerceIn(0f, 1f)
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.horizontalGradient(listOf(tint, tint.copy(alpha = 0.72f))),
                            ),
                    )
                }
            }
        }
    }
}

/** 应用图标；取不到就用色块兜底 */
@Composable
private fun AppIcon(packageName: String, tint: Color) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmapOrNull()
        }.getOrNull()
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            NexioText(text = "·", fontSize = 18.sp, color = tint)
        }
    }
}

/** 把 Drawable 转 Bitmap（支持 AdaptiveIconDrawable） */
private fun android.graphics.drawable.Drawable.toBitmapOrNull(): android.graphics.Bitmap? =
    runCatching {
        // BitmapDrawable.bitmap 在部分设备上可能为 null（资源未成功解码），
        // 所以不能直接返回它 —— 走下面的 canvas 路径兜底
        (this as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: run {
                val size = 128
                val bmp = android.graphics.Bitmap.createBitmap(
                    size,
                    size,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                val canvas = android.graphics.Canvas(bmp)
                setBounds(0, 0, size, size)
                draw(canvas)
                bmp
            }
    }.getOrNull()

/** 按包名取一个稳定的强调色（同一 App 每次一致） */
private fun accentFor(packageName: String): Color {
    val palette = listOf(
        Color(0xFF3B82F6), // 蓝
        Color(0xFF10B981), // 绿
        Color(0xFFF59E0B), // 琥珀
        Color(0xFF8B5CF6), // 紫
        Color(0xFFEC4899), // 粉
        Color(0xFF06B6D4), // 青
    )
    var h = 0
    packageName.forEach { c -> h = (h * 31 + c.code) and 0x7FFFFFFF }
    return palette[h % palette.size]
}