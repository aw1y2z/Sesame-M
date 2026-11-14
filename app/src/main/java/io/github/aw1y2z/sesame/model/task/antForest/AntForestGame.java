package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ChoiceModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy.Outcome;
import io.github.aw1y2z.sesame.entity.AlipayUser;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.model.task.antFarm.AntFarm.TaskStatus;
import io.github.aw1y2z.sesame.model.task.antGame.GameTask;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 森林乐园：6 秒拼手速、能量雨（含森林救援队上报）、乐园宝箱与乐园限定任务。
 */
public final class AntForestGame {

    private static final String TAG = AntForestV2.class.getSimpleName();

    /**
     * 能量雨各来源的结算克数，五者互不占额度。
     * <p>{@code canPlayToday} 五者共用，只表示「还能再开一局」，表示不了「是谁的额度」；
     * 游戏局恒为 {@value #ENERGY_RAIN_BY_GAME}g，是救援队的完成凭据。
     * <p>受赠局由好友随时送来，不能假设一轮开完就没了：每轮都须复查 {@code canPlayToday}。
     */
    private static final int ENERGY_RAIN_NORMAL = 120;
    private static final int ENERGY_RAIN_CARD_OR_GRANT = 70;
    private static final int ENERGY_RAIN_BY_GAME = 50;

    /** 救援队上报后等待游戏局额度下发：服务端异步推进，轮询几轮 */
    private static final long ENERGY_RAIN_GRANT_INTERVAL_MS = 3000L;
    private static final int ENERGY_RAIN_GRANT_ROUNDS = 3;

    /** 一轮最多开几局：五来源各一次，余一局防受赠叠加 */
    private static final int ENERGY_RAIN_MAX_ROUNDS_PER_RUN = 6;

    /**
     * 救援队当日标记：上报（含 4 次外网直连）只做一次。
     * <p>与 {@code EnergyRain::PlayGame}（已拿到满额一局）语义不同。上报成功即置位：
     * 状态推进是异步的，没确认也必须置位，否则每轮重发外网请求。
     */
    private static final String SLJYD_FLAG = "forest::sljydReported";

    /** 任务项已从列表移除：表示已完成，勿当失败重试 */
    private static final String SLJYD_MISSING = "MISSING";

    /** 救援队上报后的状态回读轮询：只作旁证，命中非待办即返回 */
    private static final long SLJYD_PROBE_INTERVAL_MS = 2000L;
    private static final int SLJYD_PROBE_ROUNDS = 2;

    /** 「未取到任务状态」一天只提示一次：重试照旧，但不每轮重复打同一条 */
    private static final String SLJYD_NO_STATUS_FLAG = "forest::sljydNoStatus";

    /**
     * 限时能量雨次卡（LIMIT_TIME_ENERGY_RAIN_CHANCE，限时 3 天内使用）。
     * <p>兑换是「开一局能量雨卡额度」的前置：兑换能力在主类（依赖商品清单缓存与拉黑逻辑），
     * 具体兑换哪个 SKU 属于能量雨业务，故由本模块决定、主类只提供兑换动作。
     */
    private static final String ENERGY_RAIN_CARD_SKU = "SK20250117005985";

    private static BooleanModelField drawGameCenterAward;
    private static ChoiceModelField whackModeName;
    private static IntegerModelField whackModeGames;
    private static IntegerModelField whackModeCount;
    private static IntegerModelField earliestwhackMoleTime;
    private static BooleanModelField energyRain;
    private static SelectModelField giveEnergyRainList;
    private static BooleanModelField useEnergyRainLimit;

    private AntForestGame() {
    }

    /** 注册「森林乐园 | 游戏宝箱」，位置与原 getFields() 中一致。 */
    public static void addGameCenterFields(ModelFields modelFields) {
        modelFields.addField(drawGameCenterAward = new BooleanModelField("drawGameCenterAward", "森林乐园 | 游戏宝箱", true));
    }

    /** 注册「6秒拼手速」相关配置，位置与原 getFields() 中一致。 */
    public static void addWhackMoleFields(ModelFields modelFields) {
        modelFields.addField(whackModeName = new ChoiceModelField("whackModeName", "6秒拼手速 | 运行模式", whackModeNames.CLOSE, whackModeNames.nickNames));
        modelFields.addField(whackModeGames = new IntegerModelField("whackModeGames", "6秒拼手速 | 激进模式局数", 5).setDependsOn("whackModeName"));
        modelFields.addField(whackModeCount = new IntegerModelField("whackModeCount", "6秒拼手速 | 兼容模式击打数", 15).setDependsOn("whackModeName"));
        modelFields.addField(earliestwhackMoleTime = new IntegerModelField("earliestwhackMoleTime", "6秒拼手速 | 最早执行(24小时制)", 8, 0, 23).setDependsOn("whackModeName"));
    }

