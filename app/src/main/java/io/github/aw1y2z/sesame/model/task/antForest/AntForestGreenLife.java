package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy.Outcome;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;

/**
 * 蚂蚁森林 | 森林集市（绿色生活 / 绿色租赁）。
 * <p>线上逛街发能量、集市打卡，以及绿色租赁的「线上逛街」能量生成。
 * 均为每日一次的行为，是否已做完用当日标记 {@code forest::greenLifeSendEnergy::*} 兜住，
 * 避免同一天反复问询；打卡走统一尝试策略并在失败时回读列表核对。
 */
final class AntForestGreenLife {

    private static final String TAG = AntForestGreenLife.class.getSimpleName();

    private AntForestGreenLife() {
    }

    /** 森林集市入口：线上逛街发能量 + 当期活动打卡 */
    static void greenLife() {
        sendEnergyByAction("GREEN_LIFE");
        retrieveCurrentActivity();
    }

    /** 绿色租赁：确认本轮有可发能量后再请求生成，避免被服务端拒（SYSTEM_ERROR） */
    static void greenRent() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.checkUserSecondSceneChance());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            // 本轮不发能量时再请求生成会被服务端拒：先看机会字段，直接跳过
            JSONObject chance = jo.optJSONObject("resultObject");
            if (chance != null && !chance.optBoolean("canSendEnergy", true)) {
                Log.i(TAG, "绿色租赁⏭️本轮无可发能量，跳过");
                return;
            }
            TimeUtil.sleep(200);
            jo = new JSONObject(AntForestRpcCall.generateEnergy());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }

            JSONObject resultObject = jo.getJSONObject("resultObject");
            JSONObject energyGenerated = resultObject.getJSONObject("energyGenerated");
            int zulinshangpinliulan = energyGenerated.getInt("zulinshangpinliulan");
            Log.forest("绿色租赁🛍️完成[线上逛街]#产生[" + zulinshangpinliulan + "g能量]");
            Toast.show("绿色租赁🛍️完成[线上逛街]#产生[" + zulinshangpinliulan + "g能量]");
            if (zulinshangpinliulan > 0) {
                AntForestV2.addTotalCollected(zulinshangpinliulan);
                Statistics.addData(Statistics.DataType.COLLECTED, zulinshangpinliulan);
            }
        } catch (Throwable t) {
            Log.err(TAG, "greenRent err:", t);
        }
    }

    private static void retrieveCurrentActivity() {
        try {
            JSONObject jo = new JSONObject(GreenLifeRpcCall.retrieveCurrentActivity());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            jo = jo.getJSONObject("data");
            if (!jo.has("currentActivity")) {
                Log.i(TAG, "森林集市⏭️当前无集市活动");
                return;
            }
            JSONObject currentActivity = jo.getJSONObject("currentActivity");
            int numberOfDaysCompleted = currentActivity.getInt("numberOfDaysCompleted") + 1;
            JSONObject currentTask = jo.getJSONObject("currentTask");
            if (currentTask.getBoolean("checkInCompleted")) {
                return;
            }
            String taskTemplateId = currentTask.getString("taskTemplateId");
            // 走统一策略：完成后按列表核对（checkInCompleted）；400000040 时由策略代为伪申报
            TaskAttemptPolicy.handle("forest::greenlife::" + taskTemplateId, "森林集市打卡", null,
                    () -> attemptGreenLifeCheckIn(taskTemplateId, numberOfDaysCompleted), Log::forest,
                    new TaskAttemptPolicy.Site("AntForestVitalityTaskList", "森林集市打卡",
                            taskTemplateId, null, (k) -> probeGreenLifeCheckIn(taskTemplateId))
                            .withScene("ANTFOREST"));
        } catch (Throwable t) {
            Log.err(TAG, "retrieveCurrentActivity err:", t);
        }
    }

    /** 森林集市打卡上报：{@code finishCurrentTask}。 */
    private static Outcome attemptGreenLifeCheckIn(String taskTemplateId, int numberOfDaysCompleted) {
        try {
            JSONObject jo = new JSONObject(GreenLifeRpcCall.finishCurrentTask(taskTemplateId));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                JSONObject data = jo.optJSONObject("data");
                JSONArray ja = data == null ? null : data.optJSONArray("prizes");
                StringBuilder award = new StringBuilder();
                for (int i = 0; ja != null && i < ja.length(); i++) {
                    JSONObject prize = ja.optJSONObject(i);
                    if (prize == null) {
                        continue;
                    }
                    if (award.length() > 0) {
                        award.append(";");
                    }
                    award.append(prize.optString("name"));
                }
                Log.forest("森林集市🛍️打卡[坚持" + numberOfDaysCompleted + "天]"
                        + (award.length() > 0 ? "#获得[" + award + "]" : ""));
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            if (TaskAlternative.hit(jo, null)) {
                return Outcome.UNSUPPORTED;
            }
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "attemptGreenLifeCheckIn err:", t);
        }
        return Outcome.RETRY;
    }

    /** 森林集市打卡探针：重拉活动信息，读 {@code currentTask.checkInCompleted}。 */
    private static TaskAttemptPolicy.ProbeResult probeGreenLifeCheckIn(String taskTemplateId) {
        try {
            JSONObject jo = new JSONObject(GreenLifeRpcCall.retrieveCurrentActivity());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            JSONObject data = jo.optJSONObject("data");
            JSONObject currentTask = data == null ? null : data.optJSONObject("currentTask");
            if (currentTask == null) {
                return TaskAttemptPolicy.ProbeResult.GONE;
            }
            if (!taskTemplateId.equals(currentTask.optString("taskTemplateId"))) {
                return TaskAttemptPolicy.ProbeResult.GONE;
            }
            return currentTask.optBoolean("checkInCompleted", false)
                    ? TaskAttemptPolicy.ProbeResult.RECEIVED : TaskAttemptPolicy.ProbeResult.TODO;
        } catch (Throwable t) {
            Log.err(TAG, "probeGreenLifeCheckIn err:", t);
            return TaskAttemptPolicy.ProbeResult.UNKNOWN;
        }
    }

    private static void sendEnergyByAction(String sourceType) {
        try {
            // 线上逛街每天仅可完成一次：当天已发出或额度用尽后不再问询
            if (Status.hasFlagToday("forest::greenLifeSendEnergy::" + sourceType)) {
                return;
            }
            // 先问询再发送：服务端不允许时（如当日额度用尽）不发请求
            JSONObject jo = new JSONObject(GreenLifeRpcCall.consultForSendEnergyByAction(sourceType));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONObject data = jo.getJSONObject("data");
            if (!data.optBoolean("canSendEnergy", false)) {
                Status.flagToday("forest::greenLifeSendEnergy::" + sourceType);
                return;
            }
            jo = new JSONObject(GreenLifeRpcCall.sendEnergyByAction(sourceType));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            data = jo.getJSONObject("data");
            if (!data.optBoolean("canSendEnergy", false)) {
                // 受理但未发能量：记一行响应原文，供定位（额度正常时不会走到）
                Log.i(TAG, "森林集市⏭️[" + sourceType + "]未发能量#" + data);
                return;
            }
            int receivedEnergyAmount = data.getInt("receivedEnergyAmount");
            Log.forest("森林集市🛍️完成[线上逛街]#产生[" + receivedEnergyAmount + "g能量]");
            Toast.show("森林集市🛍️完成[线上逛街]#产生[" + receivedEnergyAmount + "g能量]");
            if (receivedEnergyAmount > 0) {
                AntForestV2.addTotalCollected(receivedEnergyAmount);
                Statistics.addData(Statistics.DataType.COLLECTED, receivedEnergyAmount);
                // 确认到账后才算当天已完成（线上逛街每天仅可完成一次）
                Status.flagToday("forest::greenLifeSendEnergy::" + sourceType);
            }
        } catch (Throwable t) {
            Log.err(TAG, "sendEnergyByAction err:", t);
        }
    }
}
