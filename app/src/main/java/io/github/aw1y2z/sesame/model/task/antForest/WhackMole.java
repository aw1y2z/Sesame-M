package io.github.aw1y2z.sesame.model.task.antForest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.model.task.antForest.AntForestRpcCall;

/**
 * 6秒拼手速打地鼠
 * 整合版本：适配最新 RPC 定义
 */
public class WhackMole {
    private static final String TAG = "WhackMole";
    private static final String SOURCE = "senlinguangchangdadishu";
    private static final String EXEC_FLAG = "forest::whackMole::executed";
    private static final String FAIL_FLAG = EXEC_FLAG + "::fail";
    /** 今日失败次数上限：失败不锁全天，但也不能无限重发请求 */
    private static final int MAX_FAILURES = 3;
    
    private static volatile int totalGames = 5;
    private static volatile int moleCount = 15; // 单局击打次数上限，<=0 表示不限制
    private static final long ROUND_DURATION_MS = 6000L; // 单局时长：「6秒拼手速」
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * 2,
            Runtime.getRuntime().availableProcessors() * 2,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> new Thread(r, "WhackMole-Worker")
    );
    private static volatile boolean isRunning = false;
    
    /**
     * 运行模式
     */
    public enum Mode {
        COMPATIBLE,  // 兼容模式 (对应 old系列 RPC)
        AGGRESSIVE   // 激进模式 (对应 标准系列 RPC)
    }
    
    /**
     * 游戏会话信息
     */
    public static class GameSession {
        private final String token;
        private final int roundNumber;
        /** 本局服务端下发的地鼠 id，按出现顺序排列 */
        private final List<Long> moleIds;
        /** 带 bubbleId 的地鼠：需要主动击打，能量随击打回执返回 */
        private final Set<Long> bubbleIds;
        /** 普通地鼠：不需要击打，结算时提交 */
        private final List<Long> settleIds;
        private final long startedAt;
        /** 本局时长与地鼠出现间隔，均取服务端配置 */
        private final long durationMs;
        private final long intervalMs;

        public GameSession(String token, int roundNumber, List<Long> moleIds, Set<Long> bubbleIds,
                           List<Long> settleIds, long startedAt, long durationMs, long intervalMs) {
            this.token = token;
            this.roundNumber = roundNumber;
            this.moleIds = moleIds;
            this.bubbleIds = bubbleIds;
            this.settleIds = settleIds;
            this.startedAt = startedAt;
            this.durationMs = durationMs;
            this.intervalMs = intervalMs;
        }

        public String getToken() {
            return token;
        }

        public int getRoundNumber() {
            return roundNumber;
        }

        public List<Long> getMoleIds() {
            return moleIds;
        }

        public Set<Long> getBubbleIds() {
            return bubbleIds;
        }

        public List<Long> getSettleIds() {
            return settleIds;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        /** 本局应当结算的时刻 */
        public long getSettlesAt() {
            return startedAt + durationMs;
        }
    }
    public static Boolean closeWhackMole() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.closeWhackMole());
            return MessageUtil.checkSuccess(TAG, jo);
        }
        catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return false;
    }
    /**
     * 设置总游戏局数
     */
    public static void setTotalGames(int games) {
        totalGames = games;
    }
    
    /**
     * 设置击打地鼠数量
     */
    public static void setMoleCount(int count) {
        moleCount = count;
    }
    
    /**
     * 异步启动打地鼠
     */
    public static void start(Mode mode) {
        EXECUTOR.submit(() -> startSuspend(mode));
    }
    
    /**
     * 同步启动打地鼠（对应Kotlin suspend函数）
     */
    public static void startSuspend(Mode mode) {
        if (isRunning) {
            Log.record("正在运行⏭️打地鼠游戏中，跳过重复启动");
            return;
        }
        // 今日已完成，或失败次数已达上限
        if (Status.hasFlagToday(EXEC_FLAG) || Status.getIntFlagToday(FAIL_FLAG) >= MAX_FAILURES) {
            return;
        }
        isRunning = true;

        boolean finished = false;
        try {
            switch (mode) {
                case COMPATIBLE:
                    finished = runCompatibleMode();
                    break;
                case AGGRESSIVE:
                    finished = runAggressiveMode();
                    break;
            }
        } catch (Exception e) {
            Log.printStackTrace("打地鼠异常:", e);
        } finally {
            isRunning = false;
        }
        if (finished) {
            Status.flagToday(EXEC_FLAG);
        } else {
            // 失败不直接锁全天，但累计到上限就停手，避免每轮重发整批请求
            int failures = Status.getIntFlagToday(FAIL_FLAG) + 1;
            Status.setIntFlagToday(FAIL_FLAG, failures);
            if (failures >= MAX_FAILURES) {
                Log.record("森林能量⏭️打地鼠今日已失败" + failures + "次，不再重试");
            }
        }
        Log.record("运行状态🎮打地鼠已重置");
    }
    
    // ================= [ 兼容模式：对应 old 系列 RPC ] =================
    private static boolean runCompatibleMode() {
        try {
            long startTs = System.currentTimeMillis();

            // 1. 开始游戏 (使用 oldstartWhackMole)
            String startRespStr = AntForestRpcCall.oldstartWhackMole(SOURCE);
            JSONObject response = new JSONObject(startRespStr);
            if (!response.optBoolean("success")) {
                Log.forest(response.optString("resultDesc", "开始失败"));
                return false;
            }
            JSONArray moleInfoArray = response.optJSONArray("moleInfo");
            String token = response.optString("token");
            if (moleInfoArray == null || moleInfoArray.length() == 0 || token.isEmpty()) {
                return false;
            }
            
            List<Long> allMoleIds = new ArrayList<>();
            List<Long> bubbleMoleIds = new ArrayList<>();
            for (int i = 0; i < moleInfoArray.length(); i++) {
                JSONObject mole = moleInfoArray.getJSONObject(i);
                long moleId = mole.getLong("id");
                allMoleIds.add(moleId);
                if (mole.has("bubbleId")) {
                    bubbleMoleIds.add(moleId);
                }
            }
            
            // 2. 打有能量球的地鼠 (使用 oldwhackMole)
            int hitCount = 0;
            int hitEnergy = 0;
            Random random = new Random();
            for (Long moleId : bubbleMoleIds) {
                try {
                    String whackRespStr = AntForestRpcCall.oldwhackMole(moleId, token, SOURCE);
                    JSONObject whackResp = new JSONObject(whackRespStr);
                    if (whackResp.optBoolean("success")) {
                        int energy = whackResp.optInt("energyAmount", 0);
                        hitCount++;
                        hitEnergy += energy;
                        Log.forest("森林能量⚡️[兼容打地鼠:" + moleId + "+" + energy + "g]");
                        if (hitCount < bubbleMoleIds.size()) {
                            Thread.sleep(100 + random.nextInt(201)); // 100-300ms 随机延迟
                        }
                    }
                } catch (Throwable t) {
                    // 忽略单个击打异常
                }
            }
            
            // 3. 计算剩余 ID 并结算 (使用 oldsettlementWhackMole)
            List<String> remainingIds = new ArrayList<>();
            for (Long moleId : allMoleIds) {
                if (!bubbleMoleIds.contains(moleId)) {
                    remainingIds.add(String.valueOf(moleId));
                    if (remainingIds.size() >= moleCount) {
                        break; // 限制击打数量
                    }
                }
            }
            
            // 等待至接近6秒时长
            long elapsedTime = System.currentTimeMillis() - startTs;
            long sleepTime = Math.max(0L, 6000L - elapsedTime - 200L);
            if (sleepTime > 0) {
                Thread.sleep(sleepTime);
            }
            
            // 执行结算
            String settleRespStr = AntForestRpcCall.oldsettlementWhackMole(token, remainingIds, SOURCE);
            JSONObject settleResp = new JSONObject(settleRespStr);
            if (!MessageUtil.checkSuccess(TAG, settleResp)) {
                return false;
            }
            int total = settleResp.optInt("totalEnergy", 0);
            Log.forest("森林能量⚡️[兼容模式完成(打" + (remainingIds.size() + hitCount) + "个)总能量+" + total + "g]");
            // 结算的 totalEnergy 与逐次击打的 energyAmount 是同一笔能量，只记一次
            int gained = Math.max(total, hitEnergy);
            if (gained > 0) {
                Statistics.addData(Statistics.DataType.COLLECTED, gained);
                Statistics.save();
            }
            return true;
        } catch (Throwable t) {
            Log.record("兼容模式出错:" + (t.getMessage() != null ? t.getMessage() : "未知错误"));
        }
        return false;
    }
    
    // ================= [ 激进模式：对应 标准系列 RPC ] =================
    /**
     * 逐局「开始 → 逐个 whackMole → 用本局下发的 id 结算」。
     * 旧实现只开多局、从不出手，结算提交写死的 1..20，等于白跑一轮。
     */
    private static boolean runAggressiveMode() {
        int totalEnergy = 0;
        int playedRounds = 0;
        int hitTotal = 0;
        for (int roundNum = 1; roundNum <= totalGames; roundNum++) {
            GameSession session = startSingleRound(roundNum);
            if (session == null) {
                // 开不了下一局（次数用尽/接口失败）：剩余轮次不再尝试
                break;
            }
            List<Long> hitIds = whackRound(session);
            hitTotal += hitIds.size();
            try {
                // 结算要等本局时长走完：提前提交服务端会认为游戏未结束
                long sleepTime = session.getSettlesAt() - System.currentTimeMillis();
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            int roundEnergy = settleStandardRound(session);
            totalEnergy += roundEnergy;
            playedRounds++;
            Log.i(TAG + ".round", "第" + roundNum + "局：可打" + session.getMoleIds().size()
                    + "个，击中" + hitIds.size() + "个，结算+" + roundEnergy + "g");
            if (roundEnergy <= 0) {
                // 结算没收益说明这一轮有问题（并非次数用尽），继续开新局只会白耗当天次数
                Log.i(TAG + ".round", "第" + roundNum + "局无收益，剩余轮次不再尝试");
                break;
            }
        }
        if (playedRounds > 0) {
            Log.forest("森林能量⚡️[激进模式" + playedRounds + "局/打" + hitTotal + "个#总计" + totalEnergy + "g]");
            if (totalEnergy > 0) {
                Statistics.addData(Statistics.DataType.COLLECTED, totalEnergy);
                Statistics.save();
            }
        }
        // 次数用尽或已拿到能量才算完成；一局都没开成/整轮无收益则返回 false，留给下一轮重试
        return totalEnergy > 0 || Status.hasFlagToday(EXEC_FLAG);
    }

    /**
     * 启动单局游戏（激进模式），同时解析本局地鼠列表供逐只击打
     */
    private static GameSession startSingleRound(int round) {
        try {
            String startRespStr = AntForestRpcCall.startWhackMole();
            JSONObject startResp = new JSONObject(startRespStr);
            if (!MessageUtil.checkSuccess(TAG, startResp)) {
                return null;
            }
            if (!startResp.optBoolean("canPlayToday", true)) {
                Status.flagToday(EXEC_FLAG);
                Log.record("森林能量⏭️打地鼠今日次数已用完");
                return null;
            }
            String token = startResp.optString("token", "");
            if (token.isEmpty()) {
                Log.i(TAG + ".start", "第" + round + "局未返回 token");
                return null;
            }
            JSONArray moleInfo = startResp.optJSONArray("moleInfo");
            if (moleInfo == null || moleInfo.length() == 0) {
                Log.i(TAG + ".start", "第" + round + "局未返回 moleInfo");
                return null;
            }
            // 时长与出现间隔以服务端配置为准，缺失时按 6 秒均分
            JSONObject config = startResp.optJSONObject("config");
            long durationMs = config != null ? config.optLong("duration", 0L) * 1000L : 0L;
            if (durationMs <= 0L) {
                durationMs = ROUND_DURATION_MS;
            }
            long intervalMs = config != null ? config.optLong("intervalTime", 0L) : 0L;
            if (intervalMs <= 0L) {
                intervalMs = Math.max(1L, durationMs / moleInfo.length());
            }
            List<Long> moleIds = new ArrayList<>(moleInfo.length());
            Set<Long> bubbleIds = new HashSet<>();
            List<Long> settleIds = new ArrayList<>();
            for (int i = 0; i < moleInfo.length(); i++) {
                JSONObject mole = moleInfo.optJSONObject(i);
                if (mole == null) {
                    continue;
                }
                long moleId = mole.optLong("id", 0L);
                if (moleId <= 0L) {
                    continue;
                }
                moleIds.add(moleId);
                if (mole.has("bubbleId")) {
                    bubbleIds.add(moleId);
                } else if ("NORMAL".equals(mole.optString("type", "NORMAL"))) {
                    // 普通地鼠不击打，能量靠结算提交
                    settleIds.add(moleId);
                }
            }
            if (moleIds.isEmpty()) {
                Log.i(TAG + ".start", "第" + round + "局未返回 moleInfo");
                return null;
            }
            return new GameSession(token, round, moleIds, bubbleIds, settleIds,
                    System.currentTimeMillis(), durationMs, intervalMs);
        } catch (Exception e) {
            Log.printStackTrace("启动第" + round + "局异常:", e);
            return null;
        }
    }

    /** 按服务端下发的出现时点击打冒泡地鼠，返回击打回执成功的 id（仅用于统计） */
    private static List<Long> whackRound(GameSession session) {
        List<Long> moleIds = session.getMoleIds();
        Set<Long> bubbleIds = session.getBubbleIds();
        List<Long> hitIds = new ArrayList<>();
        String firstFail = null;
        long settlesAt = session.getSettlesAt();
        int attempts = 0;
        for (int i = 0; i < moleIds.size() && !bubbleIds.isEmpty(); i++) {
            // 按出现时点对齐：在地鼠出现前发送会被服务端判为无效击打
            long appearsAt = session.getStartedAt() + i * session.getIntervalMs();
            if (appearsAt >= settlesAt) {
                break;
            }
            TimeUtil.sleep(Math.max(0L, appearsAt - System.currentTimeMillis()));
            if (System.currentTimeMillis() >= settlesAt) {
                break;
            }
            long moleId = moleIds.get(i);
            if (!bubbleIds.contains(moleId)) {
                continue;
            }
            if (moleCount > 0 && attempts >= moleCount) {
                break;
            }
            attempts++;
            try {
                String raw = AntForestRpcCall.whackMole(moleId, session.getToken(), SOURCE);
                JSONObject jo = new JSONObject(raw);
                if (jo.optBoolean("success") || jo.optBoolean("isSuccess")) {
                    hitIds.add(moleId);
                } else if (firstFail == null) {
                    firstFail = raw;
                }
            } catch (Throwable t) {
                // 单只失败不影响其余地鼠，但首个失败原因要留痕，否则整局 0 击中无从判断
                if (firstFail == null) {
                    firstFail = t.toString();
                }
            }
        }
        if (hitIds.isEmpty() && firstFail != null) {
            Log.i(TAG + ".whack", "第" + session.getRoundNumber() + "局全部未命中，首个响应: "
                    + StringUtil.truncate(firstFail, 200));
        }
        return hitIds;
    }

    /**
     * 结算单局游戏（激进模式）。
     * <p>提交普通地鼠 id：冒泡地鼠的能量已随击打回执发放，再提交不会重复计能量；
     * 结算与击打回执无关，回执全失败也照常结算。
     */
    private static int settleStandardRound(GameSession session) {
        List<Long> settleIds = session.getSettleIds();
        if (settleIds.isEmpty()) {
            settleIds = session.getMoleIds();
        }
        if (settleIds.isEmpty()) {
            Log.i(TAG + ".settlement", "第" + session.getRoundNumber() + "局没有可结算的地鼠，跳过结算");
            return 0;
        }
        try {
            List<String> ids = new ArrayList<>(settleIds.size());
            for (Long id : settleIds) {
                ids.add(String.valueOf(id));
            }
            String respStr = AntForestRpcCall.settlementWhackMole(session.getToken(), ids, SOURCE);
            JSONObject resp = new JSONObject(respStr);
            if (MessageUtil.checkSuccess(TAG, resp)) {
                return resp.optInt("totalEnergy", 0);
            }
            // 结算被拒时留原始响应，便于区分限流与 id 无效
            Log.i(TAG + ".settlement", "第" + session.getRoundNumber() + "局结算未成功: "
                    + StringUtil.truncate(respStr, 200));
        } catch (Exception e) {
            Log.printStackTrace("结算第" + session.getRoundNumber() + "局失败:", e);
        }
        return 0;
    }
    
    /**
     * 关闭线程池（建议在应用退出时调用）
     */
    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}