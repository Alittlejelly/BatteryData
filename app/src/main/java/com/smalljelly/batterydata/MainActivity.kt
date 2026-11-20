package com.smalljelly.batterydata

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import com.smalljelly.batterydata.data.ChargeRecorder
import com.smalljelly.batterydata.ui.BatteryScreen
import com.smalljelly.batterydata.ui.theme.BatteryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏与导航栏全透明，配合 Scaffold 的零 Insets 实现沉浸式
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // 恢复上次没记完的充电过程（进程被杀 / 手机重启后也能接着记）
        ChargeRecorder.ensure(this)
        setContent {
            BatteryTheme {
                BatteryScreen()
            }
        }
    }
}
