package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONObject;

import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TaskCancelledException;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 合种浇水：组队合种（teamCooperateWater）与真爱合种（loveteam）。
 * <p>会临时切换账号的组队/个人模式：「切回个人模式」的 finally 与异常恢复都收在这里。
 * <p>配置 key 与持久化 flag **都不能改名**，改名会让老用户配置丢失、当天重复浇水。
 */
public final class AntForestTeam {

    private static final String TAG = AntForestV2.class.getSimpleName();

    /** 组队模式切换标记，持久化 key 不能改名。 */
    public static final String FLAG_TEAM_MODE_SWITCHED = "Forest::teamWaterSwitchedToTeam";

    /** 真爱合种浇水当日标记，持久化 key 不能改名。 */
    public static final String FLAG_LOVETEAM_WATER = "Forest::loveteamWater";

    /** 组队合种当日已浇量（克），持久化 key 不能改名。 */
    public static final String FLAG_TEAM_WATER_DAILY_COUNT = "FLAG_TEAM_WATER_DAILY_COUNT";

    private static BooleanModelField partnerWaterEnabled;
    private static IntegerModelField partnerWaterNum;
    private static BooleanModelField loveWaterEnabled;
    private static IntegerModelField loveWaterNum;

    private AntForestTeam() {
    }

    /** 注册合种浇水相关配置项，调用顺序与原先在 getFields() 里一致。 */
    public static void addFields(ModelFields modelFields) {
        modelFields.addField(partnerWaterEnabled = new BooleanModelField("partnerteamWater", "组队合种浇水", false));
        modelFields.addField(partnerWaterNum = new IntegerModelField("partnerteamWaterNum", "组队合种浇水" + "(g)", 10, 10, 5000).setDependsOn("partnerteamWater"));
        modelFields.addField(loveWaterEnabled = new BooleanModelField("loveteamWater", "真爱合种浇水", false));
        modelFields.addField(loveWaterNum = new IntegerModelField("loveteamWaterNum", "真爱合种浇水" + "(g)", 20, 20, 10000).setDependsOn("loveteamWater"));
    }

    /**
     * 按开关执行合种浇水。
     *
     * @param teamPriority 组队优先开关的当前值，决定组队合种浇水后是否立刻切回个人模式
     */
    public static void run(boolean teamPriority) {
        if (partnerWaterEnabled != null && Boolean.TRUE.equals(partnerWaterEnabled.getValue())) {
            teamCooperateWater(teamPriority);
        }
        if (loveWaterEnabled != null && Boolean.TRUE.equals(loveWaterEnabled.getValue())) {
            int waterNum = loveWaterNum == null ? 0 : loveWaterNum.getValue();
            if (waterNum >= 20 && waterNum <= 10000) {
                loveteam(waterNum);
            }
        }
    }

    /**
     * 组队合种浇水会临时把账号切到组队模式。若上次切回失败、或进程在切回前被杀，
     * 这里做一次校正，避免用户被永久留在组队模式（会影响个人主页 / 好友页的表现）。
     * <p>没开「组队合种浇水」也会执行：正是"开了功能 → 切换中途中断 → 用户随后把开关关掉"这种情况最需要校正。
     */
    public static void fixTeamModeIfNeeded(boolean teamPriority) {
        // 组队优先时组队是预期状态，不做"切回个人"的校正（否则刚切过去又被切回来）
        if (teamPriority) {
            return;
        }
        if (!Status.hasFlagToday(FLAG_TEAM_MODE_SWITCHED)) {
            return;
        }
        if (updateUserConfiginTeam(false)) {
            Status.clearFlag(FLAG_TEAM_MODE_SWITCHED);
            Log.record("检测到上次组队合种浇水未切回，已恢复个人模式");
        } else {
            Log.record("上次组队合种浇水未切回个人模式，本次恢复失败，下次运行重试");
        }
    }

