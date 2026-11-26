package com.smalljelly.batterydata.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.button.Button
import com.haooz.nexioui.button.ButtonDefaults
import com.haooz.nexioui.button.FlatTextButton
import com.haooz.nexioui.overlaydialog.OverlayDialog
import com.haooz.nexioui.progressindicator.LinearProgressIndicator
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.backdrop.GlassBackdrop
import com.smalljelly.batterydata.data.ApkUpdater
import com.smalljelly.batterydata.data.UpdateChecker
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 更新结果弹窗：更新说明 + 下载进度 + 下载并安装。
 *
 * 从关于页搬到这里（行为不变）：关于页只留应用身份信息，检查更新归设置 → 更新。
 */
@Composable
fun UpdateResultDialog(
    show: Boolean,
    result: UpdateChecker.Result?,
    backdrop: GlassBackdrop?,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = NexioTheme.colorScheme
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

    // NexioUI 的对话框：玻璃、模糊、折射、动画都是库自己的实现
    OverlayDialog(
        show = show,
        title = dialogTitle,
        summary = dialogSummary,
        // 面板自己采样这一层做模糊，背后页面只压暗
        liquidGlassBackdrop = backdrop,
        onDismissRequest = onDismiss,
        // 挂到最外层 Scaffold 的弹层宿主，才能盖住顶栏 / 底栏
        renderInRootScaffold = true,
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
                        NexioText(
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
                NexioText(
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
                    FlatTextButton(
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
                        NexioText(
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
                        NexioText(
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
