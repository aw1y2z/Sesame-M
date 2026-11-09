package io.github.aw1y2z.sesame.ui.miuix

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.aw1y2z.sesame.ui.HostBridge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aw1y2z.sesame.R
import io.github.aw1y2z.sesame.data.AppConfig
import io.github.aw1y2z.sesame.data.RunType
import io.github.aw1y2z.sesame.data.ViewAppInfo
import io.github.aw1y2z.sesame.util.FileUtil
import io.github.aw1y2z.sesame.util.LanguageUtil
import io.github.aw1y2z.sesame.util.Log
import io.github.aw1y2z.sesame.util.PermissionUtil
import io.github.aw1y2z.sesame.util.Statistics
import io.github.aw1y2z.sesame.util.Statistics.DataType
import io.github.aw1y2z.sesame.util.Statistics.TimeType
import io.github.aw1y2z.sesame.util.ToastUtil
import io.github.aw1y2z.sesame.util.idMap.UserIdMap
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

class MiuixMainActivity : MiuixBaseActivity() {

    var runTypeText by mutableStateOf("")

    /** 可观察的激活状态：HomeTab 等页面订阅它，onServiceBind/广播更新时自动重组刷新 */
    var uiRunType by mutableStateOf<RunType>(RunType.DISABLE)
    var hasPermission by mutableStateOf(false)

    /** 统计分页：首项是「全部账号合计」，其后按账号；由 refreshStatistics() 重新读取 */
    var statisticsPages by mutableStateOf<List<StatisticsPage>>(emptyList())

    /** 统计版本号：load / 广播刷新后自增,首页 StatisticsTable 订阅它来触发重组 */
    var statisticsVersion by mutableStateOf(0)

    private val handler = Handler(Looper.getMainLooper())
    private var isClick = false
    // 标记是否已通过系统设置页请求过权限，用于 onResume 中检测用户是否已授权
    var hasRequestedPermission by mutableStateOf(false)

    /** 激活探测已重试次数,上限见 MAX_RUN_TYPE_PROBE_TIMES */
    private var runTypeProbeTimes = 0

    private lateinit var titleRunner: Runnable

    init {
        /** 激活探测:仅当状态仍为 DISABLE 才显示未激活并周期性重试,避免覆盖晚到的 onServiceBind */
        titleRunner = Runnable {
            if (ViewAppInfo.getRunType() == RunType.DISABLE) {
                runTypeProbeTimes++
                updateSubTitle(RunType.DISABLE)
                if (runTypeProbeTimes < MAX_RUN_TYPE_PROBE_TIMES) {
                    sendQueryBroadcast()
                    handler.postDelayed(titleRunner, 3000)
                }
            } else {
                runTypeProbeTimes = 0
            }
        }
    }

