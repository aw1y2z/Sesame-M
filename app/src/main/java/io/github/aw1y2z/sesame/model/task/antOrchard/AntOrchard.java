package io.github.aw1y2z.sesame.model.task.antOrchard;

import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.entity.AlipayAntOrchardTaskList;
import io.github.aw1y2z.sesame.entity.AlipayOrchardChouChouLeTaskList;
import io.github.aw1y2z.sesame.entity.AlipayMemberCreditSesameTaskList;
import io.github.aw1y2z.sesame.entity.AlipayPlantScene;
import io.github.aw1y2z.sesame.entity.AlipayUser;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ChoiceModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.model.task.antFarm.AntFarmRpcCall;
import io.github.aw1y2z.sesame.model.task.antGame.GameTask;
import io.github.aw1y2z.sesame.model.task.antMember.AntMemberRpcCall;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.aw1y2z.sesame.util.*;
import io.github.aw1y2z.sesame.util.idMap.AntFarmDoFarmTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntOrchardTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.OrchardChouChouLeTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.MemberCreditSesameTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.PlantSceneIdMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

import java.util.*;

import android.content.Context;
import android.content.Intent;

public class AntOrchard extends ModelTask {
    private static final String TAG = "AntOrchard";
    private static final String NAME = "农场";
    private static final ModelGroup GROUP = ModelGroup.ORCHARD;
    private String[] wuaList;
    private String userId;

    // 任务黑名单：某些广告/外跳类任务后端不支持 finishTask 或需要前端行为配合
    //groupId或者title
    private static final Set<String> ORCHARD_TASK_BLACKLIST = new HashSet<>();

    static {
        ORCHARD_TASK_BLACKLIST.add("ORCHARD_NORMAL_KUAISHOU_MAX");  // 逛一逛快手
        ORCHARD_TASK_BLACKLIST.add("ORCHARD_NORMAL_DIAOYU1");       // 钓鱼1次
        ORCHARD_TASK_BLACKLIST.add("ZHUFANG3IN1");                  // 添加农场小组件并访问
        ORCHARD_TASK_BLACKLIST.add("逛助农好货得肥料");                        // 逛助农好货得肥料
        ORCHARD_TASK_BLACKLIST.add("12173");                        // 买好货
        ORCHARD_TASK_BLACKLIST.add("70000");                        // 逛好物最高得1500肥料（XLIGHT）
        ORCHARD_TASK_BLACKLIST.add("TOUTIAO");                      // 逛一逛今日头条
        ORCHARD_TASK_BLACKLIST.add("ORCHARD_NORMAL_ZADAN10_3000");  // 农场对对碰
        ORCHARD_TASK_BLACKLIST.add("TAOBAO2");                      // 逛一逛闲鱼
        ORCHARD_TASK_BLACKLIST.add("ORCHARD_NORMAL_JIUYIHUISHOU_VISIT");  // 旧衣服回收
        ORCHARD_TASK_BLACKLIST.add("ORCHARD_NORMAL_SHOUJISHUMAHUISHOU");  // 数码回收
        ORCHARD_TASK_BLACKLIST.add("ORCHARD_NORMAL_AQ_XIAZAI");           // 下载AQ
        ORCHARD_TASK_BLACKLIST.add("ORCHARD_NORMAL_WAIMAIMIANDAN");      // 逛一逛闪购外卖
        ORCHARD_TASK_BLACKLIST.add("逛一逛签到领现金");      // 逛一逛签到领现金
    }

    // 模型字段定义
    private IntegerModelField executeInterval;
    private BooleanModelField orchardListTask;
    private BooleanModelField AutoAntOrchardTaskList;
    private SelectModelField AntOrchardTaskList;
    private BooleanModelField orchardSpreadManure;
    private BooleanModelField useBatchSpread;
    private SelectModelField orchardSpreadManureSceneList;
    private IntegerModelField orchardSpreadManureCount;

    private BooleanModelField orchardPlantNew;
    private BooleanModelField drawGameCenterAward;
    private ChoiceModelField driveAnimalType;
    private SelectModelField driveAnimalList;
    private BooleanModelField batchHireAnimal;
    private SelectModelField doNotHireList;
    private SelectModelField doNotWeedingList;
    private BooleanModelField assistFriend;
    private SelectModelField assistFriendList;
    private BooleanModelField orchardChouChouLe;
    private BooleanModelField AutoOrchardChouChouLeTaskList;
    private SelectModelField OrchardChouChouLeTaskList;
    private static int fertilizerProgress = 0;

    /** 本次施肥是否用一键5次批量（由 canSpreadManure 判定，doSpreadManure 消费） */
    private static boolean spreadUseBatchThisTime = false;

