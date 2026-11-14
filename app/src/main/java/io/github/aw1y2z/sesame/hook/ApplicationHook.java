package io.github.aw1y2z.sesame.hook;

import static io.github.aw1y2z.sesame.hook.SimplePageManager.addHandler;
import static io.github.aw1y2z.sesame.hook.SimplePageManager.enableWindowMonitoring;
import io.github.aw1y2z.sesame.hook.CaptchaHook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

import io.github.aw1y2z.sesame.util.compat.XC_MethodHook;

import io.github.aw1y2z.sesame.util.XHelpers;
import io.github.aw1y2z.sesame.util.compat.XC_LoadPackage;

import java.lang.ref.WeakReference;
import java.util.Objects;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.aw1y2z.sesame.util.compat.XC_MethodReplacement;
import io.github.aw1y2z.sesame.BuildConfig;
import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.Model;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.RunType;
import io.github.aw1y2z.sesame.data.TokenConfig;
import io.github.aw1y2z.sesame.data.ViewAppInfo;
import io.github.aw1y2z.sesame.data.task.BaseTask;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.entity.AlipayVersion;
import io.github.aw1y2z.sesame.entity.FriendWatch;
import io.github.aw1y2z.sesame.entity.RpcEntity;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.model.extensions.TestRpc;
import io.github.aw1y2z.sesame.model.normal.base.BaseModel;
import io.github.aw1y2z.sesame.model.task.antMember.AntMemberRpcCall;
import io.github.aw1y2z.sesame.rpc.bridge.NewRpcBridge;
import io.github.aw1y2z.sesame.rpc.bridge.OldRpcBridge;
import io.github.aw1y2z.sesame.data.AppConfig;
import io.github.aw1y2z.sesame.rpc.bridge.RpcBridge;
import io.github.aw1y2z.sesame.rpc.bridge.RpcVersion;
import io.github.aw1y2z.sesame.rpc.intervallimit.RpcIntervalLimit;
import io.github.aw1y2z.sesame.util.ClassUtil;
import io.github.aw1y2z.sesame.util.FileUtil;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.NotificationUtil;
import io.github.aw1y2z.sesame.util.PermissionUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;
import lombok.Getter;

