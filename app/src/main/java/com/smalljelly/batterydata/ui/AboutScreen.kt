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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.smalljelly.batterydata.data.UpdateChecker
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
                                val checked = UpdateChecker.check(context.applicationContext)
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
            // 系统会拦下安装，先把用户送到「安装未知应用」授权页
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
                failure = error.message ?: "下载失败"
            }
        }
    }

    val summary = buildString {
        when {
            result == null -> Unit
            result.error != null -> append("检查失败：${result.error}")
            hasUpdate -> {
                append("发现新版本 ${result.latestVersion ?: ""}")
                result.notes?.let { append("\n$it") }
                if (result.assetSize > 0) append("\n安装包 ${formatSize(result.assetSize)}")
                if (result.downloadUrl == null) append("\n本次发布未附安装包，请前往发布页")
                append("\n\n当前版本 ${result.currentVersion}")
            }
            else -> append("当前版本 ${result.currentVersion} 已是最新")
        }
    }

    val statusLine = when {
        failure != null -> failure
        needPermission -> "已下载完成。系统要求先在「安装未知应用」里允许本应用，返回后点「安装」。"
        downloading -> "下载中 ${if (percent >= 0) "$percent%" else ""}  ·  ${formatSize(downloaded)}"
        readyApk != null -> "已下载 ${formatSize(readyApk!!.length())}"
        else -> null
    }

    OverlayDialog(
        show = show,
        title = "检查更新",
        summary = summary,
        onDismissRequest = onDismiss,
        // 挂在关于页自己的 Scaffold 上（调用点在它的 content 内），才不会被本页盖住
        renderInRootScaffold = false,
    ) {
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
                color = if (failure != null) colors.error else colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(text = "关闭", onClick = onDismiss)

            if (downloading) {
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "取消下载",
                    onClick = {
                        job?.cancel()
                        downloading = false
                    },
                )
            }

            if (needPermission) {
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "去授权",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        runCatching {
                            context.startActivity(ApkUpdater.installSettingsIntent(context))
                        }
                    },
                )
            }

            if (hasUpdate && !downloading) {
                Spacer(Modifier.width(12.dp))
                when {
                    // 已经下好了：直接装（用户在系统里授权完回来点这个）
                    readyApk != null -> TextButton(
                        text = "安装",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { startInstall(readyApk!!) },
                    )
                    // 这次 release 没挂 apk，只能去网页
                    downloadUrl == null -> TextButton(
                        text = "前往发布页",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { onOpenUrl(result?.releasePage ?: "") },
                    )
                    else -> TextButton(
                        text = if (failure != null) "重试" else "下载并安装",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { startDownload() },
                    )
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
