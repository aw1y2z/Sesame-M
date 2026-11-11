package io.github.aw1y2z.sesame.model.task.antForest;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final String INDEX_KEY = "index_response";
    private static final String INDEX_DAY_KEY = "index_day";
    /** 单章耗时上限：正常一章 1~3s，超过说明服务端在拖，继续读只是把模块时间耗在慢请求上 */
    private static final long CHAPTER_BUDGET_MS = 15 * 1000L;
    /** 连续多少次慢事件才判定本轮过慢：单次抖动不中断，避免误伤正常轮次 */
    private static final int SLOW_EVENT_LIMIT = 5;
    /** 本轮总预算兜底：零散慢请求累积也不至于把一次调度拖成十几分钟 */
    private static final long ROUND_BUDGET_MS = 15 * 60 * 1000L;
    /** 进度查询降频：每 5 章查一次（能量按差值累计，跳章查询不丢数） */
    private static final int CHECK_EVERY_CHAPTERS = 5;
    /** 临近满额(剩余 ≤ 8g)时恢复每章查询：窗口略大于一次查询最多覆盖的能量，避免满额后继续白读 */
    private static final int NEAR_FULL_MARGIN = 8;
    /** 累计 6 章无增益 / 6 章查不到进度，即认为此书到顶或网络异常（按章计，不随查询频率放大） */
    private static final int NO_GAIN_CHAPTER_LIMIT = 6;
    private static final int NO_DATA_CHAPTER_LIMIT = 6;
    /** 上报并发池：阅读时长与最近阅读同时发，串行会白等一整个慢请求 */
    private static final ExecutorService REPORT_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "forest-read-report");
        thread.setDaemon(true);
        return thread;
    });
    private static final int REPORT_WAIT_SECONDS = 16;
    /** 章节预取池：读完本章就把下一章内容取回来，与上报/进度查询/章节间隔重叠 */
    private static final ExecutorService CHAPTER_POOL = Executors.newFixedThreadPool(1, r -> {
        Thread thread = new Thread(r, "forest-read-chapter");
        thread.setDaemon(true);
        return thread;
    });
    /** 预取本身带 callTimeout(15s) 且失败重试一次，这里只做兜底，不早于请求自身超时 */
    private static final int CHAPTER_WAIT_SECONDS = 35;
    /** 本次累计获得的能量：逐章进度只走 runtime，结束时向 forest 输出一条汇总 */
    private static int totalEnergy = 0;
    /** 连续慢事件计数：跨书累计，连续多次慢就判定本轮无望，主动让出等下一轮 */
    private static int consecutiveSlow = 0;

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
            consecutiveSlow = 0;
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
            long roundStart = System.currentTimeMillis();
            // 书城首页响应较大且慢：当天复用一次，选不出书时再强制刷新
            String indexResponse = loadCachedIndex(context);
            boolean indexFromCache = !isEmpty(indexResponse);
            boolean indexRefreshed = false;
            if (indexFromCache) {
                Log.i(TAG, "复用今日书城缓存，省一次首页请求");
            }
            while (true) {
                if (isEmpty(indexResponse)) {
                    try {
                        indexResponse = fetchIndex(token);
                    } catch (java.net.SocketTimeoutException e) {
                        Log.record(TAG + "书城请求超时，本轮阅读结束");
                        logSummary("书城请求超时");
                        return;
                    }
                    if (isIndexUsable(indexResponse)) {
                        saveCachedIndex(context, indexResponse);
                        indexFromCache = true;
                    }
                }
                JSONObject freeBook = selectFreeBook(finishedBooks, indexResponse, triedBooks);
                if (freeBook == null) {
                    // 缓存里挑不出未读免费书：可能是缓存过期，刷新一次真实首页再试
                    if (indexFromCache && !indexRefreshed) {
                        indexRefreshed = true;
                        indexFromCache = false;
                        indexResponse = "";
                        Log.record(TAG + "缓存书城未选出可读书籍，刷新首页重试");
                        continue;
                    }
                    logSummary("首页暂无更多未读免费书籍");
                    return;
                }
                String bookId = freeBook.optString("book_id");
                String bookName = freeBook.optString("name", "未知");
                if (isEmpty(bookId)) {
                    triedBooks.add("");
                    TimeUtil.sleep(2000);
                } else {
                    Boolean bookResult = autoReadBook(bookId, bookName, token, context, finishedBooks, roundStart);
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
        } catch (ReadRoundSlowException e) {
            // 服务端过慢时主动让出本轮：不写今日完成标记，下一轮调度会重新进入阅读
            logSummary(e.getMessage() + "，本轮阅读提前结束，等下轮重试");
            return;
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
    private static Boolean autoReadBook(String bookId, String bookName, String token, Context context, JSONObject finishedBooks, long roundStart) throws Exception {
        JSONObject chapterData;
        try {
            chapterData = parseData(fetchChapter(bookId, null, token));
        } catch (java.net.SocketTimeoutException e) {
            // 取章节超时：偶发一次换下一本，连续两次说明服务端整体在拖，结束本轮
            markSlow("[" + bookName + "]取章节超时，换下一本");
            return null;
        }
        if (chapterData == null) {
            return Boolean.FALSE;
        }
        int lastEnergy = -1;
        int noGainChapters = 0;
        int noDataChapters = 0;
        int reportedSinceCheck = 0;
        boolean nearFull = false;
        while (chapterData != null) {
            // 慢请求已经把本轮拖得太久：直接让出，别让整轮调度超时被系统杀掉
            if (System.currentTimeMillis() - roundStart > ROUND_BUDGET_MS) {
                throw new ReadRoundSlowException("本轮阅读已超过" + (ROUND_BUDGET_MS / 60000) + "分钟");
            }
            long chapterStart = System.currentTimeMillis();
            String chapterId = chapterData.optString("id");
            String nextId = chapterData.optString("next_id");
            String chapterName = chapterData.optString("name", "未知");
            if (isEmpty(chapterId)) {
                return Boolean.FALSE;
            }
            // 本章一开始就把下一章内容取回来：与上报、进度查询、章节间隔全部重叠，翻页耗时基本被吃掉
            Future<String> nextChapter = submitChapter(bookId, nextId, token);
            // 首章必须先确认当日是否已满额、以及此书能否拿到能量，失败直接跳过这本书
            boolean firstCheck = lastEnergy == -1;
            EnergyProgress progress = null;
            if (firstCheck) {
                progress = readEnergyProgress(token, bookId, chapterId);
                if (!progress.hasData) {
                    cancelQuietly(nextChapter);
                    Log.record(TAG + "[" + bookName + "]初始进度查询失败,跳过此书");
                    return Boolean.FALSE;
                }
                if (progress.current >= progress.total) {
                    cancelQuietly(nextChapter);
                    return Boolean.TRUE;
                }
                lastEnergy = progress.current;
                nearFull = isNearFull(progress.current, progress.total);
                reportedSinceCheck = 0;
            }
            // 阅读时长与最近阅读互不依赖：只提交不阻塞，让它们和下面的章节间隔 sleep 重叠
            Future<?>[] readingReport = submitReadingReport(bookId, chapterId, chapterName, token, bookName);
            reportedSinceCheck++;
            // 平时每 5 章查一次；临近满额(剩余 ≤8g)时每章查，避免满额后继续白读
            int interval = nearFull ? 1 : CHECK_EVERY_CHAPTERS;
            if (!firstCheck && reportedSinceCheck >= interval) {
                // 这 1s 既等服务端结算，也顺带盖住上面两次上报的网络耗时
                TimeUtil.sleep(1000);
                awaitReport(readingReport);
                progress = readEnergyProgress(token, bookId, chapterId);
                reportedSinceCheck = 0;
            }
            if (progress != null) {
                if (progress.hasData) {
                    noDataChapters = 0;
                    // 本次查询覆盖的章数：首章是基线查询，没有可对比的区间
                    int chaptersSinceLastCheck = firstCheck ? 0 : interval;
                    int gain = firstCheck ? 0 : progress.current - lastEnergy;
                    lastEnergy = progress.current;
                    nearFull = isNearFull(progress.current, progress.total);
                    if (gain > 0) {
                        totalEnergy += gain;
                        Log.i(TAG, "[" + bookName + "]进度(" + progress.current + "/" + progress.total + ")获得能量" + gain + "g");
                        noGainChapters = 0;
                    } else if (!firstCheck) {
                        Log.record(TAG + "[" + bookName + "]进度同步: " + progress.current + "/" + progress.total);
                        noGainChapters += chaptersSinceLastCheck;
                    }
                    if (progress.current >= progress.total) {
                        cancelQuietly(nextChapter);
                        return Boolean.TRUE;
                    }
                    if (noGainChapters >= NO_GAIN_CHAPTER_LIMIT) {
                        cancelQuietly(nextChapter);
                        Log.record(TAG + "[" + bookName + "]连续" + noGainChapters
                                + "章无能量增加，可能达到此书上限，跳过");
                        return Boolean.FALSE;
                    }
                } else {
                    // 查询进度失败(网络超时兜底):保持 lastEnergy 不变，下次成功查询时按差值自然补记
                    noDataChapters += (firstCheck ? 0 : interval);
                    Log.record(TAG + "[" + bookName + "]进度查询失败,继续阅读下一章(能量累计到下次记录)");
                    if (noDataChapters >= NO_DATA_CHAPTER_LIMIT) {
                        cancelQuietly(nextChapter);
                        Log.record(TAG + "[" + bookName + "]连续" + noDataChapters + "章进度查询失败,网络异常,跳过");
                        return Boolean.FALSE;
                    }
                }
            }
            if (isEmpty(nextId) || "null".equals(nextId) || "0".equals(nextId)) {
                cancelQuietly(nextChapter);
                markFinished(context, finishedBooks, bookId, bookName);
                return null;
            }
            // 模拟阅读间隔，同时把本章上报的网络耗时藏进去
            TimeUtil.sleep(RANDOM.nextInt(801) + 400);
            awaitReport(readingReport);
            try {
                chapterData = parseData(awaitChapter(nextChapter));
            } catch (java.net.SocketTimeoutException e) {
                markSlow("[" + bookName + "]翻页超时，换下一本");
                return null;
            }
            if (chapterData == null) {
                return Boolean.FALSE;
            }
            // 单章耗时正常 1~3s；服务端高延迟时会涨到十几秒，连续偏慢就主动让出本轮
            long chapterCost = System.currentTimeMillis() - chapterStart;
            if (chapterCost > CHAPTER_BUDGET_MS) {
                markSlow("[" + bookName + "]单章耗时" + (chapterCost / 1000) + "s，偏慢");
            } else {
                consecutiveSlow = 0;
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

    /* 书城首页请求: 慢网络偶发超时, 复用连接立即重试一次, 仍超时交由上层结束本轮 */
    private static String fetchIndex(String token) throws Exception {
        try {
            return ReadForestRpcCall.getIndex(token, VERSION);
        } catch (java.net.SocketTimeoutException e) {
            return ReadForestRpcCall.getIndex(token, VERSION);
        }
    }

    /* 取章节: 超时重试一次, 第二次仍超时抛出, 由调用方换下一本 */
    private static String fetchChapter(String bookId, String chapterId, String token) throws Exception {
        try {
            return ReadForestRpcCall.getChapter(bookId, chapterId, token, VERSION);
        } catch (java.net.SocketTimeoutException e) {
            return ReadForestRpcCall.getChapter(bookId, chapterId, token, VERSION);
        }
    }

    /* 提前提交下一章请求；没有下一章(next_id 为空/0)时返回 null，由调用方按读完处理 */
    private static Future<String> submitChapter(String bookId, String nextId, String token) {
        if (isEmpty(nextId) || "null".equals(nextId) || "0".equals(nextId)) {
            return null;
        }
        try {
            return CHAPTER_POOL.submit(() -> fetchChapter(bookId, nextId, token));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取预取结果。预取线程只捕到 SocketTimeoutException 时原样抛出，让调用方沿用「翻页超时，换下一本」；
     * 其他异常同样还原给上层，保持与同步取章节一致的行为。
     */
    private static String awaitChapter(Future<String> future) throws Exception {
        if (future == null) {
            throw new java.net.SocketTimeoutException("章节预取未启动");
        }
        try {
            return future.get(CHAPTER_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new java.net.SocketTimeoutException("章节预取超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.net.SocketTimeoutException("章节预取被中断");
        }
    }

    /* 提前返回(满额/换书)时取消还没跑完的预取，别白占一次请求 */
    private static void cancelQuietly(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    /* 剩余能量 ≤ NEAR_FULL_MARGIN 时进入逐章查询的末段：满额判定最多延后 1 章 */
    private static boolean isNearFull(int current, int total) {
        return total > 0 && total - current <= NEAR_FULL_MARGIN;
    }

    /* 慢事件累计：单次只记一行并换书，连续多次说明服务端整体在拖，结束本轮等下轮 */
    private static void markSlow(String detail) {
        Log.record(TAG + detail);
        consecutiveSlow++;
        if (consecutiveSlow >= SLOW_EVENT_LIMIT) {
            throw new ReadRoundSlowException("服务端响应过慢，连续" + consecutiveSlow + "次慢请求");
        }
    }

    /**
     * 并发上报本章阅读时长与最近阅读：两者互不依赖，串行会白等一整个慢请求。
     * 只提交不等待，由调用方在章节间隔 sleep 之后统一 await，把网络耗时藏进那段等待里。
     * 时长上报超时只记一行继续（能量由下次进度查询按差值补记）；最近阅读只影响展示，走 4s 短超时，失败静默。
     */
    private static Future<?>[] submitReadingReport(String bookId, String chapterId, String chapterName, String token, String bookName) {
        Future<?> timeReport = REPORT_POOL.submit(() -> {
            try {
                ReadForestRpcCall.updateReadingTime(bookId, chapterId, RANDOM.nextInt(668) + 333, token, VERSION);
            } catch (java.net.SocketTimeoutException ignored) {
                Log.record(TAG + "[" + bookName + "]阅读时长上报超时，继续下一章");
            } catch (Throwable t) {
                Log.record(TAG + "[" + bookName + "]阅读时长上报失败: " + t);
            }
        });
        Future<?> recentlyReport = REPORT_POOL.submit(() -> {
            try {
                ReadForestRpcCall.setReadRecently(bookId, chapterId, chapterName, token, VERSION);
            } catch (Throwable ignored) {
            }
        });
        return new Future<?>[]{timeReport, recentlyReport};
    }

    /* 等本轮两路上报收尾：正常情况下 sleep 期间已完成，这里几乎不阻塞 */
    private static void awaitReport(Future<?>[] reports) {
        if (reports == null) {
            return;
        }
        for (Future<?> report : reports) {
            awaitQuietly(report);
        }
    }

    /* 等待任务结束但不让异常外泄：请求自身有 callTimeout 兜底，这里只是不让线程无限期挂住 */
    private static void awaitQuietly(Future<?> future) {
        try {
            future.get(REPORT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }

    /* 书城首页缓存：一天一份，当天复用可省掉一次慢请求 */
    private static String loadCachedIndex(Context context) {
        try {
            SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, 0);
            String scope = userScope();
            if (!today().equals(preferences.getString(INDEX_DAY_KEY + "_" + scope, ""))) {
                return "";
            }
            return preferences.getString(INDEX_KEY + "_" + scope, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void saveCachedIndex(Context context, String response) {
        try {
            String scope = userScope();
            context.getSharedPreferences(PREF_NAME, 0).edit()
                    .putString(INDEX_KEY + "_" + scope, response)
                    .putString(INDEX_DAY_KEY + "_" + scope, today())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /* 只缓存正常响应：失败或空响应缓存下来会让整天都选不出书 */
    private static boolean isIndexUsable(String response) {
        if (isEmpty(response)) {
            return false;
        }
        try {
            JSONObject jo = new JSONObject(response);
            return jo.optInt("code") == 2000 && jo.optJSONArray("data") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
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
        // current 缺失或 total 非正都视为降级/畸形响应：否则会被满额判定吃掉，还顺手写掉当日完成标记
        int current = dataJo.optInt("current", -1);
        int total = dataJo.optInt("total", TOTAL_FALLBACK);
        if (current < 0 || total <= 0) {
            return new EnergyProgress(false, 0, TOTAL_FALLBACK);
        }
        return new EnergyProgress(true, current, total);
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

    /** 服务端过慢：本轮主动中断，不标记今日完成，下一轮调度继续重试 */
    private static final class ReadRoundSlowException extends RuntimeException {
        ReadRoundSlowException(String message) {
            super(message);
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