    /** 本轮是否已记过「已达次数上限」：主场景额度用完后每轮还会再查一次，只留一行 */
    private static boolean spreadLimitLoggedThisRun = false;
    private static final ArrayList<String> enableSceneList = new ArrayList<>();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ModelGroup getGroup() {
        return GROUP;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(executeInterval = new IntegerModelField("executeInterval", "执行间隔(毫秒)", 500, 500, null));
        modelFields.addField(orchardListTask = new BooleanModelField("orchardListTask", "农场任务", false));
        modelFields.addField(AutoAntOrchardTaskList = new BooleanModelField("AutoAntOrchardTaskList", "农场任务 | 自动黑名单", true).setDependsOn("orchardListTask"));
        modelFields.addField(AntOrchardTaskList = new SelectModelField("AntOrchardTaskList", "农场任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntOrchardTaskList::getList).setDependsOn("AutoAntOrchardTaskList"));
        modelFields.addField(orchardSpreadManure = new BooleanModelField("orchardSpreadManure", "农场施肥 | 开启", false));
        modelFields.addField(useBatchSpread = new BooleanModelField("useBatchSpread", "一键施肥5次(边界突破)", false)
                .setDependsOn("orchardSpreadManure")
                .setDescription("开启后全程一键5次：先单次补足到5的整数倍，之后批量推进，总次数精确等于每日次数；每日次数设为200时额外命中199+5漏洞突破到204（肥料不足5倍时退回单次）"));
        modelFields.addField(orchardSpreadManureSceneList = new SelectModelField("orchardSpreadManureSceneList", "农场施肥 | 场景列表", new LinkedHashSet<>(), AlipayPlantScene::getList)
                .setDependsOn("orchardSpreadManure")
                .setDescription("只对勾选且服务端已下发的场景施肥"));
        modelFields.addField(orchardSpreadManureCount = new IntegerModelField("orchardSpreadManureCount", "农场施肥 | 每日次数", 3, 1, 200)
                .setDependsOn("orchardSpreadManure")
                .setDescription("按轮计：开一键施肥5次时精确施肥到该值，设为200则突破到204；未开启时全程单次施肥到该值"));
        modelFields.addField(drawGameCenterAward = new BooleanModelField("drawGameCenterAward", "农场乐园 | 游戏宝箱", true));
        //modelFields.addField(driveAnimalType = new ChoiceModelField("driveAnimalType", "驱赶小鸡 | 动作", DriveAnimalType.NONE, DriveAnimalType.nickNames));
        //modelFields.addField(driveAnimalList = new SelectModelField("driveAnimalList", "驱赶小鸡 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList));
        //modelFields.addField(batchHireAnimal = new BooleanModelField("batchHireAnimal", "捉鸡除草 | 开启", false));
        //modelFields.addField(doNotHireList = new SelectModelField("doNotHireList", "捉鸡除草 | 不捉鸡列表", new LinkedHashSet<>(), AlipayUser::getList));
        //modelFields.addField(doNotWeedingList = new SelectModelField("doNotWeedingList", "捉鸡除草 | 不除草列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(assistFriend = new BooleanModelField("assistFriend", "分享助力 | 开启", false));
        modelFields.addField(assistFriendList = new SelectModelField("assistFriendList", "分享助力 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList).setDependsOn("assistFriend"));
        modelFields.addField(orchardChouChouLe = new BooleanModelField("orchardChouChouLe", "抽抽乐(阿肥寻宝记)", false));
        modelFields.addField(AutoOrchardChouChouLeTaskList = new BooleanModelField("AutoOrchardChouChouLeTaskList", "抽抽乐任务 | 自动黑名单", true).setDependsOn("orchardChouChouLe"));
        modelFields.addField(OrchardChouChouLeTaskList = new SelectModelField("OrchardChouChouLeTaskList", "抽抽乐任务 | 黑名单列表", new LinkedHashSet<>(), AlipayOrchardChouChouLeTaskList::getList).setDependsOn("AutoOrchardChouChouLeTaskList"));
        return modelFields;
    }

    @Override
    public Boolean check() {
        // 假设TaskCommon.IS_ENERGY_TIME存在
        // 如果没有这个字段，可以注释掉或创建
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.i("任务暂停⏸️芭芭农场:当前为只收能量时间");
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        try {
            userId = UserIdMap.getCurrentUid();
            if (!checkOrchardOpen()) {
                return;
            }

            //初始任务列表
            if (!Status.hasFlagToday("BlackList::initAntOrchard")) {
                initAntOrchardTaskListMap(AutoAntOrchardTaskList.getValue(), orchardListTask.getValue(), orchardChouChouLe.getValue(), AutoOrchardChouChouLeTaskList.getValue());
                Status.flagToday("BlackList::initAntOrchard");
            }
            // 额外信息获取（每日肥料包）
            extraInfoGet();

            // 执行农场任务
            if (orchardListTask.getValue()) {
                orchardListTask();
            }


            // 执行施肥逻辑
            if (orchardSpreadManure.getValue()) {
                orchardSpreadManure();
            }

            // 好友助力
            if (assistFriend.getValue()) {
                orchardAssistFriend();
            }

            // 农场抽抽乐
            if (orchardChouChouLe.getValue()) {
                orchardChouChouLe();
            }

        } catch (Throwable t) {
            Log.err(TAG, "start.run err:", t);
        }
    }

    /**
     * 农场抽抽乐（阿肥寻宝记 / 农场抽抽乐普通版）
     * 流程：进入活动 → 遍历场景领取已完成任务奖励 → 同步次数 → 循环抽奖至次数为 0
     */
    private void orchardChouChouLe() {
        try {
            String res = AntOrchardRpcCall.enterDrawActivityantorchard("", "ANTORCHARD_DRAW_TIMES", "antorchard");
            JSONObject resData = new JSONObject(res);
            if (!MessageUtil.checkSuccess(TAG, resData)) {
                return;
            }
            JSONArray drawSceneGroups = resData.optJSONArray("drawSceneGroups");
            if (drawSceneGroups == null) {
                JSONObject drawScene = resData.optJSONObject("drawScene");
                if (drawScene != null) {
                    drawSceneGroups = new JSONArray();
                    drawSceneGroups.put(drawScene);
                }
            }
            if (drawSceneGroups == null) {
                return;
            }
            for (int i = 0; i < drawSceneGroups.length(); i++) {
                JSONObject drawScene = drawSceneGroups.optJSONObject(i);
                if (drawScene == null) {
                    continue;
                }
                JSONObject drawActivity = drawScene.optJSONObject("drawActivity");
                if (drawActivity == null) {
                    continue;
                }
                String activityId = drawActivity.optString("activityId");
                String drawScenename = drawActivity.optString("name");
                String sceneCode = drawActivity.optString("sceneCode");
                orchardChouChouLeScene(activityId, drawScenename, sceneCode);
            }
        } catch (Throwable t) {
            Log.err(TAG, "orchardChouChouLe err:", t);
        }
    }

    private void orchardChouChouLeScene(String activityId, String drawScenename, String sceneCode) {
        try {
            boolean doublecheck;
            int loopCount = 0;
            final int MAX_LOOP = 7;
            do {
                doublecheck = false;
                String listRes = AntOrchardRpcCall.listTaskantorchard(sceneCode + "_TASK", "antorchard");
                JSONObject listTask = new JSONObject(listRes);
                if (!MessageUtil.checkSuccess(TAG, listTask)) {
                    break;
                }
                JSONArray taskList = listTask.optJSONArray("taskInfoList");
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
                    JSONObject bizInfo = new JSONObject(taskBaseInfo.optString("bizInfo", "{}"));
                    String taskName = bizInfo.optString("title");
                    // 黑名单任务跳过（含预置小游戏种子与自动拉黑项）
                    if (OrchardChouChouLeTaskList.getValue().contains(taskName)) {
                        continue;
                    }
                    String taskStatus = taskBaseInfo.optString("taskStatus");
                    String taskType = taskBaseInfo.optString("taskType");
                    String taskSceneCode = taskBaseInfo.optString("sceneCode");
                    JSONObject taskRights = taskInfo.optJSONObject("taskRights");
                    int rightsTimes = taskRights != null ? taskRights.optInt("rightsTimes") : 0;
                    int rightsTimesLimit = taskRights != null ? taskRights.optInt("rightsTimesLimit") : 0;

                    // 已完成任务领取奖励（如每日签到）
                    if ("FINISHED".equals(taskStatus)) {
                        TimeUtil.sleep(2000);
                        String awardRes = AntOrchardRpcCall.receiveDrawTaskAwardantorchard(taskSceneCode, taskType);
                        JSONObject sginRes = new JSONObject(awardRes);
                        if (MessageUtil.checkSuccess(TAG, sginRes)) {
                            int incAwardCount = sginRes.optInt("incAwardCount", 0);
                            Log.farm("农场抽抽乐🎖️[" + taskName + "]获得抽奖*" + incAwardCount);
                            if (rightsTimesLimit - rightsTimes > 0) {
                                doublecheck = true;
                            }
                        } else {
                            MessageUtil.checkResultCodeAndMarkTaskBlackList("OrchardChouChouLeTaskList", taskName, sginRes);
                        }
                    } else if ("TODO".equals(taskStatus)) {
                        // 第二种方式：尝试自动完成（小游戏/任务），两条腿互备，失败由 checkResultCodeAndMarkTaskBlackList 自动拉黑
                        TimeUtil.sleep(1000);
                        String userId = UserIdMap.getCurrentUid();
                        String finishRes = AntOrchardRpcCall.finishTaskantorchard(taskType, taskSceneCode);
                        JSONObject finishJo = new JSONObject(finishRes);
                        MessageUtil.checkResultCodeAndMarkTaskBlackList("OrchardChouChouLeTaskList", taskName, finishJo);
                        if (!MessageUtil.checkSuccess(TAG, finishJo)) {
                            finishRes = AntOrchardRpcCall.finishTaskantorchardV2(taskType, taskSceneCode, userId);
                            finishJo = new JSONObject(finishRes);
                            MessageUtil.checkResultCodeAndMarkTaskBlackList("OrchardChouChouLeTaskList", taskName, finishJo);
                        }
                        if (MessageUtil.checkSuccess(TAG, finishJo)) {
                            Log.farm("农场抽抽乐🧾完成[" + taskName + "]");
                            doublecheck = true;
                        } else {
                            Log.farm("农场抽抽乐⚠️未完成[" + taskName + "]#taskType=" + taskType);
                        }
                    }
                }
            } while (doublecheck && ++loopCount < MAX_LOOP);

            // 同步抽奖次数
            AntOrchardRpcCall.drawSyncantorchard(activityId, "taskaward");

            // 抽奖
            final int MAX_DRAW_LOOP = 30;
            JSONObject jo = new JSONObject(AntOrchardRpcCall.enterDrawActivityantorchard(activityId, sceneCode, "antorchard"));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONObject drawAsset = jo.optJSONObject("drawAsset");
            if (drawAsset == null) {
                return;
            }
            int blance = drawAsset.optInt("blance", 0);
            int drawLoop = 0;
            while (blance > 0 && ++drawLoop <= MAX_DRAW_LOOP) {
                jo = new JSONObject(AntOrchardRpcCall.drawantorchard(activityId, sceneCode, "antorchard", userId));
                if (MessageUtil.checkSuccess(TAG, jo)) {
                    drawAsset = jo.optJSONObject("drawAsset");
                    if (drawAsset == null) {
                        break;
                    }
                    blance = drawAsset.optInt("blance", 0);
                    JSONObject prizeVO = jo.optJSONObject("prizeVO");
                    String prizeName = prizeVO != null ? prizeVO.optString("prizeName", "未知") : "未知";
                    Log.farm("农场抽抽乐🎰️[" + drawScenename + "]抽奖:" + prizeName);
                    TimeUtil.sleep(2000);
                } else {
                    break;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "orchardChouChouLeScene err:", t);
        }
    }

    /**
     * 检查农场是否已开启
     */
    private boolean checkOrchardOpen() {
        try {
            JSONObject jo = new JSONObject(AntOrchardRpcCall.orchardIndex());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }

            if (!jo.optBoolean("userOpenOrchard")) {
                getEnableField().setValue(false);
                Log.record("请先开启芭芭农场！");
                return false;
            }

            // 处理七日礼包
            if (jo.has("lotteryPlusInfo")) {
                drawLotteryPlus(jo.getJSONObject("lotteryPlusInfo"));
            }

            //获取场景列表
            initPlantScene(jo);

            // 处理可用场景列表
            handleEnableScenes(jo);

            // 处理淘宝数据（果树状态）
            handleTaobaoData(jo.getString("taobaoData"));

            // 处理金蛋
            if (drawGameCenterAward.getValue()) {
                JSONObject goldenEggInfo = jo.optJSONObject("goldenEggInfo");
                if (goldenEggInfo != null) {
                    int unsmashedGoldenEggs = goldenEggInfo.optInt("unsmashedGoldenEggs");
                    int limit = goldenEggInfo.optInt("goldenEggLimit");
                    int smashed = goldenEggInfo.optInt("smashedGoldenEggs");

                    if (unsmashedGoldenEggs > 0) {
                        // 现成的蛋先砸了
                        smashedGoldenEgg(unsmashedGoldenEggs);
                    } else {
                        int remain = limit - smashed;
                        if (remain > 0) {
                            GameTask.Orchard_ncscc.report("农场", remain);
                        }
                    }
                }
                queryOptionalPlay();
            }
            // 处理回访/浏览奖励：官方每次进农场都会调一次（2026-09-22 抓包实证）；
            // 不再加"每日一次"标记——首调可能在奖励还没产生时就把当天标记掉，反而漏领
            receiveOrchardVisitAward();

            return true;
        } catch (Throwable t) {
            Log.err(TAG, "orchardIndex err:", t);
            return false;
        }
    }

    //乐园限定活动
    private void queryOptionalPlay() {
        try {
            JSONObject jo = new JSONObject(AntOrchardRpcCall.queryOptionalPlay());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            if (!jo.has("taskTriggerPlayInfo")) {
                return;
            }
            JSONObject taskTriggerPlayInfo = jo.optJSONObject("taskTriggerPlayInfo");
            if (!taskTriggerPlayInfo.has("taskList")) {
                return;
            }
            JSONArray taskList = taskTriggerPlayInfo.getJSONArray("taskList");
            for (int j = 0; j < taskList.length(); j++) {
                JSONObject task = taskList.getJSONObject(j);
                String taskType = task.getString("taskType");
                String taskStatus = task.getString("taskStatus");
                String sceneCode = task.getString("sceneCode");
                int alreadyReceiveAwardCount = task.optInt("alreadyReceiveAwardCount");
                int awardCount = task.optInt("awardCount");
                int awardCountForReceive = awardCount - alreadyReceiveAwardCount;
                JSONObject bizInfo = task.getJSONObject("bizInfo");
                String title = bizInfo.getString("title");
                if (taskStatus.equals("FINISHED")) {
                    if (awardCountForReceive > 0) {
                        JSONObject joReceived = new JSONObject(AntOrchardRpcCall.receiveTaskAwardantorchard(awardCountForReceive, sceneCode, taskType));
                        if (MessageUtil.checkSuccess(TAG, joReceived)) {
                            int incAwardCount = joReceived.optInt("incAwardCount");
                            JSONObject taskConfigResultVO = joReceived.optJSONObject("taskConfigResultVO");
                            String awardType = taskConfigResultVO.getString("awardType");
                            Log.farm("农场乐园🎖️领取[" + title + "]奖励[" + awardType + "*" + incAwardCount + "]");
                        }
                    }
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "queryOptionalPlay err:", th);
        }
    }

    public static void initAntOrchardTaskListMap(boolean AutoAntOrchardTaskList, boolean orchardListTask, boolean orchardChouChouLe, boolean AutoOrchardChouChouLeTaskList) {
        try {
            //初始化AntOrchardTaskListMap
            AntOrchardTaskListMap.load();
            // 1. 定义黑名单（需要添加的任务）和白名单（需要移除的任务）
            // 注：浏览/外跳类不再预置拉黑，交由自动拉黑机制判定；
            // 需真实完成或存在风险的（旧衣回收、数码回收、下载APP、快手、签到领现金）保留
            Set<String> blackList = new HashSet<>();
            blackList.add("完成1笔旧衣回收");
            blackList.add("下载蚂蚁阿福看健康攻略");
            blackList.add("完成1单手机数码回收");
            blackList.add("逛一逛快手");
            blackList.add("逛一逛签到领现金");
            // 可继续添加更多黑名单任务

            Set<String> whiteList = new HashSet<>();// 从黑名单中移除该任务
            //whiteList.add("逛一芝麻树");
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                AntOrchardTaskListMap.add(task, task);
            }

            if (orchardListTask) {
                String result = AntOrchardRpcCall.orchardListTask();
                JSONObject jo = new JSONObject(result);
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    JSONArray taskArray = jo.getJSONArray("taskList");
                    for (int i = 0; i < taskArray.length(); i++) {
                        jo = taskArray.getJSONObject(i);
                        JSONObject displayConfig = jo.optJSONObject("taskDisplayConfig");
                        if (displayConfig.has("title")) {
                            String title = displayConfig.optString("title");
                            AntOrchardTaskListMap.add(title, title);
                        }
                    }
                }
                //保存任务到配置文件
                AntOrchardTaskListMap.save();
                Log.record("同步任务🉑农芭芭场肥料任务列表");

                //自动按模块初始化设定调整黑名单和白名单
                if (AutoAntOrchardTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntOrchard = config.getModelFieldsMap().get("AntOrchard");
                    SelectModelField AntOrchardTaskList = (SelectModelField) AntOrchard.get("AntOrchardTaskList");
                    if (AntOrchardTaskList == null) {
                        return;
                    }

                    // 2~4. 批量写回黑/白名单并保存
                    MessageUtil.syncTaskBlackList("芭芭农场肥料任务", "AntOrchardTaskList", blackList, whiteList, AntOrchardTaskList);
                }
            }

            // ============ 农场抽抽乐任务黑名单初始化 ============
            OrchardChouChouLeTaskListMap.load();
            Set<String> chouChouLeBlackList = new HashSet<>();
            Set<String> chouChouLeWhiteList = new HashSet<>();
            if (orchardChouChouLe) {
                String res = AntOrchardRpcCall.enterDrawActivityantorchard("", "ANTORCHARD_DRAW_TIMES", "antorchard");
                JSONObject resData = new JSONObject(res);
                if (MessageUtil.checkSuccess(TAG, resData)) {
                    JSONArray drawSceneGroups = resData.optJSONArray("drawSceneGroups");
                    if (drawSceneGroups == null) {
                        JSONObject drawScene = resData.optJSONObject("drawScene");
                        if (drawScene != null) {
                            drawSceneGroups = new JSONArray();
                            drawSceneGroups.put(drawScene);
                        }
                    }
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
                            String sceneCode = drawActivity.optString("sceneCode");
                            String listRes = AntOrchardRpcCall.listTaskantorchard(sceneCode + "_TASK", "antorchard");
                            JSONObject listTask = new JSONObject(listRes);
                            if (MessageUtil.checkSuccess(TAG, listTask)) {
                                JSONArray taskList = listTask.optJSONArray("taskInfoList");
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
                                        JSONObject bizInfo = new JSONObject(taskBaseInfo.optString("bizInfo", "{}"));
                                        String taskName = bizInfo.optString("title");
                                        if (!taskName.isEmpty()) {
                                            OrchardChouChouLeTaskListMap.add(taskName, taskName);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                OrchardChouChouLeTaskListMap.save();
                Log.record("同步任务🉑农场抽抽乐任务列表");
                if (AutoOrchardChouChouLeTaskList) {
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntOrchardFields = config.getModelFieldsMap().get("AntOrchard");
                    SelectModelField OrchardChouChouLeTaskListField = (SelectModelField) AntOrchardFields.get("OrchardChouChouLeTaskList");
                    if (OrchardChouChouLeTaskListField == null) {
                        return;
                    }
                    MessageUtil.syncTaskBlackList("农场抽抽乐任务", "OrchardChouChouLeTaskList", chouChouLeBlackList, chouChouLeWhiteList, OrchardChouChouLeTaskListField);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "initAntOrchardTaskListMap err:", t);
        }
    }

    /**
     * 处理可用场景列表
     */

    public static void initPlantScene(JSONObject jo) {
        try {
            JSONArray sceneArray = jo.getJSONArray("enableSwitchSceneList");
            if (sceneArray == null) {
                return;
            }
            PlantSceneIdMap.load();
            for (int i = 0; i < sceneArray.length(); i++) {
                String scene = sceneArray.getString(i);
                PlantSceneIdMap.add(scene, getSceneDisplayName(scene));
            }
            PlantSceneIdMap.save();
        } catch (Throwable t) {
            Log.err(TAG, "initPlantScene err:", t);
        }
    }

    private static String getSceneDisplayName(String scene) {
        switch (scene) {
            case "main": return "果树";
            case "yeb": return "金钱树";
            default: return scene;
        }
    }

    private void handleEnableScenes(JSONObject jo) {
        try {

            JSONArray sceneArray = jo.getJSONArray("enableSwitchSceneList");
            enableSceneList.clear();
            for (int i = 0; i < sceneArray.length(); i++) {
                String scene = sceneArray.getString(i);
                enableSceneList.add(scene);

                // 主场景处理
                if ("main".equals(scene)) {
                    if (jo.getString("currentPlantScene").equals(scene) || switchPlantScene(PlantScene.main)) {
                        // 处理限时挑战活动
                        //limitedTimeChallenge();
                        //querySubplotsActivity("WISH");
                        //querySubplotsActivity("CAMP_TAKEOVER");
                    }
                }

                // 余额宝场景处理
                if ("yeb".equals(scene)) {
                    JSONObject yebInfo = jo.getJSONObject("yebSceneActivityInfo");
                    if ("NOT_PLANTED".equals(yebInfo.getString("yebSceneStatus"))) {
                        enableSceneList.remove(scene);
                    } else if (yebInfo.optBoolean("revenueNotReceived")) {
                        queryYebRevenueDetail();
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "handleEnableScenes err:", t);
        }
    }

    /**
     * 处理淘宝数据（果树生长状态）
     */
    private void handleTaobaoData(String taobaoData) {
        try {
            JSONObject jo = new JSONObject(taobaoData);
            JSONObject plantInfo = jo.getJSONObject("gameInfo").getJSONObject("plantInfo");
            JSONObject seedStage = plantInfo.getJSONObject("seedStage");

            // 检查是否可兑换
            if (plantInfo.getBoolean("canExchange")) {
                Log.i("农场果树似乎可以兑换了！");
                Toast.show("芭芭农场果树似乎可以兑换了！");
            }
            // 更新施肥进度
            if (seedStage.has("totalValue")) {
                fertilizerProgress = seedStage.getInt("totalValue");
            }
        } catch (Throwable t) {
            Log.err(TAG, "handleTaoBaoData err:", t);
        }
    }

    /**
     * 农场施肥逻辑
     */
    private void orchardSpreadManure() {
        try {
            spreadLimitLoggedThisRun = false;
            // 轮次上限兜底：每轮最多施肥一次，按「上限/批量步长 + 余量」估算并留足两场景的量
            final int MAX_SPREAD_ROUND = (MAIN_SPREAD_DAILY_LIMIT / BATCH_SPREAD_SIZE + 10) * 8;
            int round = 0;
            for (; round < MAX_SPREAD_ROUND; round++) {
                boolean hasSpread = false;
                boolean anySceneQualified = false;
                // 遍历可用场景进行施肥
                for (PlantScene scene : PlantScene.getEntries()) {
                    if (enableSceneList.contains(scene.name()) && orchardSpreadManureSceneList.contains(scene.name()) && targetSpreadTimes() > 0) {
                        anySceneQualified = true;
                        // 切换场景
                        if (!switchPlantScene(scene)) {
                            Log.record("农场施肥⏭️切换场景失败[" + scene.name() + "]");
                            continue;
                        }
                        // 检查是否可施肥
                        if (!canSpreadManure(scene)) {
                            continue;
                        }
                        // 执行施肥
                        if (doSpreadManure(scene)) {
                            hasSpread = true;
                            break;
                        }
                    }
                }

                // 场景没对上（服务端未下发该场景/配置里没勾）时静默跳过，留一行便于定位
                if (!anySceneQualified) {
                    Log.record("农场施肥⏭️场景未启用#可用" + enableSceneList + "#配置" + orchardSpreadManureSceneList);
                }

                // 查询施肥活动奖励
                querySpreadManureActivity();

                // 等待间隔时间
                int interval = executeInterval.getValue() != null ? executeInterval.getValue() : 500;
                TimeUtil.sleep(interval);

                if (!hasSpread) {
                    break;
                }
            }
            if (round >= MAX_SPREAD_ROUND) {
                Log.record("农场施肥⏭️已达单轮循环上限[" + MAX_SPREAD_ROUND + "]，本轮停止");
            }
        } catch (Throwable t) {
            Log.err(TAG, "orchardSpreadManure err:", t);
        }
    }

    /**
     * 执行施肥操作
     */
    private boolean doSpreadManure(PlantScene scene) {
        try {
            String sceneName = scene.name();
            String wua = getWua();
            String result = AntOrchardRpcCall.orchardSpreadManure(sceneName, spreadUseBatchThisTime, wua);
            JSONObject jo = new JSONObject(result);

            // 场景侧的业务拒绝（P03 场景未就绪 / P14 摇钱树已达持仓金额上限）再请求也不会变，
            // 打当日标记后当天不再重放批量请求，也避免被当成 error 打印
            String resultCode = jo.optString("resultCode", "");
            if ("P03".equals(resultCode) || "P14".equals(resultCode)) {
                Status.flagToday("spreadManureLimit:" + sceneName, userId);
                Log.record("农场施肥⏭️[" + sceneName + "]被拒：" + jo.optString("memo", resultCode));
                return false;
            }

            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }

            JSONObject taobaoData = new JSONObject(jo.getString("taobaoData"));
            int cost = taobaoData.getInt("currentCost");
            boolean batch = spreadUseBatchThisTime;
            Log.farm("芭芭农场🌳" + scene.nickname() + "施肥#消耗[" + cost + "g肥料]"
                    + (batch ? "#一键5次" : "") + "#目标[" + targetSpreadTimes() + "次]");

            // 检查施肥进度：单次只加 0.01%(1) 或 一键5次只加 0.05%(5) 即被限制施肥，当天不再施肥
            if (taobaoData.has("currentStage")) {
                JSONObject stage = taobaoData.getJSONObject("currentStage");
                int newProgress = stage.optInt("totalValue", fertilizerProgress);
                int delta = newProgress - fertilizerProgress;
                int spreadTimes = batch ? BATCH_SPREAD_SIZE : 1;
                // delta 为负＝跨场景或基线过期，不作判据（判了会把没被限制的场景也停掉）
                if (delta >= 0 && delta <= spreadTimes) {
                    Log.record("施肥" + (batch ? "一键5次只加0.05%" : "1次只加0.01%") + "进度今日停止施肥！");
                    Status.flagToday("spreadManureLimit:" + sceneName, userId);
                }
                fertilizerProgress = newProgress;
            }
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "doSpreadManure err:", t);
            return false;
        }
    }


    public String getWua() {
        if (wuaList == null) {
            try {
                String content = FileUtil.readFromFile(FileUtil.getWuaFile());
                if (content != null && !content.trim().isEmpty()) {
                    wuaList = content.split("\n");
                } else {
                    wuaList = new String[0];
                }
            } catch (Throwable ignored) {
                wuaList = new String[0];
            }
        }
        if (wuaList.length > 0) {
            // 修复：修正数组索引边界
            int index = RandomUtil.nextInt(0, wuaList.length);
            return wuaList[index];
        }
        return ""; // 返回空字符串而不是null
    }

    /** 服务端每日施肥次数名义上限（单次计，`wateringLeftTimes` 是剩余次数） */
    private static final int MAIN_SPREAD_DAILY_LIMIT = 200;

    /** 一键施肥一次顶 5 次（服务端按单次累加已施肥次数） */
    private static final int BATCH_SPREAD_SIZE = 5;

    /**
     * 利用边界漏洞可达的实际日上限：已施肥达 {@code MAIN_SPREAD_DAILY_LIMIT - 1}（199）次后，
     * 再用一次「一键5次」批量，服务端允许该批量 5 次全部生效，总次数 = 199 + 5 = 204。
     * 主场景与余额宝(yeb)场景通用。
     */
    private static final int MAIN_SPREAD_BURST_LIMIT = MAIN_SPREAD_DAILY_LIMIT - 1 + BATCH_SPREAD_SIZE; // 199 + 5 = 204

    /**
     * 「每日次数」折算成服务端的单次施肥次数目标，封顶漏洞可达上限 204。
     * <p>开启一键5次时由 {@link #shouldBatchSpread} 决定批量时机，恰好推进到该目标；
     * 未开启则全程单次推进。
     */
    private int targetSpreadTimes() {
        Integer limit = orchardSpreadManureCount.getValue();
        int times = limit == null ? 0 : Math.max(limit, 0);
        return Math.min(times, MAIN_SPREAD_BURST_LIMIT);
    }

    /**
     * 批量对齐目标：配到名义上限 200 时取漏洞上限 204，其余取配置值本身。
     */
    private static int batchAlignTarget(int limit) {
        return limit >= MAIN_SPREAD_DAILY_LIMIT ? MAIN_SPREAD_BURST_LIMIT : limit;
    }

    /**
     * 本次是否走一键5次：对齐目标的剩余次数恰为 5 的整数倍时才发批量，
     * 余数先由单次补足，因此总数精确落在对齐目标上且不会越过（服务端已有非整倍数计数也能自适应）。
     */
    private static boolean shouldBatchSpread(int limit, int usedTimes) {
        int remain = batchAlignTarget(limit) - usedTimes;
        return remain > 0 && remain % BATCH_SPREAD_SIZE == 0;
    }

    /**
     * 检查是否可以施肥
     */
    private boolean canSpreadManure(PlantScene scene) {
        // 检查是否达到今日限制
        if (Status.hasFlagToday("spreadManureLimit:" + scene.name())) {
            return false;
        }

        int limit = targetSpreadTimes();
        if (limit <= 0) {
            return false;
        }

        try {
            switch (scene) {
                case main:
                    // 主场景施肥检查
                    JSONObject mainAccount = new JSONObject(AntOrchardRpcCall.orchardSyncIndex());
                    if (!MessageUtil.checkResultCode(TAG, mainAccount)) {
                        return false;
                    }
                    JSONObject accountInfo = mainAccount.getJSONObject("farmMainAccountInfo");
                    int happyPoint = Integer.parseInt(accountInfo.getString("happyPoint"));
                    int wateringCost = accountInfo.getInt("wateringCost");
                    int leftTimes = accountInfo.getInt("wateringLeftTimes");
                    int usedTimes = MAIN_SPREAD_DAILY_LIMIT - leftTimes;

                    // 剩余次数为 5 的整数倍时才批量，余数先单次补足，总数精确且不会越过；
                    // 配到 200 时对齐目标取漏洞上限 204，计数序列 4,9,…,199,204 恰在 199 处 +5 突破
                    boolean batch = Boolean.TRUE.equals(useBatchSpread.getValue())
                            && shouldBatchSpread(limit, usedTimes);
                    // 一键5次时服务端一次要消耗 5 倍肥料，余额判据必须按批量算，否则会发出注定失败的请求
                    int needCost = batch ? wateringCost * BATCH_SPREAD_SIZE : wateringCost;
                    if (happyPoint < needCost) {
                        if (batch) {
                            // 肥料不足 5 倍：退回单次（若还能施单次）
                            batch = false;
                            needCost = wateringCost;
                            if (happyPoint < needCost) {
                                Log.record("农场施肥⏭️肥料不足[" + happyPoint + "/" + needCost + "g]");
                                return false;
                            }
                        } else {
                            Log.record("农场施肥⏭️肥料不足[" + happyPoint + "/" + needCost + "g]");
                            return false;
                        }
                    }
                    if (usedTimes >= limit) {
                        if (!spreadLimitLoggedThisRun) {
                            spreadLimitLoggedThisRun = true;
                            Log.record("农场施肥⏭️已达次数上限[" + usedTimes + "/" + limit + "]");
                        }
                        return false;
                    }
                    spreadUseBatchThisTime = batch;
                    return true;

                case yeb:
                    // 余额宝场景施肥检查
                    JSONObject yebProgress = new JSONObject(AntOrchardRpcCall.orchardIndex());
                    if (!MessageUtil.checkResultCode(TAG, yebProgress) || !yebProgress.has("yebScenePlantInfo")) {
                        return false;
                    }
                    JSONObject progressInfo = yebProgress.getJSONObject("yebScenePlantInfo").getJSONObject("plantProgressInfo");
                    int currentProgress = progressInfo.getInt("spreadProgress");

                    // 与主场景同一套对齐规则；余额宝同样适用 199+5 漏洞
                    boolean batchY = Boolean.TRUE.equals(useBatchSpread.getValue())
                            && shouldBatchSpread(limit, currentProgress);
                    // yeb 肥料与主账号同池，仅在批量（需 5 倍）时取主账号余额做判据
                    if (batchY) {
                        JSONObject yebMain = new JSONObject(AntOrchardRpcCall.orchardSyncIndex());
                        if (!MessageUtil.checkResultCode(TAG, yebMain)) {
                            // 同步校验失败拿不到余额：保守退回单次，避免发出注定失败的 5 倍批量
                            batchY = false;
                        } else {
                            JSONObject yai = yebMain.getJSONObject("farmMainAccountInfo");
                            int happyPointY = Integer.parseInt(yai.getString("happyPoint"));
                            int wateringCostY = yai.getInt("wateringCost");
                            if (happyPointY < wateringCostY * BATCH_SPREAD_SIZE) {
                                // 肥料不足 5 倍：退回单次（沿用原 yeb 不判肥料，直接允许单次）
                                batchY = false;
                            }
                        }
                    }
                    // 修正原 `limit < dailyLimit`：允许推进到漏洞上限 204（dailyLimit 名义 200），避免 yeb 完全不施肥
                    if (currentProgress >= limit) {
                        return false;
                    }
                    spreadUseBatchThisTime = batchY;
                    return true;

                default:
                    return false;
            }
        } catch (Throwable t) {
            Log.err(TAG, "canSpreadManure err:", t);
            return false;
        }
    }

    /**
     * 切换种植场景
     */
    private boolean switchPlantScene(PlantScene scene) {
        try {
            String sceneName = scene.name();
            String result = AntOrchardRpcCall.switchPlantScene(sceneName);
            return MessageUtil.checkResultCode(TAG, new JSONObject(result));
        } catch (Throwable t) {
            Log.err(TAG, "switchPlantScene err:", t);
            return false;
        }
    }

    /**
     * 查询施肥活动奖励
     */
    private void querySpreadManureActivity() {
        try {
            JSONObject jo = new JSONObject(AntOrchardRpcCall.orchardIndex());
            if (MessageUtil.checkResultCode(TAG, jo) && jo.has("spreadManureActivity")) {
                JSONObject activity = jo.getJSONObject("spreadManureActivity");
                JSONObject stage = activity.getJSONObject("spreadManureStage");
                if ("FINISHED".equals(stage.getString("status"))) {
                    String result = AntOrchardRpcCall.receiveTaskAward(stage.getString("sceneCode"), stage.getString("taskType"));
                    JSONObject awardJo = new JSONObject(result);
                    if (MessageUtil.checkResultCode(TAG, awardJo)) {
                        int awardCount = awardJo.getInt("incAwardCount");
                        Log.farm("芭芭农场🎁丰收礼包#获得[" + awardCount + "g肥料]");
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "querySpreadManureActivity err:", t);
        }
    }

    /**
     * 农场任务列表处理
     */
    private void orchardListTask() {
        try {
            String result = AntOrchardRpcCall.orchardListTask();
            JSONObject jo = new JSONObject(result);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }

            boolean inTeam = jo.optBoolean("inTeam", false);
            Log.record(inTeam ? "当前为芭芭农场 team 模式（合种/帮帮种已开启）" : "当前为普通单人农场模式");

            // 处理签到任务
            if (jo.has("signTaskInfo")) {
                handleSignTask(jo.getJSONObject("signTaskInfo"));
            }

            // 处理任务列表
            JSONArray taskArray = jo.getJSONArray("taskList");
            handleTaskList(taskArray);

            // 触发已完成任务的奖励
            triggerTbTask();
        } catch (Throwable t) {
            Log.err(TAG, "orchardListTask err:", t);
        }
    }

    /**
     * 处理签到任务
     */
    private void handleSignTask(JSONObject signInfo) {
        if (Status.hasFlagToday("orchardSign")) {
            return;
        }

        try {
            JSONObject currentSign = signInfo.getJSONObject("currentSignItem");
            if (currentSign.getBoolean("signed")) {
                Log.record("农场今日已签到");
                Status.flagToday("orchardSign", userId);
                return;
            }

            // 执行签到
            String result = AntOrchardRpcCall.orchardSign();
            JSONObject signJo = new JSONObject(result);
            if (MessageUtil.checkResultCode(TAG, signJo)) {
                JSONObject newSignInfo = signJo.getJSONObject("signTaskInfo").getJSONObject("currentSignItem");
                int continuousDays = newSignInfo.getInt("currentContinuousCount");
                int award = newSignInfo.getInt("awardCount");
                Log.farm("农场任务📅七天签到[第" + continuousDays + "天]#获得[" + award + "g肥料]");
                Status.flagToday("orchardSign", userId);
            }
        } catch (Throwable t) {
            Log.err(TAG, "handleSignTask err:", t);
        }
    }

    /**
     * 处理任务列表
     */
    private void handleTaskList(JSONArray taskArray) {
        try {
            for (int i = 0; i < taskArray.length(); i++) {
                JSONObject jo = taskArray.getJSONObject(i);
                String taskStatus = jo.getString("taskStatus");
                if (TaskStatus.RECEIVED.name().equals(taskStatus)) {
                    continue;
                }

                // 跳过黑名单任务
                String groupId = jo.optString("groupId", "");
                JSONObject displayConfig = jo.optJSONObject("taskDisplayConfig");
                String title = displayConfig != null ? displayConfig.optString("title", "未知任务") : "未知任务";
                if (AntOrchardTaskList.getValue().contains(title)) {
                    continue;
                }

                if (TaskStatus.TODO.name().equals(taskStatus)) {
                    if (!finishOrchardTask(jo)) {
                        continue;
                    }
                    TimeUtil.sleep(500);
                }

                // 处理已完成的任务奖励（已在triggerTbTask中统一处理）
            }
            // 核对本轮 doFarmTask 的结果（响应不可信，以任务列表为准）
            verifyPendingTasksByList();
        } catch (Throwable t) {
            Log.err(TAG, "handleTaskList err:", t);
        }
    }

    /**
     * 完成农场任务
     */
    private boolean finishOrchardTask(JSONObject task) {
        try {
            if (!task.has("taskDisplayConfig")) {
                return false;
            }
            if (!task.getJSONObject("taskDisplayConfig").has("title")) {
                return false;
            }
            String title = task.getJSONObject("taskDisplayConfig").getString("title");
            String actionType = task.getString("actionType");
            String sceneCode = task.optString("sceneCode");
            String taskId = task.optString("taskId");

            // 处理广告任务（VISIT、XLIGHT类型）
            if ("VISIT".equals(actionType) || "XLIGHT".equals(actionType)) {
                int rightsTimes = task.optInt("rightsTimes", 0);
                int rightsTimesLimit = task.optInt("rightsTimesLimit", 0);

                // 从extend字段获取限制次数
                JSONObject extend = task.optJSONObject("extend");
                if (extend != null && rightsTimesLimit <= 0) {
                    String limitStr = extend.optString("rightsTimesLimit", "");
                    if (!limitStr.isEmpty()) {
                        try {
                            rightsTimesLimit = Integer.parseInt(limitStr);
                        } catch (Exception ignored) {
                        }
                    }
                }

                int timesToDo = (rightsTimesLimit > 0) ? (rightsTimesLimit - rightsTimes) : 1;
                if (timesToDo <= 0) {
                    return true;
                }

                for (int cnt = 0; cnt < timesToDo; cnt++) {
                    // taskId 当作 taskType 传给 finishTask（该 RPC 的参数名就叫 taskType）
                    String via = finishTaskTwice(sceneCode, title, taskId);
                    if (via != null) {
                        Log.farm("肥料任务🧾完成[" + title + "]第" + (rightsTimes + cnt + 1) + "次#" + via);
                    } else {
                        break;
                    }
                    TimeUtil.sleep(500);
                }
                return true;
            }

            // 处理触发型任务
            if ("TRIGGER".equals(actionType) || "ADD_HOME".equals(actionType) || "PUSH_SUBSCRIBE".equals(actionType)) {
                // taskId 当作 taskType 传递
                String via = finishTaskTwice(sceneCode, title, taskId);
                if (via != null) {
                    Log.farm("肥料任务🧾完成[" + title + "]#" + via);
                }
                return true;
            }

            //配合黑名单的兜底操作
            String via = finishTaskTwice(sceneCode, title, taskId);
            if (via != null) {
                Log.farm("肥料任务🧾完成[" + title + "]#" + via);
            }
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "finishOrchardTask err:", t);
            return false;
        }
    }

    /**
     * `doFarmTask` 已发出、但响应不足以判定成败的任务：{@code taskId -> 标题}。
     * <p>由 {@link #verifyPendingTasksByList()} 在列表处理完后用任务列表状态核对。
     */
    private static final LinkedHashMap<String, String> pendingVerifyTasks = new LinkedHashMap<>();

    /** 同轮核对配置（见 TaskAlternative.verify） */
    private static final TaskAlternative.VerifyConfig VERIFY_CFG = new TaskAlternative.VerifyConfig(
            "AntOrchard", "AntOrchardTaskList", "农场肥料任务", "肥料任务", "🧾完成", true, msg -> Log.farm(msg));

    /**
     * 完成任务（两条腿）：先 {@code finishTask}，不成功再用 {@code taskId} 当 bizKey 走 doFarmTask
     * （响应不可信，见 {@link TaskAlternative}）。
     *
     * @return 生效的接口名（finishTask / doFarmTask）；两条都失败或异常返回 null
     */
    private static String finishTaskTwice(String sceneCode, String taskTitle, String taskId) {
        try {
            JSONObject finishResponse = new JSONObject(AntOrchardRpcCall.finishTask(sceneCode, taskId));
            if (MessageUtil.checkSuccess(TAG, finishResponse)) {
                return "finishTask";
            }
            JSONObject doFarmResponse = new JSONObject(AntOrchardRpcCall.doFarmTask(taskId, sceneCode));
            if (MessageUtil.checkSuccess(TAG, doFarmResponse)) {
                return "doFarmTask";
            }
            // 400000040 可能已被另一种实现方案做成，不拉黑、记 pending 交给列表核对；其它错误码=真做不了，照常拉黑
            if (!MessageUtil.isUnsupportedRpc(finishResponse)) {
                MessageUtil.checkResultCodeAndMarkTaskBlackList("AntOrchardTaskList", taskTitle, finishResponse);
            } else {
                // doFarmTask 的响应对这类任务**不可信**：实测回 102「服务器正在开小差」但任务其实被做成了
                // （rightsTimes 0→1、状态转 RECEIVED）；也有同样回 102 而真没做成的。
                // 所以既不能据响应判失败（会把做成的任务拉黑），也不能判成功（真做不了的会每轮白试）：
                // 记下来，由 handleTaskList 在列表处理完后**按任务列表状态核对**
                pendingVerifyTasks.put(taskId, taskTitle);
            }
            Log.i("肥料任务🕓已触发[" + taskTitle + "]#finishTask=" + finishResponse.optString("code")
                    + "#doFarmTask=" + TaskAlternative.describe(doFarmResponse) + "，结果以任务列表为准");
        } catch (Throwable t) {
            Log.err(TAG, "finishTaskTwice err:", t);
        }
        return null;
    }

    /**
     * 核对「已触发但响应不可信」的任务：等几秒后重拉任务列表，**仍未完成**的才计入自动拉黑。
     * <p>为什么以任务列表为准：见 {@link #finishTaskTwice} 的注释——响应会撒谎（回 102 但已做成），
     * 服务端是异步推进状态的，只有任务列表的 {@code taskStatus} 才是最终判据。
     */
    private static void verifyPendingTasksByList() {
        TaskAlternative.verify(pendingVerifyTasks, VERIFY_CFG, () -> {
            JSONObject jo = new JSONObject(AntOrchardRpcCall.orchardListTask());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return null;
            }
            JSONArray taskArray = jo.optJSONArray("taskList");
            if (taskArray == null) {
                return null;
            }
            Set<String> stillTodo = new HashSet<>();
            for (int i = 0; i < taskArray.length(); i++) {
                JSONObject task = taskArray.optJSONObject(i);
                if (task == null || !TaskStatus.TODO.name().equals(task.optString("taskStatus"))) {
                    continue;
                }
                String taskId = task.optString("taskId", "");
                if (!taskId.isEmpty()) {
                    stillTodo.add(taskId);
                }
            }
            return stillTodo;
        });
    }

    /**
     * 触发淘宝任务奖励（领取所有已完成任务的奖励）
     */
    private void triggerTbTask() {
        try {
            String response = AntOrchardRpcCall.orchardListTask();
            JSONObject jo = new JSONObject(response);

            if (MessageUtil.checkResultCode(TAG, jo)) {
                JSONArray taskList = jo.getJSONArray("taskList");
                for (int i = 0; i < taskList.length(); i++) {
                    JSONObject task = taskList.getJSONObject(i);
                    if (!"FINISHED".equals(task.getString("taskStatus"))) {
                        continue;
                    }

                    String title = task.getJSONObject("taskDisplayConfig").getString("title");
                    int awardCount = task.optInt("awardCount", 0);
                    String taskId = task.getString("taskId");
                    String taskPlantType = task.getString("taskPlantType");

                    // 跳过淘宝类型的任务（需要手动操作）
                    //if ("TAOBAO".equals(taskPlantType)) {
                    //    continue;
                    //}

                    String triggerResponse = AntOrchardRpcCall.triggerTbTask(taskId, taskPlantType);
                    JSONObject triggerJo = new JSONObject(triggerResponse);
                    //检查并标记黑名单任务
                    MessageUtil.checkResultCodeAndMarkTaskBlackList("AntOrchardTaskList", title, triggerJo);
                    if (MessageUtil.checkResultCode(TAG, triggerJo)) {
                        Log.farm("肥料领取🎖️任务[" + title + "]奖励#获得[" + awardCount + "g]");
                    } else {
                        //Log.farm("领取奖励失败: " + triggerJo.toString());
                    }
                }
            } else {
                Log.record("获取任务列表失败: " + jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.err(TAG, "triggerTbTask err:", t);
        }
    }

    /**
     * 领取七日礼包
     */
    private void drawLotteryPlus(JSONObject lotteryInfo) {
        if (Status.hasFlagToday("orchardLotteryPlus")) {
            return;
        }

        try {
            if (!lotteryInfo.has("userSevenDaysGiftsItem")) {
                return;
            }

            JSONObject giftItem = lotteryInfo.getJSONObject("userSevenDaysGiftsItem");
            JSONArray dailyGifts = giftItem.getJSONArray("userEverydayGiftItems");
            String itemId = lotteryInfo.getString("itemId");

            // 检查今日是否已领取
            for (int i = 0; i < dailyGifts.length(); i++) {
                JSONObject daily = dailyGifts.getJSONObject(i);
                if (daily.getString("itemId").equals(itemId) && daily.getBoolean("received")) {
                    Log.record("芭芭农场七日礼包当日奖励已领取");
                    Status.flagToday("orchardLotteryPlus", userId);
                    return;
                }
            }

            // 领取礼包
            String result = AntOrchardRpcCall.drawLottery();
            JSONObject drawJo = new JSONObject(result);
            if (MessageUtil.checkResultCode(TAG, drawJo)) {
                JSONArray awardArray = drawJo.getJSONObject("lotteryPlusInfo").getJSONObject("userSevenDaysGiftsItem").getJSONArray("userEverydayGiftItems");

                for (int i = 0; i < awardArray.length(); i++) {
                    JSONObject award = awardArray.getJSONObject(i);
                    if (award.getString("itemId").equals(itemId)) {
                        int count = award.optInt("awardCount", 1);
                        Log.farm("芭芭农场🎁七日礼包#获得[" + count + "g肥料]");
                        Status.flagToday("orchardLotteryPlus", userId);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "drawLotteryPlus err:", t);
        }
    }

    /**
     * 获取额外信息（每日肥料包）
     */
    private void extraInfoGet() {
        if (Status.hasFlagToday("orchard::fertilizerPacket")) {
            return;
        }
        try {
            String result = AntOrchardRpcCall.extraInfoGet();
            JSONObject jo = new JSONObject(result);
            if (MessageUtil.checkResultCode(TAG, jo)) {
                JSONObject fertilizerPacket = jo.getJSONObject("data").getJSONObject("extraData").getJSONObject("fertilizerPacket");
                String status = fertilizerPacket.getString("status");
                if ("todayFertilizerFinish".equals(status)) {
                    // 服务端回读：今日这个包已是「已领完」态（响应同时带 tomorrowFertilizerNum）→ 落当日标记
                    Status.flagToday("orchard::fertilizerPacket", userId);
                    return;
                }

                if ("todayFertilizerWaitTake".equals(status)) {
                    int fertilizerNum = fertilizerPacket.getInt("todayFertilizerNum");
                    String takeResult = AntOrchardRpcCall.extraInfoSet();
                    if (MessageUtil.checkResultCode(TAG, new JSONObject(takeResult))) {
                        Log.farm("每日肥料💩[" + fertilizerNum + "g]");
                        // 回读确认服务端已离开「待领取」态才落当日标记；状态未知时宁可不打，下轮再查
                        JSONObject verify = new JSONObject(AntOrchardRpcCall.extraInfoGet());
                        if (MessageUtil.checkResultCode(TAG, verify)
                                && !"todayFertilizerWaitTake".equals(verify.getJSONObject("data")
                                .getJSONObject("extraData").getJSONObject("fertilizerPacket")
                                .optString("status"))) {
                            Status.flagToday("orchard::fertilizerPacket", userId);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "extraInfoGet err:", t);
        }
    }

    /**
     * 好友助力
     */
    private void orchardAssistFriend() {
        if (Status.hasFlagToday("orchardAssistLimit")) {
            return;
        }

        Set<String> friendList = assistFriendList.getValue();
        if (friendList == null || friendList.isEmpty()) {
            return;
        }

        try {
            for (String friendId : friendList) {
                if (Status.hasFlagToday("orchardAssist:" + friendId)) {
                    continue;
                }

                String result = AntOrchardRpcCall.achieveBeShareP2P(friendId);
                JSONObject jo = new JSONObject(result);
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    Log.farm("芭芭农场🌳助力好友[" + UserIdMap.getShowName(friendId) + "]");
                } else if ("600000027".equals(jo.optString("code"))) {
                    Status.flagToday("orchardAssistLimit", userId);
                    return;
                }

                Status.flagToday("orchardAssist:" + friendId, userId);
                TimeUtil.sleep(5000);
            }
        } catch (Throwable t) {
            Log.err(TAG, "orchardAssistFriend err:", t);
        }
    }

    /**
     * 查询子场景活动（许愿、营地接管等）
     */
    private void querySubplotsActivity(String activityType) {
        try {
            String result = AntOrchardRpcCall.querySubplotsActivity(activityType);
            JSONObject jo = new JSONObject(result);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }

            JSONArray activityList = jo.getJSONArray("subplotsActivityList");
            for (int i = 0; i < activityList.length(); i++) {
                JSONObject activity = activityList.getJSONObject(i);
                if (!activityType.equals(activity.getString("activityType"))) {
                    continue;
                }

                if ("WISH".equals(activityType)) {
                    handleWishActivity(activity);
                } else if ("CAMP_TAKEOVER".equals(activityType)) {
                    handleCampTakeoverActivity(activity);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "querySubplotsActivity err:", t);
        }
    }

    /**
     * 处理许愿活动
     */
    private void handleWishActivity(JSONObject activity) {
        try {
            String activityId = activity.getString("activityId");
            String status = activity.getString("status");

            // 已完成则领取奖励
            if ("FINISHED".equals(status)) {
                String result = AntOrchardRpcCall.receiveOrchardRights(activityId, "WISH");
                JSONObject jo = new JSONObject(result);
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    int amount = jo.getInt("amount");
                    Log.farm("农场许愿✨完成承诺#获得[" + amount + "g肥料]");
                    querySubplotsActivity("WISH"); // 重新查询状态
                }
                return;
            }

            // 未开始则许下承诺
            if ("NOT_STARTED".equals(status)) {
                Integer mainCount = orchardSpreadManureCount.getValue();
                int targetCount = mainCount != null && mainCount >= 10 ? 10 : (mainCount != null && mainCount >= 3 ? 3 : 0);

                if (targetCount > 0) {
                    JSONObject extend = new JSONObject(activity.getString("extend"));
                    JSONArray options = extend.getJSONArray("wishActivityOptionList");

                    for (int i = 0; i < options.length(); i++) {
                        JSONObject option = options.getJSONObject(i);
                        if (option.getInt("taskRequire") == targetCount) {
                            String result = AntOrchardRpcCall.triggerSubplotsActivity(activityId, "WISH", option.getString("optionKey"));
                            if (MessageUtil.checkResultCode(TAG, new JSONObject(result))) {
                                Log.farm("农场许愿✨许下承诺[每日施肥" + targetCount + "次]");
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "handleWishActivity err:", t);
        }
    }

    /**
     * 处理营地接管活动
     */
    private void handleCampTakeoverActivity(JSONObject activity) {
        try {
            JSONObject extend = new JSONObject(activity.getString("extend"));
            JSONObject currentInfo = extend.getJSONObject("currentActivityInfo");
            String status = currentInfo.getString("activityStatus");

            // 待选择奖励
            if ("TO_CHOOSE_PRIZE".equals(status)) {
                JSONArray prizes = currentInfo.getJSONArray("recommendPrizeList");
                for (int i = 0; i < prizes.length(); i++) {
                    JSONObject prize = prizes.getJSONObject(i);
                    if ("FEILIAO".equals(prize.getString("prizeType"))) {
                        String result = AntOrchardRpcCall.choosePrize(prize.getString("sendOrderId"));
                        JSONObject jo = new JSONObject(result);
                        if (MessageUtil.checkResultCode(TAG, jo)) {
                            String prizeName = jo.getJSONObject("currentActivityInfo").getJSONObject("currentPrize").getString("prizeName");
                            Log.farm("速成奖励✨接受挑战#选择[" + prizeName + "]");
                        }
                        break;
                    }
                }
            }

            // 待完成任务
            if ("TO_DO_TASK".equals(status)) {
                JSONArray tasks = currentInfo.getJSONArray("taskList");
                handleTaskList(tasks);
                querySubplotsActivity("CAMP_TAKEOVER"); // 重新查询状态
            }
        } catch (Throwable t) {
            Log.err(TAG, "handleCampTakeoverActivity err:", t);
        }
    }

    /**
     * 查询余额宝收益
     */
    private void queryYebRevenueDetail() {
        try {
            String result = AntOrchardRpcCall.yebPlantSceneRevenuePage();
            JSONObject jo = new JSONObject(result);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                // 服务端常回 error 3000「系统出错，正在排查」⇒ 详情列表拿不到。
                // 但调用点的判据 yebSceneActivityInfo.revenueNotReceived=true 本身就是"有未领收益"的证据，
                // 故不再直接放弃，改为直接触发摇钱树（没收益时服务端会拒绝，不会误领）。
                Log.record("摇钱树收益详情未获取，改走直接触发：" + (result.length() > 200 ? result.substring(0, 200) : result));
                triggerYebMoneyTreeOnce();
                return;
            }

            JSONArray revenueList = jo.optJSONArray("yebRevenueDetailList");
            if (revenueList == null) {
                Log.record("摇钱树收益详情结构异常：" + (result.length() > 200 ? result.substring(0, 200) : result));
                triggerYebMoneyTreeOnce();
                return;
            }
            for (int i = 0; i < revenueList.length(); i++) {
                JSONObject revenue = revenueList.getJSONObject(i);
                if ("I".equals(revenue.optString("orderStatus"))) {
                    triggerYebMoneyTreeOnce();
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryYebRevenueDetail err:", t);
        }
    }

    /**
     * 触发摇钱树领奖：无论成败都留痕（原实现失败时静默，导致"到底是没收益还是接口不通"无法判断）。
     */
    private void triggerYebMoneyTreeOnce() {
        try {
            String triggerResult = AntOrchardRpcCall.triggerYebMoneyTree();
            JSONObject triggerJo = new JSONObject(triggerResult);
            if (!MessageUtil.checkResultCode(TAG, triggerJo)) {
                Log.record("摇钱树触发未成功：" + (triggerResult.length() > 200 ? triggerResult.substring(0, 200) : triggerResult));
                return;
            }
            // 只记"已受理"：金额是触发响应当次返回的值，不作为"已到账收益"上报
            Log.farm("💰领取奖励[摇钱树]");
        } catch (Throwable t) {
            Log.err(TAG, "triggerYebMoneyTree err:", t);
        }
    }

    /**
     * 砸金蛋
     */
    private void smashedGoldenEgg(int unsmashedGoldenEggs) {
        try {
            // 循环砸蛋，因为你的RPC方法不支持批量
            for (int i = 0; i < unsmashedGoldenEggs; i++) {
                String response = AntOrchardRpcCall.smashedGoldenEgg();
                JSONObject jo = new JSONObject(response);

                if (MessageUtil.checkResultCode(TAG, jo)) {

                    JSONObject goldenEggInfoVO = jo.optJSONObject("goldenEggInfoVO");
                    int unsmashedGoldenEggsNow = goldenEggInfoVO != null ? goldenEggInfoVO.optInt("unsmashedGoldenEggs") : 0;
                    JSONArray batchSmashedList = jo.optJSONArray("batchSmashedList");
                    if (batchSmashedList != null && batchSmashedList.length() > 0) {
                        for (int j = 0; j < batchSmashedList.length(); j++) {
                            JSONObject smashedItem = batchSmashedList.optJSONObject(j);
                            if (smashedItem != null) {
                                int manureCount = smashedItem.optInt("manureCount", 0);
                                boolean jackpot = smashedItem.optBoolean("jackpot", false);
                                String unsmashedGoldenEggsString = "";
                                if (unsmashedGoldenEggsNow >= 0) {
                                    unsmashedGoldenEggsString = "[剩蛋" + unsmashedGoldenEggsNow + "个]";
                                }

                                String jackpotMessage = jackpot ? "（触发大奖）" : "";
                                Log.farm("砸出肥料🎖️" + manureCount + "g" + unsmashedGoldenEggsString + jackpotMessage + "");
                            }
                        }
                    }

                } else {
                    Log.farm("砸金蛋失败: " + jo.optString("resultDesc", "未知错误"));
                }

                // 每次砸蛋后等待一下
                TimeUtil.sleep(500);
            }
        } catch (Throwable t) {
            Log.err(TAG, "smashedGoldenEgg err:", t);
        }
    }

    /**
     * 领取小组件回访/浏览奖励。
     * <p>实测（2026-09-22 抓包 logs/chk_orchard 14:20:10，官方每次进入农场都会调）服务端返回
     * {@code {"canCollect":false,"manureCount":0,"needManualReceive":false,"success":true}}——
     * **没有 `orchardVisitAwardList` 结构**。原先按该字段解析，永远走"无奖励"分支并打误导性日志，
     * 调用点也因此被注释掉，导致这块奖励一直没领过。现按真实字段解析。
     */
    private void receiveOrchardVisitAward() {
        try {
            String response = AntOrchardRpcCall.receiveOrchardVisitAward();
            JSONObject jo = new JSONObject(response);
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                Log.farm("领取回访奖励失败: " + response);
                return;
            }
            int manureCount = jo.optInt("manureCount", 0);
            boolean canCollect = jo.optBoolean("canCollect", false);
            boolean needManualReceive = jo.optBoolean("needManualReceive", false);
            if (manureCount > 0) {
                Log.farm("回访奖励🎖️领取肥料*" + manureCount);
            } else if (canCollect || needManualReceive) {
                Log.i("回访奖励🎖️有待领奖励[canCollect=" + canCollect + "#需手动领取=" + needManualReceive + "]");
            }
            // 无奖励时不打日志：官方每次进农场都会调一次，属正常空返回
        } catch (Throwable t) {
            Log.err(TAG, "receiveOrchardVisitAward err:", t);
        }
    }

    /**
     * 限时挑战活动
     */
    private void limitedTimeChallenge() {
        try {
            // 使用无参版本，因为你的RPC方法不支持参数
            String response = AntOrchardRpcCall.orchardSyncIndex();
            JSONObject root = new JSONObject(response);

            if (!MessageUtil.checkResultCode(TAG, root)) {
                Log.record("orchardSyncIndex 查询失败: " + response);
                return;
            }

            JSONObject challenge = root.optJSONObject("limitedTimeChallenge");
            if (challenge == null) {
                Log.record("limitedTimeChallenge 字段不存在或为 null");
                return;
            }

            int currentRound = challenge.optInt("currentRound", 0);
            if (currentRound <= 0) {
                Log.record("currentRound 无效：" + currentRound);
                return;
            }

            JSONArray taskArray = challenge.optJSONArray("limitedTimeChallengeTasks");
            if (taskArray == null) {
                Log.record("limitedTimeChallengeTasks 字段不存在或不是数组");
                return;
            }

            int targetIdx = currentRound - 1;
            if (targetIdx < 0 || targetIdx >= taskArray.length()) {
                Log.record("当前轮数 " + currentRound + " 对应下标 " + targetIdx + " 超出数组长度: " + taskArray.length());
                return;
            }

            JSONObject roundTask = taskArray.optJSONObject(targetIdx);
            if (roundTask == null) {
                Log.record("第 " + currentRound + " 轮任务不存在");
                return;
            }

            boolean ongoing = roundTask.optBoolean("ongoing", false);
            String MtaskStatus = roundTask.optString("taskStatus");
            String MtaskId = roundTask.optString("taskId");
            int MawardCount = roundTask.optInt("awardCount", 0);

            if ("FINISHED".equals(MtaskStatus) && ongoing) {
                Log.record("第 " + currentRound + " 轮 奖励未领取，尝试领取");
                String awardResp = AntOrchardRpcCall.receiveTaskAward("ORCHARD_LIMITED_TIME_CHALLENGE", MtaskId);
                JSONObject joo = new JSONObject(awardResp);
                if (MessageUtil.checkResultCode(TAG, joo)) {
                    Log.farm("第 " + currentRound + " 轮 限时任务🎁[肥料 * " + MawardCount + "]");
                } else {
                    String desc = joo.optString("desc", "未知错误");
                    Log.record("芭芭农场 限时任务 错误：" + desc);
                }
                return;
            }

            if (!"TODO".equals(roundTask.optString("taskStatus"))) {
                Log.record("警告：第 " + currentRound + " 轮任务非 TODO，状态=" + roundTask.optString("taskStatus"));
                return;
            }

            JSONArray childTasks = roundTask.optJSONArray("childTaskList");
            if (childTasks == null) {
                Log.record("警告：第 " + currentRound + " 轮无子任务列表");
                return;
            }

            Log.record("开始处理第 " + currentRound + " 轮的 " + childTasks.length() + " 个子任务");

            for (int i = 0; i < childTasks.length(); i++) {
                JSONObject child = childTasks.optJSONObject(i);
                if (child == null || !"TODO".equals(child.optString("taskStatus"))) {
                    continue;
                }

                String childTaskId = child.optString("taskId", "未知ID");
                String actionType = child.optString("actionType");
                String groupId = child.optString("groupId");
                String sceneCode = child.optString("sceneCode");

                if ("GROUP_1_STEP_3_GAME_WZZT_30s".equals(groupId)) {
                    continue;
                }

                Log.record("------ 开始处理子任务 " + i + " | ID=" + childTaskId + " ------");

                switch (actionType) {
                    case "SPREAD_MANURE":
                        int taskRequire = child.optInt("taskRequire", 0);
                        int taskProgress = child.optInt("taskProgress", 0);
                        int need = taskRequire - taskProgress;
                        if (need > 0) {
                            Log.record("施肥任务需补充 " + need + " 次");
                            for (int j = 0; j < need; j++) {
                                // 修复：传递正确的wua参数
                                String wua = getWua();
                                String spreadResultStr = AntOrchardRpcCall.orchardSpreadManure("main", false, wua);
                                Log.record("施肥第 " + (j + 1) + " 次结果：" + spreadResultStr);
                                JSONObject resultJson = new JSONObject(spreadResultStr);
                                if (!MessageUtil.checkResultCode(TAG, resultJson)) {
                                    Log.record("芭芭农场 orchardSpreadManure 错误：" + resultJson.optString("resultDesc"));
                                    return;
                                }
                            }
                            Log.farm("施肥任务成功完成 " + need + " 次");
                        }
                        break;

                    case "GAME_CENTER":
                        String r = AntOrchardRpcCall.noticeGame("2021004165643274");
                        JSONObject jr = new JSONObject(r);
                        if (MessageUtil.checkResultCode(TAG, jr)) {
                            Log.farm("游戏任务触发成功 → 子任务应当自动完成");
                        } else {
                            Log.farm("游戏任务触发失败，返回: " + r);
                        }
                        break;

                    case "VISIT":
                        // 广告任务处理（简化为直接完成）
                        JSONObject displayCfg = child.optJSONObject("taskDisplayConfig");
                        if (displayCfg == null || displayCfg.optString("targetUrl", "").isEmpty()) {
                            Log.record("任务没有 taskDisplayConfig，无法继续");
                            continue;
                        }

                        // 对于VISIT类型的任务，尝试直接调用finishTask
                        // 注意：这里childTaskId作为taskType参数传递
                        String finishResult = AntOrchardRpcCall.finishTask(sceneCode, childTaskId);
                        JSONObject finishJo = new JSONObject(finishResult);
                        if (MessageUtil.checkResultCode(TAG, finishJo)) {
                            Log.farm("广告任务触发成功");
                        } else {
                            Log.farm("广告任务触发失败: " + finishResult);
                        }
                        break;

                    default:
                        Log.record("无法处理的任务类型：" + childTaskId + " | actionType=" + actionType);
                        break;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "limitedTimeChallenge err:", t);
        }
    }

    // 内部枚举定义
    public enum PlantScene {
        main("主场景"), yeb("余额宝场景");

        private final String nickname;

        PlantScene(String nickname) {
            this.nickname = nickname;
        }

        public String nickname() {
            return nickname;
        }

        public static PlantScene[] getEntries() {
            return values();
        }

        // 用于获取选项列表的静态方法
        public static List<String> getList() {
            List<String> list = new ArrayList<>();
            for (PlantScene scene : values()) {
                list.add(scene.name());
            }
            return list;
        }
    }

    public interface DriveAnimalType {
        int NONE = 0;
        int ALL = 1;
        String[] nickNames = {"不操作", "驱赶所有"};
    }

    public enum TaskStatus {
        TODO, FINISHED, RECEIVED
    }
}