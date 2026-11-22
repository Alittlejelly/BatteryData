package com.smalljelly.batterydata.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.haooz.nexioui.dropdown.DropdownColors
import com.haooz.nexioui.dropdown.DropdownDefaults

/**
 * 下拉条目的配色：条目本身不铺底色。
 *
 * 面板是玻璃，条目再画实色 rect 会在玻璃上切出一个个灰/白色小方块（动画期间最明显），
 * 选中态只靠打勾 + 主色文字表达就够了。所有「值胶囊 → 面板」的下拉都用这一份，
 * 避免每个页面各写一遍还写岔。
 */
@Composable
fun menuDropdownColors(): DropdownColors = DropdownDefaults.dropdownColors(
    containerColor = Color.Transparent,
    selectedContainerColor = Color.Transparent,
)