import androidx.annotation.NonNull;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ApplicationHook extends XposedModule {

    private static final String TAG = ApplicationHook.class.getSimpleName();

    /** 好友个人主页所属的小程序 appId（抓包取证）。 */
    private static final String PROFILE_APP_ID = "20002167";
    /** 承载小程序页的容器 Activity 全类名。 */
    private static final String PROFILE_CONTAINER_CLASS = "com.alipay.android.phone.xriver.bundlex.CSGAPushActivity";
    /** 模块 App 点击好友行时写入的待打开标记，与 Kotlin 侧 PENDING_PROFILE_FILE 保持一致。 */
    private static final String PENDING_PROFILE_FILE = "pending_profile.txt";
    /** 待打开标记的有效期：超过这个时间视为用户早已放弃，冷启动后再跳转会很突兀。 */
    private static final long PENDING_PROFILE_TTL = 20_000L;
    /** 上次打开的好友主页，用于屏蔽模块 App 补发的重复广播。 */
    private static String lastProfileUserId;
    private static long lastProfileTime;
    /** 上一次打开的好友页容器（CSGAPushActivity）；再打开新好友时先关掉它，返回才会回到主界面而不是上一个好友。 */
    private static WeakReference<Activity> lastProfileActivity = new WeakReference<>(null);
    /** 好友跳转发起时间：紧随其后创建的小程序容器页即视为好友页。 */
    private static long profileOpenExpectAt;
    /** 跳转发起后多久内创建的容器页仍算好友页，需覆盖冷启动耗时。 */
    private static final long PROFILE_CONTAINER_EXPECT_WINDOW = 10_000L;

    @Getter
    private static final String modelVersion = BuildConfig.VERSION_NAME;

    private static final Map<Object, Object[]> rpcHookMap = new ConcurrentHashMap<>();

    private static final Map<String, PendingIntent> wakenAtTimeAlarmMap = new ConcurrentHashMap<>();

    @Getter
    private static volatile ClassLoader classLoader = null;

    @Getter
    private static volatile Object microApplicationContextObject = null;

    // 新增：全局静态变量，存储当前进程名
    public static volatile String processName; // 供其他方法（如 startIfNeeded）调用

    /** 模块 App 自己的包名：须与 app/build.gradle 的 applicationId 一致，用于校验广播发送方 */
    private static final String MODULE_PACKAGE_NAME = "io.github.aw1y2z.sesame";
    /** 宿主包名：配对回包与状态回包必须定向到它，否则第三方也能收到 */
    private static final String HOST_PACKAGE_NAME = "com.eg.android.AlipayGphone";
    /** adb shell 的 uid：`adb shell am broadcast` 以它发送，放行以便命令行调试 */
    private static final int SHELL_UID = 2000;

    /** App → 宿主：请求配对。无校验（回包定向且幂等），第三方触发也拿不到令牌 */
    public static final String ACTION_PAIR_REQUEST = HOST_PACKAGE_NAME + ".sesame.pair.request";
    /** 宿主 → App：配对回包，带令牌与 challenge，必须 setPackage 定向 */
    public static final String ACTION_PAIR_RESULT = MODULE_PACKAGE_NAME + ".pair.result";
    /** 系统代发：定时唤醒。靠 alarm_nonce 校验，不依赖发送方 uid */
    public static final String ACTION_ALARM_EXECUTE = HOST_PACKAGE_NAME + ".sesame.alarm.execute";
    /** App → 宿主：带令牌的受控通道（restart/reloadConfig/execute/status/openProfile） */
    public static final String ACTION_CONTROL = HOST_PACKAGE_NAME + ".sesame.control";
    /** 宿主 → App：control 的 status 回包，必须 setPackage 定向 */
    public static final String ACTION_STATUS_REPLY = MODULE_PACKAGE_NAME + ".status";

    /**
     * control 通道接受的 op。{@code rpctest} 绝不入内：那是「任意 method + data 打进 RPC 层」，
     * 只在调试场景走 HTTP；森林查询、走路路径改走 {@code forestQuery}/{@code walkPath}，
     * method 由宿主侧按枚举校验，等价于固定动作。
     */
    private static final java.util.Set<String> CONTROL_OPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "restart", "reloadConfig", "execute", "status", "openProfile", "forestQuery", "walkPath"));
    /** 森林查询允许的 method，与扩展页按钮一一对应 */
    private static final java.util.Set<String> FOREST_QUERY_METHODS = new java.util.HashSet<>(java.util.Arrays.asList(
            "getWateredItems", "getWateringItems", "getTreeItems", "getNewTreeItems",
            "queryAreaTrees", "getUnlockTreeItems", "fillWateredFriendList"));
    /** 自定义走路路径允许的 method */
    private static final java.util.Set<String> WALK_PATH_METHODS = new java.util.HashSet<>(java.util.Arrays.asList(
            "addCustomWalkPathId", "addCustomWalkPathIdQueue", "clearCustomWalkPathIdQueue"));
    /**
     * control 通道频控：按 op 分维度计时。共用一张表时探活（3s 一次）会永远撞窗口，
     * 并让紧接着的 restart/execute 被判为「过于频繁」。
     */
    private static final long CONTROL_MIN_INTERVAL_STATUS = 2_000L;
    private static final long CONTROL_MIN_INTERVAL_RESTART = 10_000L;
    private static final long CONTROL_MIN_INTERVAL_EXECUTE = 60_000L;
    /** 跳转兜底是 App 侧 1.5s 后补发，窗口必须比它短，否则连点不同好友时兜底会被吞掉 */
    private static final long CONTROL_MIN_INTERVAL_OPEN_PROFILE = 1_000L;
    private static final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> lastControlAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 配对请求的频控：第三方刷这条 action 只会让我们多发定向广播 */
    private static final long PAIR_MIN_INTERVAL = 60_000L;
    private static final java.util.concurrent.atomic.AtomicLong lastPairAt =
            new java.util.concurrent.atomic.AtomicLong(0);
    /** 本进程使用的 HTTP 鉴权令牌：配对口令与闹钟 nonce 都由它派生 */
    private static volatile String httpToken;
    /** 当前 hook 实例：initHandler 一族是实例方法，受控通道需要从静态入口回调它们 */
    private static volatile ApplicationHook self;

    @Getter
    private static volatile Context context = null; // 全局上下文，对应 Kotlin 的 appContext
    @SuppressLint("StaticFieldLeak")
    private static volatile Service service; // 目标 Service 实例，也是 Context 子类

    @Getter
    private static volatile AlipayVersion alipayVersion = new AlipayVersion("");

    @Getter
    private static volatile boolean hooked = false;

    private static volatile boolean init = false;

    /** 标记一次重载是否正在进行，避免重载期间被主线程反复丢后台线程造成重复初始化 */
    private static volatile boolean initializing = false;

    private static volatile Calendar dayCalendar;

    @Getter
    private static volatile boolean offline = false;

    @Getter
    private static final AtomicInteger reLoginCount = new AtomicInteger(0);

    @Getter
    private static Handler mainHandler;

    private static BaseTask mainTask;

    private static RpcBridge rpcBridge;

    @Getter
    private static RpcVersion rpcVersion;

    private static PowerManager.WakeLock wakeLock;

    private static PendingIntent alarm0Pi;

    private static XC_MethodHook.Unhook rpcRequestUnhook;

    private static XC_MethodHook.Unhook rpcResponseUnhook;

    private static BroadcastReceiver broadcastReceiver = null;

    private static volatile boolean broadcastReceiverRegistered = false;

    /** 主进程"模块初始化开始"标记是否已打印（供日志查看器按轮分组，每个主进程加载周期只打一次） */
    private static volatile boolean initStartLogged = false;

    public static void setOffline(boolean offline) {
        ApplicationHook.offline = offline;
    }

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        self = this;
        XHelpers.init(this);
        log(4, TAG, "event=module_loaded api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " version=" + getFrameworkVersion());
        try {
            // 加载起点标记：置于 AppConfig.load 之前，供日志查看器把整个加载阶段归到其后一轮的开头。
            // 仅主进程打印，与 handleLoadPackage 的兜底打印共用去重标志
            if (ClassUtil.PACKAGE_NAME.equals(getCurrentProcessName(ClassUtil.PACKAGE_NAME)) && !initStartLogged) {
                initStartLogged = true;
                Log.i("模块初始化开始");
            }
            // 读取与 App 共享的日志开关配置，使各分项开关在本进程真正生效
            AppConfig.load();
            // 模块已在 LSPosed 中启用：onModuleLoaded 被调用即代表已启用，
            // 直接标记为已激活（与是否打开 / hook 支付宝无关）
            ViewAppInfo.setRunTypeByCode(RunType.MODEL.getCode());
            // 若 UI 已启动，发同进程广播实时刷新界面
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app != null) {
                sendStatusBroadcast(app);
            }
        } catch (Throwable t) {
            // 模块激活相关步骤（配置加载/激活标记/激活广播）失败必须可见，否则「模块没生效」毫无线索
            Log.printStackTrace(TAG + " onModuleLoaded", t);
        }
    }


    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        // 用真实进程名：支付宝每个子进程都会回调 onPackageReady，若直接拿包名当进程名，
        // 会绕过主进程判断，导致每个子进程都重复执行一遍 hook 与初始化（日志刷屏）
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam();
        lpparam.packageName = param.getPackageName();
        lpparam.processName = getCurrentProcessName(param.getPackageName());
        lpparam.classLoader = param.getClassLoader();
        handleLoadPackage(lpparam);
    }

    /** 当前进程名；取不到时回退包名（行为同旧版） */
    private static String getCurrentProcessName(String fallback) {
        // 首选 /proc/self/cmdline：不依赖 ActivityThread 是否已完成 attach。
        // onModuleLoaded 等早期阶段 currentProcessName() 仍为空，会误回退包名，
        // 导致各子进程被当成主进程（重复初始化 / 加载标记刷屏）
        try {
            byte[] buf = new byte[256];
            int n;
            try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
                n = in.read(buf);
            }
            if (n > 0) {
                int end = 0;
                while (end < n && buf[end] != 0) {
                    end++;
                }
                if (end > 0) {
                    String name = new String(buf, 0, end, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读取失败时落到 ActivityThread 反射
        }
        try {
            Object processName = Class.forName("android.app.ActivityThread")
                    .getMethod("currentProcessName").invoke(null);
            if (processName instanceof String && !((String) processName).isEmpty()) {
                return (String) processName;
            }
            Log.i(TAG, "getCurrentProcessName empty, fallback=" + fallback);
        } catch (Throwable t) {
            Log.i(TAG, "getCurrentProcessName failed, fallback=" + fallback);
        }
        return fallback;
    }

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 先提取进程名并赋值给全局变量
        processName = lpparam.processName; // 新增：将 Xposed 提供的进程名赋值给全局变量
        if (ClassUtil.PACKAGE_NAME.equals(lpparam.packageName) && ClassUtil.PACKAGE_NAME.equals(lpparam.processName)) {
            // 提前在锁内落标志：onPackageReady 与旧回调 handleLoadPackage 可能并发/重复触发，
            // 旧写法在方法末尾才置位，竞态下会重复执行整套 hook 与初始化
            synchronized (ApplicationHook.class) {
                if (hooked) {
                    return;
                }
                hooked = true;
            }
            classLoader = lpparam.classLoader;
            // 持锁标记「宿主主进程在运行」：模块 App 删账号前靠它判断，
            // 宿主活着的时候不能删（注入进程会把账号目录立刻写回来）
            FileUtil.acquireHostLock();
            // 兜底：若 onModuleLoaded 未先行打印（理论上不会），这里补上加载起点标记，
            // 保证每个主进程加载周期有且仅有一条"模块初始化开始"
            if (!initStartLogged) {
                initStartLogged = true;
                Log.i("模块初始化开始");
            }

            XHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    context = (Context) param.args[0];
                    alipayVersion = new AlipayVersion(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
                    try {
                        AlipayMiniMarkHelper.init(classLoader);
                        AuthCodeHelper.init(classLoader);
                        // 小程序抓包（游戏等小程序数据）：与日志页「抓包记录」共用开关
                        MiniProgramHook.install(classLoader, processName);
                        // 同步调用：异步写法会被当成不阻塞，实际这里会等初始化完成
                        // 启动时不再调 getAuthCode：返回值被丢弃，且在当前版本必然失败（facade 为 null）
                        initSimplePageManager();
                    } catch (Exception e) {
                        Log.printStackTrace(e);
                    }
                    super.afterHookedMethod(param);
                }
            });
            try {
                XHelpers.findAndHookMethod("com.alipay.mobile.nebulaappproxy.api.rpc.H5AppRpcUpdate", classLoader, "matchVersion", classLoader.loadClass(ClassUtil.H5PAGE_NAME), Map.class, String.class, XC_MethodReplacement.returnConstant(false));
            } catch (Throwable t) {
                Log.err(TAG, "hook matchVersion err:", t);
            }
            try {
                XHelpers.findAndHookMethod("com.alipay.mobile.quinox.LauncherActivity", classLoader, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String targetUid = getUserId();
                        if (targetUid == null) {
                            Log.record("用户未登录");
                            Toast.show("用户未登录");
                            return;
                        }
                        if (!init) {
                            // 重载在后台线程执行，加载成功后会自行置 init=true；此处无需依赖返回值
                            initHandler(true);
                            return;
                        }
                        String currentUid = UserIdMap.getCurrentUid();
                        if (!targetUid.equals(currentUid)) {
                            if (currentUid != null) {
                                Handler handler = ApplicationHook.getMainHandler();
                                if (handler == null) {
                                    // Service onCreate hook 尚未跑完（此时 service/context 也未就绪），
                                    // 丢给下一次 onResume 或加载完成后的流程，不在这里 NPE
                                    Log.record("跳过用户切换处理：mainHandler 尚未就绪");
                                    return;
                                }
                                handler.postDelayed(() -> {
                                    Log.record("用户已切换");
                                    Toast.show("用户已切换");
                                    initHandler(true);
                                }, 1000);
                                return;
                            }
                            UserIdMap.initUser(targetUid);
                        }
                        if (offline) {
                            offline = false;
                            execHandler();
                            ((Activity) param.thisObject).finish();
                            Log.i(TAG, "Activity reLogin");
                        }
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook login err:", t);
            }
            // 只有兜底走 alipays:// 时才会创建这个中转页，设透明主题减轻闪烁。
            // 不要靠改窗口尺寸/alpha 隐藏它：自由窗口下边界由系统按任务计算，压不住；
            // 残留的 alpha=0 窗口盖在主界面上就是黑屏。
            try {
                XHelpers.findAndHookMethod("com.alipay.mobile.quinox.SchemeLauncherActivity", classLoader, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Activity) {
                                ((Activity) param.thisObject).setTheme(android.R.style.Theme_Translucent_NoTitleBar);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook SchemeLauncherActivity err:", t);
            }
            // 连续打开不同好友时，小程序容器会在同一 task 里叠出多个实例，
            // 返回就会退回上一个好友页。容器本身没有重写 onCreate，这里挂 Activity.onCreate 再按类名筛。
            try {
                XHelpers.findAndHookMethod("android.app.Activity", classLoader, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!(param.thisObject instanceof Activity)) {
                                return;
                            }
                            Activity activity = (Activity) param.thisObject;
                            if (!PROFILE_CONTAINER_CLASS.equals(activity.getClass().getName())) {
                                return;
                            }
                            handleProfileContainer(activity);
                        } catch (Throwable th) {
                            Log.err(TAG, "handleProfileContainer err:", th);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook CSGAPushActivity err:", t);
            }
            // 模块 App 点击好友时会把目标好友落盘并立刻拉起支付宝。
            // 这里在支付宝任意页面 resume 时消费该标记，热启动/冷启动都能在第一帧就跳走，
            // 不必再靠模块 App 延迟发广播（那 2.5s 的等待就是延迟的来源）。
            try {
                XHelpers.findAndHookMethod("android.app.Activity", classLoader, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Activity) {
                                checkPendingProfile((Activity) param.thisObject);
                            }
                        } catch (Throwable th) {
                            Log.err(TAG, "checkPendingProfile in onResume err:", th);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook Activity.onResume err:", t);
            }
            // 支付宝以小窗/分屏形态在前台时，被模块 App 拉起只是抢到窗口焦点，不会重新走 onResume，
            // 标记就没人消费，最后落到兜底广播——那条路要在后台任务里启动页面，系统必补一个中转页。
            // 焦点到手就消费，此时支付宝已经在前台，跳转就是普通的前台启动。
            try {
                XHelpers.findAndHookMethod("android.app.Activity", classLoader, "onWindowFocusChanged", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!(param.thisObject instanceof Activity) || param.args.length == 0) {
                                return;
                            }
                            if (!Boolean.TRUE.equals(param.args[0])) {
                                return;
                            }
                            checkPendingProfile((Activity) param.thisObject);
                        } catch (Throwable th) {
                            Log.err(TAG, "checkPendingProfile in onWindowFocusChanged err:", th);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook Activity.onWindowFocusChanged err:", t);
            }
            try {
                XHelpers.findAndHookMethod("android.app.Service", classLoader, "onCreate", new XC_MethodHook() {

                    @SuppressLint({"WakelockTimeout", "UnsafeDynamicallyLoadedCode"})
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 1. 获取目标 Service 实例（appService 是 Service 子类，也是 Context 类型）
                        Service appService = (Service) param.thisObject;
                        if (!ClassUtil.CURRENT_USING_SERVICE.equals(appService.getClass().getCanonicalName())) {
                            return;// 非目标 Service，直接返回，保障只处理支付宝前台服务
                        }

                        // 2. 兜底赋值全局 context（对应 Kotlin appContext 的二次赋值）
                        context = appService.getApplicationContext(); // 获取应用全局上下文，更新全局变量
                        service = appService; // 存储 Service 实例，供后续复用

                        // 3. 调用 registerBroadcastReceiver，传入参数 Context（appService）
                        // 这里的 appService 就是对应 Kotlin registerBroadcastReceiver(appContext!!) 的参数
                        registerBroadcastReceiver(appService);

                        // 主动通知 App 本模块已被 LSPosed 启用并注入支付宝，用于显示「已激活」
                        // 广播失败会导致 UI 迟迟显示「未激活」，sendStatusBroadcast 内部已记日志
                        sendStatusBroadcast(appService);

                        context = appService.getApplicationContext();
                        service = appService;
                        mainHandler = new Handler(Looper.getMainLooper());
                        mainTask = BaseTask.newInstance("MAIN_TASK", new Runnable() {

                            private volatile long lastExecTime = 0;

                            @Override
                            public void run() {
                                int checkInterval = 0;
                                try {
                                    checkInterval = BaseModel.getCheckInterval().getValue();
                                    if (!init) {
                                        return;
                                    }
                                    Log.record("应用版本：" + alipayVersion.getVersionString());
                                    Log.record("模块版本：" + modelVersion);
                                    Log.record("开始执行");
                                    if (lastExecTime + 2000 > System.currentTimeMillis()) {
                                        Log.record("执行间隔较短，跳过执行");
                                        execDelayedHandler(checkInterval);
                                        return;
                                    }
                                    updateDay();
                                    String targetUid = getUserId();
                                    String currentUid = UserIdMap.getCurrentUid();
                                    if (targetUid == null || currentUid == null) {
                                        Log.record("用户为空，放弃执行");
                                        reLogin();
                                        return;
                                    }
                                    if (!targetUid.equals(currentUid)) {
                                        Log.record("开始切换用户");
                                        Toast.show("开始切换用户");
                                        reLogin();
                                        return;
                                    }
                                    // 本轮 check 的起点单独留一份：waitTime 只依赖它，不再依赖会被后续重写的 lastExecTime
                                    long checkStartTimestamp = System.currentTimeMillis();
                                    lastExecTime = checkStartTimestamp;
                                    try {
                                        FutureTask<Boolean> checkTask = new FutureTask<>(AntMemberRpcCall::check);
                                        Thread checkThread = new Thread(checkTask);
                                        checkThread.start();
                                        if (!checkTask.get(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                                            long waitTime = CHECK_TIMEOUT_MS - (System.currentTimeMillis() - checkStartTimestamp);
                                            if (waitTime > 0) {
                                                Thread.sleep(waitTime);
                                            }
                                            Log.record("执行失败：检查超时");
                                            reLogin();
                                            return;
                                        }
                                        reLoginCount.set(0);
                                    } catch (InterruptedException | ExecutionException |
                                             TimeoutException e) {
                                        Log.record("执行失败：检查中断");
                                        reLogin();
                                        return;
                                    } catch (Exception e) {
                                        Log.record("执行失败：检查异常");
                                        reLogin();
                                        Log.printStackTrace(TAG, e);
                                        return;
                                    }
                                    TaskCommon.update();
                                    ModelTask.startAllTask(false);
                                    lastExecTime = System.currentTimeMillis();

                                    try {
                                        List<String> execAtTimeList = BaseModel.getExecAtTimeList().getValue();
                                        if (execAtTimeList != null) {
                                            Calendar lastExecTimeCalendar = TimeUtil.getCalendarByTimeMillis(lastExecTime);
                                            Calendar nextExecTimeCalendar = TimeUtil.getCalendarByTimeMillis(lastExecTime + checkInterval);
                                            for (String execAtTime : execAtTimeList) {
                                                Calendar execAtTimeCalendar = TimeUtil.getTodayCalendarByTimeStr(execAtTime);
                                                if (execAtTimeCalendar != null && lastExecTimeCalendar.compareTo(execAtTimeCalendar) < 0 && nextExecTimeCalendar.compareTo(execAtTimeCalendar) > 0) {
                                                    Log.record("设置定时执行:" + execAtTime);
                                                    execDelayedHandler(execAtTimeCalendar.getTimeInMillis() - lastExecTime);
                                                    FileUtil.clearLog();
                                                    return;
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        Log.err(TAG, "execAtTime err:", e);
                                    }

                                    // 只收能量窗口内要高频重收，不能按执行间隔整段跳过窗口
                                    execDelayedHandler(getNextInterval(checkInterval));
                                    FileUtil.clearLog();
                                } catch (Exception e) {
                                    Log.record("执行异常:");
                                    Log.printStackTrace(e);
                                } finally {
                                    // 单链之下"本轮没人排期"就等于永久停摆：兜住 !init、各处 return 与未捕获异常
                                    if (!tickScheduled) {
                                        execDelayedHandler(checkInterval > 0 ? checkInterval : FALLBACK_INTERVAL);
                                    }
                                }
                            }
                        });
                        dayCalendar = Calendar.getInstance();
                        Statistics.load();
                        FriendWatch.load();
                        if (initHandler(true)) {
                            init = true;
                        }
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook service onCreate err:", t);
            }
            try {
                XHelpers.findAndHookMethod("android.app.Service", classLoader, "onDestroy", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Service service = (Service) param.thisObject;
                        if (!ClassUtil.CURRENT_USING_SERVICE.equals(service.getClass().getCanonicalName())) {
                            return;
                        }
                        Log.record("支付宝前台服务被销毁");
                        NotificationUtil.updateStatusText("支付宝前台服务被销毁");
                        destroyHandler(true);
                        FriendWatch.unload();
                        Statistics.unload();
                        // 前台服务已销毁，紧接着要跑一轮，否则白等一个查询间隔
                        restartSelf(true);
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook service onDestroy err:", t);
            }
            // ---- 宿主的前后台询问：默认谎报"在前台"，唯独风控/滑块链路在真实后台时如实回答 ----
            // 一律谎报能让后台 H5/RPC 跑起来，但滑块会被判定为可展示、后台又拿不到窗口，
            // 验证流程死等用户滑动，切回支付宝即卡死。所以先 callOriginal 取真值，
            // 只有"真在后台 + 询问方是风控/滑块"才说实话。
            try {
                XHelpers.findAndHookMethod("com.alipay.mobile.common.fgbg.FgBgMonitorImpl", classLoader, "isInBackground", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(answerInBackgroundQuestion(param));
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook FgBgMonitorImpl method 1 err:", t);
            }
            try {
                XHelpers.findAndHookMethod("com.alipay.mobile.common.fgbg.FgBgMonitorImpl", classLoader, "isInBackground", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(answerInBackgroundQuestion(param));
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook FgBgMonitorImpl method 2 err:", t);
            }
            try {
                XHelpers.findAndHookMethod("com.alipay.mobile.common.fgbg.FgBgMonitorImpl", classLoader, "isInBackgroundV2", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(answerInBackgroundQuestion(param));
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook FgBgMonitorImpl method 3 err:", t);
            }
            try {
                XHelpers.findAndHookMethod("com.alipay.mobile.common.transport.utils.MiscUtils", classLoader, "isAtFrontDesk", classLoader.loadClass("android.content.Context"), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(answerAtFrontDeskQuestion(param));
                    }
                });
            } catch (Throwable t) {
                Log.err(TAG, "hook MiscUtils err:", t);
            }
        }
    }

    private static void setWakenAtTimeAlarm() {
        try {
            // 闹钟口令由令牌派生，令牌没解析出来时排出去的 PendingIntent 一定通不过校验，
            // 到点才发现等于整轮唤醒白等：这里先补一次解析，仍取不到就干脆不排。
            if (alarmNonce() == null) {
                resolveHttpToken();
            }
            if (alarmNonce() == null) {
                Log.record("定时唤醒未设置#令牌不可用，闹钟口令无法派生");
                return;
            }
            unsetWakenAtTimeAlarm();
            try {
                PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, alarmIntent(), getPendingIntentFlag());
                Calendar calendar = Calendar.getInstance();
                calendar.add(Calendar.DAY_OF_MONTH, 1);
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);
                if (setAlarmTask(calendar.getTimeInMillis(), pendingIntent)) {
                    alarm0Pi = pendingIntent;
                }
            } catch (Exception e) {
                Log.err(TAG, "setWakenAt0 err:", e);
            }
            List<String> wakenAtTimeList = BaseModel.getWakenAtTimeList().getValue();
            if (wakenAtTimeList != null && !wakenAtTimeList.isEmpty()) {
                Calendar nowCalendar = Calendar.getInstance();
                // 从 0 开始：原实现从下标 1 起，第一个唤醒时刻永远不注册（只配一个时刻时整体失效）。
                // request code 用 seq(1..n) 而不是下标：0 已被上面的 00:00 闹钟占用，
                // 复用同一个 request code 会让两个 PendingIntent 互相顶掉
                for (int i = 0, len = wakenAtTimeList.size(); i < len; i++) {
                    try {
                        int seq = i + 1;
                        String wakenAtTime = wakenAtTimeList.get(i);
                        Calendar wakenAtTimeCalendar = TimeUtil.getTodayCalendarByTimeStr(wakenAtTime);
                        if (wakenAtTimeCalendar != null) {
                            if (wakenAtTimeCalendar.compareTo(nowCalendar) > 0) {
                                PendingIntent wakenAtTimePendingIntent = PendingIntent.getBroadcast(context, seq, alarmIntent(), getPendingIntentFlag());
                                if (setAlarmTask(wakenAtTimeCalendar.getTimeInMillis(), wakenAtTimePendingIntent)) {
                                    String wakenAtTimeKey = seq + "|" + wakenAtTime;
                                    wakenAtTimeAlarmMap.put(wakenAtTimeKey, wakenAtTimePendingIntent);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.err(TAG, "setWakenAtTime err:", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.err(TAG, "setWakenAtTimeAlarm err:", e);
        }
    }

    private static void unsetWakenAtTimeAlarm() {
        try {
            for (Map.Entry<String, PendingIntent> entry : wakenAtTimeAlarmMap.entrySet()) {
                try {
                    String wakenAtTimeKey = entry.getKey();
                    PendingIntent wakenAtTimePendingIntent = entry.getValue();
                    if (unsetAlarmTask(wakenAtTimePendingIntent)) {
                        wakenAtTimeAlarmMap.remove(wakenAtTimeKey);
                    }
                } catch (Exception e) {
                    Log.err(TAG, "unsetWakenAtTime err:", e);
                }
            }
            try {
                if (unsetAlarmTask(alarm0Pi)) {
                    alarm0Pi = null;
                }
            } catch (Exception e) {
                Log.err(TAG, "unsetWakenAt0 err:", e);
            }
        } catch (Exception e) {
            Log.err(TAG, "unsetWakenAtTimeAlarm err:", e);
        }
    }

    @SuppressLint("WakelockTimeout")
    /**
     * 切换账号 / 首启的重载入口：这里只做需要 UI 反馈的快速前置检查，
     * 文件 IO、配置反序列化、装 Hook 等重活交给 {@link #runInit} 在后台线程跑，否则主线程会卡死。
     */
    private Boolean initHandler(Boolean force) {
        return initHandler(force, true);
    }

    /**
     * @param exec 重载后是否紧接着跑一轮。配置保存触发的重载传 false，但仍要排下一跳，
     *             否则重载清掉旧排期后任务链就断了。
     */
    private Boolean initHandler(Boolean force, boolean exec) {
        if (service == null) {
            return false;
        }
        // 快速前置检查：未登录 / 无闹钟权限，留在调用线程同步返回（Toast 内部已切主线程，后台调用也安全）
        if (force) {
            String userId = getUserId();
            if (userId == null) {
                Log.record("用户未登录");
                Toast.show("用户未登录");
                return false;
            }
            if (!PermissionUtil.checkAlarmPermissions()) {
                Log.record("支付宝无闹钟权限");
                mainHandler.postDelayed(() -> {
                    if (!PermissionUtil.checkOrRequestAlarmPermissions(context)) {
                        android.widget.Toast.makeText(context, "请授予支付宝使用闹钟权限", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }, 2000);
                return false;
            }
        }
        // 主线程调用则丢到后台线程执行，避免卡 UI；广播重启等已在后台线程的场景直接同步执行
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (initializing) {
                return false;
            }
            initializing = true;
            final Boolean f = force;
            new Thread(() -> runInit(f, exec), "Sesame-InitHandler").start();
            return null;
        }
        return runInit(force, exec);
    }

    /** 令牌是 32 字节随机数的十六进制串；只有符合这个形状的旧文件内容才继承 */
    private static final java.util.regex.Pattern HTTP_TOKEN_PATTERN =
            java.util.regex.Pattern.compile("[0-9a-f]{64}");

    /** 支付宝 userId 是纯数字；它会拼进 alipays:// 查询串，必须挡住其它字符 */
    private static final java.util.regex.Pattern USER_ID_PATTERN =
            java.util.regex.Pattern.compile("[0-9]{1,32}");

    /**
     * 本地 HTTP 鉴权令牌：首次生成后落在宿主私有目录并复用。
     * <p>读写失败返回空串：空令牌在 {@link BaseHandler#verifyToken} 里是拒绝，宁可服务不可用。
     */
    private static String resolveHttpToken() {
        // 缓存命中直接返回：激活广播要在 Service.onCreate 里发，那时 runInit 还没跑，
        // 不在这里先解析一次，回包就会缺令牌而被 App 当成伪造消息丢掉
        String cached = httpToken;
        if (cached != null) {
            return cached;
        }
        try {
            FileUtil.initPrivateDirectory(context);
            java.io.File tokenFile = FileUtil.getHttpTokenFile();
            String token = FileUtil.readFromFile(tokenFile).trim();
            if (!isHttpToken(token)) {
                // 升级后首次启动：把共享目录里的旧令牌搬进私有目录。值保持不变，
                // PC 侧已经保存的令牌继续有效。
                String legacy = FileUtil.readFromFile(FileUtil.getLegacyHttpTokenFile()).trim();
                token = isHttpToken(legacy) ? legacy : randomHttpToken();
                if (!FileUtil.write2File(token, tokenFile)) {
                    Log.record("HTTP 鉴权令牌写入失败，本次不启动 HTTP 服务");
                    return "";
                }
                Log.record("已生成 HTTP 鉴权令牌（存于私有目录 http_token.txt）");
            }
            syncLegacyHttpToken(token);
            // 配对通道与闹钟 nonce 都要用同一个值：缓存下来，避免每次广播都读一次私有文件
            httpToken = token;
            return token;
        } catch (Throwable t) {
            Log.err(TAG, "resolveHttpToken err:", t);
        }
        return "";
    }

    /**
     * 共享目录里的令牌副本与「抓包记录」开关同生共死：开着写、关着删。
     * <p>抓包开关本身就是 /debugHandler 的门禁，「PC 需要令牌」和「调试模式打开」是同一件事；
     * 读路径只认私有目录，副本被改写不影响鉴权。
     */
    private static void syncLegacyHttpToken(String token) {
        if (!Boolean.TRUE.equals(AppConfig.INSTANCE.getEnableDebugLog())) {
            removeMirrorTokenFile();
            return;
        }
        java.io.File mirrorFile = FileUtil.getLegacyHttpTokenFile();
        if (!token.equals(FileUtil.readFromFile(mirrorFile).trim())) {
            if (FileUtil.write2File(token, mirrorFile)) {
                Log.record("调试模式已开启，令牌副本：" + mirrorFile);
            } else {
                Log.error("令牌副本写入失败：" + mirrorFile);
            }
        }
    }

    /** 删掉共享目录里的令牌副本：不存在是正常情况，删除失败记一条错误日志 */
    private static void removeMirrorTokenFile() {
        java.io.File mirrorFile = FileUtil.getLegacyHttpTokenFile();
        if (mirrorFile.exists() && !mirrorFile.delete()) {
            Log.error("共享目录令牌副本删除失败：" + mirrorFile);
        }
    }

    private static boolean isHttpToken(String token) {
        return token != null && HTTP_TOKEN_PATTERN.matcher(token).matches();
    }

    private static String randomHttpToken() {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 真正执行重载，必须在非主线程调用（UI 提示已内部切回主线程）。
     * <p>synchronized 防止切号与首启 / 广播重启并发触发重复初始化。
     */
    private synchronized Boolean runInit(Boolean force, boolean exec) {
        destroyHandler(force);
        try {
            if (force) {
                String userId = getUserId();
                if (userId == null) {
                    Log.record("用户未登录");
                    Toast.show("用户未登录");
                    return false;
                }

                //调用 startIfNeeded 方法，参数与 Kotlin 保持一致
                ModuleHttpServerManager.getInstance().startIfNeeded(8080, resolveHttpToken(), processName, "com.eg.android.AlipayGphone");

                UserIdMap.initUser(userId);
                Model.initAllModel();
                ConfigV2.load(userId);

                boolean enableModule = Model.getModel(BaseModel.class).getEnableField().getValue();
                if (!enableModule) {
                    Log.record("芝麻粒已禁用");
                    Toast.show("芝麻粒已禁用");
                    return false;
                }
                if (io.github.aw1y2z.sesame.data.AppConfig.INSTANCE.getBatteryPerm() && !init && !PermissionUtil.checkBatteryPermissions()) {
                    Log.record("支付宝无始终在后台运行权限");
                    mainHandler.postDelayed(() -> {
                        if (!PermissionUtil.checkOrRequestBatteryPermissions(context)) {
                            android.widget.Toast.makeText(context, "请授予支付宝终在后台运行权限", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }, 2000);
                }
                if (AppConfig.INSTANCE.getNewRpc()) {
                    rpcBridge = new NewRpcBridge();
                } else {
                    rpcBridge = new OldRpcBridge();
                }
                rpcBridge.load();
                rpcVersion = rpcBridge.getVersion();
                if (BaseModel.getStayAwake().getValue()) {
                    try {
                        PowerManager pm = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, service.getClass().getName());
                        wakeLock.acquire();
                    } catch (Throwable t) {
                        Log.printStackTrace(t);
                    }
                }
                setWakenAtTimeAlarm();
                installRpcRecordHook();
                NotificationUtil.start(service);
                CaptchaHook.setupHook(classLoader);
                Model.bootAllModel(classLoader);
                Status.load();
                TokenConfig.load();
                updateDay();
                BaseModel.initData();
                BaseModel.initRpcRequest();
                Toast.show("芝麻粒加载成功");
                init = true;
            }
            offline = false;
            if (exec) {
                execHandler();
            } else {
                // 只重载、不执行：但仍要排上下一跳——destroyHandler 已把旧排期清掉，
                // 不补一条的话任务链就断了，之后无论等多久都不会再跑
                execDelayedHandler(Math.max(BaseModel.getCheckInterval().getValue(), FALLBACK_INTERVAL));
                // 重载会重建通知（stop + start），而这一轮不跑任务、没人再刷新正文，
                // 状态栏会停在没有文字的空通知上；这里补一次，写上下次执行时间
                NotificationUtil.updateIdleText();
                Log.record("配置已重载#未触发执行，下一跳按间隔排队");
            }
            return true;
        } catch (Throwable th) {
            Log.err(TAG, "startHandler err:", th);
            Toast.show("芝麻粒加载失败");
            return false;
        } finally {
            // 无论成功/失败/未登录，都复位守卫，允许后续（如切号）再次触发重载
            initializing = false;
        }
    }

    /**
     * 安装抓包钩子（请求 + 返回各一个），开关为「抓包记录」。
     * <p>老接口没有 RpcBridgeExtension.rpc，故要求「使用新接口」开着；重复调用不叠加 hook。
     */
    private static synchronized void installRpcRecordHook() {
        if (!AppConfig.INSTANCE.getEnableDebugLog()) {
            return;
        }
        if (!AppConfig.INSTANCE.getNewRpc()) {
            Log.i(TAG, "抓包需要开启「使用新接口」，已跳过");
            return;
        }
        if (rpcRequestUnhook != null || rpcResponseUnhook != null) {
            return;
        }
        try {
            rpcRequestUnhook = XHelpers.findAndHookMethod("com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension", classLoader, "rpc", String.class, boolean.class, boolean.class, String.class, classLoader.loadClass(ClassUtil.JSON_OBJECT_NAME), String.class, classLoader.loadClass(ClassUtil.JSON_OBJECT_NAME), boolean.class, boolean.class, int.class, boolean.class, String.class, classLoader.loadClass("com.alibaba.ariver.app.api.App"), classLoader.loadClass("com.alibaba.ariver.app.api.Page"), classLoader.loadClass("com.alibaba.ariver.engine.api.bridge.model.ApiContext"), classLoader.loadClass("com.alibaba.ariver.engine.api.bridge.extension" + ".BridgeCallback"), new XC_MethodHook() {

                @SuppressLint("WakelockTimeout")
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object[] args = param.args;
                    Object object = args[15];
                    Object[] recordArray = new Object[4];
                    recordArray[0] = System.currentTimeMillis();
                    recordArray[1] = args[0];
                    recordArray[2] = args[4];
                    rpcHookMap.put(object, recordArray);
                }

                @SuppressLint("WakelockTimeout")
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object object = param.args[15];
                    Object[] recordArray = rpcHookMap.remove(object);
                    if (recordArray != null) {
                        Log.debug("记录\n时间: " + recordArray[0] + "\n方法: " + recordArray[1] + "\n参数: " + recordArray[2] + "\n数据: " + recordArray[3] + "\n");
                    } else {
                        Log.debug("删除记录ID: " + object.hashCode());
                    }
                }

            });
            Log.i(TAG, "hook record request successfully");
        } catch (Throwable t) {
            Log.err(TAG, "hook record request err:", t);
        }
        try {
            rpcResponseUnhook = XHelpers.findAndHookMethod("com.alibaba.ariver.engine.common.bridge.internal.DefaultBridgeCallback", classLoader, "sendJSONResponse", classLoader.loadClass(ClassUtil.JSON_OBJECT_NAME), new XC_MethodHook() {

                @SuppressLint("WakelockTimeout")
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object object = param.thisObject;
                    Object[] recordArray = rpcHookMap.get(object);
                    if (recordArray != null) {
                        recordArray[3] = String.valueOf(param.args[0]);
                    }
                }

            });
            Log.i(TAG, "hook record response successfully");
        } catch (Throwable t) {
            Log.err(TAG, "hook record response err:", t);
        }
    }

    /** 卸载抓包钩子；未安装时是空操作 */
    private static synchronized void uninstallRpcRecordHook() {
        if (rpcResponseUnhook != null) {
            try {
                rpcResponseUnhook.unhook();
            } catch (Throwable e) {
                Log.printStackTrace(e);
            }
            rpcResponseUnhook = null;
        }
        if (rpcRequestUnhook != null) {
            try {
                rpcRequestUnhook.unhook();
            } catch (Throwable e) {
                Log.printStackTrace(e);
            }
            rpcRequestUnhook = null;
        }
        // 卸载后 map 里的残留 entry 会继续强引用 BridgeCallback / 请求体 / 响应体，
        // 而此后已无人 remove，必须在这里清空
        if (!rpcHookMap.isEmpty()) {
            rpcHookMap.clear();
        }
    }

    private synchronized static void destroyHandler(Boolean force) {
        try {
            if (force) {
                if (service != null) {
                    stopHandler();
                    BaseModel.destroyData();
                    Status.unload();
                    NotificationUtil.stop();
                    RpcIntervalLimit.clearIntervalLimit();
                    ConfigV2.unload();
                    Model.destroyAllModel();
                    UserIdMap.unload();
                }
                uninstallRpcRecordHook();
                if (wakeLock != null) {
                    wakeLock.release();
                    wakeLock = null;
                }
                if (rpcBridge != null) {
                    rpcVersion = null;
                    rpcBridge.unload();
                    rpcBridge = null;
                }
            } else {
                ModelTask.stopAllTask();
            }
        } catch (Throwable th) {
            Log.err(TAG, "stopHandler err:", th);
        }
    }

    private static void execHandler() {
        try {
            NotificationUtil.setRunning();
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        startMainTask();
    }

    /** 起跳失败或取不到间隔时的兜底排期间隔（下限 1 分钟，避免自旋） */
    private static final long FALLBACK_INTERVAL = 60_000;

    /** 单轮 check 的超时窗口：既是 FutureTask.get 的上限，也是失败后补齐到整轮网格的基准 */
    private static final long CHECK_TIMEOUT_MS = 10_000;

    /** 起跳的公共实现：线程起不来等异常不能逃到宿主主线程（执行槽已由 BaseTask 归还） */
    private static void startMainTask() {
        try {
            if (Boolean.TRUE.equals(mainTask.startTask(false)) || tickScheduled) {
                return;
            }
            // 没跑起来（check 不通过等）又无人排期：兜底续排，否则链断
            execDelayedHandler(Math.max(BaseModel.getCheckInterval().getValue(), FALLBACK_INTERVAL));
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
    }

    /** 是否已有待发的下一跳：单链之下既用它兜底续排，又不覆盖本轮更早的显式排期（定时执行、reLogin 快重试） */
    private static volatile boolean tickScheduled = false;

    /** 待发 tick 的预定触发时刻，仅用于"排期被覆盖"的日志归因 */
    private static volatile long scheduledExecAt = 0;

    /** 延迟触发的一跳：提成常量才能在下一次排期前 removeCallbacks 掉旧链（重载/切号会重建 mainTask） */
    private static final Runnable MAIN_TICK = () -> {
        tickScheduled = false;
        scheduledExecAt = 0;
        try {
            NotificationUtil.setRunning();
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        startMainTask();
    };

    /** 只收能量窗口内的重收间隔：好友能量 7:00 起陆续成熟，5 分钟一轮足够兜住 */
    private static final long ENERGY_WINDOW_INTERVAL = 5 * 60 * 1000L;

    /**
     * 计算下一轮的排期延迟。平日按执行间隔；「只收能量时间」窗口内压到 5 分钟一轮，
     * 窗口即将开始、下一轮会整段跳过窗口时，提前排到窗口开始后 1 分钟。
     * <p>默认 06:55:30 那轮跑完后要按执行间隔（默认 50 分钟）等到 7:45，
     * 7:00~7:31 陆续成熟的好友能量中间没有任何一次收取机会，
     * 表现就是「早上那波完全不收能量」。
     */
    private static long getNextInterval(long checkInterval) {
        List<String> ranges = BaseModel.getEnergyTime().getValue();
        if (ranges == null || ranges.isEmpty() || checkInterval <= 0) {
            return checkInterval;
        }
        long now = System.currentTimeMillis();
        for (String range : ranges) {
            if (range == null || !range.contains("-")) {
                continue;
            }
            String[] parts = range.trim().split("-");
            if (parts.length != 2) {
                continue;
            }
            Calendar start = TimeUtil.getTodayCalendarByTimeStr(parts[0].trim());
            Calendar end = TimeUtil.getTodayCalendarByTimeStr(parts[1].trim());
            if (start == null || end == null) {
                continue;
            }
            long startMillis = start.getTimeInMillis();
            long endMillis = end.getTimeInMillis();
            if (endMillis <= startMillis) {
                // 跨天窗口（如 2359-0030）：结束时间落在次日
                endMillis += TimeUnit.DAYS.toMillis(1);
            }
            if (now >= startMillis && now < endMillis) {
                return Math.min(checkInterval, ENERGY_WINDOW_INTERVAL);
            }
            if (now < startMillis && startMillis - now < checkInterval) {
                return startMillis - now + 60_000L;
            }
        }
        return checkInterval;
    }

    private static void execDelayedHandler(long delayMillis) {
        // 调度时立即记录下次执行时间，所有任务完成时 updateLastExecText 会一并写入
        try {
            NotificationUtil.setNextExecTime(System.currentTimeMillis() + delayMillis);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        long newExecAt = System.currentTimeMillis() + delayMillis;
        if (tickScheduled) {
            // 后写覆盖前写（如 reLogin 覆盖"定时执行"）会让原排期静默失效，记一行便于归因
            Log.record("排期被覆盖：原定 " + TimeUtil.getTimeStr(scheduledExecAt) + " → 改为 " + TimeUtil.getTimeStr(newExecAt));
        }
        // 先摘掉上一条排期，否则每次重载/切号都会多留一条幽灵链，每个 interval 触发两次
        mainHandler.removeCallbacks(MAIN_TICK);
        mainHandler.postDelayed(MAIN_TICK, delayMillis);
        scheduledExecAt = newExecAt;
        tickScheduled = true;
    }

    private static void stopHandler() {
        mainTask.stopTask();
        ModelTask.stopAllTask();
        // 一并清掉待发 tick：否则重载后新周期第一轮会误判「已排期」而跳过续排，下一跳由上一周期的时刻决定
        mainHandler.removeCallbacks(MAIN_TICK);
        tickScheduled = false;
        scheduledExecAt = 0;
    }

    public static void updateDay() {
        Calendar nowCalendar = Calendar.getInstance();
        try {
            int nowYear = nowCalendar.get(Calendar.YEAR);
            int nowMonth = nowCalendar.get(Calendar.MONTH);
            int nowDay = nowCalendar.get(Calendar.DAY_OF_MONTH);
            if (dayCalendar.get(Calendar.YEAR) != nowYear || dayCalendar.get(Calendar.MONTH) != nowMonth || dayCalendar.get(Calendar.DAY_OF_MONTH) != nowDay) {
                dayCalendar = (Calendar) nowCalendar.clone();
                dayCalendar.set(Calendar.HOUR_OF_DAY, 0);
                dayCalendar.set(Calendar.MINUTE, 0);
                dayCalendar.set(Calendar.SECOND, 0);
                Log.record("日期更新为：" + nowYear + "-" + (nowMonth + 1) + "-" + nowDay);
                setWakenAtTimeAlarm();
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        try {
            Statistics.save(nowCalendar);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        try {
            Status.save(nowCalendar);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        try {
            FriendWatch.updateDay();
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    @SuppressLint({"ScheduleExactAlarm", "MissingPermission"})
    private static Boolean setAlarmTask(long triggerAtMillis, PendingIntent operation) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
            }
            return true;
        } catch (Throwable th) {
            Log.err(TAG, "setAlarmTask err:", th);
        }
        return false;
    }

    private static Boolean unsetAlarmTask(PendingIntent operation) {
        try {
            if (operation != null) {
                AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                alarmManager.cancel(operation);
            }
            return true;
        } catch (Throwable th) {
            Log.err(TAG, "unsetAlarmTask err:", th);
        }
        return false;
    }

    /**
     * 替换 RPC 实现（离线模式、诊断、单元测试注入替身）；传 null 回到默认的支付宝 RPC 桥。
     * <p>注入替身后，RpcCall 构造的请求体（method + data）原样交给替身，
     * 因此不连真机也能检查请求体。
     */
    public static void setRpcBridge(RpcBridge bridge) {
        rpcBridge = bridge;
    }

    public static String requestString(RpcEntity rpcEntity) {
        return rpcBridge.requestString(rpcEntity, 3, -1);
    }

    public static String requestString(RpcEntity rpcEntity, int tryCount, int retryInterval) {
        return rpcBridge.requestString(rpcEntity, tryCount, retryInterval);
    }

    public static String requestString(String method, String data) {
        return rpcBridge.requestString(method, data);
    }

    public static String requestString(String method, String data, String relation) {
        return rpcBridge.requestString(method, data, relation);
    }

    public static String requestString(String method, String data, int tryCount, int retryInterval) {
        return rpcBridge.requestString(method, data, tryCount, retryInterval);
    }

    public static String requestString(String method, String data, String relation, int tryCount, int retryInterval) {
        return rpcBridge.requestString(method, data, relation, tryCount, retryInterval);
    }

    public static RpcEntity requestObject(RpcEntity rpcEntity) {
        return rpcBridge.requestObject(rpcEntity, 3, -1);
    }

    public static RpcEntity requestObject(RpcEntity rpcEntity, int tryCount, int retryInterval) {
        return rpcBridge.requestObject(rpcEntity, tryCount, retryInterval);
    }

    public static RpcEntity requestObject(String method, String data) {
        return rpcBridge.requestObject(method, data);
    }

    public static RpcEntity requestObject(String method, String data, String relation) {
        return rpcBridge.requestObject(method, data, relation);
    }

    public static RpcEntity requestObject(String method, String data, int tryCount, int retryInterval) {
        return rpcBridge.requestObject(method, data, tryCount, retryInterval);
    }

    public static RpcEntity requestObject(String method, String data, String relation, int tryCount, int retryInterval) {
        return rpcBridge.requestObject(method, data, relation, tryCount, retryInterval);
    }

    /**
     * 在本进程内重载 hook，效果等同 restart 广播；同进程自救绕广播只会多依赖一次分发，
     * 而服务刚被销毁时那恰恰是最不可靠的一环。
     */
    private void restartSelf(boolean exec) {
        new Thread(() -> {
            try {
                initHandler(true, exec);
            } catch (Throwable th) {
                Log.printStackTrace(TAG, th);
            }
        }, "Sesame-Restart").start();
    }

    /** 定时唤醒的 Intent：action 独立于业务广播，并带上由令牌派生的 nonce */
    private static Intent alarmIntent() {
        Intent intent = new Intent(ACTION_ALARM_EXECUTE);
        String nonce = alarmNonce();
        if (nonce != null) {
            intent.putExtra("nonce", nonce);
        }
        return intent;
    }

    /**
     * 闹钟口令：由稳定令牌派生，令牌不变则口令不变，PendingIntent 里的快照始终有效。
     * <p>派生而不是存原文：这个 extra 会跟着 PendingIntent 落进系统闹钟，不该放能直接调 HTTP 的凭据。
     */
    private static String alarmNonce() {
        String token = httpToken;
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((token + ":sesame-alarm").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.err(TAG, "alarmNonce err:", t);
            return null;
        }
    }

    private static boolean verifyAlarmNonce(Intent intent) {
        String expected = alarmNonce();
        String given = intent == null ? null : intent.getStringExtra("nonce");
        if (expected == null || given == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                given.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * control 通道鉴权：令牌常数时间比较 + 要求定向投递。
     * <p>{@code getPackage()} 只表示「发送方是否定向」，挡不住伪造；真正拦人的是令牌比较，
     * 定向只决定令牌会不会外泄。
     */
    private static boolean verifyControl(Intent intent) {
        if (intent == null || !HOST_PACKAGE_NAME.equals(intent.getPackage())) {
            return false;
        }
        String token = httpToken;
        String given = intent.getStringExtra("token");
        if (token == null || given == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                given.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 各 op 的最小间隔：探活快，动作为慢，execute 最慢 */
    private static long controlMinInterval(String op) {
        if (op == null) {
            return CONTROL_MIN_INTERVAL_RESTART;
        }
        switch (op) {
            case "status":
                return CONTROL_MIN_INTERVAL_STATUS;
            case "execute":
                return CONTROL_MIN_INTERVAL_EXECUTE;
            case "openProfile":
                return CONTROL_MIN_INTERVAL_OPEN_PROFILE;
            case "restart":
            case "reloadConfig":
            default:
                return CONTROL_MIN_INTERVAL_RESTART;
        }
    }

    /** 频控：同一 op 两次请求至少间隔各自窗口，挡住令牌泄露后的批量刷；计时按 op 独立 */
    private static boolean controlRateLimited(String op) {
        String key = op == null ? "" : op;
        long min = controlMinInterval(key);
        java.util.concurrent.atomic.AtomicLong slot = lastControlAt.computeIfAbsent(
                key, k -> new java.util.concurrent.atomic.AtomicLong(0));
        long now = System.currentTimeMillis();
        while (true) {
            long last = slot.get();
            if (last != 0 && now - last < min) {
                return true;
            }
            if (slot.compareAndSet(last, now)) {
                return false;
            }
        }
    }

    /**
     * 受控通道统一入口：广播（{@code sesame.control}）与 HTTP 都从这里进，保证语义与副作用一致。
     * @return null 表示已受理，否则是拒绝原因
     */
    public static String dispatchControl(String op, String userId, String group, String data, boolean exec) {
        if (op == null || !CONTROL_OPS.contains(op)) {
            return "unknown op";
        }
        ApplicationHook hook = self;
        if (hook == null) {
            return "hook not loaded";
        }
        switch (op) {
            case "restart":
                // 带 userId 时只在切到该账号后重载，避免旧账号的保存把新账号的进程带跑一遍
                if (StringUtil.isEmpty(userId) || Objects.equals(UserIdMap.getCurrentUid(), userId)) {
                    hook.restartSelf(exec);
                }
                return null;
            case "reloadConfig":
                reloadAppConfig();
                return null;
            case "execute":
                // 与 restart 同理：带 userId 时只在切到该账号后才跑，避免切号后旧账号的请求跑新账号任务
                if (StringUtil.isEmpty(userId) || Objects.equals(UserIdMap.getCurrentUid(), userId)) {
                    hook.executeGroup(group);
                }
                return null;
            case "status":
                hook.replyStatus();
                return null;
            case "openProfile":
                openFriendProfile(context, userId);
                return null;
            case "forestQuery":
                // group 承载 method：只接受上面那张表里的值，越界一律拒绝
                if (group == null || !FOREST_QUERY_METHODS.contains(group)) {
                    return "unknown method";
                }
                TestRpc.start("antForest", group, null);
                return null;
            case "walkPath":
                if (group == null || !WALK_PATH_METHODS.contains(group)) {
                    return "unknown method";
                }
                if ("clearCustomWalkPathIdQueue".equals(group)) {
                    TestRpc.start("clearCustomWalkPathIdQueue", null, "setCustomWalkPathIdQueue");
                } else if ("addCustomWalkPathIdQueue".equals(group)) {
                    if (StringUtil.isEmpty(data)) {
                        return "missing data";
                    }
                    TestRpc.start("addCustomWalkPathIdQueue", data, "setCustomWalkPathIdQueue");
                } else {
                    if (StringUtil.isEmpty(data)) {
                        return "missing data";
                    }
                    TestRpc.start("addCustomWalkPathId", data, "setCustomWalkPathIdList");
                }
                return null;
            default:
                return "unknown op";
        }
    }

    /** UI 侧改完共享配置后通知本进程重读：只重读 AppConfig，不重挂 Model hook */
    private static void reloadAppConfig() {
        try {
            AppConfig.load();
            // 「抓包记录」开关即时装卸钩子（原来只在 initHandler 判定，改了必须重启进程才生效）
            if (AppConfig.INSTANCE.getEnableDebugLog()) {
                installRpcRecordHook();
                MiniProgramHook.install(classLoader, processName);
                // 调试模式一开就把令牌镜像到共享目录，PC 侧立刻可读，不必等进程重启或下次解析令牌
                String mirrorToken = resolveHttpToken();
                if (!mirrorToken.isEmpty()) {
                    syncLegacyHttpToken(mirrorToken);
                }
            } else {
                uninstallRpcRecordHook();
                MiniProgramHook.uninstall();
            }
            // 令牌副本跟随抓包开关：关掉调试就立刻删副本，不留到进程重启
            if (!Boolean.TRUE.equals(AppConfig.INSTANCE.getEnableDebugLog())) {
                removeMirrorTokenFile();
            }
            Log.i(TAG, "reload AppConfig from UI");
        } catch (Throwable th) {
            Log.err(TAG, "sesame reloadConfig err:", th);
        }
    }

    /** 跑一轮任务；执行前必须重载配置，否则「刚开启的模块点了执行却不跑」 */
    private void executeGroup(String groupCode) {
        new Thread(() -> {
            try {
                ConfigV2.load(UserIdMap.getCurrentUid());
                if (StringUtil.isEmpty(groupCode)) {
                    initHandler(false);
                } else if (ModelGroup.BASE == ModelGroup.getByCode(groupCode)) {
                    ModelTask.stopAllTask();
                    // 同分组分支：先打提示，避免被森林的同步执行挡在后面
                    Log.record("开始执行全部任务");
                    ModelTask.startAllTask(false);
                } else {
                    ModelTask.stopAllTask();
                    // 先打"开始"再启动：森林 isSync=true 会同步阻塞到跑完，放在后面会晚很久才出现
                    Log.record("开始执行分组【" + ModelGroup.getName(groupCode) + "】任务");
                    int count = ModelTask.startGroupTask(groupCode);
                    if (count == 0) {
                        Log.record("分组【" + ModelGroup.getName(groupCode) + "】无可执行任务");
                    }
                }
            } catch (Throwable th) {
                Log.printStackTrace(TAG, th);
            }
        }, "Sesame-Execute").start();
    }

    /** 回一条「宿主在线」给模块 App：必须定向，否则第三方伪造也能让 App 误判宿主活着 */
    private void replyStatus() {
        sendStatusBroadcast(context);
    }

    /**
     * 激活/探活的统一回包：定向到模块包 + 带令牌，App 侧据此确认这是宿主发的而不是第三方伪造的。
     * <p>静态是因为 onModuleLoaded 早于 Service.onCreate，那时还拿不到当前实例上的 {@code context}。
     */
    private static void sendStatusBroadcast(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            // 令牌可能尚未解析（Service.onCreate 早于 runInit），先取一次，否则回包缺令牌会被 App 丢掉
            if (httpToken == null) {
                resolveHttpToken();
            }
            String token = httpToken;
            // 空令牌的回包会被 App 当成伪造消息丢掉，宁可不发也要留一条日志
            if (token == null || token.isEmpty()) {
                Log.record("状态回包未发送#令牌不可用，App 会显示未激活");
                return;
            }
            Intent reply = new Intent(ACTION_STATUS_REPLY);
            reply.setPackage(MODULE_PACKAGE_NAME);
            reply.putExtra("token", token);
            ctx.sendBroadcast(reply);
        } catch (Throwable th) {
            Log.err(TAG, "sesame sendBroadcast status err:", th);
        }
    }

    private static int getPendingIntentFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            return PendingIntent.FLAG_UPDATE_CURRENT;
        }
    }

    public static Object getMicroApplicationContext() {
        if (microApplicationContextObject == null) {
            return microApplicationContextObject = XHelpers.callMethod(XHelpers.callStaticMethod(XHelpers.findClass("com.alipay.mobile.framework.AlipayApplication", classLoader), "getInstance"), "getMicroApplicationContext");
        }
        return microApplicationContextObject;
    }

    public static Object getServiceObject(String service) {
        try {
            return XHelpers.callMethod(getMicroApplicationContext(), "findServiceByInterface", service);
        } catch (Throwable th) {
            Log.err(TAG, "getServiceObject err", th);
        }
        return null;
    }

    public static Object getUserObject() {
        try {
            return XHelpers.callMethod(getServiceObject(XHelpers.findClass("com.alipay.mobile.personalbase.service.SocialSdkContactService", classLoader).getName()), "getMyAccountInfoModelByLocal");
        } catch (Throwable th) {
            Log.err(TAG, "getUserObject err", th);
        }
        return null;
    }

    public static String getUserId() {
        try {
            Object userObject = getUserObject();
            if (userObject != null) {
                return (String) XHelpers.getObjectField(userObject, "userId");
            }
        } catch (Throwable th) {
            Log.err(TAG, "getUserId err", th);
        }
        return null;
    }

    public static void reLogin() {
        // mainHandler / context 只在 Service onCreate hook 内赋值；先取局部快照，避免读到半初始化状态或 NPE
        Handler handler = mainHandler;
        Context ctx = context;
        if (handler == null || ctx == null) {
            Log.record("跳过重登录：mainHandler/context 尚未就绪");
            return;
        }
        handler.post(() -> {
            if (reLoginCount.get() < 5) {
                execDelayedHandler(reLoginCount.getAndIncrement() * 5000L);
            } else {
                execDelayedHandler(Math.max(BaseModel.getCheckInterval().getValue(), 180_000));
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setClassName(ClassUtil.PACKAGE_NAME, ClassUtil.CURRENT_USING_ACTIVITY);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            offline = true;
            ctx.startActivity(intent);
        });
    }

    private class AlipayBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (action == null) {
                return;
            }
            switch (action) {
                case ACTION_PAIR_REQUEST:
                    handlePairRequest(context, intent);
                    return;
                case ACTION_ALARM_EXECUTE:
                    // 系统闹钟由 PendingIntent 代发，拿不到发送方 uid，只能靠 extra 里的 nonce 认
                    if (!verifyAlarmNonce(intent)) {
                        Log.record("定时唤醒校验失败，已忽略");
                        return;
                    }
                    executeGroup(null);
                    return;
                case ACTION_CONTROL:
                    // uid 白名单在 API 34 以下取不到发送方，只能当补充；令牌才是这条通道的门
                    if (!isTrustedBroadcastSender(context, this)) {
                        Log.record("受控通道来源不可信，已忽略");
                        return;
                    }
                    if (!verifyControl(intent)) {
                        Log.record("受控通道令牌校验失败，已忽略");
                        return;
                    }
                    String op = intent.getStringExtra("op");
                    // 每个 op 各有窗口（status 2s / restart 10s / execute 60s / openProfile 5s），
                    // 探活不会挤掉动作，动作之间也互不影响
                    if (controlRateLimited(op)) {
                        Log.record("受控通道触发过于频繁，已忽略#" + op);
                        return;
                    }
                    String rejected = dispatchControl(op,
                            intent.getStringExtra("userId"),
                            intent.getStringExtra("group"),
                            intent.getStringExtra("data"),
                            intent.getBooleanExtra("exec", false));
                    if (rejected != null) {
                        Log.record("受控通道拒绝#" + op + ":" + rejected);
                    }
                    return;
                default:
                    Log.i("sesame broadcast ignored action:" + action);
            }
        }
    }

    /**
     * 配对：App 发 challenge，宿主把当前令牌定向回给 App 包。
     * <p>故意不校验令牌——App 还没有令牌，这正是它拿令牌的途径。安全性来自回包定向
     * 与令牌只在宿主私有目录，刷屏由 60 秒频控兜住。
     */
    private void handlePairRequest(Context ctx, Intent intent) {
        try {
            long now = System.currentTimeMillis();
            long last = lastPairAt.get();
            if (last != 0 && now - last < PAIR_MIN_INTERVAL) {
                return;
            }
            lastPairAt.set(now);
            String challenge = intent.getStringExtra("challenge");
            if (StringUtil.isEmpty(challenge)) {
                return;
            }
            String token = httpToken;
            if (token == null) {
                token = resolveHttpToken();
            }
            if (StringUtil.isEmpty(token)) {
                Log.record("配对失败：本进程尚无可用令牌");
                return;
            }
            Intent result = new Intent(ACTION_PAIR_RESULT);
            result.setPackage(MODULE_PACKAGE_NAME);
            result.putExtra("token", token);
            result.putExtra("challenge", challenge);
            ctx.sendBroadcast(result);
            Log.record("已响应对口请求");
        } catch (Throwable th) {
            Log.err(TAG, "handlePairRequest err:", th);
        }
    }

    /**
     * 校验广播发送方是否可信。Receiver 必须导出（模块 App 与支付宝不同 UID），
     * 所以「任意应用都能触发 restart」只能在这里按发送方 uid 拦。
     * <p>白名单：本进程、模块 App、adb shell。uid 取自 Android 14+ 的 getSentFromUid()，
     * 低版本与 {@code Process.INVALID_UID}（系统代发，如闹钟触发的定时执行）一律放行，否则定时任务失效。
     * @return true 表示可信
     */
    private static boolean isTrustedBroadcastSender(Context context, BroadcastReceiver receiver) {
        try {
            // 发送方 uid 只有 Android 14+ 的 getSentFromUid 能取到；低版本无从判定，放行保持原行为
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return true;
            }
            int uid = receiver.getSentFromUid();
            if (uid == Process.myUid() || uid == SHELL_UID) {
                return true;
            }
            if (uid == Process.INVALID_UID) {
                // 系统代发的广播（如闹钟到点触发 PendingIntent 的定时执行）取不到来源；
                // 这里放行以免定时任务失效，是本校验唯一的松口
                Log.record("广播来源无法判定，按放行处理");
                return true;
            }
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String pkg : packages) {
                    if (MODULE_PACKAGE_NAME.equals(pkg)) {
                        return true;
                    }
                }
            }
            Log.record("广播发送方不可信，已忽略#uid=" + uid);
            return false;
        } catch (Throwable t) {
            // 校验本身出错时放行：宁可退回改动前的行为，也不让 restart/reloadConfig 这类正常
            // 流程因校验异常而失效（出错原因已记日志，便于排查）
            Log.err(TAG, "校验广播发送方失败:", t);
            return true;
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerBroadcastReceiver(Context context) {
        try {
            if (broadcastReceiverRegistered && broadcastReceiver != null) {
                try {
                    context.unregisterReceiver(broadcastReceiver);
                    broadcastReceiverRegistered = false;
                } catch (Throwable t) {
                    Log.err(TAG, "hook unregisterBroadcastReceiver err:", t);
                }
            }

            // 只留三条：配对（无令牌可用，回包定向）、闹钟（nonce 校验）、受控通道（令牌 + 定向）。
            // 原来的 restart/execute/reLogin/status/rpctest/reloadConfig/openProfile 七条全部并入
            // control，任意应用注册同名 action 也拿不到令牌，触发不了任何动作。
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_PAIR_REQUEST);
            intentFilter.addAction(ACTION_ALARM_EXECUTE);
            intentFilter.addAction(ACTION_CONTROL);

            broadcastReceiver = new AlipayBroadcastReceiver();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(broadcastReceiver, intentFilter);
            }
            broadcastReceiverRegistered = true;
        } catch (Throwable th) {
            Log.err(TAG, "hook registerBroadcastReceiver err:", th);
        }
    }

    /**
     * 在宿主（支付宝）进程内打开指定好友的个人主页。
     * <p>必须由本进程发起：模块 App 发的 {@code alipays://} 调用方不是支付宝自身，
     * 会被 {@code appSchemeCheck} 判为不可信而拦掉。
     * @param userId 好友的支付宝 userId
     */
    private static void openFriendProfile(Context context, String userId) {
        // 兜底广播进来时标记可能还留着（resume 那条路没走通），先清掉：
        // 否则下一次任意页面 resume 会把同一个好友再弹一次。
        new java.io.File(FileUtil.MAIN_DIRECTORY_FILE, PENDING_PROFILE_FILE).delete();
        if (StringUtil.isEmpty(userId)) {
            Log.record("打开好友主页失败#缺少userId");
            return;
        }
        // userId 直接拼进 alipays:// 的查询串，放任任意字符等于把拼串权交给调用方
        if (!USER_ID_PATTERN.matcher(userId).matches()) {
            Log.record("打开好友主页失败#userId 格式非法");
            return;
        }
        // 模块 App 冷启动时会补发几次广播，这里对同一好友做去重，避免重复弹页
        long now = System.currentTimeMillis();
        if (userId.equals(lastProfileUserId) && now - lastProfileTime < 8000) {
            return;
        }
        lastProfileUserId = userId;
        lastProfileTime = now;
        // 标记本次跳转：紧随其后创建的容器页就是好友页，需要接管并关掉上一个好友页
        profileOpenExpectAt = now;
        // 好友主页是 appId=20002167 的小程序页 profilePage。
        // 走 alipays:// scheme 会多起 SchemeLauncherActivity / LauncherActivity 两个空窗口
        // （小窗、分屏下每个 Activity 都是独立窗口），故优先在进程内直接启动。
        if (startProfileInProcess(userId)) {
            // 启动入口没有回执：超时未等到容器页就换下一个入口，都失败才退回 scheme
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(() -> {
                if (profileOpenExpectAt == 0 || !userId.equals(lastProfileUserId)) {
                    return;
                }
                if (startProfileEventInProcess(userId)) {
                    handler.postDelayed(() -> {
                        if (profileOpenExpectAt != 0 && userId.equals(lastProfileUserId)) {
                            startProfileScheme(context, userId);
                        }
                    }, 900);
                    return;
                }
                startProfileScheme(context, userId);
            }, 800);
            return;
        }
        startProfileScheme(context, userId);
    }

    /** 走 scheme 跳好友主页，作为进程内启动失败时的退路 */
    private static boolean startProfileScheme(Context context, String userId) {
        try {
            context.startActivity(buildProfileSchemeIntent(userId));
            return true;
        } catch (Throwable th) {
            profileOpenExpectAt = 0;
            Log.err(TAG, "openFriendProfile fallback err#userId=" + userId, th);
            Log.record("打开好友主页失败#userId=" + userId);
            return false;
        }
    }

    private static String buildProfileUri(String userId) {
        return "alipays://platformapi/startApp"
                + "?appId=" + PROFILE_APP_ID
                + "&pageName=profilePage"
                + "&userId=" + userId;
    }

    /** 走 scheme 的跳转 intent，作为进程内 startApp 失败时的退路 */
    private static Intent buildProfileSchemeIntent(String userId) {
        Intent it = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(buildProfileUri(userId)));
        // NEW_TASK + 调用方上下文：支付宝此刻已经在前台，这个 flags 只会复用现有任务
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return it;
    }

    /**
     * 在支付宝进程内直接启动好友主页小程序，返回是否成功发起。
     * <p>
     * 签名是五参数 {@code startApp(from, appId, Bundle params, Bundle extra, activity)}，
     * 启动参数全在第三个 Bundle 里；当成 JSON 字符串传会打开不相干的页面。
     */
    private static boolean startProfileInProcess(String userId) {
        return invokeStartMethod("startApp", userId);
    }


    /** 备选入口：scheme 路径最后拉起容器的是 startAppEvent，签名与参数同上 */
    private static boolean startProfileEventInProcess(String userId) {
        return invokeStartMethod("startAppEvent", userId);
    }

    /**
     * 反射调用框架启动入口。只认「五参数 + 第 2 个是 String(appId) + 第 3 个是 Bundle」的重载：
     * 同名重载很多，只按名字匹配会打错。用 {@code invoke} 而非 {@code XHelpers.callMethod}，
     * 参数为 null 时也能按声明类型匹配。
     */
    private static boolean invokeStartMethod(String methodName, String userId) {
        try {
            Object microApp = getMicroApplicationContext();
            if (microApp == null) {
                return false;
            }
            android.os.Bundle params = buildProfileStartBundle(userId);
            for (Class<?> clazz = microApp.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                    if (!methodName.equals(method.getName()) || method.getParameterCount() != 5) {
                        continue;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (types[2] != android.os.Bundle.class || !types[1].isAssignableFrom(String.class)) {
                        continue;
                    }
                    try {
                        method.setAccessible(true);
                        method.invoke(microApp, null, PROFILE_APP_ID, params, null, null);
                        return true;
                    } catch (Throwable th) {
                        Log.err(TAG, methodName + " invoke err#userId=" + userId, th);
                        return false;
                    }
                }
            }
            Log.i(TAG, "no usable " + methodName + " method on " + microApp.getClass().getName());
            return false;
        } catch (Throwable th) {
            Log.err(TAG, "invokeStartMethod err#" + methodName, th);
            return false;
        }
    }

    /** 好友主页的启动参数，key 与 scheme 路径里的 Bundle 一致 */
    private static android.os.Bundle buildProfileStartBundle(String userId) {
        android.os.Bundle params = new android.os.Bundle();
        params.putString("appId", PROFILE_APP_ID);
        params.putString("pageName", "profilePage");
        params.putString("userId", userId);
        params.putBoolean("recordGestureTime", true);
        params.putBoolean("startFromExternal", false);
        params.putString("ap_framework_sceneId", PROFILE_APP_ID);
        params.putString("ap_framework_scheme", buildProfileUri(userId));
        params.putString("bizLogParam",
                "{\"coldStartup\":false,\"fromScheme\":true,\"showLoginAndGesture\":false}");
        return params;
    }

    /**
     * 接管好友页容器：记下当前实例，并把上一次的好友页关掉。
     * <p>
     * 不关的话多个 {@code CSGAPushActivity} 会叠在同一 task 里，从第二个好友页返回会回到上一个好友。
     */
    private static void handleProfileContainer(Activity activity) {
        if (!isFriendProfileContainer(activity)) {
            return;
        }
        Activity last = lastProfileActivity.get();
        if (last != null && last != activity && !last.isFinishing() && !last.isDestroyed()) {
            last.finish();
        }
        lastProfileActivity = new WeakReference<>(activity);
    }

    /**
     * 判断容器页是否是好友主页：intent 里带好友主页小程序参数，或者是本次跳转后紧接着创建的容器。
     */
    private static boolean isFriendProfileContainer(Activity activity) {
        boolean expect = profileOpenExpectAt > 0
                && System.currentTimeMillis() - profileOpenExpectAt < PROFILE_CONTAINER_EXPECT_WINDOW;
        if (expect) {
            profileOpenExpectAt = 0;
        }
        boolean matched = false;
        try {
            Intent intent = activity.getIntent();
            if (intent != null) {
                String raw = intent.toString() + " " + intent.getDataString();
                matched = raw.contains(PROFILE_APP_ID) || raw.contains("profilePage");
            }
        } catch (Throwable ignored) {
        }
        return matched || expect;
    }

    /**
     * 消费「待打开好友」标记：模块 App 点击好友行时先落盘再拉起支付宝，宿主冷启动后
     * 一进 {@code LauncherActivity.onResume} 就能直接跳主页，省掉「先停在首页」的闪烁。
     * <p>标记只消费一次，超过 {@link #PENDING_PROFILE_TTL} 视为过期。
     */
    private static void checkPendingProfile(Context context) {
        try {
            java.io.File file = new java.io.File(FileUtil.MAIN_DIRECTORY_FILE, PENDING_PROFILE_FILE);
            if (!file.exists()) {
                return;
            }
            String text = FileUtil.readFromFile(file);
            // 先删再跳：即使下面跳转抛异常也不会反复触发
            file.delete();
            if (StringUtil.isEmpty(text)) {
                return;
            }
            String[] parts = text.trim().split("\\s+");
            String userId = parts[0];
            long ts = 0;
            if (parts.length > 1) {
                try {
                    ts = Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            if (ts > 0 && System.currentTimeMillis() - ts > PENDING_PROFILE_TTL) {
                return;
            }
            openFriendProfile(context, userId);
        } catch (Throwable th) {
            Log.err(TAG, "checkPendingProfile err:", th);
        }
    }

    // 滑块验证hook注册
    private void initSimplePageManager() {
        if (shouldEnableSimplePageManager()) {
            enableWindowMonitoring(classLoader);
            addHandler("com.alipay.mobile.nebulax.xriver.activity.XRiverActivity", new Captcha1Handler());
            addHandler("com.eg.android.AlipayGphone.AlipayLogin", new Captcha2Handler());
        }
    }

    /** @return 支付宝版本低于等于 10.6.58.99999 时需启用 SimplePageManager */
    private boolean shouldEnableSimplePageManager() {
        if (alipayVersion.toString().isEmpty()) {
            return false;
        }

        AlipayVersion maxSupported = new AlipayVersion("10.6.58.99999");
        if (alipayVersion.compareTo(maxSupported) > 0) {
            // 只有在不支持时才打印警告
            Log.record("目标应用版本[" + alipayVersion.getVersionString() + "]高于[10.6.58.99999]不支持自动过滑块验证");
            return false;
        }

        return true;
    }

    // ----------------------------------------------------------------
    // 宿主前后台询问的回答
    // ----------------------------------------------------------------

    /** 是否已提示过"如实回答"（该事件会反复出现，只留一次痕） */
    private static volatile boolean honestAnswerLogged;
    /** 是否已提示过"取真实状态失败"（失败原因通常固定，避免刷屏） */
    private static volatile boolean originalCallFailedLogged;

    /**
     * 回答宿主的 {@code isInBackground()}：默认仍按原行为谎报 false（"不在后台"），
     * 只有当宿主**真的**在后台、且询问方是风控/滑块链路时如实回答 true
     * ——否则宿主会在后台尝试展示滑块界面，界面出不来、验证流程一直等用户滑动，切回支付宝即卡死。
     */
    private static boolean answerInBackgroundQuestion(XC_MethodHook.MethodHookParam param) {
        Boolean reallyInBackground = originalBoolean(param, "isInBackground");
        if (reallyInBackground != null && reallyInBackground && isRiskControlCaller()) {
            noteHonestAnswerForRiskControl();
            return true;
        }
        return false;
    }

    /**
     * 回答宿主的 {@code isAtFrontDesk()}：默认仍按原行为谎报 true（"在前台"），
     * 只有当宿主**真的**不在前台、且询问方是风控/滑块链路时如实回答 false。
     */
    private static boolean answerAtFrontDeskQuestion(XC_MethodHook.MethodHookParam param) {
        Boolean atFrontDesk = originalBoolean(param, "isAtFrontDesk");
        if (atFrontDesk != null && !atFrontDesk && isRiskControlCaller()) {
            noteHonestAnswerForRiskControl();
            return false;
        }
        return true;
    }

    /**
     * 调用原方法取真实返回值。
     *
     * @return 真值；取不到（异常 / 非布尔）时返回 null，调用方按原行为谎报
     */
    private static Boolean originalBoolean(XC_MethodHook.MethodHookParam param, String what) {
        try {
            Object result = param.callOriginal();
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Throwable t) {
            if (!originalCallFailedLogged) {
                originalCallFailedLogged = true;
                Log.err(TAG, "取 " + what + " 真实前后台状态失败，继续按原行为谎报:", t);
            }
            return null;
        }
    }

    /**
     * 询问方是否来自风控/滑块链路（{@code com.alipay.rdssecuritysdk} 等）。
     * <p>只在**真实后台**时才会走到这里，因此不影响前台热路径；只看最上面若干帧，够用且便宜。
     */
    private static boolean isRiskControlCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int limit = Math.min(stack.length, 12);
        for (int i = 3; i < limit; i++) {
            String className = stack[i].getClassName();
            if (className.startsWith("com.alipay.rdssecuritysdk")
                    || className.contains("captcha") || className.contains("Captcha")) {
                return true;
            }
        }
        return false;
    }

    private static void noteHonestAnswerForRiskControl() {
        if (honestAnswerLogged) {
            return;
        }
        honestAnswerLogged = true;
        // 运行事件（非成功记录），写入运行日志便于核对；运行日志受「查看运行日志」开关控制
        Log.i("风控/滑块链路在后台询问前后台状态：已如实回答，避免在后台创建滑块界面（界面出不来、切回支付宝卡死）");
    }
}
