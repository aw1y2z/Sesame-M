package io.github.aw1y2z.sesame.ui.miuix

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aw1y2z.sesame.util.FileUtil
import io.github.aw1y2z.sesame.util.ToastUtil
import io.github.aw1y2z.sesame.data.AppConfig
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
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

/** 日志视图模式:按单条 / 按执行轮次(以"开始执行"为界) */
enum class LogViewMode { BY_ENTRY, BY_RUN }

/** 一轮执行聚合:从"开始执行"到下一轮之前的所有单条日志 */
data class LogRunEntry(
    val runIndex: Int,
    val startTime: String?,
    val startLineNumber: Int,
    val entries: List<LogEntry>
)

/**
 * 运行日志的轮次开始行正则。覆盖三种形态:
 * "[账号N]开始执行"、"[账号N]开始执行分组【xx】任务"、"[账号N]开始执行全部任务"。
 * 用前缀+tag 双重限定,避免"森林阅读📖开始执行..."、"黄金票[开始执行]"等任务日志被误判为轮次边界。
 */
private val RUN_START_REGEX = Regex("""^\[账号\d+\]开始执行(?:\s*$|分组【[^】]*】任务\s*$|全部任务\s*$)""")

/**
 * 模块加载起点行正则:"模块初始化开始"。
 * 由 ApplicationHook.onModuleLoaded 在主进程、AppConfig.load 之前打印,
 * 是主进程一次加载周期的最早日志。旧版模块无此行,天然不影响历史日志的轮次划分。
 */
private val INIT_START_REGEX = Regex("""^模块初始化开始\s*$""")

/** 运行日志轮次锚点:执行起点("开始执行") + 模块加载起点("模块初始化开始"),均按时间升序 */
private data class RuntimeAnchors(
    val runStarts: List<String>,
    val initStarts: List<String>
)

/**
 * 读取运行日志全文,提取轮次锚点:
 * - 执行起点("[账号N]开始执行...")的时间戳;
 * - 模块加载起点("模块初始化开始")的时间戳。
 * 读取全文而非受 [MAX_LOG_ENTRIES]/[MAX_TAIL_BYTES] 约束的尾部片段,确保全天各轮次的
 * 开始标记都能作为锚点,使森林/庄园/金豆/其他记录也能按统一执行周期正确划分轮次。
 */
private fun readRuntimeAnchors(): RuntimeAnchors {
    val file = LogType.RUNTIME.file
    if (!file.exists()) return RuntimeAnchors(emptyList(), emptyList())
    val runStarts = mutableListOf<String>()
    val initStarts = mutableListOf<String>()
    try {
        file.forEachLine(Charsets.UTF_8) { line ->
            val m = TIME_REGEX.find(line) ?: return@forEachLine
            val tag = m.groupValues[2].takeIf { it.isNotEmpty() }
            val body = m.groupValues[3]
            val time = m.groupValues[1].takeIf { it.isNotEmpty() } ?: return@forEachLine
            if (tag == "RUNTIME") {
                when {
                    RUN_START_REGEX.containsMatchIn(body) -> runStarts.add(time)
                    INIT_START_REGEX.containsMatchIn(body) -> initStarts.add(time)
                }
            }
        }
    } catch (_: Throwable) {
        // 读取失败时退化为无锚点(各页按单条显示)
    }
    return RuntimeAnchors(runStarts.sorted(), initStarts.sorted())
}

/**
 * 将单条日志按"执行轮次"聚合。轮次边界取自运行日志的"开始执行"时间(全局锚点),
 * 每条日志按其时间戳归入"最近一次开始执行之后、下一次之前"的轮次。
 * 若两轮"开始执行"之间存在模块加载起点(进程重启后的加载阶段),该轮边界提前到加载起点,
 * 使加载阶段日志归于其后一轮执行,而非上一轮末尾。
 * 这样森林/庄园/金豆/其他记录等没有自身轮次标记的类目,也能按统一的执行周期聚合显示。
 * 返回顺序:最早轮在前(runIndex=1 为最早一轮,与时间顺序一致)。时间基于 "HH:mm:ss.SSS" 字典序比较(同日有效)。
 */
