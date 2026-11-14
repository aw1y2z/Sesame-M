package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy.Outcome;
import io.github.aw1y2z.sesame.data.task.TaskAward;
import io.github.aw1y2z.sesame.entity.AlipayAntForestVitalityTaskList;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.model.task.antFarm.AntFarm.TaskStatus;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.AntForestHuntTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntForestVitalityTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 蚂蚁森林 | 森林任务与签到。
 * <p>承载活力值/绿色行动任务列表的拉取与领奖、容器型父任务的子任务处理、森林签到、
 * 青春特权道具领取，以及任务黑名单候选的初始化同步。
 * <p>需要宿主行为的任务（浇水类活力值任务）由调用方注入「能否浇水 / 浇水动作」，本类不读主类的浇水配置。
 */
final class AntForestTask {

    private static final String TAG = AntForestTask.class.getSimpleName();

    /** 站内行为做完后的列表确认轮询：行为生效有延迟，命中即返回，未确认则留给下一轮再核 */
    private static final long VITALITY_PROBE_INTERVAL_MS = 3000L;
    private static final int VITALITY_PROBE_ROUNDS = 3;

    private static BooleanModelField forestTaskGroup;
    private static BooleanModelField forestSign;
    private static BooleanModelField forest7DaySign;
    private static BooleanModelField receiveForestTaskAward;
    private static BooleanModelField AutoAntForestVitalityTaskList;
    private static SelectModelField AntForestVitalityTaskList;

    private AntForestTask() {
    }

    /**
     * 注册「森林任务」配置项：父级开关 + 三个互不相干的子任务，子任务各自可单独开。
     * <pre>
     * 森林任务
     *  ├ 森林签到
     *  ├ 森林7日签到
     *  └ 活力值任务
     *     ├ 活力值任务 | 自动黑名单
     *     └ 活力值任务 | 黑名单列表
     * </pre>
     * 子项名称不再带「森林任务 |」前缀——父级开关本身就是这一层，展开后直接是各项。
     */
    static void addFields(ModelFields modelFields) {
        modelFields.addField(forestTaskGroup = new BooleanModelField("forestTaskGroup", "森林任务", true));
        modelFields.addField(forestSign = new BooleanModelField("forestSign", "森林签到", false).setDependsOn("forestTaskGroup"));
        modelFields.addField(forest7DaySign = new BooleanModelField("forest7DaySign", "森林7日签到", false).setDependsOn("forestTaskGroup"));
        modelFields.addField(receiveForestTaskAward = new BooleanModelField("receiveForestTaskAward", "活力值任务", false).setDependsOn("forestTaskGroup"));
        modelFields.addField(AutoAntForestVitalityTaskList = new BooleanModelField("AutoAntForestVitalityTaskList", "活力值任务 | 自动黑名单", true).setDependsOn("receiveForestTaskAward"));
        modelFields.addField(AntForestVitalityTaskList = new SelectModelField("AntForestVitalityTaskList", "活力值任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntForestVitalityTaskList::getList).setDependsOn("AutoAntForestVitalityTaskList"));
    }

    /** 森林签到（复活能量）：父级关闭时一并跳过 */
    static boolean isForestSignEnabled() {
        return Boolean.TRUE.equals(forestTaskGroup.getValue()) && Boolean.TRUE.equals(forestSign.getValue());
    }

    /** 森林7日签到 */
    static boolean isForest7DaySignEnabled() {
        return Boolean.TRUE.equals(forestTaskGroup.getValue()) && Boolean.TRUE.equals(forest7DaySign.getValue());
    }

    /** 活力值任务（拉列表、领奖、上报站内行为，黑名单也挂在这一项下） */
    static boolean isVitalityTaskEnabled() {
        return Boolean.TRUE.equals(forestTaskGroup.getValue())
                && receiveForestTaskAward != null && Boolean.TRUE.equals(receiveForestTaskAward.getValue());
    }

    static boolean isAutoVitalityBlackListEnabled() {
        return AutoAntForestVitalityTaskList != null && Boolean.TRUE.equals(AutoAntForestVitalityTaskList.getValue());
    }

    /** 活力值任务黑名单（配置里勾选的任务标题） */
    static Set<String> getVitalityTaskBlackList() {
        return AntForestVitalityTaskList == null ? Collections.emptySet() : AntForestVitalityTaskList.getValue();
    }

    /**
     * 同步任务黑名单候选：把服务端当前下发的任务标题写入配置页选项，并按自动拉黑开关回写黑白名单。
     * <p>寻宝（抽抽乐）的候选由 {@code forestHunt} 开关控制，其黑名单字段仍由主类持有。
     */
    public static void initTaskBlackList(boolean autoVitalityTaskList, boolean autoHuntTaskList,
                                         boolean vitalityTask, boolean forestHunt) {
        try {

            //初始化AntForestVitalityTaskListMap
            AntForestVitalityTaskListMap.load();
            // 1. 定义黑名单（需要添加的任务）和白名单（需要移除的任务）
            // 注：不再预置拉黑，全部交由自动拉黑机制判定
            // 预置黑名单登记在 MessageUtil（单一真相，配置页据此标注"默认"）
            Set<String> blackList = MessageUtil.presetBlackList("AntForestV2", "AntForestVitalityTaskList");
            // 可继续添加更多黑名单任务

            Set<String> whiteList = new HashSet<>();
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                AntForestVitalityTaskListMap.add(task, task);
            }

            if (vitalityTask) {
                JSONObject jo = new JSONObject(AntForestRpcCall.queryTaskList());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    JSONArray forestTasksNew = jo.optJSONArray("forestTasksNew");
                    if (forestTasksNew != null && forestTasksNew.length() != 0) {
                        for (int i = 0; i < forestTasksNew.length(); i++) {
                            JSONObject forestTask = forestTasksNew.getJSONObject(i);
                            JSONArray taskInfoList = forestTask.optJSONArray("taskInfoList");
                            if (taskInfoList != null && taskInfoList.length() != 0) {
                                for (int j = 0; j < taskInfoList.length(); j++) {
                                    JSONObject taskInfo = taskInfoList.getJSONObject(j);
                                    JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                                    JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                                    String taskType = taskBaseInfo.getString("taskType");
                                    String taskTitle = bizInfo.optString("taskTitle", taskType);
                                    AntForestVitalityTaskListMap.add(taskTitle, taskTitle);
                                }
                            }
                        }
                    }
                }

                jo = new JSONObject(AntForestRpcCall.listTaskopengreen());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    // 添加安全的空值判断
                    if (jo.has("taskInfoList")) {
                        JSONArray taskInfoList = jo.optJSONArray("taskInfoList");
                        if (taskInfoList != null && taskInfoList.length() != 0) {
                            for (int j = 0; j < taskInfoList.length(); j++) {
                                JSONObject taskInfo = taskInfoList.getJSONObject(j);
                                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                                JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                                String taskType = taskBaseInfo.getString("taskType");
                                String taskTitle = bizInfo.optString("taskTitle", taskType);
                                AntForestVitalityTaskListMap.add(taskTitle, taskTitle);
                            }
                        }
                    }
                }


                //保存任务到配置文件
                AntForestVitalityTaskListMap.save();
                Log.record("同步任务🉑森林活力值任务列表");

                //自动按模块初始化设定调整黑名单和白名单
                if (autoVitalityTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntForestV2 = config.getModelFieldsMap().get("AntForestV2");
                    SelectModelField AntForestVitalityTaskList = (SelectModelField) AntForestV2.get("AntForestVitalityTaskList");
                    if (AntForestVitalityTaskList == null) {
                        return;
                    }

                    // 2~4. 批量写回黑/白名单并保存
                    MessageUtil.syncTaskBlackList("森林活力值任务", "AntForestVitalityTaskList", blackList, whiteList, AntForestVitalityTaskList);
                }
            }

            //初始化AntForestHuntTaskListMap
            AntForestHuntTaskListMap.load();
            // 1. 定义黑名单（需要添加的任务）和白名单（需要移除的任务）
            // 注：抽抽乐里的游戏/开宝箱类不再预置拉黑，交由自动拉黑机制判定
            blackList = MessageUtil.presetBlackList("AntForestV2", "AntForestHuntTaskList");
            // 可继续添加更多黑名单任务

            whiteList = new HashSet<>();
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                AntForestHuntTaskListMap.add(task, task);
            }

