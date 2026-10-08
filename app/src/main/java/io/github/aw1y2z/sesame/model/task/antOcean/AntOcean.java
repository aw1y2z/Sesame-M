package io.github.aw1y2z.sesame.model.task.antOcean;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ChoiceModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy.Outcome;
import io.github.aw1y2z.sesame.entity.AlipayAntOceanAntiepTaskList;
import io.github.aw1y2z.sesame.entity.AlipayAntOceanFishBlackList;
import io.github.aw1y2z.sesame.entity.AlipayUser;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.model.normal.answerAI.AnswerAI;
import io.github.aw1y2z.sesame.model.task.antFarm.AntFarm.TaskStatus;
import io.github.aw1y2z.sesame.model.task.antFarm.AntFarmRpcCall;
import io.github.aw1y2z.sesame.model.task.antForest.AntForestRpcCall;
import io.github.aw1y2z.sesame.util.JsonUtil;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.AntOceanAntiepTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntOceanFishBlackListMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author Constanline
 * @since 2023/08/01
 */
public class AntOcean extends ModelTask {
    private static final String TAG = AntOcean.class.getSimpleName();

    /**
     * 获取任务名称
     *
     * @return 海洋任务名称
     */
    @Override
    public String getName() {
        return "海洋";
    }

    /**
     * 获取任务分组
     *
     * @return 森林分组
     */
    @Override
    public ModelGroup getGroup() {
        return ModelGroup.FOREST;
    }

    private BooleanModelField queryTaskList;
    private BooleanModelField AutoAntOceanAntiepTaskList;
    private SelectModelField AntOceanAntiepTaskList;
    private BooleanModelField AutoAntOceanFishBlackList;  // 自动添加摸鱼黑名单
    private SelectModelField AntOceanFishBlackList;  // 摸鱼黑名单列表
    private ChoiceModelField cleanOceanType;
    private SelectModelField cleanOceanList;
    private BooleanModelField exchangeUniversalPiece;
    private BooleanModelField useUniversalPiece;
    private BooleanModelField replica;
    private BooleanModelField antfishEnable;  // 开启摸鱼功能
    private BooleanModelField antfishAutoTask;  // 自动完成任务获得次数


    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(queryTaskList = new BooleanModelField("queryTaskList", "海洋任务", false));
        modelFields.addField(AutoAntOceanAntiepTaskList = new BooleanModelField("AutoAntOceanAntiepTaskList", "海洋任务 | 自动黑名单", true).setDependsOn("queryTaskList"));
        modelFields.addField(AntOceanAntiepTaskList = new SelectModelField("AntOceanAntiepTaskList", "海洋任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntOceanAntiepTaskList::getList).setDependsOn("AutoAntOceanAntiepTaskList"));
        modelFields.addField(cleanOceanType = new ChoiceModelField("cleanOceanType", "清理海域 | 动作", CleanOceanType.NONE, CleanOceanType.nickNames));
        modelFields.addField(cleanOceanList = new SelectModelField("cleanOceanList", "清理海域 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList).setDependsOn("cleanOceanType"));
        modelFields.addField(exchangeUniversalPiece = new BooleanModelField("exchangeUniversalPiece", "万能拼图 | 制作", false));
        modelFields.addField(useUniversalPiece = new BooleanModelField("useUniversalPiece", "万能拼图 | 使用", false));
        modelFields.addField(replica = new BooleanModelField("replica", "潘多拉海域", false));
        modelFields.addField(antfishEnable = new BooleanModelField("antfishEnable", "海洋摸鱼 | 开启摸鱼", false));
        modelFields.addField(antfishAutoTask = new BooleanModelField("antfishAutoTask", "海洋摸鱼 | 摸鱼任务", false).setDependsOn("antfishEnable"));
        modelFields.addField(AutoAntOceanFishBlackList = new BooleanModelField("AutoAntOceanFishBlackList", "海洋摸鱼 | 自动黑名单", true).setDependsOn("antfishAutoTask"));
        modelFields.addField(AntOceanFishBlackList = new SelectModelField("AntOceanFishBlackList", "摸鱼任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntOceanFishBlackList::getList).setDependsOn("AutoAntOceanFishBlackList"));
        return modelFields;
    }

    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.i("任务暂停⏸️神奇海洋:当前为仅收能量时间");
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        try {
            if (!queryOceanStatus()) {
                return;
            }

            //初始任务列表
            if (!Status.hasFlagToday("BlackList::initAntOceanAntiep")) {
                initAntOceanAntiepTaskListMap(AutoAntOceanAntiepTaskList.getValue(), queryTaskList.getValue(),AutoAntOceanFishBlackList.getValue(),antfishAutoTask.getValue());
                Status.flagToday("BlackList::initAntOceanAntiep");
            }

            queryHomePage();

            if (queryTaskList.getValue()) {
                queryTaskList();
            }
            if (cleanOceanType.getValue() != CleanOceanType.NONE) {
                queryUserRanking();
            }
            if (exchangeUniversalPiece.getValue()) {
                exchangeUniversalPiece();
            }
            if (useUniversalPiece.getValue()) {
                useUniversalPiece();
            }

            //开启新海域修复
            openWAIT_FOR_UNLOCK();

            if (replica.getValue()) {
                queryReplicaHome();
            }

            //添加蹲点清理自己海洋
            autocleanOcean(UserIdMap.getCurrentUid());

            // 神秘海洋（摸鱼）功能
            if (antfishEnable.getValue()) {
                antfishRun();
            }

        } catch (Throwable t) {
            Log.err(TAG, "AntOcean.start.run err:", t);
        }
    }

    private Boolean queryOceanStatus() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryOceanStatus());
            if (MessageUtil.checkResultCode(TAG, jo)) {
                if (!jo.getBoolean("opened")) {
                    getEnableField().setValue(false);
                    Log.record("请先开启神奇海洋，并完成引导教程");
                    return false;
                }
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryOceanStatus err:", t);
        }
        return false;
    }

    public static void initAntOceanAntiepTaskListMap(boolean AutoAntOceanAntiepTaskList, boolean queryTaskList,boolean AutoAntOceanFishBlackList,boolean antfishAutoTask) {
        try {
            //初始化AntOceanAntiepTaskListMap
            AntOceanAntiepTaskListMap.load();
            // 1. 定义黑名单（需要添加的任务）和白名单（需要移除的任务）
            // 注：battleTile 类实验任务（如"随机任务：玩一玩得拼图"）不再预置拉黑，
            // 交由自动拉黑机制判定（释放清单见 MessageUtil.sweepReleasedDefaults）
            Set<String> blackList = new HashSet<>();
            // 可继续添加更多黑名单任务

            Set<String> whiteList = new HashSet<>();// 从黑名单中移除该任务
            //whiteList.add("逛一芝麻树");
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                AntOceanAntiepTaskListMap.add(task, task);
            }

            if (queryTaskList) {
                JSONObject jo = new JSONObject(AntOceanRpcCall.queryTaskList());
                if (MessageUtil.checkResultCode(TAG, jo)) {

                    JSONArray ja = jo.getJSONArray("antOceanTaskVOList");
                    for (int i = 0; i < ja.length(); i++) {
                        jo = ja.getJSONObject(i);
                        JSONObject bizInfo = new JSONObject(jo.getString("bizInfo"));
                        String taskTitle = bizInfo.optString("taskTitle");
                        AntOceanAntiepTaskListMap.add(taskTitle, taskTitle);
                    }
                }
                //保存任务到配置文件
                AntOceanAntiepTaskListMap.save();
                Log.record("同步任务🉑海洋普通任务列表");

                //自动按模块初始化设定调整黑名单和白名单
                if (AutoAntOceanAntiepTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntOcean = config.getModelFieldsMap().get("AntOcean");
                    SelectModelField AntOceanAntiepTaskList = (SelectModelField) AntOcean.get("AntOceanAntiepTaskList");
                    if (AntOceanAntiepTaskList == null) {
                        return;
                    }

                    // 2~4. 批量写回黑/白名单并保存
                    MessageUtil.syncTaskBlackList("海洋普通任务", "AntOceanAntiepTaskList", blackList, whiteList, AntOceanAntiepTaskList);
                }
            }

            //初始化AntOceanFishBlackListMap
            AntOceanFishBlackListMap.load();
            // 1. 定义黑名单（需要添加的任务）和白名单（需要移除的任务）
            // 注：小游戏类任务（如"玩一玩向僵尸开炮"）不再预置拉黑，交由自动拉黑机制判定
            blackList = new HashSet<>();
            // 可继续添加更多黑名单任务
            whiteList = new HashSet<>();// 从黑名单中移除该任务
            //whiteList.add("逛一芝麻树");
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                AntOceanFishBlackListMap.add(task, task);
            }

            //初始化AntOceanFishBlackListMap
            if (antfishAutoTask) {
                JSONObject jo = new JSONObject(AntOceanRpcCall.antfishListTask());
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        return;
                    }
                    JSONArray taskInfoList = jo.optJSONArray("taskInfoList");
                    if (taskInfoList == null || taskInfoList.length() == 0) {
                        return;
                    }
                    for (int i = 0; i < taskInfoList.length(); i++) {
                        JSONObject taskInfo = taskInfoList.optJSONObject(i);
                        if (taskInfo == null) continue;
                        JSONObject taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo");
                        if (taskBaseInfo == null) continue;
                        JSONObject bizInfo = new JSONObject(taskBaseInfo.optString("bizInfo", "{}"));
                        String taskTitle = bizInfo.getString("taskTitle");
                        AntOceanFishBlackListMap.add(taskTitle, taskTitle);
                    }

                AntOceanFishBlackListMap.save();
                Log.record("同步任务🉑海洋去摸鱼任务列表");
                //自动按模块初始化设定调整黑名单和白名单
                if (AutoAntOceanFishBlackList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntForestV2 = config.getModelFieldsMap().get("AntOcean");
                    SelectModelField AntOceanFishBlackList = (SelectModelField) AntForestV2.get("AntOceanFishBlackList");
                    if (AntOceanFishBlackList == null) {
                        return;
                    }

                    // 2~4. 批量写回黑/白名单并保存
                    MessageUtil.syncTaskBlackList("海洋去摸鱼任务", "AntOceanFishBlackList", blackList, whiteList, AntOceanFishBlackList);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "initAntOceanAntiepTaskListMap err:", t);
        }
    }

