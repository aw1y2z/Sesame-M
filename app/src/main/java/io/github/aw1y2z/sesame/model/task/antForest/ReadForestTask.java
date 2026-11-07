package io.github.aw1y2z.sesame.model.task.antForest;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.hook.AuthCodeHelper;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 森林无纸阅读任务: 读免费书攒阅读时长换绿色能量, 当日能量满额后停止
 */
public final class ReadForestTask {
    private static final String APP_ID = "2021003114652763";
    private static final String PREF_NAME = "sesame_forest_read";
    private static final String FINISHED_KEY = "finished_books";
    private static final String TOKEN_KEY = "token";
    private static final String DONE_FLAG = "Forest::readForest";
    private static final Random RANDOM = new Random();
    private static final String TAG = "森林阅读📖";
    private static final int TOTAL_FALLBACK = 150;
    private static final String VERSION = "0.2.2410251510.53";
    /** 本次累计获得的能量：逐章进度只走 runtime，结束时向 forest 输出一条汇总 */
    private static int totalEnergy = 0;

    private ReadForestTask() {
    }

    public static void execute() {
        try {
            Context context = ApplicationHook.getContext();
            if (context == null) {
                Log.record(TAG + "未获取到应用上下文，自动阅读任务失败");
                return;
            }
            if (Status.hasFlagToday(DONE_FLAG)) {
                Log.i(TAG, "自动阅读任务今日已执行，跳过");
                return;
            }
            Log.record(TAG + "开始执行自动阅读任务(多本连读模式)");
            totalEnergy = 0;
            // 优先复用缓存token(带token校验有效), 失效再用authCode重新登录
            String token = loadCachedToken(context);
            if (!isEmpty(token) && isTokenValid(token)) {
                // token 有效直接复用
            } else {
                token = "";
                String authCode = AuthCodeHelper.getAuthCode(APP_ID);
                if (isEmpty(authCode)) {
                    Log.record(TAG + "authCode为空，自动阅读任务失败");
                    return;
                }
                String loginResponse = ReadForestRpcCall.login(authCode, VERSION);
                if (isEmpty(loginResponse)) {
                    Log.record(TAG + "登录失败: 返回为空");
                    return;
                }
                JSONObject loginJo = new JSONObject(loginResponse);
                if (loginJo.optInt("code") != 2000) {
                    Log.record(TAG + "登录失败: " + loginJo.optString("msg"));
                    return;
                }
                JSONObject loginData = loginJo.optJSONObject("data");
                if (loginData == null) {
                    Log.record(TAG + "登录失败: 缺少token");
                    return;
                }
                token = loginData.optString("token");
                if (isEmpty(token)) {
                    Log.record(TAG + "登录失败: token为空");
                    return;
                }
                saveCachedToken(context, token);
            }
            JSONObject finishedBooks = loadFinishedBooks(context, userScope());
            Set<String> triedBooks = new HashSet<>();
            while (true) {
                String indexResponse;
                try {
                    indexResponse = ReadForestRpcCall.getIndex(token, VERSION);
                } catch (java.net.SocketTimeoutException e) {
                    Log.record(TAG + "书城请求超时，本轮阅读结束");
                    logSummary("书城请求超时");
                    return;
                }
                JSONObject freeBook = selectFreeBook(finishedBooks, indexResponse, triedBooks);
                if (freeBook == null) {
                    logSummary("首页暂无更多未读免费书籍");
                    return;
                }
                String bookId = freeBook.optString("book_id");
                String bookName = freeBook.optString("name", "未知");
                if (isEmpty(bookId)) {
                    triedBooks.add("");
                    TimeUtil.sleep(2000);
                } else {
                    Boolean bookResult = autoReadBook(bookId, bookName, token, context, finishedBooks);
                    if (Boolean.TRUE.equals(bookResult)) {
                        Status.flagToday(DONE_FLAG, UserIdMap.getCurrentUid());
                        Log.forest(TAG + "今日能量已满，本次共获得能量" + totalEnergy + "g");
                        return;
                    } else if (Boolean.FALSE.equals(bookResult)) {
                        logSummary("检测到当前无法获得阅读能量，终止任务流程");
                        return;
                    } else {
                        triedBooks.add(bookId);
                        TimeUtil.sleep(2000);
                    }
                }
            }
        } catch (Throwable t) {
            // 外网抖动很常见，超时/OOM 之外的异常才需要堆栈：
            // 读超时只记一行，避免每次网络抖动都在运行日志刷 30 行堆栈
            if (isNetworkIssue(t)) {
                Log.record(TAG + "网络超时，本轮阅读中断");
            } else {
                Log.err(TAG, "execute err:", t);
            }
            // 异常中断也向 forest 输出本轮累计能量汇总,避免异常轮次 forest 日志无记录
            logSummary("执行异常中断");
        }
    }