    /** needReturn:false 切回个人模式，true 切到组队模式 */
    public static boolean updateUserConfiginTeam(boolean needReturn) {
        try {
            String updateStr = AntForestRpcCall.updateUserConfiginTeam(needReturn);
            JSONObject updateJo = new JSONObject(updateStr);
            if (!MessageUtil.checkResultCode(TAG, updateJo)) {
                Log.record("updateUserConfig 返回异常");
                return false;
            }
            Log.record("模式切换成功：" + (needReturn ? "切到组队模式" : "切到个人模式"));
            return true;
        } catch (Throwable th) {
            Log.err(TAG, "updateUserConfig err:", th);
        }
        return false;
    }

    /**
     * 是否处于组队版：只有组队主页的 teamHomeResult 下才带 mainMember（个人数据被嵌进合种树页面）。
     * <p>teamHomeResult 本身个人版也有（小队挑战配置），nextAction 同样不可靠（个人版常见
     * Cultivate），故二者都不参与判定。
     */
    public static boolean isTeam(JSONObject homeObj) {
        if (homeObj == null) {
            return false;
        }
        JSONObject teamHomeResult = homeObj.optJSONObject("teamHomeResult");
        return teamHomeResult != null && teamHomeResult.optJSONObject("mainMember") != null;
    }