    companion object {
        /** 最多探测次数:每次间隔 3 秒,共约 15 秒,覆盖 XposedService 冷启动绑定晚于 Activity 的情况 */
        private const val MAX_RUN_TYPE_PROBE_TIMES = 5

        /**
         * 设备显示名:优先读 ro.product.marketname。
         * 小米/红米及多数模拟器的 ro.product.model 只是内部型号,多设备会重复。
         */
        fun getDeviceDisplayName(): String {
            try {
                val clazz = Class.forName("android.os.SystemProperties")
                val get = clazz.getMethod("get", String::class.java)
                val market = get.invoke(null, "ro.product.marketname") as? String
                if (!market.isNullOrBlank()) {
                    return market
                }
            } catch (e: Throwable) {
                // 取不到市场名就退回内部型号：显示会退化（小米/红米尤其明显），异常要留痕
                Log.debug("getDeviceDisplayName: " + e)
            }
            return Build.MODEL ?: Build.DEVICE ?: ""
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.i("view broadcast action:" + action + " intent:" + intent)
            if (action != null) {
                when (action) {
                    "io.github.aw1y2z.sesame.status" -> {
                        // 回包必须带本机已配对的令牌：缺失或对不上就是别人伪造的探活，忽略
                        val token = intent.getStringExtra("token")
                        if (token == null || token != HostBridge.getToken(context)) {
                            Log.i("status reply without valid token, ignored")
                            return
                        }
                        // 模块已被 LSPosed 启用并注入支付宝，标记为已激活
                        ViewAppInfo.setRunTypeByCode(RunType.MODEL.getCode())
                        runTypeProbeTimes = 0
                        handler.removeCallbacks(titleRunner)
                        updateSubTitle(RunType.MODEL)
                        if (isClick) {
                            ToastUtil.show(context, "芝麻粒加载状态正常")
                            isClick = false
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // runType 被模块置为 MODEL（onModuleLoaded）时立即刷新界面，无需手动加载配置
        ViewAppInfo.setRunTypeListener {
            runOnUiThread {
                handler.removeCallbacks(titleRunner)
                updateSubTitle(ViewAppInfo.getRunType())
            }
        }
        ViewAppInfo.checkRunType()
        updateSubTitle(ViewAppInfo.getRunType())
        val intentFilter = IntentFilter()
        intentFilter.addAction("io.github.aw1y2z.sesame.status")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, intentFilter)
        }
        setAppContent {
            MainScreen(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台补一次配对：首次启动时宿主多半没在跑，用户开完支付宝再回来就通了
        HostBridge.ensurePaired(this)
        // 激活状态探测独立于存储权限：防止 titleRunner 重复累积，先清空再启动
        if (RunType.DISABLE == ViewAppInfo.getRunType()) {
            handler.removeCallbacks(titleRunner)
            runTypeProbeTimes = 0
            sendQueryBroadcast()
            handler.postDelayed(titleRunner, 3000)
        }
        checkPermissionAndRefresh()
    }

    /**
     * 检查文件权限：已授权也要重读统计，否则从配置页或后台返回首页仍是旧值。
     */
    private fun checkPermissionAndRefresh() {
        hasRequestedPermission = false
        if (PermissionUtil.checkFilePermissions(this)) {
            hasPermission = true
            refreshStatistics()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            hasPermission = granted
            if (granted) refreshStatistics()
        }
    }

    /**
     * 重新加载统计并刷新首页。
     * <p>靠可观察的 statisticsVersion 触发重组:静态单例变化不会重组,首页会一直是 0。
     */
    fun refreshStatistics() {
        if (!hasPermission) return
        try {
            // 统计按账号分文件，界面一次读全部账号：首项是合计（默认显示），其后是各账号。
            // 不再读单例：界面进程没有「当前账号」的概念，读单例只会拿到没有归属的那一份。
            val perAccount = FileUtil.listAccountStatisticsUids().map { uid ->
                StatisticsPage(uid, UserIdMap.getAccountLabel(uid) ?: uid, Statistics.readFrom(uid))
            }
            val total = Statistics.aggregate(perAccount.map { it.statistics })
            statisticsPages = listOf(StatisticsPage(null, "全部账号合计", total)) + perAccount
            statisticsVersion++
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    fun updateSubTitle(runType: RunType) {
        uiRunType = runType
        runTypeText = when (runType) {
            RunType.DISABLE -> ViewAppInfo.getAppTitle() + "【" + getString(R.string.disable) + "】"
            RunType.MODEL -> ViewAppInfo.getAppTitle() + "【" + getString(R.string.activated) + "】"
            RunType.PACKAGE -> ViewAppInfo.getAppTitle() + "【" + getString(R.string.loading) + "】"
        }
    }

    fun sendStatus() {
        isClick = true
        HostBridge.send(this, "status", null, null, false)
    }

    /** 向支付宝进程查询本模块注入状态（不弹 Toast），由 titleRunner 周期性调用 */
    fun sendQueryBroadcast() {
        HostBridge.send(this, "status", null, null, false)
    }

    /**
     * 让注入进程整体重启（重新初始化并重挂 hook），用于必须重挂的开关如「使用新接口」；
     * 只重载 AppConfig 的 broadcastReloadConfig() 不够。
     */
    fun broadcastRestart() {
        HostBridge.send(this, "restart", null, null, false)
    }

    /** 通知支付宝进程重载共享配置（日志开关等），使开关在注入进程中即时生效 */
    fun broadcastReloadConfig() {
        HostBridge.send(this, "reloadConfig", null, null, false)
    }

    fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            ToastUtil.show(this, "无法打开链接")
        }
    }

    fun toggleLanguage() {
        val appConfig = AppConfig.INSTANCE
        appConfig.languageSimplifiedChinese = !appConfig.languageSimplifiedChinese
        if (AppConfig.save()) {
            LanguageUtil.setLocal(this)
            recreate()
        }
    }

    fun isIconHidden(): Boolean {
        val alias = ComponentName(this, "io.github.aw1y2z.sesame.ui.MainActivityAlias")
        return packageManager.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun toggleHideIcon() {
        val alias = ComponentName(this, "io.github.aw1y2z.sesame.ui.MainActivityAlias")
        val state = packageManager.getComponentEnabledSetting(alias)
        val newState = if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        packageManager.setComponentEnabledSetting(alias, newState, PackageManager.DONT_KILL_APP)
    }

    override fun onPause() {
        super.onPause()
        // 离开前台即停止状态轮询，避免后台无谓广播与泄漏
        handler.removeCallbacks(titleRunner)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(titleRunner)
        try {
            unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            // 未注册成功时注销会抛，属正常路径，留痕即可
            Log.debug("unregisterReceiver: " + e)
        }
    }
}

@Composable
fun MainScreen(activity: MiuixMainActivity) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    // 切回首页时刷新数据统计，避免停留在旧值
                    onClick = {
                        selectedTab = 0
                        activity.refreshStatistics()
                    },
                    icon = Icons.Filled.Home,
                    label = "首页"
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = Icons.Filled.Description,
                    label = "日志"
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = Icons.Filled.Tune,
                    label = "配置"
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = Icons.Filled.Settings,
                    label = "设置"
                )
            }
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            when (selectedTab) {
                0 -> HomeTab(activity)
                1 -> LogsTab(activity)
                2 -> ConfigTab(activity)
                3 -> SettingsTab(activity)
            }
        }
    }
}

@Composable
fun HomeTab(activity: MiuixMainActivity) {
    val context = LocalContext.current
    // 订阅 Compose state：onServiceBind / 状态广播到达时会自动重组刷新首页状态
    val activated = activity.uiRunType == RunType.MODEL
    val appTitle = ViewAppInfo.getAppTitle()
    val version = ViewAppInfo.getAppVersion()

    Text(
        text = "Sesame-M",
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
    )
    Spacer(Modifier.height(16.dp))

    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFE8F5E9), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (activated) "已激活" else "已关闭",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$version (${io.github.aw1y2z.sesame.BuildConfig.VERSION_CODE})",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFF2E7D32)
                )
                Text(
                    text = "API 102",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFF2E7D32)
                )
            }
            if (activated) {
                Image(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    alignment = Alignment.Center,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF2E7D32))
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    SmallTitle(text = "运行环境")
    CardColumn {
        // 不再放「模块状态」「版本」两行：顶部绿色 banner 已经显示激活状态与版本，重复
        StatusRow("SDK API", Build.VERSION.SDK_INT.toString())
        StatusRow("设备", MiuixMainActivity.getDeviceDisplayName())
        StatusRow("系统架构", Build.SUPPORTED_ABIS?.firstOrNull() ?: "")
    }
    Spacer(Modifier.height(16.dp))

    SmallTitle(text = "数据统计")
    CardColumn {
        StatisticsTable(activity)
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
fun StatusRow(label: String, value: String) {
    // 左右补 16dp，对齐设置页的行（SwitchPreference/ArrowPreference 自带 insideMargin 的左右留白）；
    // 上下**故意**保持 8dp：首页 5 行的行距拉到 16dp 会多出 ~80dp，首屏又会显示不全
    // 字号字重取 miuix 行样式 token（标题 headline1、摘要 body2），与配置/设置页的 preference 行一致
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.headline1,
            color = MiuixTheme.colorScheme.onBackground
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary
        )
    }
}