    /* 从书城响应中挑选一本免费且未读过(也不在本次已尝试列表中)的书 */
    private static JSONObject selectFreeBook(JSONObject finishedBooks, String indexResponse, Set<String> triedBooks) throws Exception {
        JSONArray dataList;
        if (isEmpty(indexResponse) || (dataList = new JSONObject(indexResponse).optJSONArray("data")) == null) {
            return null;
        }
        for (int i = 0; i < dataList.length(); i++) {
            JSONObject dataJo = dataList.optJSONObject(i);
            if (dataJo == null) {
                continue;
            }
            JSONArray blockResourceList = dataJo.optJSONArray("block_resource");
            if (blockResourceList == null) {
                continue;
            }
            for (int j = 0; j < blockResourceList.length(); j++) {
                JSONObject bookJo = blockResourceList.optJSONObject(j);
                if (bookJo == null) {
                    continue;
                }
                String bookId = bookJo.optString("book_id");
                if ("0.00".equals(bookJo.optString("price")) && !triedBooks.contains(bookId)) {
                    if (!finishedBooks.has(bookId)) {
                        return bookJo;
                    }
                    triedBooks.add(bookId);
                }
            }
        }
        return null;
    }

    /* 逐章阅读一本书: 返回TRUE=今日能量已满, FALSE=此书无法再获得能量(或无法继续), null=全书读完自动换下一本 */
    private static Boolean autoReadBook(String bookId, String bookName, String token, Context context, JSONObject finishedBooks) throws Exception {
        JSONObject chapterData;
        try {
            chapterData = parseData(ReadForestRpcCall.getChapter(bookId, null, token, VERSION));
        } catch (java.net.SocketTimeoutException e) {
            // 取章节超时只影响这一本，交给上层换书，不必中断整轮
            Log.record(TAG + "[" + bookName + "]取章节超时，换下一本");
            return null;
        }
        if (chapterData == null) {
            return Boolean.FALSE;
        }
        int lastEnergy = -1;
        int noGainCount = 0;
        int noDataCount = 0;
        while (chapterData != null) {
            String chapterId = chapterData.optString("id");
            String nextId = chapterData.optString("next_id");
            String chapterName = chapterData.optString("name", "未知");
            if (isEmpty(chapterId)) {
                return Boolean.FALSE;
            }
            if (lastEnergy == -1) {
                EnergyProgress firstProgress = readEnergyProgress(token, bookId, chapterId);
                if (!firstProgress.hasData) {
                    Log.record(TAG + "[" + bookName + "]初始进度查询失败,跳过此书");
                    return Boolean.FALSE;
                }
                if (firstProgress.current >= firstProgress.total) {
                    return Boolean.TRUE;
                }
                lastEnergy = firstProgress.current;
            }
            // 上报类接口偶发超时不必中断整本书：吞掉异常继续读，能量由下次进度查询自然补记
            try {
                ReadForestRpcCall.updateReadingTime(bookId, chapterId, RANDOM.nextInt(668) + 333, token, VERSION);
            } catch (java.net.SocketTimeoutException ignored) {
                Log.record(TAG + "[" + bookName + "]阅读时长上报超时，继续下一章");
            }
            try {
                ReadForestRpcCall.setReadRecently(bookId, chapterId, chapterName, token, VERSION);
            } catch (java.net.SocketTimeoutException ignored) {
            }
            TimeUtil.sleep(1000);
            EnergyProgress progress = readEnergyProgress(token, bookId, chapterId);
            if (progress.hasData) {
                noDataCount = 0;
                int gain = progress.current - lastEnergy;
                if (gain > 0) {
                    totalEnergy += gain;
                    Log.i(TAG, "[" + bookName + "]进度(" + progress.current + "/" + progress.total + ")获得能量" + gain + "g");
                    noGainCount = 0;
                } else {
                    Log.record(TAG+"[" + bookName + "]进度同步: " + progress.current + "/" + progress.total);
                    noGainCount++;
                }
                if (progress.current >= progress.total) {
                    return Boolean.TRUE;
                }
                if (noGainCount >= 5) {
                    Log.record(TAG + "[" + bookName + "]连续5章无能量增加，可能达到此书上限，跳过");
                    return Boolean.FALSE;
                }
            } else {
                // 查询进度失败(网络超时兜底):保持 lastEnergy 不变、不计无能量次数,继续读下一章;
                // 下次成功查询时 gain=新进度-旧lastEnergy 会自然补记本次已上报时长的能量,避免记录丢失
                noDataCount++;
                Log.record(TAG + "[" + bookName + "]进度查询失败,继续阅读下一章(能量累计到下次记录)");
                if (noDataCount >= 5) {
                    Log.record(TAG + "[" + bookName + "]连续5章进度查询失败,网络异常,跳过");
                    return Boolean.FALSE;
                }
            }
            if (isEmpty(nextId) || "null".equals(nextId) || "0".equals(nextId)) {
                markFinished(context, finishedBooks, bookId, bookName);
                return null;
            }
            TimeUtil.sleep(RANDOM.nextInt(2002) + 1000);
            try {
                chapterData = parseData(ReadForestRpcCall.getChapter(bookId, nextId, token, VERSION));
            } catch (java.net.SocketTimeoutException e) {
                Log.record(TAG + "[" + bookName + "]翻页超时，换下一本");
                return null;
            }
            if (chapterData == null) {
                return Boolean.FALSE;
            }
            if (progress.hasData) {
                lastEnergy = progress.current;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * 是否为外网抖动类异常（超时/连接中断）。
     * <p>这类异常由网络决定、不代表代码缺陷，堆栈对定位无帮助，故不写异常日志。
     */
    private static boolean isNetworkIssue(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof java.net.SocketTimeoutException
                    || cur instanceof java.net.ConnectException
                    || cur instanceof java.net.UnknownHostException
                    || cur instanceof java.io.InterruptedIOException) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return false;
    }

    private static EnergyProgress readEnergyProgress(String token, String bookId, String chapterId) throws Exception {
        // 查询进度接口偶发网络超时:本地重试一次消化偶发抖动,避免抛异常刷日志;持续超时则兜底返回
        String response = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                response = ReadForestRpcCall.checkEnergy(token, VERSION, bookId, chapterId);
                break;
            } catch (java.net.SocketTimeoutException e) {
                TimeUtil.sleep(1000);
            }
        }
        if (isEmpty(response)) {
            return new EnergyProgress(false, 0, TOTAL_FALLBACK);
        }
        JSONObject dataJo = new JSONObject(response).optJSONObject("data");
        if (dataJo == null) {
            return new EnergyProgress(false, 0, TOTAL_FALLBACK);
        }
        return new EnergyProgress(true, dataJo.optInt("current"), dataJo.optInt("total", TOTAL_FALLBACK));
    }