    private static void teamCooperateWater(boolean teamPriority) {
        // 记录本次是否由本方法切到组队模式，只要是就得切回来
        boolean switchedToTeam = false;
        try {
            int userDailyTarget = Math.min(Math.max(partnerWaterNum.getValue(), 10), 5000);
            int todayUsed = Status.getforestHuntHelpToday(FLAG_TEAM_WATER_DAILY_COUNT);
            int userRemainingQuota = userDailyTarget - todayUsed;

            if (userRemainingQuota < 10) {
                Log.record("组队合种今日已达标 (已浇" + todayUsed + "g / 目标" + userDailyTarget + "g)，跳过");
                return;
            }

            JSONObject homeJo = queryTeamHomePage();
            if (homeJo == null) {
                return;
            }

            // 以 teamHomeResult.mainMember 判定当前版本：只有组队主页才把个人数据嵌进合种树页面。
            // teamHomeResult 本身个人版也有（小队挑战配置），teamBaseInfo 在个人版同样会出现
            // （已退出/受邀队伍的残留，teamWater 会以 PARAM_ILLEGAL 拒绝），nextAction 也不可靠
            // （个人版常见 Cultivate）。
            if (!isTeam(homeJo)) {
                // 响应连基础字段都没有说明是异常返回，此时不要贸然修改账户设置
                if (!homeJo.has("userBaseInfo") && !homeJo.has("nextAction") && !homeJo.has("now")) {
                    Log.record("首页响应异常(无主页字段)，跳过组队合种浇水");
                    return;
                }
                Log.record("当前为个人版，切换至组队版进行组队合种浇水");
                if (!updateUserConfiginTeam(true)) {
                    Log.record("切换到组队模式失败，跳过组队合种浇水");
                    return;
                }
                switchedToTeam = true;
                Status.flagToday(FLAG_TEAM_MODE_SWITCHED);
                homeJo = queryTeamHomePage();
                if (homeJo == null) {
                    return;
                }
                if (!isTeam(homeJo)) {
                    Log.record("切换后仍未进入组队版(可能未加入队伍)，跳过组队合种浇水");
                    return;
                }
            }

            String teamId = getTeamId(homeJo);
            if (teamId.isEmpty()) {
                Log.record("未获取到组队合种 TeamID");
                return;
            }

            JSONObject userBaseInfo = homeJo.optJSONObject("userBaseInfo");
            int currentEnergy = userBaseInfo == null ? 0 : userBaseInfo.optInt("currentEnergy", 0);
            if (currentEnergy < 10) {
                Log.record("当前能量不足10g(" + currentEnergy + "g)，无法浇水");
                return;
            }

            // 获取服务端限制
            JSONObject miscJo = new JSONObject(AntForestRpcCall.queryMiscInfo("teamCanWaterCount", teamId));
            if (!MessageUtil.checkResultCode(TAG, miscJo)) {
                Log.record("queryMiscInfo 查询失败");
                return;
            }

            int serverRemaining = getTeamCanWaterCount(miscJo);
            Log.record("组队状态检查:目标剩余" + userRemainingQuota + "g|官方剩余" + serverRemaining + "g|背包能量" + currentEnergy + "g");

            if (serverRemaining < 10) {
                Log.record("官方限制今日无可浇水额度，跳过");
                return;
            }

            // 计算最终浇水量
            int finalWaterAmount = Math.min(userRemainingQuota, Math.min(serverRemaining, currentEnergy));
            if (finalWaterAmount < 10) {
                Log.record("计算后浇水量(" + finalWaterAmount + "g)低于最小限制10g，不执行");
                return;
            }

            // 执行浇水。记账原则：
            // - 确定没发出去（离线/本轮作废）、服务端明确未成功（已浇过/额度不足/结构不符）：不记账，下轮可重试
            // - 超时/解析异常：请求可能已到服务端，按已浇记账避免重复
            JSONObject waterJo = null;
            Throwable waterErr = null;
            try {
                waterJo = new JSONObject(AntForestRpcCall.teamWater(teamId, finalWaterAmount));
            } catch (Throwable t) {
                waterErr = t;
            }
            boolean accountWater = true;
            if (waterErr != null) {
                if (ApplicationHook.isOffline() || waterErr instanceof TaskCancelledException) {
                    // 确定没发出去（离线 / 本轮代际已作废）：不记账，下一轮再试
                    accountWater = false;
                    Log.record("组队合种浇水未发出(离线/本轮作废)，下次运行重试");
                } else {
                    // 超时 / 响应解析异常：请求可能已经到服务端，按已浇记账避免重复
                    Log.err(TAG, "teamWater err:", waterErr);
                    Log.record("组队合种浇水结果不明(超时/解析异常)，按已浇记账避免重复");
                }
            } else if (MessageUtil.checkResultCode(TAG, waterJo) || MessageUtil.checkSuccess(TAG, waterJo)) {
                Log.forest("组队合种🚿[合种浇水]#" + finalWaterAmount + "g");
                Toast.show("组队合种🚿给合种浇水" + finalWaterAmount + "g");
            } else {
                // 服务端明确未返回成功（今日已浇过 / 额度用尽 / 结构不符）：不记账，
                // 避免没浇上却占掉当日额度；保留响应原文便于排查
                accountWater = false;
                Log.record("组队合种浇水未确认成功，本次不记账，下次运行重试：" + waterJo);
            }
            if (accountWater) {
                Status.forestHuntHelpToday(FLAG_TEAM_WATER_DAILY_COUNT, todayUsed + finalWaterAmount, UserIdMap.getCurrentUid());
                Log.record("组队合种今日浇水累计: " + (todayUsed + finalWaterAmount) + "g / " + userDailyTarget + "g");
            }
        } catch (Throwable t) {
            Log.err(TAG, "teamCooperateWater err:", t);
        } finally {
            // 任何分支（含异常和提前 return）都要切回个人模式，否则会一直停在组队模式影响其它功能。
            // 组队优先时由 run() 收尾统一切回，这里不切（否则会把本轮后续活动打回个人版）
            if (switchedToTeam && !teamPriority) {
                if (updateUserConfiginTeam(false)) {
                    Status.clearFlag(FLAG_TEAM_MODE_SWITCHED);
                    Log.record("已返回个人模式");
                } else {
                    Log.record("组队合种浇水后切回个人模式失败，下次运行重试");
                }
            }
        }
    }

