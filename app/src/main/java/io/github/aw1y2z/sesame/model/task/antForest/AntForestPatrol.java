package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ChoiceModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAward;
import io.github.aw1y2z.sesame.entity.AlipayMonopolyTaskList;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.MonopolyTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 保护地：旧版巡护、新版保护地（自动前进 / 自动任务 / 兑换证书）、动物伙伴。
 */
public final class AntForestPatrol {

    private static final String TAG = AntForestV2.class.getSimpleName();

    /**
     * 新版保护地任务场景码：跨保护地区域固定的场景标识。
     * 保护地区域会轮换（洪山动物园→玉树东仲→…），但 regionCode 由 entryInfo 动态下发、
     * sceneCode 始终复用此值；服务端按 regionCode 返回该区域任务，故区域轮换无需改代码。
     */
    private static final String MONOPOLY_TASK_SCENE_HSDWY = "ANTFOREST_MONOPOLY_TASK_HSDWY";

    /** 新版保护地任务下发的等待时长上限，超过视为异常 */
    private static final long MONOPOLY_MAX_TASK_SECONDS = 300L;

    private static final int MAX_PATROL_STEPS = 50;
    private static final int MAX_PATROL_ROUNDS = 100;
    private static final int MAX_MONOPOLY_TASK_ROUNDS = 100;

    private static BooleanModelField protectionGroup;
    private static BooleanModelField userPatrol;
    private static BooleanModelField monopolyGroup;
    private static BooleanModelField monopolyPatrol;
    private static BooleanModelField monopolyTasks;
    private static BooleanModelField monopolyExchangeCert;
    private static BooleanModelField animalGroup;
    private static BooleanModelField monopolyAnimalEnergy;
    private static BooleanModelField monopolyDispatchAnimal;
    private static BooleanModelField AutoMonopolyTaskList;
    private static SelectModelField MonopolyTaskList;
    private static ChoiceModelField animalDispatchRule;

    private AntForestPatrol() {
    }

    /**
     * 注册保护地与动物伙伴配置项：三层父子全部靠 dependsOn 联动，不引入额外的分组渲染。
     * <pre>
     * 保护地及动物伙伴
     *  ├ 旧版保护地 | 巡护
     *  ├ 新版保护地
     *  │  ├ 新版保护地 | 自动前进
     *  │  ├ 新版保护地 | 自动任务 → 自动黑名单 → 黑名单列表
     *  │  └ 新版保护地 | 自动兑换证书
     *  └ 动物伙伴
     *     ├ 动物伙伴 | 领取能量
     *     └ 动物伙伴 | 自动派遣 → 派遣规则
     * </pre>
     * 父级默认开启：子项本身默认关闭，所以升级后既不会隐藏老用户的已有配置，也不会凭空多做事。
     */
    public static void addFields(ModelFields modelFields) {
        modelFields.addField(protectionGroup = new BooleanModelField("protectionGroup", "保护地及动物伙伴", true));
        modelFields.addField(userPatrol = new BooleanModelField("userPatrol", "旧版保护地 | 巡护", false).setDependsOn("protectionGroup"));
        modelFields.addField(monopolyGroup = new BooleanModelField("monopolyGroup", "新版保护地", true).setDependsOn("protectionGroup"));
        modelFields.addField(monopolyPatrol = new BooleanModelField("monopolyPatrol", "新版保护地 | 自动前进", false).setDependsOn("monopolyGroup"));
        modelFields.addField(monopolyTasks = new BooleanModelField("monopolyTasks", "新版保护地 | 自动任务", false).setDependsOn("monopolyGroup"));
        modelFields.addField(AutoMonopolyTaskList = new BooleanModelField("AutoMonopolyTaskList", "新版保护地 | 自动黑名单", true).setDependsOn("monopolyTasks"));
        modelFields.addField(MonopolyTaskList = new SelectModelField("MonopolyTaskList", "新版保护地 | 黑名单列表", new LinkedHashSet<>(), AlipayMonopolyTaskList::getList).setDependsOn("AutoMonopolyTaskList"));
        modelFields.addField(monopolyExchangeCert = new BooleanModelField("monopolyExchangeCert", "新版保护地 | 自动兑换证书", false)
                .setDescription("每张图消耗 5000g 能量")
                .setDependsOn("monopolyGroup"));
        modelFields.addField(animalGroup = new BooleanModelField("animalGroup", "动物伙伴", true).setDependsOn("protectionGroup"));
        modelFields.addField(monopolyAnimalEnergy = new BooleanModelField("monopolyAnimalEnergy", "动物伙伴 | 领取能量", false).setDependsOn("animalGroup"));
        modelFields.addField(monopolyDispatchAnimal = new BooleanModelField("monopolyDispatchAnimal", "动物伙伴 | 自动派遣", false).setDependsOn("animalGroup"));
        modelFields.addField(animalDispatchRule = new ChoiceModelField("animalDispatchRule", "动物伙伴 | 派遣规则", AnimalDispatchRule.SEQUENCE, AnimalDispatchRule.nickNames).setDependsOn("monopolyDispatchAnimal"));
    }

    public interface AnimalDispatchRule {

        int SEQUENCE = 0;
        int QUANTITY = 1;

        String[] nickNames = {"按默认顺序", "按最多能量"};
    }

    /** 新版保护地任务列表开关是否开启（父级关闭时一并视为关闭） */
    public static boolean isTaskListEnabled() {
        return Boolean.TRUE.equals(protectionGroup.getValue())
                && Boolean.TRUE.equals(monopolyGroup.getValue())
                && Boolean.TRUE.equals(monopolyTasks.getValue());
    }

    /**
     * 按开关执行巡护 / 保护地 / 动物伙伴，父级关闭则整块跳过。
     *
     * @param canConsumeAnimalProp 本次是否还能消耗动物道具（false 表示已有动物在巡护）
     */
    public static void run(boolean canConsumeAnimalProp) {
        if (!Boolean.TRUE.equals(protectionGroup.getValue())) {
            return;
        }
        if (Boolean.TRUE.equals(userPatrol.getValue())) {
            queryUserPatrol();
        }
        if (Boolean.TRUE.equals(monopolyGroup.getValue())) {
            if (Boolean.TRUE.equals(monopolyPatrol.getValue())) {
                monopolyPatrol();
            }
        }
        if (Boolean.TRUE.equals(animalGroup.getValue())) {
            if (Boolean.TRUE.equals(monopolyAnimalEnergy.getValue())) {
                collectMonopolyAnimalEnergy();
            }
            dispatchAnimal(canConsumeAnimalProp);
        }
    }