    private static JSONObject parseData(String response) throws Exception {
        if (isEmpty(response)) {
            return null;
        }
        JSONObject jo = new JSONObject(response);
        if (jo.optInt("code") != 2000) {
            return null;
        }
        return jo.optJSONObject("data");
    }

    private static JSONObject loadFinishedBooks(Context context, String userScope) {
        String stored = "{}";
        try {
            SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, 0);
            String read = sharedPreferences.getString(FINISHED_KEY + "_" + userScope, "{}");
            if (read != null) {
                stored = read;
            }
            return new JSONObject(stored);
        } catch (Exception unused) {
            return new JSONObject();
        }
    }

    /* 记入已读书列表(按账号隔离), 后续自动换书时跳过 */
    private static void markFinished(Context context, JSONObject finishedBooks, String bookId, String bookName) {
        try {
            if (finishedBooks.has(bookId)) {
                return;
            }
            finishedBooks.put(bookId, bookName == null ? "未知" : bookName);
            context.getSharedPreferences(PREF_NAME, 0)
                    .edit()
                    .putString(FINISHED_KEY + "_" + userScope(), finishedBooks.toString())
                    .apply();
        } catch (Exception unused) {
        }
    }

    private static boolean isTokenValid(String token) {
        try {
            String response = ReadForestRpcCall.checkToken(token, VERSION);
            return !isEmpty(response) && new JSONObject(response).optInt("code") == 2000;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String loadCachedToken(Context context) {
        try {
            return context.getSharedPreferences(PREF_NAME, 0).getString(TOKEN_KEY + "_" + userScope(), "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void saveCachedToken(Context context, String token) {
        try {
            context.getSharedPreferences(PREF_NAME, 0).edit().putString(TOKEN_KEY + "_" + userScope(), token).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String userScope() {
        String uid = UserIdMap.getCurrentUid();
        return isEmpty(uid) ? "default" : uid.replaceAll("[^0-9A-Za-z_-]", "_");
    }

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    /** 结束时向 forest 输出一条汇总；逐章进度只走 runtime，避免刷屏 */
    private static void logSummary(String reason) {
        if (totalEnergy > 0) {
            Log.forest(TAG + reason + "，本次共获得能量" + totalEnergy + "g");
        } else {
            Log.record(TAG + reason);
        }
    }

    private static final class EnergyProgress {
        final int current;
        final boolean hasData;
        final int total;

        EnergyProgress(boolean hasData, int current, int total) {
            this.hasData = hasData;
            this.current = current;
            this.total = total;
        }
    }
}
