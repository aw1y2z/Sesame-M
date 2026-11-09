package io.github.aw1y2z.sesame.model.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.github.aw1y2z.sesame.model.task.antGame.GameTask;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.Status;

/**
 * 金豆乐园奖励处理：有抽奖次数时优先抽奖，次数用尽后按游戏权益做上报。
 * <p>
 * 每轮都重新拉取快照，只有服务端状态确实推进（抽奖次数变化或权益次数增加）才继续；
 * 若上报后状态没有变化，则记下当日跳过标记并结束，避免服务端不认账时反复请求。
 */
public final class GoldenBeansGameCenter {

    /** 收敛轮次上限，防止服务端状态回环导致死循环 */
    private static final int MAX_ROUND = 64;

    private GoldenBeansGameCenter() {
    }

    /**
     * @return 是否已无剩余可自动推进的乐园奖励
     */
    public static boolean run(int interval) {
        try {
            Set<String> attempted = new HashSet<>();
            Set<String> failed = new HashSet<>();
            for (int round = 0; round < MAX_ROUND; round++) {
                JSONObject snapshot = GoldenBeansSupport.parse(goldenbeansRpcCall.fetchGameList());
                if (!GoldenBeansSupport.ok(snapshot)) {
                    Log.goldenBeans("金豆乐园⚠️列表查询失败[" + GoldenBeansSupport.describe(snapshot) + "]");
                    return false;
                }
                JSONObject drawRights = GoldenBeansSupport.findObject(snapshot, "gameCenterDrawRights");
                int quotaCanUse = drawRights != null ? Math.max(drawRights.optInt("quotaCanUse", 0), 0) : 0;
                int usedQuota = drawRights != null ? Math.max(drawRights.optInt("usedQuota", 0), 0) : 0;
                int quotaLimit = drawRights != null ? Math.max(drawRights.optInt("quotaLimit", 0), 0) : 0;

                // 有可用抽奖次数：优先抽奖
                if (quotaCanUse > 0) {
                    GoldenBeansSupport.pause(interval);
                    JSONObject drawResponse = GoldenBeansSupport.parse(goldenbeansRpcCall.drawLottery());
                    if (!GoldenBeansSupport.ok(drawResponse)) {
                        Log.goldenBeans("金豆乐园⚠️抽奖失败[" + GoldenBeansSupport.describe(drawResponse) + "]");
                        return false;
                    }
                    JSONObject after = GoldenBeansSupport.parse(goldenbeansRpcCall.fetchGameList());
                    int afterQuota = quotaCanUse;
                    int afterUsed = usedQuota;
                    if (after != null) {
                        JSONObject afterRights = GoldenBeansSupport.findObject(after, "gameCenterDrawRights");
                        if (afterRights != null) {
                            afterQuota = Math.max(afterRights.optInt("quotaCanUse", quotaCanUse), 0);
                            afterUsed = Math.max(afterRights.optInt("usedQuota", usedQuota), 0);
                        }
                    }
                    if (afterQuota >= quotaCanUse && afterUsed <= usedQuota) {
                        Log.record("金豆乐园⚠️抽奖未确认#次数[" + usedQuota + "→" + afterUsed + "]");
                        return false;
                    }
                    Log.goldenBeans("金豆乐园🎰抽奖[第" + afterUsed + "/" + quotaLimit + "次]"
                            + GoldenBeansSupport.awardText(drawResponse));
                    continue;
                }

                // 无抽奖次数：尝试游戏权益上报
                int remainingDraws = Math.max(quotaLimit - usedQuota - quotaCanUse, 0);
                Map<String, GameCandidate> candidates = collect(snapshot);
                // 额度状态（可用/已用/上限）每轮恒定，候选明细属过程数据；不单独打，
                // 真正抽中时下面的「金豆乐园🎰抽奖[第N次]#获得…」才是结果
                GameCandidate candidate = null;
                for (GameCandidate item : candidates.values()) {
                    if (Status.hasFlagToday(skipFlag(item))) {
                        continue;
                    }
                    if ((item.hasPendingReward() || remainingDraws > 0)
                            && GameTask.matchAppId(item.appId) != null
                            && !failed.contains(item.key())
                            && !attempted.contains(item.key() + ":" + remainingDraws)) {
                        candidate = item;
                        break;
                    }
                }

                if (candidate == null) {
                    if (quotaLimit > 0 && usedQuota >= quotaLimit) {
                        Log.i(GoldenBeansSupport.TAG, "金豆乐园🎰今日次数已用尽[" + usedQuota + "/" + quotaLimit + "]");
                    } else {
                        Log.i(GoldenBeansSupport.TAG, "金豆乐园🎰无可自动推进项#次数[" + usedQuota + "/" + quotaLimit + "]");
                    }
                    return true;
                }

                attempted.add(candidate.key() + ":" + remainingDraws);
                GameTask gameTask = GameTask.matchAppId(candidate.appId);
                if (gameTask == null) {
                    continue;
                }
                int remaining = Math.max(candidate.remainingRewards(), remainingDraws);
                Log.goldenBeans("金豆乐园🎮游玩[" + gameTask.getTitle() + "]#目标[" + remaining + "]");
                GoldenBeansSupport.pause(interval);
                // 金豆乐园场景必须使用专用上报渠道 "goldenbean"，
                // 否则游戏服会接受上报（code=1）但支付宝侧权益不推进，表现为"砸蛋/游戏任务无效"
                int successes = gameTask.reportSync("金豆乐园:" + gameTask.getTitle(), remaining,
                        goldenbeansRpcCall.GAME_CHANNEL);
                if (successes <= 0) {
                    failed.add(candidate.key());
                    Log.record("金豆乐园⚠️[" + gameTask.getTitle() + "]上报失败#本轮跳过该游戏");
                    continue;
                }

                JSONObject after = GoldenBeansSupport.parse(goldenbeansRpcCall.fetchGameList());
                if (after == null) {
                    return false;
                }
                JSONObject afterRights = GoldenBeansSupport.findObject(after, "gameCenterDrawRights");
                int afterQuota = afterRights != null
                        ? Math.max(afterRights.optInt("quotaCanUse", quotaCanUse), 0) : quotaCanUse;
                int afterUsed = afterRights != null
                        ? Math.max(afterRights.optInt("usedQuota", usedQuota), 0) : usedQuota;
                GameCandidate afterCandidate = collect(after).get(candidate.key());
                boolean candidateProgressed = afterCandidate != null
                        && afterCandidate.rightTimes > candidate.rightTimes;
                boolean rightsProgressed = afterQuota > quotaCanUse || afterUsed > usedQuota;
                if (candidateProgressed || rightsProgressed) {
                    Log.goldenBeans("金豆乐园🎮[" + gameTask.getTitle() + "]权益["
                            + candidate.rightTimes + "→"
                            + (afterCandidate != null ? afterCandidate.rightTimes : candidate.rightTimes)
                            + "]#抽奖次数[" + quotaCanUse + "→" + afterQuota + "]");
                    continue;
                }
                Status.flagToday(skipFlag(candidate));
                Log.record("金豆乐园⚠️[" + gameTask.getTitle() + "]状态未推进#今日不再尝试该游戏");
                continue;
            }
            Log.record("金豆乐园⚠️达到收敛轮次上限[" + MAX_ROUND + "]");
            return false;
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "runGameCenterFlow err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
            return false;
        }
    }