    /**
     * 新版保护地任务列表初始化：把服务端下发的任务名同步到 {@link MonopolyTaskListMap}，
     * 作为配置页「新版保护地 | 黑名单列表」的候选项。
     * <p>没有预置黑白名单：新版保护地任务名由服务端动态下发，全部交给自动拉黑机制判定。
     */
    public static void syncTaskList() {
        try {
            MonopolyTaskListMap.load();
            // regionCode 由 entryInfo 动态下发(区域轮换无需改代码),只拉当前区域任务作为黑名单候选项
            String regionCode = getMonopolyRegionCode();
            if (!regionCode.isEmpty()) {
                JSONObject jo = monopolyResponse(AntForestRpcCall.listMonopolyTasks(regionCode, MONOPOLY_TASK_SCENE_HSDWY));
                if (MessageUtil.checkSuccess(TAG, jo)) {
                    JSONArray taskInfoList = jo.optJSONArray("taskInfoList");
                    if (taskInfoList != null) {
                        for (int i = 0; i < taskInfoList.length(); i++) {
                            JSONObject task = taskInfoList.optJSONObject(i);
                            JSONObject base = task == null ? null : task.optJSONObject("taskBaseInfo");
                            if (base == null) {
                                continue;
                            }
                            String taskType = base.optString("taskType");
                            if (taskType.isEmpty()) {
                                continue;
                            }
                            String title = AntForestV2.blackTaskKey(monopolyTaskTitle(base, taskType));
                            MonopolyTaskListMap.add(title, title);
                        }
                    }
                }
            }
            MonopolyTaskListMap.save();
            Log.record("同步任务🉑新版保护地任务列表");
        } catch (Throwable t) {
            Log.err(TAG, "initMonopolyTaskListMap err:", t);
        }
    }

