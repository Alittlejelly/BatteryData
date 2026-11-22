package com.smalljelly.batterydata.ui.components

// 顶栏按钮（三点 / 加号 / 返回 / 任意图标）与「更多」菜单已全部换成 NexioUI 的组件：
//   - 按钮    → com.haooz.nexioui.button.FlatIconButton（图标用 miuix 图标集补齐）
//   - 菜单    → com.haooz.nexioui.dropdown.OverlayDropdownPopup（窗口级浮层，收起态＝触发的圆钮）
//
// 自研实现已删除。此文件保留为空壳，是因为工作区根目录带 Everyone:(CI)(DENY)(DC)，
// Remove-Item 会静默失败，无法真正删除子文件。