    private void queryHomePage() {
        try {
            JSONObject joHomePage = new JSONObject(AntOceanRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, joHomePage)) {
                return;
            }

            if (joHomePage.has("bubbleVOList")) {
                collectEnergy(joHomePage.getJSONArray("bubbleVOList"));
            }

            JSONObject userInfoVO = joHomePage.getJSONObject("userInfoVO");
            int rubbishNumber = userInfoVO.optInt("rubbishNumber", 0);
            String userId = userInfoVO.getString("userId");
            cleanOcean(userId, rubbishNumber);

            JSONObject ipVO = userInfoVO.optJSONObject("ipVO");
            if (ipVO != null) {
                int surprisePieceNum = ipVO.optInt("surprisePieceNum", 0);
                if (surprisePieceNum > 0) {
                    ipOpenSurprise();
                }
            }

            queryMiscInfo();
        } catch (Throwable t) {
            Log.err(TAG, "queryHomePage err:", t);
        }
    }

    private static void collectEnergy(JSONArray bubbleVOList) {
        try {
            for (int i = 0; i < bubbleVOList.length(); i++) {
                JSONObject bubble = bubbleVOList.getJSONObject(i);
                if (!"ocean".equals(bubble.getString("channel"))) {
                    continue;
                }
                if ("AVAILABLE".equals(bubble.getString("collectStatus"))) {
                    long bubbleId = bubble.getLong("id");
                    String userId = bubble.getString("userId");
                    JSONObject jo = new JSONObject(AntForestRpcCall.collectEnergy(null, userId, bubbleId));
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        JSONArray retBubbles = jo.optJSONArray("bubbles");
                        if (retBubbles != null) {
                            for (int j = 0; j < retBubbles.length(); j++) {
                                JSONObject retBubble = retBubbles.optJSONObject(j);
                                if (retBubble != null) {
                                    int collectedEnergy = retBubble.getInt("collectedEnergy");
                                    Log.other("神奇海洋🐳收取[" + UserIdMap.getMaskName(userId) + "]的海洋能量#" + collectedEnergy + "g");
                                    Statistics.addData(Statistics.DataType.COLLECTED, collectedEnergy);
                                }
                            }
                            Statistics.save();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "collectEnergy err:", t);
        }
    }

    private static void cleanOcean(String userId, int rubbishNumber) {
        try {
            for (int i = 0; i < rubbishNumber; i++) {
                JSONObject jo = new JSONObject(AntOceanRpcCall.cleanOcean(userId));
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    JSONArray cleanRewardVOS = jo.getJSONArray("cleanRewardVOS");
                    checkReward(cleanRewardVOS);
                    Log.other("神奇海洋🐳清理[" + UserIdMap.getMaskName(userId) + "]海域");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "cleanOcean err:", t);
        }
    }

    private void autocleanOcean(String UserId) {
        try {
            JSONObject joHomePage = new JSONObject(AntOceanRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, joHomePage)) {
                return;
            }
            JSONObject userInfoVO = joHomePage.getJSONObject("userInfoVO");
            Long canCleanLaterTime = userInfoVO.getLong("canCleanLaterTime");
            long updateTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
            addChildTask(new ChildModelTask(UserId, "Ocean", this::queryHomePage, updateTime));
            String taskId = "Ocean|" + UserId;
            if (!hasChildTask(taskId)) {
                addChildTask(new ChildModelTask(taskId, "Ocean", this::queryHomePage, canCleanLaterTime));
                Log.record("神奇海洋🐳蹲添加蹲点在[" + TimeUtil.getCommonDate(canCleanLaterTime) + "]执行清理海洋");
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryHomePage err:", t);
        }
    }

    private static void ipOpenSurprise() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.ipOpenSurprise());
            if (MessageUtil.checkResultCode(TAG, jo)) {
                JSONArray rewardVOS = jo.getJSONArray("surpriseRewardVOS");
                checkReward(rewardVOS);
            }
        } catch (Throwable t) {
            Log.err(TAG, "ipOpenSurprise err:", t);
        }
    }

    private static void combineFish(String fishId) {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.combineFish(fishId));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                JSONObject fishDetailVO = jo.getJSONObject("fishDetailVO");
                String name = fishDetailVO.getString("name");
                Log.other("神奇海洋🐳迎回[" + name + "]");
            }
            //检测是否能开启限时挑战
            createSeaAreaExtraCollect();
        } catch (Throwable t) {
            Log.err(TAG, "combineFish err:", t);
        }
    }

    private static void checkReward(JSONArray rewards) {
        try {
            for (int i = 0; i < rewards.length(); i++) {
                JSONObject reward = rewards.getJSONObject(i);
                String name = reward.getString("name");
                JSONArray attachReward = reward.getJSONArray("attachRewardBOList");
                if (attachReward.length() > 0) {
                    Log.other("神奇海洋🐳获得[" + name + "]拼图");
                    boolean canCombine = true;
                    for (int j = 0; j < attachReward.length(); j++) {
                        JSONObject detail = attachReward.getJSONObject(j);
                        if (detail.optInt("count", 0) == 0) {
                            canCombine = false;
                            break;
                        }
                    }
                    if (canCombine && reward.optBoolean("unlock", false)) {
                        String fishId = reward.getString("id");
                        combineFish(fishId);
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "checkReward err:", t);
        }
    }

    private static void queryReplicaHome() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryReplicaHome());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }

            if (jo.has("userReplicaAssetVO")) {
                JSONObject userReplicaAssetVO = jo.getJSONObject("userReplicaAssetVO");
                int canCollectAssetNum = userReplicaAssetVO.getInt("canCollectAssetNum");
                collectReplicaAsset(canCollectAssetNum);
            }

            if (jo.has("userCurrentPhaseVO")) {
                JSONObject userCurrentPhaseVO = jo.getJSONObject("userCurrentPhaseVO");
                String phaseCode = userCurrentPhaseVO.getString("phaseCode");
                String code = jo.getJSONObject("userReplicaInfoVO").getString("code");
                if ("COMPLETED".equals(userCurrentPhaseVO.getString("phaseStatus"))) {
                    unLockReplicaPhase(code, phaseCode);
                }
            }

            queryReplicaTaskList();
        } catch (Throwable t) {
            Log.err(TAG, "queryReplicaHome err:", t);
        }
    }

    private static void collectReplicaAsset(int canCollectAssetNum) {
        try {
            for (int i = 0; i < canCollectAssetNum; i++) {
                JSONObject jo = new JSONObject(AntOceanRpcCall.collectReplicaAsset());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    Log.other("神奇海洋🐳[学习海洋科普知识]#获得[潘多拉能量*1]");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "collectReplicaAsset err:", t);
        }
    }

    private static void unLockReplicaPhase(String replicaCode, String replicaPhaseCode) {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.unLockReplicaPhase(replicaCode, replicaPhaseCode));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                String name = jo.getJSONObject("currentPhaseInfo").getJSONObject("extInfo").getString("name");
                Log.other("神奇海洋🐳迎回[" + name + "]");
            }
        } catch (Throwable t) {
            Log.err(TAG, "unLockReplicaPhase err:", t);
        }
    }

    private static void queryReplicaTaskList() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryReplicaTaskList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray ja = jo.getJSONArray("antOceanTaskVOList");
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                String taskStatus = jo.getString("taskStatus");
                if (!TaskStatus.FINISHED.name().equals(taskStatus)) {
                    continue;
                }
                String taskType = jo.getString("taskType");
                JSONObject bizInfo = new JSONObject(jo.getString("bizInfo"));
                String taskTitle = bizInfo.getString("taskTitle");
                receiveReplicaTaskAward(taskType, taskTitle);
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryReplicaTaskList err:", t);
        }
    }

    private static void receiveReplicaTaskAward(String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.receiveReplicaTaskAward(taskType));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                int incAwardCount = jo.getInt("incAwardCount");
                Log.other("神奇海洋🐳领取[" + taskTitle + "]奖励#获得[潘多拉能量*" + incAwardCount + "]");
            }
        } catch (Throwable t) {
            Log.err(TAG, "receiveReplicaTaskAward err:", t);
        }
    }

    private static void queryMiscInfo() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryMiscInfo());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject miscHandlerVOMap = jo.getJSONObject("miscHandlerVOMap");
            JSONObject homeTipsRefresh = miscHandlerVOMap.getJSONObject("HOME_TIPS_REFRESH");
            if (homeTipsRefresh.optBoolean("fishCanBeCombined") || homeTipsRefresh.optBoolean("canBeRepaired")) {
                querySeaAreaDetailList();
            }
            switchOceanChapter();
        } catch (Throwable t) {
            Log.err(TAG, "queryMiscInfo err:", t);
        }
    }

    private static void createSeaAreaExtraCollect() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.querySeaAreaDetailList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            //判断神秘海域
            boolean awardSeaAreaCanCreateExtraCollect = jo.optBoolean("awardSeaAreaCanCreateExtraCollect", false);
            if (awardSeaAreaCanCreateExtraCollect) {
                JSONObject Extrajo = new JSONObject(AntOceanRpcCall.createSeaAreaExtraCollect());
                if (MessageUtil.checkResultCode(TAG, Extrajo)) {
                    if (Extrajo.has("seaAreaExtraCollectVO")) {
                        Log.other("神奇海洋🐳开启了神秘海域");
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "createSeaAreaExtraCollect err:", t);
        }
    }

    private static void querySeaAreaDetailList() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.querySeaAreaDetailList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            //判断神秘海域
            boolean awardSeaAreaCanCreateExtraCollect = jo.optBoolean("awardSeaAreaCanCreateExtraCollect", false);
            if (awardSeaAreaCanCreateExtraCollect) {
                JSONObject Extrajo = new JSONObject(AntOceanRpcCall.createSeaAreaExtraCollect());
                if (MessageUtil.checkResultCode(TAG, Extrajo)) {
                    if (Extrajo.has("seaAreaExtraCollectVO")) {
                        Log.other("神奇海洋🐳开启了神秘海域");
                    }
                }
            }
            int seaAreaNum = jo.getInt("seaAreaNum");
            int fixSeaAreaNum = jo.getInt("fixSeaAreaNum");
            int currentSeaAreaIndex = jo.getInt("currentSeaAreaIndex");
            if (currentSeaAreaIndex < fixSeaAreaNum && seaAreaNum > fixSeaAreaNum) {
                queryOceanPropList();
            }
            JSONArray seaAreaVOs = jo.getJSONArray("seaAreaVOs");
            for (int i = 0; i < seaAreaVOs.length(); i++) {
                JSONObject seaAreaVO = seaAreaVOs.getJSONObject(i);
                JSONArray fishVOs = seaAreaVO.getJSONArray("fishVO");
                for (int j = 0; j < fishVOs.length(); j++) {
                    JSONObject fishVO = fishVOs.getJSONObject(j);
                    if (!fishVO.getBoolean("unlock") && "COMPLETED".equals(fishVO.getString("status"))) {
                        String fishId = fishVO.getString("id");
                        combineFish(fishId);
                    }
                }
                if (seaAreaVO.has("seaAreaExtraCollectVO")) {
                    JSONObject seaAreaExtraCollectVO = seaAreaVO.getJSONObject("seaAreaExtraCollectVO");
                    String ExtraStatus = seaAreaExtraCollectVO.optString("status");
                    if (!ExtraStatus.equals("FINISHED")) {
                        JSONArray ExtrafishVOs = seaAreaExtraCollectVO.optJSONArray("fishVO");
                    if (ExtrafishVOs == null) {
                        continue;
                    }
                        for (int j = 0; j < ExtrafishVOs.length(); j++) {
                            JSONObject ExtrafishVO = ExtrafishVOs.getJSONObject(j);
                            if (!ExtrafishVO.getBoolean("unlock") && "COMPLETED".equals(ExtrafishVO.getString("status"))) {
                                String ExtrafishId = ExtrafishVO.getString("id");
                                combineFish(ExtrafishId);
                            }
                        }
                    }
                }
            }
            // 「最后一个海域待解锁」是整份响应级别的判断，放在循环外，避免逐海域重复触发修复
            if (seaAreaVOs.length() > 0) {
                JSONObject lastSeaAreaVO = seaAreaVOs.getJSONObject(seaAreaVOs.length() - 1);
                if ("WAIT_FOR_UNLOCK".equals(lastSeaAreaVO.optString("status"))) {
                    AntOceanRpcCall.repairSeaArea();
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "querySeaAreaDetailList err:", t);
        }
    }

    private static void openWAIT_FOR_UNLOCK() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.querySeaAreaDetailList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            //判断神秘海域
            boolean awardSeaAreaCanCreateExtraCollect = jo.optBoolean("awardSeaAreaCanCreateExtraCollect", false);
            if (awardSeaAreaCanCreateExtraCollect) {

                String args = "[{\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"uniqueId\":\"" + AntOceanRpcCall.getUniqueId() + "\"}]";
                String Extrastr = ApplicationHook.requestString("alipay.antocean.ocean.h5.createSeaAreaExtraCollect", args);
                JSONObject Extrajo = new JSONObject(Extrastr == null ? "{}" : Extrastr);
                if (MessageUtil.checkResultCode(TAG, Extrajo)) {
                    if (Extrajo.has("seaAreaExtraCollectVO")) {
                        Log.other("神奇海洋🐳开启了神秘海域");
                    }
                }
            }
            JSONArray seaAreaVOs = jo.getJSONArray("seaAreaVOs");
            JSONObject seaAreaVO = seaAreaVOs.getJSONObject(seaAreaVOs.length() - 1);
            String LastseaAreaStatus = seaAreaVO.optString("status");
            if (LastseaAreaStatus.equals("WAIT_FOR_UNLOCK")) {
                AntOceanRpcCall.repairSeaArea();
            }
        } catch (Throwable t) {
            Log.err(TAG, "querySeaAreaDetailList err:", t);
        }
    }

    private static void queryOceanPropList() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryOceanPropList());
            if (MessageUtil.checkResultCode(TAG, jo)) {
                AntOceanRpcCall.repairSeaArea();
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryOceanPropList err:", t);
        }
    }

    private static void switchOceanChapter() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryOceanChapterList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            String currentChapterCode = jo.getString("currentChapterCode");
            JSONArray chapterVOs = jo.getJSONArray("userChapterDetailVOList");
            boolean isFinish = false;
            String dstChapterCode = "";
            String dstChapterName = "";
            for (int i = 0; i < chapterVOs.length(); i++) {
                JSONObject chapterVO = chapterVOs.getJSONObject(i);
                int repairedSeaAreaNum = chapterVO.getInt("repairedSeaAreaNum");
                int seaAreaNum = chapterVO.getInt("seaAreaNum");
                if (chapterVO.getString("chapterCode").equals(currentChapterCode)) {
                    isFinish = repairedSeaAreaNum >= seaAreaNum;
                } else {
                    if (repairedSeaAreaNum >= seaAreaNum || !chapterVO.getBoolean("chapterOpen")) {
                        continue;
                    }
                    dstChapterName = chapterVO.getString("chapterName");
                    dstChapterCode = chapterVO.getString("chapterCode");
                }
            }
            if (isFinish && !StringUtil.isEmpty(dstChapterCode)) {
                jo = new JSONObject(AntOceanRpcCall.switchOceanChapter(dstChapterCode));
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    Log.i("神奇海洋🐳切换到[" + dstChapterName + "]系列");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "switchOceanChapter err:", t);
        }
    }

    private void queryUserRanking() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryUserRanking());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (Status.hasFlagToday("Ocean::HELP_CLEAN_ALL_FRIEND_LIMIT")) {
                return;
            }
            JSONArray fillFlagVOList = jo.getJSONArray("fillFlagVOList");
            for (int i = 0; i < fillFlagVOList.length(); i++) {
                JSONObject fillFlag = fillFlagVOList.getJSONObject(i);
                if (cleanOceanType.getValue() != CleanOceanType.NONE) {
                    cleanFriendOcean(fillFlag);
                }
            }
            int pos = 20;
            List<String> idList = new ArrayList<>();
            JSONArray allRankingList = jo.getJSONArray("allRankingList");
            while (pos < allRankingList.length()) {
                JSONObject friend = allRankingList.getJSONObject(pos);
                String userId = friend.optString("userId", "");
                if (userId.equals(UserIdMap.getCurrentUid()) || userId.isEmpty()) {
                    pos++;
                    continue;
                }
                idList.add(userId);
                pos++;
                if (pos % 20 == 0) {
                    jo = new JSONObject(AntOceanRpcCall.fillUserFlag(new JSONArray(idList).toString()));
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        return;
                    }
                    fillFlagVOList = jo.getJSONArray("fillFlagVOList");
                    for (int i = 0; i < fillFlagVOList.length(); i++) {
                        JSONObject fillFlag = fillFlagVOList.getJSONObject(i);
                        if (cleanOceanType.getValue() != CleanOceanType.NONE) {
                            cleanFriendOcean(fillFlag);
                            if (Status.hasFlagToday("Ocean::HELP_CLEAN_ALL_FRIEND_LIMIT")) {
                                return;
                            }
                        }
                    }
                    idList.clear();
                }
            }
            if (!idList.isEmpty()) {
                jo = new JSONObject(AntOceanRpcCall.fillUserFlag(new JSONArray(idList).toString()));
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                fillFlagVOList = jo.getJSONArray("fillFlagVOList");
                for (int i = 0; i < fillFlagVOList.length(); i++) {
                    JSONObject fillFlag = fillFlagVOList.getJSONObject(i);
                    if (cleanOceanType.getValue() != CleanOceanType.NONE) {
                        cleanFriendOcean(fillFlag);
                        if (Status.hasFlagToday("Ocean::HELP_CLEAN_ALL_FRIEND_LIMIT")) {
                            return;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryUserRanking err:", t);
        }
    }

    private void cleanFriendOcean(JSONObject fillFlag) {
        if (!fillFlag.optBoolean("canClean")) {
            return;
        }
        try {
            String userId = fillFlag.getString("userId");
            boolean isCleanOcean = cleanOceanList.getValue().contains(userId);
            if (cleanOceanType.getValue() != CleanOceanType.CLEAN) {
                isCleanOcean = !isCleanOcean;
            }
            if (!isCleanOcean) {
                return;
            }
            if (cleanFriendOcean(userId)) {
                TimeUtil.sleep(1000);
            }
        } catch (Throwable t) {
            Log.err(TAG, "cleanFriendOcean err:", t);
        }
    }

    private Boolean cleanFriendOcean(String userId) {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryFriendPage(userId));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            if (Status.hasFlagToday("Ocean::HELP_CLEAN_ALL_FRIEND_LIMIT")) {
                return false;
            }
            jo = new JSONObject(AntOceanRpcCall.cleanFriendOcean(userId));
            if (jo.has("resultDesc")) {
                if (jo.getString("resultDesc").contains("上限")) {
                    Log.other("神奇海洋🐳" + jo.getString("resultDesc"));
                    Status.flagToday("Ocean::HELP_CLEAN_ALL_FRIEND_LIMIT");
                }
                return false;
            }
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Log.other("神奇海洋🐳帮助[" + UserIdMap.getMaskName(userId) + "]清理海域");
                JSONArray cleanRewardVOS = jo.getJSONArray("cleanRewardVOS");
                checkReward(cleanRewardVOS);
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "cleanFriendOcean err:", t);
        }
        return false;
    }

    /**
     * 帮**一位**好友清理海洋，用于完成「每日任务：帮好友清理垃圾」。
     * <p>与 {@link #queryUserRanking()} 的批量清理不同：不受「清理海域|动作」「清理海域|好友列表」开关约束，
     * 只为完成该日常任务清理一个 canClean=true 的好友即止（官方动作实测 = queryFriendPage + cleanFriendOcean）。
     *
     * @return 是否真的清理成功
     */
    private boolean helpCleanOneFriend() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryUserRanking());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            if (cleanOneFromFillFlagList(jo.optJSONArray("fillFlagVOList"))) {
                return true;
            }
            // 排行榜中较后位置的好友可能没带 canClean 标记，向服务端补问一批（最多 20 个）
            JSONArray allRankingList = jo.optJSONArray("allRankingList");
            if (allRankingList == null) {
                return false;
            }
            List<String> idList = new ArrayList<>();
            for (int i = 0; i < allRankingList.length() && idList.size() < 20; i++) {
                String userId = allRankingList.getJSONObject(i).optString("userId", "");
                if (userId.isEmpty() || userId.equals(UserIdMap.getCurrentUid())) {
                    continue;
                }
                idList.add(userId);
            }
            if (idList.isEmpty()) {
                return false;
            }
            jo = new JSONObject(AntOceanRpcCall.fillUserFlag(new JSONArray(idList).toString()));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            return cleanOneFromFillFlagList(jo.optJSONArray("fillFlagVOList"));
        } catch (Throwable t) {
            Log.err(TAG, "helpCleanOneFriend err:", t);
        }
        return false;
    }

    /**
     * 从 fillFlagVOList 里挑一个好友清理，最多试 3 个，成功即止。
     * <p>只做"能不能清"的筛选，不看「好友列表」配置——配置为空时也要能完成日常任务。
     */
    private boolean cleanOneFromFillFlagList(JSONArray fillFlagVOList) {
        if (fillFlagVOList == null) {
            return false;
        }
        try {
            int tried = 0;
            for (int i = 0; i < fillFlagVOList.length() && tried < 3; i++) {
                JSONObject fillFlag = fillFlagVOList.optJSONObject(i);
                if (fillFlag == null || !fillFlag.optBoolean("canClean")) {
                    continue;
                }
                String userId = fillFlag.optString("userId", "");
                if (userId.isEmpty() || userId.equals(UserIdMap.getCurrentUid())) {
                    continue;
                }
                tried++;
                if (cleanFriendOcean(userId)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "cleanOneFromFillFlagList err:", t);
        }
        return false;
    }

    private void queryTaskList() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryTaskList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray ja = jo.getJSONArray("antOceanTaskVOList");
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                String taskStatus = jo.optString("taskStatus");
                String sceneCode = jo.getString("sceneCode");
                String taskType = jo.getString("taskType");
                JSONObject bizInfo = new JSONObject(jo.getString("bizInfo"));
                String taskTitle = bizInfo.optString("taskTitle");
                if (TaskStatus.RECEIVED.name().equals(taskStatus)) {
                    continue;
                }
                if (TaskStatus.TODO.name().equals(taskStatus) && !finishOceanTask(jo)) {
                    continue;
                }
                TimeUtil.sleep(500);

                receiveTaskAward(sceneCode, taskType, taskTitle);
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryTaskList err:", t);
        }
    }

    //日常任务
    private static void receiveTaskAward(String sceneCode, String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.receiveTaskAward(sceneCode, taskType));
            TimeUtil.sleep(500);
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntOceanAntiepTaskList", taskTitle, jo);
            if (MessageUtil.checkSuccess(TAG, jo)) {
                String awardCount = jo.optString("incAwardCount");
                Log.other("海洋任务🎖️领取[" + taskTitle + "]奖励#获得[" + awardCount + "块拼图]");
            }
        } catch (Throwable t) {
            Log.err(TAG, "receiveTaskAward err:", t);
        }
    }

    private Boolean finishOceanTask(JSONObject task) {
        try {
            if (task.has("taskProgress")) {
                // 进度类任务（如"连续N天来海洋"）无法用 RPC 直接完成，也不能拉黑（否则会永久跳过真任务）；
                // 这里显式记录，避免"列表拿到了却没动作、日志也没有"
                Log.i("海洋任务⏭️跳过[进度任务暂不自动完成]#taskType=" + task.optString("taskType"));
                return false;
            }
            JSONObject bizInfo = new JSONObject(task.getString("bizInfo"));
            String taskTitle = bizInfo.optString("taskTitle");
            //黑名单任务跳过
            if (AntOceanAntiepTaskList.getValue().contains(taskTitle)) {
                return false;
            }
            String sceneCode = task.getString("sceneCode");
            String taskType = task.getString("taskType");
            // 答题任务走独立流程
            if (taskTitle.equals("每日任务：答题学海洋知识")) {
                if (answerQuestion()) {
                    Log.other("海洋任务🧾完成[" + taskTitle + "]");
                    return true;
                }
                return false;
            }
            // 帮好友清理垃圾：服务端 bizInfo.autoCompleteTask=false ⇒ finishTask 必被拒（400000040 不支持rpc调用）。
            // 实测（2026-09-22 抓包 logs/chk_ocean）官方动作 = queryFriendPage + cleanFriendOcean(cleanedUserId)，
            // 清理成功后服务端**自行**把该任务翻成 FINISHED，随后由调用点的 receiveTaskAward 领奖，
            // 全程没有 finishTask，也不需要客户端申报
            if ("FRIENDRUBBISCLEAN_EVERYDAY_NEW".equals(taskType) || taskTitle.contains("帮好友清理垃圾")) {
                if (helpCleanOneFriend()) {
                    Log.other("海洋任务🧾完成[" + taskTitle + "]#帮好友清理垃圾");
                    return true;
                }
                Log.other("海洋任务⚠️未完成[" + taskTitle + "]#taskType=" + taskType + "，本次没找到可清理的好友");
                return false;
            }
            // 限时任务不自动完成（自动完成易触发风控），显式记录而不是静默跳过
            if (taskTitle.startsWith("限时任务：")) {
                Log.i("海洋任务⏭️跳过[" + taskTitle + "]#taskType=" + taskType + "，限时任务不自动完成");
                return false;
            }
            // 其余 TODO 一律尝试完成：原先按中文文案 + taskType 白名单精确分派，服务端一改文案
            // 或换个 taskType 变体就会整类任务一个请求都不发（列表拿到了却没动作、日志也没有）。
            // 做不了的由自动拉黑机制接管，避免用"服务端字符串精确相等"这种不稳定假设当开关
            // 做不了的当天只试一次、临时故障留待下一轮（见 TaskAttemptPolicy）
            Outcome outcome = TaskAttemptPolicy.handle("ocean::" + sceneCode + "/" + taskType, taskTitle, null,
                    () -> attemptFinishOceanTask(sceneCode, taskType, taskTitle), Log::other,
                    new TaskAttemptPolicy.Site("AntOceanAntiepTaskList", "海洋任务", taskType, sceneCode));
            return outcome == Outcome.DONE || outcome == Outcome.TRIGGERED;
        } catch (Throwable t) {
            Log.err(TAG, "finishOceanTask err:", t);
        }
        return false;
    }

    /** 海洋任务完成上报：做不了的只记一行、不拉黑；"不支持rpc"交给通用类伪申报 */
    private static Outcome attemptFinishOceanTask(String sceneCode, String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.finishTask(sceneCode, taskType));
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntOceanAntiepTaskList", taskTitle, jo);
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.other("海洋任务🧾完成[" + taskTitle + "]");
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            // 不支持rpc调用（400000040）→ 由 TaskAttemptPolicy 代为伪申报
            if (TaskAlternative.hit(jo, sceneCode)) {
                return Outcome.UNSUPPORTED;
            }
            Log.other("海洋任务⚠️未完成[" + taskTitle + "]#taskType=" + taskType + "，需在支付宝内手动完成");
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "finishOceanTask err:", t);
        }
        return Outcome.RETRY;
    }

    // 海洋答题任务
    private static Boolean answerQuestion() {
        // 与海洋其它任务一致：当天成功过就不再重复请求（服务端的 answered 只作兜底）
        if (Status.hasFlagToday("Ocean::ANSWER_QUESTION")) {
            return false;
        }
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.getQuestion());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            if (jo.getBoolean("answered")) {
                Log.record("问题已经被回答过，跳过答题流程");
                return false;
            }
            String questionId = jo.getString("questionId");
            JSONArray options = jo.getJSONArray("options");
            if (options.length() == 0) {
                Log.record("海洋答题：选项为空，跳过");
                return false;
            }
            // 与蚂蚁新村每日答题用的是同一个 dada 接口，这里同样交给 AI 作答
            String answer = AnswerAI.getAnswer(jo.optString("title"), JsonUtil.jsonArrayToList(options));
            TimeUtil.sleep(500);
            jo = new JSONObject(AntOceanRpcCall.submitAnswer(answer, questionId));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Log.other("海洋答题成功");
                Status.flagToday("Ocean::ANSWER_QUESTION");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "answerQuestion err:", t);
        }
        return false;
    }

    // 制作万能拼图
    private static void exchangeUniversalPiece() {
        try {
            // 获取道具兑换列表的JSON数据
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryOceanPropList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            // 获取重复拼图数量
            int duplicatePieceNum = jo.getInt("duplicatePieceNum");
            while (duplicatePieceNum >= 10) {
                // 如果重复拼图数量大于等于10，则执行道具兑换操作
                int exchangeNum = Math.min(duplicatePieceNum / 10, 50);
                if (!exchangeUniversalPiece(exchangeNum)) {
                    break;
                }
                TimeUtil.sleep(1000);
                duplicatePieceNum -= exchangeNum * 10;
            }
        } catch (Throwable t) {
            Log.err(TAG, "exchangeUniversalPiece error:", t);
        }
    }

    private static Boolean exchangeUniversalPiece(int number) {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.exchangeUniversalPiece(number));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                String duplicatePieceNum = jo.getString("duplicatePieceNum");
                String exchangeNum = jo.getString("exchangeNum");
                Log.other("神奇海洋🐳制作[万能拼图*" + exchangeNum + "]#剩余[重复拼图*" + duplicatePieceNum + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "exchangeUniversalPiece error:", t);
        }
        return false;
    }

    // 使用万能拼图
    private static void useUniversalPiece() {
        try {
            // 获取道具使用类型列表的JSON数据
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryOceanPropList("UNIVERSAL_PIECE"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            // 获取道具类型列表中的holdsNum值
            JSONArray oceanPropVOByTypeList = jo.getJSONArray("oceanPropVOByTypeList");
            // 遍历每个道具类型信息
            for (int i = 0; i < oceanPropVOByTypeList.length(); i++) {
                JSONObject oceanPropVO = oceanPropVOByTypeList.getJSONObject(i);
                int holdsNum = oceanPropVO.getInt("holdsNum");
                int pageNum = 0;
                boolean hasMore = true;
                // 兜底：最多翻 50 页。原先只看 hasMore，服务端若恒返回 true 且每页都没消耗，会无限翻页发 RPC
                while (holdsNum > 0 && hasMore && pageNum < 50) {
                    // 查询鱼列表的JSON数据
                    pageNum++;
                    jo = new JSONObject(AntOceanRpcCall.queryFishList(pageNum));
                    // 检查是否成功获取到鱼列表并且 hasMore 为 true
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        // 如果没有成功获取到鱼列表或者 hasMore 为 false，则停止后续操作
                        return;
                    }
                    hasMore = jo.optBoolean("hasMore");
                    // 获取鱼列表中的fishVOS数组
                    if (!jo.has("fishVOS")) {
                        return;
                    }
                    JSONArray fishVOS = jo.getJSONArray("fishVOS");
                    int used = useUniversalPiece(fishVOS, holdsNum);
                    if (used <= 0) {
                        // 本页没有可用拼图（或替换失败）：持有数不会减少，继续翻页也是空转，直接结束
                        break;
                    }
                    holdsNum -= used;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "useUniversalPiece error:", t);
        }
    }

    private static int useUniversalPiece(JSONArray fishVOS, int holdsNum) {
        int count = 0;
        try {
            for (int i = 0; i < fishVOS.length() && count < holdsNum; i++) {
                JSONObject fishVO = fishVOS.getJSONObject(i);
                if (!fishVO.has("pieces")) {
                    continue;
                }
                count += useUniversalPiece(fishVO, holdsNum - count);
            }
        } catch (Throwable t) {
            Log.err(TAG, "useUniversalPiece error:", t);
        }
        return count;
    }

    private static int useUniversalPiece(JSONObject fishVO, int holdsNum) {
        JSONArray assetsDetails = new JSONArray();
        try {
            int order = fishVO.getInt("order");
            String name = fishVO.getString("name");
            JSONArray pieces = fishVO.getJSONArray("pieces");
            for (int i = 0; i < pieces.length(); i++) {
                JSONObject piece = pieces.getJSONObject(i);
                if (piece.getInt("num") > 1) {
                    continue;
                }
                JSONObject assetsDetail = new JSONObject();
                assetsDetail.put("assets", order);
                assetsDetail.put("assetsNum", 1);
                assetsDetail.put("attachAssets", Integer.parseInt(piece.getString("id")));
                assetsDetail.put("propCode", "UNIVERSAL_PIECE");
                assetsDetails.put(assetsDetail);
                if (assetsDetails.length() == holdsNum) {
                    break;
                }
            }
            if (useUniversalPiece(assetsDetails, name, holdsNum - assetsDetails.length())) {
                TimeUtil.sleep(1000);
                return assetsDetails.length();
            }
        } catch (Throwable t) {
            Log.err(TAG, "useUniversalPiece error:", t);
        }
        return 0;
    }

    private static Boolean useUniversalPiece(JSONArray assetsDetails, String name, int holdsNum) {
        try {
            if (assetsDetails.length() == 0) {
                return false;
            }
            JSONObject jo = new JSONObject(AntOceanRpcCall.useUniversalPiece(assetsDetails));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                int userCount = assetsDetails.length();
                Log.other("神奇海洋🐳使用[万能拼图*" + userCount + "]迎回[" + name + "]#剩余[万能拼图*" + holdsNum + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "useUniversalPiece error:", t);
        }
        return false;
    }

    public interface CleanOceanType {

        int NONE = 0;
        int CLEAN = 1;
        int NOT_CLEAN = 2;

        String[] nickNames = {"不清理海域", "清理已选好友", "清理未选好友"};

    }

    // ========== 神秘海洋（摸鱼）相关方法 ==========

    /**
     * 神秘海洋主方法
     */
    private void antfishRun() {
        try {
            // 1. 查询海洋状态
            if (!antfishQueryStatus()) {
                return;
            }

            // 2. 查询主页信息
            antfishQueryHomePage();

            // 3. 查询并处理任务
            if (antfishAutoTask.getValue()) {
                antfishHandleTasks();
            }

            // 4. 执行摸鱼
            touchfish();


        } catch (Throwable t) {
            Log.err(TAG, "antfishRun err:", t);
        }
    }

    /**
     * 查询海洋状态
     */
    private boolean antfishQueryStatus() {
        try {
            String result = AntOceanRpcCall.antfishStatus();
            JSONObject jo = new JSONObject(result);

            if (MessageUtil.checkResultCode(TAG, jo)) {
                String fishStatus = jo.optString("fishStatus", "DEFAULT_AI_FISH");
                int level = jo.optInt("level", 0);
                if("DEFAULT_AI_FISH".equals(fishStatus)){
                    drawFish();
                }
                //Log.record("海洋摸鱼🐟状态[" + fishStatus + "]等级" + level);
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "antfishQueryStatus err:", t);
        }
        return false;
    }

    /**
     * 查询主页信息
     */
    private void antfishQueryHomePage() {
        try {
            String result = AntOceanRpcCall.antfishHomepage();
            JSONObject jo = new JSONObject(result);

            if (MessageUtil.checkResultCode(TAG, jo)) {
                // 获取当前赛季信息
                String seasonTitle = "";
                String seasonId = "";
                String projectName = "";
                String region = "";
                String fishLevelName = "";
                String fishInteractStatus= "";
                String nickName = "";
                int touchEnergy = 0;
                int touchTotal = 0;
                int remainTouchChance = 0;
                JSONObject currentSeasonInfo = jo.optJSONObject("currentSeasonInfo");
                if (currentSeasonInfo != null) {
                    seasonId = currentSeasonInfo.optString("seasonId", "");
                    seasonTitle = "";
                    JSONObject extInfo = currentSeasonInfo.optJSONObject("extInfo");
                    if (extInfo != null) {
                        seasonTitle = extInfo.optString("seasonTitle", "");
                    }
                }

                // 获取能量
                long energy = jo.optLong("energy", 0);
                //Log.record("海洋摸鱼🐟当前能量" + energy);

                // 获取项目信息
                JSONObject project = jo.optJSONObject("project");
                if (project != null) {
                    projectName = project.optString("projectName", "");
                    region = project.optString("region", "");
                }
                // 获取摸鱼信息
                JSONObject myFish = jo.optJSONObject("myFish");
                if (myFish != null) {
                    nickName = myFish.optString("nickName", "");
                    touchEnergy = myFish.optInt("touchEnergy", 0);

                    // 获取鱼等级信息
                    JSONObject currentLevel = myFish.optJSONObject("currentLevel");
                    if (currentLevel != null) {
                        fishLevelName = currentLevel.optString("name", "");
                    }
                    JSONObject interactVO = myFish.optJSONObject("interactVO");
                    if (interactVO != null) {
                        touchTotal = interactVO.optInt("touchTotal", 0);
                        fishInteractStatus = interactVO.optString("fishInteractStatus", "");
                        remainTouchChance = interactVO.optInt("remainTouchChance", 0);
                        
                        // 解析鱼主人信息
                        JSONObject owner = interactVO.optJSONObject("owner");
                        if (owner != null) {
                            String ownerNickName = owner.optString("nickName", "");
                            String ownerUserId = owner.optString("userId", "");
                            if (!ownerNickName.isEmpty()) {
                                Log.record("海洋摸鱼🐟当前鱼状态["+ fishInteractStatus +"]主人[" + ownerNickName + "](" + ownerUserId + ")");
                            }
                        }
                    }
                }
                Log.record("海洋摸鱼🐟当前赛季[" + seasonTitle + "](" + seasonId + ")项目[" + projectName + "](" + region + ")用户[" + nickName + "]鱼状态["+ fishInteractStatus +"]等级[" + fishLevelName + "]可摸鱼"+remainTouchChance+"累计摸" + touchTotal + "累计获得能量" + touchEnergy + "g(总"+energy+"g)");
                //鱼被困了，解救鱼
                if("CAPTURED".equals(fishInteractStatus)){
                    rescueFish();
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "antfishQueryHomePage err:", t);
        }
    }

    private boolean drawFish() {
        try {
            // 鱼图片URL列表
            String[] fishImgUrls = {
                "https://mdn.alipayobjects.com/afts/img/JNVwTJAPzqMAAAAAQOAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/ZK3FTK8eQ-IAAAAAQIAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/Se_RQ7215tsAAAAAQQAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/n8yQSYH-lvgAAAAAQGAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/_rKKT6IJRWcAAAAAQKAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/_zy6QaSffRMAAAAAQGAAAAgA9KBtAQJr/original?bz=ai_fish",
                "https://mdn.alipayobjects.com/afts/img/3tFWT43StnAAAAAAQHAAAAgA9KBtAQJr/original?bz=ai_fish"
            };
            
            // 随机选择一张图片
            int randomIndex = (int) (Math.random() * fishImgUrls.length);
            String imgUrl = fishImgUrls[randomIndex];
            
            // 从URL中提取imgId（img/和/original之间的部分）
            String imgId = imgUrl.substring(imgUrl.indexOf("img/") + 4, imgUrl.indexOf("/original"));
            
            String result = AntOceanRpcCall.drawFish(imgId, imgUrl);
            JSONObject jo = new JSONObject(result);

            if (MessageUtil.checkResultCode(TAG, jo)) {
                Log.other("开通摸鱼🐟提交画鱼成功");
                Toast.show("开通摸鱼🐟提交画鱼成功");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "antfishFinishTask err:", t);
        }
        return false;
    }

    //解救鱼
    private boolean rescueFish() {
        try {
            String result = AntOceanRpcCall.rescueFish();
            JSONObject jo = new JSONObject(result);
            if (MessageUtil.checkResultCode(TAG, jo)) {
                // 解析解救后的状态
                JSONObject myFish = jo.optJSONObject("myFish");
                if (myFish != null) {
                    JSONObject interactVO = myFish.optJSONObject("interactVO");
                    if (interactVO != null) {
                        String fishInteractStatus = interactVO.optString("fishInteractStatus", "");
                        int remainChance = interactVO.optInt("remainTouchChance", 0);
                        int touchTotal = interactVO.optInt("touchTotal", 0);
                        
                        Log.other("摸鱼解救🐟解救成功[" + fishInteractStatus + "]可摸鱼次数" + remainChance + "累计摸鱼" + touchTotal);
                        Toast.show("摸鱼解救🐟解救成功[" + fishInteractStatus + "]");
                    } else {
                        Log.other("摸鱼任务🐟解救成功");
                    }
                } else {
                    Log.other("摸鱼任务🐟解救成功");
                }
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "rescueFish err:", t);
        }
        return false;
    }

    /**
     * 处理任务（查询并完成任务获取摸鱼次数）
     */
    private void antfishHandleTasks() {
        try {
            String result = AntOceanRpcCall.antfishListTask();
            JSONObject jo = new JSONObject(result);

            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }

            JSONArray taskInfoList = jo.optJSONArray("taskInfoList");
            if (taskInfoList == null || taskInfoList.length() == 0) {
                Log.i("海洋摸鱼🐟暂无任务");
                return;
            }

            //Log.other("海洋摸鱼🐟发现 " + taskInfoList.length() + " 个任务");

            for (int i = 0; i < taskInfoList.length(); i++) {
                JSONObject taskInfo = taskInfoList.optJSONObject(i);
                if (taskInfo == null) continue;

                // 解析任务基础信息
                JSONObject taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo");
                if (taskBaseInfo == null) continue;

                String taskType = taskBaseInfo.optString("taskType", "");
                String taskStatus = taskBaseInfo.optString("taskStatus", "");
                String taskMode = taskBaseInfo.optString("taskMode", "");

                // 解析 bizInfo
                JSONObject bizInfo = new JSONObject(taskBaseInfo.optString("bizInfo", "{}"));
                String taskTitle = bizInfo.optString("taskTitle", "未知任务");

                // 解析任务奖励信息
                JSONObject taskRights = taskInfo.optJSONObject("taskRights");
                int awardCount = taskRights != null ? taskRights.optInt("awardCount", 0) : 0;

                // 已完成任务领取奖励
                if ("FINISHED".equals(taskStatus)) {
                    if (antfishReceiveTaskAward(taskType)) {
                        Log.other("摸鱼任务🎖️领取[" + taskTitle + "]获得摸鱼次数*" + awardCount);
                    }
                    continue;
                }
                //黑名单任务跳过
                if (AntOceanFishBlackList.getValue().contains(taskTitle)) {
                    continue;
                }
                // 处理其他 TODO 状态任务
                if ("TODO".equals(taskStatus)) {
                    if (antfishFinishTask(taskTitle, taskType)) {
                        if (antfishReceiveTaskAward(taskType)) {
                            Log.other("摸鱼任务🎖️领取[" + taskTitle + "]获得摸鱼次数*" + awardCount);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "antfishHandleTasks err:", t);
        }
    }

    /**
     * 完成任务
     */
    private boolean antfishFinishTask(String taskTitle, String taskType) {
        try {
            String result = AntOceanRpcCall.antfishFinishTask(taskType);
            JSONObject jo = new JSONObject(result);
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntOceanFishBlackList", taskTitle, jo);
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Log.other("摸鱼任务🧾完成[" + taskTitle + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "antfishFinishTask err:", t);
        }
        return false;
    }

    /**
     * 领取任务奖励
     */
    private boolean antfishReceiveTaskAward(String taskType) {
        try {
            if (Status.hasFlagToday("Ocean::ANTAIFISH_TaskAward")) {
                return false;
            }
            String result = AntOceanRpcCall.antfishReceiveTaskAward(taskType);
            JSONObject jo = new JSONObject(result);
            if (!jo.optBoolean("success", false)) {
                Log.record("领取海洋摸鱼[" + jo.optString("desc", "无返回结果") + "]今天不再执行");
                Status.flagToday("Ocean::ANTAIFISH_TaskAward_Limitid");
            }
            if (MessageUtil.checkResultCode(TAG, jo)) {
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "antfishReceiveTaskAward err:", t);
        }
        return false;
    }

    /**
     * 执行摸鱼
     */
    private void touchfish() {
        try {
            // 先查询主页获取剩余摸鱼次数
            String homeResult = AntOceanRpcCall.antfishHomepage();
            JSONObject homeJo = new JSONObject(homeResult);

            if (!MessageUtil.checkResultCode(TAG, homeJo)) {
                return;
            }

            // 从主页响应中获取摸鱼相关信息
            JSONObject myFish = homeJo.optJSONObject("myFish");
            if (myFish == null) {
                Log.record("海洋摸鱼未获取到[我的鱼信息]");
                return;
            }

            // 获取交互状态
            JSONObject interactVO = myFish.optJSONObject("interactVO");
            if (interactVO == null) {
                Log.i("海洋摸鱼🐟未获取到交互状态信息");
                return;
            }

            int remainTouchChance = interactVO.optInt("remainTouchChance", 0);

            //Log.other("海洋摸鱼🐟剩余摸鱼次数" + remainTouchChance);

            if (remainTouchChance <= 0) {
                //Log.other("海洋摸鱼🐟今日摸鱼次数已用完");
                return;
            }

            // 执行摸鱼循环
            int touchCount = 0;
            int totalEnergy = 0;
            // 护底：防止服务端未返回剩余摸鱼次数导致 while 永不退出（线程永久挂起，只能重启进程恢复）
            int touchGuard = 0;
            final int MAX_TOUCH = 200;
            while (remainTouchChance > 0 && touchGuard < MAX_TOUCH) {
                touchGuard++;
                String touchResult = AntOceanRpcCall.antfishTouchfish();
                JSONObject touchJo = new JSONObject(touchResult);

                if (!MessageUtil.checkResultCode(TAG, touchJo)) {
                    Log.other("海洋摸鱼🐟摸鱼失败");
                    break;
                }

                touchCount++;

                // 解析摸鱼结果
                String touchType = touchJo.optString("touchType", "");
                JSONArray touchRewardList = touchJo.optJSONArray("touchRewardList");

                int energyGain = 0;
                String rewardDesc = "";
                String rewardType = "";

                // 循环处理奖励列表
                for (int i = 0; touchRewardList != null && i < touchRewardList.length(); i++) {
                    JSONObject reward = touchRewardList.optJSONObject(i);
                    if (reward == null) continue;

                    JSONObject extInfo = reward.optJSONObject("extInfo");
                    if (extInfo == null) continue;

                    JSONObject popup = extInfo.optJSONObject("popup");
                    if (popup == null) continue;

                    energyGain += popup.optInt("rightsNums", 0);
                    if (rewardDesc.isEmpty()) {
                        rewardDesc = popup.optString("name", "");
                    }
                    if (rewardType.isEmpty()) {
                        rewardType = reward.optString("rewardType", "");
                    }

                    // 检查是否摸到鱼
                    JSONObject captureInfoVO = touchJo.optJSONObject("captureInfoVO");
                    if (captureInfoVO != null && "touchFish".equals(touchType)) {
                        String captureNickName = captureInfoVO.optString("nickName", "");
                        String captureUserId = captureInfoVO.optString("userId", "未知ID");
                        String displayName = captureNickName.isEmpty() ? captureUserId : captureNickName;
                        Log.other("海洋摸鱼🐟摸到了[" + displayName + "]的鱼获得" + popup.optInt("rightsNums", 0) + "g能量");
                        Toast.show("海洋摸鱼🐟获得" + popup.optInt("rightsNums", 0) + "g能量");
                    } else {
                        Log.other("海洋摸鱼🐟[" + popup.optString("name", "") + "]" + popup.optInt("rightsNums", 0) + "g");
                        Toast.show("海洋摸鱼🐟获得" + popup.optInt("rightsNums", 0) + "g能量");
                    }

                    totalEnergy += energyGain;
                    Statistics.addData(Statistics.DataType.COLLECTED, energyGain);
                }

                // 更新剩余次数：仅当响应明确带回 myFish.interactVO 时才采用，否则视为无法继续，安全退出避免死循环
                boolean remainUpdated = false;
                JSONObject myFishResult = touchJo.optJSONObject("myFish");
                if (myFishResult != null) {
                    JSONObject interactResult = myFishResult.optJSONObject("interactVO");
                    if (interactResult != null) {
                        remainTouchChance = interactResult.optInt("remainTouchChance", 0);
                        remainUpdated = true;
                    }
                }
                if (!remainUpdated) {
                    Log.record("海洋摸鱼🐟服务端未返回剩余摸鱼次数，结束摸鱼循环避免死循环");
                    break;
                }
            }

            if (touchGuard >= MAX_TOUCH) {
                Log.record("海洋摸鱼🐟摸鱼循环达到上限[" + MAX_TOUCH + "]，强制退出（疑似服务端未正确递减剩余次数）");
            }

            if (touchCount > 0) {
                Log.other("海洋摸鱼🐟本次共摸鱼" + touchCount + "次获得" + totalEnergy + "g能量");
                Toast.show("海洋摸鱼🐟获得" + totalEnergy + "g能量");
            }

        } catch (Throwable t) {
            Log.err(TAG, "antfishDrawFish err:", t);
        }
    }

}
