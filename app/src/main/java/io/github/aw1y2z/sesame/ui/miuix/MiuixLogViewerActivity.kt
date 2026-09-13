package io.github.aw1y2z.sesame.ui.miuix

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aw1y2z.sesame.util.FileUtil
import io.github.aw1y2z.sesame.util.ToastUtil
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 二级日志页支持的日志类型(与一级页面日志类目一一对应)。
 * 一级页点击哪个类目,二级页就只展示该类。
 */
enum class LogType(val displayName: String) {
    FOREST("森林记录"),
    GOLDENBEANS("金豆记录"),
    FARM("庄园记录"),
    OTHER("其他记录"),
    DEBUG("抓包记录"),
    ERROR("查看异常日志"),
    RUNTIME("查看运行日志");

    /** 每次访问都重新取当日文件,避免跨天后路径过期 */
    val file: File
        get() = when (this) {
            FOREST -> FileUtil.getForestLogFile()
            GOLDENBEANS -> FileUtil.getGoldenBeansLogFile()
            FARM -> FileUtil.getFarmLogFile()
            OTHER -> FileUtil.getOtherLogFile()
            DEBUG -> FileUtil.getDebugLogFile()
            ERROR -> FileUtil.getErrorLogFile()
            RUNTIME -> FileUtil.getRuntimeLogFile()
        }

    companion object {
        /** 一级页跳转时携带的 extra key,值为 LogType.name */
        const val EXTRA_LOG_TYPE = "sesame_log_type"

        fun fromIntent(intent: Intent?): LogType {
            val name = intent?.getStringExtra(EXTRA_LOG_TYPE)
            return entries.firstOrNull { it.name == name } ?: RUNTIME
        }
    }
}

/** 单条日志条目(可能含多行正文) */
data class LogEntry(
    val lineNumber: Int,
    val time: String?,
    val tag: String?,
    val body: String
)

class MiuixLogViewerActivity : MiuixBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppContent {
            LogScreen(this, LogType.fromIntent(intent))
        }
    }
}

/**
 * 日志详情页:展示指定类目的全部条目卡片。
 * 仿 LSPosed 日志界面:每条目一张卡(标签 + 时间 + 正文)。
 *
 * 列表从底部开始排(reverseLayout),而列表初始位置就是最新一条,
 * 所以一打开页面看到的就是最新日志;之后每 [LOG_REFRESH_INTERVAL_MS] 检查一次日志文件,
 * 有新内容就刷新并跟到最新;上滑翻历史时暂停跟随(不会被新日志顶跑),滑回最新后自动恢复。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogScreen(activity: MiuixLogViewerActivity, logType: LogType) {
    val context = LocalContext.current
    var entries by remember(logType) { mutableStateOf(loadLogEntries(logType.file)) }
    // 每次刷新到新内容后自增,作为「把视角钉回最新一条」的触发信号
    var revision by remember(logType) { mutableStateOf(0) }
    // 用户是否翻到历史里去了:是则不再自动跟随,免得看历史时被新日志顶跑
    var browsingHistory by remember(logType) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 列表停下时按位置判断是否停在最新(索引 0):还能往回滚就说明用户翻到上面看历史了
    LaunchedEffect(listState, logType) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect { browsingHistory = listState.canScrollBackward }
    }

    // 定时刷新:文件无变化时只做一次轻量的 length/lastModified 比较,不读盘也不重组
    LaunchedEffect(logType) {
        var stamp = logFileStamp(logType.file)
        while (true) {
            delay(LOG_REFRESH_INTERVAL_MS)
            // 页面不在前台时跳过,避免后台无谓读盘
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                continue
            }
            // 每次重新取 file,跨天时能自动切到新一天的文件
            val file = logType.file
            val newStamp = logFileStamp(file)
            if (newStamp == null || newStamp == stamp) {
                continue
            }
            stamp = newStamp
            entries = withContext(Dispatchers.IO) { loadLogEntries(file) }
            revision++
        }
    }

    // 有新日志时把视角钉回最新一条。
    // 注意滚的是索引 0(最新那条所在位置),它恒定存在,不会像"滚到末尾"那样因列表尚未测量而失效。
    LaunchedEffect(revision) {
        if (!browsingHistory && entries.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            LogTopBar(
                title = logType.displayName,
                onBack = { activity.finish() },
                onExport = {
                    val exported = FileUtil.exportFile(logType.file)
                    if (exported != null) {
                        ToastUtil.show(context, "已导出: " + exported.path)
                    } else {
                        ToastUtil.show(context, "导出失败")
                    }
                },
                onClear = {
                    if (FileUtil.clearFile(logType.file)) {
                        entries = loadLogEntries(logType.file)
                        ToastUtil.show(context, "已清空")
                    }
                }
            )
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "(空)",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        } else {
            LazyColumn(
                state = listState,
                // 从底部开始排:索引 0(最新那条)在屏幕最下方,而列表初始位置就是索引 0,
                // 所以一打开页面看到的就是最新日志,不依赖任何"滚动到底部"的动作。
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(
                    // 倒序传入(最新在前),配合 reverseLayout 后视觉上仍是"旧的在上面、最新在最下面"
                    entries.asReversed(),
                    key = { _, e -> "${e.lineNumber}-${e.hashCode()}" }
                ) { _, entry ->
                    LogEntryCard(entry)
                }
            }
        }
    }
}

/** 单条日志卡片:标题(TAG)+ 右上时间戳 + 下方正文 */
@Composable
fun LogEntryCard(entry: LogEntry) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.tag ?: "日志",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary
            )
            Text(
                text = entry.time ?: "",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        if (entry.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.body,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onBackground
            )
        }
    }
}