    /** 注册能量雨相关配置，位置与原 getFields() 中一致。 */
    public static void addEnergyRainFields(ModelFields modelFields) {
        modelFields.addField(energyRain = new BooleanModelField("energyRain", "收集能量雨", false));
        modelFields.addField(giveEnergyRainList = new SelectModelField("giveEnergyRainList", "赠送能量雨好友列表", new LinkedHashSet<>(), AlipayUser::getList).setDependsOn("energyRain"));
        modelFields.addField(useEnergyRainLimit = new BooleanModelField("useEnergyRainLimit", "兑换使用限时能量雨卡", false));
    }

    /** 拼手速是否为「关闭」模式：主类据此决定要不要关掉服务端的强制弹窗。 */
    public static boolean isWhackMoleClosed() {
        return whackModeName == null || whackModeName.getValue() == whackModeNames.CLOSE;
    }

    /** 能量雨开关 */
    public static boolean isEnergyRainEnabled() {
        return energyRain != null && Boolean.TRUE.equals(energyRain.getValue());
    }

    /**
     * 检查并处理6秒拼手速逻辑（每天主动执行一次）
     */
    public static void whackMole() {
        try {
            if (whackModeName.getValue() == whackModeNames.CLOSE) {
                // 检查今天是否已执行过打地鼠
                if (Status.hasFlagToday("forest::whackMole::executed")) {
                    Log.record("⏭️ 今天已完成过6秒拼手速，跳过执行");
                } else {
                    // 主动执行打地鼠（今日首次）
                    Log.record("🎮 开始执行6秒拼手速（今日首次）");
                    checkAndHandleWhackMole();
                    Status.flagToday("forest::whackMole::executed");
                    Log.record("✅ 6秒拼手速已完成，今天不再执行");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "whackMole err:", t);
        }
    }

    public static void checkAndHandleWhackMole() {
        try {
            // 获取当前选择的索引 (0, 1, 或 2)
            int modeIndex = (whackModeName != null) ? whackModeName.getValue() : 0;

            // 如果索引为 0 (关闭)，直接返回
            if (modeIndex == 0) {
                return;
            }

            // 检查执行时间
            int hour = Integer.parseInt(Log.getFormatTime().split(":")[0]);
            if (hour >= earliestwhackMoleTime.getValue()) {
                String whackMoleFlag = "forest::whackMole::executed";
                if (Status.hasFlagToday(whackMoleFlag)) {
                    return;
                }

                // 根据索引匹配模式
                switch (modeIndex) {
                    case 1: // 兼容模式
                        Log.record("触发任务🎮拼手速:兼容模式");
                        WhackMole.setTotalGames(1);
                        int defaultMoleCount = (whackModeCount != null) ? whackModeCount.getValue() : 15;
                        WhackMole.setMoleCount(defaultMoleCount);
                        WhackMole.start(WhackMole.Mode.COMPATIBLE);
                        break;

                    case 2: // 激进模式
                        Log.record("触发任务🎮拼手速:激进模式");
                        int configGames = (whackModeGames != null) ? whackModeGames.getValue() : 5;
                        WhackMole.setTotalGames(configGames);
                        WhackMole.start(WhackMole.Mode.AGGRESSIVE);
                        break;
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 开一局能量雨并结算。
     *
     * <p>只有 50g 的游戏局能证明救援队已换到额度；任务列表回读不可作判据（上报异步，回读必然抢跑）。
     *
     * @return 结算成功的克数；失败返回 {@code -1}
     */
    public static int startEnergyRain() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.startEnergyRain());
            TimeUtil.sleep(500);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return -1;
            }
            String token = jo.getString("token");
            JSONArray bubbleEnergyList = jo.getJSONObject("difficultyInfo").getJSONArray("bubbleEnergyList");
            int sum = 0;
            for (int i = 0; i < bubbleEnergyList.length(); i++) {
                sum += bubbleEnergyList.getInt(i);
            }
            // 结算参数无点击轨迹，时长是唯一能体现不是机器的特征，故随机化
            TimeUtil.sleep(5000L + (long) (Math.random() * 5000L));
            jo = new JSONObject(AntForestRpcCall.energyRainSettlement(sum, token));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return -1;
            }
            Toast.show("获得了[" + sum + "g]能量[能量雨]");
            Log.forest("收能量雨🌧️" + energyRainLabel(sum) + "[" + sum + "g]");
            AntForestV2.addTotalCollected(sum);
            Statistics.addData(Statistics.DataType.COLLECTED, sum);
            TimeUtil.sleep(500);
            // 游戏局即救援队已换到额度，此标记只能由此处置位
            if (sum == ENERGY_RAIN_BY_GAME) {
                Status.flagToday("EnergyRain::PlayGame");
            }
            return sum;
        } catch (Throwable th) {
            Log.err(TAG, "startEnergyRain err:", th);
        }
        return -1;
    }

