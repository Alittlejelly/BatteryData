package com.smalljelly.batterydata.ui.theme

import androidx.compose.ui.graphics.Color

/** MIUI 电池绿 */
val BatteryGreen = Color(0xFF22C55E)
val BatteryGreenDeep = Color(0xFF11B14F)
val BatteryAmber = Color(0xFFFFA51F)
val BatteryRed = Color(0xFFFF4D3F)

/** 全局统一品牌蓝 */
val ChartBlue = Color(0xFF3481FF)

val LightBackground = Color(0xFFF1F2F4)
val LightCard = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF000000)
val LightTextSecondary = Color(0xFF8A8A8E)
val LightDivider = Color(0xFFEFEFF1)

val DarkBackground = Color(0xFF000000)
val DarkCard = Color(0xFF1C1C1E)
val DarkTextPrimary = Color(0xFFEDEDED)
val DarkTextSecondary = Color(0xFF8E8E93)
val DarkDivider = Color(0xFF2C2C2E)

/** 电量越低，主色越偏向橙、红，与 MIUI 一致。 */
fun batteryAccent(level: Int): Color = when {
    level <= 15 -> BatteryRed
    level <= 40 -> BatteryAmber
    else -> BatteryGreen
}