            if (forestHunt) {
                JSONObject resData = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen("", "ANTFOREST_NORMAL_DRAW", "task_entry"));
                if (MessageUtil.checkSuccess(TAG, resData)) {
                    JSONArray drawSceneGroups = resData.optJSONArray("drawSceneGroups");
                    if (drawSceneGroups != null) {
                        for (int i = 0; i < drawSceneGroups.length(); i++) {
                            JSONObject drawScene = drawSceneGroups.optJSONObject(i);
                            if (drawScene == null) {
                                continue;
                            }
                            JSONObject drawActivity = drawScene.optJSONObject("drawActivity");
                            if (drawActivity == null) {
                                continue;
                            }
                            String sceneCode = drawActivity.optString("sceneCode", "");
                            if (sceneCode.isEmpty()) {
                                continue;
                            }
                            JSONObject listTaskopengreen = new JSONObject(AntForestRpcCall.listTaskopengreen(sceneCode + "_TASK", "task_entry"));
                            if (MessageUtil.checkSuccess(TAG, listTaskopengreen)) {
                                JSONArray taskList = listTaskopengreen.optJSONArray("taskInfoList");
                                if (taskList != null) {
                                    for (int j = 0; j < taskList.length(); j++) {
                                        JSONObject taskInfo = taskList.optJSONObject(j);
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
                                        if (!taskName.isEmpty()) {
                                            AntForestHuntTaskListMap.add(taskName, taskName);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                AntForestHuntTaskListMap.save();
                Log.record("同步任务🉑森林抽抽乐任务列表");
                //自动按模块初始化设定调整黑名单和白名单
                if (autoHuntTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntForestV2 = config.getModelFieldsMap().get("AntForestV2");
                    SelectModelField AntForestHuntTaskList = (SelectModelField) AntForestV2.get("AntForestHuntTaskList");
                    if (AntForestHuntTaskList == null) {
                        return;
                    }

                    // 2~4. 批量写回黑/白名单并保存
                    MessageUtil.syncTaskBlackList("森林抽抽乐任务", "AntForestHuntTaskList", blackList, whiteList, AntForestHuntTaskList);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "initAntForestTaskListMap err:", t);
        }
    }

    /** 森林签到（复活能量） */
    static void vantiepSign() {
        if (Status.hasFlagToday("forest::vantiepSign")) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryTaskList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray forestSignVOList = jo.optJSONArray("forestSignVOList");
            if (forestSignVOList == null || forestSignVOList.length() == 0) {
                return;
            }
            JSONObject forestSignVO = forestSignVOList.optJSONObject(0);
            if (forestSignVO == null) {
                return;
            }
            String currentSignKey = forestSignVO.getString("currentSignKey"); // 当前签到的 key
            String signId = forestSignVO.getString("signId"); // 签到ID
            String sceneCode = forestSignVO.getString("sceneCode"); // 场景代码
            JSONArray signRecords = forestSignVO.getJSONArray("signRecords"); // 签到记录
            for (int i = 0; i < signRecords.length(); i++) { // 遍历签到记录
                JSONObject signRecord = signRecords.getJSONObject(i);
                String signKey = signRecord.getString("signKey");
                int awardCount = signRecord.getInt("awardCount");
                if (signKey.equals(currentSignKey) && signRecord.getBoolean("signed")) {
                    // 服务端回读：本期 currentSignKey 已签 → 落当日标记
                    Status.flagToday("forest::vantiepSign");
                    break;
                }
                if (signKey.equals(currentSignKey) && !signRecord.getBoolean("signed")) {
                    JSONObject joSign = new JSONObject(AntForestRpcCall.antiepSign(signId, UserIdMap.getCurrentUid(), sceneCode));
                    TimeUtil.sleep(300); // 等待300毫秒
                    if (MessageUtil.checkSuccess(TAG + "森林签到失败:", joSign)) {
                        int continuousCount = joSign.getInt("continuousCount");
                        Log.forest("森林签到📆拯救第" + continuousCount + "天#复活[" + awardCount + "g能量]");
                        Statistics.addData(Statistics.DataType.COLLECTED, awardCount);
                        // 回读确认：重新拉列表，本期 currentSignKey 已签才落当日标记
                        JSONObject verify = new JSONObject(AntForestRpcCall.queryTaskList());
                        if (MessageUtil.checkResultCode(TAG, verify)) {
                            JSONArray verifyRecords = verify.getJSONArray("forestSignVOList")
                                    .getJSONObject(0).getJSONArray("signRecords");
                            for (int j = 0; j < verifyRecords.length(); j++) {
                                JSONObject verifyRecord = verifyRecords.getJSONObject(j);
                                if (currentSignKey.equals(verifyRecord.optString("signKey"))
                                        && verifyRecord.optBoolean("signed")) {
                                    Status.flagToday("forest::vantiepSign");
                                    break;
                                }
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "vitalitySign err:", t);
        }
    }

    /** 森林7日签到 */
    static void queryCommonSign() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryCommonSign("ANTFOREST_GIFT7TH_SIGN_202506"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("forestSignVO")) {
                if (!Status.hasFlagToday("forest::CommonSign")) {
                    Status.flagToday("forest::CommonSign");
                    Log.i("森林签到📆尚未检测到[森林7日签到数据]若出现数据立马为大人领取");
                }
                return;
            }
            JSONObject forestSignVO = jo.getJSONObject("forestSignVO");
            String currentSignKey = forestSignVO.getString("currentSignKey"); // 当前签到的 key
            String signId = forestSignVO.getString("signId"); // 签到ID
            String sceneCode = forestSignVO.getString("sceneCode"); // 场景代码
            JSONArray signRecords = forestSignVO.getJSONArray("signRecords"); // 签到记录
            for (int i = 0; i < signRecords.length(); i++) { // 遍历签到记录
                JSONObject signRecord = signRecords.getJSONObject(i);
                String signKey = signRecord.getString("signKey");
                int awardCount = signRecord.getInt("awardCount");
                String awardType = signRecord.getString("awardType");
                JSONObject extInfo = signRecord.getJSONObject("extInfo");
                String awardName = extInfo.getString("awardName");
                if (signKey.equals(currentSignKey) && signRecord.getBoolean("signed")) {
                    // 服务端回读：本期 currentSignKey 已签 → 落当日标记
                    Status.flagToday("forest::vantiepSign");
                    break;
                }
                if (signKey.equals(currentSignKey) && !signRecord.getBoolean("signed")) {
                    JSONObject joSign = new JSONObject(AntForestRpcCall.antiepSign(signId, UserIdMap.getCurrentUid(), sceneCode));
                    TimeUtil.sleep(300); // 等待300毫秒
                    if (MessageUtil.checkSuccess(TAG + "森林7日签到:", joSign)) {
                        int continuousCount = joSign.getInt("continuousCount");
                        Log.forest("森林签到📆第" + continuousCount + "天#7日签到[" + awardName + "*" + awardCount + "]");
                        if (awardType.equals("ENERGY")) {
                            Statistics.addData(Statistics.DataType.COLLECTED, awardCount);
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "vitalitySign err:", t);
        }
    }

    /** 活力值签到（发的是活力值，不是能量） */
    static void vitalitySign() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.vitalitySign());
            TimeUtil.sleep(300);
            if (MessageUtil.checkResultCode(TAG, jo)) {
                int continuousCount = jo.getInt("continuousCount");
                int signAwardCount = jo.getInt("signAwardCount");
                Log.forest("森林任务📆签到[" + continuousCount + "天]奖励[" + signAwardCount + "活力值]");
            }
        } catch (Throwable t) {
            Log.err(TAG, "vitalitySign err:", t);
        }
    }

    /**
     * 青春特权森林道具路由。
     * <p>
     * firstTaskType 是查询用的分组标识，与要领取的 awardTaskType 不同；
     * source 需与 firstTaskType 配对传入，不可随意替换。
     */
    private static final class YouthForestRoute {
        final String firstTaskType;
        final String source;
        final String awardTaskType;
        final String displayName;

        YouthForestRoute(String firstTaskType, String source, String awardTaskType, String displayName) {
            this.firstTaskType = firstTaskType;
            this.source = source;
            this.awardTaskType = awardTaskType;
            this.displayName = displayName;
        }
    }

    private static final YouthForestRoute[] YOUTH_FOREST_ROUTES = {
            new YouthForestRoute("DNHZ_SL_college", "DNHZ_SL_college", "DAXUESHENG_SJK", "双击卡"),
            new YouthForestRoute("DXS_BHZ", "202212TJBRW", "NENGLIANGZHAO_20230807", "能量罩"),
            new YouthForestRoute("DXS_JSQ", "202212TJBRW", "JIAOSUQI_20230808", "加速器"),
    };

    static void queryVitalityYouthTaskList() {
        for (YouthForestRoute route : YOUTH_FOREST_ROUTES) {
            queryYouthForestTask(route);
        }
    }

    /**
     * 处理活力值 / 绿色行动任务列表（领奖 + 站内行为上报）。
     *
     * @param canWaterFriend    能否浇得动水（配置为不浇水 / 未选好友时为 false）
     * @param waterFriendEnergy 浇水动作
     */
    static void queryTaskList(BooleanSupplier canWaterFriend, Runnable waterFriendEnergy) {
        queryVitalityYouthTaskList();
        try {
            boolean doubleCheck = true;
            // 复核最多 5 轮：领奖/上报后要重取列表（新判定完成的任务要能接着处理），
            // 但服务端若对已处理任务仍回需要继续，这里就会无上限空转刷请求，故与下方循环同样设上限兜底。
            int listPass = 0;
            while (doubleCheck && listPass++ < 5) {
                JSONObject jo = new JSONObject(AntForestRpcCall.queryTaskList());
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    doubleCheck = false;
                    continue;
                }
                // 添加安全的空值判断
                if (!jo.has("forestTasksNew")) {
                    doubleCheck = false;
                    continue;
                }
                JSONArray forestTasksNew = jo.optJSONArray("forestTasksNew");
                if (forestTasksNew == null || forestTasksNew.length() == 0) {
                    doubleCheck = false;
                    continue;
                }
                for (int i = 0; i < forestTasksNew.length(); i++) {
                    JSONObject forestTask = forestTasksNew.optJSONObject(i);
                    if (forestTask == null) {
                        continue;
                    }
                    JSONArray taskInfoList = forestTask.optJSONArray("taskInfoList");
                    if (taskInfoList == null) {
                        continue;
                    }
                    doubleCheck = doForsetTaskList(taskInfoList, canWaterFriend, waterFriendEnergy);
                }
            }
            doubleCheck = true;
            // 复核最多 5 轮：领奖后要重取列表（新判定完成的子任务要能接着领），但服务端若对已领奖任务
            // 仍回 success，这里就会空转刷请求，故设上限兜底。
            int pass = 0;
            while (doubleCheck && pass++ < 5) {
                JSONObject jo = new JSONObject(AntForestRpcCall.listTaskopengreen());
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                // 添加安全的空值判断
                if (!jo.has("taskInfoList")) {
                    return;
                }
                JSONArray taskInfoList = jo.optJSONArray("taskInfoList");
                if (taskInfoList == null || taskInfoList.length() == 0) {
                    return;
                }
                doubleCheck = doForsetTaskList(taskInfoList, canWaterFriend, waterFriendEnergy);
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryTaskList err:", t);
        }
    }

    private static Boolean doForsetTaskList(JSONArray taskInfoList, BooleanSupplier canWaterFriend,
                                            Runnable waterFriendEnergy) {
        boolean doubleCheck = false;
        try {
            if (taskInfoList == null || taskInfoList.length() == 0) {
                return doubleCheck;
            }
            for (int j = 0; j < taskInfoList.length(); j++) {
                JSONObject taskInfo = taskInfoList.getJSONObject(j);
                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                String taskType = taskBaseInfo.getString("taskType");
                String taskTitle = bizInfo.optString("taskTitle", taskType);
                String sceneCode = taskBaseInfo.getString("sceneCode");
                String taskStatus = taskBaseInfo.getString("taskStatus");
                JSONArray childTaskTypeList = taskInfo.optJSONArray("childTaskTypeList");
                // 容器型父任务（taskNode=PARENT，如选教卡 ENERGY_XUANJIAO）：行为都在 childTaskTypeList 里，
                // 父任务自身只是一个任务组、没有完成接口。它的 taskStatus 常为 RECEIVED（任务组自身的奖已领），
                // 不能因此跳过子任务——否则子任务里「已完成但未领奖」的奖励永远领不到。
                if ("PARENT".equals(taskBaseInfo.optString("taskNode"))
                        && childTaskTypeList != null && childTaskTypeList.length() > 0) {
                    // 统一处理：先领「已完成但未领奖」的子任务，再按子任务自带的 autoCompleteTask
                    // 标记尝试待办项。不能按 taskType 白名单挑着做——未列举的容器会被静默跳过、
                    // 子任务永远没人领；也不能无条件尝试：行为子任务（缴费/出行等）需真实操作，
                    // 服务端只会回「不支持rpc调用」，尝试纯属白费请求。
                    boolean changed = awardFinishedChildren(childTaskTypeList);
                    changed = doChildTask(childTaskTypeList, taskTitle) || changed;
                    // 容器父任务自身的奖励同样要领：子任务可能都已领取，父任务却仍是 FINISHED 待领，
                    // 直接 continue 会把它永远漏掉。纯领奖、无副作用，不受开关约束。
                    if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                        changed = receiveTaskAward(sceneCode, taskType, taskTitle) || changed;
                    }
                    // 只有真动了才再核一遍。容器型父任务每轮都在列表里，无条件置 true 会让上层的
                    // while(doubleCheck) 空转。
                    if (changed) {
                        doubleCheck = true;
                    }
                    continue;
                }
                if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                    if (receiveTaskAward(sceneCode, taskType, taskTitle)) {
                        doubleCheck = true;
                    }
                } else if (TaskStatus.TODO.name().equals(taskStatus)) {
                    //黑名单任务跳过
                    if (getVitalityTaskBlackList().contains(taskTitle)) {
                        continue;
                    }
                    // 周期/持续型任务：由真实行为推进、常驻列表且无 RPC 可完成，跳过（不尝试、不拉黑）
                    if (TaskAttemptPolicy.isCyclicTask(taskBaseInfo)) {
                        // 周期任务常驻列表、由真实行为推进，每轮结果相同；静默跳过
                        continue;
                    }

                    // 站内行为类任务（浇水/收能量/能量雨）：完成条件是**服务端的行为计数**，
                    // 只能真做那个行为；上报与伪申报都推不动它，会白试一次后按"兜底未生效"拉黑
                    TaskAttemptPolicy.Forge forge = forestVitalityForge(taskType, sceneCode, taskTitle, bizInfo,
                            canWaterFriend, waterFriendEnergy);
                    // 做不了的当天只试一次、临时故障留待下一轮（见 TaskAttemptPolicy）
                    Outcome outcome = TaskAttemptPolicy.handle("forest::" + sceneCode + "/" + taskType, taskTitle, null,
                            () -> attemptFinishTask(sceneCode, taskType, taskTitle), Log::forest,
                            new TaskAttemptPolicy.Site("AntForestVitalityTaskList", "森林任务", taskType, sceneCode,
                                    TaskAlternative.DEFAULT_VERSION, forge,
                                    (k) -> probeForestVitalityStatus(sceneCode, taskType))
                                    // 本家值：任务场景 + ANTFOREST
                                    .withScene(sceneCode).withSource("ANTFOREST"));
                    doubleCheck = outcome == Outcome.DONE || outcome == Outcome.TRIGGERED;
                }
            }
            //可能是触发限时挑战奖励的
            AntForestRpcCall.batchQueryAndTouchopengreen();
        } catch (Throwable t) {
            Log.err(TAG, "doForsetTaskList err:", t);
        }
        return doubleCheck;
    }

    /**
     * 森林活力值任务的「站内行为」分派：能对上模块已有行为的才返回 forge，其余返回 null。
     * <p>判据取**任务名/描述**而非 {@code taskCategorization}：后者会把站内浇水这类行为也标成
     * {@code Exchange}，按它判会把这些任务当成交易类放过（既不伪造、又很快被拉黑）。
     * <p>真交易/履约类（缴费/出行/回收）由 {@code TaskAlternative.isTransactionTask} 在策略里先行拦下，
     * 到不了这里，故此处只需认得出"能做的事"。
     */
    private static TaskAttemptPolicy.Forge forestVitalityForge(String taskType, String sceneCode, String taskTitle,
                                                               JSONObject bizInfo, BooleanSupplier canWaterFriend,
                                                               Runnable waterFriendEnergy) {
        // 「进页面看一眼」类（EFFECTIVE_TASK）：完成条件就是这个上报动作本身，
        // 不消耗能量也不依赖好友列表，故优先且无条件尝试
        if (isTakeLookVitalityTask(taskType, taskTitle, bizInfo)) {
            return () -> {
                if (bizInfo != null && !bizInfo.optString("taskJumpUrl").isEmpty()) {
                    Log.forest("森林任务🧭[" + taskTitle + "]#外跳类，按'看一眼'上报");
                }
                AntForestRpcCall.takeLookEnd();
                return probeVitalityDone(sceneCode, taskType);
            };
        }
        // 真需要浇水的才走浇水，且要有可浇的对象：配置为"不浇水"或没选好友时浇不动，
        // 此时不伪造（否则等于谎报做过），留给下一轮
        if (isWaterVitalityTask(taskType, taskTitle) && canWaterFriend.getAsBoolean()) {
            return () -> {
                waterFriendEnergy.run();
                return probeVitalityDone(sceneCode, taskType);
            };
        }
        if (isEnergyRainVitalityTask(taskType, taskTitle)) {
            return () -> {
                // 活力值任务里的能量雨就是普通一局，按次卡/赠送额度走
                AntForestGame.startEnergyRain();
                return probeVitalityDone(sceneCode, taskType);
            };
        }
        return null;
    }

    /**
     * 「看一眼」类任务：标题里的动作（浇水/签到等）只是营销文案，真正的完成条件是
     * 上报一次"进过森林页面"。靠标题判会去做那件文案里的事——既白做，也不满足条件。
     * <p>除标题/类型外，还认服务端下发的两个明确信号：
     * <ul>
     *   <li>{@code taskAccEffectiveType=EFFECTIVE_TASK}：完成条件就是"进过页面"这个动作本身；</li>
     *   <li>{@code bizInfo} 带非空 {@code taskJumpUrl}：逛一逛/外跳类（如 {@code GYG_diantao} 淘宝）
     *       同为 EFFECTIVE_TASK，先按同一动作上报（模块打不开外部 H5，只能报"看过"）。</li>
     * </ul>
     */
    private static boolean isTakeLookVitalityTask(String taskType, String taskTitle, JSONObject bizInfo) {
        String type = taskType == null ? "" : taskType.toLowerCase();
        if (type.startsWith("zhrw_")
                || (taskTitle != null && (taskTitle.contains("免费拿") || taskTitle.contains("绿植")))) {
            return true;
        }
        if (bizInfo == null) {
            return false;
        }
        return "EFFECTIVE_TASK".equals(bizInfo.optString("taskAccEffectiveType"))
                || !bizInfo.optString("taskJumpUrl").isEmpty();
    }

    /**
     * 行为发出后等列表确认：行为类任务的完成要以任务列表为准，行为生效有延迟。
     * <p>不能无脑返回 true——那样会让策略立刻落当日标记，任务在**行为还没被服务端确认**时就被锁死，
     * 下一轮还会撞上"兜底未生效"的判据被拉黑。
     * <p>返回 false 只表示"本次没等到确认"，不代表行为没发出；日志据此如实措辞。
     */
    private static boolean probeVitalityDone(String sceneCode, String taskType) {
        for (int round = 0; round < VITALITY_PROBE_ROUNDS; round++) {
            TimeUtil.sleep(VITALITY_PROBE_INTERVAL_MS);
            if (TaskAttemptPolicy.isDone(probeForestVitalityStatus(sceneCode, taskType))) {
                return true;
            }
        }
        Log.forest("森林任务⏳[" + taskType + "]#行为已发但列表未确认，下次运行重试");
        return false;
    }

    /** 浇水类：{@code ZHRW_HUIYUANjiaoshui_202608}、一键浇水等 */
    private static boolean isWaterVitalityTask(String taskType, String taskTitle) {
        String type = taskType == null ? "" : taskType.toLowerCase();
        return type.contains("jiaoshui") || type.contains("watering")
                || (taskTitle != null && (taskTitle.contains("浇水") || taskTitle.contains("一键浇水")));
    }

    /** 能量雨类 */
    private static boolean isEnergyRainVitalityTask(String taskType, String taskTitle) {
        String type = taskType == null ? "" : taskType.toLowerCase();
        return type.contains("energyrain")
                || (taskTitle != null && taskTitle.contains("能量雨"));
    }

    private static void queryYouthForestTask(YouthForestRoute route) {
        if (Status.hasFlagToday("vitalityTask::" + route.firstTaskType)) {
            return;
        }
        try {
            String status = queryYouthForestTaskStatus(route);
            if (status.isEmpty()) {
                Log.forest("青春特权道具[跳过]" + route.displayName + " 未匹配到对应任务类型");
                return;
            }
            boolean isReceived = TaskStatus.RECEIVED.name().equals(status);
            if (!isReceived && TaskStatus.FINISHED.name().equals(status)) {
                // 领奖 RPC 照常执行,但不再把响应 JSON dump 进 forest 日志(成败以随后查到的 已领取/未领取 状态为准)
                AntForestRpcCall.receiveYouthPrivilegeTaskAward(route.source, route.awardTaskType);
                TimeUtil.sleep(1000);
                status = queryYouthForestTaskStatus(route);
                isReceived = TaskStatus.RECEIVED.name().equals(status);
            }
            if (isReceived) {
                Status.flagToday("vitalityTask::" + route.firstTaskType);
                Log.forest("青春特权道具[" + route.displayName + "]已领取");
            } else {
                Log.forest("青春特权道具[" + route.displayName + "]未领取 状态=" + status);
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryYouthForestTask err:", t);
        }
    }

    /**
     * 查询单个青春特权道具任务状态，返回 taskStatus；查不到返回空串。
     */
    private static String queryYouthForestTaskStatus(YouthForestRoute route) throws JSONException {
        JSONObject jo = new JSONObject(AntForestRpcCall.queryYouthPrivilegeTaskList(route.firstTaskType, route.source));
        JSONObject payload = jo.optJSONObject("resData");
        if (payload == null) {
            payload = jo;
        }
        for (JSONObject taskInfo : collectOpenGreenTaskInfos(payload)) {
            JSONObject taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo");
            if (taskBaseInfo == null || !Objects.equals(route.awardTaskType, taskBaseInfo.optString("taskType"))) {
                continue;
            }
            return taskBaseInfo.optString("taskStatus");
        }
        return "";
    }

    /**
     * 收集 listTaskopengreen 响应中所有层级的 taskInfo，兼容多种嵌套结构。
     */
    private static List<JSONObject> collectOpenGreenTaskInfos(JSONObject payload) {
        List<JSONObject> taskInfos = new ArrayList<>();
        appendTaskInfoList(taskInfos, payload.optJSONArray("taskInfoList"));
        appendTaskGroups(taskInfos, payload.optJSONArray("taskGroupList"));
        appendTaskGroups(taskInfos, payload.optJSONArray("forestTasksNew"));
        JSONObject result = payload.optJSONObject("result");
        if (result != null) {
            appendTaskInfoList(taskInfos, result.optJSONArray("taskInfoList"));
            appendTaskGroups(taskInfos, result.optJSONArray("taskGroupList"));
        }
        return taskInfos;
    }

    private static void appendTaskInfoList(List<JSONObject> taskInfos, JSONArray taskInfoList) {
        if (taskInfoList == null) {
            return;
        }
        for (int i = 0; i < taskInfoList.length(); i++) {
            JSONObject taskInfo = taskInfoList.optJSONObject(i);
            if (taskInfo != null) {
                taskInfos.add(taskInfo);
            }
        }
    }

    private static void appendTaskGroups(List<JSONObject> taskInfos, JSONArray groups) {
        if (groups == null) {
            return;
        }
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group != null) {
                appendTaskInfoList(taskInfos, group.optJSONArray("taskInfoList"));
            }
        }
    }

    private static Boolean receiveTaskAward(String sceneCode, String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.receiveTaskAward(sceneCode, taskType));
            TimeUtil.sleep(500);
            if (MessageUtil.checkSuccess(TAG, jo)) {
                int incAwardCount = jo.optInt("incAwardCount");
                String returnData = jo.optString("returnData").trim();
                // 权益型任务（选教卡子任务、TEST_LEAF_* 等）的响应形如
                // {"incAwardCount":1,"provideRightsSuccess":true,"returnData":"{}"}：**只有数量、没有单位**。
                // 按"不含 Energy 就当活力值"贴标签会把无量纲的数字记成活力值，故只记权益发放。
                if (returnData.isEmpty() || "{}".equals(returnData)) {
                    Log.forest("森林任务🎖️领取[" + taskTitle + "]#权益已发放[数量" + incAwardCount
                            + "]#响应未给单位");
                } else if (returnData.toUpperCase().contains("ENERGY")) {
                    Log.forest("森林任务🎖️领取[" + taskTitle + "]奖励#获得[" + incAwardCount + "g能量]");
                    Statistics.addData(Statistics.DataType.COLLECTED, incAwardCount);
                } else {
                    Log.forest("森林任务🎖️领取[" + taskTitle + "]奖励#获得[" + incAwardCount + "活力值]");
                }
                return true;
            }
            // 领奖收口：先按任务列表复核"已领到"（该调用点不拉黑）
            TimeUtil.sleep(800);
            if (TaskAward.confirmReceivedOrBlackList("森林任务🎖️领取",
                    k -> probeForestVitalityStatus(sceneCode, taskType), taskTitle, taskTitle, null,
                    msg -> Log.forest(msg))) {
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "receiveTaskAward err:", t);
        }
        return false;
    }

    /** 森林任务完成上报：拉黑与否仍交给自动拉黑机制（400000040 已降级为连续确认）。供乐园任务复用，故放开可见性。 */
    static Outcome attemptFinishTask(String sceneCode, String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.finishTask(sceneCode, taskType));
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntForestVitalityTaskList", AntForestV2.blackTaskKey(taskTitle), jo);
            TimeUtil.sleep(500);
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.forest("森林任务🧾️完成[" + taskTitle + "]");
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            // 不支持rpc调用（400000040）→ 由 TaskAttemptPolicy 代为伪申报：逛一逛类外部场景
            // （GYG_* / TAOBAO_* / NONGCHANG_* 等，如森林图书馆、淘宝人生、科普视频、点淘、淘宝特价版、
            // 芭芭农场、摇一摇、买菜、薅羊毛）；不再按 taskType 维护白名单
            if (TaskAlternative.hit(jo, sceneCode)) {
                return Outcome.UNSUPPORTED;
            }
            Log.forest("完成任务[" + taskTitle + "]失败");
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "finishTask err:", t);
        }
        return Outcome.RETRY;
    }

    /** 列表状态探针：重拉绿色行动任务列表，按 sceneCode+taskType 匹配该任务当前状态。 */
    private static TaskAttemptPolicy.ProbeResult probeForestVitalityStatus(String sceneCode, String taskType) {
        // 状态在 taskBaseInfo 子对象内
        return TaskAttemptPolicy.probeByList(TAG, AntForestRpcCall::listTaskopengreen, "taskInfoList",
                jo -> MessageUtil.checkResultCode(TAG, jo),
                taskInfo -> {
                    JSONObject taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo");
                    if (taskBaseInfo == null
                            || !sceneCode.equals(taskBaseInfo.optString("sceneCode"))
                            || !taskType.equals(taskBaseInfo.optString("taskType"))) {
                        return null;
                    }
                    return taskBaseInfo.optString("taskStatus");
                });
    }

    /**
     * 容器型父任务下「已完成但未领奖」的子任务领奖。
     * <p>这类容器的行为子任务被判定完成后不会自动发放，必须逐个 {@code receiveTaskAward}；
     * 只处理 TODO 子任务时，已完成的奖励会一直挂在列表里领不到（如 TEST_LEAF_TASK）。
     * <p>失败不记日志也不拉黑：与顶层 FINISHED 任务的领奖路径保持一致，避免把轮询变成噪声。
     *
     * @return 是否有子任务真领到奖励（供上层决定要不要再核一遍列表）
     */
    private static boolean awardFinishedChildren(JSONArray childTaskTypeList) {
        boolean awarded = false;
        try {
            for (int i = 0; i < childTaskTypeList.length(); i++) {
                JSONObject taskInfo = childTaskTypeList.getJSONObject(i);
                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                if (!TaskStatus.FINISHED.name().equals(taskBaseInfo.optString("taskStatus"))) {
                    continue;
                }
                JSONObject bizInfo = new JSONObject(taskBaseInfo.optString("bizInfo", "{}"));
                String taskType = taskBaseInfo.optString("taskType");
                String taskTitle = bizInfo.optString("taskTitle", taskType);
                String sceneCode = taskBaseInfo.optString("sceneCode");
                if (receiveTaskAward(sceneCode, taskType, taskTitle)) {
                    awarded = true;
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "awardFinishedChildren err:", th);
        }
        return awarded;
    }

    /** @return 本轮是否真上报了子任务（供上层判断要不要再核一遍列表） */
    private static boolean doChildTask(JSONArray childTaskTypeList, String title) {
        boolean changed = false;
        try {
            for (int i = 0; i < childTaskTypeList.length(); i++) {
                JSONObject taskInfo = childTaskTypeList.getJSONObject(i);
                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                String taskType = taskBaseInfo.getString("taskType");
                String taskTitle = bizInfo.optString("taskTitle", title);
                String sceneCode = taskBaseInfo.getString("sceneCode");
                String taskStatus = taskBaseInfo.getString("taskStatus");
                if (TaskStatus.TODO.name().equals(taskStatus)) {
                    if (bizInfo.optBoolean("autoCompleteTask")) {
                        // 走统一策略：400000040 时由 TaskAttemptPolicy 代为 doFarmTask 伪申报，
                        // 裸调 finishTask 会把 UNSUPPORTED 吞成 false，兜底永不触发
                        TaskAttemptPolicy.handle("forest::child::" + sceneCode + "/" + taskType, taskTitle, null,
                                () -> attemptFinishTask(sceneCode, taskType, taskTitle), Log::forest,
                                new TaskAttemptPolicy.Site("AntForestVitalityTaskList", "森林任务",
                                        taskType, sceneCode, (k) -> probeForestVitalityStatus(sceneCode, taskType))
                                        .withScene("ANTFOREST"));
                        changed = true;
                    }
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "doChildTask err:", th);
        }
        return changed;
    }
}