    /** 按克数标注能量雨局（仅日志用）；70g 三来源同值，合并标注不臆造 */
    private static String energyRainLabel(int sum) {
        if (sum == ENERGY_RAIN_NORMAL) {
            return "(每日免费局)";
        }
        if (sum == ENERGY_RAIN_CARD_OR_GRANT) {
            return "(70g局)";
        }
        if (sum == ENERGY_RAIN_BY_GAME) {
            return "(救援队游戏局)";
        }
        return "";
    }

    /**
     * 兑换并使用限时能量雨卡：能量雨的前置额度，开了开关才做。
     *
     * <p>兑换动作由主类注入——它依赖商品清单缓存与拉黑逻辑，属于主类的兑换能力；
     * 兑换哪个 SKU、换到卡后怎么用，才是能量雨自己的事。
     *
     * @param exchangeCard 按 skuId 兑换，返回是否成功；主类传 {@code this::exchangeBenefit}
     */
    public static void useEnergyRainCard(Function<String, Boolean> exchangeCard) {
        if (useEnergyRainLimit == null || !Boolean.TRUE.equals(useEnergyRainLimit.getValue())) {
            return;
        }
        // 今日兑换已达上限/停兑：不再空转兑换与背包轮询
        if (Boolean.TRUE.equals(Status.hasFlagToday("forest::exchangeLimit::" + ENERGY_RAIN_CARD_SKU))) {
            return;
        }
        if (exchangeCard == null) {
            return;
        }
        try {
            // 商店兑换 限时能量雨卡
            exchangeCard.apply(ENERGY_RAIN_CARD_SKU);
            TimeUtil.sleep(2000);
            JSONObject jo;
            do {
                TimeUtil.sleep(1000);
                // 背包查找 能量雨卡
                jo = null;
                List<JSONObject> list = AntForestProp.getPropGroup(AntForestProp.getForestPropVOList(), AntForestV2.PropGroup.energyRain.name());
                if (!list.isEmpty()) {
                    jo = list.get(0);
                }
                if (jo == null) {
                    break;
                }
                // 使用能量雨卡
            } while (AntForestProp.consumeProp(jo));
        } catch (Throwable th) {
            Log.err(TAG, "useEnergyRainCard err:", th);
        }
    }

    /**
     * 收集能量雨：先开尽当前额度，再送好友，最后上报救援队等游戏局额度。
     *
     * <p>受赠局由好友随时送来，故每轮都要先复查现有额度，不能因「今日已开满」而整体跳过。
     */
    public static void runEnergyRain() {
        if (!isEnergyRainEnabled()) {
            return;
        }
        try {
            // 1. 开掉当前额度（含受赠局、跨轮才生效的送出局）
            drainEnergyRainQuota();

            // 2. 赠送：送出后自己会得一次，额度同样走 canPlayToday
            JSONObject joEnergyRainHome = new JSONObject(AntForestRpcCall.queryEnergyRainHome());
            TimeUtil.sleep(500);
            if (MessageUtil.checkResultCode(TAG, joEnergyRainHome)
                    && joEnergyRainHome.getBoolean("canGrantStatus")) {
                Log.record("有送能量雨的机会");
                JSONObject joGrantList = new JSONObject(AntForestRpcCall.queryEnergyRainCanGrantList());
                TimeUtil.sleep(500);
                JSONArray grantInfos = joGrantList.optJSONArray("grantInfos");
                Set<String> set = giveEnergyRainList.getValue();
                boolean granted = false;
                for (int j = 0; grantInfos != null && j < grantInfos.length(); j++) {
                    JSONObject grantInfo = grantInfos.optJSONObject(j);
                    if (grantInfo == null || !grantInfo.optBoolean("canGrantedStatus", false)) {
                        continue;
                    }
                    String userId = grantInfo.optString("userId", "");
                    if (userId.isEmpty() || !set.contains(userId)) {
                        continue;
                    }
                    JSONObject joChance = new JSONObject(AntForestRpcCall.grantEnergyRainChance(userId));
                    TimeUtil.sleep(500);
                    granted = true;
                    // 列表里没有可赠送的好友时不赠送
                    if (MessageUtil.checkResultCode(TAG, joChance)) {
                        Log.forest("送能量雨🌧️[" + UserIdMap.getMaskName(userId) + "]");
                        // 送出换来的额度可能延迟生效，能开就开
                        drainEnergyRainQuota();
                    }
                    break;
                }
                if (!granted) {
                    Log.record("没有可以送的用户");
                }
            }

            // 3. 游戏局：今天已换到过就收工
            if (Status.hasFlagToday("EnergyRain::PlayGame")) {
                return;
            }
            // SLJYD_FLAG 只挡重复上报，不挡「把已下发的额度开掉」
            if (!Status.hasFlagToday(SLJYD_FLAG)) {
                checkAndDoEndGameTask();
                TimeUtil.sleep(4000);
            }
            // 额度异步下发，轮询等它出现；没等到也不打标记，下一轮再来
            for (int round = 0; round < ENERGY_RAIN_GRANT_ROUNDS; round++) {
                JSONObject jo = new JSONObject(AntForestRpcCall.queryEnergyRainHome());
                if (MessageUtil.checkResultCode(TAG, jo) && jo.getBoolean("canPlayToday")) {
                    drainEnergyRainQuota();
                    return;
                }
                TimeUtil.sleep(ENERGY_RAIN_GRANT_INTERVAL_MS);
            }
            Log.record("能量雨🌧️游戏局额度未下发#下次运行重试");
        } catch (Throwable th) {
            Log.err(TAG, "energyRain err:", th);
        }
    }

