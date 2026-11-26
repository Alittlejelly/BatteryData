package com.smalljelly.batterydata.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 动画相关的全局开关（和 [AppMaterial] 同一套路：全局可观察状态，改完立刻生效）。
 *
 * 为什么不能用 `remember { settings.xxx() }`：那样每个页面只在**首次组合**时读一次，
 * 用户在「外观与动画」里关掉预见式返回后，当前这一页（以及已经组合着的页面）
 * 手里还是旧值 —— 表现出来就是「开关点了没用，返回照样跟手」。
 * 放进全局状态后，开关一改，所有页面的 `AppPage` 立刻重组、立刻换成普通返回。
 */
object AppMotion {
    /** 预见式返回动画：二级页跟手预览返回，默认开启 */
    var predictiveBack by mutableStateOf(true)
        private set

    fun applyPredictiveBack(enabled: Boolean) {
        predictiveBack = enabled
    }
}