/**
 * 通用顶部栏:返回图标 + 可选的操作图标(导入/导出/删除) + 横向 marquee 滚动的标题。
 * 仿 LSPosed 日志页样式。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogTopBar(
    title: String,
    onBack: () -> Unit,
    onImport: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 1
            )
            if (onImport != null) {
                IconButton(onClick = onImport) {
                    // 导入图标:把 Upload 旋转 180°(朝下)与导出(朝上)区分
                    Icon(
                        imageVector = Icons.Filled.Upload,
                        contentDescription = "导入",
                        tint = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.rotate(180f)
                    )
                }
            }
            if (onExport != null) {
                IconButton(onClick = onExport) {
                    Icon(
                        imageVector = Icons.Filled.Upload,
                        contentDescription = "导出",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
            if (onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

/** 日志自动刷新间隔(毫秒) */
private const val LOG_REFRESH_INTERVAL_MS = 1000L

/** 日志文件签名:长度 + 修改时间,用于判断文件是否有新内容 */
private data class LogFileStamp(val length: Long, val modified: Long)

private fun logFileStamp(file: File?): LogFileStamp? =
    if (file != null && file.exists()) LogFileStamp(file.length(), file.lastModified()) else null

/** 日志查看时最多从文件尾部读取的字节数(避免大文件全量加载导致卡顿) */
private const val MAX_TAIL_BYTES = 1024 * 1024L

/** 日志查看时最多展示的条目数 */
private const val MAX_LOG_ENTRIES = 500

/**
 * 从文件尾部读取文本,最多 maxBytes 字节。
 * 若非从头读取,会丢弃首个可能被截断的行。
 */
private fun readTailText(file: File, maxBytes: Long): String {
    val length = file.length()
    if (length <= 0L) {
        return ""
    }
    val start = maxOf(0L, length - maxBytes)
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(start)
        val bytes = ByteArray((length - start).toInt())
        raf.readFully(bytes)
        var text = String(bytes, Charsets.UTF_8)
        if (start > 0L) {
            val idx = text.indexOf('\n')
            text = if (idx >= 0) text.substring(idx + 1) else ""
        }
        return text
    }
}

/**
 * 读取日志文件并按行解析为条目;无时间戳的行合并到上一条(多行日志聚合为同一卡片)。
 * 仅读取文件尾部,并限制最大条目数,保证大文件也能快速打开。
 */
private fun loadLogEntries(file: File?): List<LogEntry> {
    if (file == null || !file.exists()) {
        return emptyList()
    }
    val timeRegex = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\w+):\\s*(.*)$")
    val entries = ArrayDeque<LogEntry>()
    return try {
        val text = readTailText(file, MAX_TAIL_BYTES)
        var lineNumber = 0
        for (line in text.lineSequence()) {
            lineNumber++
            val match = timeRegex.find(line)
            if (match != null) {
                entries.addLast(
                    LogEntry(
                        lineNumber = lineNumber,
                        time = match.groupValues[1],
                        tag = match.groupValues[2],
                        body = match.groupValues[3]
                    )
                )
            } else {
                // 无时间戳:视为上一条的续行,合并到同卡片
                if (entries.isNotEmpty()) {
                    val last = entries.removeLast()
                    entries.addLast(last.copy(body = last.body + "\n" + line))
                } else {
                    entries.addLast(LogEntry(lineNumber, null, null, line))
                }
            }
            // 超出上限时丢弃最早的条目,保证内存占用可控
            while (entries.size > MAX_LOG_ENTRIES) {
                entries.removeFirst()
            }
        }
        entries.toList()
    } catch (e: Throwable) {
        emptyList()
    }
}