    /** 开尽当前能量雨额度：{@code canPlayToday} 为真就开一局，直到服务端说没有 */
    private static void drainEnergyRainQuota() {
        try {
            for (int round = 0; round < ENERGY_RAIN_MAX_ROUNDS_PER_RUN; round++) {
                JSONObject jo = new JSONObject(AntForestRpcCall.queryEnergyRainHome());
                if (!MessageUtil.checkResultCode(TAG, jo) || !jo.getBoolean("canPlayToday")) {
                    return;
                }
                if (startEnergyRain() < 0) {
                    // 开局/结算失败：额度没消耗掉，再问也是同样结果，直接交给下一轮
                    return;
                }
                // 局间留随机间隔，否则连续开局是明显的机器节奏
                TimeUtil.sleep(1000L + (long) (Math.random() * 4000L));
            }
        } catch (Throwable th) {
            Log.err(TAG, "drainEnergyRainQuota err:", th);
        }
    }

    /**
     * 回读森林救援队（{@code GAME_DONE_SLJYD}）的任务状态。
     *
     * <p>返回 {@link #SLJYD_MISSING} 表示响应里已无此项——整组消失，或任务组还在但任务项被摘掉，
     * 都是服务端完成后移除的结果，属「已完成」的证据，勿当失败重试；
     * {@code null} 表示响应残缺（接口失败、字段缺失），无从判断。
     */
    private static String queryEnergyRainSljydStatus() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryEnergyRainEndGameList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return null;
            }
            if (!jo.has("energyRainEndGameGroupTask")) {
                // 连任务组都没有：要么今天没有这项，要么就是已经做完被整组摘掉
                return SLJYD_MISSING;
            }
            JSONObject groupTask = jo.optJSONObject("energyRainEndGameGroupTask");
            JSONArray taskInfoList = groupTask != null ? groupTask.optJSONArray("taskInfoList") : null;
            if (taskInfoList == null) {
                return null;
            }
            for (int i = 0; i < taskInfoList.length(); i++) {
                JSONObject task = taskInfoList.optJSONObject(i);
                JSONObject baseInfo = task != null ? task.optJSONObject("taskBaseInfo") : null;
                if (baseInfo == null) {
                    continue;
                }
                if ("GAME_DONE_SLJYD".equals(baseInfo.optString("taskType"))) {
                    return baseInfo.optString("taskStatus");
                }
            }
            // 任务组还在、但列表里已没有这一项：与「整组消失」同理，是完成后被摘掉的证据，不能当残缺重试
            return SLJYD_MISSING;
        } catch (Throwable t) {
            Log.err(TAG, "queryEnergyRainSljydStatus err:", t);
        }
        return null;
    }

    /**
     * 救援队是否已达成：任务项被移除、或状态不在待办/未触发，都算已完成。
     * <p>{@code null}（响应残缺）不作已完成，留待下次判断。
     */
    private static boolean isSljydDone(String status) {
        return SLJYD_MISSING.equals(status)
                || (status != null && !"TODO".equals(status) && !"NOT_TRIGGER".equals(status));
    }

    public static void checkAndDoEndGameTask() {
        try {
            // 1. 查询游戏任务列表
            String response = AntForestRpcCall.queryEnergyRainEndGameList();
            JSONObject jo = new JSONObject(response);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            boolean needInit = jo.optBoolean("needInitTask", false);
            if (!jo.has("energyRainEndGameGroupTask") && !needInit) {
                // 无任务组且未要求接入 = 已做完或今天没下发，置位以免每轮重复查
                Status.flagToday(SLJYD_FLAG);
                Log.record("森林救援队🐱无未完结任务组#今日跳过");
                return;
            }

            // 2. 初始化新任务（需要接入森林救援队）
            if (needInit) {
                Log.record("检测到新任务，准备接入[森林救援队]...");
                String initResStr = AntForestRpcCall.initTask("GAME_DONE_SLJYD");
                JSONObject initRes = new JSONObject(initResStr);
                if (!MessageUtil.checkResultCode(TAG, initRes)) {
                    return;
                }
            }

            // 3. 状态未完结才上报，避免每轮重复发起外部请求
            String sljydStatus = queryEnergyRainSljydStatus();
            // 刚 initTask 的一轮，「列表里没有」只说明服务端还没落库，不能当已完成
            if (!needInit && SLJYD_MISSING.equals(sljydStatus)) {
                Status.flagToday(SLJYD_FLAG);
                Log.record("森林救援队🐱已完结#任务项已从列表移除");
                return;
            }

            boolean shouldReport = needInit
                    || "TODO".equals(sljydStatus)
                    || "NOT_TRIGGER".equals(sljydStatus);

            if (shouldReport) {
                // 每发一次就回读一次状态：达成所需次数协议未给出，以真实推进为准、不按固定次数硬发。
                // 必须同步——异步版会让回读抢在请求之前，读到旧状态。
                final String[] after = {sljydStatus};
                int successes = GameTask.Forest_sljyd.reportSync("森林救援队", 1, null, () -> {
                    TimeUtil.sleep(SLJYD_PROBE_INTERVAL_MS);
                    after[0] = queryEnergyRainSljydStatus();
                    return isSljydDone(after[0]);
                });
                if (successes <= 0) {
                    // 一次都没发出去，不打标记，下一轮可再试
                    Log.record("森林救援队🐱上报失败#下次运行重试");
                    return;
                }
                // 上报发出即置位：状态推进是异步的，没确认也不能每轮重发外网请求
                Status.flagToday(SLJYD_FLAG);
                if (isSljydDone(after[0])) {
                    Log.record("森林救援队🐱已完成[" + successes + "次上报](状态:" + after[0] + ")");
                } else {
                    // 未推进时继续轮询几轮作旁证，不额外发请求
                    for (int round = 0; round < SLJYD_PROBE_ROUNDS; round++) {
                        TimeUtil.sleep(SLJYD_PROBE_INTERVAL_MS);
                        after[0] = queryEnergyRainSljydStatus();
                        if (isSljydDone(after[0])) {
                            break;
                        }
                    }
                    Log.i(TAG, "森林救援队🐱上报已发出[" + successes + "次]，状态暂未推进(" + after[0] + ")");
                }
                return;
            }
            if (sljydStatus == null) {
                // 响应残缺（接口失败/字段缺失），判断不了状态：不置完成标记，下一轮继续试，但一天只提示一次
                if (!Boolean.TRUE.equals(Status.hasFlagToday(SLJYD_NO_STATUS_FLAG))) {
                    Status.flagToday(SLJYD_NO_STATUS_FLAG);
                    Log.record("森林救援队🐱未取到任务状态#下次运行重试");
                }
                return;
            }
            Log.record("森林救援队🐱无需上报(状态:" + sljydStatus + ")，今日跳过");
            Status.flagToday(SLJYD_FLAG);
        } catch (Throwable th) {
            Log.printStackTrace("执行能量雨后续任务出错:", th);
        }
    }

    /**
     * 森林乐园：先开宝箱、再刷任务次数，最后处理乐园限定任务。
     *
     * @param blackList 环境任务黑名单（原类里的 {@code AntForestVitalityTaskList}）
     */
    public static void runGameCenter(Set<String> blackList) {
        if (drawGameCenterAward == null || !Boolean.TRUE.equals(drawGameCenterAward.getValue())) {
            return;
        }
        doforestgame();
        queryOptionalPlay(blackList);
    }

    public static void doforestgame() {
        try {
            String response = AntForestRpcCall.queryGameList();
            JSONObject jo = new JSONObject(response);

            // 验证请求是否成功
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                Log.error("queryGameList 失败: " + jo.optString("desc"));
                return;
            }

            JSONObject drawRights = jo.optJSONObject("gameCenterDrawRights");
            if (drawRights != null) {
                int perTime = drawRights.optInt("quotaPerTime", 100);

                // 换算实际宝箱次数
                int canUseCount = drawRights.optInt("quotaCanUse") / perTime;
                int limitCount = drawRights.optInt("quotaLimit") / perTime;
                int usedCount = drawRights.optInt("usedQuota") / perTime;

                // 1. 处理待开启奖励 (批量开启)
                if (canUseCount > 0) {
                    Log.record("森林乐园正在一次性开启 " + canUseCount + " 个宝箱...");
                    JSONObject drawJo = new JSONObject(AntForestRpcCall.drawGameCenterAward(canUseCount));
                    if (!MessageUtil.checkResultCode(drawJo)) {
                        return;
                    }
                    JSONArray awardList = drawJo.optJSONArray("gameCenterDrawAwardList");
                    int totalEnergy = 0;
                    List<String> otherAwards = new ArrayList<>();

                    if (awardList != null) {
                        for (int i = 0; i < awardList.length(); i++) {
                            JSONObject award = awardList.getJSONObject(i);
                            String type = award.optString("awardType");
                            String name = award.optString("awardName");
                            int count = award.optInt("awardCount");
                            Log.forest("森林乐园🎁开宝箱得[" + name + "*" + count + "]");
                            if ("ENERGY".equals(type)) {
                                totalEnergy += count;
                            } else {
                                otherAwards.add(name + "x" + count);
                            }
                        }
                    }
                    Statistics.addData(Statistics.DataType.COLLECTED, totalEnergy);
                    // 输出统计结果
                    StringBuilder logMsg = new StringBuilder("森林乐园🎁[开宝箱]共计");
                    if (totalEnergy > 0) {
                        logMsg.append("获得能量").append(totalEnergy).append("g");
                    }
                    if (!otherAwards.isEmpty()) {
                        if (totalEnergy > 0) {
                            logMsg.append(", ");
                        }
                        logMsg.append("其他: ").append(String.join("/", otherAwards));
                    }
                    Log.forest(logMsg.toString());
                    Toast.show(logMsg.toString());

                }

                // 2. 判断是否需要刷任务
                int remainToTask = limitCount - usedCount;
                if (remainToTask > 0) {
                    GameTask.Forest_slxcc.report("森林", remainToTask);
                } else {
                    Log.record("今日森林乐园游戏任务已满额");
                }
            }

        } catch (CancellationException e) {
            throw e;
        } catch (Throwable t) {
            Log.printStackTrace("doforestgame 流程异常", t);
        }
    }

    //乐园限定活动（bizType=ANTFOREST / sceneCode=ANTFOREST_COMMON）
    //奖励类型以能量为主，场景码 ANTFOREST_LEYUAN_DAILY_TASK
    public static void queryOptionalPlay(Set<String> blackList) {
        try {
            // 每个任务的每个阶段（taskType#rightsTimes）本轮最多动作一次：同一阶段重复动作会白跑，
            // 但阶段推进（领取后 rightsTimes 递增）要允许再做，故去重键带序号。
            Set<String> attempted = new HashSet<>();
            int round = 0;
            // 首轮按实际任务阶段数算定上限（见 optionalPlayRoundLimit），之后不再变
            int roundLimit = 0;
            while (round++ < roundLimit || roundLimit == 0) {
                JSONObject jo = new JSONObject(AntForestRpcCall.queryOptionalPlay());
                if (!MessageUtil.checkSuccess(TAG, jo)) {
                    Log.forest("森林乐园⚠️任务列表拉取失败[" + TaskAlternative.describe(jo) + "]");
                    return;
                }
                if (!jo.has("taskTriggerPlayInfo")) {
                    Log.forest("森林乐园⚠️任务列表缺少任务信息");
                    return;
                }
                JSONObject taskTriggerPlayInfo = jo.optJSONObject("taskTriggerPlayInfo");
                if (taskTriggerPlayInfo == null || !taskTriggerPlayInfo.has("taskList")) {
                    Log.forest("森林乐园⚠️任务列表缺少任务明细");
                    return;
                }
                JSONArray taskList = taskTriggerPlayInfo.getJSONArray("taskList");
                // 账号当前没有乐园限定任务，直接收工
                if (taskList.length() == 0) {
                    Log.forest("森林乐园🎈当前无限定任务");
                    return;
                }
                if (roundLimit == 0) {
                    roundLimit = optionalPlayRoundLimit(taskList);
                }
                // 本轮是否还有新的动作（不含已试过的），没有就收工
                boolean progressed = false;

                for (int j = 0; j < taskList.length(); j++) {
                    JSONObject task = taskList.getJSONObject(j);
                    String taskStatus = task.getString("taskStatus");
                    int alreadyReceiveAwardCount = task.optInt("alreadyReceiveAwardCount");
                    int awardCount = task.optInt("awardCount");
                    int awardCountForReceive = awardCount - alreadyReceiveAwardCount;
                    int rightsTimesLimit = task.optInt("rightsTimesLimit");
                    int rightsTimes = task.optInt("rightsTimes");
                    String awardType = task.optString("awardType", "能量");
                    JSONObject bizInfo = task.optJSONObject("bizInfo");
                    if (bizInfo == null) {
                        continue;
                    }
                    String title = bizInfo.optString("title");
                    String source = task.optString("source", "ch_appcenter__chsub_9patch");
                    String sceneCode = task.optString("sceneCode", "");
                    String taskType = task.optString("taskType", "");
                    String groupId = task.optString("groupId", "");
                    // 开箱类任务的完成条件是**服务端的开箱计数**，申报完成不会让计数推进
                    // （申报只会被拒，伪申报被拒后又会被判"兜底未生效"拉黑），只能真去开箱
                    String actionType = bizInfo.optString("actionType", "");
                    // 去重键带轮次与阶段序号：同一轮内同阶段只动作一次（防重复请求），
                    // 但下一轮可再试——服务端状态推进有延迟，跨轮仍可能落到新阶段
                    String attemptKey = taskType + "#" + rightsTimes + "@" + round;
                    // 领奖去重**不带轮次**且**跨调用生效**：发奖是幂等的，服务端对已领过的任务仍会成功
                    // 返回同一个 incAwardCount；只按轮次或单次调用去重会让重跑的每一轮都重复领一次、
                    // 把同一笔能量反复记账。故按"任务+阶段"打当日标记，一天只领一次。
                    String awardKey = "forest::optionalplay::award::" + sceneCode + "/" + taskType
                            + "#" + rightsTimes;

                    // 黑名单任务跳过
                    if (blackList != null && blackList.contains(title)) {
                        continue;
                    }
                    // 底线：交易/支付/充值类一律不申报、一次即永久拉黑，绝不伪造。
                    // 必须同时查 groupId：充值类的 taskType 常无关键词，判据在 groupId（cz=充值）
                    if (TaskAlternative.isTransactionTask(taskType)
                            || TaskAlternative.isTransactionTask(groupId)
                            || TaskAlternative.isTransactionTask(sceneCode)) {
                        // 同一轮只拉一次：该分支不做去重的话，多轮重拉会把同一批任务反复写盘并刷屏
                        if (attempted.add(AntForestV2.blackTaskKey(title))) {
                            MessageUtil.MarkTaskBlackListPermanent("AntForestV2", "AntForestVitalityTaskList",
                                    "森林乐园限定任务", AntForestV2.blackTaskKey(title));
                            Log.forest("森林乐园⏭️交易/履约类[" + title + "]#不申报，已永久拉黑");
                        }
                        continue;
                    }

                    // 已完成待领：领奖。已领满（awardCountForReceive<=0）不再重复请求；
                    // 当日已领过的同一阶段也不再重复请求（见 awardKey 注释：重复领会造成统计虚高）
                    if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                        if (awardCountForReceive > 0 && !Status.hasFlagToday(awardKey)) {
                            progressed = true;
                            JSONObject joReceived = new JSONObject(AntForestRpcCall.receiveTaskAwardopengreen(source, sceneCode, taskType));
                            if (MessageUtil.checkSuccess(TAG, joReceived)) {
                                Status.flagToday(awardKey);
                                int incAwardCount = joReceived.optInt("incAwardCount");
                                JSONObject taskConfigResultVO = joReceived.optJSONObject("taskConfigResultVO");
                                if (taskConfigResultVO != null) {
                                    awardType = taskConfigResultVO.optString("awardType", awardType);
                                }
                                // 能量统计
                                if ("能量".equals(awardType) || "ENERGY".equals(awardType)) {
                                    Log.forest("森林乐园🎖️领取[" + title + "]奖励[" + incAwardCount + "g能量]");
                                    Statistics.addData(Statistics.DataType.COLLECTED, incAwardCount);
                                } else {
                                    Log.forest("森林乐园🎖️领取[" + title + "]奖励[" + awardType + "*" + incAwardCount + "]");
                                }
                            }
                        }
                        continue;
                    }

                    // 待完成 / 多阶段未满：上报完成
                    if (TaskStatus.TODO.name().equals(taskStatus) || rightsTimes < rightsTimesLimit) {
                        if (!attempted.add(attemptKey)) {
                            continue;
                        }
                        String label = rightsTimesLimit > 1
                                ? title + "(" + (rightsTimes + 1) + "/" + rightsTimesLimit + ")" : title;
                        // key 必须带轮次：多次任务每推进一档都是**新的**尝试，不带轮次会让第一档打上的
                        // 当日标记把后续档位全挡下、任务停在第一档。单次任务保持原 key，语义不变。
                        String policyKey = "forest::optionalplay::" + sceneCode + "/" + taskType
                                + (rightsTimesLimit > 1 ? "#" + rightsTimes : "");
                        // 走统一策略：400000040 时由 TaskAttemptPolicy 代为 doFarmTask 伪申报，
                        // 开箱类任务的尝试动作 = 真去开箱（申报完成对它是无效动作）；
                        // 其余任务仍走 finishTask 上报
                        final boolean unwrapTask = isUnwrapAction(actionType);
                        if (unwrapTask) {
                            // 开箱类任务（如"开10个乐园宝箱"）的完成条件是服务端累计开箱计数，由 doforestgame()
                            // 统一开箱（本方法之前已跑、用尽当天额度）；这里若再开必然读到 0 箱子、每轮重拉都刷
                            // "暂无可开宝箱"。未达满额时直接交给次日累计与上方 Finished 分支领奖即可。
                            continue;
                        }
                        Outcome outcome = TaskAttemptPolicy.handle(
                                policyKey, label, null,
                                () -> AntForestTask.attemptFinishTask(sceneCode, taskType, label), Log::forest,
                                new TaskAttemptPolicy.Site("AntForestVitalityTaskList", "森林乐园限定任务",
                                        taskType, sceneCode, TaskAlternative.DEFAULT_VERSION, null,
                                        (k) -> probeOptionalPlayStatus(sceneCode, taskType),
                                        (k) -> probeOptionalPlayExhausted(sceneCode, taskType),
                                        false).withScene("ANTFOREST"));
                        // 只有真正推进了才算"本轮有新动作"：被当日标记挡下（已试过）、临时故障（RETRY）
                        // 与做不了（UNABLE）都不该让外层重拉列表空转（与庄园一致）
                        if (outcome == Outcome.DONE || outcome == Outcome.TRIGGERED
                                || outcome == Outcome.AWARDED) {
                            progressed = true;
                        }
                    }
                }

                // 一整轮里既无可领也无可做：可重复任务已打满次数，收工
                if (!progressed) {
                    break;
                }
                // 服务端状态推进有延迟，留出落态时间再重拉
                TimeUtil.sleep(1500);
            }
        } catch (Throwable th) {
            Log.err(TAG, "queryOptionalPlay err:", th);
        }
    }

    /** 开箱类动作（累计开箱 N 个）：完成条件在服务端的开箱计数上，只能真开箱，申报无效 */
    private static boolean isUnwrapAction(String actionType) {
        return "ACC_UNWRAP".equals(actionType) || "UNWRAP".equals(actionType);
    }

    /**
     * 乐园限定任务单轮最大重拉次数：正常由「本轮无任何可执行/可领取动作」提前收工，
     * 此上限只防服务端恒回 TODO 时死循环。
     * <p>必须按**各任务阶段数之和**给足：每次重拉每个任务最多推进一档，多个多阶段任务
     * 串行推进，固定值会在任务没做满时就收工。
     */
    private static int optionalPlayRoundLimit(JSONArray taskList) {
        int total = 0;
        for (int i = 0; i < taskList.length(); i++) {
            JSONObject task = taskList.optJSONObject(i);
            if (task == null) {
                continue;
            }
            int limit = task.optInt("rightsTimesLimit", 1);
            total += Math.max(limit, 1);
        }
        // 至少 12，最多 60：给足阶段数，也防任务列表异常时长时间空转
        return Math.min(Math.max(total + 6, 12), 60);
    }

    /**
     * 乐园限定任务状态探针：重拉 {@code queryOptionalPlay} 列表（**该任务的归属列表**），
     * 而非森林任务列表——两者的 sceneCode 会重叠（都是 ANTFOREST），跨列表匹配会恒匹配不到
     * 而被当成 {@code GONE}（已消失=已完成），把"做不了"误判成"已完成"。
     */
    private static TaskAttemptPolicy.ProbeResult probeOptionalPlayStatus(String sceneCode, String taskType) {
        // 任务数组嵌在 taskTriggerPlayInfo 内层
        return TaskAttemptPolicy.probeByNestedList(TAG, AntForestRpcCall::queryOptionalPlay,
                jo -> MessageUtil.checkSuccess(TAG, jo),
                jo -> {
                    JSONObject playInfo = jo.optJSONObject("taskTriggerPlayInfo");
                    return playInfo == null ? null : playInfo.optJSONArray("taskList");
                },
                task -> taskType.equals(task.optString("taskType")) && sceneCode.equals(task.optString("sceneCode"))
                        ? task.optString("taskStatus") : null);
    }

    /** 打满判据：重拉乐园限定任务列表，读该任务的已做次数是否达上限。 */
    private static boolean probeOptionalPlayExhausted(String sceneCode, String taskType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryOptionalPlay());
            if (!MessageUtil.checkSuccess(TAG, jo) || !jo.has("taskTriggerPlayInfo")) {
                return false;
            }
            JSONObject playInfo = jo.optJSONObject("taskTriggerPlayInfo");
            JSONArray taskList = playInfo == null ? null : playInfo.optJSONArray("taskList");
            for (int i = 0; taskList != null && i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null || !taskType.equals(task.optString("taskType"))
                        || !sceneCode.equals(task.optString("sceneCode"))) {
                    continue;
                }
                int limit = task.optInt("rightsTimesLimit", 0);
                return limit > 0 && task.optInt("rightsTimes", 0) >= limit;
            }
        } catch (Throwable t) {
            Log.err(TAG, "probeOptionalPlayExhausted err:", t);
        }
        return false;
    }

    public interface whackModeNames {
        int CLOSE = 0;
        int WHACK_MODE_COMPATIBLE = 1;
        int WHACK_MODE_AGGRESSIVE = 2;
        String[] nickNames = {"关闭", "兼容模式", "激进模式"};
    }
}
