package io.github.aw1y2z.sesame.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 模块 App 与宿主（支付宝进程）之间的唯一通道，只走广播：经 {@code sesame.pair.request}
 * 配对拿到令牌，之后每个动作都带令牌并用 {@code setPackage} 定向投给宿主。
 * <p>不走回环 HTTP：127.0.0.1 的 socket 同样要 INTERNET 权限，而本 APK 不申请网络权限。
 */
public final class HostBridge {
    private static final String TAG = "HostBridge";
    private static final String HOST_PACKAGE = "com.eg.android.AlipayGphone";
    private static final String ACTION_PAIR_REQUEST = HOST_PACKAGE + ".sesame.pair.request";
    private static final String ACTION_PAIR_RESULT = "io.github.aw1y2z.sesame.pair.result";
    private static final String ACTION_CONTROL = HOST_PACKAGE + ".sesame.control";
    private static final String SP_NAME = "host_bridge";
    private static final String KEY_TOKEN = "token";
    /** 配对回包的等待窗口：宿主没在跑时没必要一直挂着 receiver */
    private static final long PAIR_TIMEOUT_MS = 10_000L;

    private HostBridge() {
    }

    /** 读取已配对的令牌；没配对过返回 null（调用方据此引导用户先打开支付宝） */
    public static String getToken(Context context) {
        if (context == null) {
            return null;
        }
        String token = prefs(context).getString(KEY_TOKEN, null);
        return token != null && token.length() == 64 ? token : null;
    }

    public static boolean isPaired(Context context) {
        return getToken(context) != null;
    }

    /** 忘记令牌：令牌被宿主重置或鉴权一直失败时调用，之后需要重新配对 */
    public static void clearToken(Context context) {
        if (context != null) {
            prefs(context).edit().remove(KEY_TOKEN).apply();
        }
    }

    /** 未配对时发起一次配对；已配对直接返回（幂等，可放心在每次进前台时调用） */
    public static void ensurePaired(Context context) {
        if (context == null || isPaired(context)) {
            return;
        }
        requestPair(context.getApplicationContext());
    }

    /** 主动配对：发随机 challenge，校验宿主定向回包后才落盘，防止别的应用塞假令牌 */
    public static void requestPair(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String challenge = randomHex(16);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    if (!challenge.equals(intent.getStringExtra("challenge"))) {
                        android.util.Log.w(TAG, "pair result challenge mismatch");
                        return;
                    }
                    String token = intent.getStringExtra("token");
                    if (token == null || token.length() != 64) {
                        android.util.Log.w(TAG, "pair result token malformed");
                        return;
                    }
                    prefs(app).edit().putString(KEY_TOKEN, token).apply();
                    android.util.Log.i(TAG, "paired with host");
                } finally {
                    unregisterQuietly(app, this);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_PAIR_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "register pair receiver failed", t);
            return;
        }
        Intent request = new Intent(ACTION_PAIR_REQUEST);
        // 定向：不设包名会被同设备任意应用收到
        request.setPackage(HOST_PACKAGE);
        request.putExtra("challenge", challenge);
        try {
            app.sendBroadcast(request);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "send pair request failed", t);
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> unregisterQuietly(app, receiver), PAIR_TIMEOUT_MS);
    }

    /** 发受控动作，{@code op} 须是宿主白名单里的取值；广播单向，结果只在 status 回包与日志里 */
    public static void send(Context context, String op, String userId, String group, boolean exec) {
        send(context, op, userId, group, null, exec);
    }

    /** 带 data 的重载：扩展页的走路路径 ID 这类参数走这里 */
    public static void send(Context context, String op, String userId, String group, String data,
                            boolean exec) {
        if (context == null || op == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        String token = getToken(app);
        if (token == null) {
            // 还没有令牌：先配对，用户下次操作时通常就通了
            requestPair(app);
            return;
        }
        controlBroadcast(app, op, userId, group, data, exec, token);
    }

    private static boolean controlBroadcast(Context app, String op, String userId, String group,
                                           String data, boolean exec, String token) {
        try {
            Intent intent = new Intent(ACTION_CONTROL);
            // 必须定向：隐式广播会把带令牌的 Intent 投给所有注册方
            intent.setPackage(HOST_PACKAGE);
            intent.putExtra("token", token);
            intent.putExtra("op", op);
            if (userId != null && !userId.isEmpty()) {
                intent.putExtra("userId", userId);
            }
            if (group != null && !group.isEmpty()) {
                intent.putExtra("group", group);
            }
            if (data != null && !data.isEmpty()) {
                intent.putExtra("data", data);
            }
            if (exec) {
                intent.putExtra("exec", true);
            }
            app.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "control broadcast " + op + " failed", t);
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    private static void unregisterQuietly(Context context, BroadcastReceiver receiver) {
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
            // 已注销或从未注册成功
        }
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : raw) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