    private static void queryUserPatrol() {
        try {
            // 换图/走路/兑换都用 continue 回到循环头，原本没有任何终止条件：
            // 服务端状态不前进时会一直重发，整轮卡死且后面任务全不执行
            int loopCount = 0;
            String lastState = null;
            th:
            do {
                if (++loopCount > MAX_PATROL_ROUNDS) {
                    Log.i(TAG, "巡护⚖️循环超过上限，本轮停止");
                    break;
                }
                JSONObject jo = new JSONObject(AntForestRpcCall.queryUserPatrol());
                TimeUtil.sleep(500);
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                JSONObject patrolState = jo.optJSONObject("userPatrol");
                if (patrolState != null) {
                    // 本轮开局状态与上一轮完全相同：上一轮的走步/兑换/换图没让任何字段前进，
                    // 再循环只是原样空转，直接收手
                    String state = patrolState.optInt("patrolId") + "/" + patrolState.optInt("currentNode")
                            + "/" + patrolState.optString("currentStatus") + "/"
                            + patrolState.optJSONObject("chance");
                    if (state.equals(lastState)) {
                        Log.i(TAG, "巡护⚖️状态未推进，本轮停止");
                        break;
                    }
                    lastState = state;
                }
                JSONObject resData = new JSONObject(AntForestRpcCall.queryMyPatrolRecord());
                TimeUtil.sleep(500);
                if (resData.optBoolean("canSwitch")) {
                    JSONArray records = resData.optJSONArray("records");
                    if (records == null) {
                        break;
                    }
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject record = records.optJSONObject(i);
                        if (record == null) {
                            continue;
                        }
                        JSONObject recordPatrol = record.optJSONObject("userPatrol");
                        if (recordPatrol == null) {
                            continue;
                        }
                        if (recordPatrol.getInt("unreachedNodeCount") > 0) {
                            if ("silent".equals(recordPatrol.getString("mode"))) {
                                JSONObject patrolConfig = record.getJSONObject("patrolConfig");
                                String patrolId = patrolConfig.getString("patrolId");
                                resData = new JSONObject(AntForestRpcCall.switchUserPatrol(patrolId));
                                TimeUtil.sleep(500);
                                if (MessageUtil.checkResultCode(TAG, resData)) {
                                    Log.i("巡护⚖️-切换地图至" + patrolId);
                                    continue th;
                                }
                                // 切换失败还 continue 会无限重发同一张图：直接结束本轮巡护
                                Log.i(TAG, "巡护⚖️切换地图失败，本轮停止");
                                break th;
                            }
                            break;
                        }
                    }
                }

                JSONObject homePatrol = jo.getJSONObject("userPatrol");
                int currentNode = homePatrol.getInt("currentNode");
                String currentStatus = homePatrol.getString("currentStatus");
                int patrolId = homePatrol.getInt("patrolId");
                JSONObject chance = homePatrol.getJSONObject("chance");
                int leftChance = chance.getInt("leftChance");
                int leftStep = chance.getInt("leftStep");
                int usedStep = chance.getInt("usedStep");
                if ("STANDING".equals(currentStatus)) {
                    if (leftChance > 0) {
                        jo = new JSONObject(AntForestRpcCall.patrolGo(currentNode, patrolId));
                        TimeUtil.sleep(500);
                        patrolKeepGoing(jo.toString(), currentNode, patrolId);
                        continue;
                    } else if (leftStep >= 2000 && usedStep < 10000) {
                        jo = new JSONObject(AntForestRpcCall.exchangePatrolChance(leftStep));
                        TimeUtil.sleep(300);
                        if (MessageUtil.checkResultCode(TAG, jo)) {
                            int addedChance = jo.optInt("addedChance", 0);
                            Log.forest("步数兑换⚖️[巡护次数*" + addedChance + "]");
                            continue;
                        }
                    }
                } else if ("GOING".equals(currentStatus)) {
                    patrolKeepGoing(null, currentNode, patrolId);
                }
                break;
            } while (true);
        } catch (Throwable t) {
            Log.err(TAG, "queryUserPatrol err:", t);
        }
    }

    private static void patrolKeepGoing(String s, int nodeIndex, int patrolId) {
        try {
            // 服务端持续回 GOING、currentNode 却不推进时，这里原本 100ms 一轮无限重发，
            // 外层 queryUserPatrol 的轮次上限挡不住单次调用内部死循环
            int loopCount = 0;
            int lastNode = Integer.MIN_VALUE;
            int unchanged = 0;
            String current = s;
            do {
                if (++loopCount > MAX_PATROL_STEPS) {
                    Log.i(TAG, "巡护⚖️前行次数超过上限，本轮停止");
                    return;
                }
                if (current == null) {
                    current = AntForestRpcCall.patrolKeepGoing(nodeIndex, patrolId, "image");
                }
                JSONObject jo = new JSONObject(current);
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                JSONArray jaEvents = jo.optJSONArray("events");
                if (jaEvents == null || jaEvents.length() == 0) {
                    return;
                }
                JSONObject userPatrolNow = jo.getJSONObject("userPatrol");
                int currentNode = userPatrolNow.getInt("currentNode");
                // 连续多轮节点不变：巡护没有推进，再发也是同一响应
                if (currentNode == lastNode) {
                    if (++unchanged >= 3) {
                        Log.i(TAG, "巡护⚖️节点连续未前进，本轮停止");
                        return;
                    }
                } else {
                    unchanged = 0;
                    lastNode = currentNode;
                }
                JSONObject events = jaEvents.getJSONObject(0);
                JSONObject rewardInfo = events.optJSONObject("rewardInfo");
                if (rewardInfo != null) {
                    JSONObject animalProp = rewardInfo.optJSONObject("animalProp");
                    if (animalProp != null) {
                        JSONObject animal = animalProp.optJSONObject("animal");
                        if (animal != null) {
                            Log.forest("巡护森林🏇🏻[" + animal.getString("name") + "碎片]");
                        }
                    }
                }
                if (!"GOING".equals(jo.getString("currentStatus"))) {
                    return;
                }
                JSONObject materialInfo = events.getJSONObject("materialInfo");
                String materialType = materialInfo.optString("materialType", "image");
                current = AntForestRpcCall.patrolKeepGoing(currentNode, patrolId, materialType);
                TimeUtil.sleep(100);
            } while (true);
        } catch (Throwable t) {
            Log.err(TAG, "patrolKeepGoing err:", t);
        }
    }

    /* 新版保护地：遍历所有已开放的保护地，逐区域巡护（解决多个保护地并行时的覆盖问题） */
    private static void monopolyPatrol() {
        try {
            JSONObject mapPage = monopolyResponse(AntForestRpcCall.queryMonopolyMapPage());
            JSONArray regionList = mapPage.optJSONArray("regionList");
            // 旧版/未返回区域列表时退化为单区域：仅巡护当前所在保护地
            if (regionList == null || regionList.length() == 0) {
                patrolMonopolyRegion();
                return;
            }
            // 由 regionList 中 currentRegion=true 确定初始所在保护地；切走后该标记会"过期"，
            // 故自行跟踪当前已切到的区域，只要与目标不一致就重新切换，避免原地重复巡护
            String activeRegion = "";
            for (int i = 0; i < regionList.length(); i++) {
                JSONObject r = regionList.optJSONObject(i);
                if (r != null && r.optBoolean("currentRegion", false)) {
                    activeRegion = r.optString("regionCode");
                    break;
                }
            }
            for (int i = 0; i < regionList.length(); i++) {
                JSONObject region = regionList.optJSONObject(i);
                if (region == null) {
                    continue;
                }
                String regionCode = region.optString("regionCode");
                // 中文名在顶层 regionName；displayInfo.regionNameText 为兜底
                String regionName = region.optString("regionName", "");
                if (regionName.isEmpty()) {
                    JSONObject displayInfo = region.optJSONObject("displayInfo");
                    regionName = displayInfo == null ? "" : displayInfo.optString("regionNameText", "");
                }
                if (regionName.isEmpty()) {
                    regionName = regionCode;
                }
                // 仅处理已开放的保护地；PREPARING 表示尚未到开放时间
                if (regionCode.isEmpty() || "PREPARING".equals(region.optString("regionAvailability"))) {
                    continue;
                }
                if (!regionCode.equals(activeRegion)) {
                    JSONObject sr = new JSONObject(AntForestRpcCall.switchMonopolyRegion(regionCode));
                    if (!"SUCCESS".equals(sr.optString("resultCode"))) {
                        Log.record("新版保护地🌲[" + regionName + "]切换失败，跳过该保护地");
                        continue;
                    }
                    activeRegion = regionCode;
                }
                Log.record("新版保护地🌲开始巡护[" + regionName + "]");
                patrolMonopolyRegion();
            }
        } catch (Throwable t) {
            Log.err(TAG, "monopolyPatrol err:", t);
        }
    }

    /* 新版保护地：自动前进（领取首页机会 + 掷骰推进 + 事件确认），作用于当前所在的保护地 */
    private static void patrolMonopolyRegion() {
        try {
            JSONObject state = new JSONObject(AntForestRpcCall.queryMonopolyEntryInfo());
            if (!MessageUtil.checkResultCode(TAG, state)) {
                return;
            }
            JSONObject regionInfo = state.optJSONObject("regionInfo");
            JSONObject mapInfo = state.optJSONObject("mapInfo");
            JSONObject userInfo = state.optJSONObject("userInfo");
            if (regionInfo == null || mapInfo == null || userInfo == null) {
                Log.record("新版保护地🌲入口缺少区域、地图或用户状态，跳过本轮");
                return;
            }
            if (isMonopolyClosed(regionInfo) || isMonopolyClosed(mapInfo)) {
                Log.record("新版保护地🌲当前区域或地图不在开放期，停止本轮");
                return;
            }
            String regionCode = regionInfo.optString("regionCode");
            boolean tasksEnabled = Boolean.TRUE.equals(monopolyTasks.getValue());
            // 掷骰会解锁新任务，所以骰子用完后需要再补跑一次任务
            boolean tasksMayHaveChanged = false;
            if (tasksEnabled) {
                monopolyTask(regionCode);
            }
            if (Boolean.TRUE.equals(monopolyExchangeCert.getValue())) {
                exchangeMonopolyCertificate(regionCode);
            }
            // 首次进入地图时服务端要求带引导参数掷骰
            boolean guideRoll = userInfo.optBoolean("firstEnterMonopoly");
            JSONObject pendingEvent = state.optJSONObject("eventInfo");

            // 首页道具：领取当日巡护机会（骰子）
            JSONObject props = new JSONObject(AntForestRpcCall.triggerMonopolyHomeProps());
            if (!MessageUtil.checkResultCode(TAG, props)) {
                return;
            }
            state = new JSONObject(AntForestRpcCall.queryMonopolyEntryInfo());
            if (!MessageUtil.checkResultCode(TAG, state)) {
                return;
            }
            if (pendingEvent == null) {
                pendingEvent = state.optJSONObject("eventInfo");
            }

            Set<String> confirmedEvents = new HashSet<>();
            while (!Thread.currentThread().isInterrupted()) {
                JSONObject event = pendingEvent;
                if (event != null && event.optBoolean("needConfirm")) {
                    String eventId = event.optString("eventId");
                    String eventType = event.optString("eventType");
                    String actionKey = "CHARITY".equals(eventType) ? "confirm" : ("SPECIAL".equals(eventType) ? "skip" : null);
                    JSONObject displayInfo = event.optJSONObject("displayInfo");
                    if (eventId.isEmpty() || actionKey == null || displayInfo == null
                            || !"SINGLE_ACTION_CONFIRM".equals(displayInfo.optString("flowType"))) {
                        Log.record("新版保护地🌲事件缺少可执行决策，保留当前事件[" + eventId + "]");
                        return;
                    }
                    if (confirmedEvents.contains(eventId)) {
                        Log.record("新版保护地🌲事件[" + eventId + "]已提交确认，等待后续调度刷新");
                        return;
                    }
                    JSONObject confirmation = new JSONObject(AntForestRpcCall.confirmMonopolyEvent(eventId, actionKey));
                    if (!MessageUtil.checkResultCode(TAG, confirmation)) {
                        return;
                    }
                    confirmedEvents.add(eventId);
                    // 事件标题取 displayInfo.titleText，缺省按类型给出中文，避免输出内部事件 ID
                    String eventTitle = displayInfo.optString("titleText", "");
                    if (eventTitle.isEmpty()) {
                        eventTitle = displayInfo.optString("title", "");
                    }
                    if (eventTitle.isEmpty()) {
                        eventTitle = "CHARITY".equals(eventType) ? "公益事件" : ("SPECIAL".equals(eventType) ? "特殊事件" : "事件");
                    }
                    Log.forest("新版保护地🌲" + eventTitle + ("confirm".equals(actionKey) ? "已确认" : "已跳过"));
                    state = new JSONObject(AntForestRpcCall.queryMonopolyEntryInfo());
                    if (!MessageUtil.checkResultCode(TAG, state)) {
                        return;
                    }
                    pendingEvent = state.optJSONObject("eventInfo");
                    continue;
                }

                int diceCount = state.optInt("totalDiceCount", -1);
                if (diceCount < 0) {
                    Log.record("新版保护地🌲响应缺少可用骰子数量，跳过本轮");
                    return;
                }
                if (diceCount == 0) {
                    if (tasksEnabled && tasksMayHaveChanged) {
                        monopolyTask(regionCode);
                        tasksMayHaveChanged = false;
                        state = new JSONObject(AntForestRpcCall.queryMonopolyEntryInfo());
                        if (!MessageUtil.checkResultCode(TAG, state)) {
                            return;
                        }
                        pendingEvent = state.optJSONObject("eventInfo");
                        continue;
                    }
                    Log.record("新版保护地🌲当前无可用骰子，后续调度继续查询");
                    return;
                }

                JSONObject currentUserInfo = state.optJSONObject("userInfo");
                int previousSteps = currentUserInfo == null ? -1 : currentUserInfo.optInt("stepCount", -1);
                JSONObject roll = new JSONObject(AntForestRpcCall.rollMonopolyDice(guideRoll));
                if (!MessageUtil.checkResultCode(TAG, roll)) {
                    return;
                }
                guideRoll = false;
                pendingEvent = roll.optJSONObject("eventInfo");
                JSONObject rollUserInfo = roll.optJSONObject("userInfo");
                int steps = rollUserInfo == null ? -1 : rollUserInfo.optInt("stepCount", -1);
                // 骰子数、步数、事件三者都没变化，说明服务端没受理，留到下次调度重试
                if (roll.optInt("totalDiceCount", -1) == diceCount && steps == previousSteps && pendingEvent == null) {
                    Log.record("新版保护地🌲掷骰后未确认状态变化，保留后续调度");
                    return;
                }
                tasksMayHaveChanged = true;
                Log.forest("新版保护地🌲掷骰[" + roll.optInt("diceNumber") + "]剩余" + roll.optInt("totalDiceCount") + "次");
                TimeUtil.sleep(500);
                state = new JSONObject(AntForestRpcCall.queryMonopolyEntryInfo());
                if (!MessageUtil.checkResultCode(TAG, state)) {
                    return;
                }
                if (pendingEvent == null) {
                    pendingEvent = state.optJSONObject("eventInfo");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "patrolMonopolyRegion err:", t);
        }
    }

    /* 新版保护地：为当前展区兑换解锁证书（消耗 5000g 能量，需开关；每区域每天至多一次） */
    private static void exchangeMonopolyCertificate(String regionCode) {
        try {
            JSONObject mapPage = monopolyResponse(AntForestRpcCall.queryMonopolyMapPage());
            JSONArray mapList = mapPage.optJSONArray("currentRegionMapList");
            if (mapList == null) {
                return;
            }
            String projectId = "";
            String mapCode = regionCode;
            for (int i = 0; i < mapList.length(); i++) {
                JSONObject map = mapList.optJSONObject(i);
                if (map == null) {
                    continue;
                }
                // 仅兑换「当前激活」且证书待领取的展区（currentMap=true）。LV2+ 在解锁前
                // currentMap=false、且多为 LOCKED/PREPARING，避免误兑下一张图的证书
                if (!"PENDING".equals(map.optString("certificateStatus"))
                        || !map.optBoolean("currentMap", false)) {
                    continue;
                }
                // 证书兑换入口（projectId%3D<数字>，URL 编码）可能深嵌套，直接对整条地图项
                // JSON 文本做正则匹配；优先取 mapDisplay.lockedRedirectUrl 字段内的证书链接
                String pid = parseMonopolyProjectId(map.toString());
                if (!pid.isEmpty()) {
                    projectId = pid;
                    mapCode = map.optString("mapCode", regionCode);
                    break;
                }
            }
            if (projectId.isEmpty()) {
                Log.record("新版保护地🌲当前开放展区暂未提供证书兑换入口，跳过");
                return;
            }
            String dayTag = "forest::monopolyCert::" + mapCode;
            if (Status.hasFlagToday(dayTag)) {
                return;
            }
            JSONObject query = monopolyResponse(AntForestRpcCall.queryMonopolyExchangeTree(projectId));
            // 所需能量嵌在 exchangeableTree.energy 内，当前能量 currentEnergy 在顶层
            JSONObject exchangeableTree = query.optJSONObject("exchangeableTree");
            int need = exchangeableTree == null
                    ? query.optInt("energy", 0)
                    : exchangeableTree.optInt("energy", query.optInt("energy", 0));
            int own = query.optInt("currentEnergy", 0);
            if (need <= 0 || own < need) {
                Log.record("新版保护地🌲无法获取证书兑换所需能量(" + own + "/" + need + "g)，跳过");
                return;
            }
            JSONObject exchange = monopolyResponse(AntForestRpcCall.exchangeMonopolyTree(projectId));
            if ("SUCCESS".equals(exchange.optString("resultCode"))) {
                Status.flagToday(dayTag);
                // projectName 在查询响应顶层，兑换响应里可能没有
                String projectName = query.optString("projectName", "").replace("<br/>", "·");
                if (projectName.isEmpty()) {
                    projectName = exchange.optString("projectName", "").replace("<br/>", "·");
                }
                Log.forest("新版保护地🌲🎟️已兑换解锁证书[" + projectName + "]，消耗" + need + "g能量");
            } else {
                Log.record("新版保护地🌲兑换证书失败：" + exchange.optString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.err(TAG, "exchangeMonopolyCertificate err:", t);
        }
    }

    /* 从地图条目 JSON 文本中解析证书兑换的 projectId。链接为 URL 编码（projectId%3D<数字>），
     * 优先从 mapDisplay.lockedRedirectUrl 字段内取（即证书兑换入口），避免误取动物/栖息地等其它 projectId */
    private static String parseMonopolyProjectId(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = Pattern.compile("lockedRedirectUrl\"\\s*:\\s*\"[^\"]*?projectId%3D(\\d+)").matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("redirectUrl\"\\s*:\\s*\"[^\"]*?projectId%3D(\\d+)").matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("projectId%3D(\\d+)").matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("projectId=(\\d+)").matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    /* 新版保护地：区域/地图是否未开放或已结束（缺少日期字段视为一直开放） */
    private static boolean isMonopolyClosed(JSONObject activity) {
        long now = System.currentTimeMillis();
        long start = parseMonopolyDate(activity.optString("startDate"));
        long end = parseMonopolyDate(activity.optString("endDate"));
        return (start > 0 && now < start) || (end > 0 && now >= end);
    }

    /* 解析新版保护地的 "yyyy-MM-dd HH:mm:ss" 时间串（东八区），解析失败返回 0 */
    private static long parseMonopolyDate(String date) {
        if (StringUtil.isEmpty(date)) {
            return 0L;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
            format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            format.setLenient(false);
            Date parsed = format.parse(date);
            return parsed == null ? 0L : parsed.getTime();
        } catch (ParseException e) {
            return 0L;
        }
    }

    /* 新版保护地部分接口的响应会被包在 resData 里 */
    private static JSONObject monopolyResponse(String raw) throws JSONException {
        JSONObject jo = new JSONObject(raw);
        JSONObject resData = jo.optJSONObject("resData");
        return resData == null ? jo : resData;
    }

    /** 当前保护地区域码：由 entryInfo 动态下发，区域轮换无需改代码；取不到返回空串 */
    private static String getMonopolyRegionCode() {
        try {
            JSONObject state = new JSONObject(AntForestRpcCall.queryMonopolyEntryInfo());
            if (!MessageUtil.checkResultCode(TAG, state)) {
                return "";
            }
            JSONObject regionInfo = state.optJSONObject("regionInfo");
            return regionInfo == null ? "" : regionInfo.optString("regionCode");
        } catch (Throwable t) {
            Log.err(TAG, "getMonopolyRegionCode err:", t);
            return "";
        }
    }

    /* 新版保护地：完成可自动执行的任务并领取奖励 */
    private static boolean monopolyTask(String regionCode) {
        // sceneCode 为跨保护地区域固定的任务场景标识；regionCode 由 entryInfo 动态下发(区域轮换无需改代码)。
        // 服务端按 regionCode 返回该区域任务,请求带固定 sceneCode 即可,拿不到任务时按空列表跳过。
        String sceneCode = MONOPOLY_TASK_SCENE_HSDWY;
        boolean changed = false;
        try {
            // 每轮只处理一个任务：任务多的区域 20 轮做不完，剩下的只能等下一轮任务运行才处理，
            // 故上限放到 100 轮；再用任务列表快照兜底，操作后列表连续不变说明服务端没认账
            String lastSnapshot = null;
            int sameSnapshot = 0;
            for (int round = 0; round < MAX_MONOPOLY_TASK_ROUNDS; round++) {
                JSONObject jo = monopolyResponse(AntForestRpcCall.listMonopolyTasks(regionCode, sceneCode));
                if (!MessageUtil.checkSuccess(TAG, jo)) {
                    return changed;
                }
                JSONArray taskInfoList = jo.optJSONArray("taskInfoList");
                if (taskInfoList == null) {
                    Log.record("新版保护地🌲任务列表为空，跳过");
                    return changed;
                }
                boolean acted = false;
                for (int i = 0; i < taskInfoList.length() && !acted; i++) {
                    JSONObject task = taskInfoList.optJSONObject(i);
                    JSONObject base = task == null ? null : task.optJSONObject("taskBaseInfo");
                    // 用任务自带的真实 sceneCode 做完成/领奖(服务端按 regionCode 返回,sceneCode 以任务为准)
                    String taskSceneCode = base == null ? "" : base.optString("sceneCode");
                    if (taskSceneCode.isEmpty()) {
                        continue;
                    }
                    String taskType = base.optString("taskType");
                    if (taskType.isEmpty()) {
                        continue;
                    }
                    String title = monopolyTaskTitle(base, taskType);
                    String status = base.optString("taskStatus");
                    // 黑名单任务静默跳过；已完成待领奖的仍把奖励领掉（与森林任务一致）
                    if (isMonopolyTaskBlacklisted(title) && !"FINISHED".equals(status)) {
                        continue;
                    }
                    if ("FINISHED".equals(status)) {
                        acted = true;
                        changed |= monopolyReceiveTaskAward(regionCode, taskType, taskSceneCode, title);
                    } else if ("TODO".equals(status) && "NORMAL".equals(base.optString("taskMode"))
                            && "VISIT_FLOAT_BALL".equals(base.optString("taskProdPlayType"))) {
                        // 只自动做"浏览浮球"这类纯等待任务，其余需要真实业务动作的任务不碰
                        acted = true;
                        changed |= monopolyFinishTask(base, taskType, taskSceneCode, title);
                    }
                }
                if (!acted) {
                    return changed;
                }
                // 处理过任务但重拉到的列表与上一轮完全相同：完成/领奖没让任何状态变化，
                // 再循环只会对同一任务空转。容忍一轮异步落态，连续两轮相同才收手
                String snapshot = taskInfoList.toString();
                if (snapshot.equals(lastSnapshot)) {
                    if (++sameSnapshot >= 2) {
                        Log.i(TAG, "新版保护地🌲任务状态未推进，本轮停止");
                        return changed;
                    }
                } else {
                    sameSnapshot = 0;
                    lastSnapshot = snapshot;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "monopolyTask err:", t);
        }
        return changed;
    }

    /* 新版保护地任务展示名：bizInfo.title → bizInfo.taskTitle → 任务类型（各接口键名不统一） */
    private static String monopolyTaskTitle(JSONObject base, String taskType) {
        String bizInfo = base.optString("bizInfo");
        if (!bizInfo.isEmpty()) {
            try {
                JSONObject biz = new JSONObject(bizInfo);
                String title = biz.optString("title");
                if (title.isEmpty()) {
                    title = biz.optString("taskTitle");
                }
                if (!title.isEmpty()) {
                    return title;
                }
            } catch (Throwable t) {
                Log.err(TAG, "monopolyTaskTitle err:", t);
            }
        }
        return taskType;
    }

    /* 新版保护地任务是否在配置的黑名单里（键统一剥掉标题末尾的 "(n/N)" 次数后缀） */
    private static boolean isMonopolyTaskBlacklisted(String title) {
        Set<String> blackList = MonopolyTaskList.getValue();
        return blackList != null && blackList.contains(AntForestV2.blackTaskKey(title));
    }

    /* 失败响应按统一判据自动拉黑（可重试错误不会拉黑），受「自动黑名单」开关控制 */
    private static void markMonopolyTaskBlackList(String title, JSONObject jo) {
        if (Boolean.TRUE.equals(AutoMonopolyTaskList.getValue())) {
            MessageUtil.checkResultCodeAndMarkTaskBlackList("MonopolyTaskList", AntForestV2.blackTaskKey(title), jo);
        }
    }

    /* 新版保护地：按服务端下发的时长等待后提交任务完成 */
    private static boolean monopolyFinishTask(JSONObject base, String taskType, String sceneCode, String title) {
        try {
            long seconds = 0L;
            String prodPlayParam = base.optString("prodPlayParam");
            if (!prodPlayParam.isEmpty()) {
                seconds = new JSONObject(prodPlayParam).optLong("timeCount", 0L);
            }
            if (seconds <= 0 || seconds > MONOPOLY_MAX_TASK_SECONDS) {
                Log.record("新版保护地🌲任务[" + title + "]下发时长异常(" + seconds + "s)，跳过");
                return false;
            }
            TimeUtil.sleep(TimeUnit.SECONDS.toMillis(seconds));
            // 交易/履约类：不申报（伪申报会被服务端判风险），直接交自动黑名单
            if (TaskAlternative.isTransactionTask(taskType)) {
                // 底线：交易/支付类一次即永久拉黑，绝不伪造
                Log.forest("新版保护地🌲⏭️交易/履约类[" + title + "]#不申报，已永久拉黑");
                MessageUtil.MarkTaskBlackListPermanent("AntForestV2", "MonopolyTaskList", "新版保护地任务",
                        AntForestV2.blackTaskKey(title));
                return false;
            }
            JSONObject jo = monopolyResponse(AntForestRpcCall.finishMonopolyTask(taskType, sceneCode));
            markMonopolyTaskBlackList(title, jo);
            // 不支持rpc调用（400000040）→ 换另一种实现方案（doFarmTask 伪申报）；
            // 响应不可信，成败仍以任务列表为准（下一轮列表 FINISHED 即去领奖）
            if (TaskAlternative.hit(jo, sceneCode)) {
                // 本家值：保护地任务场景 + ANTFOREST
                TaskAlternative.trigger(null, null, title, taskType, sceneCode,
                        TaskAlternative.DEFAULT_VERSION, sceneCode, "ANTFOREST",
                        "新版保护地🌲", Log::forest);
                return true;
            }
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.forest("新版保护地🌲任务完成[" + title + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "monopolyFinishTask err:", t);
        }
        return false;
    }

    /* 新版保护地：领取任务奖励 */
    private static boolean monopolyReceiveTaskAward(String regionCode, String taskType, String sceneCode, String title) {
        try {
            // 领奖响应没有明确的能量到账量字段，只能用账户余额差值估算真实到账量
            int energyBefore = queryCurrentEnergy();
            JSONObject jo = monopolyResponse(AntForestRpcCall.receiveMonopolyTask(taskType, sceneCode));
            boolean ok = MessageUtil.checkSuccess(TAG, jo);
            // 领奖收口：先按任务列表复核"已领到"，未确认才交自动拉黑（顺序由 TaskAward 固定）
            if (!ok && TaskAward.confirmReceivedOrBlackList("新版保护地🌲任务奖励",
                    k -> probeMonopolyStatus(regionCode, taskType, sceneCode), taskType, title,
                    () -> markMonopolyTaskBlackList(title, jo),
                    msg -> Log.forest(msg))) {
                recordMonopolyAward(title, energyBefore);
                return true;
            }
            if (ok) {
                markMonopolyTaskBlackList(title, jo);
                Log.forest("新版保护地🌲任务奖励[" + title + "]");
                recordMonopolyAward(title, energyBefore);
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "monopolyReceiveTaskAward err:", t);
        }
        return false;
    }

    /**
     * 领取保护地奖励后按账户能量余额差值记账。
     *
     * @param energyBefore 领奖前查询到的能量余额；{@code < 0} 表示查询失败，放弃记账
     */
    private static void recordMonopolyAward(String title, int energyBefore) {
        if (energyBefore < 0) {
            return;
        }
        int energyAfter = queryCurrentEnergy();
        if (energyAfter < 0) {
            return;
        }
        int gained = energyAfter - energyBefore;
        if (gained <= 0) {
            return;
        }
        Log.forest("新版保护地🌲任务奖励[" + title + "]到账[" + gained + "g能量]");
        AntForestV2.addTotalCollected(gained);
        Statistics.addData(Statistics.DataType.COLLECTED, gained);
    }

    /**
     * 查询账户当前能量余额（{@code userBaseInfo.currentEnergy}）。
     *
     * @return 余额；查询失败返回 {@code -1}
     */
    private static int queryCurrentEnergy() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return -1;
            }
            JSONObject userBaseInfo = jo.optJSONObject("userBaseInfo");
            if (userBaseInfo == null || !userBaseInfo.has("currentEnergy")) {
                return -1;
            }
            return userBaseInfo.optInt("currentEnergy", -1);
        } catch (Throwable t) {
            Log.err(TAG, "queryCurrentEnergy err:", t);
            return -1;
        }
    }

    /* 列表状态探针：重拉新版保护地任务列表，按 taskType 匹配该任务当前状态 */
    private static TaskAttemptPolicy.ProbeResult probeMonopolyStatus(String regionCode, String taskType, String sceneCode) {
        try {
            JSONObject jo = monopolyResponse(AntForestRpcCall.listMonopolyTasks(regionCode, sceneCode));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            JSONArray taskInfoList = jo.optJSONArray("taskInfoList");
            if (taskInfoList == null) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            for (int i = 0; i < taskInfoList.length(); i++) {
                JSONObject task = taskInfoList.optJSONObject(i);
                JSONObject base = task == null ? null : task.optJSONObject("taskBaseInfo");
                if (base == null || !taskType.equals(base.optString("taskType"))) {
                    continue;
                }
                String status = base.optString("taskStatus");
                if ("FINISHED".equals(status)) {
                    return TaskAttemptPolicy.ProbeResult.FINISHED;
                }
                if ("RECEIVED".equals(status)) {
                    return TaskAttemptPolicy.ProbeResult.RECEIVED;
                }
                return TaskAttemptPolicy.ProbeResult.TODO;
            }
            // 任务已从列表消失：视为已完成且已领
            return TaskAttemptPolicy.ProbeResult.GONE;
        } catch (Throwable t) {
            Log.err(TAG, "probeMonopolyStatus err:", t);
            return TaskAttemptPolicy.ProbeResult.UNKNOWN;
        }
    }

    /* 新版动物伙伴：领取已产生的派遣能量 */
    private static void collectMonopolyAnimalEnergy() {
        try {
            String uid = UserIdMap.getCurrentUid();
            if (StringUtil.isEmpty(uid)) {
                return;
            }
            JSONObject jo = monopolyResponse(AntForestRpcCall.queryUsingCreatureInfo(uid));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject creature = jo.optJSONObject("userCreatureVO");
            if (creature == null) {
                return;
            }
            JSONObject energy = creature.optJSONObject("robEnergyVO");
            if (energy == null) {
                Log.record("新版动物伙伴🦩缺少能量状态，跳过");
                return;
            }
            if (energy.optBoolean("energyIsCollect")) {
                return;
            }
            if (energy.optInt("yesterdayRobEnergy", 0) <= 0) {
                // 昨日没有产出能量，属于正常情况，不打扰用户
                return;
            }
            String creatureCode = creature.optString("creatureCode");
            String shortDay = energy.optString("yesterdayShortDay");
            if (!energy.has("energyIsCollect") || creatureCode.isEmpty() || shortDay.isEmpty()) {
                Log.record("新版动物伙伴🦩能量缺少领取标识，跳过");
                return;
            }
            JSONObject collect = monopolyResponse(AntForestRpcCall.collectMonopolyCreatureEnergy(creatureCode, shortDay));
            if (!MessageUtil.checkResultCode(TAG, collect)) {
                return;
            }
            int collected = collect.optInt("collectedEnergy", -1);
            if (collected < 0) {
                Log.forest("新版动物伙伴🦩领取成功但缺少实际到账量，不计入统计");
                return;
            }
            if (collected > 0) {
                Statistics.addData(Statistics.DataType.COLLECTED, collected);
            }
            Log.forest("新版动物伙伴🦩[" + creature.optString("creatureName", creatureCode) + "]派遣能量[" + collected + "g]");
        } catch (Throwable t) {
            Log.err(TAG, "collectMonopolyAnimalEnergy err:", t);
        }
    }

    /**
     * 动物派遣：新旧两套入口的候选合并后择优，一次只能派一只。
     * <p>「按最多能量派遣」时两边各取最大值再比大小：新版取 {@code initialRobEnergy}、
     * 旧版取 {@code robEnergyInDaily}，两个字段口径不同，此处只按数值比较。
     * 「按默认顺序派遣」时新版取第一只，空了或派遣失败再回退旧版。
     */
    private static void dispatchAnimal(boolean canConsumeAnimalProp) {
        if (!Boolean.TRUE.equals(monopolyDispatchAnimal.getValue())) {
            return;
        }
        Integer rule = animalDispatchRule.getValue();
        boolean byEnergy = rule != null && rule == AnimalDispatchRule.QUANTITY;
        if (!canConsumeAnimalProp) {
            Log.record("已经有动物伙伴在巡护森林");
            return;
        }
        JSONObject creature = null;
        try {
            JSONObject entry = new JSONObject(AntForestRpcCall.queryMonopolyEntryInfo());
            if (MessageUtil.checkResultCode(TAG, entry)) {
                if (entry.optBoolean("usingMonopolyCreature")) {
                    Log.record("新版动物伙伴🦩已有动物在巡护，保留当前伙伴");
                    return;
                }
                creature = findMonopolyCreature(entry, byEnergy);
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryMonopolyEntryInfo err:", t);
        }
        if (!byEnergy) {
            if (creature != null && assignMonopolyCreature(creature)) {
                return;
            }
            JSONObject prop = findAnimalProp(false);
            if (prop != null) {
                consumeAnimalProp(prop);
            }
            return;
        }
        JSONObject prop = findAnimalProp(true);
        if (creature != null && (prop == null || creature.optInt("initialRobEnergy") >= animalPropEnergy(prop))) {
            if (assignMonopolyCreature(creature)) {
                return;
            }
            consumeAnimalProp(prop);
            return;
        }
        if (prop != null && consumeAnimalProp(prop)) {
            return;
        }
        if (creature != null) {
            assignMonopolyCreature(creature);
        }
    }

    /* 新版动物候选：默认顺序取第一只，按最多能量取 initialRobEnergy 最高（字段缺失时退回第一只） */
    private static JSONObject findMonopolyCreature(JSONObject entry, boolean byEnergy) {
        JSONArray creatureList = entry.optJSONArray("creatureList");
        if (creatureList == null) {
            Log.record("新版动物伙伴🦩入口缺少动物列表，跳过");
            return null;
        }
        List<JSONObject> candidates = new ArrayList<>();
        boolean allHasEnergy = true;
        for (int i = 0; i < creatureList.length(); i++) {
            JSONObject creature = creatureList.optJSONObject(i);
            if (creature == null || !isMonopolyCreatureIdle(creature)) {
                continue;
            }
            candidates.add(creature);
            if (creature.isNull("initialRobEnergy")) {
                allHasEnergy = false;
            }
        }
        if (candidates.isEmpty()) {
            Log.record("新版动物伙伴🦩当前没有可派遣的动物");
            return null;
        }
        if (!byEnergy || !allHasEnergy) {
            return candidates.get(0);
        }
        JSONObject selected = candidates.get(0);
        for (JSONObject candidate : candidates) {
            if (candidate.optInt("initialRobEnergy") > selected.optInt("initialRobEnergy")) {
                selected = candidate;
            }
        }
        return selected;
    }

    /* 派遣指定动物，返回是否派遣成功 */
    private static boolean assignMonopolyCreature(JSONObject creature) {
        try {
            String creatureCode = creature.getString("creatureCode");
            JSONObject jo = new JSONObject(AntForestRpcCall.assignMonopolyCreature(creatureCode));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Log.forest("新版动物伙伴🦩派遣[" + creature.optString("creatureName", creatureCode) + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "assignMonopolyCreature err:", t);
        }
        return false;
    }

    /* 新版动物是否空闲可派遣 */
    private static boolean isMonopolyCreatureIdle(JSONObject creature) {
        if (StringUtil.isEmpty(creature.optString("creatureCode"))) {
            return false;
        }
        if ("using".equals(creature.optString("status")) || creature.optLong("assignTime", 0L) > 0) {
            return false;
        }
        JSONObject energy = creature.optJSONObject("robEnergyVO");
        if (energy == null) {
            return true;
        }
        if (energy.has("robRemainDays") && energy.optInt("robRemainDays") <= 0) {
            return false;
        }
        int maxWorkDays = energy.optInt("maxWorkDays", 0);
        return maxWorkDays <= 0 || energy.optInt("alreadyWorkDays", 0) < maxWorkDays;
    }

    // 旧版动物候选：默认顺序取第一个，按最多能量翻页取 robEnergyInDaily 最高
    private static JSONObject findAnimalProp(boolean byEnergy) {
        try {
            boolean sequence = !byEnergy;
            JSONObject animalProp = null;
            String nextCursor = null;
            for (int page = 0; page < 20; page++) {
                JSONObject jo = new JSONObject(AntForestRpcCall.queryAnimalPropList(nextCursor));
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    break;
                }
                JSONArray animalProps = jo.optJSONArray("animalProps");
                if (animalProps != null) {
                    for (int i = 0; i < animalProps.length(); i++) {
                        JSONObject prop = animalProps.optJSONObject(i);
                        if (prop == null) {
                            continue;
                        }
                        if (animalProp == null) {
                            animalProp = prop;
                        } else if (!sequence && animalPropEnergy(prop) > animalPropEnergy(animalProp)) {
                            animalProp = prop;
                        }
                    }
                }
                if (sequence || !jo.optBoolean("hasMore")) {
                    break;
                }
                String cursor = jo.optString("nextCursor");
                if (StringUtil.isEmpty(cursor) || cursor.equals(nextCursor)) {
                    break;
                }
                nextCursor = cursor;
            }
            return animalProp;
        } catch (Throwable t) {
            Log.err(TAG, "findAnimalProp err:", t);
        }
        return null;
    }

    // 动物道具每日可获能量（"按最多能量派遣"按此最高选择，字段取自 partner.robAbility.robEnergyInDaily）
    private static int animalPropEnergy(JSONObject animalProp) {
        JSONObject partner = animalProp.optJSONObject("partner");
        if (partner == null) {
            return 0;
        }
        JSONObject robAbility = partner.optJSONObject("robAbility");
        return robAbility == null ? 0 : robAbility.optInt("robEnergyInDaily", 0);
    }

    // 派遣伙伴，返回是否派遣成功
    private static boolean consumeAnimalProp(JSONObject animalProp) {
        if (animalProp == null) {
            return false;
        }
        try {
            String propGroup = animalProp.getJSONObject("main").getString("propGroup");
            String propType = animalProp.getJSONObject("main").getString("propType");
            String name = animalProp.getJSONObject("partner").getString("name");
            JSONObject jo = new JSONObject(AntForestRpcCall.consumeProp(propGroup, propType, false));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Log.forest("巡护派遣🐆[" + name + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "consumeAnimalProp err:", t);
        }
        return false;
    }
}
