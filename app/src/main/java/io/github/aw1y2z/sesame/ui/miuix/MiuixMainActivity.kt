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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import java.util.Calendar

class MiuixMainActivity : MiuixBaseActivity() {

    var runTypeText by mutableStateOf("")

    /** 可观察的激活状态：HomeTab 等页面订阅它，onServiceBind/广播更新时自动重组刷新 */
    var uiRunType by mutableStateOf<RunType>(RunType.DISABLE)
    var statisticsText by mutableStateOf("")
    var hasPermission by mutableStateOf(false)

    private val handler = Handler(Looper.getMainLooper())
    private var isClick = false

    /** 激活探测已重试次数,上限见 MAX_RUN_TYPE_PROBE_TIMES */
    private var runTypeProbeTimes = 0

    private lateinit var titleRunner: Runnable

    init {
        /**
         * 激活探测:仅当真实状态仍为 DISABLE 时才显示未激活,并在超时前周期性重试,
         * 避免覆盖晚到的 onServiceBind 激活信号,也避免 XposedService 绑定较慢时误报未激活
         */
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
         * 设备显示名:优先读市场名(如 Xiaomi 13)。
         * 小米/红米及多数云手机、模拟器的 ro.product.model 只是内部型号编号(如 2211133C),
         * 多设备会显示相同,须用 ro.product.marketname 才有人类可读名称。
         */
        fun getDeviceDisplayName(): String {
            try {
                val clazz = Class.forName("android.os.SystemProperties")
                val get = clazz.getMethod("get", String::class.java)
                val market = get.invoke(null, "ro.product.marketname") as? String
                if (!market.isNullOrBlank()) {
                    return market
                }
            } catch (_: Throwable) {
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

                    "io.github.aw1y2z.sesame.update" -> {
                        Statistics.load()
                        statisticsText = Statistics.getText(this@MiuixMainActivity)
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
        intentFilter.addAction("io.github.aw1y2z.sesame.update")
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
        // 激活状态探测独立于存储权限：防止 titleRunner 重复累积，先清空再启动
        if (RunType.DISABLE == ViewAppInfo.getRunType()) {
            handler.removeCallbacks(titleRunner)
            runTypeProbeTimes = 0
            sendQueryBroadcast()
            handler.postDelayed(titleRunner, 3000)
        }
        if (hasPermission) {
            try {
                Statistics.load()
                Statistics.updateDay(Calendar.getInstance())
                statisticsText = Statistics.getText(this)
            } catch (e: Exception) {
                Log.printStackTrace(e)
            }
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
        try {
            isClick = true
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.status"))
        } catch (th: Throwable) {
            Log.i("view sendBroadcast status err:")
            Log.printStackTrace(th)
        }
    }

    /** 向支付宝进程查询本模块注入状态（不弹 Toast），由 titleRunner 周期性调用 */
    fun sendQueryBroadcast() {
        try {
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.status"))
        } catch (th: Throwable) {
            Log.i("view sendBroadcast status err:")
            Log.printStackTrace(th)
        }
    }

    /** 通知支付宝进程重载共享配置（日志开关等），使开关在注入进程中即时生效 */
    fun broadcastReloadConfig() {
        try {
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.reloadConfig"))
        } catch (th: Throwable) {
            Log.i("view sendBroadcast reloadConfig err:")
            Log.printStackTrace(th)
        }
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

    fun exportStatistics(): Uri? {
        return FileUtil.getExportedStatisticsFile()?.let { Uri.fromFile(it) }
    }

    fun importStatistics(): Boolean {
        val src = FileUtil.getExportedStatisticsFile()
        if (src != null && FileUtil.copyTo(src, FileUtil.getStatisticsFile())) {
            statisticsText = Statistics.getText(this)
            return true
        }
        return false
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
        } catch (_: Exception) {
        }
    }
}

@Composable
fun MainScreen(activity: MiuixMainActivity) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        if (!PermissionUtil.checkOrRequestFilePermissions(activity)) {
            activity.hasPermission = false
        } else {
            activity.hasPermission = true
        }
        onDispose { }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
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
                2 -> ConfigTab()
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
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
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
                    fontSize = 14.sp,
                    color = Color(0xFF2E7D32)
                )
                Text(
                    text = "API 102",
                    fontSize = 14.sp,
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

    SmallTitle(text = "模块状态")
    CardColumn {
        StatusRow("模块状态", if (activated) "已激活" else "未激活")
        StatusRow("版本", version)
        StatusRow("SDK API", Build.VERSION.SDK_INT.toString())
        StatusRow("设备", MiuixMainActivity.getDeviceDisplayName())
        StatusRow("系统架构", Build.SUPPORTED_ABIS?.firstOrNull() ?: "")
    }
    Spacer(Modifier.height(16.dp))

    SmallTitle(text = "数据统计")
    CardColumn {
        StatisticsTable()
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onBackground)
        Text(text = value, fontSize = 15.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
fun StatisticsTable() {
    val rows = listOf(
        "收" to listOf(DataType.COLLECTED),
        "帮" to listOf(DataType.HELPED),
        "浇" to listOf(DataType.WATERED),
        "被水" to listOf(DataType.WATEREDCOUNT),
        "浇水" to listOf(DataType.WATERINGCOUNT)
    )
    val columns = listOf(TimeType.DAY, TimeType.MONTH, TimeType.YEAR)
    val headers = listOf("今日", "本月", "今年")

    Column {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f))
            headers.forEach { header ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = header, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
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
                    Text(text = label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onBackground)
                }
                columns.forEach { timeType ->
                    val value = types.sumOf { Statistics.getData(timeType, it) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(text = value.toString(), fontSize = 15.sp, color = MiuixTheme.colorScheme.onBackground)
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
        LogSwitchRow("抓包记录", debug, onClick = { openLog(activity, LogType.DEBUG) }) {
            debug = it
            AppConfig.INSTANCE.enableDebugLog = it
            AppConfig.save()
            activity.broadcastReloadConfig()
            if (!it) FileUtil.clearLog("debug")
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

/** 日志条目行：点按整行进入对应日志详情；右侧开关控制是否记录 */
@Composable
fun LogSwitchRow(title: String, checked: Boolean, onClick: () -> Unit, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            color = MiuixTheme.colorScheme.onBackground
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
fun ConfigTab() {
    val context = LocalContext.current
    val items = remember {
        val list = ArrayList<Pair<String?, String>>()
        list.add(null to "默认")
        try {
            val dir = FileUtil.CONFIG_DIRECTORY_FILE
            dir.listFiles()?.forEach { configDir ->
                if (configDir.isDirectory) {
                    val userId = configDir.name
                    UserIdMap.loadSelf(userId)
                    val userEntity = UserIdMap.get(userId)
                    val name = userEntity?.let { it.showName + ": " + it.account } ?: userId
                    list.add(userId to name)
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
        items.forEach { (userId, name) ->
            ArrowPreference(
                title = name,
                onClick = {
                    val intent = Intent(context, MiuixSettingsActivity::class.java)
                    if (userId != null) intent.putExtra("userId", userId)
                    context.startActivity(intent)
                }
            )
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
        var iconHidden by remember { mutableStateOf(activity.isIconHidden()) }
        BooleanSwitch("隐藏图标", iconHidden) {
            activity.toggleHideIcon()
            iconHidden = activity.isIconHidden()
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
fun BooleanSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreference(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

@Composable
fun CardColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        content()
    }
}
