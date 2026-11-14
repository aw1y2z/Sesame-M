package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.RuntimeInfo;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ChoiceModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ListModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectAndCountModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.StringModelField;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.entity.AlipayAntForestHuntTaskList;
import io.github.aw1y2z.sesame.entity.AlipayUser;
import io.github.aw1y2z.sesame.entity.CustomOption;
import io.github.aw1y2z.sesame.entity.FriendWatch;
import io.github.aw1y2z.sesame.entity.VitalityBenefit;
import io.github.aw1y2z.sesame.entity.AlipayForestHunt;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.model.extensions.ExtensionsHandle;
import io.github.aw1y2z.sesame.model.normal.base.BaseModel;
import io.github.aw1y2z.sesame.rpc.intervallimit.FixedOrRangeIntervalLimit;
import io.github.aw1y2z.sesame.rpc.intervallimit.RpcIntervalLimit;
import io.github.aw1y2z.sesame.ui.ObjReference;
import io.github.aw1y2z.sesame.util.AverageMath;
import io.github.aw1y2z.sesame.util.ListUtil;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;
import lombok.Getter;

/**
 * 蚂蚁森林V2
 */
public class AntForestV2 extends ModelTask {

    private static final String TAG = AntForestV2.class.getSimpleName();

    /**
     * 活力兑换遇「系统繁忙」当日最多重试几次。
     * <p>「系统繁忙」是临时故障（服务端文案为"请稍后再试"），打整日标记会放弃当天剩余可兑额度，
     * 故按次计数、用尽才当日停。
     */

    static final AverageMath offsetTimeMath = new AverageMath(5);

    static final Map<String, Long> usingProps = new ConcurrentHashMap<>();

    final AtomicInteger taskCount = new AtomicInteger(0);

    /** 收取域：首页/好友页查询、能量球收取、蹲点收取、礼盒与回赠 */
    final AntForestEnergy energy = new AntForestEnergy(this);

    /** 浇水：好友浇水、回赠水与次数记账 */
    final AntForestWater water = new AntForestWater(this);

    /** 绿色行动：开通、行动打卡与光盘行动 */
    final AntForestEcoLife eco = new AntForestEcoLife(this);

    /** 道具：连续用卡、背包查询、道具消费与赠送 */
    final AntForestProp prop = new AntForestProp(this);

    String selfId;

    Integer tryCountInt;

    Integer retryIntervalInt;

    Integer advanceTimeInt;

    Integer checkIntervalInt;

    FixedOrRangeIntervalLimit collectIntervalEntity;

    FixedOrRangeIntervalLimit doubleCollectIntervalEntity;

    final AverageMath delayTimeMath = new AverageMath(5);

    final ObjReference<Long> collectEnergyLockLimit = new ObjReference<>(0L);

    private final Object usePropLockObj = new Object();

    BooleanModelField collectEnergy;
    private BooleanModelField expiredEnergy;
    private IntegerModelField advanceTime;
    private IntegerModelField tryCount;
    private IntegerModelField retryInterval;
    private SelectModelField dontCollectList;

    private BooleanModelField teamPriority;
    ChoiceModelField CollectSelfEnergyType;

    IntegerModelField CollectSelfEnergyThreshold;
    IntegerModelField collectRobExpandEnergy;
    BooleanModelField collectRobExpandEnergyEnable;
    BooleanModelField collectWateringBubble;
    BooleanModelField batchRobEnergy;
    BooleanModelField balanceNetworkDelay;
    //PK能量
    private BooleanModelField pkEnergy;

    private BooleanModelField collectProp;
    private StringModelField queryInterval;
    private StringModelField collectInterval;
    private StringModelField doubleCollectInterval;
    ChoiceModelField doubleClickType;
    ListModelField.ListJoinCommaToStringModelField doubleCardTime;
    @Getter
    private IntegerModelField doubleCountLimit;
    IntegerModelField CollectBombEnergyLimit;
    BooleanModelField doubleCardConstant;
    ChoiceModelField helpFriendCollectType;
    SelectModelField helpFriendCollectList;

    IntegerModelField helpFriendCollectListLimit;
    BooleanModelField returnWater;
    IntegerModelField returnWater33;
    IntegerModelField returnWater18;
    IntegerModelField returnWater10;
    ChoiceModelField waterFriendType;
    BooleanModelField waterFriendEnergySendChat;

    private BooleanModelField waterFriendEnergyFirst;
    SelectAndCountModelField waterFriendList;

    private SelectAndCountModelField wateredFriendList;

    private BooleanModelField doubleWaterFriendEnergy;
    private BooleanModelField vitalityExchangeBenefit;
    private SelectAndCountModelField vitality_ExchangeBenefitList;
    BooleanModelField collectGiftBox;
    private BooleanModelField medicalHealth;
    private BooleanModelField greenLife;
    private BooleanModelField marketBrowseTask;

    private BooleanModelField greenRent;
    SelectModelField whoYouWantToGiveTo;
    private BooleanModelField ecoLife;
    private BooleanModelField youthPrivilege;
    SelectModelField ecoLifeOptions;
    private BooleanModelField readForest;

    static int totalCollected = 0;
    static int totalHelpCollected = 0;
    static boolean hasErrorWait = false;

    /** 供拆出的模块（如保护地领奖）累加本轮收取量，与 run() 内的统计共用同一计数。 */
    static void addTotalCollected(int amount) {
        totalCollected += amount;
    }

