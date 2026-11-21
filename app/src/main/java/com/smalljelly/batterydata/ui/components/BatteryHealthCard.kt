package com.smalljelly.batterydata.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smalljelly.batterydata.data.BatterySample
import com.smalljelly.batterydata.data.healthRatingText
import com.smalljelly.batterydata.ui.theme.BatteryAmber
import com.smalljelly.batterydata.ui.theme.BatteryGreen
import com.smalljelly.batterydata.ui.theme.BatteryRed
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 电池健康：容量衰减百分比 + 设计容量 / 满电容量 / 循环次数 / 系统状态。
 * 手动设定设计容量的入口在右上角「更多」菜单里，卡片上不再重复。
 */
@Composable
fun BatteryHealthCard(
    sample: BatterySample,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val percent = sample.healthPercent
    val rating = if (percent == null) sample.healthText else healthRatingText(percent)
    val accent = when {
        percent == null -> colors.onSurfaceVariantSummary
        percent >= 80f -> BatteryGreen
        percent >= 70f -> BatteryAmber
        else -> BatteryRed
    }

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
            Text(
                text = "电池健康",
                style = MiuixTheme.textStyles.title3,
                color = colors.onSurfaceContainer,
            )
            if (percent != null) {
                RatingChip(text = rating, color = accent)
            }
        }
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            if (percent != null) {
                Text(
                    text = percent.toInt().toString(),
                    fontSize = 52.sp,
                    lineHeight = 54.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.5).sp,
                    color = colors.onSurfaceContainer,
                )
                Text(
                    text = "%",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 7.dp),
                )
            } else {
                Text(
                    text = sample.healthText,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceContainer,
                )
            }
        }

        if (percent != null) {
            Spacer(Modifier.height(10.dp))
            HealthBar(progress = percent / 100f, color = accent)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0%", fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
                Text("100%", fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "当前机型未开放容量节点，健康度以系统状态为准",
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }

        Spacer(Modifier.height(12.dp))
        InnerDivider()
        InfoRow(
            label = "设计容量",
            value = capacityText(sample.designCapacityMah) + designSourceSuffix(sample.designSource),
        )
        InnerDivider()
        InfoRow("满电容量", capacityText(sample.estimatedFullChargeMah))
        InnerDivider()
        InfoRow("循环次数", sample.cycleCount?.let { "$it 次" } ?: "未公开")
        InnerDivider()
        InfoRow("系统健康状态", sample.healthText)
    }
}

@Composable
fun ManualCapacityDialog(
    show: Boolean,
    manualCapacity: Float?,
    currentDesign: String,
    onSet: (Float?) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "手动设定设计容量",
        summary = "手动值优先于系统节点与机型库数据。\n当前：$currentDesign",
        // 默认底色与页面 background 同色，深色下整块糊在一起；用卡片色区分出边界
        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
    ) {
        // 必须把整个 TextFieldValue（含 selection / composition）存在状态里。
        // 之前每帧 new 一个 TextFieldValue(text)：默认 selection 落在末尾、composition 为 null，
        // 于是光标被反复拽回末尾（点不动、拖不动），输入法的组合区还每帧被清掉，
        // 数字会按旧插入点重新落位 —— 看起来就是"倒着输入"。
        var field by remember(show) {
            mutableStateOf(
                TextFieldValue(manualCapacity?.roundToInt()?.toString() ?: ""),
            )
        }
        TextField(
            value = field,
            onValueChange = { value ->
                // 只接受 5 位以内的纯数字；不合格的改动整条丢弃（光标位置也一起保留）
                if (value.text.length <= 5 && value.text.all { it.isDigit() }) {
                    field = value
                }
            },
            label = "设计容量 (mAh)",
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
        )
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = "恢复自动",
                enabled = manualCapacity != null,
                onClick = { onSet(null) },
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                text = "取消",
                onClick = onDismiss,
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = "确定",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = (field.text.toFloatOrNull() ?: 0f) in 1000f..10000f,
                onClick = { onSet(field.text.toFloatOrNull()) },
            )
        }
    }
}

fun designSourceSuffix(source: String?): String = when (source) {
    "手动" -> "（手动）"
    "系统节点" -> ""
    null -> ""
    else -> "（参考）"
}

@Composable
private fun HealthBar(progress: Float, color: Color) {
    val colors = MiuixTheme.colorScheme
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "healthBar",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.secondaryContainer),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(lerp(color, Color.White, 0.18f), color),
                    ),
                ),
        )
    }
}

@Composable
private fun RatingChip(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, fontSize = 12.sp, color = color)
    }
}

fun capacityText(mah: Float?): String = when {
    mah == null || mah <= 0f -> "未公开"
    else -> "${Math.round(mah)} mAh"
}
