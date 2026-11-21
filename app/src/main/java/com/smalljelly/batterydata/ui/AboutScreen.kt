package com.smalljelly.batterydata.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smalljelly.batterydata.R
import com.smalljelly.batterydata.data.ApkUpdater
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.data.UpdateChecker
import com.smalljelly.batterydata.ui.components.InnerDivider
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页：独立页面（不是对话框），版式参考系统"关于手机管家"——
 * 左上返回箭头 + 大标题，中部居中的应用图标 / 名称 / 版本号，底部是检查更新。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLogs: () -> Unit = {}) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }

    // 更新源：自动（GitHub + Gitee 并行）/ 手动锁定其中一个
    val settings = remember { BatterySettings(context.applicationContext) }
    var updateSource by remember {
        mutableStateOf(UpdateChecker.Source.fromStorage(settings.updateSource()))
    }
    var showSourceDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            IconButton(
                onClick = onBack,
                modifier = Modifier.offset(x = (-12).dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Basic.ArrowRight,
                    contentDescription = "返回",
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(180f),
                    tint = colors.onBackground,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "关于电池数据",
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
            )

            Spacer(Modifier.weight(1f))

            // 中部：图标 + 名称 + 版本号（整体水平居中）
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(size = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "电池数据",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "版本号：$versionName",
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }

            Spacer(Modifier.weight(1f))

            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 不要按压波纹：卡片内缩的矩形波纹很突兀，点击反馈只靠右侧状态文案变化
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !checking,
                        ) {
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "检查更新",
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when {
                                checking -> "正在检查…"
                                result == null -> "当前 $versionName"
                                result?.hasUpdate == true -> "发现新版本"
                                else -> "已是最新"
                            },
                            fontSize = 14.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = colors.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showSourceDialog = true },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "更新源",
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = updateSource.label,
                            fontSize = 14.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = colors.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenLogs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "日志诊断",
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = colors.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "数据来自系统 BatteryManager 与 power_supply 节点",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
        }

        // ⚠ 必须写在 Scaffold 内容里：OverlayDialog 只往「最近的 Scaffold」的弹窗宿主里塞，
        // 而宿主是 Scaffold 用 CompositionLocal 提供、并在自己的布局里渲染的。写在
        // Scaffold 外面就落到了默认的空列表上 —— 对话框永远不显示（点了像没反应）。
        UpdateResultDialog(
            show = showResultDialog,
            result = result,
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

        // 同样是 OverlayDialog，同样要写在 content 里，否则宿主不渲染
        if (showSourceDialog) {
            OverlayDialog(
                show = true,
                title = "更新源",
                backgroundColor = colors.surfaceContainer,
                onDismissRequest = { showSourceDialog = false },
                renderInRootScaffold = false,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // 用 miuix 自己的设置项组件：左右 16dp 内边距、按压反馈、标题/说明双行，
                    // 跟系统「选择网络」这类弹窗是同一套，不是手搓的 Row
                    UpdateChecker.Source.entries.forEachIndexed { index, option ->
                        BasicComponent(
                            title = option.label,
                            summary = option.description,
                            endActions = {
                                if (updateSource == option) {
                                    Icon(
                                        imageVector = MiuixIcons.Basic.Check,
                                        contentDescription = "已选中",
                                        modifier = Modifier.size(20.dp),
                                        tint = colors.primary,
                                    )
                                }
                            },
                            onClick = {
                                updateSource = option
                                settings.setUpdateSource(option.storageValue)
                                // 换源后旧结果作废，别让「发现新版本」的文案留在卡片上误导人
                                result = null
                                showSourceDialog = false
                            },
                        )
                        if (index != UpdateChecker.Source.entries.lastIndex) {
                            InnerDivider()
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    TextButton(
                        text = "取消",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showSourceDialog = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateResultDialog(
    show: Boolean,
    result: UpdateChecker.Result?,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme
    val hasUpdate = result?.hasUpdate == true
    val downloadUrl = result?.downloadUrl
    val checkError = result?.error
    val notes = result?.notes

    // 下载状态：每次弹窗重新打开都重置
    var downloading by remember(show) { mutableStateOf(false) }
    var percent by remember(show) { mutableStateOf(0) }
    var downloaded by remember(show) { mutableStateOf(0L) }
    var readyApk by remember(show) { mutableStateOf<File?>(null) }
    var failure by remember(show) { mutableStateOf<String?>(null) }
    var needPermission by remember(show) { mutableStateOf(false) }
    var job by remember(show) { mutableStateOf<Job?>(null) }

    // 对话框关掉就掐掉下载，别在后台空跑流量
    DisposableEffect(show) {
        onDispose { job?.cancel() }
    }

    fun startInstall(apk: File) {
        if (!ApkUpdater.canInstall(context)) {
            // 系统会拦下安装：先把用户送到「安装未知应用」授权页，回来后仍点「安装」
            needPermission = true
            runCatching { context.startActivity(ApkUpdater.installSettingsIntent(context)) }
            return
        }
        if (ApkUpdater.install(context, apk)) {
            onDismiss()
        } else {
            failure = "无法拉起系统安装器，可前往 Release 页面手动下载"
        }
    }

    fun startDownload() {
        if (downloadUrl.isNullOrBlank()) return
        downloading = true
        percent = 0
        downloaded = 0L
        failure = null
        needPermission = false
        val version = result?.latestVersion.orEmpty()
        job = scope.launch {
            runCatching {
                ApkUpdater.download(
                    context = context.applicationContext,
                    url = downloadUrl,
                    version = version,
                    onProgress = { p, done, _ ->
                        if (p >= 0) percent = p
                        downloaded = done
                    },
                )
            }.onSuccess { apk ->
                downloading = false
                readyApk = apk
                startInstall(apk)
            }.onFailure { error ->
                downloading = false
                // 用户点「取消下载」也会走到这里（协程被取消），别把它当成失败弹出来
                if (error !is kotlinx.coroutines.CancellationException) {
                    failure = error.message ?: "下载失败"
                }
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
        job = null
        downloading = false
        percent = 0
        downloaded = 0L
    }

    val dialogTitle = when {
        checkError != null -> "检查更新失败"
        hasUpdate -> "发现新版本 ${result?.latestVersion.orEmpty()}"
        else -> "已是最新版本"
    }
    // 摘要只留一行基本信息：更新说明放到正文里按行显示，混在一起就是一团乱
    val dialogSummary = buildString {
        append("当前版本 ${result?.currentVersion.orEmpty()}")
        val size = result?.assetSize ?: 0L
        if (hasUpdate && size > 0) append("  ·  安装包 ${formatSize(size)}")
        // 自动模式下两家都可能应答，标一句来源，排查"为什么版本对不上"时有用
        if (hasUpdate && !result?.source.isNullOrBlank()) append("  ·  来自 ${result?.source}")
    }

    val statusLine = when {
        failure != null -> failure
        checkError != null -> checkError
        needPermission -> "已下载完成。在系统设置里允许「安装未知应用」后，回来再点「安装」。"
        downloading -> if (percent >= 0) {
            "下载中 $percent%  ·  ${formatSize(downloaded)}"
        } else {
            "正在下载  ·  ${formatSize(downloaded)}"
        }
        readyApk != null -> "下载完成 ${formatSize(readyApk!!.length())}"
        else -> null
    }

    val primaryLabel = when {
        downloading -> "下载中"
        failure != null -> "重试"
        readyApk != null -> "安装"
        downloadUrl == null -> "前往发布页"
        else -> "下载并安装"
    }

    OverlayDialog(
        show = show,
        title = dialogTitle,
        summary = dialogSummary,
        // 默认底色是 background，和页面同色 —— 深色下背景全黑，弹窗完全糊在页面里分不开。
        // 用卡片色（浅色偏白 / 深色 #1C1C1E），两层之间才有边界。
        backgroundColor = colors.surfaceContainer,
        onDismissRequest = onDismiss,
        // 挂在关于页自己的 Scaffold 上（调用点在它的 content 内），才不会被本页盖住
        renderInRootScaffold = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 更新说明：按行显示（小节标题加粗），超出高度内部滚动，不把弹窗顶破
            if (!notes.isNullOrBlank() && !downloading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 196.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    notes.split("\n").forEach { line ->
                        val heading = line.length <= 8 && !line.contains("：") && !line.contains(":")
                        Text(
                            text = line,
                            fontSize = if (heading) 14.sp else 13.sp,
                            lineHeight = 20.sp,
                            fontWeight = if (heading) FontWeight.Medium else FontWeight.Normal,
                            color = if (heading) colors.onSurfaceContainer else colors.onSurfaceVariantSummary,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            if (downloading) {
                LinearProgressIndicator(
                    progress = if (percent >= 0) percent / 100f else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
                Spacer(Modifier.height(10.dp))
            }

            statusLine?.let { line ->
                Text(
                    text = line,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = if (failure != null || checkError != null) colors.error else colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasUpdate) {
                    // 和删除记录弹窗同一套：两个等宽按钮，主操作是 Miuix 主色实心按钮
                    TextButton(
                        text = if (downloading) "取消下载" else "稍后",
                        modifier = Modifier.weight(1f),
                        onClick = { if (downloading) cancelDownload() else onDismiss() },
                    )
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !downloading,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = {
                            when {
                                downloading -> Unit
                                readyApk != null -> startInstall(readyApk!!)
                                downloadUrl == null -> onOpenUrl(result?.releasePage ?: "")
                                else -> startDownload()
                            }
                        },
                    ) {
                        Text(
                            text = primaryLabel,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = if (downloading) colors.disabledOnPrimaryButton else Color.White,
                        )
                    }
                } else {
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = onDismiss,
                    ) {
                        Text(
                            text = "知道了",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "--"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
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
