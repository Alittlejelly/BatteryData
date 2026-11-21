package com.smalljelly.batterydata.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.data.LogFile
import com.smalljelly.batterydata.data.LogFileStore
import com.smalljelly.batterydata.ui.components.GlassBackButton
import com.smalljelly.batterydata.ui.components.GlassIconButton
import com.smalljelly.batterydata.ui.components.PageTopBar
import com.smalljelly.batterydata.ui.components.pageTopBarHeight
import com.smalljelly.batterydata.ui.components.rememberTopBarCollapseProgress
import com.smalljelly.batterydata.ui.components.rememberTopBarMaterialAlpha
import com.smalljelly.batterydata.ui.components.rememberTopBarPull
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val logTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

/** 列表底部留白（不含导航栏、不含为"收标题"补的行程、不含底部按钮栏） */
private val LogListBottomSpace = 24.dp

/** 实时记录的采样间隔 */
private const val LiveSampleIntervalMs = 2_000L

/**
 * 日志诊断页：顶栏和记录页完全一套（大标题 + 顶部渐进模糊带 + 玻璃按钮），
 * 卡片用 Miuix Card，列出应用私有目录里的诊断日志；底部一颗蓝色主按钮开始 / 停止实时记录。
 */
@Composable
fun LogDiagnosticScreen(onBack: () -> Unit, onOpenLog: (LogFile) -> Unit) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val store = remember { LogFileStore(context.applicationContext) }

    var logs by remember { mutableStateOf<List<LogFile>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordedSec by remember { mutableIntStateOf(0) }

    /**
     * [seedIfEmpty] 只在首次进页面时为 true：没有任何日志时抓一份快照，页面不至于空白。
     * 清空之后必须传 false —— 否则立刻又补一份新快照，看起来就是"清空了文件还在"。
     */
    fun refresh(seedIfEmpty: Boolean = true) {
        if (seedIfEmpty && store.list().isEmpty()) store.collect()
        logs = store.list()
    }

    LaunchedEffect(Unit) { refresh() }

    // 实时记录：每 2 秒追一条采样，顺带刷新列表里的大小，用户能看见文件在长
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        var ticks = 0
        while (true) {
            store.appendLiveSample()
            recordedSec = ticks * (LiveSampleIntervalMs / 1000).toInt()
            logs = store.list()
            ticks++
            delay(LiveSampleIntervalMs)
        }
    }

    // 离开页面就收尾（补一行结束时间），不要在后台留一个"还在记"的假状态
    DisposableEffect(Unit) {
        onDispose {
            recording = false
            if (store.liveRecording != null) store.stopLiveRecording()
        }
    }

    val listState = rememberLazyListState()
    // 顶栏材质：与记录页同一口径 —— 停在顶部时没有材质，内容钻进栏下才淡入
    val (pullModifier, pull) = rememberTopBarPull()
    val topBarAlpha = rememberTopBarMaterialAlpha(listState, pull)
    val collapseProgress = rememberTopBarCollapseProgress {
        if (listState.firstVisibleItemIndex > 0) 10_000 else listState.firstVisibleItemScrollOffset
    }
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current

    Scaffold(
        topBar = {
            PageTopBar(
                title = "日志收集",
                largeTitle = "日志收集",
                collapseProgress = collapseProgress,
                backdrop = backdrop,
                materialAlpha = topBarAlpha,
                page = "logs",
                navigation = {
                    GlassBackButton(
                        backdrop = backdrop,
                        onClick = onBack,
                        materialAlpha = topBarAlpha,
                    )
                },
                actions = {
                    GlassIconButton(
                        backdrop = backdrop,
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "清空日志",
                        onClick = { confirmClear = true },
                        materialAlpha = topBarAlpha,
                    )
                },
            )
        },
        bottomBar = {
            LogRecordBar(
                recording = recording,
                recordedSec = recordedSec,
                onToggle = {
                    if (recording) {
                        recording = false
                        store.stopLiveRecording()
                        logs = store.list()
                    } else if (store.startLiveRecording() != null) {
                        recordedSec = 0
                        recording = true
                        logs = store.list()
                    }
                },
            )
        },
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { innerPadding ->
        // 让位用 Scaffold 实测的栏高（第一帧还没量到时退回常数）
        val measuredTop = innerPadding.calculateTopPadding()
        val contentTopInset =
            (if (measuredTop > 0.dp) measuredTop else pageTopBarHeight()) + 8.dp
        val insetPx = with(density) { contentTopInset.roundToPx() }
        val baseBottomPx = with(density) { LogListBottomSpace.roundToPx() }
        // 底部按钮栏（含导航栏让位）——Scaffold 已经算好了
        val bottomBarPx = with(density) { innerPadding.calculateBottomPadding().roundToPx() }

        var viewportPx by remember { mutableIntStateOf(0) }

        // 内容不足一屏时列表没有滚动范围 -> 顶栏永远等不到"内容钻进栏下"，
        // 大标题也收不起来。和记录页同一套办法：底部补一段够用的行程（3/4 个栏高）。
        val extraRoomPx by remember(listState, insetPx, viewportPx, bottomBarPx) {
            derivedStateOf {
                val info = listState.layoutInfo
                if (viewportPx <= 0 || info.totalItemsCount == 0 ||
                    info.visibleItemsInfo.size < info.totalItemsCount
                ) {
                    return@derivedStateOf 0f
                }
                // 全部可见时，可见条目高度之和就是内容高度（这个版本的
                // LazyListLayoutInfo 没有 totalItemsSize）
                val contentPx = info.visibleItemsInfo.sumOf { it.size }
                val existing = contentPx + insetPx + baseBottomPx + bottomBarPx - viewportPx
                (insetPx * 3 / 4f - existing).coerceAtLeast(0f)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 采样源：整块滚动内容（顶栏从这里取模糊素材）
                .layerBackdrop(backdrop),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { if (it.height != viewportPx) viewportPx = it.height }
                    .overScrollVertical()
                    // 内层才能拿到"列表没消费掉"的增量（回弹位移）
                    .then(pullModifier),
                overscrollEffect = null,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = contentTopInset,
                    bottom = LogListBottomSpace + with(density) { extraRoomPx.toDp() } +
                        with(density) { bottomBarPx.toDp() },
                ),
            ) {
                items(logs, key = { it.name }) { log ->
                    LogCard(
                        log = log,
                        onClick = { onOpenLog(log) },
                    )
                }
                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = "暂无日志文件",
                            fontSize = 14.sp,
                            color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }

        // ⚠ 对话框必须写在 Scaffold **内容**里：OverlayDialog 只往「最近的 Scaffold」
        // 的弹窗宿主里塞，宿主由 Scaffold 通过 CompositionLocal 提供、也只在自己的
        // 布局里渲染。写在 Scaffold 外面就落到了默认的空列表上 —— 对话框永远不出现，
        // 表现就是"点删除按钮没反应"（连遮罩都没有）。
        OverlayDialog(
            show = confirmClear,
            title = "清空日志",
            summary = "将删除全部诊断日志文件，此操作不可恢复。",
            backgroundColor = colors.surfaceContainer,
            onDismissRequest = { confirmClear = false },
            renderInRootScaffold = false,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = { confirmClear = false })
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "清空",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        // 正在记录的文件也算日志，一起停掉再删
                        if (store.liveRecording != null) {
                            recording = false
                            store.stopLiveRecording()
                        }
                        store.deleteAll()
                        confirmClear = false
                        refresh(seedIfEmpty = false)
                    },
                )
            }
        }
    }
}

