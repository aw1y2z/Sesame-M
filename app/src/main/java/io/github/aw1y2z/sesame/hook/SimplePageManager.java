package io.github.aw1y2z.sesame.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.aw1y2z.sesame.data.AppConfig;
import io.github.aw1y2z.sesame.util.compat.XC_MethodHook;

/**
 * A simplified PageManager - only keeps Activity monitoring and Dialog tracking.
 */
@SuppressLint("StaticFieldLeak")
public class SimplePageManager {
    private static final String TAG = "SimplePageManager";
    
    private static WeakReference<Context> mContextRef;
    private static ClassLoader mClassLoader;
    /** 顶层 Activity 只持弱引用：静态强引用会把已销毁的 Activity 一直留到进程结束 */
    private static WeakReference<Activity> topActivityRef;
    
    private static final ConcurrentHashMap<String, ActivityFocusHandler> activityFocusHandlerMap = new ConcurrentHashMap<>();
    
    // 主线程 Handler（复用项目原生调度方式，替代协程 Dispatchers.Main）
    public static final Handler handler = new Handler(Looper.getMainLooper());
    
    private static int taskDuration = 500;
    // 用 AtomicBoolean 而不是 volatile boolean：原先"判断 + 置位"是两步，多线程下会同时通过
    private static final AtomicBoolean hasPendingActivityTask = new AtomicBoolean(false);
    private static boolean disable = false;
    
