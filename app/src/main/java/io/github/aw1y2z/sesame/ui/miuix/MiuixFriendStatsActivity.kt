package io.github.aw1y2z.sesame.ui.miuix

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aw1y2z.sesame.entity.FriendWatch
import io.github.aw1y2z.sesame.util.FileUtil
import io.github.aw1y2z.sesame.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 待打开好友标记文件名，与宿主侧 ApplicationHook.PENDING_PROFILE_FILE 保持一致 */
private const val PENDING_PROFILE_FILE = "pending_profile.txt"

/** 宿主包名 */
private const val HOST_PACKAGE = "com.eg.android.AlipayGphone"

/** 请求宿主打开好友主页的广播 action */
private const val ACTION_OPEN_PROFILE = "com.eg.android.AlipayGphone.sesame.openProfile"

/** 好友统计二级页:展示单向好友列表及其互动记录,与其它二级页面统一风格(LogTopBar)。 */
class MiuixFriendStatsActivity : MiuixBaseActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBroadcast: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppContent {
            FriendStatsScreen(this)
        }
    }

    override fun onDestroy() {
        pendingBroadcast?.let { mainHandler.removeCallbacks(it) }
        pendingBroadcast = null
        super.onDestroy()
    }

    /**
     * 打开好友主页。跳转必须由支付宝进程发起（模块 App 自己发 scheme 会被宿主判为不可信），
     * 所以这里只做两件事：写待打开标记 + 拉起支付宝。
     *
     * 宿主在支付宝任意页面 resume 时就会消费标记并跳转，点击到出页面几乎是连续的；
     * 广播只是标记没被消费时的兜底。
     */
    fun openFriendProfile(userId: String) {
        try {
            // 主路径：支付宝进程一有页面 resume 就会读到该标记并立刻跳转，
            // 冷启动、热启动都走这条，不需要等待
            try {
                FileUtil.write2File(
                    "$userId\n${System.currentTimeMillis()}",
                    File(FileUtil.MAIN_DIRECTORY_FILE, PENDING_PROFILE_FILE)
                )
            } catch (t: Throwable) {
                Log.printStackTrace(t)
            }
            packageManager.getLaunchIntentForPackage(HOST_PACKAGE)?.let {
                it.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(it)
            }
            // 兜底：标记写入失败或没被消费时再补一次广播。
            // 连续点不同好友先取消上一次的，避免旧广播把上一个好友又拉出来。
            pendingBroadcast?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                try {
                    sendBroadcast(
                        Intent(ACTION_OPEN_PROFILE).putExtra("userId", userId)
                    )
                } catch (t: Throwable) {
                    Log.printStackTrace(t)
                }
            }
            pendingBroadcast = runnable
            mainHandler.postDelayed(runnable, 1500)
        } catch (t: Throwable) {
            Log.printStackTrace(t)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FriendStatsScreen(activity: MiuixFriendStatsActivity) {
    // 好友表可能有几百条：读盘 + 逐条解析不能压在组合期的主线程上，否则首帧要等整份解析完
    // 才出来（好友多时白屏 1s+）。null 表示还没读完，列表区据此显示"加载中"。
    var friends by remember { mutableStateOf<List<FriendWatch>?>(null) }
    var query by remember { mutableStateOf("") }
    var sortKey by remember { mutableStateOf(FriendSort.WEEK) }
    // 默认降序：收得多的人排前面才是常用视角
    var ascending by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        friends = withContext(Dispatchers.IO) {
            try {
                FriendWatch.getList()
            } catch (e: Throwable) {
                // getList() 内部只兜了解析异常，读盘异常会穿透到这里；
                // 不接住就会永远停在「加载中…」，比报错更难察觉
                Log.printStackTrace(e)
                emptyList()
            }
        }
    }

    val listState = rememberLazyListState()

    val filtered = remember(friends, query) {
        friends?.let { all ->
            if (query.isBlank()) all else all.filter { it.name.contains(query, ignoreCase = true) }
        }
    }

    // 数据层已只统计通讯录好友，这里的按名字过滤只是兜底：
    // 万一还有历史遗留的无名记录，点开是空白页，不显示更合适。
    val shown = remember(filtered, sortKey, ascending) {
        filtered
            ?.filter { it.name.isNotBlank() }
            ?.let { list ->
                val byValue = when (sortKey) {
                    FriendSort.WEEK -> if (ascending) compareBy<FriendWatch> { it.weekGet }
                    else compareByDescending { it.weekGet }
                    FriendSort.ALL -> if (ascending) compareBy<FriendWatch> { it.allGet }
                    else compareByDescending { it.allGet }
                }
                // 数值相同时按名字排，避免每次重组顺序抖动
                list.sortedWith(byValue.thenBy { it.name })
            }
    }

    Scaffold(
        topBar = {
            LogTopBar(
                title = "好友统计",
                onBack = { activity.finish() }
            )
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 搜索框 ──────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "",
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "搜索",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Text(
                                "×",
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .clickable { query = "" }
                            )
                        }
                    }
                )
            }

            // ── 排序 ────────────────────────────────────────────────
            // 再点一次当前字段切升/降序；换字段时回到降序（默认想看收得多的）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // requestScrollToItem 只是登记，下一次测量时才生效，因此能和排序状态在同一帧落地：
                // 若放到 LaunchedEffect 里滚，会先按新顺序在旧滚动位置画一帧再跳回顶部，那一帧错位就是看到的闪
                SortChip("周收", sortKey == FriendSort.WEEK, ascending) {
                    listState.requestScrollToItem(0)
                    if (sortKey == FriendSort.WEEK) {
                        ascending = !ascending
                    } else {
                        sortKey = FriendSort.WEEK
                        ascending = false
                    }
                }
                SortChip("总收", sortKey == FriendSort.ALL, ascending) {
                    listState.requestScrollToItem(0)
                    if (sortKey == FriendSort.ALL) {
                        ascending = !ascending
                    } else {
                        sortKey = FriendSort.ALL
                        ascending = false
                    }
                }
            }

            if (shown == null || shown.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            shown == null -> "加载中…"
                            query.isNotEmpty() -> "(无匹配好友)"
                            else -> "(空)"
                        },
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(items = shown, key = { it.id }) { fw ->
                        FriendRow(fw) { activity.openFriendProfile(fw.id) }
                    }
                }
            }
        }
    }
}

/** 好友统计的排序字段。 */
private enum class FriendSort {
    WEEK, ALL
}

/**
 * 排序芯片：点当前字段切升/降序，点另一字段换排序。
 * 箭头只在选中时出现，未选中的 chip 不该看起来像已排好序。
 */
@Composable
private fun SortChip(label: String, selected: Boolean, ascending: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MiuixTheme.colorScheme.primaryContainer
    else MiuixTheme.colorScheme.surfaceContainer
    val fg = if (selected) MiuixTheme.colorScheme.onPrimaryContainer
    else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
        if (selected) {
            Text(text = if (ascending) "↑" else "↓", fontSize = 12.sp, color = fg)
        }
    }
}

/** 好友行：点一下打开该好友的支付宝主页，具体流程见 openFriendProfile()。 */
@Composable
private fun FriendRow(fw: FriendWatch, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = fw.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "开始统计: ${fw.startTime}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "周收: ${fw.weekGet} g   总收: ${fw.allGet} g",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}
