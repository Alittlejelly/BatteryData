package com.smalljelly.batterydata.ui.components

import androidx.compose.ui.graphics.Color
import com.smalljelly.batterydata.ui.theme.MaterialOption

/**
 * 悬浮小面（顶栏圆按钮、浮层菜单、删除胶囊、贴底弹窗）的底色：
 * 与底栏胶囊共用 [MaterialOption.surfaceAlphaScale]，所以切档时它们一起变透/变奶白。
 */
fun materialGlassColor(dark: Boolean, option: MaterialOption): Color {
    val base = if (dark) 0.84f else 0.76f
    return (if (dark) Color(0xFF242424) else Color(0xFFFAFAFA))
        .copy(alpha = (base * option.surfaceAlphaScale).coerceIn(0f, 1f))
}