    /** 上报后服务端未推进时的当日跳过标记，避免重复尝试同一个游戏权益 */
    private static String skipFlag(GameCandidate candidate) {
        return "goldenBeans::gameSkip::" + candidate.appId + ":" + candidate.taskId;
    }

    private static Map<String, GameCandidate> collect(JSONObject response) {
        Map<String, GameCandidate> candidates = new LinkedHashMap<>();
        scan(response, candidates);
        return candidates;
    }

    private static void scan(Object source, Map<String, GameCandidate> candidates) {
        if (source instanceof JSONObject) {
            JSONObject obj = (JSONObject) source;
            String appId = obj.optString("appId", "").trim();
            JSONArray benefits = obj.optJSONArray("deliveryBenefitList");
            if (!appId.isEmpty() && benefits != null) {
                for (int i = 0; i < benefits.length(); i++) {
                    JSONObject benefit = benefits.optJSONObject(i);
                    if (benefit == null
                            || !"IEP_REQUEST".equalsIgnoreCase(benefit.optString("benefitType", ""))) {
                        continue;
                    }
                    String tracer = benefit.optString("iepTaskTracer", "");
                    String taskId = benefit.optString("iepTaskId", "").trim();
                    if (taskId.isEmpty()) {
                        taskId = GoldenBeansSupport.tracerField(tracer, "taskType");
                    }
                    int rightTimesLimit = benefit.optInt("rightTimesLimit", 0);
                    if (taskId.isEmpty() || rightTimesLimit <= 0) {
                        continue;
                    }
                    String taskStatus = benefit.optString("taskStatus", "").trim();
                    if (taskStatus.isEmpty()) {
                        taskStatus = GoldenBeansSupport.tracerField(tracer, "taskStatus");
                    }
                    GameCandidate candidate = new GameCandidate();
                    candidate.appId = appId;
                    candidate.taskId = taskId;
                    candidate.taskStatus = taskStatus;
                    candidate.rightTimes = Math.max(benefit.optInt("rightTimes", 0), 0);
                    candidate.rightTimesLimit = rightTimesLimit;
                    if (!candidates.containsKey(candidate.key())) {
                        candidates.put(candidate.key(), candidate);
                    }
                }
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                scan(obj.opt(keys.next()), candidates);
            }
        } else if (source instanceof JSONArray) {
            JSONArray array = (JSONArray) source;
            for (int i = 0; i < array.length(); i++) {
                scan(array.opt(i), candidates);
            }
        }
    }

    /** 金豆乐园中可上报的游戏权益候选 */
    private static final class GameCandidate {
        private String appId = "";
        private String taskId = "";
        private String taskStatus = "";
        private int rightTimes;
        private int rightTimesLimit;

        private String key() {
            return appId + ":" + taskId;
        }

        private boolean hasPendingReward() {
            return !"RECEIVED".equalsIgnoreCase(taskStatus) && rightTimes < rightTimesLimit;
        }

        private int remainingRewards() {
            return Math.max(rightTimesLimit - rightTimes, 0);
        }
    }
}