private fun aggregateByRun(
    source: List<LogEntry>,
    runStarts: List<String>,
    initStarts: List<String>
): List<LogRunEntry> {
    val anchors = effectiveRunStarts(runStarts, initStarts)
    if (anchors.isEmpty()) {
        // 无锚点(从未运行过):退化为单轮,全部日志归一轮
        if (source.isEmpty()) return emptyList()
        return listOf(LogRunEntry(1, source.first().time, source.first().lineNumber, source))
    }
    val buckets = MutableList(anchors.size) { mutableListOf<LogEntry>() }
    for (e in source) {
        val t = e.time
        // 找到 <= t 的最大锚点索引;早于所有锚点的散落日志归入第 1 轮(最早)
        val k = if (t == null) -1 else anchors.indexOfLast { it <= t }
        val b = if (k < 0) 0 else k
        buckets[b].add(e)
    }
    return buckets.mapIndexedNotNull { index, list ->
        if (list.isEmpty()) null
        else LogRunEntry(
            runIndex = index + 1,
            startTime = list.first().time,
            startLineNumber = list.first().lineNumber,
            entries = list.toList()
        )
    }
}

/**
 * 加载起点与"开始执行"的最大时间差。只接受紧邻轮次开始前的加载标记,
 * 避免执行中途其他子进程的偶发标记把下一轮边界拖到本轮中间(轮次卡片错位/消失)。
 * 正常冷启动加载到开始执行通常不足 1 秒,10 秒上限足够覆盖慢设备。
 */
private const val INIT_BOUNDARY_WINDOW_MS = 10_000L

/** "HH:mm:ss.SSS" → 当天毫秒数(同日有效);格式异常返回 null */
private fun timeToMillis(t: String): Long? {
    return try {
        val parts = t.split(':', '.')
        if (parts.size != 4) return null
        (((parts[0].toLong() * 60 + parts[1].toLong()) * 60 + parts[2].toLong()) * 1000) + parts[3].toLong()
    } catch (_: Throwable) {
        null
    }
}

/**
 * 每轮有效起点:默认取该轮"开始执行"时间;若开始执行前 [INIT_BOUNDARY_WINDOW_MS] 内存在
 * 模块加载起点(取离开始执行最近的一条),则提前到该加载起点,使加载阶段日志归于本轮开头。
 * 第 1 轮不调整——早于所有锚点的散落日志本就归入第 1 轮。
 * 旧版模块不打印"模块初始化开始",initStarts 为空时行为与原来完全一致。
 */
