package com.smalljelly.batterydata.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme

/** MIUI 设置页里的分组标题。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    NexioText(
        text = text,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp, top = 2.dp),
        style = NexioTheme.textStyles.subtitle,
        color = NexioTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 卡片内的一行“标签 — 值”。 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = NexioTheme.colorScheme.onSurfaceVariantSummary,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        NexioText(
            text = label,
            style = NexioTheme.textStyles.body1,
            color = NexioTheme.colorScheme.onSurfaceContainer,
        )
        NexioText(
            text = value,
            style = NexioTheme.textStyles.body1,
            color = valueColor,
        )
    }
}

/** 实时刷新提示，带呼吸圆点。 */
@Composable
fun LiveBadge(text: String, modifier: Modifier = Modifier, dotColor: Color = Color(0xFF22C55E)) {
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .alpha(pulse)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        NexioText(
            text = text,
            fontSize = 12.sp,
            color = NexioTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 卡片内部的细分隔线。 */
@Composable
fun InnerDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(NexioTheme.colorScheme.dividerLine),
    )
}

@Composable
fun VerticalInnerDivider(height: Int = 34) {
    Box(
        Modifier
            .width(0.6.dp)
            .height(height.dp)
            .background(NexioTheme.colorScheme.dividerLine),
    )
}
