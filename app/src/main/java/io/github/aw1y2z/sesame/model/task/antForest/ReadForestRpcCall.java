package io.github.aw1y2z.sesame.model.task.antForest;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;

import org.json.JSONObject;
import io.github.aw1y2z.sesame.hook.ApplicationHook;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 森林无纸阅读 HTTP 客户端: 直连 https://m.zhangwenwh.com/api (小程序自有网关, 不走支付宝RPC)
 */
public final class ReadForestRpcCall {
    private static final String APP_ID = "2021003114652763";
    private static final String DEFAULT_VERSION = "0.2.2410251510.53";
    private static final String HOST = "https://m.zhangwenwh.com/api";
    private static final Random RANDOM = new Random();
    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");
    /** 网关偶发长时间无响应，读超时收紧到 12s：宁可失败换书，也不要卡死整轮 */
    private static final int CONNECT_TIMEOUT = 5;
    private static final int READ_TIMEOUT = 12;
    private static final int CALL_TIMEOUT = 15;
    /** set_read_recently 只影响"最近阅读"展示，失败也无所谓，给它独立的 4s 短超时 */
    private static final int SHORT_READ_TIMEOUT = 4;
    private static final int SHORT_CALL_TIMEOUT = 6;

    /** 全程共用同一个 Client：内部连接池复用 keep-alive，省掉每请求一次 TLS 握手 */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(4, 5, TimeUnit.MINUTES))
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    /** 短超时 Client：newBuilder 复用同一个连接池，与主 Client 共享 keep-alive 连接 */
    private static final OkHttpClient SHORT_CLIENT = CLIENT.newBuilder()
            .readTimeout(SHORT_READ_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(SHORT_CALL_TIMEOUT, TimeUnit.SECONDS)
            .build();

    private ReadForestRpcCall() {
    }

    /* 登录: 用authCode换取token */
    public static String login(String authCode, String version) throws Exception {
        JSONObject body = new JSONObject();
        body.put("auth_code", authCode);
        body.put("app_id", APP_ID);
        body.put("cid", "7001");
        return post("/authorization", body, null, getReferer(version, null, null));
    }

    /* 校验缓存token是否仍有效: 带token请求authorization, 有效时返回data="success" */
    public static String checkToken(String token, String version) throws Exception {
        return post("/authorization", new JSONObject(), token, getReferer(version, null, null));
    }

    /* 首页书城: 获取免费书列表 */
    public static String getIndex(String token, String version) throws Exception {
        return get("/index/index?type=1", token, getReferer(version, null, null));
    }

    /* 章节内容: chapterId为空取第一章, 否则按chapter_id取下一章 */
    public static String getChapter(String bookId, String chapterId, String token, String version) throws Exception {
        String path;
        if (chapterId == null || chapterId.length() == 0) {
            path = "/book/chapter?book_id=" + bookId;
        } else {
            path = "/book/chapter?book_id=" + bookId + "&chapter_id=" + chapterId + "&chapter_num=&source=";
        }
        return get(path, token, getReferer(version, bookId, chapterId));
    }

    /* 查询阅读绿色能量进度 current/total */
    public static String checkEnergy(String token, String version, String bookId, String chapterId) throws Exception {
        return get("/user/forestPro", token, getReferer(version, bookId, chapterId));
    }

    /* 上报阅读时长(秒) */
    public static String updateReadingTime(String bookId, String chapterId, int seconds, String token, String version) throws Exception {
        JSONObject body = new JSONObject();
        body.put("time", seconds);
        putChapterId(body, chapterId);
        body.put("book_id", bookId);
        return post("/user/updateWel", body, token, getReferer(version, bookId, chapterId));
    }

    /* 上报最近阅读(书名/章节名): 只影响展示, 失败无损失, 走短超时客户端 */
    public static String setReadRecently(String bookId, String chapterId, String chapterName, String token, String version) throws Exception {
        JSONObject body = new JSONObject();
        body.put("book_id", bookId);
        putChapterId(body, chapterId);
        body.put("chapter_name", chapterName);
        return postShort("/book/set_read_recently", body, token, getReferer(version, bookId, chapterId));
    }

    private static void putChapterId(JSONObject body, String chapterId) throws Exception {
        try {
            body.put("chapter_id", Long.parseLong(chapterId));
        } catch (Exception unused) {
            body.put("chapter_id", chapterId);
        }
    }

    /* 构造referer: 模拟小程序页面路径, 章节页带bid/cid */
    public static String getReferer(String version, String bookId, String chapterId) {
        if (version == null || version.length() == 0) {
            version = DEFAULT_VERSION;
        }
        if (version.startsWith("http")) {
            return version;
        }
        String base = "https://" + APP_ID + ".hybrid.alipay-eco.com/" + APP_ID + "/" + version + "/index.html";
        if (bookId != null && bookId.length() != 0 && chapterId != null && chapterId.length() != 0) {
            return base + "#moduleA/pages/chapter/index?__appxPageId=" + (RANDOM.nextInt(20) + 1) + "&bid=" + bookId + "&cid=" + chapterId;
        }
        if (bookId != null && bookId.length() != 0) {
            return base + "#moduleA/pages/chapter/index?__appxPageId=" + (RANDOM.nextInt(20) + 1) + "&bid=" + bookId;
        }
        return base + "#pages/index/index";
    }

    /**
     * 动态拼装 User-Agent，按小程序真实请求头对齐：
     * 设备/系统/Chrome 取自系统 http.agent；MYWeb/nt/ws 等按真实格式追加，不再带 ChannelId。
     */
    private static String getBaseUserAgent() {
        String systemUa = System.getProperty("http.agent");
        if (systemUa == null || systemUa.isEmpty()) {
            systemUa = "Mozilla/5.0 (Linux; Android 11)";
        }
        // 真实 UA 里 Mobile Safari/537.36 位于 UCBS 之后，系统 UA 末尾自带的那份要去掉
        String base = systemUa.replace(" Mobile Safari/537.36", "").trim();
        String alipayVer = ApplicationHook.getAlipayVersion().getVersionString();
        return base
                + " MYWeb/1.11.126.260911211123 UWS/3.22.2.9999 UCBS/3.22.2.9999_220000000000"
                + " Mobile Safari/537.36 NebulaSDK/1.8.100112 Nebula"
                + " AlipayDefined(nt:" + networkType() + ",ws:" + screenSpec() + ")"
                + " AliApp(AP/" + alipayVer + ") AlipayClient/" + alipayVer
                + " Language/zh-Hans isConcaveScreen/true"
                + " Region/CNAriver/" + alipayVer + " DTN/2.0";
    }

    /** 当前网络类型(WIFI/5G/4G)，取不到按 WIFI */
    private static String networkType() {
        try {
            Context context = ApplicationHook.getContext();
            if (context != null) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return "WIFI";
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                            if (tm != null && tm.getDataNetworkType() == TelephonyManager.NETWORK_TYPE_NR) {
                                return "5G";
                            }
                        }
                        return "4G";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "WIFI";
    }

    /** 屏幕规格(宽|0|密度)，取不到用 393|0|2.75 */
    private static String screenSpec() {
        try {
            Context context = ApplicationHook.getContext();
            if (context != null) {
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                int width = Math.round(dm.widthPixels / dm.density);
                float density = Math.round(dm.density * 100) / 100f;
                return width + "|0|" + density;
            }
        } catch (Throwable ignored) {
        }
        return "393|0|2.75";
    }

    private static Map<String, String> getHeaders(String token, String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json;charset=UTF-8");
        headers.put("referer", referer == null ? "" : referer);
        headers.put("user-agent", getBaseUserAgent());
        headers.put("x-requested-with", "com.eg.android.AlipayGphone");
        headers.put("x-mass-tappid", APP_ID);
        headers.put("accept-language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("x-release-type", "ONLINE");
        if (token != null) {
            headers.put("authorization", "Bearer " + token);
        }
        return headers;
    }

    private static String get(String path, String token, String referer) throws Exception {
        return execute(CLIENT, "GET", path, null, token, referer);
    }

    private static String post(String path, JSONObject body, String token, String referer) throws Exception {
        return execute(CLIENT, "POST", path, body == null ? "{}" : body.toString(), token, referer);
    }

    private static String postShort(String path, JSONObject body, String token, String referer) throws Exception {
        return execute(SHORT_CLIENT, "POST", path, body == null ? "{}" : body.toString(), token, referer);
    }

    /**
     * 单次请求：共用连接池，正常读完由 OkHttp 自动回池，异常连接自动丢弃。
     * 4xx/5xx 也返回响应体文本（与原先读 errorStream 的行为一致），业务层按 code 判断。
     */
    private static String execute(OkHttpClient client, String method, String path, String body, String token, String referer) throws Exception {
        Request.Builder builder = new Request.Builder().url(HOST + path);
        for (Map.Entry<String, String> entry : getHeaders(token, referer).entrySet()) {
            builder.addHeader(entry.getKey(), entry.getValue());
        }
        if ("POST".equals(method)) {
            builder.post(RequestBody.create(body == null ? "" : body, JSON));
        } else {
            builder.get();
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            return responseBody == null ? "" : responseBody.string();
        } catch (IOException e) {
            throw asTimeoutException(e);
        }
    }

    /**
     * OkHttp 的读超时/CallTimeout 可能抛 InterruptedIOException，
     * 统一成 SocketTimeoutException，让上层继续按「超时降级」处理，不必区分异常类型。
     */
    private static Exception asTimeoutException(IOException e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof SocketTimeoutException) {
                return (SocketTimeoutException) cur;
            }
            if (cur instanceof InterruptedIOException || "timeout".equalsIgnoreCase(cur.getMessage())) {
                SocketTimeoutException timeout = new SocketTimeoutException(cur.getMessage() == null ? "timeout" : cur.getMessage());
                timeout.initCause(cur);
                return timeout;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return e;
    }
}