    /** 查询森林首页，失败返回 null */
    private static JSONObject queryTeamHomePage() {
        try {
            JSONObject homeJo = new JSONObject(AntForestRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, homeJo)) {
                Log.record("queryHomePage 返回异常");
                return null;
            }
            return homeJo;
        } catch (Throwable t) {
            Log.err(TAG, "queryTeamHomePage err:", t);
        }
        return null;
    }

    /** 取组队合种的 teamId，缺失时返回空串 */
    private static String getTeamId(JSONObject homeJo) {
        JSONObject teamHomeResult = homeJo.optJSONObject("teamHomeResult");
        if (teamHomeResult == null) {
            return "";
        }
        JSONObject teamBaseInfo = teamHomeResult.optJSONObject("teamBaseInfo");
        return teamBaseInfo == null ? "" : teamBaseInfo.optString("teamId", "");
    }

    /** 取官方今日剩余可浇额度，缺失时返回 0 */
    private static int getTeamCanWaterCount(JSONObject miscJo) {
        JSONObject combineHandlerVOMap = miscJo.optJSONObject("combineHandlerVOMap");
        if (combineHandlerVOMap == null) {
            return 0;
        }
        JSONObject teamCanWaterCount = combineHandlerVOMap.optJSONObject("teamCanWaterCount");
        return teamCanWaterCount == null ? 0 : teamCanWaterCount.optInt("waterCount", 0);
    }

    private static void loveteam(int waterNum) {
        if (Status.hasFlagToday(FLAG_LOVETEAM_WATER)) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.loveteamHome());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject userInfo = jo.optJSONObject("userInfo");
            String teamId = userInfo == null ? "" : userInfo.optString("teamId", "");
            if (teamId.isEmpty()) {
                Log.record("真爱合种:未加入真爱合种或未取到队伍，跳过");
                return;
            }
            loveteamWater(teamId, getLoveteamName(jo, teamId), waterNum);
        } catch (Throwable th) {
            Log.err(TAG, "loveteam err:", th);
        }
    }

    /**
     * 真爱合种名称：loveHome 返回体里没有稳定已知的名称字段，这里按可能的 key 依次探测，
     * 都取不到时退回 teamId，保证日志始终有可读内容。
     */
    private static String getLoveteamName(JSONObject loveHome, String teamId) {
        JSONObject userInfo = loveHome.optJSONObject("userInfo");
        if (userInfo != null) {
            for (String key : new String[]{"teamName", "name", "treeName"}) {
                String name = userInfo.optString(key, "");
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        String name = loveHome.optString("teamName", "");
        return name.isEmpty() ? teamId : name;
    }

    /**
     * 真爱合种浇水（每日一次）。
     * <p>当日标记**先落再发请求**，避免同一天重复浇。
     * <p>以下两种情况撤销标记、允许下一轮重试：确定没发出去（离线、本轮代际已作废）、
     * 服务端明确未返回成功（已浇过 / 额度用尽 / 结构不符），避免没浇上却占掉当日标记。
     * <p>超时、响应解析异常**保留标记**：请求可能已经到服务端（水已经浇了），
     * 撤销标记会让「改配置触发的那一轮」再浇一次，表现为"每次修改配置返回就再浇一次"。
     */
    private static void loveteamWater(String teamId, String teamName, int waterNum) {
        Status.flagToday(FLAG_LOVETEAM_WATER);
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.loveteamWater(teamId, waterNum));
            if (MessageUtil.checkSuccess(TAG, jo) || MessageUtil.checkResultCode(TAG, jo)) {
                Log.forest("真爱浇水🚿给[" + teamName + "]合种浇水" + waterNum + "g");
                Toast.show("真爱浇水🚿给[" + teamName + "]合种浇水" + waterNum + "g");
                return;
            }
            // 服务端明确未成功（今日已浇过 / 额度用尽 / 结构不符）：撤销当日标记，下次运行重试
            Status.clearFlag(FLAG_LOVETEAM_WATER);
            Log.record("真爱合种浇水未确认成功，本次不记账，下次运行重试：" + jo);
        } catch (Throwable th) {
            if (ApplicationHook.isOffline() || th instanceof TaskCancelledException) {
                // 确定没发出去：撤销标记，下一轮再试
                Status.clearFlag(FLAG_LOVETEAM_WATER);
                Log.record("真爱合种浇水未发出(离线/本轮作废)，下次运行重试");
            } else {
                // 可能已经发出去（超时 / 响应解析异常）：保留标记，避免每轮重浇
                Log.err(TAG, "loveteamWater err:", th);
            }
        }
    }
}
