package com.smalljelly.batterydata.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smalljelly.batterydata.data.BatterySample
import com.smalljelly.batterydata.ui.theme.batteryAccent
import com.haooz.nexioui.text.NexioText
import java.util.Locale

/**
 * 顶部大电池卡片：底色是浅色电池壳，实心部分按剩余电量填充，还原 MIUI “省电与电池” 的主视觉。
 */
@Composable
fun BatteryHeroCard(
    sample: BatterySample,
    etaText: String?,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val level = sample.level.coerceIn(0, 100)
    val fraction by animateFloatAsState(
        targetValue = level / 100f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "batteryFill",
    )
    val accent = batteryAccent(level)
    val accentDeep = lerp(accent, Color.Black, 0.14f)
    val trackColor = if (dark) lerp(Color.Black, accent, 0.26f) else lerp(Color.White, accent, 0.26f)
    // 卡里所有文案统一纯白（#FFFFFFFF）。电量低时文字会落在浅色的"空壳"上，
    // 所以给文字加一层极淡的投影兜底，不然白字会糊在浅绿底里看不见。
    val contentColor = Color.White
    val textStyle = remember {
        TextStyle(
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.16f),
                offset = Offset(0f, 1f),
                blurRadius = 6f,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(Brush.verticalGradient(listOf(accent, accentDeep))),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusPill(sample = sample, textStyle = textStyle)

            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    NexioText(
                        text = level.toString(),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-2.5).sp,
                        lineHeight = 74.sp,
                        color = contentColor,
                        style = textStyle,
                    )
                    NexioText(
                        text = "%",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        style = textStyle,
                        modifier = Modifier.padding(start = 3.dp, bottom = 11.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                NexioText(
                    text = etaText ?: "正在采集实时数据…",
                    fontSize = 14.sp,
                    color = contentColor,
                    style = textStyle,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(
    sample: BatterySample,
    textStyle: TextStyle,
) {
    val label = when {
        sample.isFull -> "已充满"
        sample.isCharging -> sample.powerW?.let {
            "充电中 ${String.format(Locale.US, "%.1f", it)} W"
        } ?: "充电中"
        sample.isDischarging -> sample.powerW?.let {
            "放电中 ${String.format(Locale.US, "%.1f", kotlin.math.abs(it))} W"
        } ?: "放电中"
        else -> sample.statusText
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.2f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NexioText(text = label, fontSize = 12.sp, color = Color.White, style = textStyle)
    }
}