/**
 * 底部固定栏：一颗 Miuix 主色（蓝）按钮。
 * 记录中文案变成「停止记录 · 00:12」并带计时，停止后文件留在上面的列表里可点开。
 *
 * 底色用**渐进遮罩**而不是一块实色：从顶边全透明过渡到按钮上方的实色，
 * 列表滚到底部时自然"化"进背景，不会在栏的上沿切出一条硬边（和顶栏渐进模糊同一个思路，
 * 这里只要渐变、不需要模糊）。
 */
@Composable
private fun LogRecordBar(
    recording: Boolean,
    recordedSec: Int,
    onToggle: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val background = colors.background
    val scrim = remember(background) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to background.copy(alpha = 0f),
                0.28f to background.copy(alpha = 0.55f),
                0.52f to background,
                1f to background,
            ),
        )
    }
    Box(Modifier.fillMaxWidth()) {
        // 列表从下面穿过，这一层负责"渐隐"：越往上越透，内容在上面就慢慢消失
        Box(
            Modifier
                .matchParentSize()
                .background(scrim),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp, bottom = 10.dp),
        ) {
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColorsPrimary(),
                insideMargin = PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    text = if (recording) "停止记录 · ${formatElapsed(recordedSec)}" else "开始记录日志",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun formatElapsed(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

@Composable
private fun LogCard(log: LogFile, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (log.name.startsWith("live-")) "实时记录" else "运行日志",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = log.name,
                    fontSize = 15.sp,
                    color = colors.onSurfaceContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Size: ${formatSize(log.sizeBytes)}",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = logTimeFormat.format(Date(log.modifiedMillis)),
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}

private val ColorPrimary = androidx.compose.ui.graphics.Color(0xFF2962FF)

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