/** 首页「数据统计」的一块：标签 + 该账号的数据；列表首项是全部账号合计 */
data class StatisticsPage(val userId: String?, val label: String, val statistics: Statistics)

@Composable
fun StatisticsTable(activity: MiuixMainActivity) {
    // 订阅 statisticsVersion：load / 广播刷新后自增,触发本表重组读取最新数据
    activity.statisticsVersion
    val pages = activity.statisticsPages
    if (pages.isEmpty()) {
        return
    }
    // 左右滑动切换账号，默认停在首项（全部账号合计）。
    // 循环滑动：页数取超大值、真实下标取模，滑到最后一页继续滑就回到第一页（两个方向都能一直滑）。
    // 起始页对齐到 0（合计），并放在中间，使左右两侧都有足够页可滑。
    val initialPage = remember(pages.size) {
        val base = Int.MAX_VALUE / 2
        base - base % pages.size
    }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { Int.MAX_VALUE })
    // 每次进入首页都回到首项（合计）：pagerState 自身会记住上次页码，不归位就不算「默认显示合计」
    LaunchedEffect(pages.size) {
        pagerState.scrollToPage(initialPage)
    }
    val current = pagerState.currentPage % pages.size
    Column(
        Modifier
            .fillMaxWidth()
            // 左右补 16dp：卡片( CardColumn )自带的 16dp 之外，再补上行内边距，
            // 让表格文字与「模块状态」那些行的标签左边界对齐（都是 48dp），否则整块会贴着卡片边缘更靠左
            // 上下补 16dp：卡片本身不带内边距（各部分自备留白），不补的话表头会贴住卡片上边缘
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pages[current].label,
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            if (pages.size > 1) {
                // 明示这一块可以左右滑动，否则用户不知道还有别的账号
                Text(
                    text = "${current + 1}/${pages.size}  左右滑动",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            StatisticsGrid(pages[page % pages.size].statistics)
        }
    }
}

