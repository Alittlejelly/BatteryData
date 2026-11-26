package com.smalljelly.batterydata.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import com.haooz.nexioui.scaffold.Scaffold
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.icon.NexioIcons
import com.haooz.nexioui.icon.basic.ArrowRight
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.R
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.data.UpdateChecker
import com.smalljelly.batterydata.ui.components.CheckUpdateCard
import com.smalljelly.batterydata.ui.components.NexioIconButton
import com.smalljelly.batterydata.ui.components.NexioTopBar
import com.smalljelly.batterydata.ui.components.UpdateResultDialog
import com.smalljelly.batterydata.ui.components.UpdateSourceCard
import com.smalljelly.batterydata.ui.components.pageTopBarHeight
import com.smalljelly.batterydata.ui.components.rememberNexioTopBarState
import kotlinx.coroutines.launch

/**
 * 关于页：**静态页，不滚动**。
 *
 * 一屏之内放完：应用身份（图标 / 名称 / 版本）+ 检查更新 + 更新源 + 日志诊断入口。
 * 不再有折叠大标题、也不挂滚动容器 —— 内容本来就一屏放得下，滚动只会让顶部
 * 那套折叠/回弹看起来莫名其妙。
 *
 * 布局是 `Column` + 底部 `weight` 撑开的留白：内容整体靠上，末尾那句说明贴底，
 * 屏幕高低不同都居中得好看。玻璃采样层仍然写在底色之前（下拉面板要用）。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val colors = NexioTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { BatterySettings(context.applicationContext) }
    val backdrop = rememberLayerBackdrop()
    val topBarState = rememberNexioTopBarState()
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    var updateSource by remember {
        mutableStateOf(
            UpdateChecker.Source.entries.firstOrNull {
                it.storageValue == settings.updateSource()
            } ?: UpdateChecker.Source.AUTO,
        )
    }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                // 关于页固定大标题：静态页没有滚动，大标题就一直是展开态
                NexioTopBar(
                    state = topBarState,
                    title = "关于",
                    largeTitle = "关于",
                    backdrop = backdrop,
                    navigation = { backdropAlpha, shadowAlpha ->
                        // 圆形玻璃钮旋转 180° 当返回箭头（图标本身不支持下旋转参数）
                        Box(Modifier.rotate(180f)) {
                            NexioIconButton(
                                onClick = onBack,
                                backdrop = backdrop,
                                icon = NexioIcons.Basic.ArrowRight,
                                contentDescription = "返回",
                                backdropAlpha = backdropAlpha,
                                shadowAlpha = shadowAlpha,
                                iconTint = colors.onBackground,
                            )
                        }
                    },
                )
            },
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(colors.background)
                    .padding(top = innerPadding.calculateTopPadding())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {

            AppIcon(size = 88.dp)
            Spacer(Modifier.height(14.dp))
            NexioText(
                text = "电池数据",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            NexioText(
                text = "版本号：$versionName",
                fontSize = 14.sp,
                color = colors.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(28.dp))
            CheckUpdateCard(
                versionName = versionName,
                checking = checking,
                result = result,
                onClick = {
                    checking = true
                    scope.launch {
                        val checked = UpdateChecker.check(context.applicationContext, updateSource)
                        result = checked
                        checking = false
                        // 已是最新：没必要弹窗打断，一条 Toast 就够
                        if (!checked.hasUpdate && checked.error == null) {
                            Toast.makeText(
                                context,
                                "当前已是最新版本 $versionName",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            showResultDialog = true
                        }
                    }
                },
            )

            Spacer(Modifier.height(12.dp))
            UpdateSourceCard(
                source = updateSource,
                onPick = { option ->
                    updateSource = option
                    settings.setUpdateSource(option.storageValue)
                    // 换源后旧结果作废，别让「发现新版本」留在卡片上误导人
                    result = null
                },
                backdrop = backdrop,
            )

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                EntryRow(
                    title = "日志诊断",
                    summary = "查看运行日志与诊断快照",
                    onClick = onOpenLogs,
                )
            }

            Spacer(Modifier.weight(1f))
            NexioText(
                text = "数据来自系统 BatteryManager 与 power_supply 节点",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
            }
        }

    }

    UpdateResultDialog(
        show = showResultDialog,
        result = result,
        backdrop = backdrop,
        onDismiss = { showResultDialog = false },
        onOpenUrl = { url ->
            showResultDialog = false
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

/**
 * 应用图标：用 design/app-icon-source.png 生成的静态位图（drawable-nodpi/ic_launcher_flat.png）。
 * 不用自适应图标 XML —— 它被普通 Image 绘制时会再按系统安全区缩放一次，显示会偏小。
 */
@Composable
private fun AppIcon(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_flat),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f)),
    )
}