    /** 处理链的最大尝试次数（0 起算，与历史行为一致：共 11 次） */
    private static final int MAX_ACTIVITY_ATTEMPT = 10;
    /**
     * 验证码处理工作线程。
     * <p>处理过程要遍历视图树、还要"等界面稳定"地 sleep，**绝不能放在主线程**：原实现跑在主线程，
     * 一次尝试就阻塞 1s 以上，叠加十来次重试足以把宿主界面拖到无响应（后台时滑块解不掉、必然跑满重试，
     * 就是最严重的场景）。单线程即可——验证码处理本就该串行，也顺带起到限流作用。
     */
    private static final ExecutorService captchaWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Sesame-Captcha");
        thread.setDaemon(true);
        return thread;
    });
    /** 处理链跑完之前又有新触发（新弹窗 / 新 Activity）：记下来，链结束后补跑一次，保证不漏 */
    private static final AtomicBoolean rerunRequested = new AtomicBoolean(false);
    
    // 对话框列表会被 hook 线程（写）与模块线程（读）同时访问：
    // CopyOnWriteArrayList 保证遍历期间不会被插入/删除打断（原先 ArrayList 会抛 ConcurrentModificationException）
    private static final List<WeakReference<Dialog>> dialogs = new CopyOnWriteArrayList<>();
    private static boolean windowMonitorEnabled = false;
    
    /**
     * Activity焦点处理器接口
     */
    public interface ActivityFocusHandler {
        boolean handleActivity(Activity activity, io.github.aw1y2z.sesame.hook.SimpleViewImage root);
    }
    
    static {
        enablePageMonitor();
    }
    
    public static Context getContext() {
        return mContextRef != null ? mContextRef.get() : null;
    }
    
    public static ClassLoader getClassLoader() {
        return mClassLoader;
    }
    
    public static Activity getTopActivity() {
        return topActivityRef != null ? topActivityRef.get() : null;
    }
    
    public static void setTaskDuration(int duration) {
        taskDuration = duration;
    }
    
    public static void setDisable(boolean disabled) {
        disable = disabled;
    }
    
    public static void addHandler(String activityClassName, ActivityFocusHandler handler) {
        activityFocusHandlerMap.put(activityClassName, handler);
    }
    
    public static void removeHandler(String activityClassName) {
        activityFocusHandlerMap.remove(activityClassName);
    }
    
    public static List<WeakReference<Dialog>> getDialogs() {
        return dialogs;
    }
    
    public static void enableWindowMonitoring(ClassLoader classLoader) {
        if (classLoader != null) {
            mClassLoader = classLoader;
        }
        Log.i(
                TAG,
                "启用窗口监控被调用，窗口监控已启用: " + windowMonitorEnabled + ", 类加载器: " + (mClassLoader != null ? mClassLoader.getClass().getName() : "null")
        );
        if (!windowMonitorEnabled) {
            enableWindowMonitor();
            windowMonitorEnabled = true;
        }
    }
    
    /**
     * 重载方法：兼容无参调用
     */
    public static void enableWindowMonitoring() {
        enableWindowMonitoring(null);
    }
    
    /**
     * 尝试在对话框中查找视图
     */
    @SuppressLint("UseCompatLoadingForDrawables")
    public static io.github.aw1y2z.sesame.hook.SimpleViewImage tryGetTopView(String xpath) {
        // 清理空引用：CopyOnWriteArrayList 的 removeIf 内部按快照处理，不会与其它线程的写入冲突
        dialogs.removeIf(ref -> ref.get() == null);
        
        for (WeakReference<Dialog> dialogWeakReference : dialogs) {
            Dialog dialog = dialogWeakReference.get();
            if (dialog == null || !dialog.isShowing()) {
                continue;
            }
            View decorView = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
            if (decorView == null) {
                continue;
            }
            Log.d(TAG, "  - 对话框: " + dialog.getClass().getName() + ", 正在显示: " + dialog.isShowing());
            // 整棵树只在「抓包记录」打开时才遍历：一次验证码要反复走 tryGetTopView，
            // 无条件递归会把每个节点的文本重复刷进日志
            if (AppConfig.INSTANCE.getEnableDebugLog()) {
                debugPrintAllTextViews(decorView, 0);
            }
            
            io.github.aw1y2z.sesame.hook.SimpleViewImage viewImage = new io.github.aw1y2z.sesame.hook.SimpleViewImage(decorView);
            ArrayList<io.github.aw1y2z.sesame.hook.SimpleViewImage> results = (ArrayList<SimpleViewImage>) SimpleXpathParser.evaluate(viewImage, xpath);
            if (!results.isEmpty()) {
                return results.get(0);
            }
        }
        return null;
    }
    
    /**
     * 打印所有 TextView 的文本内容；只应由 {@link #tryGetTopView} 在「抓包记录」打开时调用
     */
    private static void debugPrintAllTextViews(View view, int depth) {
        // 不用 String.repeat：它是 API 33 才有的平台方法，minSdk 26 且未开 desugar，
        // Android 8–12 上会抛 NoSuchMethodError，把整条抓包记录链路打断
        StringBuilder indentBuilder = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) {
            indentBuilder.append("  ");
        }
        String indent = indentBuilder.toString();
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            String text = textView.getText() != null ? textView.getText().toString() : "";
            String contentDesc = textView.getContentDescription() != null ? textView.getContentDescription().toString() : "";
            
            if (!text.isEmpty() || !contentDesc.isEmpty()) {
                Log.d(
                        TAG,
                        indent + "文本视图[" + view.getClass().getSimpleName() + "] 文本='" + text + "' 内容描述='" + contentDesc + "'"
                );
            }
        }
        
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                debugPrintAllTextViews(viewGroup.getChildAt(i), depth + 1);
            }
        }
    }
    
    /**
     * 启用 Activity 监控
     */
    private static void enablePageMonitor() {
        try {
            CompatHelpers.findAndHookMethod(
                    Application.class,
                    "dispatchActivityResumed",
                    Activity.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.args[0];
                            topActivityRef = new WeakReference<>(activity);
                            if (mContextRef == null || mContextRef.get() == null) {
                                mContextRef = new WeakReference<>(activity.getApplicationContext());
                            }
                            mClassLoader = activity.getClassLoader();
                            triggerActivity();
                        }
                    }
            );
        } catch (Throwable e) {
            Log.e(TAG, "挂钩 Activity->dispatchActivityResumed 错误: ", e);
        }
    }
    
    /**
     * 如果对话框不存在则添加到监控列表
     */
    private static void addDialogIfNotExists(Dialog dialog, String source) {
        boolean exists = false;
        for (WeakReference<Dialog> ref : dialogs) {
            if (ref.get() == dialog) {
                exists = true;
                break;
            }
        }
        
        if (!exists) {
            dialogs.add(new WeakReference<>(dialog));
            Log.d(TAG, "对话框已从 " + source + " 添加，总数: " + dialogs.size());
            triggerDialogProcessing();
        } else {
            Log.d(TAG, "对话框从 " + source + " 已存在于列表中");
        }
    }
    
    /**
     * 挂钩对话框构造函数
     */
    private static void hookDialogConstructor(Class<?>... parameterTypes) {
        StringBuilder paramStr = new StringBuilder();
        for (Class<?> clazz : parameterTypes) {
            if (paramStr.length() > 0) {
                paramStr.append(",");
            }
            paramStr.append(clazz.getSimpleName());
        }
        
        try {
            CompatHelpers.findAndHookConstructor(
                    "android.app.Dialog",
                    getClassLoader(),
                    parameterTypes,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Dialog dialog = (Dialog) param.thisObject;
                            addDialogIfNotExists(dialog, "构造函数(" + paramStr + ")");
                        }
                    }
            );
        } catch (Throwable e) {
            Log.e(TAG, "挂钩对话框构造函数(" + paramStr + ") 错误: ", e);
        }
    }
    
    /**
     * 启用对话框监控
     */
    private static void enableWindowMonitor() {
        Log.i(TAG, "启用窗口监控被调用，类加载器: " + (mClassLoader != null ? mClassLoader.getClass().getName() : "null"));
        
        // Hook Dialog 不同构造函数
        hookDialogConstructor(Context.class);
        hookDialogConstructor(Context.class, int.class);
        try {
            // 兼容带OnCancelListener的构造函数
            Class<?> onCancelListenerClass = Class.forName("android.content.DialogInterface$OnCancelListener");
            hookDialogConstructor(Context.class, boolean.class, onCancelListenerClass);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "找不到DialogInterface.OnCancelListener类", e);
        }
        

    }
    
    /**
     * 触发 Activity 处理
     */
    private static void triggerActivity() {
        triggerPendingActivityHandler("Activity 已恢复");
    }
    
    /**
     * 触发 Dialog 处理
     */
    private static void triggerDialogProcessing() {
        triggerPendingActivityHandler("Dialog 已创建");
    }
    
    /**
     * 触发待处理的 Activity 处理器
     */
    private static void triggerPendingActivityHandler(String source) {
        final Activity activity = getTopActivity();
        if (activity == null) {
            Log.i(TAG, "无法从 " + source + " 触发处理器，未找到顶层 Activity");
            return;
        }
        
        final ActivityFocusHandler handler = activityFocusHandlerMap.get(activity.getClass().getName());
        if (handler == null) {
            Log.d(TAG, "未找到 " + activity.getClass().getName() + " 的处理器，来源: " + source);
            return;
        }
        
        // 判断与置位必须原子：原先"先读后写 volatile"会让两个线程同时通过检查
        if (!hasPendingActivityTask.compareAndSet(false, true)) {
            // 已有处理链在跑：不要并发再开一条。原实现每次尝试开始就把标记清掉，触发稍密就会同时跑多条链，
            // 每条都在主线程阻塞 1s 以上 → 后台场景下宿主被拖死。这里改为整条链独占，只记下"跑完再补一次"。
            rerunRequested.set(true);
            Log.d(TAG, "已有处理链在运行，标记补跑（来源: " + source + "）");
            return;
        }
        
        Log.i(TAG, "从 " + source + " 触发 " + activity.getClass().getName() + " 的处理器");
        triggerActivityActive(activity, handler, 0);
    }
    
    /**
     * 延迟触发 Activity 处理（替换原 Kotlin 协程逻辑，纯 Java 实现）
     */
    private static void triggerActivityActive(
            final Activity activity,
            final ActivityFocusHandler activityFocusHandler,
            final int triggerCount
    ) {
        if (disable) {
            // 原先直接 return 没有复位标记，会让标记永远停在 true，之后每次触发都被"已有待处理任务"跳过
            releasePendingTask();
            Log.i(TAG, "页面触发管理器已禁用");
            return;
        }
        
        // 替代协程 delay()：延迟时长保持 taskDuration
        handler.postDelayed(() -> attemptOnWorker(activity, activityFocusHandler, triggerCount), taskDuration);
    }
    
    /**
     * 主线程：取一次视图快照，然后把真正的处理交给工作线程（主线程不做任何等待）
     */
    private static void attemptOnWorker(
            final Activity activity,
            final ActivityFocusHandler activityFocusHandler,
            final int triggerCount
    ) {
        SimpleViewImage root = null;
        try {
            View decorView = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (decorView != null) {
                root = new SimpleViewImage(decorView);
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "取 Activity 视图出错: " + activity.getClass().getName(), throwable);
        }
        if (root == null) {
            onAttemptFinished(activity, activityFocusHandler, triggerCount, false);
            return;
        }
        
        final SimpleViewImage rootView = root;
        try {
            captchaWorker.execute(() -> {
                boolean handled = false;
                try {
                    handled = activityFocusHandler.handleActivity(activity, rootView);
                } catch (Throwable throwable) {
                    Log.e(TAG, "处理 Activity 出错: " + activity.getClass().getName(), throwable);
                }
                final boolean done = handled;
                // 回到主线程再决定：结束本次链，还是继续下一次尝试
                handler.post(() -> onAttemptFinished(activity, activityFocusHandler, triggerCount, done));
            });
        } catch (Throwable throwable) {
            // 线程池不可用（极罕见）：必须复位标记，否则后续触发会被永久跳过
            Log.e(TAG, "验证码处理线程不可用: ", throwable);
            releasePendingTask();
        }
    }
    
    /**
     * 一次尝试结束（主线程）：处理成功即结束整条链，否则继续下一次（上限与历史行为一致）
     */
    private static void onAttemptFinished(
            Activity activity,
            ActivityFocusHandler activityFocusHandler,
            int triggerCount,
            boolean handled
    ) {
        if (handled) {
            releasePendingTask();
            return;
        }
        if (triggerCount <= MAX_ACTIVITY_ATTEMPT) {
            triggerActivityActive(activity, activityFocusHandler, triggerCount + 1);
        } else {
            Log.w(TAG, "Activity 事件触发失败次数过多: " + activityFocusHandler.getClass().getName());
            releasePendingTask();
        }
    }
    
    /**
     * 结束本次处理链（复位"有待处理任务"标记）；期间若有被记下的补跑请求，就立刻补跑一次。
     * <p>补跑只消费一次标记，因此即使处理一直不成功也不会自我循环下去。
     */
    private static void releasePendingTask() {
        hasPendingActivityTask.set(false);
        if (rerunRequested.compareAndSet(true, false)) {
            triggerPendingActivityHandler("补跑");
        }
    }
}