private fun effectiveRunStarts(runStarts: List<String>, initStarts: List<String>): List<String> {
    if (runStarts.isEmpty() || initStarts.isEmpty()) return runStarts
    return runStarts.mapIndexed { index, start ->
        if (index == 0) {
            start
        } else {
            val prev = runStarts[index - 1]
            val startMs = timeToMillis(start) ?: return@mapIndexed start
            // initStarts 已升序:区间内最后一条即离开始执行最近的标记
            val marker = initStarts.lastOrNull { it > prev && it < start }?.takeIf { m ->
                val mMs = timeToMillis(m)
                mMs != null && startMs - mMs in 0..INIT_BOUNDARY_WINDOW_MS
            }
            marker ?: start
        }
    }
}

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
    // 增量读取器:只在文件真有新增时解析新增部分,避免运行日志每秒整份重读重解析
    val logReader = remember(logType) { LogTailReader() }
    // 首帧只留空列表:整份日志在组合之后的 IO 线程读取,解析压在组合阶段会卡住首帧
    var entries by remember(logType) { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loaded by remember(logType) { mutableStateOf(false) }
    var revision by remember(logType) { mutableStateOf(0) }
    // 搜索文本
    var searchQuery by remember { mutableStateOf("") }
    // Runtime 页面额外支持 tag 过滤
    var selectedTag by remember { mutableStateOf<String?>(null) }
    // 设置"日志查看方式切换"开启时,所有类目日志都按轮次聚合(每轮一格);
    // 轮次边界取自运行日志的"开始执行"时间(全局锚点),使森林/庄园/金豆/其他记录也按统一执行周期显示
    var viewMode by remember(logType) {
        mutableStateOf(
            if (AppConfig.INSTANCE.logViewByRun ?: false) LogViewMode.BY_RUN else LogViewMode.BY_ENTRY
        )
    }
    // 按轮模式的一键收拢/展开:把所有轮次卡片折叠成标题行
    var collapseAll by remember(logType) { mutableStateOf(false) }
    // 展开某一轮后需要定位的列表下标:展开时把该轮第一条顶到视口顶部
    var revealIndex by remember(logType) { mutableStateOf<Int?>(null) }
    // 点击展开前该卡片的收起高度:用于判断"展开后的高度是否已测量完成"
    var revealBaseline by remember(logType) { mutableStateOf(-1) }

    // 全局轮次锚点:从运行日志全文提取"开始执行"(执行起点)与"模块初始化开始"(加载起点)
    // 行的时间戳(升序)。读取全文(不受单页 500 条/尾部字节上限约束),确保全天各轮次的
    // 开始标记都能作为锚点,使森林/庄园/金豆/其他记录也能按统一执行周期正确划分轮次。
    // 同上,读取全文也在 IO 线程进行;读完前用空锚点(各页退化为单轮显示)
    var runtimeAnchors by remember { mutableStateOf(RuntimeAnchors(emptyList(), emptyList())) }
    // 按轮聚合;搜索在聚合后的轮次内逐条匹配
    val runEntries = remember(entries, runtimeAnchors) {
        aggregateByRun(entries, runtimeAnchors.runStarts, runtimeAnchors.initStarts)
    }
    val filteredRuns = remember(runEntries, searchQuery) {
        if (searchQuery.isBlank()) runEntries
        else runEntries.filter { run ->
            run.entries.any {
                (it.tag?.contains(searchQuery, ignoreCase = true) == true) ||
                        it.body.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val listState = rememberLazyListState()

    // 定时刷新:只读新增的字节并增量解析;有新条目才更新列表与滚动标记
    LaunchedEffect(logType) {
        val file = logType.file
        // 首次读取:整份日志与轮次锚点都在 IO 线程解析,读完才置 loaded,
        // 中间的空列表显示「加载中」而不是「(空)」
        val (first, anchors) = withContext(Dispatchers.IO) {
            logReader.poll(file) to readRuntimeAnchors()
        }
        entries = first
        runtimeAnchors = anchors
        loaded = true
        while (true) {
            delay(LOG_REFRESH_INTERVAL_MS)
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                continue
            }
            val updated = withContext(Dispatchers.IO) { logReader.poll(file) }
            // 无新增时读取器返回原实例:据此跳过重组(否则每秒都要重建整份列表)
            if (updated !== entries) {
                // 手动滑动/翻看历史期间冻结列表:按轮卡片高度随新增日志变化,reverseLayout 下
                // 任何内容变化都会把当前视图顶跑;用户回到底部后下一轮刷新自动补上
                if (listState.isScrollInProgress || listState.canScrollBackward) {
                    continue
                }
                entries = updated
                revision++
            }
        }
    }

    // 有新日志时仅在「人就在最新处、且没有手动滑动」时跟随:
    // 手动翻看历史期间绝不改动滚动位置,否则每秒新增的日志会把你反复顶跑
    LaunchedEffect(revision) {
        if (entries.isNotEmpty() && !listState.isScrollInProgress && !listState.canScrollBackward) {
            listState.scrollToItem(0)
        }
    }

    // 点开某一轮后定位到该轮开头(第一条):reverseLayout 展开时默认停在末尾,
    // 这里按"条目高度 - 视口高度"把卡片顶部(标题+第一条)对齐到视口顶部。
    // 逐帧检查,一旦测到展开后的高度立即定位,把中间"停在末尾"的闪烁帧压到最少
    LaunchedEffect(revealIndex) {
        val index = revealIndex ?: return@LaunchedEffect
        val baseline = revealBaseline
        var attempts = 0
        while (attempts < 8) {
            delay(16)
            attempts++
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                ?: continue
            if (baseline > 0 && info.size <= baseline) continue // 展开后的高度还没测出来
            val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            if (viewport > 0) {
                listState.scrollToItem(index, (info.size - viewport).coerceAtLeast(0))
            }
            break
        }
        // 必须放在滚动之后:提前清空会改变 LaunchedEffect 的 key,
        // 协程在 delay 处被取消,定位根本不会执行
        revealIndex = null
    }

    // 计算可见条目:根据搜索文本和 tag 过滤
    val filteredEntries = entries.filter { e ->
        val matchSearch = searchQuery.isBlank() ||
                (e.tag?.contains(searchQuery, ignoreCase = true) == true) ||
                e.body.contains(searchQuery, ignoreCase = true)
        val matchTag = selectedTag == null || e.tag == selectedTag
        matchSearch && matchTag
    }

    // Runtime 页面:统计各 tag 出现次数,用于 Chip 显示
    val tagCounts = remember(entries) {
        entries.mapNotNull { it.tag }.groupBy { it }.mapValues { it.value.size }
    }

    Scaffold(
        topBar = {
            LogTopBar(
                title = logType.displayName,
                actions = {
                    // 按轮查看时提供一键收拢/展开:折叠后只剩各轮标题行,便于快速定位轮次
                    if (viewMode == LogViewMode.BY_RUN) {
                        Text(
                            text = if (collapseAll) "展开" else "收拢",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { collapseAll = !collapseAll }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                },
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
                        entries = logReader.reload(logType.file)
                        searchQuery = ""
                        selectedTag = null
                        ToastUtil.show(context, "已清空")
                    }
                },
                onShare = if (logType == LogType.RUNTIME) {
                    {
                        val file = logType.file
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, file.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(Intent.createChooser(shareIntent, "分享日志"))
                    }
                } else null
            )
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 搜索框 ──────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = "",
                    modifier = Modifier.weight(1f),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "搜索",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            Text(
                                "×",
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(end = 12.dp).clickable { searchQuery = "" }
                            )
                        }
                    }
                )
            }
            // ── Runtime 过滤条:按条/按轮两种模式下都提供 tag 过滤 ──
            if (logType == LogType.RUNTIME && tagCounts.isNotEmpty()) {
                val tags = tagCounts.keys.sorted()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TagChip(
                        label = "全部",
                        count = entries.size,
                        selected = selectedTag == null,
                        onClick = { selectedTag = null }
                    )
                    for (tag in tags) {
                        TagChip(
                            label = tag,
                            count = tagCounts[tag]!!,
                            selected = selectedTag == tag,
                            onClick = { selectedTag = if (selectedTag == tag) null else tag }
                        )
                    }
                }
            }
            // ── 日志列表 ────────────────────────────────────────────────
            if (filteredEntries.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            !loaded -> "加载中…"
                            searchQuery.isNotEmpty() || selectedTag != null -> "无匹配日志"
                            else -> "(空)"
                        },
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                // 按轮模式用聚合后的轮次列表,按条模式用原始单条列表
                val displayList: List<Any> = if (viewMode == LogViewMode.BY_RUN) {
                    filteredRuns.asReversed()
                } else {
                    filteredEntries.asReversed()
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(
                        displayList,
                        // key 与内容解耦:按轮卡片若把 startLineNumber 纳入 key,500 条滑动窗口
                        // 丢弃旧条目时 key 会变化,锚点失效、卡片被重建,手动翻看时视图会自己跳
                        key = { _, item ->
                            when (item) {
                                is LogEntry -> "e-${item.lineNumber}"
                                is LogRunEntry -> "r-${item.runIndex}"
                                else -> item.hashCode().toString()
                            }
                        }
                    ) { index, item ->
                        when (item) {
                            is LogEntry -> LogEntryCard(item)
                            is LogRunEntry -> RunEntryCard(
                                run = item,
                                selectedTag = selectedTag,
                                searchQuery = searchQuery,
                                collapseAll = collapseAll,
                                itemIndex = index,
                                onExpand = { idx ->
                                    // 记录展开前的收起高度,供定位时判断"高度已测量完成"
                                    revealBaseline = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == idx }?.size ?: -1
                                    revealIndex = idx
                                }
                            )
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** Tag 过滤 Chip:圆角小药片,选中时高亮 */
@Composable
private fun TagChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MiuixTheme.colorScheme.primaryContainer
        else MiuixTheme.colorScheme.surfaceContainer
    val fg = if (selected) MiuixTheme.colorScheme.onPrimaryContainer
        else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            color = fg.copy(alpha = 0.7f)
        )
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
 * 一轮执行卡片:头部显示轮次序号与起止时间,点击展开/折叠本轮日志。
 * 默认展开;搜索时仅渲染匹配行(精确定位);选中 tag 时仅显示本轮内该 tag 的日志。
 */
@Composable
fun RunEntryCard(
    run: LogRunEntry,
    selectedTag: String? = null,
    searchQuery: String = "",
    collapseAll: Boolean = false,
    itemIndex: Int = -1,
    onExpand: (Int) -> Unit = {}
) {
    val shown = when {
        searchQuery.isNotBlank() -> run.entries.filter {
            (it.tag?.contains(searchQuery, ignoreCase = true) == true) ||
                    it.body.contains(searchQuery, ignoreCase = true)
        }
        selectedTag != null -> run.entries.filter { it.tag == selectedTag }
        else -> run.entries
    }
    // 单卡手动展开与全局收拢互不干扰:全局切换时统一重置,之后仍可单独点开某一轮
    var expanded by remember { mutableStateOf(!collapseAll) }
    LaunchedEffect(collapseAll) { expanded = !collapseAll }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    expanded = !expanded
                    if (expanded && itemIndex >= 0) onExpand(itemIndex)
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "第 ${run.runIndex} 轮" + (run.startTime?.let { "  $it" } ?: ""),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary
            )
            Text(
                text = shown.size.toString() + " 条" + if (expanded) " ▾" else " ▸",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            val text = shown.joinToString("\n") { e ->
                buildString {
                    if (e.time != null) append(e.time).append(" ")
                    if (e.tag != null) append(e.tag).append(": ")
                    append(e.body)
                }
            }
            Text(
                text = text,
                fontSize = 12.sp,
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
    onClear: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onExecute: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
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
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
            actions()
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
            if (onShare != null) {
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "分享",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
            if (onExecute != null) {
                IconButton(onClick = onExecute) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "执行",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

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

/** 日志自动刷新间隔(毫秒) */
private const val LOG_REFRESH_INTERVAL_MS = 1000L

// 两种行格式：
// runtime/system/debug: 13:34:10.251 RUNTIME: 执行结束-庄园
// forest/farm/goldenbeans/other: 19:37:33.150 森林签到...
private val TIME_REGEX = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(?:(\\w+):\\s*)?(.*)$")

/**
 * 日志增量读取器：记住已解析到的文件偏移,只解析新增的字节。
 *
 * <p>日志由注入进程持续追加,整份重读会让运行日志页每秒重读 1MB、重解析 500 条并让整
 * 列表重组。这里只读新增部分;无新增时返回上一次的同一个列表实例,调用方据此跳过重组。
 *
 * <p>文件被清空或轮转(长度回退)时自动退回整份重读。
 */
private class LogTailReader {
    private val entries = ArrayDeque<LogEntry>()

    /** 已解析到的文件偏移,下次从这里继续读 */
    private var consumed = 0L

    /** 尚无换行的尾部残行:写入方可能只写了一半,留到下次与新增内容拼接 */
    private var pending = ""
    private var lineNumber = 0
    private var initialized = false
    private var currentFile: File? = null
    private var snapshot: List<LogEntry> = emptyList()

    /** 读取新增内容并返回条目快照;无新增时返回上一次的同一个实例 */
    fun poll(target: File): List<LogEntry> {
        if (target != currentFile) {
            // 跨天等导致目标文件变化:重新整份加载
            currentFile = target
            initialized = false
        }
        val length = if (target.exists()) target.length() else 0L
        if (!initialized || length < consumed) {
            // 首次打开,或文件被清空/轮转:整份重读尾部
            reset()
            loadTail(target)
            initialized = true
            return publish()
        }
        if (length == consumed) {
            return snapshot
        }
        val appended = readAppended(target) ?: return snapshot
        consumed += appended.first
        appendLines(pending + appended.second)
        pending = ""
        return publish()
    }

    /** 强制整份重读(如日志被清空后) */
    fun reload(target: File): List<LogEntry> {
        initialized = false
        return poll(target)
    }

    private fun reset() {
        entries.clear()
        consumed = 0L
        pending = ""
        lineNumber = 0
    }

    private fun publish(): List<LogEntry> {
        snapshot = entries.toList()
        return snapshot
    }

    /** 整份读取文件尾部,并把「还没写完的最后一行」留作 pending */
    private fun loadTail(target: File) {
        val text = readTailText(target, MAX_TAIL_BYTES)
        val cut = text.lastIndexOf('\n')
        if (cut < 0) {
            pending = text
        } else {
            appendLines(text.substring(0, cut + 1))
            pending = text.substring(cut + 1)
        }
        consumed = if (target.exists()) target.length() else 0L
    }

    /**
     * 从 consumed 读到文件末尾,返回(已消费字节数, 文本);还没有完整的一行时返回 null。
     *
     * <p>只取到最后一个换行为止:换行字节不会出现在多字节序列中间,这样既不会把半个字符
     * 解成乱码,也不会丢掉「写到一半的那一行」。
     */
    private fun readAppended(target: File): Pair<Long, String>? {
        return try {
            RandomAccessFile(target, "r").use { raf ->
                val length = raf.length()
                if (length <= consumed) {
                    return null
                }
                raf.seek(consumed)
                val bytes = ByteArray((length - consumed).toInt())
                raf.readFully(bytes)
                val newline = bytes.lastIndexOf('\n'.code.toByte())
                if (newline < 0) {
                    return null
                }
                (newline + 1).toLong() to String(bytes, 0, newline + 1, Charsets.UTF_8)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** 按行解析并追加;无时间戳的行视为上一条的续行,合并到同一张卡片 */
    private fun appendLines(text: String) {
        if (text.isEmpty()) {
            return
        }
        val lines = text.split('\n')
        // 结尾的换行不构成一行,split 会多出一个空串,直接跳过
        val count = if (lines.last().isEmpty()) lines.size - 1 else lines.size
        for (i in 0 until count) {
            val line = lines[i].removeSuffix("\r")
            lineNumber++
            val match = TIME_REGEX.find(line)
            if (match != null) {
                entries.addLast(
                    LogEntry(
                        lineNumber = lineNumber,
                        time = match.groupValues[1],
                        tag = match.groupValues[2].takeIf { it.isNotEmpty() },
                        body = match.groupValues[3]
                    )
                )
            } else if (entries.isNotEmpty()) {
                val last = entries.removeLast()
                entries.addLast(last.copy(body = last.body + "\n" + line))
            } else {
                entries.addLast(LogEntry(lineNumber, null, null, line))
            }
            // 超出上限时丢弃最早的条目,保证内存占用可控
            while (entries.size > MAX_LOG_ENTRIES) {
                entries.removeFirst()
            }
        }
    }
}