/** 单个账号（或合计）的统计表：行=项目、列=今日/本月/今年 */
@Composable
private fun StatisticsGrid(statistics: Statistics) {
    val rows = listOf(
        "收" to listOf(DataType.COLLECTED),
        "帮" to listOf(DataType.HELPED),
        "浇" to listOf(DataType.WATERED),
        "被水" to listOf(DataType.WATEREDCOUNT),
        "浇水" to listOf(DataType.WATERINGCOUNT)
    )
    val columns = listOf(TimeType.DAY, TimeType.MONTH, TimeType.YEAR)
    val headers = listOf("今日", "本月", "今年")

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f))
            headers.forEach { header ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // 列标题：与行内说明同级（body2），不再用硬编码 13sp
                    Text(
                        text = header,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        rows.forEach { (label, types) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    // 行标签用 headline1：与「模块状态」等 preference 行的标题同级
                    Text(text = label, style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onBackground)
                }
                columns.forEach { timeType ->
                    val value = types.sumOf { statistics.dataOf(timeType, it) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        // 数值用 body2：与 preference 行的摘要/值同级（原来硬编码 15sp，夹在 14sp/17sp 之间最显割裂）
                        Text(text = value.toString(), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
                    }
                }
            }
        }
    }
}

@Composable
fun LogsTab(activity: MiuixMainActivity) {
    Text(
        text = "日志",
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
    )

    SmallTitle(text = "分类记录")
    CardColumn {
        var forest by remember { mutableStateOf(AppConfig.INSTANCE.enableForestLog ?: true) }
        LogSwitchRow("森林记录", forest, onClick = { openLog(activity, LogType.FOREST) }) {
            forest = it
            AppConfig.INSTANCE.enableForestLog = it
            AppConfig.save()
            activity.broadcastReloadConfig()
            if (!it) FileUtil.clearLog("forest")
        }
        var farm by remember { mutableStateOf(AppConfig.INSTANCE.enableFarmLog ?: true) }
        LogSwitchRow("庄园记录", farm, onClick = { openLog(activity, LogType.FARM) }) {
            farm = it
            AppConfig.INSTANCE.enableFarmLog = it
            AppConfig.save()
            activity.broadcastReloadConfig()
            if (!it) FileUtil.clearLog("farm")
        }
        var goldenBeans by remember { mutableStateOf(AppConfig.INSTANCE.enableGoldenBeansLog ?: true) }
        LogSwitchRow("金豆记录", goldenBeans, onClick = { openLog(activity, LogType.GOLDENBEANS) }) {
            goldenBeans = it
            AppConfig.INSTANCE.enableGoldenBeansLog = it
            AppConfig.save()
            activity.broadcastReloadConfig()
            if (!it) FileUtil.clearLog("goldenbeans")
        }
        var other by remember { mutableStateOf(AppConfig.INSTANCE.enableOtherLog ?: true) }
        LogSwitchRow("其他记录", other, onClick = { openLog(activity, LogType.OTHER) }) {
            other = it
            AppConfig.INSTANCE.enableOtherLog = it
            AppConfig.save()
            activity.broadcastReloadConfig()
            if (!it) FileUtil.clearLog("other")
        }
    }
    Spacer(Modifier.height(16.dp))

    SmallTitle(text = "系统记录")
    CardColumn {
        var debug by remember { mutableStateOf(AppConfig.INSTANCE.enableDebugLog ?: false) }
        LogSwitchRow(
            "抓包记录", debug,
            onClick = { openLog(activity, LogType.DEBUG) },
            // 同时是 /debugHandler 的门禁：开了等于允许 PC 端打任意 RPC
            summary = "同时允许 PC 调试"
        ) {
            debug = it
            AppConfig.INSTANCE.enableDebugLog = it
            AppConfig.save()
            if (it) {
                // 副本由宿主写入，未配对时只能等它下次加载
                if (!HostBridge.isPaired(activity)) {
                    ToastUtil.show(activity, "已开启，打开支付宝后生效")
                }
            } else {
                // 关闭即撤销副本：宿主那条 reloadConfig 在未配对、宿主未运行或频控命中时都不生效
                HostBridge.revokeMirrorToken(activity)
            }
            activity.broadcastReloadConfig()
            // 关闭时**不清空** debug 日志：抓到的包是排查证据，要清空请到日志页点「删除」
        }
        var error by remember { mutableStateOf(AppConfig.INSTANCE.enableViewErrorLog ?: true) }
        LogSwitchRow("查看异常日志", error, onClick = { openLog(activity, LogType.ERROR) }) {
            error = it
            AppConfig.INSTANCE.enableViewErrorLog = it
            AppConfig.save()
            activity.broadcastReloadConfig()
            if (!it) FileUtil.clearLog("error")
        }
        var runtime by remember { mutableStateOf(AppConfig.INSTANCE.enableViewRuntimeLog ?: true) }
        LogSwitchRow("查看运行日志", runtime, onClick = { openLog(activity, LogType.RUNTIME) }) {
            runtime = it
            AppConfig.INSTANCE.enableViewRuntimeLog = it
            AppConfig.save()
            activity.broadcastReloadConfig()
            if (!it) FileUtil.clearLog("runtime")
        }
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * 日志条目行：点按整行进详情，右侧开关控制是否记录；用库的 SwitchPreference 保证字体与设置页一致。
 * <p>insideMargin 覆写为 8dp（库默认 16dp）贴合原行距；它没有 onClick，外层再套一层可点区域。
 */
@Composable
fun LogSwitchRow(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    summary: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
        SwitchPreference(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 覆写库默认的 16dp 上下内边距，尽量贴近日志页原来的行距（左右仍是 16dp，与设置页一致）
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        )
        // 库的 preference 行自带 clickable/ripple，会把触摸吞掉（实测套在外层的 clickable 收不到事件），
        // 所以压在它**上层**盖一层透明可点区域：只盖标题侧，右侧给开关留出 72dp，
        // 这样"点标题进日志、点开关只切开关"
        Box(
            Modifier
                .matchParentSize()
                .padding(end = 72.dp)
                .clickable(onClick = onClick)
                // 这层盖在库的 SwitchPreference 之上，miuix 0.9.4 的 semantics 合并会把下层的行标题吞掉
                // （无障碍树里读不到「森林记录」等标题），这里把标题补回语义
                .semantics { contentDescription = title }
        )
    }
}

/** 打开日志查看器(显示指定日志类型的全部条目) */
fun openLog(activity: MiuixMainActivity, logType: LogType) {
    try {
        activity.startActivity(
            Intent(activity, MiuixLogViewerActivity::class.java)
                .putExtra(LogType.EXTRA_LOG_TYPE, logType.name)
        )
    } catch (t: Throwable) {
        Log.printStackTrace(t)
    }
}

@Composable
fun ConfigTab(activity: MiuixMainActivity) {
    val context = LocalContext.current
    val items = remember {
        // (userId, 标题, 副标题)：标题固定为「账号N」保证单行不换行，昵称/账号放副标题
        val list = ArrayList<Triple<String?, String, String?>>()
        list.add(Triple(null, "默认", null))
        try {
            val dir = FileUtil.CONFIG_DIRECTORY_FILE
            dir.listFiles()?.forEach { configDir ->
                if (configDir.isDirectory) {
                    val userId = configDir.name
                    UserIdMap.loadSelf(userId)
                    val userEntity = UserIdMap.get(userId)
                    val label = UserIdMap.getAccountLabel(userId) ?: userId
                    // 副标题优先显示「昵称:账号」；新用户尚未被模块钩子同步资料（self.json 不存在）时
                    // 回退显示 userId 本身，避免空白且仍能区分账号
                    // 副标题优先显示「昵称:账号」；昵称缺失时只显示账号，不再出现字面 "null"
                    val summary = userEntity?.let { ue ->
                        ue.showName?.let { name -> "$name: ${ue.account}" } ?: (ue.account ?: userId)
                    } ?: userId
                    list.add(Triple(userId, label, summary))
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
        list
    }

    Text(
        text = "配置",
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
    )

    SmallTitle(text = "配置管理")
    CardColumn {
        items.forEach { (userId, title, summary) ->
            ArrowPreference(
                title = title,
                summary = summary,
                onClick = {
                    val intent = Intent(context, MiuixSettingsActivity::class.java)
                    if (userId != null) intent.putExtra("userId", userId)
                    context.startActivity(intent)
                }
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    // 模块功能：全局配置（不分账号），与上面的「按账号配置」并列放在配置页更合理
    SmallTitle(text = "模块功能")
    CardColumn {
        // 这几项原先是「按账号」存在账号配置里，现改为全局配置 AppConfig（模块级，不分账号）
        var newRpc by remember { mutableStateOf(AppConfig.INSTANCE.newRpc ?: true) }
        BooleanSwitch("使用新接口", newRpc, summary = "最低支持 v10.3.96.8100") {
            AppConfig.INSTANCE.newRpc = it
            AppConfig.save()
            newRpc = it
            // 换接口要重挂 RPC bridge，必须让注入进程整体重启（只重载配置不够）
            activity.broadcastRestart()
        }
        var showToast by remember { mutableStateOf(AppConfig.INSTANCE.showToast ?: true) }
        BooleanSwitch("气泡提示", showToast) {
            AppConfig.INSTANCE.showToast = it
            AppConfig.save()
            showToast = it
            activity.broadcastReloadConfig()
        }
        // 气泡纵向偏移：一级界面没有整数控件，用 ArrowPreference 展开输入框，输入即保存
        var toastOffsetY by remember { mutableStateOf((AppConfig.INSTANCE.toastOffsetY ?: 0).toString()) }
        var offsetExpanded by remember { mutableStateOf(false) }
        ArrowPreference(
            title = "气泡纵向偏移",
            summary = if (toastOffsetY.isEmpty()) "0 px（正数向下）" else "$toastOffsetY px（正数向下）",
            onClick = { offsetExpanded = !offsetExpanded }
        )
        if (offsetExpanded) {
            top.yukonga.miuix.kmp.basic.TextField(
                value = toastOffsetY,
                onValueChange = { text ->
                    // 只接受整数（允许开头一个负号），改完立刻写回并让注入进程重载
                    val filtered = text.filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) }
                    toastOffsetY = filtered
                    filtered.toIntOrNull()?.let { value ->
                        AppConfig.INSTANCE.toastOffsetY = value
                        AppConfig.save()
                        activity.broadcastReloadConfig()
                    }
                },
                // 不要 label：它会作为浮动小标题显示在输入框内部（与上方行标题重复）；单位说明放到上面的 summary 里
                label = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        var enableOnGoing by remember { mutableStateOf(AppConfig.INSTANCE.enableOnGoing ?: false) }
        BooleanSwitch("开启状态栏禁删", enableOnGoing) {
            AppConfig.INSTANCE.enableOnGoing = it
            AppConfig.save()
            enableOnGoing = it
            activity.broadcastReloadConfig()
        }
        var closeCaptchaDialog by remember { mutableStateOf(AppConfig.INSTANCE.closeCaptchaDialog ?: true) }
        BooleanSwitch("屏蔽部分弹窗", closeCaptchaDialog) {
            AppConfig.INSTANCE.closeCaptchaDialog = it
            AppConfig.save()
            closeCaptchaDialog = it
            activity.broadcastReloadConfig()
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
fun SettingsTab(activity: MiuixMainActivity) {
    val context = LocalContext.current

    Text(
        text = "设置",
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
    )

    SmallTitle(text = "功能设置")
    CardColumn {
        ArrowPreference(
            title = "好友统计",
            onClick = { context.startActivity(Intent(context, MiuixFriendStatsActivity::class.java)) }
        )
        ArrowPreference(
            title = "扩展功能",
            onClick = { context.startActivity(Intent(context, MiuixExtensionsActivity::class.java)) }
        )
    }
    Spacer(Modifier.height(16.dp))

    SmallTitle(text = "系统设置")
    CardColumn {
        // 文件权限申请引导
        val hasFilePerm = activity.hasPermission
        if (!hasFilePerm) {
            ArrowPreference(
                title = "申请文件权限",
                summary = "模块需要文件权限才能正常运行",
                onClick = {
                    try {
                        PermissionUtil.checkOrRequestFilePermissions(activity)
                        activity.hasRequestedPermission = true
                    } catch (e: Exception) {
                        ToastUtil.show(context, "申请权限失败")
                    }
                }
            )
        }
        var iconHidden by remember { mutableStateOf(activity.isIconHidden()) }
        BooleanSwitch("隐藏图标", iconHidden) {
            activity.toggleHideIcon()
            iconHidden = activity.isIconHidden()
        }
        var logViewByRun by remember { mutableStateOf(AppConfig.INSTANCE.logViewByRun ?: false) }
        BooleanSwitch("日志查看方式切换", logViewByRun) {
            AppConfig.INSTANCE.logViewByRun = it
            AppConfig.save()
            logViewByRun = it
        }
        var darkMode by remember { mutableStateOf(AppConfig.INSTANCE.darkMode ?: false) }
        BooleanSwitch("深色模式", darkMode) {
            AppConfig.INSTANCE.darkMode = it
            AppConfig.save()
            darkMode = it
            activity.recreate()
        }
        var followSystem by remember { mutableStateOf(AppConfig.INSTANCE.followSystem ?: true) }
        BooleanSwitch("跟随系统设置", followSystem) {
            AppConfig.INSTANCE.followSystem = it
            AppConfig.save()
            followSystem = it
            activity.recreate()
        }
        var batteryPerm by remember { mutableStateOf(AppConfig.INSTANCE.batteryPerm ?: true) }
        BooleanSwitch("为支付宝申请后台运行权限", batteryPerm) {
            AppConfig.INSTANCE.batteryPerm = it
            AppConfig.save()
            batteryPerm = it
        }
        if (batteryPerm) {
            val hasPerm = try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.isIgnoringBatteryOptimizations("com.eg.android.AlipayGphone") == true
            } catch (e: Exception) {
                false
            }
            if (!hasPerm) {
                ArrowPreference(
                    title = "立即申请权限",
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                data = Uri.parse("package:" + "com.eg.android.AlipayGphone")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            ToastUtil.show(context, "申请权限失败")
                        }
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    SmallTitle(text = "关于")
    CardColumn {
        ArrowPreference(
            title = "关于应用",
            onClick = { context.startActivity(Intent(context, MiuixAboutActivity::class.java)) }
        )
    }
    Spacer(Modifier.height(16.dp))

}

@Composable
fun BooleanSwitch(title: String, checked: Boolean, summary: String? = null, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

@Composable
fun CardColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // Card 只传 modifier：preference 行直接作为子项，行的左右缩进交给行自身的 insideMargin。
    // 之前给 Card 传 insideMargin 会把所有行整体往里缩，行自带的方形按压高亮就成了"悬在卡片里的方框"；
    // 让行顶满卡片宽度后，高亮是一条通栏色带，圆角由卡片自身裁剪处理。
    Card(modifier = modifier.fillMaxWidth()) {
        content()
    }
}