    /**
     * 黑名单键：剥掉标题末尾的 "(n/N)" 次数后缀。
     * <p>权限类任务在上报时标题会被拼上 "(2/10)"，
     * 而运行时的黑名单检查用的是纯标题；不处理会导致写进黑名单的键永远匹配不上、拉黑失效。
     */
    static String blackTaskKey(String taskTitle) {
        return StringUtil.stripCountSuffix(taskTitle);
    }

    // LIMIT_TIME_ENERGY_DOUBLE_CLICK,CR20230516000363
    // LIMIT_TIME_ENERGY_RAIN_CHANCE,SK20250117005985,VITALITY_ENERGYRAIN_3DAYS，限时3天内使用能量雨次卡
    // 兑换与使用见 AntForestGame#useEnergyRainCard：兑换动作仍由本类的 exchangeBenefit 完成

    @Getter
    Set<String> dontCollectMap = new HashSet<>();

    @Override
    public String getName() {
        return "森林";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.FOREST;
    }

    private BooleanModelField ForestHunt;
    private BooleanModelField AutoAntForestHuntTaskList;
    private SelectModelField AntForestHuntTaskList;
    private BooleanModelField ForestHuntDraw;
    private BooleanModelField ForestHuntHelp;
    private SelectModelField ForestHuntHelpList;

    SelectAndCountModelField continuousUseCardOptions;

    BooleanModelField autoUseShieldCard;
    IntegerModelField continuousUseShieldHour;
    private BooleanModelField NORMALForestHuntHelp;
    private BooleanModelField ACTIVITYForestHuntHelp;

