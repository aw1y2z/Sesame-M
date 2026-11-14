package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAward;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy.Outcome;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.ForestHuntIdMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

public class ForestChouChouLe {

    private static final String TAG = ForestChouChouLe.class.getSimpleName();

    /** 能量奖励的数值写在 prizeName 里（如 188g能量），prizeNum 只是份数 */
    private static final Pattern ENERGY_PRIZE_PATTERN = Pattern.compile("(\\d+)g能量");

    void chouChouLe(Boolean ForestHuntDraw, Boolean ForestHuntHelp, Set<String> shareIds, Boolean NORMALForestHuntHelp, Boolean ACTIVITYForestHuntHelp, Set<String> AntForestHuntTaskList) {
        try {
            ForestHuntIdMap.load();
            // String source = "task_entry";
            // String source = "guide";
            // String source = "forestchouchoule";
            JSONObject resData = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen("", "ANTFOREST_NORMAL_DRAW", "task_entry"));
            if (!MessageUtil.checkSuccess(TAG, resData)) {
                return;
            }
            // 提取drawSceneGroups数组
            JSONArray drawSceneGroups = resData.getJSONArray("drawSceneGroups");
            for (int i = 0; i < drawSceneGroups.length(); i++) {
                JSONObject drawScene = drawSceneGroups.getJSONObject(i);
                JSONObject drawActivity = drawScene.getJSONObject("drawActivity");

                String activityId = drawActivity.getString("activityId");
                String drawScenename = drawActivity.getString("name");
                String sceneCode = drawActivity.getString("sceneCode");

                chouChouLescene(ForestHuntDraw, activityId, drawScenename, sceneCode, ForestHuntHelp, shareIds, NORMALForestHuntHelp, ACTIVITYForestHuntHelp, AntForestHuntTaskList);
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    void chouChouLescene(Boolean ForestHuntDraw, String activityId, String drawScenename, String sceneCode, Boolean ForestHuntHelp, Set<String> shareIds, Boolean NORMALForestHuntHelp, Boolean ACTIVITYForestHuntHelp, Set<String> AntForestHuntTaskList) {
        String taskUid = UserIdMap.getCurrentUid();
        try {
            boolean doublecheck;

            // =====================================================

            int loopCount = 0; // 循环次数计数
            final int MAX_LOOP = 7; // 最大循环次数，避免死循环
            // 本轮已做过的任务：重拉列表后不再重做（服务端状态推进慢，重拉时它可能仍是待办，
            // 不去重会把每批任务重跑 MAX_LOOP 遍；跨场景不共享，各场景独立计数）
            Set<String> handledThisRun = new LinkedHashSet<>();

      /*
      sceneCode
      ANTFOREST_NORMAL_DRAW_TASK
      ANTFOREST_ACTIVITY_DRAW_TASK

      taskType
      FOREST_NORMAL_DRAW_SHARE
      FOREST_ACTIVITY_DRAW_SHARE

      p2pSceneCode
      FOREST_NORMAL_20250829_SHARE
      FOREST_NORMAL_20251024_SHARE
       */
            do {
                doublecheck = false;
                JSONObject listTaskopengreen = new JSONObject(AntForestRpcCall.listTaskopengreen(sceneCode + "_TASK", "task_entry"));
                if (MessageUtil.checkSuccess(TAG, listTaskopengreen)) {
                    JSONArray taskList = listTaskopengreen.optJSONArray("taskInfoList");
                    if (taskList == null) {
                        break;
                    }
                    for (int i = 0; i < taskList.length(); i++) {
                        JSONObject taskInfo = taskList.optJSONObject(i);
                        if (taskInfo == null) {
                            continue;
                        }
                        JSONObject taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo");
                        if (taskBaseInfo == null) {
                            continue;
                        }
                        String bizInfoStr = taskBaseInfo.optString("bizInfo", "");
                        if (bizInfoStr.isEmpty()) {
                            continue;
                        }
                        JSONObject bizInfo = new JSONObject(bizInfoStr);
                        String taskName = bizInfo.optString("title", "");
                        String desc = bizInfo.optString("desc", "");

                        String taskSceneCode = taskBaseInfo.optString("sceneCode", "");
                        String taskStatus = taskBaseInfo.optString("taskStatus", "");
                        String taskType = taskBaseInfo.optString("taskType", "");

                        JSONObject taskRights = taskInfo.getJSONObject("taskRights");
                        int rightsTimes = taskRights.getInt("rightsTimes");
                        int rightsTimesLimit = taskRights.getInt("rightsTimesLimit");

                        // ==================== 活力值兑换任务 =====================
                        // 2/2 多次任务：每兑换一次活力值即达一个领奖里程碑（1/2、2/2 均可领奖）；
                        // 故 FINISHED(1/2) 是"可领奖但还剩次数"的中间态——必须先领奖、再继续兑换下一档，
                        // 否则只做 1 次就停在 1/2，第 2 次兑换永远不触发。每兑换一档都先领奖再走下一档。
                        if (taskType.equals("NORMAL_DRAW_EXCHANGE_VITALITY")) {
                            if (!handledThisRun.add(taskType)) {
                                continue;
                            }
                            // 领奖按"任务+档位"打当日标记：已领过再领会回失败、走"已按列表确认领取"，
                            // 不标记的话每一轮都会重试并重复刷同一条日志。
                            String claimKey = "forest::chouchoule::exchange::award::" + taskSceneCode
                                    + "/" + taskType + "#" + rightsTimes;
                            if ((taskStatus.equals("FINISHED") || taskStatus.equals("RECEIVED"))
                                    && !Status.hasFlagToday(claimKey)) {
                                TimeUtil.sleep(2000);
                                JSONObject sginRes = new JSONObject(AntForestRpcCall.receiveTaskAwardopengreen("task_entry", taskSceneCode, taskType));
                                boolean claimed = false;
                                if (MessageUtil.checkSuccess(TAG, sginRes)) {
                                    int incAwardCount = sginRes.getInt("incAwardCount");
                                    Log.forest("森林寻宝🎖️[" + taskName + "]获得抽奖*" + incAwardCount);
                                    claimed = true;
                                    if (rightsTimesLimit - rightsTimes > 0) {
                                        doublecheck = true;
                                    }
                                } else {
                                    claimed = TaskAward.confirmReceivedOrBlackList("森林寻宝🎖️",
                                            k -> probeChouChouLeStatus(sceneCode, taskSceneCode, taskType), taskName,
                                            taskName,
                                            () -> MessageUtil.checkResultCodeAndMarkTaskBlackList("AntForestHuntTaskList", taskName, sginRes),
                                            msg -> Log.forest(msg));
                                }
                                if (claimed) {
                                    Status.flagToday(claimKey);
                                }
                            }
                            // 名额已用满（2/2）才算真正做完；否则继续向下兑换下一档
                            if (rightsTimes >= rightsTimesLimit) {
                                continue;
                            }
                            // TODO 起步，或 FINISHED 但尚有余次：补齐剩余兑换次数（每档兑换后顺带领奖）
                            int remain = rightsTimesLimit - rightsTimes;
                            if (remain <= 0) {
                                continue;
                            }
                            String exchangeKey = "forest::chouchoule::exchangeTotal::" + taskSceneCode + "/" + taskType;
                            int doneToday = Status.getIntFlagToday(exchangeKey);
                            for (int cnt = 0; cnt < remain; cnt++) {
                                int totalVitalityAmount = 0;
                                try {
                                    JSONObject jo = new JSONObject(AntForestRpcCall.queryVitalityStoreIndex());
                                    if (!MessageUtil.checkResultCode(TAG, jo) || !jo.has("userVitalityInfoVO")) {
                                        break;
                                    }
                                    JSONObject userVitalityInfo = jo.getJSONObject("userVitalityInfoVO");
                                    totalVitalityAmount = userVitalityInfo.optInt("totalVitalityAmount", 0);
                                } catch (Throwable th) {
                                    Log.err(TAG, "chouChouLesceneEXCHANGE err:", th);
                                    break;
                                }
                                if (totalVitalityAmount < 20) {
                                    Log.forest("森林寻宝🧾活力值" + totalVitalityAmount + "不能兑换");
                                    break;
                                }
                                // 用"本轮已做几次"作 key：同一次兑换重拉列表时会命中同一条当日标记、
                                // 不会重复请求；不同次数各自成键，不至于第一次就把后面几次一起挡死。
                                TaskAttemptPolicy.Site exchangeSite = new TaskAttemptPolicy.Site(
                                        "AntForestHuntTaskList", "森林寻宝",
                                        taskType, taskSceneCode,
                                        (k) -> probeChouChouLeStatus(sceneCode, taskSceneCode, taskType),
                                        (k) -> rightsTimes >= rightsTimesLimit)
                                        .withScene(taskSceneCode).withSource("ADBASICLIB");
                                if (cnt < remain - 1) {
                                    // 多档里程碑的**中间档**：一次兑换不会让列表变完成（兑换满才 FINISHED），
                                    // 列表复核在这里永远判"未完成"，会把成功的兑换改判成做不了、挡住下一档。
                                    // 最后一档仍走列表复核，保留"响应撒谎"的兜底。
                                    exchangeSite = exchangeSite.withTrustResponse();
                                }
                                Outcome exchangeOutcome = TaskAttemptPolicy.handle(
                                        "forest::chouchoule::exchange::" + taskSceneCode + "/" + taskType
                                                + "#" + doneToday,
                                        taskName, null,
                                        () -> attemptExchangeVitality(activityId, sceneCode, taskSceneCode, taskType, taskName),
                                        Log::forest, exchangeSite);
                                if (exchangeOutcome != Outcome.DONE && exchangeOutcome != Outcome.TRIGGERED
                                        && exchangeOutcome != Outcome.AWARDED) {
                                    break; // 做不了就收手，不再空转剩余次数
                                }
                                doneToday++;
                                Status.setIntFlagToday(exchangeKey, doneToday);
                                // 每兑换一档都可能到达领奖里程碑（1/2、2/2），到就先领奖再走下一档；
                                // 同样按"任务+档位"打当日标记，避免重拉列表时重复领奖/重复刷日志
                                String loopClaimKey = "forest::chouchoule::exchange::award::" + taskSceneCode
                                        + "/" + taskType + "#" + (rightsTimes + cnt + 1);
                                if (!Status.hasFlagToday(loopClaimKey)) {
                                    TimeUtil.sleep(1500);
                                    JSONObject sginRes2 = new JSONObject(AntForestRpcCall.receiveTaskAwardopengreen("task_entry", taskSceneCode, taskType));
                                    if (MessageUtil.checkSuccess(TAG, sginRes2)) {
                                        Log.forest("森林寻宝🎖️[" + taskName + "]获得抽奖*" + sginRes2.getInt("incAwardCount"));
                                        Status.flagToday(loopClaimKey);
                                    }
                                }
                                doublecheck = true;
                                TimeUtil.sleep(1000);
                            }
                            continue;
                        }

                        // 已完成任务领取奖励
                        if (taskStatus.equals("FINISHED")) {
                            TimeUtil.sleep(2000);
                            JSONObject sginRes = new JSONObject(AntForestRpcCall.receiveTaskAwardopengreen("task_entry", taskSceneCode, taskType));
                            if (MessageUtil.checkSuccess(TAG, sginRes)) {
                                int incAwardCount = sginRes.getInt("incAwardCount");
                                Log.forest("森林寻宝🎖️[" + taskName + "]获得抽奖*" + incAwardCount);
                                if (rightsTimesLimit - rightsTimes > 0) {
                                    doublecheck = true;
                                }
                            } else {
                                // 领奖收口：先按任务列表复核"已领到"，未确认才交自动拉黑（顺序由 TaskAward 固定）
                                TaskAward.confirmReceivedOrBlackList("森林寻宝🎖️",
                                        k -> probeChouChouLeStatus(sceneCode, taskSceneCode, taskType), taskName,
                                        taskName,
                                        () -> MessageUtil.checkResultCodeAndMarkTaskBlackList("AntForestHuntTaskList", taskName, sginRes),
                                        msg -> Log.forest(msg));
                            }
                            continue;
                        }

                        //黑名单任务跳过
                        if (AntForestHuntTaskList.contains(taskName)) {
                            continue;
                        }

                        if (taskType.contains("_DRAW_SHARE") && ForestHuntHelp) {
                            int forestHuntHelpTodayCount = Status.getforestHuntHelpToday(taskType);
                            if (forestHuntHelpTodayCount < shareIds.size()) {
                                JSONObject prodPlayParam = new JSONObject(taskBaseInfo.getString("prodPlayParam"));
                                String p2pSceneCode = prodPlayParam.getString("p2pSceneCode");
                                Log.record("森林寻宝🎰️执行[" + drawScenename + "]助力好友");
                                DoForestHuntHelp(shareIds, activityId, p2pSceneCode, taskType);
                            }
                        }
                        // 在最后一个任务时强制开启助力
                        if (i == (taskList.length() - 1)) {
                            if (NORMALForestHuntHelp && sceneCode.equals("ANTFOREST_NORMAL_DRAW")) {
                                int forestHuntHelpTodayCount = Status.getforestHuntHelpToday("FOREST_NORMAL_DRAW_SHARE");
                                if (forestHuntHelpTodayCount < shareIds.size()) {
                                    Log.record("森林寻宝🎰️执行[普通场景]助力好友(薅羊毛，如果助力结果不返回成功请关闭配置项)");
                                    DoForestHuntHelp(shareIds, activityId, "FOREST_NORMAL_20250829_SHARE", "FOREST_NORMAL_DRAW_SHARE");
                                }
                            }
                            if (ACTIVITYForestHuntHelp && sceneCode.equals("ANTFOREST_ACTIVITY_DRAW")) {
                                int forestHuntHelpTodayCount = Status.getforestHuntHelpToday("FOREST_ACTIVITY_DRAW_SHARE");
                                if (forestHuntHelpTodayCount < shareIds.size()) {
                                    Log.record("森林寻宝🎰️执行[活动场景]助力好友(薅羊毛，如果助力结果不返回成功请关闭配置项)");
                                    DoForestHuntHelp(shareIds, activityId, "FOREST_NORMAL_20251024_SHARE", "FOREST_ACTIVITY_DRAW_SHARE");
                                }
                            }
                        }
                        // 周期/持续型任务：跳过，不尝试也不拉黑
                        if (TaskAttemptPolicy.isCyclicTask(taskBaseInfo)) {
                            // 周期任务常驻列表、由真实行为推进，每轮结果相同；静默跳过
                            continue;
                        }
                        // 本轮已处理过：重拉列表时不再重做（见 handledThisRun 说明）
                        if (!handledThisRun.add(taskType)) {
                            continue;
                        }
                        // 活力值兑换任务已在上方统一处理（含 FINISHED 中间态与每档领奖），此处不再重复。

                        // 统一处理 FOREST_NORMAL_DRAW 和 FOREST_ACTIVITY_DRAW开头任务
                        if ((taskType.startsWith("FOREST_NORMAL_DRAW") || taskType.startsWith("FOREST_ACTIVITY_DRAW")) && taskStatus.equals("TODO")) {
                            // 一次补齐：同一轮内按剩余次数连做，打满即止
                            int remain = rightsTimesLimit > 0 ? rightsTimesLimit - rightsTimes : 1;
                            for (int cnt = 0; cnt < remain; cnt++) {
                                TimeUtil.sleep(1000);
                                // 三条腿见 chouChouLeFinishTask（XLIGHT=广告类先走 4Chouchoule，其余先走 opengreen）
                                if (!chouChouLeFinishTask(taskType, taskSceneCode, taskName, taskType.contains("XLIGHT"), sceneCode, rightsTimes + cnt)) {
                                    break; // 做不了就收手，不再空转剩余次数
                                }
                                doublecheck = true;
                            }
                            continue;
                        }
                        if (taskStatus.equals("TODO")) {
                            // 一次补齐：同一轮内按剩余次数连做，打满即止（与庄园抽抽乐同一形态）
                            // 兜底完成任务操作（三条腿见 chouChouLeFinishTask，非 XLIGHT 先走 opengreen）
                            int remain = rightsTimesLimit > 0 ? rightsTimesLimit - rightsTimes : 1;
                            for (int cnt = 0; cnt < remain; cnt++) {
                                TimeUtil.sleep(1000);
                                if (!chouChouLeFinishTask(taskType, taskSceneCode, taskName, false, sceneCode, rightsTimes + cnt)) {
                                    break; // 做不了就收手，不再空转剩余次数
                                }
                                doublecheck = true;
                            }
                        }
                    }
                }
            } while (doublecheck && ++loopCount < MAX_LOOP);

            // ==================== 执行抽奖 ====================
            if (ForestHuntDraw) {
                JSONObject jo = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen(activityId, sceneCode, "task_entry"));
                if (MessageUtil.checkSuccess(TAG, jo)) {
                    JSONObject drawAsset = jo.getJSONObject("drawAsset");
                    int blance = drawAsset.getInt("blance");

                    final int MAX_DRAW_LOOP = 100;
                    int drawLoop = 0;
                    // 成功但余额不降时也要能退出，避免无上限连发
                    while (blance > 0 && ++drawLoop <= MAX_DRAW_LOOP) {
                        jo = new JSONObject(AntForestRpcCall.drawopengreen(activityId, sceneCode, "task_entry", UserIdMap.getCurrentUid()));
                        if (MessageUtil.checkSuccess(TAG, jo)) {
                            drawAsset = jo.getJSONObject("drawAsset");
                            blance = drawAsset.getInt("blance");
                            JSONObject prizeVO = jo.getJSONObject("prizeVO");
                            String prizeName = prizeVO.getString("prizeName");
                            int prizeNum = prizeVO.getInt("prizeNum");
                            Log.forest("森林寻宝🎁领取[" + prizeName + "*" + prizeNum + "]" + "");
                            Toast.show("森林寻宝🎁领取[" + prizeName + "*" + prizeNum + "]");
                            if (prizeName.contains("g能量")) {
                                // 记能量值而非份数，否则寻宝奖励会按"个数"入账
                                Matcher energyMatcher = ENERGY_PRIZE_PATTERN.matcher(prizeName);
                                if (energyMatcher.find()) {
                                    Statistics.addData(Statistics.DataType.COLLECTED, Integer.parseInt(energyMatcher.group(1)) * prizeNum);
                                }
                            }
                        } else {
                            blance--;
                        }
                    }
                }
            }

            // ==============================================

        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    // kuzVe2lrSrXFdacxxi3KWjxx-4O7FEYDgn0xx0OehP5jt9-YINZOkxgPDkvWvkwkQXSDbZ
    // -77VUJcjlcZsjGio6MsAtmwxkxkx(FOREST_NORMAL_DRAW_SHARE)
    // kuzVe2lrSrXFdacxxi3KWjxx-4O7FEYDgn0xx0OehP5jt9-bxgpIW643h4FnWRjs9uZzng
    // -77VUJcjlcZsjGio6MsAtmwxkxkx(FOREST_ACTIVITY_DRAW_SHARE)
    void DoForestHuntHelp(Set<String> shareIds, String activityId, String p2pSceneCode, String taskType) {
        String taskUid = UserIdMap.getCurrentUid();
        try {
            int forestHuntHelpTodayCount;

            for (String shareUserId : shareIds) {
                forestHuntHelpTodayCount = Status.getforestHuntHelpToday(taskType);
                if (Status.hasFlagToday(taskType + "::" + shareUserId)) {
                    continue;
                }
                String shareId;
                if ((shareUserId.length() > 20 && shareUserId.length() < 28) && taskType.equals("FOREST_NORMAL_DRAW_SHARE")) {
                    shareId = shareUserId + "4O7FEYDgn0xx0OehP5jt9" + "YINZOkxgPDkvWvkwkQXSDbZ" + "77VUJcjlcZsjGio6MsAtmwxkxkx";
                } else if ((shareUserId.length() > 20 && shareUserId.length() < 28) && taskType.equals("FOREST_ACTIVITY_DRAW_SHARE")) {
                    shareId = shareUserId + "4O7FEYDgn0xx0OehP5jt9" + "bxgpIW643h4FnWRjs9uZzng" + "77VUJcjlcZsjGio6MsAtmwxkxkx";
                } else {
                    Log.forest("森林寻宝🎰️[错误分享用户]#" + UserIdMap.getShowName(shareUserId));
                    continue;
                }
                String userId = shareComponentRecall(p2pSceneCode, shareId);
                if (userId.equals("解析userID失败")) {
                    continue;
                }
                TimeUtil.sleep(1500);
                //能成功返回，但解析不了UID
                if (userId.length() != 16) {
                    continue;
                }
                String resconfirmShareRecall = confirmShareRecall(activityId, p2pSceneCode, shareId, userId);
                TimeUtil.sleep(1500);

                String userName = UserIdMap.getShowName(userId) != null ? UserIdMap.getShowName(userId) : userId;
                Log.forest("森林寻宝👊助力[" + userName + "]" + resconfirmShareRecall);
                // 标记助力成功
                Status.flagToday(taskType + "::" + shareUserId, taskUid);
                forestHuntHelpTodayCount++;
                // 统计场景助力次数
                Status.forestHuntHelpToday(taskType, forestHuntHelpTodayCount, taskUid);
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 抽抽乐任务的三条腿：4Chouchoule ↔ opengreen 互备（XLIGHT 先走前者），都被拒时再换
     * doFarmTask（见 {@link TaskAlternative}）。
     *
     * @param stage 当前档位（调用方的 rightsTimes）：多次任务每推进一档都是**新的**尝试，
     *              策略键必须带上它，否则第一档打上的当日标记会把后续档位全挡下、任务停在第一档。
     *              单次任务传 0 即可（键保持原样）。
     * @return 已成功或已触发（true 时调用方应 {@code doublecheck = true} 重拉列表核对）
     */
    private boolean chouChouLeFinishTask(String taskType, String taskSceneCode, String taskName, boolean xlight,
                                         String activitySceneCode, int stage) {
        // 做不了的当天只试一次、临时故障留待下一轮（见 TaskAttemptPolicy）
        // 完成判定以任务列表为准（探针复核），响应不可信
        String policyKey = "forest::chouchoule::" + taskSceneCode + "/" + taskType + "#" + stage;
        Outcome outcome = TaskAttemptPolicy.handle(policyKey, taskName, null,
                () -> attemptChouChouLeTask(taskType, taskSceneCode, taskName, xlight), Log::forest,
                new TaskAttemptPolicy.Site("AntForestHuntTaskList", "森林寻宝", taskType, taskSceneCode,
                        (k) -> probeChouChouLeStatus(activitySceneCode, taskSceneCode, taskType))
                        // 本家值：任务场景 + ADBASICLIB
                        .withScene(taskSceneCode).withSource("ADBASICLIB"));
        return outcome == Outcome.DONE || outcome == Outcome.TRIGGERED;
    }

    /** 活力值兑换抽奖次数：{@code exchangeTimesFromTaskopengreen}。 */
    private Outcome attemptExchangeVitality(String activityId, String sceneCode, String taskSceneCode,
                                            String taskType, String taskName) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.exchangeTimesFromTaskopengreen(
                    activityId, sceneCode, "task_entry", taskSceneCode, taskType));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.forest("森林寻宝🎖️[" + taskName + "]获得抽奖*" + jo.optInt("times"));
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            if (TaskAlternative.hit(jo, taskSceneCode)) {
                return Outcome.UNSUPPORTED;
            }
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "attemptExchangeVitality err:", t);
        }
        return Outcome.RETRY;
    }

    /** 列表状态探针：用与本场景相同的表达式重拉寻宝任务列表，按 sceneCode+taskType 匹配该任务当前状态。 */
    private TaskAttemptPolicy.ProbeResult probeChouChouLeStatus(String activitySceneCode, String taskSceneCode,
                                                                String taskType) {
        // 状态在 taskBaseInfo 子对象内
        return TaskAttemptPolicy.probeByNestedList(TAG,
                () -> AntForestRpcCall.listTaskopengreen(activitySceneCode + "_TASK", "task_entry"),
                jo -> MessageUtil.checkSuccess(TAG, jo),
                jo -> jo.optJSONArray("taskInfoList"),
                item -> {
                    JSONObject taskBaseInfo = item.optJSONObject("taskBaseInfo");
                    if (taskBaseInfo == null
                            || !taskSceneCode.equals(taskBaseInfo.optString("sceneCode"))
                            || !taskType.equals(taskBaseInfo.optString("taskType"))) {
                        return null;
                    }
                    return taskBaseInfo.optString("taskStatus");
                });
    }

    /** 森林寻宝/抽抽乐完成上报：两种接口互为兜底，做不了不拉黑 */
    private Outcome attemptChouChouLeTask(String taskType, String taskSceneCode, String taskName, boolean xlight) {
        try {
            JSONObject result = new JSONObject(xlight
                    ? AntForestRpcCall.finishTask4Chouchoule(taskType, taskSceneCode)
                    : AntForestRpcCall.finishTaskopengreen(taskType, taskSceneCode));
            if (!MessageUtil.checkSuccess(TAG, result)) {
                // 已完结就不必再换另一个接口重报一遍
                if (MessageUtil.isAlreadyDone(result)) {
                    return Outcome.DONE;
                }
                result = new JSONObject(xlight
                        ? AntForestRpcCall.finishTaskopengreen(taskType, taskSceneCode)
                        : AntForestRpcCall.finishTask4Chouchoule(taskType, taskSceneCode));
            }
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntForestHuntTaskList", taskName, result);
            if (MessageUtil.checkSuccess(TAG, result)) {
                Log.forest("森林寻宝🧾完成[" + taskName + "]");
                return Outcome.DONE;
            }
            // 服务端答「任务已完结」：此前已上报成功，等价于完成，不要再走到「未完成」
            if (MessageUtil.isAlreadyDone(result)) {
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(result) || MessageUtil.isServerBusy(result)) {
                return Outcome.RETRY;
            }
            // 不支持rpc调用（400000040）→ 由 TaskAttemptPolicy 代为伪申报
            if (TaskAlternative.hit(result, taskSceneCode)) {
                return Outcome.UNSUPPORTED;
            }
            Log.other("森林寻宝⚠️未完成[" + taskName + "]");
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "chouChouLeFinishTask err:", t);
        }
        return Outcome.RETRY;
    }

    private String shareComponentRecall(String sceneCode, String shareId) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.shareComponentRecall(sceneCode, shareId));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return "解析shareID失败";
            }
            if (jo.has("inviterInfoVo")) {
                jo = jo.getJSONObject("inviterInfoVo");
                return jo.getString("userId");
            }
        } catch (Throwable t) {
            Log.err(TAG, "shareComponentRecall err:", t);
        }
        return "解析userID失败";
    }

    private String confirmShareRecall(String activityId, String p2pSceneCode, String shareId, String userId) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.confirmShareRecall(activityId, p2pSceneCode, shareId, userId));
            return jo.getString("desc");
        } catch (Throwable t) {
            Log.err(TAG, "confirmShareRecall err:", t);
        }
        return "FALSE end";
    }
}