    private BooleanModelField energyPvp;

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(collectEnergy = new BooleanModelField("collectEnergy", "收集能量", false));
        modelFields.addField(teamPriority = new BooleanModelField("teamPriority", "组队收集能量优先", false));
        modelFields.addField(dontCollectList = new SelectModelField("dontCollectList", "不收取能量列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(batchRobEnergy = new BooleanModelField("batchRobEnergy", "一键收取", false));
        modelFields.addField(CollectSelfEnergyType = new ChoiceModelField("CollectSelfEnergyType", "收单个能量球 | " + "方式", CollectSelfType.ALL, CollectSelfType.nickNames));
        modelFields.addField(CollectSelfEnergyThreshold = new IntegerModelField("CollectSelfEnergyThreshold", "收单个能量球阈值(0不限制)", 0, 0, 10000));
        modelFields.addField(pkEnergy = new BooleanModelField("pkEnergy", "Pk榜收取 | 开关", false));
        modelFields.addField(energyPvp = new BooleanModelField("energyPvp", "1V1能量挑战 | 开关", false));
        modelFields.addField(collectWateringBubble = new BooleanModelField("collectWateringBubble", "收取金球", false));
        modelFields.addField(wateredFriendList = new SelectAndCountModelField("wateredFriendList", "统计 | 应被好友浇水", new LinkedHashMap<>(), AlipayUser::getList, "请填写被浇水次数(用于核对金球)", 1, 3));
        modelFields.addField(collectRobExpandEnergyEnable = new BooleanModelField("collectRobExpandEnergyEnable", "倍卡额外能量", true));
        modelFields.addField(collectRobExpandEnergy = new IntegerModelField("collectRobExpandEnergy", "倍卡额外能量(大于该值收取)", 0, 0, 1000000).setDependsOn("collectRobExpandEnergyEnable"));
        modelFields.addField(expiredEnergy = new BooleanModelField("expiredEnergy", "收取过期能量", false));
        modelFields.addField(queryInterval = new StringModelField("queryInterval", "查询间隔(毫秒或毫秒范围)", "500-1000"));
        modelFields.addField(collectInterval = new StringModelField("collectInterval", "收取间隔" + "(毫秒或毫秒范围)", "1000" + "-1500"));
        modelFields.addField(doubleCollectInterval = new StringModelField("doubleCollectInterval", "双击间隔(毫秒或毫秒范围)", "50-150"));
        modelFields.addField(balanceNetworkDelay = new BooleanModelField("balanceNetworkDelay", "平衡网络延迟", true));
        modelFields.addField(advanceTime = new IntegerModelField("advanceTime", "提前时间(毫秒)", 0, 0, 500));
        modelFields.addField(tryCount = new IntegerModelField("tryCount", "尝试收取(次数)", 1, 0, 10));
        modelFields.addField(retryInterval = new IntegerModelField("retryInterval", "重试间隔(毫秒)", 1000, 0, 10000));
        AntForestGame.addGameCenterFields(modelFields);
        modelFields.addField(CollectBombEnergyLimit = new IntegerModelField("CollectBombEnergyLimit", "单个炸弹能量大于该值收取", 0, 0, 100000));
        modelFields.addField(continuousUseCardOptions = new SelectAndCountModelField("continuousUseCardOptions", "连续兑换使用道具卡片", new LinkedHashMap<>(), CustomOption::getContinuousUseCardOptions, "请填写使用次数(每日,0不限)"));
        modelFields.addField(autoUseShieldCard = new BooleanModelField("autoUseShieldCard", "自动续用保护罩", false));
        modelFields.addField(continuousUseShieldHour = new IntegerModelField("continuousUseShieldHour", "自动续用保护罩(小时)", 24, 1, 168).setDependsOn("autoUseShieldCard"));
        //modelFields.addField(doubleClickType = new ChoiceModelField("doubleClickType", "双击卡 | " + "自动使用", UsePropType.CLOSE, UsePropType.nickNames));
        //modelFields.addField(doubleCountLimit = new IntegerModelField("doubleCountLimit", "双击卡 | " + "使用次数", 6));
        //modelFields.addField(doubleCardTime = new ListModelField.ListJoinCommaToStringModelField("doubleCardTime", "双击卡 | 使用时间(范围)", ListUtil.newArrayList("0700" + "-0730")));
        //modelFields.addField(doubleCardConstant = new BooleanModelField("DoubleCardConstant", "双击卡 | 限时双击永动机", false));
        modelFields.addField(returnWater = new BooleanModelField("returnWater", "返水 | 开启", false));
        modelFields.addField(returnWater10 = new IntegerModelField("returnWater10", "返水 | 10克需收能量(0不限)", 10).setDependsOn("returnWater"));
        modelFields.addField(returnWater18 = new IntegerModelField("returnWater18", "返水 | 18克需收能量(0不限)", 18).setDependsOn("returnWater"));
        modelFields.addField(returnWater33 = new IntegerModelField("returnWater33", "返水 | 33克需收能量(0不限)", 33).setDependsOn("returnWater"));
        modelFields.addField(waterFriendType = new ChoiceModelField("waterFriendType", "浇水 | 动作", WaterFriendType.WATER_00, WaterFriendType.nickNames));
        modelFields.addField(waterFriendList = new SelectAndCountModelField("waterFriendList", "浇水 | 好友列表", new LinkedHashMap<>(), AlipayUser::getList, "请填写浇水次数(每日)", 1, 3));
        modelFields.addField(waterFriendEnergySendChat = new BooleanModelField("waterFriendEnergySendChat", "浇水 | 发送已浇水提醒", false));
        modelFields.addField(waterFriendEnergyFirst = new BooleanModelField("waterFriendEnergyFirst", "浇水 | 每次首先执行", false));
        modelFields.addField(doubleWaterFriendEnergy = new BooleanModelField("doubleWaterFriendEnergy", "浇水 | 强制检查重复一次", false));
        modelFields.addField(helpFriendCollectType = new ChoiceModelField("helpFriendCollectType", "复活能量 | 动作", HelpFriendCollectType.NONE, HelpFriendCollectType.nickNames));
        modelFields.addField(helpFriendCollectList = new SelectModelField("helpFriendCollectList", "复活能量 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList).setDependsOn("helpFriendCollectType"));
        modelFields.addField(helpFriendCollectListLimit = new IntegerModelField("helpFriendCollectListLimit", "复活好友能量下限(大于该值复活,0不限制)", 0, 0, 100000).setDependsOn("helpFriendCollectType"));
        modelFields.addField(vitalityExchangeBenefit = new BooleanModelField("vitalityExchangeBenefit", "活力值 | 兑换权益", false));
        modelFields.addField(vitality_ExchangeBenefitList = new SelectAndCountModelField("vitality_ExchangeBenefitList", "活力值 | 权益列表", new LinkedHashMap<>(), VitalityBenefit::getList, "请填写兑换次数(每日)", 1, 100).setDependsOn("vitalityExchangeBenefit"));
        AntForestGame.addWhackMoleFields(modelFields);
        modelFields.addField(collectProp = new BooleanModelField("collectProp", "收集道具", false));
        modelFields.addField(whoYouWantToGiveTo = new SelectModelField("whoYouWantToGiveTo", "赠送道具好友列表", new LinkedHashSet<>(), AlipayUser::getList, "会赠送所有可送道具都给已选择的好友"));
        AntForestGame.addEnergyRainFields(modelFields);
        AntForestPatrol.addFields(modelFields);
        AntForestTask.addFields(modelFields);
        modelFields.addField(collectGiftBox = new BooleanModelField("collectGiftBox", "领取礼盒", false));
        modelFields.addField(medicalHealth = new BooleanModelField("medicalHealth", "医疗健康", false));
        modelFields.addField(greenLife = new BooleanModelField("greenLife", "森林集市", false));
        modelFields.addField(marketBrowseTask = new BooleanModelField("marketBrowseTask", "绿植浏览", false));
        modelFields.addField(greenRent = new BooleanModelField("greenRent", "绿色租赁", false));
        modelFields.addField(readForest = new BooleanModelField("readForest", "无纸阅读", false));
        modelFields.addField(youthPrivilege = new BooleanModelField("youthPrivilege", "青春特权 | 森林道具", false));
        modelFields.addField(ecoLife = new BooleanModelField("ecoLife", "绿色行动 | 开启", false));
        modelFields.addField(ecoLifeOptions = new SelectModelField("ecoLifeOptions", "绿色行动 | 选项", new LinkedHashSet<>(), CustomOption::getEcoLifeOptions, "光盘行动需要先手动完成一次").setDependsOn("ecoLife"));
        AntForestTeam.addFields(modelFields);
        modelFields.addField(ForestHunt = new BooleanModelField("ForestHunt", "森林寻宝", false));
        modelFields.addField(AutoAntForestHuntTaskList = new BooleanModelField("AutoAntForestHuntTaskList", "抽抽乐任务 | 自动黑名单", true).setDependsOn("ForestHunt"));
        modelFields.addField(AntForestHuntTaskList = new SelectModelField("AntForestHuntTaskList", "抽抽乐任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntForestHuntTaskList::getList).setDependsOn("AutoAntForestHuntTaskList"));
        modelFields.addField(ForestHuntDraw = new BooleanModelField("ForestHuntDraw", "森林寻宝抽奖", false).setDependsOn("ForestHunt"));
        modelFields.addField(ForestHuntHelp = new BooleanModelField("ForestHuntHelp", "森林寻宝助力", false).setDependsOn("ForestHunt"));
        modelFields.addField(NORMALForestHuntHelp = new BooleanModelField("NORMALForestHuntHelp", "普通场景强制助力" + "(助力任务不在列表中时使用，如果日志显示失效请关闭)", false).setDependsOn("ForestHunt"));
        modelFields.addField(ACTIVITYForestHuntHelp = new BooleanModelField("ACTIVITYForestHuntHelp", "活动场景强制助力" + "(同上)", false).setDependsOn("ForestHunt"));
        modelFields.addField(ForestHuntHelpList = new SelectModelField("ForestHuntHelpList", "点击配置寻宝助力列表" + "(填写shareId中开头的22-24位字符在\"4O7FEYDgn\"前的)", new LinkedHashSet<>(), AlipayForestHunt::getList).setDependsOn("ForestHunt"));
        AntForestDress.addFields(modelFields);
        return modelFields;
    }

    @Override
    public Boolean check() {
        if (RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime) > System.currentTimeMillis()) {
            Log.record("异常等待中，暂不执行检测！");
            return false;
        }
        return true;
    }

    @Override
    public Boolean isSync() {
        return true;
    }

    @Override
    public void boot(ClassLoader classLoader) {
        super.boot(classLoader);
        // 两个接口各自持有独立的限流实例：IntervalLimit 的锁与时间戳都在实例上，
        // 共用会让好友家查询与首页查询互相抢同一个节流窗口，逐个好友收取时成倍拉长整轮耗时
        RpcIntervalLimit.addIntervalLimit("alipay.antforest.forest.h5.queryHomePage",
                new FixedOrRangeIntervalLimit(queryInterval.getValue(), 10, 10000));
        RpcIntervalLimit.addIntervalLimit("alipay.antforest.forest.h5.queryFriendHomePage",
                new FixedOrRangeIntervalLimit(queryInterval.getValue(), 10, 10000));
        RpcIntervalLimit.addIntervalLimit("alipay.antmember.forest.h5.collectEnergy", 0);
        RpcIntervalLimit.addIntervalLimit("alipay.antmember.forest.h5.queryEnergyRanking", 100);
        RpcIntervalLimit.addIntervalLimit("alipay.antforest.forest.h5.fillUserRobFlag", 500);
        tryCountInt = tryCount.getValue();
        retryIntervalInt = retryInterval.getValue();
        advanceTimeInt = advanceTime.getValue();
        checkIntervalInt = BaseModel.getCheckInterval().getValue();
        dontCollectMap = dontCollectList.getValue();
        collectIntervalEntity = new FixedOrRangeIntervalLimit(collectInterval.getValue(), 50, 10000);
        doubleCollectIntervalEntity = new FixedOrRangeIntervalLimit(doubleCollectInterval.getValue(), 10, 5000);
        delayTimeMath.clear();
        AntForestRpcCall.init();
    }

    @Override
    public void run() {
        // 组队优先：本次运行是否由本方法切到组队模式（结束时需切回个人）
        boolean prioritySwitchedToTeam = false;
        try {
            taskCount.set(0);
            selfId = UserIdMap.getCurrentUid();
            hasErrorWait = false;

            // 组队合种浇水异常中断后，把账号从组队模式恢复回个人模式
            AntForestTeam.fixTeamModeIfNeeded(teamPriority.getValue());

            // 组队优先：主动切到组队模式，让 queryHomePage 返回组队主页（个人能量在 mainMember 里）
            if (teamPriority.getValue()) {
                if (AntForestTeam.updateUserConfiginTeam(true)) {
                    prioritySwitchedToTeam = true;
                    Status.flagToday(AntForestTeam.FLAG_TEAM_MODE_SWITCHED);
                    Log.record("组队优先：已切换到组队模式");
                } else {
                    // 账号不在任何队伍 / 切换失败：降级为个人模式（原行为）
                    Log.record("组队优先：切换到组队模式失败（可能不在任何队伍），按个人模式继续");
                }
            }

            // 只收能量时段：森林只做收能量（自己+好友）与能量雨，其余森林任务全部跳过；
            // 组队切换、倍卡/校准等收能量必需的前置与收尾仍然执行。
            // 庄园/农场等其它模组此时整体暂停（各模块 check() 里由 IS_ENERGY_TIME 拦截）。
            boolean energyOnly = TaskCommon.IS_ENERGY_TIME;

            if (!energyOnly && waterFriendEnergyFirst.getValue()) {
                water.waterFriendEnergy();
            }

            // 能量雨卡（前置额度，开关在模块内部判断；兑换能力由本类提供）
            AntForestGame.useEnergyRainCard(AntForestMall::exchangeBenefit);

            // 收集能量雨（开关在模块内部判断）
            AntForestGame.runEnergyRain();

            if (!energyOnly) {
                if (ecoLife.getValue()) {
                    eco.ecoLife();
                }

                if (youthPrivilege.getValue()) {
                    // 青春特权道具（大学生双击卡/能量罩/加速器）与「森林任务」共用同一套实现，
                    // 由 queryTaskList 内部按 vitalityTask::<firstTaskType> 逐任务标记
                    AntForestTask.queryVitalityYouthTaskList();
                }
                //连续兑换使用道具卡片
                prop.continuousUseCardOptions();

                if (AntForestTask.isForestSignEnabled()) {
                    AntForestTask.vantiepSign();
                }
            }

            JSONObject selfHomeObject = energy.collectSelfEnergy();
            try {
                JSONObject friendsObject = new JSONObject(AntForestRpcCall.queryEnergyRanking());
                if (MessageUtil.checkResultCode(TAG, friendsObject)) {
                    energy.collectFriendsEnergy(friendsObject, "ordinary");
                    int pos = 20;
                    List<String> idList = new ArrayList<>();
                    JSONArray totalDatas = friendsObject.getJSONArray("totalDatas");
                    while (pos < totalDatas.length()) {
                        JSONObject friend = totalDatas.getJSONObject(pos);
                        idList.add(friend.getString("userId"));
                        pos++;
                        if (pos % 20 == 0) {
                            energy.collectFriendsEnergy(idList, "ordinary");
                            idList.clear();
                        }
                    }
                    if (!idList.isEmpty()) {
                        energy.collectFriendsEnergy(idList, "ordinary");
                    }
                }
                selfHomeObject = energy.collectSelfEnergy();
            } catch (Throwable t) {
                Log.err(TAG, "queryEnergyRanking err:", t);
            }

            if (!TaskCommon.IS_ENERGY_TIME && selfHomeObject != null) {
                String whackMoleStatus = selfHomeObject.optString("whackMoleStatus");
                if (Objects.equals("CAN_PLAY", whackMoleStatus) || Objects.equals("CAN_INITIATIVE_PLAY", whackMoleStatus) || Objects.equals("NEED_MORE_FRIENDS", whackMoleStatus)) {
                    AntForestGame.checkAndHandleWhackMole();
                }
                boolean hasMore = false;
                do {
                    if (hasMore) {
                        hasMore = false;
                        selfHomeObject = energy.querySelfHome();
                    }
                    if (collectWateringBubble.getValue()) {
                        JSONArray wateringBubbles = selfHomeObject.has("wateringBubbles") ? selfHomeObject.getJSONArray("wateringBubbles") : new JSONArray();
                        if (wateringBubbles.length() > 0) {
                            for (int i = 0; i < wateringBubbles.length(); i++) {
                                // 每颗金球单独计数：声明在循环外会复用上一次的值重复计入
                                int collected = 0;
                                JSONObject wateringBubble = wateringBubbles.getJSONObject(i);
                                String bizType = wateringBubble.getString("bizType");
                                String friendShowName = UserIdMap.getShowName(wateringBubble.getString("userId"));
                                switch (bizType) {
                                    case "jiaoshui": {
                                        JSONObject joEnergy = new JSONObject(AntForestRpcCall.collectEnergy(bizType, selfId, wateringBubble.getLong("id")));
                                        if (MessageUtil.checkResultCode("收取[我]的浇水金球", joEnergy)) {
                                            JSONArray bubbles = joEnergy.getJSONArray("bubbles");
                                            for (int j = 0; j < bubbles.length(); j++) {
                                                collected += bubbles.getJSONObject(j).getInt("collectedEnergy");
                                            }

                                            if (collected > 0) {
                                                //记录被浇水次数
                                                Status.wateredFriendToday(wateringBubble.getString("userId"));
                                                Statistics.addData(Statistics.DataType.WATEREDCOUNT, 1);
                                                String msg = "收取金球🍯[" + friendShowName + "]的浇水[" + collected + "g]";
                                                Log.forest(msg + "");
                                                Toast.show(msg);
                                                totalCollected += collected;
                                                Statistics.addData(Statistics.DataType.COLLECTED, collected);
                                            } else {
                                                Log.forest("收取[我]的浇水金球失败");
                                            }
                                        }
                                        break;
                                    }
                                    case "fuhuo": {
                                        JSONObject joEnergy = new JSONObject(AntForestRpcCall.collectRebornEnergy());
                                        if (MessageUtil.checkResultCode("收取[我]的复活金球", joEnergy)) {
                                            collected = joEnergy.getInt("energy");
                                            String msg = "收取金球🍯复活[" + collected + "g]";
                                            Log.forest(msg + "");
                                            Toast.show(msg);
                                            totalCollected += collected;
                                            Statistics.addData(Statistics.DataType.COLLECTED, collected);
                                        }
                                        break;
                                    }
                                    case "baohuhuizeng": {
                                        JSONObject joEnergy = new JSONObject(AntForestRpcCall.collectEnergy(bizType, selfId, wateringBubble.getLong("id")));
                                        if (MessageUtil.checkResultCodeString("收取[" + friendShowName + "]的复活回赠金球", joEnergy)) {
                                            JSONArray bubbles = joEnergy.getJSONArray("bubbles");
                                            for (int j = 0; j < bubbles.length(); j++) {
                                                collected += bubbles.getJSONObject(j).getInt("collectedEnergy");
                                            }
                                            if (collected > 0) {
                                                String msg = "收取金球🍯[" + friendShowName + "]复活回赠[" + collected + "g]";
                                                Log.forest(msg + "");
                                                Toast.show(msg);
                                                totalCollected += collected;
                                                Statistics.addData(Statistics.DataType.COLLECTED, collected);
                                            } else {
                                                Log.forest("收取[" + friendShowName + "]的复活回赠金球失败");
                                            }
                                        }
                                        break;
                                    }
                                }
                                TimeUtil.sleep(1000L);
                            }
                            if (wateringBubbles.length() >= 20) {
                                hasMore = true;
                            }
                        }
                    }
                    if (collectProp.getValue()) {
                        JSONArray givenProps = selfHomeObject.has("givenProps") ? selfHomeObject.getJSONArray("givenProps") : new JSONArray();
                        if (givenProps.length() > 0) {
                            for (int i = 0; i < givenProps.length(); i++) {
                                JSONObject jo = givenProps.getJSONObject(i);
                                String giveConfigId = jo.getString("giveConfigId");
                                String giveId = jo.getString("giveId");
                                String propName = jo.getJSONObject("propConfig").getString("propName");
                                jo = new JSONObject(AntForestRpcCall.collectProp(giveConfigId, giveId));
                                if (MessageUtil.checkSuccess(TAG, jo)) {
                                    Log.forest("领取道具🎭[" + propName + "]");
                                }
                                TimeUtil.sleep(1000L);
                            }
                            if (givenProps.length() >= 20) {
                                hasMore = true;
                            }
                        }
                    }
                } while (hasMore);
                JSONArray usingUserProps;
                if (selfHomeObject.has("usingUserPropsNew")) {
                    usingUserProps = selfHomeObject.getJSONArray("usingUserPropsNew");
                } else {
                    usingUserProps = selfHomeObject.has("usingUserProps") ? selfHomeObject.getJSONArray("usingUserProps") : new JSONArray();
                }
                boolean canConsumeAnimalProp = true;
                if (usingUserProps.length() > 0) {
                    for (int i = 0; i < usingUserProps.length(); i++) {
                        JSONObject jo = usingUserProps.getJSONObject(i);
                        if (!Objects.equals("animal", jo.optString("propGroup"))) {
                            continue;
                        } else {
                            canConsumeAnimalProp = false;
                        }
                        JSONObject extInfo = new JSONObject(jo.getString("extInfo"));
                        int energy = extInfo.optInt("energy", 0);
                        if (energy > 0 && !extInfo.optBoolean("isCollected")) {
                            String propId = jo.getString("propId");
                            String propType = jo.getString("propType");
                            String shortDay = extInfo.getString("shortDay");
                            String animalName = extInfo.getJSONObject("animal").getString("name");
                            jo = new JSONObject(AntForestRpcCall.collectAnimalRobEnergy(propId, propType, shortDay));
                            if (MessageUtil.checkResultCode(TAG, jo)) {
                                Log.forest("动物能量🦩派遣" + animalName + "收取能量[" + energy + "g]");
                                Statistics.addData(Statistics.DataType.COLLECTED, energy);
                            }
                            TimeUtil.sleep(500);
                            break;
                        }
                    }
                }
                //强制重复浇水一次
                if (doubleWaterFriendEnergy.getValue()) {
                    if (!Status.hasFlagToday("Forest::doubleWaterFriendEnergy")) {
                        water.doubleWaterFriendEnergy();
                    }
                }

                water.waterFriendEnergy();

                if (pkEnergy.getValue()) {
                    energy.collectPkEnergy();
                }

                //初始任务列表
                if (!Status.hasFlagToday("BlackList::initAntForest")) {
                    AntForestTask.initTaskBlackList(AntForestTask.isAutoVitalityBlackListEnabled(), AutoAntForestHuntTaskList.getValue(), AntForestTask.isVitalityTaskEnabled(), ForestHunt.getValue());
                    Status.flagToday("BlackList::initAntForest");
                }
                // 新版保护地任务名单来自服务端下发：每轮同步一次，保证黑名单候选项与当前区域任务一致
                if (AntForestPatrol.isTaskListEnabled()) {
                    AntForestPatrol.syncTaskList();
                }

                // 组队合种浇水 + 真爱合种浇水
                AntForestTeam.run(teamPriority.getValue());

                // 森林寻宝
                if (ForestHunt.getValue()) {
                    ForestChouChouLe forestChouChouLe = new ForestChouChouLe();
                    forestChouChouLe.chouChouLe(ForestHuntDraw.getValue(), ForestHuntHelp.getValue(), ForestHuntHelpList.getValue(), NORMALForestHuntHelp.getValue(), ACTIVITYForestHuntHelp.getValue(), AntForestHuntTaskList.getValue());
                }

                // 巡护 / 新版保护地 / 动物伙伴
                AntForestPatrol.run(canConsumeAnimalProp);
                if (expiredEnergy.getValue()) {
                    popupTask();
                }

                if (AntForestTask.isForest7DaySignEnabled()) {
                    AntForestTask.queryCommonSign();
                }
                if (AntForestTask.isVitalityTaskEnabled()) {
                    AntForestTask.queryTaskList(water::canWaterFriend, water::waterFriendEnergy);
                }

                prop.giveProp();
                if (vitalityExchangeBenefit.getValue()) {
                    vitalityExchangeBenefit();
                }
                /* 森林集市 */
                if (greenLife.getValue()) {
                    AntForestGreenLife.greenLife();
                }

                // 绿植浏览（独立任务，与森林集市无关）
                if (marketBrowseTask.getValue()) {
                    GreenPlantBrowse.run();
                }

                // 绿色租赁
                if (greenRent.getValue()) {
                    if (!Status.hasFlagToday("Forest::greenRent")) {
                        AntForestGreenLife.greenRent();
                        Status.flagToday("Forest::greenRent");
                    }
                }

                if (medicalHealth.getValue()) {
                    // 医疗健康 绿色医疗 16g*6能量
                    energy.queryForestEnergy("FEEDS");
                    // 医疗健康 电子小票 4g*10能量
                    energy.queryForestEnergy("BILL");
                }

                // 无纸阅读: 读免费书攒绿色能量
                if (readForest.getValue()) {
                    ReadForestTask.execute();
                }

                if (AntForestDress.isEnabled()) {
                    AntForestDress.run();
                }

                AntForestGame.checkAndHandleWhackMole();

                //森林乐园（开关在模块内部判断）
                AntForestGame.runGameCenter(AntForestTask.getVitalityTaskBlackList());

                //1V1能量挑战
                if (energyPvp.getValue()) {
                    if (AntForestPvp.updateUserConfigEnergyPvp()) {
                        AntForestPvp.queryPvpHomeInfo();
                        AntForestPvp.receivePvpRewards();
                    }

                }
                energy.forestEnergyInfo();
            }
        } catch (Throwable t) {
            Log.err(TAG, "AntForestV2.run err:", t);
        } finally {
            try {
                synchronized (AntForestV2.this) {
                    int count = taskCount.get();
                    if (count > 0) {
                        AntForestV2.this.wait(TimeUnit.MINUTES.toMillis(30));
                        count = taskCount.get();
                    }
                    if (count > 0) {
                        Log.record("执行超时-蚂蚁森林");
                    } else if (count != 0) {
                        Log.record("执行完成-蚂蚁森林");
                    }
                }
            } catch (InterruptedException ie) {
                Log.i(TAG, "执行中断-蚂蚁森林");
            }
            // 倍卡与校准都放在异步子任务跑完之后（好友能量是异步收取的，先查只能看到旧值）：
            // 先收倍卡，再由校准按服务端明细覆盖今日收取——顺序反了校准就统计不到这笔
            energy.collectRobExpandEnergy();
            syncTodayCollectedFromServer();
            Statistics.save();
            FriendWatch.save();
            // 组队优先：本轮结束切回个人模式（放在倍卡/校准之后，避免它们落到个人版）
            if (prioritySwitchedToTeam) {
                if (AntForestTeam.updateUserConfiginTeam(false)) {
                    Status.clearFlag(AntForestTeam.FLAG_TEAM_MODE_SWITCHED);
                    Log.record("组队优先：已切回个人模式");
                } else {
                    Log.record("组队优先：切回个人模式失败，下次运行重试");
                }
            }
        }
    }

    /** 每页条数：200 条/页；普通账号当日明细 1~2 页即可翻完，翻页在 reachedOlder 处提前结束。 */
    private static final int ENERGY_RECORD_PAGE_SIZE = 200;

    /** 明细翻页上限：防服务端异常恒回 hasMore 时死循环；好友多的账号单日明细可超旧上限，故放宽到 100 页（=20000 条）以覆盖整天记录。 */
    private static final int ENERGY_RECORD_MAX_PAGES = 100;

    /**
     * 用服务端能量明细**覆盖**今日收取能量，取代逐点累加的口径。
     * <p>只统计**今日 0 点之后**的记录：{@code nextId} 是时间戳游标，从 0 开始倒序翻页，
     * 一旦遇到早于今日起点的记录即可停止（更早的必定更旧）。
     * <p>拉取失败或解析不到记录时保留原值——覆盖是"定值"，不该把已有统计冲成 0。
     */
    private void syncTodayCollectedFromServer() {
        try {
            Calendar todayStart = Calendar.getInstance();
            todayStart.set(Calendar.HOUR_OF_DAY, 0);
            todayStart.set(Calendar.MINUTE, 0);
            todayStart.set(Calendar.SECOND, 0);
            todayStart.set(Calendar.MILLISECOND, 0);
            long todayStartMillis = todayStart.getTimeInMillis();

            long nextId = 0;
            int todayTotal = 0;
            int scanned = 0;
            boolean reachedOlder = false;
            for (int page = 0; page < ENERGY_RECORD_MAX_PAGES; page++) {
                JSONObject jo = new JSONObject(AntForestRpcCall.pagingQueryEnergyRecord(nextId, ENERGY_RECORD_PAGE_SIZE));
                // 响应层级随调用通道而异：内置抓包里明细挂在 resData 下，而模块 requestString
                // 拿到的是解析后的业务体（recordVOList 直接平铺在顶层）。两处都取，避免漏解析。
                JSONObject resData = jo.optJSONObject("resData");
                if (resData == null) {
                    resData = jo;
                }
                JSONArray records = resData.optJSONArray("recordVOList");
                if (records == null) {
                    // 拉取失败：宁可不更新（保留旧值），也不要把今日统计冲成 0
                    Log.record("能量明细⚠️无明细字段[" + TaskAlternative.describe(jo) + "]#今日统计保持原值");
                    return;
                }
                if (records == null || records.length() == 0) {
                    break;
                }
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.optJSONObject(i);
                    if (record == null) {
                        continue;
                    }
                    long bizDate = record.optLong("bizDate", 0);
                    if (bizDate < todayStartMillis) {
                        // 已翻过今日边界：更早的记录不必再看
                        reachedOlder = true;
                        break;
                    }
                    int e = record.optInt("energy", 0);
                    // 只累加**进账**：明细混有支出（合种浇水等为负值），净额求和会与客户端
                    // 「今日获得能量」口径不一致——那是只计收入的。
                    if (e <= 0) {
                        continue;
                    }
                    scanned++;
                    todayTotal += e;
                }
                if (reachedOlder || !resData.optBoolean("hasMore", false)) {
                    break;
                }
                long next = resData.optLong("nextId", 0);
                if (next <= 0 || next == nextId) {
                    // 游标未推进：再翻会重复计数，直接收手
                    break;
                }
                nextId = next;
                TimeUtil.sleep(300);
            }

            // 一条都没扫到（接口改版/字段变动）时不能覆盖：否则会把已有统计冲成 0
            if (scanned == 0) {
                Log.record("能量明细⚠️未解析到任何记录#今日统计保持原值");
                return;
            }
            int delta = Statistics.setDayCollectedFromServer(todayTotal);
            if (delta != 0) {
                Statistics.save();
                Log.forest("今日能量⚡️[按服务端明细校准]#今日" + todayTotal + "g"
                        + (delta > 0 ? "#补记" + delta : "#修正" + delta) + "g");
            }
        } catch (Throwable t) {
            Log.err(TAG, "syncTodayCollectedFromServer err:", t);
        }
    }

    private void popupTask() {
        try {
            JSONObject resData = new JSONObject(AntForestRpcCall.popupTask());
            if (!MessageUtil.checkResultCode(TAG, resData)) {
                return;
            }
            JSONArray forestSignVOList = resData.optJSONArray("forestSignVOList");
            if (forestSignVOList != null) {
                for (int i = 0; i < forestSignVOList.length(); i++) {
                    JSONObject forestSignVO = forestSignVOList.getJSONObject(i);
                    String signId = forestSignVO.getString("signId");
                    String currentSignKey = forestSignVO.getString("currentSignKey");
                    JSONArray signRecords = forestSignVO.getJSONArray("signRecords");
                    for (int j = 0; j < signRecords.length(); j++) {
                        JSONObject signRecord = signRecords.getJSONObject(j);
                        String signKey = signRecord.getString("signKey");
                        if (signKey.equals(currentSignKey)) {
                            if (!signRecord.getBoolean("signed")) {
                                // 参数顺序：entityId, userId, sceneCode（后两个易传反）
                                JSONObject resData2 = new JSONObject(AntForestRpcCall.antiepSign(signId, UserIdMap.getCurrentUid(), "ANTFOREST_ENERGY_SIGN"));
                                if (MessageUtil.checkSuccess(TAG, resData2)) {
                                    int expiredEnergy = signRecord.getInt("awardCount");
                                    Log.forest("过期能量💊[" + expiredEnergy + "g]");
                                    Statistics.addData(Statistics.DataType.COLLECTED, expiredEnergy);
                                }
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "popupTask err:", t);
        }
    }

    void forestExtensions() {
        try {
            ExtensionsHandle.handleAlphaRequest("antForest", "extensions", usingProps);
        } catch (Throwable t) {
            Log.err(TAG, "forestExtensions err:", t);
        }
    }

    private void vitalityExchangeBenefit() {
        try {
            // 清单当日拉一次即可：单轮最多 5 个分类共 20 多次请求，且它同时是配置页兑换选项的来源，
            // 不能完全不拉；当日标记与重试逻辑都在 AntForestMall.getAllSkuInfo() 内部
            AntForestMall.getAllSkuInfo();
            Map<String, Integer> exchangeList = vitality_ExchangeBenefitList.getValue();
            for (Map.Entry<String, Integer> entry : exchangeList.entrySet()) {
                String skuId = entry.getKey();
                Integer count = entry.getValue();
                if (count == null || count < 0) {
                    continue;
                }
                // 失效 skuId（权益下架/换号/服务端改 ID）当日已判过就跳过，避免每轮刷屏并拖累后续任务
                if (Status.hasFlagToday("forest::exchangeSkuMissing::" + skuId)) {
                    continue;
                }
                // 每日次数之外再加硬循环上限：即便出现意外也不会空转
                int loopCount = 0;
                while (loopCount++ < count
                        && Status.canVitalityExchangeBenefitToday(skuId, count)
                        && AntForestMall.exchangeBenefit(skuId)) {
                    TimeUtil.sleep(3000);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "vitalityExchangeBenefit err:", t);
        }
    }

    public enum CollectStatus {
        AVAILABLE, WAITING, INSUFFICIENT, ROBBED
    }

    public enum ItemStatus {
        NO_ENOUGH_POINT, NO_ENOUGH_STOCK, REACH_LIMIT, SECKILL_NOT_BEGIN, SECKILL_HAS_END, HAS_NEVER_EXPIRE_DRESS;

        public static final String[] nickNames = {"活力值不足", "库存量不足", "兑换达上限", "秒杀未开始", "秒杀已结束", "不限时皮肤"};

        public String nickName() {
            return nickNames[ordinal()];
        }
    }

    public enum PropGroup {
        shield, boost, doubleClick, energyRain, vitalitySignDouble, stealthCard, robExpandCard;

        public static final String[] nickNames = {"能量保护罩", "时光加速器", "能量双击卡", "能量雨卡", "活力翻倍卡", "隐身卡", "能量翻倍卡"};

        public String nickName() {
            return nickNames[ordinal()];
        }
    }

    public interface WaterFriendType {

        int WATER_00 = 0;
        int WATER_10 = 1;
        int WATER_18 = 2;
        int WATER_33 = 3;
        int WATER_66 = 4;

        String[] nickNames = {"不浇水", "浇水10克", "浇水18克", "浇水33克", "浇水66克"};
        int[] waterEnergy = {0, 10, 18, 33, 66};
    }

    public interface HelpFriendCollectType {

        int NONE = 0;
        int HELP = 1;
        int NOT_HELP = 2;

        String[] nickNames = {"不复活能量", "复活已选好友", "复活未选好友"};
    }

    public interface UsePropType {

        int CLOSE = 0;
        int ALL = 1;
        int ONLY_LIMIT_TIME = 2;

        String[] nickNames = {"关闭", "所有道具", "限时道具"};
    }

    public interface CollectSelfType {
        int ALL = 0;
        int OVER_THRESHOLD = 1;
        int BELOW_THRESHOLD = 2;

        String[] nickNames = {"所有", "大于阈值", "小于阈值"};
    }
}
