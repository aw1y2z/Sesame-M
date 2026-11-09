package io.github.aw1y2z.sesame.model.task.antForest;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.aw1y2z.sesame.util.XHelpers;
import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.RuntimeInfo;
import io.github.aw1y2z.sesame.data.TokenConfig;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ChoiceModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.EmptyModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ListModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectAndCountModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.StringModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.TextModelField;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAward;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy.Outcome;
import io.github.aw1y2z.sesame.entity.AlipayAntForestHuntTaskList;
import io.github.aw1y2z.sesame.entity.AlipayAntForestVitalityTaskList;
import io.github.aw1y2z.sesame.entity.AlipayMonopolyTaskList;
import io.github.aw1y2z.sesame.entity.AlipayUser;
import io.github.aw1y2z.sesame.entity.CollectEnergyEntity;
import io.github.aw1y2z.sesame.entity.CustomOption;
import io.github.aw1y2z.sesame.entity.FriendWatch;
import io.github.aw1y2z.sesame.entity.KVNode;
import io.github.aw1y2z.sesame.entity.RpcEntity;
import io.github.aw1y2z.sesame.entity.VitalityBenefit;
import io.github.aw1y2z.sesame.entity.AlipayForestHunt;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.model.extensions.ExtensionsHandle;
import io.github.aw1y2z.sesame.model.normal.base.BaseModel;
import io.github.aw1y2z.sesame.model.task.antFarm.AntFarm.TaskStatus;
import io.github.aw1y2z.sesame.model.task.antGame.GameTask;
import io.github.aw1y2z.sesame.rpc.intervallimit.FixedOrRangeIntervalLimit;
import io.github.aw1y2z.sesame.rpc.intervallimit.RpcIntervalLimit;
import io.github.aw1y2z.sesame.ui.ObjReference;
import io.github.aw1y2z.sesame.util.AverageMath;
import io.github.aw1y2z.sesame.util.JsonUtil;
import io.github.aw1y2z.sesame.util.ListUtil;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.NotificationUtil;
import io.github.aw1y2z.sesame.util.RandomUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TaskCancelledException;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.AntForestHuntTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntForestVitalityTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.MonopolyTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;
import io.github.aw1y2z.sesame.util.idMap.VitalityBenefitIdMap;
import lombok.Getter;

/**
 * 蚂蚁森林V2
 */
public class AntForestV2 extends ModelTask {

    private static final String TAG = AntForestV2.class.getSimpleName();

    /**
     * 组队合种浇水本地标记：表示"本方法把用户切到了组队模式，还没切回来"。
     * 用于切回失败 / 进程被杀之后的校正，避免用户被永久留在组队模式。
     */
    private static final String FLAG_TEAM_MODE_SWITCHED = "Forest::teamWaterSwitchedToTeam";

    /**
     * 真爱合种浇水的当日标记。持久化 key，**不能改名**（改名等于让所有人当天再浇一次）。
     */
    private static final String FLAG_LOVETEAM_WATER = "Forest::loveteamWater";

    /**
     * 组队合种当日已浇量（克）的持久化 key，**不能改名**。
     */
    private static final String FLAG_TEAM_WATER_DAILY_COUNT = "FLAG_TEAM_WATER_DAILY_COUNT";

    /**
     * 新版保护地任务场景码：跨保护地区域固定的场景标识。
     * 保护地区域会轮换（洪山动物园→玉树东仲→…），但 regionCode 由 entryInfo 动态下发、
     * sceneCode 始终复用此值；服务端按 regionCode 返回该区域任务，故区域轮换无需改代码。
     */
    private static final String MONOPOLY_TASK_SCENE_HSDWY = "ANTFOREST_MONOPOLY_TASK_HSDWY";

    /**
     * 新版保护地任务的浏览时长上限（秒）：服务端下发 timeCount 后要等这么久才能提交完成，
     * 超出上限视为异常数据，不阻塞本轮任务。
     */
    private static final long MONOPOLY_MAX_TASK_SECONDS = 300L;

    /**
     * 活力兑换遇「系统繁忙」当日最多重试几次。
     * <p>「系统繁忙」是临时故障（服务端文案为"请稍后再试"），打整日标记会放弃当天剩余可兑额度，
     * 故按次计数、用尽才当日停。
     */
    private static final int VITALITY_EXCHANGE_RETRY_MAX = 3;

    /** 站内行为做完后的列表确认轮询：行为生效有延迟，命中即返回，未确认则留给下一轮再核 */
    private static final long VITALITY_PROBE_INTERVAL_MS = 3000L;
    private static final int VITALITY_PROBE_ROUNDS = 3;

    /**
     * 能量雨各来源的结算克数。
     *
     * <p>五个来源互不占额度：每日免费 {@value #ENERGY_RAIN_NORMAL}g、次卡/送出/受赠各
     * {@value #ENERGY_RAIN_CARD_OR_GRANT}g、救援队游戏局 {@value #ENERGY_RAIN_BY_GAME}g。
     * {@code canPlayToday} 五者共用，只表示「还能再开一局」，表示不了「是谁的额度」。
     * 70g 的三种来源克数相同、无法也不必区分，唯有游戏局恒为 50g，是救援队已完成的凭据。
     *
     * <p>受赠局由好友随时送来，故不能假设一轮开完就没了：每轮都须复查 {@code canPlayToday}。
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

    private static final AverageMath offsetTimeMath = new AverageMath(5);

    private static final Map<String, Long> usingProps = new ConcurrentHashMap<>();

    private static final Map<String, String> dressMap;

    static {
        dressMap = new HashMap<>();
        // position To positionType
        dressMap.put("tree__main", "treeMain");
        dressMap.put("bg__sky_0", "bgSky0");
        dressMap.put("bg__sky_cloud", "bgSkyCloud");
        dressMap.put("bg__ground_a", "bgGroundA");
        dressMap.put("bg__ground_b", "bgGroundB");
        dressMap.put("bg__ground_c", "bgGroundC");
        // positionType To position
        dressMap.put("treeMain", "tree__main");
        dressMap.put("bgSky0", "bg__sky_0");
        dressMap.put("bgSkyCloud", "bg__sky_cloud");
        dressMap.put("bgGroundA", "bg__ground_a");
        dressMap.put("bgGroundB", "bg__ground_b");
        dressMap.put("bgGroundC", "bg__ground_c");
    }

    private final AtomicInteger taskCount = new AtomicInteger(0);

    private String selfId;

    private Integer tryCountInt;

    private Integer retryIntervalInt;

    private Integer advanceTimeInt;

    private Integer checkIntervalInt;

    private FixedOrRangeIntervalLimit collectIntervalEntity;

    private FixedOrRangeIntervalLimit doubleCollectIntervalEntity;

    private final AverageMath delayTimeMath = new AverageMath(5);

    private final ObjReference<Long> collectEnergyLockLimit = new ObjReference<>(0L);

    private final Object usePropLockObj = new Object();

    private BooleanModelField collectEnergy;
    private BooleanModelField expiredEnergy;
    private BooleanModelField energyRain;
    private IntegerModelField advanceTime;
    private IntegerModelField tryCount;
    private IntegerModelField retryInterval;
    private SelectModelField dontCollectList;

    private BooleanModelField drawGameCenterAward;
    private BooleanModelField teamPriority;
    private ChoiceModelField CollectSelfEnergyType;

    private IntegerModelField CollectSelfEnergyThreshold;
    private IntegerModelField collectRobExpandEnergy;
    private BooleanModelField collectRobExpandEnergyEnable;
    private BooleanModelField collectWateringBubble;
    private BooleanModelField batchRobEnergy;
    private BooleanModelField balanceNetworkDelay;
    //PK能量
    private BooleanModelField pkEnergy;
    private ChoiceModelField whackModeName;
    private IntegerModelField whackModeGames;
    private IntegerModelField whackModeCount;
    private IntegerModelField earliestwhackMoleTime;

    // 定义运行模式名称数组（需提前声明，与原 Kotlin 中的 whackMoleModeNames 对应）

    private BooleanModelField collectProp;
    private StringModelField queryInterval;
    private StringModelField collectInterval;
    private StringModelField doubleCollectInterval;
    private ChoiceModelField doubleClickType;
    private ListModelField.ListJoinCommaToStringModelField doubleCardTime;
    @Getter
    private IntegerModelField doubleCountLimit;
    private IntegerModelField CollectBombEnergyLimit;
    private BooleanModelField useEnergyRainLimit;
    private BooleanModelField doubleCardConstant;
    private ChoiceModelField helpFriendCollectType;
    private SelectModelField helpFriendCollectList;

    private IntegerModelField helpFriendCollectListLimit;
    private BooleanModelField returnWater;
    private IntegerModelField returnWater33;
    private IntegerModelField returnWater18;
    private IntegerModelField returnWater10;
    private BooleanModelField receiveForestTaskAward;

    private BooleanModelField AutoAntForestVitalityTaskList;
    private SelectModelField AntForestVitalityTaskList;
    private ChoiceModelField waterFriendType;
    private BooleanModelField waterFriendEnergySendChat;

    private BooleanModelField waterFriendEnergyFirst;
    private SelectAndCountModelField waterFriendList;

    private SelectAndCountModelField wateredFriendList;

    private BooleanModelField doubleWaterFriendEnergy;
    private SelectModelField giveEnergyRainList;
    private BooleanModelField vitalityExchangeBenefit;
    private SelectAndCountModelField vitality_ExchangeBenefitList;
    private BooleanModelField userPatrol;
    private BooleanModelField monopolyPatrol;
    private BooleanModelField monopolyTasks;
    private BooleanModelField monopolyExchangeCert;
    private BooleanModelField monopolyAnimalEnergy;
    private BooleanModelField monopolyDispatchAnimal;
    private ChoiceModelField monopolyDispatchPriority;
    private BooleanModelField AutoMonopolyTaskList;
    private SelectModelField MonopolyTaskList;
    private BooleanModelField collectGiftBox;
    private BooleanModelField medicalHealth;
    private BooleanModelField greenLife;
    private BooleanModelField marketBrowseTask;

    private BooleanModelField greenRent;
    private ChoiceModelField consumeAnimalPropType;
    private SelectModelField whoYouWantToGiveTo;
    private BooleanModelField ecoLife;
    private BooleanModelField youthPrivilege;
    private SelectModelField ecoLifeOptions;
    private BooleanModelField dress;
    private BooleanModelField readForest;
    private TextModelField dressDetailList;

    private static int totalCollected = 0;
    private static int totalHelpCollected = 0;
    private static boolean hasErrorWait = false;

    @Getter
    private Set<String> dontCollectMap = new HashSet<>();

    @Override
    public String getName() {
        return "森林";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.FOREST;
    }

    private BooleanModelField loveteamWater;
    private IntegerModelField loveteamWaterNum;

    private BooleanModelField partnerteamWater;
    private IntegerModelField partnerteamWaterNum;
    private BooleanModelField ForestHunt;
    private BooleanModelField AutoAntForestHuntTaskList;
    private SelectModelField AntForestHuntTaskList;
    private BooleanModelField ForestHuntDraw;
    private BooleanModelField ForestHuntHelp;
    private SelectModelField ForestHuntHelpList;

    private SelectAndCountModelField continuousUseCardOptions;

    private BooleanModelField autoUseShieldCard;
    private IntegerModelField continuousUseShieldHour;
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
        modelFields.addField(drawGameCenterAward = new BooleanModelField("drawGameCenterAward", "森林乐园 | 游戏宝箱", true));
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
        modelFields.addField(helpFriendCollectList = new SelectModelField("helpFriendCollectList", "复活能量 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(helpFriendCollectListLimit = new IntegerModelField("helpFriendCollectListLimit", "复活好友能量下限(大于该值复活,0不限制)", 0, 0, 100000).setDependsOn("helpFriendCollectType"));
        modelFields.addField(vitalityExchangeBenefit = new BooleanModelField("vitalityExchangeBenefit", "活力值 | 兑换权益", false));
        modelFields.addField(vitality_ExchangeBenefitList = new SelectAndCountModelField("vitality_ExchangeBenefitList", "活力值 | 权益列表", new LinkedHashMap<>(), VitalityBenefit::getList, "请填写兑换次数(每日)", 1, 100).setDependsOn("vitalityExchangeBenefit"));
        modelFields.addField(whackModeName = new ChoiceModelField("whackModeName", "6秒拼手速 | 运行模式", whackModeNames.CLOSE, whackModeNames.nickNames));
        modelFields.addField(whackModeGames = new IntegerModelField("whackModeGames", "6秒拼手速 | 激进模式局数", 5).setDependsOn("whackModeName"));
        modelFields.addField(whackModeCount = new IntegerModelField("whackModeCount", "6秒拼手速 | 兼容模式击打数", 15).setDependsOn("whackModeName"));
        modelFields.addField(earliestwhackMoleTime = new IntegerModelField("earliestwhackMoleTime", "6秒拼手速 | 最早执行(24小时制)", 8, 0, 23).setDependsOn("whackModeName"));
        modelFields.addField(collectProp = new BooleanModelField("collectProp", "收集道具", false));
        modelFields.addField(whoYouWantToGiveTo = new SelectModelField("whoYouWantToGiveTo", "赠送道具好友列表", new LinkedHashSet<>(), AlipayUser::getList, "会赠送所有可送道具都给已选择的好友"));
        modelFields.addField(energyRain = new BooleanModelField("energyRain", "收集能量雨", false));
        modelFields.addField(giveEnergyRainList = new SelectModelField("giveEnergyRainList", "赠送能量雨好友列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(useEnergyRainLimit = new BooleanModelField("useEnergyRainLimit", "兑换使用限时能量雨卡", false));
        modelFields.addField(userPatrol = new BooleanModelField("userPatrol", "旧版保护地巡护", false));
        modelFields.addField(monopolyPatrol = new BooleanModelField("monopolyPatrol", "新版保护地 | 自动前进", false));
        modelFields.addField(monopolyTasks = new BooleanModelField("monopolyTasks", "新版保护地 | 自动任务", false));
        modelFields.addField(monopolyExchangeCert = new BooleanModelField("monopolyExchangeCert", "新版保护地 | 自动兑换证书(每张图消耗5000g能量)", false));
        modelFields.addField(monopolyAnimalEnergy = new BooleanModelField("monopolyAnimalEnergy", "新版动物伙伴 | 领取能量", false));
        modelFields.addField(monopolyDispatchAnimal = new BooleanModelField("monopolyDispatchAnimal", "新版动物伙伴 | 自动派遣", false));
        modelFields.addField(monopolyDispatchPriority = new ChoiceModelField("monopolyDispatchPriority", "新版动物伙伴 | 派遣优先级", 0, new String[]{"新版优先", "旧版优先"}).setDependsOn("monopolyDispatchAnimal"));
        modelFields.addField(AutoMonopolyTaskList = new BooleanModelField("AutoMonopolyTaskList", "新版保护地 | 自动黑名单", true).setDependsOn("monopolyTasks"));
        modelFields.addField(MonopolyTaskList = new SelectModelField("MonopolyTaskList", "新版保护地 | 黑名单列表", new LinkedHashSet<>(), AlipayMonopolyTaskList::getList).setDependsOn("AutoMonopolyTaskList"));
        modelFields.addField(consumeAnimalPropType = new ChoiceModelField("consumeAnimalPropType", "派遣动物伙伴", ConsumeAnimalPropType.NONE, ConsumeAnimalPropType.nickNames));
        modelFields.addField(receiveForestTaskAward = new BooleanModelField("receiveForestTaskAward", "森林任务", false));
        modelFields.addField(AutoAntForestVitalityTaskList = new BooleanModelField("AutoAntForestVitalityTaskList", "活力值 | 自动黑名单", true));
        modelFields.addField(AntForestVitalityTaskList = new SelectModelField("AntForestVitalityTaskList", "活力值 | 黑名单列表", new LinkedHashSet<>(), AlipayAntForestVitalityTaskList::getList).setDependsOn("AutoAntForestVitalityTaskList"));
        modelFields.addField(collectGiftBox = new BooleanModelField("collectGiftBox", "领取礼盒", false));
        modelFields.addField(medicalHealth = new BooleanModelField("medicalHealth", "医疗健康", false));
        modelFields.addField(greenLife = new BooleanModelField("greenLife", "森林集市", false));
        modelFields.addField(marketBrowseTask = new BooleanModelField("marketBrowseTask", "绿植浏览", false));
        modelFields.addField(greenRent = new BooleanModelField("greenRent", "绿色租赁", false));
        modelFields.addField(readForest = new BooleanModelField("readForest", "无纸阅读", false));
        modelFields.addField(youthPrivilege = new BooleanModelField("youthPrivilege", "青春特权 | 森林道具", false));
        modelFields.addField(ecoLife = new BooleanModelField("ecoLife", "绿色行动 | 开启", false));
        modelFields.addField(ecoLifeOptions = new SelectModelField("ecoLifeOptions", "绿色行动 | 选项", new LinkedHashSet<>(), CustomOption::getEcoLifeOptions, "光盘行动需要先手动完成一次").setDependsOn("ecoLife"));
        modelFields.addField(partnerteamWater = new BooleanModelField("partnerteamWater", "组队合种浇水", false));
        modelFields.addField(partnerteamWaterNum = new IntegerModelField("partnerteamWaterNum", "组队合种浇水" + "(g)", 10, 10, 5000).setDependsOn("partnerteamWater"));
        modelFields.addField(loveteamWater = new BooleanModelField("loveteamWater", "真爱合种浇水", false));
        modelFields.addField(loveteamWaterNum = new IntegerModelField("loveteamWaterNum", "真爱合种浇水" + "(g)", 20, 20, 10000).setDependsOn("loveteamWater"));
        modelFields.addField(ForestHunt = new BooleanModelField("ForestHunt", "森林寻宝", false));
        modelFields.addField(AutoAntForestHuntTaskList = new BooleanModelField("AutoAntForestHuntTaskList", "抽抽乐任务 | 自动黑名单", true).setDependsOn("ForestHunt"));
        modelFields.addField(AntForestHuntTaskList = new SelectModelField("AntForestHuntTaskList", "抽抽乐任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntForestHuntTaskList::getList).setDependsOn("AutoAntForestHuntTaskList"));
        modelFields.addField(ForestHuntDraw = new BooleanModelField("ForestHuntDraw", "森林寻宝抽奖", false).setDependsOn("ForestHunt"));
        modelFields.addField(ForestHuntHelp = new BooleanModelField("ForestHuntHelp", "森林寻宝助力", false).setDependsOn("ForestHunt"));
        modelFields.addField(NORMALForestHuntHelp = new BooleanModelField("NORMALForestHuntHelp", "普通场景强制助力" + "(助力任务不在列表中时使用，如果日志显示失效请关闭)", false).setDependsOn("ForestHunt"));
        modelFields.addField(ACTIVITYForestHuntHelp = new BooleanModelField("ACTIVITYForestHuntHelp", "活动场景强制助力" + "(同上)", false).setDependsOn("ForestHunt"));
        modelFields.addField(ForestHuntHelpList = new SelectModelField("ForestHuntHelpList", "点击配置寻宝助力列表" + "(填写shareId中开头的22-24位字符在\"4O7FEYDgn\"前的)", new LinkedHashSet<>(), AlipayForestHunt::getList).setDependsOn("ForestHunt"));
        modelFields.addField(dress = new BooleanModelField("dress", "装扮保护 | 开启", false));
        modelFields.addField(dressDetailList = new TextModelField("dressDetailList", "装扮保护 | " + "装扮信息", "").setDependsOn("dress"));
        modelFields.addField(new EmptyModelField("dressDetailListClear", "装扮保护 | 装扮信息清除", () -> dressDetailList.reset()).setDependsOn("dress"));
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
            fixTeamModeIfNeeded();

            // 组队优先：主动切到组队模式，让 queryHomePage 返回组队主页（个人能量在 mainMember 里）
            if (teamPriority.getValue()) {
                if (updateUserConfiginTeam(true)) {
                    prioritySwitchedToTeam = true;
                    Status.flagToday(FLAG_TEAM_MODE_SWITCHED);
                    Log.record("组队优先：已切换到组队模式");
                } else {
                    // 账号不在任何队伍 / 切换失败：降级为个人模式（原行为）
                    Log.record("组队优先：切换到组队模式失败（可能不在任何队伍），按个人模式继续");
                }
            }

            if (waterFriendEnergyFirst.getValue()) {
                waterFriendEnergy();
            }


            if (useEnergyRainLimit.getValue()) {
                useEnergyRainCard();
            }

            if (energyRain.getValue()) {
                energyRain();
            }

            if (ecoLife.getValue()) {
                ecoLife();
            }

            if (youthPrivilege.getValue()) {
                // 青春特权道具（大学生双击卡/能量罩/加速器）与「森林任务」共用同一套实现，
                // 由 queryTaskList 内部按 vitalityTask::<firstTaskType> 逐任务标记
                queryVitalityYouthTaskList();
            }
            //连续兑换使用道具卡片
            continuousUseCardOptions();

            if (receiveForestTaskAward.getValue()) {
                vantiepSign();
            }

            JSONObject selfHomeObject = collectSelfEnergy();
            try {
                JSONObject friendsObject = new JSONObject(AntForestRpcCall.queryEnergyRanking());
                if (MessageUtil.checkResultCode(TAG, friendsObject)) {
                    collectFriendsEnergy(friendsObject, "ordinary");
                    int pos = 20;
                    List<String> idList = new ArrayList<>();
                    JSONArray totalDatas = friendsObject.getJSONArray("totalDatas");
                    while (pos < totalDatas.length()) {
                        JSONObject friend = totalDatas.getJSONObject(pos);
                        idList.add(friend.getString("userId"));
                        pos++;
                        if (pos % 20 == 0) {
                            collectFriendsEnergy(idList, "ordinary");
                            idList.clear();
                        }
                    }
                    if (!idList.isEmpty()) {
                        collectFriendsEnergy(idList, "ordinary");
                    }
                }
                selfHomeObject = collectSelfEnergy();
            } catch (Throwable t) {
                Log.err(TAG, "queryEnergyRanking err:", t);
            }

            if (!TaskCommon.IS_ENERGY_TIME && selfHomeObject != null) {
                String whackMoleStatus = selfHomeObject.optString("whackMoleStatus");
                if (Objects.equals("CAN_PLAY", whackMoleStatus) || Objects.equals("CAN_INITIATIVE_PLAY", whackMoleStatus) || Objects.equals("NEED_MORE_FRIENDS", whackMoleStatus)) {
                    checkAndHandleWhackMole();
                }
                boolean hasMore = false;
                do {
                    if (hasMore) {
                        hasMore = false;
                        selfHomeObject = querySelfHome();
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
                        doubleWaterFriendEnergy();
                    }
                }

                waterFriendEnergy();

                if (pkEnergy.getValue()) {
                    collectPKEnergy();
                }

                //初始任务列表
                if (!Status.hasFlagToday("BlackList::initAntForest")) {
                    initAntForestTaskListMap(AutoAntForestVitalityTaskList.getValue(), AutoAntForestHuntTaskList.getValue(), receiveForestTaskAward.getValue(), ForestHunt.getValue());
                    Status.flagToday("BlackList::initAntForest");
                }
                // 新版保护地任务名单来自服务端下发：每轮同步一次，保证黑名单候选项与当前区域任务一致
                if (monopolyTasks.getValue()) {
                    initMonopolyTaskListMap();
                }

                // 组队合种浇水
                if (partnerteamWater.getValue()) {
                    teamCooperateWater();
                }
                // 真爱合种浇水
                if (loveteamWater.getValue()) {
                    if (loveteamWaterNum.getValue() >= 20 && loveteamWaterNum.getValue() <= 10000) {
                        loveteam(loveteamWaterNum.getValue());
                    }
                }

                // 森林寻宝
                if (ForestHunt.getValue()) {
                    ForestChouChouLe forestChouChouLe = new ForestChouChouLe();
                    forestChouChouLe.chouChouLe(ForestHuntDraw.getValue(), ForestHuntHelp.getValue(), ForestHuntHelpList.getValue(), NORMALForestHuntHelp.getValue(), ACTIVITYForestHuntHelp.getValue(), AntForestHuntTaskList.getValue());
                }

                if (userPatrol.getValue()) {
                    queryUserPatrol();
                }
                if (monopolyPatrol.getValue()) {
                    monopolyPatrol();
                }
                if (monopolyAnimalEnergy.getValue()) {
                    collectMonopolyAnimalEnergy();
                }
                // 动物伙伴自动派遣：新版受独立开关与优先级控制，旧版仍只看"派遣动物伙伴"选择
                Integer monopolyDispatchPriorityValue = monopolyDispatchPriority.getValue();
                boolean newAnimalFirst = monopolyDispatchPriorityValue == null || monopolyDispatchPriorityValue == 0;
                boolean animalDispatched = false;
                if (monopolyDispatchAnimal.getValue() && newAnimalFirst) {
                    animalDispatched = dispatchMonopolyAnimal(canConsumeAnimalProp);
                }
                if (consumeAnimalPropType.getValue() != ConsumeAnimalPropType.NONE) {
                    if (!canConsumeAnimalProp) {
                        Log.record("已经有动物伙伴在巡护森林");
                    } else if (!animalDispatched) {
                        animalDispatched = queryAnimalPropList();
                    }
                }
                if (monopolyDispatchAnimal.getValue() && !animalDispatched && !newAnimalFirst) {
                    dispatchMonopolyAnimal(canConsumeAnimalProp);
                }
                if (expiredEnergy.getValue()) {
                    popupTask();
                }

                if (receiveForestTaskAward.getValue()) {
                    queryCommonSign();
                    queryTaskList();
                }

                giveProp();
                if (vitalityExchangeBenefit.getValue()) {
                    vitalityExchangeBenefit();
                }
                /* 森林集市 */
                if (greenLife.getValue()) {
                    greenLife();
                }

                // 绿植浏览（独立任务，与森林集市无关）
                if (marketBrowseTask.getValue()) {
                    GreenPlantBrowse.run();
                }

                // 绿色租赁
                if (greenRent.getValue()) {
                    if (!Status.hasFlagToday("Forest::greenRent")) {
                        greenRent();
                        Status.flagToday("Forest::greenRent");
                    }
                }

                if (medicalHealth.getValue()) {
                    // 医疗健康 绿色医疗 16g*6能量
                    queryForestEnergy("FEEDS");
                    // 医疗健康 电子小票 4g*10能量
                    queryForestEnergy("BILL");
                }

                // 无纸阅读: 读免费书攒绿色能量
                if (readForest.getValue()) {
                    ReadForestTask.execute();
                }

                if (dress.getValue()) {
                    dress();
                }

                checkAndHandleWhackMole();

                //森林乐园
                if (drawGameCenterAward.getValue()) {
                    doforestgame();
                    queryOptionalPlay();
                }

                //1V1能量挑战
                if (energyPvp.getValue()) {
                    if (updateUserConfigEnergyPvp()) {
                        queryPvpHomeInfo();
                        receivePvpRewards();
                    }

                }
                ForestEnergyInfo();
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
            collectRobExpandEnergy();
            syncTodayCollectedFromServer();
            Statistics.save();
            FriendWatch.save();
            // 组队优先：本轮结束切回个人模式（放在倍卡/校准之后，避免它们落到个人版）
            if (prioritySwitchedToTeam) {
                if (updateUserConfiginTeam(false)) {
                    Status.clearFlag(FLAG_TEAM_MODE_SWITCHED);
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

    /** 单次巡护前行的轮次上限：防服务端恒回 GOING 时 100ms 一轮无限重发 */
    private static final int MAX_PATROL_STEPS = 50;

    /** 巡护外层轮次上限：每轮最多走一步/换一次图，上限过小会把剩余步骤挤到下一轮才做 */
    private static final int MAX_PATROL_ROUNDS = 100;

    /** 保护地任务每轮只处理一个，上限按「单次运行尽量做完」放宽，靠状态未推进提前收手 */
    private static final int MAX_MONOPOLY_TASK_ROUNDS = 100;

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

    private void ForestEnergyInfo() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray bubbles = jo.getJSONArray("bubbles");
            int bubblesNumber = bubbles.length();

            if (!jo.has("userBaseInfo")) {
                return;
            }
            JSONObject userBaseInfo = jo.getJSONObject("userBaseInfo");
            int currentEnergy = userBaseInfo.optInt("currentEnergy", 0);
            int totalCertCount = userBaseInfo.optInt("totalCertCount", 0);
            if (!jo.has("userVitalityInfo")) {
                return;
            }
            JSONObject userVitalityInfo = jo.getJSONObject("userVitalityInfo");
            int totalVitalityAmount = userVitalityInfo.optInt("totalVitalityAmount", 0);

            jo = new JSONObject(AntForestRpcCall.queryDynamicsIndex());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            if (!jo.has("todayEnergySummary")) {
                return;
            }
            JSONObject todayEnergySummary = jo.getJSONObject("todayEnergySummary");
            int obtainTotal = todayEnergySummary.optInt("obtainTotal", 0);
            int robbedTotal = todayEnergySummary.optInt("robbedTotal", 0);

            //获取能量日榜top
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("energyRank", "day"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("myself")) {
                return;
            }
            JSONObject myself = jo.getJSONObject("myself");
            int dayenergySummation = myself.optInt("energySummation", 0);
            int dayrank = myself.optInt("rank", 0);

            //获取能量周榜top
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("energyRank", "week"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("myself")) {
                return;
            }
            myself = jo.getJSONObject("myself");
            int weekenergySummation = myself.optInt("energySummation", 0);
            int weekrank = myself.optInt("rank", 0);

            //获取能量总榜top
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("energyRank", "total"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("myself")) {
                return;
            }
            myself = jo.getJSONObject("myself");
            int totalenergySummation = myself.optInt("energySummation", 0);
            int totalrank = myself.optInt("rank", 0);

            //获取偷我日榜top
            String dayenergySummationtop3 = "偷我日榜top3:";
            String userId;
            int energySummation;
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("robRank", "day"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("friendRanking")) {
                return;
            }
            JSONArray friendRankings = jo.getJSONArray("friendRanking");
            for (int i = 0; i < (Math.max(friendRankings.length(), 3)); i++) {
                JSONObject friendRanking = friendRankings.getJSONObject(i);
                energySummation = friendRanking.optInt("energySummation", 0);
                if (energySummation == 0) {
                    break;
                }
                userId = friendRanking.optString("userId", null);
                dayenergySummationtop3 = dayenergySummationtop3 + "[" + UserIdMap.getShowName(userId) + "]" + energySummation + "g;";
            }

            //获取偷我周榜top
            String weekenergySummationtop3 = "偷我周榜top3:";
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("robRank", "week"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("friendRanking")) {
                return;
            }
            friendRankings = jo.getJSONArray("friendRanking");
            for (int i = 0; i < (Math.max(friendRankings.length(), 3)); i++) {
                JSONObject friendRanking = friendRankings.getJSONObject(i);
                energySummation = friendRanking.optInt("energySummation", 0);
                if (energySummation == 0) {
                    break;
                }
                userId = friendRanking.optString("userId", null);
                weekenergySummationtop3 = weekenergySummationtop3 + "[" + UserIdMap.getShowName(userId) + "]" + energySummation + "g;";
            }
            String ForestInfo = "森林榜单🌳收取" + obtainTotal + "g;被收" + robbedTotal + "g;能量球" + bubblesNumber + "个;活力值" + totalVitalityAmount + ";当前能量" + currentEnergy + "g;证书" + totalCertCount + ";😡" + dayenergySummationtop3 + weekenergySummationtop3 + "😁日榜第" + dayrank + "名:" + dayenergySummation + "g;周榜第" + weekrank + "名:" + weekenergySummation + "g;总榜第" + totalrank + "名:" + totalenergySummation + "g;";
            Log.record(ForestInfo);

        } catch (Throwable th) {
            Log.err(TAG, "ForestEnergyInfo err:", th);
        }

    }

    private void collectPKEnergy() {
        try {
            JSONObject pkObject = new JSONObject(AntForestRpcCall.queryTopEnergyChallengeRanking());
            if (!MessageUtil.checkResultCode(TAG + "获取PK排行榜失败:", pkObject)) {
                Log.error("获取PK排行榜失败: " + pkObject.optString("resultDesc"));
            } else {
                if (!pkObject.getString("rankMemberStatus").equals("JOIN")) {
                    Log.record("未加入PK排行榜");
                    return;
                }
                collectFriendsEnergy(pkObject, "PK");
                //继续处理靠后的PK好友
                JSONArray totalData = pkObject.optJSONArray("totalData");
                if (totalData == null || totalData.length() == 0) {
                    Log.record("PK好友排行榜为空，跳过");
                    return;
                }
                List<String> pkIdList = new ArrayList<>();
                for (int pos = 20; pos < totalData.length(); pos++) {
                    JSONObject pkFriend = totalData.getJSONObject(pos);
                    String userId = pkFriend.getString("userId");
                    if (Objects.equals(userId, selfId)) {
                        continue; //如果是自己则跳过
                    }
                    pkIdList.add(userId);
                    if (pkIdList.size() == 20) {
                        collectFriendsEnergy(pkIdList, "PK");
                        pkIdList.clear();
                    }
                }
                if (!pkIdList.isEmpty()) {
                    collectFriendsEnergy(pkIdList, "PK");
                }
                Log.forest("收取PK能量🧾完成！");
            }
        } catch (Exception e) {
            Log.printStackTrace(TAG, e);
        }
    }

    private void notifyMain() {
        if (taskCount.decrementAndGet() < 1) {
            synchronized (AntForestV2.this) {
                AntForestV2.this.notifyAll();
            }
        }
    }

    private JSONObject querySelfHome() {
        JSONObject userHomeObject = null;
        try {
            long start = System.currentTimeMillis();
            userHomeObject = new JSONObject(AntForestRpcCall.queryHomePage());
            long end = System.currentTimeMillis();
            long serverTime = userHomeObject.getLong("now");
            int offsetTime = offsetTimeMath.nextInteger((int) ((start + end) / 2 - serverTime));
            // 正常对时偏差只有几十到几百毫秒，只有超过 1s 才值得记一条，否则每次请求都刷全量日志
            if (Math.abs(offsetTime) > 1000) {
                Log.i("服务器时间：" + serverTime + "，本地与服务器时间差：" + offsetTime);
            }
            //兼容组队模式
            if (isTeam(userHomeObject)) {
                JSONObject teamHomeResult = userHomeObject.optJSONObject("teamHomeResult");
                JSONObject mainMember = teamHomeResult != null ? teamHomeResult.optJSONObject("mainMember") : null;
                //取出组队模式下的selfHomeObject
                if (mainMember != null) {
                    Iterator<String> keyIterator = mainMember.keys();
                    while (keyIterator.hasNext()) {
                        String key = keyIterator.next();
                        Object value = mainMember.get(key);
                        //将道具卡详情存为一般森林主页格式，以便统一解析
                        if (key.equals("usingUserProps")) {
                            key = "usingUserPropsNew";
                        }
                        // 核心方法：put()
                        // 效果：存在该 key 则覆盖原值，不存在则新增键值对
                        userHomeObject.put(key, value);
                    }
                }
                //userHomeObject = teamHomeResult != null ? teamHomeResult.optJSONObject("mainMember") : null;
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return userHomeObject;
    }

    private JSONObject queryFriendHome(String userId) {
        JSONObject userHomeObject = null;
        try {
            long start = System.currentTimeMillis();
            userHomeObject = new JSONObject(AntForestRpcCall.queryFriendHomePage(userId));
            long end = System.currentTimeMillis();
            long serverTime = userHomeObject.getLong("now");
            int offsetTime = offsetTimeMath.nextInteger((int) ((start + end) / 2 - serverTime));
            if (Math.abs(offsetTime) > 1000) {
                Log.i("服务器时间：" + serverTime + "，本地与服务器时间差：" + offsetTime);
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return userHomeObject;
    }

    private JSONObject collectSelfEnergy() {
        try {
            JSONObject selfHomeObject = querySelfHome();
            if (selfHomeObject != null) {
                if (whackModeName.getValue() == whackModeNames.CLOSE) {
                    JSONObject propertiesObject = selfHomeObject.optJSONObject("properties");
                    if (propertiesObject != null) {
                        if (Objects.equals("Y", propertiesObject.optString("whackMole"))) {
                            if (io.github.aw1y2z.sesame.model.task.antForest.WhackMole.closeWhackMole()) {
                                Log.forest("6秒拼手速🧾关闭成功");
                            } else {
                                Log.forest("6秒拼手速⚠️关闭失败");
                            }
                        }
                    }
                }
                String nextAction = selfHomeObject.optString("nextAction");
                if ("WhackMole".equalsIgnoreCase(nextAction)) {
                    Log.record("检测到6秒拼手速强制弹窗，先执行拼手速");
                    checkAndHandleWhackMole();
                }
                return collectUserEnergy(UserIdMap.getCurrentUid(), selfHomeObject, "ordinary");
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return null;
    }

    private JSONObject collectFriendEnergy(String userId, String getType) {
        if (hasErrorWait) {
            return null;
        }
        try {
            JSONObject userHomeObject = queryFriendHome(userId);
            if (userHomeObject != null) {
                return collectUserEnergy(userId, userHomeObject, getType);
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return null;
    }

    private JSONObject collectUserEnergy(String userId, JSONObject userHomeObject, String getType) {
        try {
            if (!MessageUtil.checkResultCode(TAG, userHomeObject)) {
                return userHomeObject;
            }

            long serverTime = userHomeObject.getLong("now");
            boolean isSelf = Objects.equals(userId, selfId);
            String userName;
            boolean isCollectEnergy;
            //默认收炸弹能量
            boolean isBombCollectenergy = true;
            if (getType.equals("PK")) {
                JSONObject userBaseInfo = userHomeObject.getJSONObject("userBaseInfo");
                userName = userBaseInfo.optString("displayName") + "(PK森友)";
                isCollectEnergy = true;
            } else {
                userName = UserIdMap.getMaskName(userId);
                isCollectEnergy = collectEnergy.getValue() && !dontCollectMap.contains(userId);
            }

            if (isSelf) {
                Log.record("进入[" + userName + "]的蚂蚁森林");
                updateUsingPropsEndTime(userHomeObject);
            } else {
                if (isCollectEnergy) {
                    JSONArray jaProps = userHomeObject.getJSONArray("usingUserPropsNew");
                    for (int i = 0; i < jaProps.length(); i++) {
                        JSONObject joProp = jaProps.getJSONObject(i);
                        if (Objects.equals("shield", joProp.getString("propGroup"))) {
                            if (joProp.getLong("endTime") > serverTime) {
                                Log.record("[" + userName + "]能量罩保护到[" + TimeUtil.getCommonDateS(joProp.getLong("endTime")) + "]");
                                isCollectEnergy = false;
                                JSONArray jaBubbles = userHomeObject.getJSONArray("bubbles");
                                for (int ii = 0; ii < jaBubbles.length(); ii++) {
                                    JSONObject canbubble = jaBubbles.getJSONObject(ii);
                                    long bubbleId = canbubble.getLong("id");
                                    switch (CollectStatus.valueOf(canbubble.getString("collectStatus"))) {
                                        case AVAILABLE:
                                            break;
                                        case WAITING:
                                            long produceTime = canbubble.getLong("produceTime");
                                            //如果保护罩不能覆盖能量成熟时间
                                            if (produceTime < joProp.getLong("endTime")) {
                                                break;
                                            }
                                            if (checkIntervalInt + checkIntervalInt / 2 > produceTime - serverTime) {
                                                if (hasChildTask(AntForestV2.getBubbleTimerTid(userId, bubbleId))) {
                                                    break;
                                                }
                                                addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                                Log.record("[" + userName + "]能量保护罩时间[" + TimeUtil.getCommonDate(joProp.getLong("endTime")) + "]#未覆盖能量球成熟时间[" + TimeUtil.getCommonDate(produceTime) + "]");
                                                Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                            } else {
                                                Log.i("用户[" + userName + "]能量成熟时间: " + TimeUtil.getCommonDate(produceTime));
                                            }
                                            break;
                                    }
                                }
                                break;
                            }
                        }
                        if (Objects.equals("energyBombCard", joProp.getString("propGroup"))) {
                            if (joProp.getLong("endTime") > serverTime) {
                                Log.record("[" + userName + "]使用了炸弹卡");
                                if (userHomeObject.has("bubbles")) {
                                    JSONArray jaBubbles = userHomeObject.getJSONArray("bubbles");
                                    for (int ii = 0; ii < jaBubbles.length(); ii++) {
                                        JSONObject Bombubble = jaBubbles.getJSONObject(ii);
                                        int remainEnergy = Bombubble.optInt("remainEnergy");
                                        //存在小于预设值
                                        if (remainEnergy < CollectBombEnergyLimit.getValue()) {
                                            isBombCollectenergy = false;
                                        } else {
                                            Log.record("[" + userName + "]炸弹能量[" + remainEnergy + "g]>设定值[" + CollectBombEnergyLimit.getValue() + "g]");
                                            isBombCollectenergy = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!isBombCollectenergy) {
                isCollectEnergy = false;
            }

            if (isCollectEnergy) {
                JSONArray jaBubbles = userHomeObject.getJSONArray("bubbles");
                List<Long> bubbleIdList = new ArrayList<>();
                for (int i = 0; i < jaBubbles.length(); i++) {
                    JSONObject bubble = jaBubbles.getJSONObject(i);
                    int remainEnergy = bubble.optInt("remainEnergy");
                    long bubbleId = bubble.getLong("id");
                    switch (CollectStatus.valueOf(bubble.getString("collectStatus"))) {
                        case AVAILABLE:
                            //用阈值判断单个能量球需收取情况
                            if (CollectSelfEnergyType.getValue() == CollectSelfType.ALL) {
                                bubbleIdList.add(bubbleId);
                            } else if ((CollectSelfEnergyType.getValue() == CollectSelfType.OVER_THRESHOLD) && (remainEnergy >= CollectSelfEnergyThreshold.getValue())) {
                                bubbleIdList.add(bubbleId);
                            } else if (((CollectSelfEnergyType.getValue() == CollectSelfType.BELOW_THRESHOLD) && (remainEnergy <= CollectSelfEnergyThreshold.getValue()))) {
                                bubbleIdList.add(bubbleId);
                            }
                            break;
                        case WAITING:
                            long produceTime = bubble.getLong("produceTime");
                            if (checkIntervalInt + checkIntervalInt / 2 > produceTime - serverTime) {
                                if (hasChildTask(AntForestV2.getBubbleTimerTid(userId, bubbleId))) {
                                    break;
                                }
                                if (CollectSelfEnergyType.getValue() == CollectSelfType.ALL) {
                                    addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                    Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                } else if ((CollectSelfEnergyType.getValue() == CollectSelfType.OVER_THRESHOLD) && (remainEnergy >= CollectSelfEnergyThreshold.getValue())) {
                                    addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                    Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                } else if (((CollectSelfEnergyType.getValue() == CollectSelfType.BELOW_THRESHOLD) && (remainEnergy <= CollectSelfEnergyThreshold.getValue()))) {
                                    addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                    Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                }
                            } else {
                                Log.i("用户[" + userName + "]能量成熟时间: " + TimeUtil.getCommonDate(produceTime));
                            }
                            break;
                    }
                }
                // 组队模式下走逐个收取，避免批量收取在合种场景下异常；
                // 直接复用已查询的 userHomeObject 判定组队状态，不再额外请求首页。
                if (batchRobEnergy.getValue() && (CollectSelfEnergyType.getValue() == CollectSelfType.ALL) && !isTeam(userHomeObject)) {
                    Iterator<Long> iterator = bubbleIdList.iterator();
                    List<Long> batchBubbleIdList = new ArrayList<>();
                    while (iterator.hasNext()) {
                        batchBubbleIdList.add(iterator.next());
                        if (batchBubbleIdList.size() >= 6) {
                            collectEnergy(new CollectEnergyEntity(userId, userHomeObject, AntForestRpcCall.getCollectBatchEnergyRpcEntity(userId, batchBubbleIdList)), userName);
                            batchBubbleIdList = new ArrayList<>();
                        }
                    }
                    int size = batchBubbleIdList.size();
                    if (size > 0) {
                        if (size == 1) {
                            collectEnergy(new CollectEnergyEntity(userId, userHomeObject, AntForestRpcCall.getCollectEnergyRpcEntity(null, userId, batchBubbleIdList.get(0))), userName);
                        } else {
                            collectEnergy(new CollectEnergyEntity(userId, userHomeObject, AntForestRpcCall.getCollectBatchEnergyRpcEntity(userId, batchBubbleIdList)), userName);
                        }
                    }
                } else {
                    for (Long bubbleId : bubbleIdList) {
                        collectEnergy(new CollectEnergyEntity(userId, userHomeObject, AntForestRpcCall.getCollectEnergyRpcEntity(null, userId, bubbleId)), userName);
                    }
                }
            }

            return userHomeObject;
        } catch (Throwable t) {
            Log.err(TAG, "collectUserEnergy err:", t);
        }
        return null;
    }

    private void collectFriendsEnergy(List<String> idList, String getType) {
        try {
            if (hasErrorWait) {
                return;
            }
            collectFriendsEnergy(new JSONObject(AntForestRpcCall.fillUserRobFlag(new JSONArray(idList).toString())), getType);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private void collectFriendsEnergy(JSONObject friendsObject, String getType) {
        if (hasErrorWait) {
            return;
        }
        try {
            JSONArray jaFriendRanking = friendsObject.optJSONArray("friendRanking");
            if (jaFriendRanking == null) {
                return;
            }
            for (int i = 0, len = jaFriendRanking.length(); i < len; i++) {
                try {
                    JSONObject friendObject = jaFriendRanking.getJSONObject(i);
                    String userId = friendObject.getString("userId");
                    if (Objects.equals(userId, selfId)) {
                        continue;
                    }
                    JSONObject userHomeObject = null;
                    if (getType.equals("PK")) {
                        boolean collectEnergy = true;
                        if (!friendObject.optBoolean("canCollectEnergy")) {
                            long canCollectLaterTime = friendObject.getLong("canCollectLaterTime");
                            if (canCollectLaterTime <= 0 || (canCollectLaterTime - System.currentTimeMillis() > checkIntervalInt)) {
                                collectEnergy = false;
                            }
                        }
                        if (collectEnergy) {
                            userHomeObject = collectFriendEnergy(userId, getType);
                        }
                    } else {
                        if (collectEnergy.getValue() && !dontCollectMap.contains(userId)) {
                            boolean collectEnergy = true;
                            if (!friendObject.optBoolean("canCollectEnergy")) {
                                long canCollectLaterTime = friendObject.getLong("canCollectLaterTime");
                                if (canCollectLaterTime <= 0 || (canCollectLaterTime - System.currentTimeMillis() > checkIntervalInt)) {
                                    collectEnergy = false;
                                }
                            }
                            if (collectEnergy) {
                                userHomeObject = collectFriendEnergy(userId, getType);
                            } /* else {
                  Log.i("不收取[" + UserIdMap.getNameById(userId) + "], userId=" + userId);
              }*/
                        }

                        if (helpFriendCollectType.getValue() != HelpFriendCollectType.NONE && friendObject.optBoolean("canProtectBubble") && !Status.hasFlagToday("forest::protectBubble")) {
                            boolean isHelpCollect = helpFriendCollectList.getValue().contains(userId);
                            if (helpFriendCollectType.getValue() != HelpFriendCollectType.HELP) {
                                isHelpCollect = !isHelpCollect;
                            }
                            if (isHelpCollect) {
                                if (userHomeObject == null) {
                                    userHomeObject = queryFriendHome(userId);
                                }
                                if (userHomeObject != null) {
                                    protectFriendEnergy(userHomeObject);
                                }
                            }
                        }
                        if (collectGiftBox.getValue() && friendObject.getBoolean("canCollectGiftBox")) {
                            if (userHomeObject == null) {
                                userHomeObject = queryFriendHome(userId);
                            }
                            if (userHomeObject != null) {
                                collectGiftBox(userHomeObject);
                            }
                        }
                    }
                } catch (Exception t) {
                    Log.err(TAG, "collectFriendEnergy err:", t);
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private void collectGiftBox(JSONObject userHomeObject) {
        try {
            JSONObject giftBoxInfo = userHomeObject.optJSONObject("giftBoxInfo");
            JSONObject userEnergy = userHomeObject.optJSONObject("userEnergy");
            String userId = userEnergy == null ? UserIdMap.getCurrentUid() : userEnergy.optString("userId");
            if (giftBoxInfo != null) {
                JSONArray giftBoxList = giftBoxInfo.optJSONArray("giftBoxList");
                if (giftBoxList != null && giftBoxList.length() > 0) {
                    for (int ii = 0; ii < giftBoxList.length(); ii++) {
                        try {
                            JSONObject giftBox = giftBoxList.getJSONObject(ii);
                            String giftBoxId = giftBox.getString("giftBoxId");
                            String title = giftBox.getString("title");
                            JSONObject giftBoxResult = new JSONObject(AntForestRpcCall.collectFriendGiftBox(giftBoxId, userId));
                            if (!MessageUtil.checkResultCode(TAG, giftBoxResult)) {
                                continue;
                            }
                            int energy = giftBoxResult.optInt("energy", 0);
                            Log.forest("礼盒能量🎁[" + UserIdMap.getMaskName(userId) + "-" + title + "]#" + energy + "g");
                            Statistics.addData(Statistics.DataType.COLLECTED, energy);
                        } catch (Throwable t) {
                            Log.printStackTrace(t);
                            break;
                        } finally {
                            TimeUtil.sleep(500);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private void protectFriendEnergy(JSONObject userHomeObject) {
        try {
            JSONArray wateringBubbles = userHomeObject.optJSONArray("wateringBubbles");
            JSONObject userEnergy = userHomeObject.optJSONObject("userEnergy");
            String userId = userEnergy == null ? UserIdMap.getCurrentUid() : userEnergy.optString("userId");
            if (wateringBubbles != null && wateringBubbles.length() > 0) {
                for (int j = 0; j < wateringBubbles.length(); j++) {
                    try {
                        JSONObject wateringBubble = wateringBubbles.getJSONObject(j);
                        if (!Objects.equals("fuhuo", wateringBubble.getString("bizType"))) {
                            continue;
                        }
                        if (wateringBubble.getJSONObject("extInfo").optInt("restTimes", 0) == 0) {
                            Status.flagToday("forest::protectBubble");
                        }
                        if (!wateringBubble.getBoolean("canProtect")) {
                            continue;
                        }
                        int fullEnergy = wateringBubble.optInt("fullEnergy", 0);
                        if (fullEnergy < helpFriendCollectListLimit.getValue()) {
                            continue;
                        }
                        JSONObject joProtect = new JSONObject(AntForestRpcCall.protectBubble(userId));
                        if (!MessageUtil.checkResultCode(TAG, joProtect)) {
                            continue;
                        }
                        int vitalityAmount = joProtect.optInt("vitalityAmount", 0);

                        String str = "复活能量🧾[" + UserIdMap.getMaskName(userId) + "-" + fullEnergy + "g]" + (vitalityAmount > 0 ? "#活力值+" + vitalityAmount : "");
                        Log.forest(str);
                        totalHelpCollected += fullEnergy;
                        Statistics.addData(Statistics.DataType.HELPED, fullEnergy);

                        break;
                    } catch (Throwable t) {
                        Log.printStackTrace(t);
                        break;
                    } finally {
                        TimeUtil.sleep(500);
                    }
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private void collectEnergy(CollectEnergyEntity collectEnergyEntity, String username) {
        collectEnergy(collectEnergyEntity, false, username);
    }

    private void collectEnergy(CollectEnergyEntity collectEnergyEntity, Boolean joinThread, String username) {
        if (hasErrorWait) {
            return;
        }
        Runnable runnable = () -> {
            try {
                String userId = collectEnergyEntity.getUserId();
                RpcEntity rpcEntity = collectEnergyEntity.getRpcEntity();
                boolean needDouble = collectEnergyEntity.getNeedDouble();
                boolean needRetry = collectEnergyEntity.getNeedRetry();
                int tryCount = collectEnergyEntity.addTryCount();
                int collected = 0;
                long startTime;
                synchronized (collectEnergyLockLimit) {
                    long sleep;
                    if (needDouble) {
                        collectEnergyEntity.unsetNeedDouble();
                        sleep = doubleCollectIntervalEntity.getInterval() - System.currentTimeMillis() + collectEnergyLockLimit.get();
                    } else if (needRetry) {
                        collectEnergyEntity.unsetNeedRetry();
                        sleep = retryIntervalInt - System.currentTimeMillis() + collectEnergyLockLimit.get();
                    } else {
                        sleep = collectIntervalEntity.getInterval() - System.currentTimeMillis() + collectEnergyLockLimit.get();
                    }
                    if (sleep > 0) {
                        TimeUtil.sleep(sleep);
                    }
                    startTime = System.currentTimeMillis();
                    collectEnergyLockLimit.setForce(startTime);
                }
                ApplicationHook.requestObject(rpcEntity, 0, 0);
                long spendTime = System.currentTimeMillis() - startTime;
                if (balanceNetworkDelay.getValue()) {
                    delayTimeMath.nextInteger((int) (spendTime / 3));
                }
                if (rpcEntity.getHasError()) {
                    String errorCode = (String) XHelpers.callMethod(rpcEntity.getResponseObject(), "getString", "error");
                    if (Objects.equals("1004", errorCode)) {
                        if (BaseModel.getWaitWhenException().getValue() > 0) {
                            long waitTime = System.currentTimeMillis() + BaseModel.getWaitWhenException().getValue();
                            RuntimeInfo.getInstance().put(RuntimeInfo.RuntimeInfoKey.ForestPauseTime, waitTime);
                            NotificationUtil.updateStatusText("异常");
                            Log.record("触发异常,等待至" + TimeUtil.getCommonDate(waitTime));
                            hasErrorWait = true;
                            return;
                        }
                        TimeUtil.sleep(600 + RandomUtil.delay());
                    }
                    if (tryCount < tryCountInt) {
                        collectEnergyEntity.setNeedRetry();
                        collectEnergy(collectEnergyEntity, username);
                    }
                    return;
                }
                JSONObject jo = new JSONObject(rpcEntity.getResponseString());
                String resultCode = jo.getString("resultCode");
                if (!"SUCCESS".equalsIgnoreCase(resultCode)) {
                    if ("PARAM_ILLEGAL2".equals(resultCode)) {
                        Log.forest("[" + username + "]" + "能量已被收取,取消重试 错误:" + jo.getString("resultDesc"));
                        return;
                    }
                    Log.forest("[" + username + "]" + jo.getString("resultDesc"));
                    if (tryCount < tryCountInt) {
                        collectEnergyEntity.setNeedRetry();
                        collectEnergy(collectEnergyEntity, username);
                    }
                    return;
                }

                JSONArray jaBubbles = jo.getJSONArray("bubbles");

                int jaBubbleLength = jaBubbles.length();
                if (jaBubbleLength > 1) {
                    List<Long> newBubbleIdList = new ArrayList<>();
                    for (int i = 0; i < jaBubbleLength; i++) {
                        JSONObject bubble = jaBubbles.getJSONObject(i);
                        if (bubble.getBoolean("canBeRobbedAgain")) {
                            newBubbleIdList.add(bubble.getLong("id"));
                        }
                        collected += bubble.getInt("collectedEnergy");
                    }
                    if (collected > 0) {
                        FriendWatch.friendWatch(userId, collected);
                        String str;
                        if (jo.has("bombCardEffect")) {
                            JSONObject bombCardEffect = jo.getJSONObject("bombCardEffect");
                            int explodeEnergy = bombCardEffect.optInt("explodeEnergy", 0);
                            str = "一键收取🪂[" + username + "]#" + collected + "g被炸" + explodeEnergy + "g";
                        } else {
                            str = "一键收取🪂[" + username + "]#" + collected + "g";
                        }
                        if (needDouble) {
                            Log.forest(str + "[双击]");
                            Log.i("收取耗时[" + spendTime + "]ms[双击]");
                            Toast.show(str + "[双击]");
                        } else {
                            Log.forest(str);
                            Log.i("收取耗时[" + spendTime + "]ms");
                            Toast.show(str);
                        }
                        totalCollected += collected;
                        Statistics.addData(Statistics.DataType.COLLECTED, collected);
                    } else {
                        Log.forest("一键收取[" + username + "]的能量失败，好友：" + userId + "，气泡：" + newBubbleIdList);
                    }
                    if (!newBubbleIdList.isEmpty()) {
                        collectEnergyEntity.setRpcEntity(AntForestRpcCall.getCollectBatchEnergyRpcEntity(userId, newBubbleIdList));
                        collectEnergyEntity.setNeedDouble();
                        collectEnergyEntity.resetTryCount();
                        collectEnergy(collectEnergyEntity, username);
                    }
                } else if (jaBubbleLength == 1) {
                    JSONObject bubble = jaBubbles.getJSONObject(0);
                    collected += bubble.getInt("collectedEnergy");
                    FriendWatch.friendWatch(userId, collected);
                    if (collected > 0) {
                        String str;
                        if (jo.has("bombCardEffect")) {
                            JSONObject bombCardEffect = jo.getJSONObject("bombCardEffect");
                            int explodeEnergy = bombCardEffect.optInt("explodeEnergy", 0);
                            str = "收取能量🪂[" + username + "]#" + collected + "g被炸" + explodeEnergy + "g";
                        } else {
                            str = "收取能量🪂[" + username + "]#" + collected + "g";
                        }

                        if (needDouble) {
                            Log.forest(str + "[双击]");
                            Log.i("收取耗时[" + spendTime + "]ms[双击]");
                            Toast.show(str + "[双击]");
                        } else {
                            Log.forest(str);
                            Log.i("收取耗时[" + spendTime + "]ms");
                            Toast.show(str);
                        }
                        totalCollected += collected;
                        Statistics.addData(Statistics.DataType.COLLECTED, collected);
                    } else {
                        Log.forest("收取[" + username + "]的能量失败");
                        Log.i(TAG, "收取失败详情: UserID=" + userId + ", BubbleId=" + bubble.getLong("id"));
                    }
                    if (bubble.getBoolean("canBeRobbedAgain")) {
                        collectEnergyEntity.setNeedDouble();
                        collectEnergyEntity.resetTryCount();
                        collectEnergy(collectEnergyEntity, username);
                        return;
                    }
                    JSONObject userHome = collectEnergyEntity.getUserHome();
                    if (userHome == null) {
                        return;
                    }
                    String bizNo = userHome.optString("bizNo");
                    if (bizNo.isEmpty()) {
                        return;
                    }
                    if (returnWater.getValue()) {
                        int returnCount = 0;
                        if (collected >= returnWater33.getValue()) {
                            returnCount = 33;
                        } else if (collected >= returnWater18.getValue()) {
                            returnCount = 18;
                        } else if (collected >= returnWater10.getValue()) {
                            returnCount = 10;
                        }
                        if (returnCount > 0) {
                            returnFriendWater(userId, bizNo, 1, returnCount);
                        }
                    }
                }
            } catch (Exception e) {
                Log.err(TAG, "collectEnergy err:", e);
            } finally {
                Statistics.save();
                notifyMain();
            }
        };
        taskCount.incrementAndGet();
        if (joinThread) {
            runnable.run();
        } else {
            addChildTask(new ChildModelTask("CE|" + collectEnergyEntity.getUserId() + "|" + runnable.hashCode(), "CE", runnable));
        }
    }

    private void updateUsingPropsEndTime() throws JSONException {
        JSONObject joHomePage = new JSONObject(AntForestRpcCall.queryHomePage());
        TimeUtil.sleep(100);
        updateUsingPropsEndTime(joHomePage);
    }

    private void updateUsingPropsEndTime(JSONObject joHomePage) {
        try {
            JSONArray ja = joHomePage.getJSONArray("loginUserUsingPropNew");
            if (ja.length() == 0) {
                ja = joHomePage.getJSONArray("usingUserPropsNew");
            }
            for (int i = 0; i < ja.length(); i++) {
                JSONObject jo = ja.getJSONObject(i);
                usingProps.put(jo.getString("propGroup"), jo.getLong("endTime"));
            }
            forestExtensions();
        } catch (Throwable th) {
            Log.err(TAG, "updateUsingPropsEndTime err:", th);
        }
    }

    /**
     * 收取倍卡额外能量（本轮收尾调用）。倍卡额度是本轮收取行为累积出来的，
     * 先收只能收到上一轮攒下的部分，本轮领到的要等到下一轮；且好友能量是异步收取，
     * 必须等子任务跑完再查，否则查到的仍是未累积的旧值。
     */
    private void collectRobExpandEnergy() {
        if (!collectRobExpandEnergyEnable.getValue()) {
            return;
        }
        try {
            // 复用 querySelfHome：组队版个人数据在 teamHomeResult.mainMember 下，直读根对象取不到道具
            JSONObject joHomePage = querySelfHome();
            if (joHomePage != null) {
                collectRobExpandEnergy(joHomePage);
            }
        } catch (Throwable th) {
            Log.err(TAG, "collectRobExpandEnergy err:", th);
        }
    }

    /** 从首页响应里取出在用的倍卡并收取 */
    private void collectRobExpandEnergy(JSONObject joHomePage) {
        try {
            // 组队版首页把在用道具放在 usingUserProps（个人版在 loginUserUsingPropNew），三个字段都取
            JSONArray ja = joHomePage.optJSONArray("loginUserUsingPropNew");
            if (ja == null || ja.length() == 0) {
                ja = joHomePage.optJSONArray("usingUserPropsNew");
            }
            if (ja == null || ja.length() == 0) {
                ja = joHomePage.optJSONArray("usingUserProps");
            }
            if (ja == null) {
                Log.i(TAG, "额外能量⏭️未在使用倍卡");
                return;
            }
            for (int i = 0; i < ja.length(); i++) {
                // 单个道具结构异常不能中断整轮扫描：逐项兜住，取不到字段按跳过处理
                try {
                    JSONObject jo = ja.optJSONObject(i);
                    if (jo == null || !PropGroup.robExpandCard.name().equals(jo.optString("propGroup"))) {
                        continue;
                    }
                    collectRobExpandEnergy(jo.optString("extInfo"), jo.optString("propId"), jo.optString("propType"));
                } catch (Throwable th) {
                    Log.err(TAG, "collectRobExpandEnergy item err:", th);
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "collectRobExpandEnergy err:", th);
        }
    }

    private void collectRobExpandEnergy(String extInfo, String propId, String propType) {
        if (extInfo.isEmpty()) {
            return;
        }
        // 关闭"倍卡额外能量"开关：不收取额外能量
        if (!collectRobExpandEnergyEnable.getValue()) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(extInfo);
            double leftEnergy = jo.optDouble("leftEnergy", 0);
            int threshold = collectRobExpandEnergy.getValue();
            // 开关开启：受数值限制，只有大于"倍卡额外能量"阈值才收取
            if (leftEnergy > threshold) {
                collectRobExpandEnergy(propId, propType);
            } else {
                // 被阈值拦下不是服务端行为，留过程日志便于区分"没进分支"与"服务端拒绝"
                Log.i(TAG, "额外能量⏭️剩余[" + leftEnergy + "g]未超过阈值[" + threshold + "g]");
            }
        } catch (Throwable th) {
            Log.err(TAG, "collectRobExpandEnergy err:", th);
        }
    }

    private void collectRobExpandEnergy(String propId, String propType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.collectRobExpandEnergy(propId, propType));
            if ("SUCCESS".equalsIgnoreCase(jo.optString("resultCode"))) {
                int collectEnergy = jo.optInt("collectEnergy");
                Log.forest("额外能量🎄收取[" + collectEnergy + "g]");
                totalCollected += collectEnergy;
                Statistics.addData(Statistics.DataType.COLLECTED, collectEnergy);
                return;
            }
            // 额外能量不足整数部分是服务端正常提示，非异常：按结果类记录，不打原始响应
            Log.forest("额外能量⏭️" + jo.optString("resultDesc"));
        } catch (Throwable th) {
            Log.err(TAG, "collectRobExpandEnergy err:", th);
        }
    }

    private void queryForestEnergy(String scene) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryForestEnergy(scene));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            jo = jo.getJSONObject("data").getJSONObject("response");
            JSONArray ja = jo.getJSONArray("energyGeneratedList");
            if (ja.length() > 0) {
                harvestForestEnergy(scene, ja);
            }
            int remainBubble = jo.optInt("remainBubble");
            for (int i = 0; i < remainBubble; i++) {
                ja = produceForestEnergy(scene);
                if (ja.length() == 0 || !harvestForestEnergy(scene, ja)) {
                    return;
                }
                TimeUtil.sleep(1000);
            }
        } catch (Throwable th) {
            Log.err(TAG, "queryForestEnergy err:", th);
        }
    }

    private JSONArray produceForestEnergy(String scene) {
        JSONArray energyGeneratedList = new JSONArray();
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.produceForestEnergy(scene));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                JSONObject response = jo.optJSONObject("data") == null
                        ? null : jo.getJSONObject("data").optJSONObject("response");
                if (response != null) {
                    energyGeneratedList = response.optJSONArray("energyGeneratedList");
                    if (energyGeneratedList == null) {
                        energyGeneratedList = new JSONArray();
                    }
                    if (energyGeneratedList.length() > 0) {
                        String title = scene.equals("FEEDS") ? "绿色医疗" : "电子小票";
                        int cumulativeEnergy = response.optInt("cumulativeEnergy", 0);
                        Log.forest("医疗健康🧾完成[" + title + "]#产生[" + cumulativeEnergy + "g能量]");
                    }
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "produceForestEnergy err:", th);
        }
        return energyGeneratedList;
    }

    private Boolean harvestForestEnergy(String scene, JSONArray bubbles) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.harvestForestEnergy(scene, bubbles));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            jo = jo.getJSONObject("data").getJSONObject("response");
            int collectedEnergy = jo.getInt("collectedEnergy");
            if (collectedEnergy > 0) {
                String title = scene.equals("FEEDS") ? "绿色医疗" : "电子小票";
                Log.forest("医疗健康🧾收取[" + title + "]#获得[" + collectedEnergy + "g能量]");
                totalCollected += collectedEnergy;
                Statistics.addData(Statistics.DataType.COLLECTED, collectedEnergy);
                return true;
            }
        } catch (Throwable th) {
            Log.err(TAG, "harvestForestEnergy err:", th);
        }
        return false;
    }

    /**
     * 检查并处理6秒拼手速逻辑（每天主动执行一次）
     */
    private void whackMole() {
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

    private void checkAndHandleWhackMole() {
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

    public void initAntForestTaskListMap(boolean AutoAntForestVitalityTaskList, boolean AutoAntForestHuntTaskList, boolean receiveForestTaskAward, boolean ForestHunt) {
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

            if (receiveForestTaskAward) {
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
                if (AutoAntForestVitalityTaskList) {
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

            if (ForestHunt) {
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
                if (AutoAntForestHuntTaskList) {
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

    /**
     * 新版保护地任务列表初始化：把服务端下发的任务名同步到 {@link MonopolyTaskListMap}，
     * 作为配置页「新版保护地 | 黑名单列表」的候选项。
     * <p>没有预置黑白名单：新版保护地任务名由服务端动态下发，全部交给自动拉黑机制判定。
     */
    public void initMonopolyTaskListMap() {
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
                            String title = blackTaskKey(monopolyTaskTitle(base, taskType));
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

    /* 森林集市 */
    private static void greenLife() {
        sendEnergyByAction("GREEN_LIFE");
        retrieveCurrentActivity();
    }

    // 绿色租赁
    private static void greenRent() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.checkUserSecondSceneChance());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            // 本轮不发能量时再请求生成会被服务端拒（SYSTEM_ERROR）：先看机会字段，直接跳过
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
                totalCollected += zulinshangpinliulan;
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

    /**
     * 乐园限定任务状态探针：重拉 {@code queryOptionalPlay} 列表（**该任务的归属列表**），
     * 按 sceneCode+taskType 匹配。
     * <p>不能借用活力值任务的探针（{@code listTaskopengreen}）——乐园任务不在那个列表里，
     * 会恒匹配不到而被当成 {@code GONE}（已消失=已完成），把"做不了"误判成"已完成"。
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
                totalCollected += receivedEnergyAmount;
                Statistics.addData(Statistics.DataType.COLLECTED, receivedEnergyAmount);
                // 确认到账后才算当天已完成（线上逛街每天仅可完成一次）
                Status.flagToday("forest::greenLifeSendEnergy::" + sourceType);
            }
        } catch (Throwable t) {
            Log.err(TAG, "sendEnergyByAction err:", t);
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
                                // antiepSign(entityId, userId, sceneCode)：原实现把 userId 与 sceneCode 传反，接口必然失败
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

    private void waterFriendEnergy() {
        String taskUid = UserIdMap.getCurrentUid();
        int waterEnergy = WaterFriendType.waterEnergy[waterFriendType.getValue()];
        if (waterEnergy == 0) {
            return;
        }
        Map<String, Integer> friendMap = waterFriendList.getValue();
        for (Map.Entry<String, Integer> friendEntry : friendMap.entrySet()) {
            String uid = friendEntry.getKey();
            if (selfId.equals(uid)) {
                continue;
            }
            Integer waterCount = friendEntry.getValue();
            if (waterCount == null || waterCount <= 0) {
                continue;
            }
            if (waterCount > 3) {
                waterCount = 3;
            }
            // 只补浇「配置次数 - 当日已浇」的差额，避免部分失败后被重复浇满而超出配置
            int remainCount = waterCount - Status.getWaterFriendToday(uid);
            if (remainCount > 0) {
                try {
                    JSONObject jo = new JSONObject(AntForestRpcCall.queryFriendHomePage(uid));
                    TimeUtil.sleep(100);
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        String bizNo = jo.getString("bizNo");
                        KVNode<Integer, Boolean> waterCountKVNode = returnFriendWater(uid, bizNo, remainCount, waterEnergy);
                        int wateredCount = waterCountKVNode.getKey();
                        if (wateredCount > 0) {
                            Status.waterFriendToday(uid, wateredCount, taskUid);
                        }
                        if (!waterCountKVNode.getValue()) {
                            break;
                        }
                    }
                } catch (Throwable t) {
                    Log.err(TAG, "waterFriendEnergy err:", t);
                }
            }
        }
    }

    private KVNode<Integer, Boolean> returnFriendWater(String userId, String bizNo, int count, int waterEnergy) {
        if (bizNo == null || bizNo.isEmpty()) {
            return new KVNode<>(0, true);
        }
        int wateredTimes = 0;
        boolean isContinue = true;
        try {
            String s;
            JSONObject jo;
            int energyId = getEnergyId(waterEnergy);
            label:
            for (int waterCount = 1; waterCount <= count; waterCount++) {
                s = AntForestRpcCall.transferEnergy(userId, bizNo, energyId, waterFriendEnergySendChat.getValue() ? "Y" : "N");
                TimeUtil.sleep(1500);
                jo = new JSONObject(s);

                String resultCode = jo.getString("resultCode");
                switch (resultCode) {
                    case "SUCCESS":
                        //记录浇水次数
                        Status.wateringFriendToday(userId);
                        Statistics.addData(Statistics.DataType.WATERINGCOUNT, 1);
                        int currentEnergy = jo.getJSONObject("userBaseInfo").getInt("currentEnergy");
                        Log.forest("好友浇水🚿给[" + UserIdMap.getShowName(userId) + "]浇" + waterEnergy + "g#剩余能量[" + currentEnergy + "g]");
                        Toast.show("好友浇水🚿给[" + UserIdMap.getShowName(userId) + "]浇" + waterEnergy + "g");
                        wateredTimes++;
                        Statistics.addData(Statistics.DataType.WATERED, waterEnergy);
                        break;
                    case "WATERING_TIMES_LIMIT":
                        Log.forest("好友浇水🚿今日给[" + UserIdMap.getMaskName(userId) + "]浇水已达上限");
                        wateredTimes = 3;
                        break label;
                    case "WATERING_USER_LIMIT":
                        Log.forest("好友浇水🚿给[" + UserIdMap.getMaskName(userId) + "]浇水，" + jo.getString("resultDesc"));
                        wateredTimes = 3;
                        break label;
                    default:
                        // 未知失败不再重发：响应丢失但已生效时，用同一 bizNo 重发会重复扣能量
                        Log.forest("好友浇水🚿" + jo.getString("resultDesc"));
                        Log.i(TAG, "好友浇水响应: " + jo);
                        break label;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "returnFriendWater err:", t);
        }
        return new KVNode<>(wateredTimes, isContinue);
    }

    private int getEnergyId(int waterEnergy) {
        if (waterEnergy <= 0) {
            return 0;
        }
        if (waterEnergy >= 66) {
            return 42;
        }
        if (waterEnergy >= 33) {
            return 41;
        }
        if (waterEnergy >= 18) {
            return 40;
        }
        return 39;
    }

    private void doubleWaterFriendEnergy() {
        String taskUid = UserIdMap.getCurrentUid();
        int waterEnergy = WaterFriendType.waterEnergy[waterFriendType.getValue()];
        if (waterEnergy == 0) {
            return;
        }
        boolean reSet = true;
        Map<String, Integer> friendMap = waterFriendList.getValue();
        for (Map.Entry<String, Integer> friendEntry : friendMap.entrySet()) {
            String uid = friendEntry.getKey();
            if (selfId.equals(uid)) {
                continue;
            }
            Integer waterCount = friendEntry.getValue();
            if (waterCount == null || waterCount <= 0) {
                continue;
            }
            if (Status.canWaterFriendToday(uid, 3)) {
                reSet = false;
            }
        }
        if (reSet) {
            for (Map.Entry<String, Integer> friendEntry : friendMap.entrySet()) {
                String uid = friendEntry.getKey();
                if (selfId.equals(uid)) {
                    continue;
                }
                Integer waterCount = friendEntry.getValue();
                if (waterCount == null || waterCount <= 0) {
                    continue;
                }
                //重置浇水次数
                Status.resetWaterFriendToday(uid, taskUid);
            }
            Log.record("好友浇水🚿今日给好友浇水状态已重置！");
            Status.flagToday("Forest::doubleWaterFriendEnergy");
        }
    }

    private void forestExtensions() {
        try {
            ExtensionsHandle.handleAlphaRequest("antForest", "extensions", usingProps);
        } catch (Throwable t) {
            Log.err(TAG, "forestExtensions err:", t);
        }
    }

    // skuId, sku
    Map<String, JSONObject> skuInfo = new HashMap<>();

    /**
     * 最近一次清单拉取是否完整：有分类请求失败即为 false。
     * 不完整时不能把缺失的 skuId 判为「已下架」，否则一次网络抖动会把配置里的兑换项全部拉黑。
     */
    private boolean skuListComplete = false;

    private void vitalityExchangeBenefit() {
        try {
            // 清单当日拉一次即可：单轮最多 5 个分类共 20 多次请求，且它同时是配置页兑换选项的来源，
            // 不能完全不拉；当日标记与重试逻辑都在 getAllSkuInfo() 内部
            getAllSkuInfo();
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
                        && exchangeBenefit(skuId)) {
                    TimeUtil.sleep(3000);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "vitalityExchangeBenefit err:", t);
        }
    }

    private void vantiepSign() {
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

    private void queryCommonSign() {
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

    private void vitalitySign() {
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
            new YouthForestRoute("DXS_JSQ", "202212TJBRW", "JIASUQI_20230808", "加速器"),
    };

    private void queryVitalityYouthTaskList() {
        for (YouthForestRoute route : YOUTH_FOREST_ROUTES) {
            queryYouthForestTask(route);
        }
    }

    private void queryTaskList() {
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
                    doubleCheck = doForsetTaskList(taskInfoList);
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
                doubleCheck = doForsetTaskList(taskInfoList);
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryTaskList err:", t);
        }
    }

    private Boolean doForsetTaskList(JSONArray taskInfoList) {
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
                    if (AntForestVitalityTaskList.getValue().contains(taskTitle)) {
                        continue;
                    }
                    // 周期/持续型任务：由真实行为推进、常驻列表且无 RPC 可完成，跳过（不尝试、不拉黑）
                    if (TaskAttemptPolicy.isCyclicTask(taskBaseInfo)) {
                        // 周期任务常驻列表、由真实行为推进，每轮结果相同；静默跳过
                        continue;
                    }

                    // 站内行为类任务（浇水/收能量/能量雨）：完成条件是**服务端的行为计数**，
                    // 只能真做那个行为；上报与伪申报都推不动它，会白试一次后按"兜底未生效"拉黑
                    TaskAttemptPolicy.Forge forge = forestVitalityForge(taskType, sceneCode, taskTitle, bizInfo);
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
    private TaskAttemptPolicy.Forge forestVitalityForge(String taskType, String sceneCode, String taskTitle,
                                                        JSONObject bizInfo) {
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
        if (isWaterVitalityTask(taskType, taskTitle) && canWaterFriend()) {
            return () -> {
                waterFriendEnergy();
                return probeVitalityDone(sceneCode, taskType);
            };
        }
        if (isEnergyRainVitalityTask(taskType, taskTitle)) {
            return () -> {
                // 活力值任务里的能量雨就是普通一局，按次卡/赠送额度走
                startEnergyRain();
                return probeVitalityDone(sceneCode, taskType);
            };
        }
        return null;
    }

    /** 是否浇得动水：动作非"不浇水"且选了好友 */
    private boolean canWaterFriend() {
        int type = waterFriendType.getValue();
        if (type < 0 || type >= WaterFriendType.waterEnergy.length
                || WaterFriendType.waterEnergy[type] == 0) {
            return false;
        }
        Map<String, Integer> friendMap = waterFriendList.getValue();
        return friendMap != null && !friendMap.isEmpty();
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
    private boolean probeVitalityDone(String sceneCode, String taskType) {
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

    private void queryYouthForestTask(YouthForestRoute route) {
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
    private String queryYouthForestTaskStatus(YouthForestRoute route) throws JSONException {
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

    private Boolean receiveTaskAward(String sceneCode, String taskType, String taskTitle) {
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

    /**
     * 黑名单键：剥掉标题末尾的 "(n/N)" 次数后缀。
     * <p>权限类任务在上报时标题会被拼上 "(2/10)"，
     * 而运行时的黑名单检查用的是纯标题；不处理会导致写进黑名单的键永远匹配不上、拉黑失效。
     */
    private static String blackTaskKey(String taskTitle) {
        return StringUtil.stripCountSuffix(taskTitle);
    }

    /** 森林任务完成上报：拉黑与否仍交给自动拉黑机制（400000040 已降级为连续确认） */
    private Outcome attemptFinishTask(String sceneCode, String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.finishTask(sceneCode, taskType));
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntForestVitalityTaskList", blackTaskKey(taskTitle), jo);
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
    private TaskAttemptPolicy.ProbeResult probeForestVitalityStatus(String sceneCode, String taskType) {
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
    private boolean awardFinishedChildren(JSONArray childTaskTypeList) {
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
    private boolean doChildTask(JSONArray childTaskTypeList, String title) {
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

    /**
     * 开一局能量雨并结算。
     *
     * <p>只有 50g 的游戏局能证明救援队已换到额度；任务列表回读不可作判据（上报异步，回读必然抢跑）。
     *
     * @return 结算成功的克数；失败返回 {@code -1}
     */
    private int startEnergyRain() {
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
            totalCollected += sum;
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

    // LIMIT_TIME_ENERGY_DOUBLE_CLICK,CR20230516000363
    // LIMIT_TIME_ENERGY_RAIN_CHANCE,SK20250117005985,VITALITY_ENERGYRAIN_3DAYS，限时3天内使用能量雨次卡
    private void useEnergyRainCard() {
        try {
            // 商店兑换 限时能量雨卡
            exchangeBenefit("SK20250117005985");
            TimeUtil.sleep(2000);
            JSONObject jo;
            do {
                TimeUtil.sleep(1000);
                // 背包查找 能量雨卡
                jo = null;
                List<JSONObject> list = getPropGroup(getForestPropVOList(), PropGroup.energyRain.name());
                if (!list.isEmpty()) {
                    jo = list.get(0);
                }
                if (jo == null) {
                    break;
                }
                // 使用能量雨卡
            } while (consumeProp(jo));
        } catch (Throwable th) {
            Log.err(TAG, "useEnergyRainCard err:", th);
        }
    }

    /**
     * 收集能量雨：先开尽当前额度，再送好友，最后上报救援队等游戏局额度。
     *
     * <p>受赠局由好友随时送来，故每轮都要先复查现有额度，不能因「今日已开满」而整体跳过。
     */
    private void energyRain() {
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
    private void drainEnergyRainQuota() {
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
     * <p>返回 {@link #SLJYD_MISSING} 表示响应已无此项（完成后服务端会移除，是「已完成」的证据，勿当失败重试）；
     * {@code null} 表示响应残缺，无从判断。
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
                // 响应残缺，判断不了状态
                Log.record("森林救援队🐱未取到任务状态#下次运行重试");
                return;
            }
            Log.record("森林救援队🐱无需上报(状态:" + sljydStatus + ")，今日跳过");
            Status.flagToday(SLJYD_FLAG);
        } catch (Throwable th) {
            Log.printStackTrace("执行能量雨后续任务出错:", th);
        }
    }

    public void doforestgame() {
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
    private void queryOptionalPlay() {
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
                    if (AntForestVitalityTaskList.getValue().contains(title)) {
                        continue;
                    }
                    // 底线：交易/支付/充值类一律不申报、一次即永久拉黑，绝不伪造。
                    // 必须同时查 groupId：充值类的 taskType 常无关键词，判据在 groupId（cz=充值）
                    if (TaskAlternative.isTransactionTask(taskType)
                            || TaskAlternative.isTransactionTask(groupId)
                            || TaskAlternative.isTransactionTask(sceneCode)) {
                        // 同一轮只拉一次：该分支不做去重的话，多轮重拉会把同一批任务反复写盘并刷屏
                        if (attempted.add(blackTaskKey(title))) {
                            MessageUtil.MarkTaskBlackListPermanent("AntForestV2", "AntForestVitalityTaskList",
                                    "森林乐园限定任务", blackTaskKey(title));
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
                                () -> attemptFinishTask(sceneCode, taskType, label), Log::forest,
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
    private int optionalPlayRoundLimit(JSONArray taskList) {
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

    private void continuousUseCardOptions() {
        //双击卡
        continuousUseAndExchangeCard("doubleClick", "SK20240805004754");
        //收能量倍卡
        continuousUseAndExchangeCard("robExpandCard", "");
        //保护罩：自动续用为独立开关，不受「连续兑换使用道具卡片」选项限制
        if (autoUseShieldCard.getValue()) {
            continuousUseAndExchangeCard("shield", "CR20230516000370");
        }
        //隐身卡
        continuousUseAndExchangeCard("stealthCard", "SK20230521000206");
        //炸弹卡
        //continuousUseAndExchangeCard("energyBombCard", "SK20250219006517");
    }

    private void continuousUseAndExchangeCard(String propGroupType, String exchangeProp) {
        try {
            if (propGroupType.equals("shield") || continuousUseCardOptions.contains(propGroupType)) {
                long continuousUseCardSecond = continuousUseCardCheak(propGroupType);
                // 未使用/已过期（==0）需新开；shield 且在用且剩余<阈值（>0）需续用延长；其余（-1 充足）不处理
                if (continuousUseCardSecond == 0
                        || (propGroupType.equals("shield") && continuousUseCardSecond > 0)) {
                    // 每日使用次数上限（0 = 不限制）：达到后当天不再消耗该卡片，也不再兑换补充
                    Integer optLimit = continuousUseCardOptions.get(propGroupType);
                    int cardLimit = optLimit == null ? 0 : optLimit;
                    if (cardLimit > 0) {
                        int used = Status.getIntFlagToday("continuousUseCard::count::" + propGroupType);
                        if (used >= cardLimit) {
                            Log.forest("连续兑换使用道具卡片🌟[" + propGroupType + "]今日已用满[" + used + "/" + cardLimit + "]#停止");
                            return;
                        }
                    }
                    TimeUtil.sleep(500);
                    JSONObject rightCard = chooseContinuousLIMITTIMECard(propGroupType);
                    if (rightCard == null) {
                        if (exchangeProp != null) {
                            exchangeBenefit(exchangeProp);
                            TimeUtil.sleep(500);
                        }
                        rightCard = chooseContinuousLIMITTIMECard(propGroupType);
                        if (rightCard == null) {
                            return;
                        }
                    }
                    int holdsNum = rightCard.optInt("holdsNum");
                    if (holdsNum == 0) {
                        return;
                    }
                    int loopCount = 0; // 循环次数计数
                    final int MAX_LOOP = 10; // 最大循环次数，避免死循环
                    cardLoop: do {
                        rightCard = chooseContinuousLIMITTIMECard(propGroupType);
                        if (rightCard == null) {
                            return;
                        }
                        holdsNum = rightCard.optInt("holdsNum");
                        if (holdsNum == 0) {
                            return;
                        }
                        if (!rightCard.has("propIdList")) {
                            return;
                        }
                        JSONArray propIdList = rightCard.optJSONArray("propIdList");
                        if (propIdList.length() == 0) {
                            return;
                        }
                        // 当日使用次数上限（0 = 不限制）：达到后不再消耗该道具卡片（在真正消耗前拦截）
                        // cardLimit 取自外层 if(==0) 块（每选项独立设置），此处直接复用
                        if (cardLimit > 0) {
                            int used = Status.getIntFlagToday("continuousUseCard::count::" + propGroupType);
                            if (used >= cardLimit) {
                                Log.forest("连续兑换使用道具卡片🌟[" + propGroupType + "]今日已用满[" + used + "/" + cardLimit + "]#停止");
                                return;
                            }
                        }
                        String propId = propIdList.optString(0);
                        String propType = rightCard.optString("propType");
                        String propName = rightCard.getJSONObject("propConfigVO").getString("propName");
                        JSONObject joResult = null;
                        switch (propGroupType) {
                            case "doubleClick":
                            case "shield":
                            case "robExpandCard":
                                if (continuousUseCardSecond > 0) {
                                    joResult = new JSONObject(AntForestRpcCall.consumeProp(propGroupType, propId, propType, true));
                                } else {
                                    joResult = new JSONObject(AntForestRpcCall.consumeProp(propGroupType, propId, propType, false));
                                }
                                holdsNum--;
                                TimeUtil.sleep(500);
                                if (MessageUtil.checkResultCode(TAG, joResult)) {
                                    Log.forest("使用道具🎭[" + propName + "]");
                                }
                                break;

                            case "stealthCard":
                                joResult = new JSONObject(AntForestRpcCall.consumeProp(propGroupType, propId, propType));
                                holdsNum--;
                                TimeUtil.sleep(1000);
                                if (MessageUtil.checkResultCode(TAG, joResult)) {
                                    Log.forest("使用道具🎭[" + propName + "]");
                                }
                                break;
                            /*case "energyBombCard":
                                joResult = new JSONObject(AntForestRpcCall.consumeProp(propGroupType, propId, propType,false));
                                holdsNum--;
                                TimeUtil.sleep(1000);
                                if (MessageUtil.checkResultCode(TAG, joResult)) {
                                    Log.forest("使用道具🎭[" + propName + "]");
                                }
                                break;*/
                        }
                        // 保护罩：续用到剩余保护时间≥"自动续用保护罩(小时)"阈值即停止，避免一次用光背包多张
                        if (propGroupType.equals("shield") && continuousUseCardCheak("shield") == -1) {
                            break cardLoop;
                        }
                        // 使用成功才计入当日次数（上限已在循环开头拦截，此处不会多发请求）
                        if (cardLimit > 0 && MessageUtil.checkResultCode(TAG, joResult)) {
                            Status.setIntFlagToday("continuousUseCard::count::" + propGroupType,
                                    Status.getIntFlagToday("continuousUseCard::count::" + propGroupType) + 1);
                        }
                        continuousUseCardSecond = continuousUseCardCheak(propGroupType);
                        if (continuousUseCardSecond < 0) {
                            return;
                        }
                        TimeUtil.sleep(500);
                    } while (holdsNum > 0 && ++loopCount < MAX_LOOP);
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "continuousUseAndExchangeCard err:", th);
        }
    }

    /**
     * 该道具类型在"当前没在使用（接口里没出现 / 已经过期）"时，是否应该启用一张新的。
     * <p>只有保护罩需要这样处理：它的意义是让能量球不被偷，过期后必须再动一张新的，否则等于没保护。
     * 上述两种情况都返回 -1 会让过期后永远不再续用，表现为"不自动使用保护罩"。其余道具维持原行为：
     * 隐身卡固定不使用；双击卡/倍率卡按阈值接续，改动会额外消耗道具。
     */
    private static boolean canStartWhenNotInUse(String propGroupType) {
        return "shield".equals(propGroupType);
    }

    //判断是否可以使用道具卡片
    //返回值-1为不可用，0为可用，大于0为剩余时间
    private long continuousUseCardCheak(String propGroupType) {
        try {
            JSONObject joMiscHomes = new JSONObject(AntForestRpcCall.queryMiscInfo());
            if (!MessageUtil.checkResultCode(TAG, joMiscHomes)) {
                return -1;
            }
            if (!joMiscHomes.has("combineHandlerVOMap")) {
                return -1;
            }
            long now = System.currentTimeMillis();
            JSONObject combineHandlerVOMap = joMiscHomes.optJSONObject("combineHandlerVOMap");
            if (!combineHandlerVOMap.has("usingProp")) {
                // 当前没有任何道具在使用：保护罩要能从头用一张，其余道具保持原行为（不可用）
                return canStartWhenNotInUse(propGroupType) ? shieldFallback(now) : -1;
            }
            JSONObject usingProp = combineHandlerVOMap.optJSONObject("usingProp");
            if (!usingProp.has("userPropVOS")) {
                return canStartWhenNotInUse(propGroupType) ? shieldFallback(now) : -1;
            }
            JSONArray userPropVOS = usingProp.getJSONArray("userPropVOS");
            for (int i = 0; i < userPropVOS.length(); i++) {
                JSONObject userPropVO = userPropVOS.getJSONObject(i);
                String propGroup = userPropVO.optString("propGroup");
                if (propGroup.equals(propGroupType)) {
                    long endTime = userPropVO.optLong("endTime");
                    long duringTime = endTime - now;
                    if (duringTime < 0) {
                        // 用过的道具已过期（接口里仍留着这条记录）：保护罩应重新启用一张
                        return canStartWhenNotInUse(propGroupType) ? 0 : -1;
                    }
                    switch (propGroupType) {
                        case "doubleClick":
                            if (duringTime / (1000 * 60) < 60 * 24 * 31) {
                                return duringTime;
                            } else {
                                return -1;
                            }
                        case "robExpandCard":
                            if (duringTime / (1000 * 60) < 60 * 24 * 30) {
                                return duringTime;
                            } else {
                                return -1;
                            }
                        case "stealthCard":
                            return -1;
                        case "shield":
                            // 在保护中且剩余<阈值：返回 >0 表示"需续用延长"；充足则返回 -1 不处理
                            return duringTime / (1000 * 60) < 60 * continuousUseShieldHour.getValue()
                                    ? duringTime : -1;
                        /*case "energyBombCard":
                            if (duringTime / (1000 * 60) < 3*60 * 24) {
                                Log.forest("duringTime");
                                return duringTime;
                            }
                            else {
                                return -1;
                            }*/

                    }
                }
            }
            // usingProp 列表不含该道具（保护罩不在其中），改按主页真实到期时间判断
            return canStartWhenNotInUse(propGroupType) ? shieldFallback(now) : 0;
        } catch (Throwable th) {
            Log.err(TAG, "useDoubleCard err:", th);
        }
        return -1;
    }

    /**
     * 保护罩在道具接口里查不到时的兜底：按个人主页的到期时间判断是否续用
     * （返回值同 {@link #continuousUseCardCheak}：-1 不可用 / 0 无保护可用 / >0 剩余毫秒）。
     */
    private long shieldFallback(long now) {
        long duringTime = queryShieldEndTime() - now;
        if (duringTime <= 0) {
            return 0;
        }
        return duringTime / (1000 * 60) < 60 * continuousUseShieldHour.getValue() ? duringTime : -1;
    }

    /**
     * 保护罩的真实到期时间（毫秒），未在保护中返回 0。
     * <p>{@code queryMiscInfo} 的 usingProp 不含 shield，只能从个人主页的 usingUserPropsNew 取。
     */
    private long queryShieldEndTime() {
        try {
            JSONObject joHomePage = new JSONObject(AntForestRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, joHomePage)) {
                return 0;
            }
            JSONArray ja = joHomePage.optJSONArray("loginUserUsingPropNew");
            if (ja == null || ja.length() == 0) {
                ja = joHomePage.optJSONArray("usingUserPropsNew");
            }
            if (ja == null) {
                return 0;
            }
            long endTime = 0;
            for (int i = 0; i < ja.length(); i++) {
                JSONObject prop = ja.getJSONObject(i);
                if ("shield".equals(prop.optString("propGroup"))) {
                    endTime = Math.max(endTime, prop.optLong("endTime"));
                }
            }
            return endTime;
        } catch (Throwable th) {
            Log.err(TAG, "queryShieldEndTime err:", th);
        }
        return 0;
    }

    private String useRobExpandCardFactor() {
        try {
            JSONObject joMiscHomes = new JSONObject(AntForestRpcCall.queryMiscInfo());
            if (!MessageUtil.checkResultCode(TAG, joMiscHomes)) {
                return null;
            }
            if (!joMiscHomes.has("combineHandlerVOMap")) {
                return null;
            }
            long now = System.currentTimeMillis();
            JSONObject combineHandlerVOMap = joMiscHomes.optJSONObject("combineHandlerVOMap");
            if (!combineHandlerVOMap.has("usingProp")) {
                return null;
            }
            JSONObject usingProp = combineHandlerVOMap.optJSONObject("usingProp");
            if (!usingProp.has("userPropVOS")) {
                return null;
            }
            JSONArray userPropVOS = usingProp.getJSONArray("userPropVOS");
            for (int i = 0; i < userPropVOS.length(); i++) {
                JSONObject userPropVO = userPropVOS.getJSONObject(i);
                String propGroup = userPropVO.optString("propGroup");
                if (propGroup.equals("robExpandCard")) {
                    if (!userPropVO.has("detail")) {
                        return null;
                    }
                    return userPropVO.optJSONObject("detail").optString("factor");
                }
            }
            return null;
        } catch (Throwable th) {
            Log.err(TAG, "UserobExpandCardFactor err:", th);
        }
        return null;
    }


    //选出可以使用的限时道具卡片
    private JSONObject chooseContinuousLIMITTIMECard(String propGroupType) {
        try {
            JSONArray forestPropVOList = getForestPropVOList();
            JSONObject rightCard = null;
            String useFactor = useRobExpandCardFactor();
            for (int i = 0; i < forestPropVOList.length(); i++) {
                JSONObject forestBagProp = forestPropVOList.getJSONObject(i);
                String propGroup = forestBagProp.optString("propGroup");
                if (forestBagProp.has("recentExpireTime") && propGroup.equals(propGroupType)) {
                    switch (propGroup) {
                        case "stealthCard":
                        case "shield":
                        case "doubleClick":
                            //case "energyBombCard":
                            if (rightCard != null) {
                                long recentExpireTimerightCard = rightCard.optLong("recentExpireTime");
                                long recentExpireTimeforestBagProp = forestBagProp.optLong("recentExpireTime");
                                if (recentExpireTimerightCard > recentExpireTimeforestBagProp) {
                                    rightCard = forestBagProp;
                                }
                            } else {
                                rightCard = forestBagProp;
                            }
                            break;
                        case "robExpandCard":
                            //有在用倍卡，选择同倍率快到期卡
                            if (useFactor != null) {
                                String factorforestBagProp = forestBagProp.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                                if (factorforestBagProp.equals(useFactor)) {
                                    if (rightCard != null) {
                                        String factorrightCard = rightCard.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                                        long recentExpireTimerightCard = rightCard.optLong("recentExpireTime");
                                        long recentExpireTimeforestBagProp = forestBagProp.optLong("recentExpireTime");
                                        if (Float.parseFloat(factorrightCard) == Float.parseFloat(factorforestBagProp)) {
                                            if (recentExpireTimerightCard > recentExpireTimeforestBagProp) {
                                                rightCard = forestBagProp;
                                            }
                                        }
                                    } else {
                                        rightCard = forestBagProp;
                                    }
                                }
                            }
                            //没有在用倍卡，选择最高倍率卡
                            else {
                                if (rightCard != null) {
                                    String factorrightCard = rightCard.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                                    String factorforestBagProp = forestBagProp.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                                    if (Float.parseFloat(factorrightCard) < Float.parseFloat(factorforestBagProp)) {
                                        rightCard = forestBagProp;
                                    }
                                } else {
                                    rightCard = forestBagProp;
                                }
                            }
                    }
                }
            }
            // 保护罩的永久卡（ENERGY_SHIELD）服务端不下发 recentExpireTime，会被上面按到期时间的筛选排除，
            // 导致背包里有罩也一张都用不了，故补一次兜底；限时保护罩仍优先于它
            if (rightCard == null && "shield".equals(propGroupType)) {
                for (int i = 0; i < forestPropVOList.length(); i++) {
                    JSONObject forestBagProp = forestPropVOList.getJSONObject(i);
                    if ("shield".equals(forestBagProp.optString("propGroup")) && !forestBagProp.has("recentExpireTime")) {
                        rightCard = forestBagProp;
                        break;
                    }
                }
            }
            return rightCard;
        } catch (Throwable th) {
            Log.err(TAG, "useDoubleCard err:", th);
        }
        return null;
    }

    private void usePropBeforeCollectEnergy(String userId) {
        if (Objects.equals(selfId, userId)) {
            return;
        }
        if (needDoubleClick()) {
            synchronized (usePropLockObj) {
                if (needDoubleClick()) {
                    useDoubleCard(getForestPropVOList());
                }
            }
        }
    }

    private Boolean needDoubleClick() {
        // 双击卡配置已停用（addField 被注释）→ 字段为 null，按"关闭"处理，避免 NPE
        if (doubleClickType == null || doubleClickType.getValue() == UsePropType.CLOSE) {
            return false;
        }
        Long doubleClickEndTime = usingProps.get(PropGroup.doubleClick.name());
        if (doubleClickEndTime == null) {
            return true;
        }
        return doubleClickEndTime < System.currentTimeMillis();
    }

    private void useDoubleCard(JSONArray forestPropVOList) {
        try {
            if (hasDoubleCardTime() && Status.canDoubleToday()) {
                // 背包查找 能量双击卡
                JSONObject jo = null;
                List<JSONObject> list = getPropGroup(forestPropVOList, PropGroup.doubleClick.name());
                if (!list.isEmpty()) {
                    jo = list.get(0);
                }
                if (jo == null || !jo.has("recentExpireTime")) {
                    // 配置已停用则字段为 null，按"关闭"处理
                    if (doubleCardConstant != null && doubleCardConstant.getValue()) {
                        // 商店兑换 限时能量双击卡
                        if (exchangeBenefit("SK20240805004754")) {
                            jo = getForestPropVO(getForestPropVOList(), "ENERGY_DOUBLE_CLICK_31DAYS");
                        } else if (exchangeBenefit("CR20230516000363")) {
                            jo = getForestPropVO(getForestPropVOList(), "LIMIT_TIME_ENERGY_DOUBLE_CLICK");
                        }
                    }
                }
                if (jo == null) {
                    return;
                }
                if (!jo.has("recentExpireTime")
                        && (doubleClickType == null || doubleClickType.getValue() == UsePropType.ONLY_LIMIT_TIME)) {
                    return;
                }
                // 使用能量双击卡
                if (consumeProp(jo)) {
                    Long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(jo.getJSONObject("propConfigVO").getLong("durationTime"));
                    usingProps.put(PropGroup.doubleClick.name(), endTime);
                    Status.DoubleToday();
                } else {
                    updateUsingPropsEndTime();
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "useDoubleCard err:", th);
        }
    }

    private boolean hasDoubleCardTime() {
        // 配置已停用（addField 被注释）→ 字段为 null，视为"不在使用时间范围"
        if (doubleCardTime == null) {
            return false;
        }
        long currentTimeMillis = System.currentTimeMillis();
        return TimeUtil.checkInTimeRange(currentTimeMillis, doubleCardTime.getValue());
    }

    /* 赠送道具 */
    private void giveProp() {
        Set<String> set = whoYouWantToGiveTo.getValue();
        if (set.isEmpty()) {
            return;
        }
        for (String userId : set) {
            if (UserIdMap.getCurrentUid() == null || Objects.equals(UserIdMap.getCurrentUid(), userId)) {
                continue;
            }
            giveProp(userId);
            break;
        }
    }

    private void giveProp(String targetUserId) {
        try {
            do {
                try {
                    JSONObject jo = new JSONObject(AntForestRpcCall.queryPropList(true));
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        return;
                    }
                    JSONArray forestPropVOList = jo.optJSONArray("forestPropVOList");
                    if (forestPropVOList != null && forestPropVOList.length() > 0) {
                        jo = forestPropVOList.getJSONObject(0);
                        String giveConfigId = jo.getJSONObject("giveConfigVO").getString("giveConfigId");
                        int holdsNum = jo.optInt("holdsNum", 0);
                        String propName = jo.getJSONObject("propConfigVO").getString("propName");
                        String propId = jo.getJSONArray("propIdList").getString(0);
                        jo = new JSONObject(AntForestRpcCall.giveProp(giveConfigId, propId, targetUserId));
                        if (MessageUtil.checkResultCode(TAG, jo)) {
                            Log.forest("赠送道具🎭[" + UserIdMap.getMaskName(targetUserId) + "]#" + propName);
                            if (holdsNum > 1 || forestPropVOList.length() > 1) {
                                continue;
                            }
                        }
                    }
                } finally {
                    TimeUtil.sleep(1500);
                }
                break;
            } while (true);
        } catch (Throwable th) {
            Log.err(TAG, "giveProp err:", th);
        }
    }

    /**
     * 绿色行动
     */
    private void ecoLife() {
        try {
            JSONObject jo = new JSONObject(EcoLifeRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject data = jo.getJSONObject("data");
            if (!data.getBoolean("openStatus")) {
                Log.forest("绿色任务☘未开通");
                jo = new JSONObject(EcoLifeRpcCall.openEcolife());
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                if (!String.valueOf(true).equals(JsonUtil.getValueByPath(jo, "data.opResult"))) {
                    return;
                }
                Log.forest("绿色任务🍀报告大人，开通成功(～￣▽￣)～可以愉快的玩耍了");
                jo = new JSONObject(EcoLifeRpcCall.queryHomePage());
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                data = jo.getJSONObject("data");
            }
            String dayPoint = data.getString("dayPoint");
            JSONArray actionListVO = data.getJSONArray("actionListVO");
            if (ecoLifeOptions.getValue().contains("dish")) {
                photoGuangPan(dayPoint);
            }
            if (ecoLifeOptions.getValue().contains("tick")) {
                ecoLifeTick(actionListVO, dayPoint);
            }
        } catch (Throwable th) {
            Log.err(TAG, "ecoLife err:", th);
        }
    }

    /* 绿色行动打卡 */

    private void ecoLifeTick(JSONArray actionListVO, String dayPoint) {
        try {
            String source = "source";
            for (int i = 0; i < actionListVO.length(); i++) {
                JSONObject actionVO = actionListVO.getJSONObject(i);
                JSONArray actionItemList = actionVO.getJSONArray("actionItemList");
                for (int j = 0; j < actionItemList.length(); j++) {
                    JSONObject actionItem = actionItemList.getJSONObject(j);
                    if (!actionItem.has("actionId")) {
                        continue;
                    }
                    if (actionItem.getBoolean("actionStatus")) {
                        continue;
                    }
                    String actionId = actionItem.getString("actionId");
                    String actionName = actionItem.getString("actionName");
                    if ("photoguangpan".equals(actionId)) {
                        continue;
                    }
                    JSONObject jo = new JSONObject(EcoLifeRpcCall.tick(actionId, dayPoint, source));
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        Log.forest("绿色打卡🍀[" + actionName + "]");
                    }
                    TimeUtil.sleep(500);
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "ecoLifeTick err:", th);
        }
    }

    /**
     * 光盘行动
     */
    private void photoGuangPan(String dayPoint) {
        try {
            String source = "renwuGD";
            // 检查今日任务状态
            JSONObject jo = new JSONObject(EcoLifeRpcCall.queryDish(source, dayPoint));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            // 更新光盘照片
            Map<String, String> dishImage = new HashMap<>();
            JSONObject data = jo.optJSONObject("data");
            if (data != null) {
                String beforeMealsImageUrl = data.optString("beforeMealsImageUrl");
                String afterMealsImageUrl = data.optString("afterMealsImageUrl");
                if (!StringUtil.isEmpty(beforeMealsImageUrl) && !StringUtil.isEmpty(afterMealsImageUrl)) {
                    Pattern pattern = Pattern.compile("img/(.*)/original");
                    Matcher beforeMatcher = pattern.matcher(beforeMealsImageUrl);
                    if (beforeMatcher.find()) {
                        dishImage.put("BEFORE_MEALS", beforeMatcher.group(1));
                    }
                    Matcher afterMatcher = pattern.matcher(afterMealsImageUrl);
                    if (afterMatcher.find()) {
                        dishImage.put("AFTER_MEALS", afterMatcher.group(1));
                    }
                    TokenConfig.saveDishImage(dishImage);
                }
            }
            if (Objects.equals("SUCCESS", jo.getJSONObject("data").getString("status"))) {
                return;
            }

            dishImage = TokenConfig.getRandomDishImage();
            if (dishImage == null) {
                Log.forest("光盘行动💿请先完成一次光盘打卡");
                return;
            }
            // 上传餐前照片
            jo = new JSONObject(EcoLifeRpcCall.uploadBeforeMealsDishImage(dishImage.get("BEFORE_MEALS"), dayPoint));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            // 上传餐后照片
            jo = new JSONObject(EcoLifeRpcCall.uploadAfterMealsDishImage(dishImage.get("AFTER_MEALS"), dayPoint));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            // 提交
            jo = new JSONObject(EcoLifeRpcCall.tick("photoguangpan", dayPoint, source));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            String toastMsg = jo.getJSONObject("data").getString("toastMsg");
            Toast.show("光盘行动💿打卡完成#" + toastMsg);
            Log.forest("光盘行动💿打卡完成#" + toastMsg + "");
        } catch (Throwable t) {
            Log.err(TAG, "photoGuangPan err:", t);
        }
    }

    private void queryUserPatrol() {
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
                        JSONObject userPatrol = record.optJSONObject("userPatrol");
                        if (userPatrol == null) {
                            continue;
                        }
                        if (userPatrol.getInt("unreachedNodeCount") > 0) {
                            if ("silent".equals(userPatrol.getString("mode"))) {
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

                JSONObject userPatrol = jo.getJSONObject("userPatrol");
                int currentNode = userPatrol.getInt("currentNode");
                String currentStatus = userPatrol.getString("currentStatus");
                int patrolId = userPatrol.getInt("patrolId");
                JSONObject chance = userPatrol.getJSONObject("chance");
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

    private void patrolKeepGoing(String s, int nodeIndex, int patrolId) {
        try {
            // 服务端持续回 GOING、currentNode 却不推进时，这里原本 100ms 一轮无限重发，
            // 外层 queryUserPatrol 的轮次上限挡不住单次调用内部死循环
            int loopCount = 0;
            int lastNode = Integer.MIN_VALUE;
            int unchanged = 0;
            do {
                if (++loopCount > MAX_PATROL_STEPS) {
                    Log.i(TAG, "巡护⚖️前行次数超过上限，本轮停止");
                    return;
                }
                if (s == null) {
                    s = AntForestRpcCall.patrolKeepGoing(nodeIndex, patrolId, "image");
                }
                JSONObject jo = new JSONObject(s);
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                JSONArray jaEvents = jo.optJSONArray("events");
                if (jaEvents == null || jaEvents.length() == 0) {
                    return;
                }
                JSONObject userPatrol = jo.getJSONObject("userPatrol");
                int currentNode = userPatrol.getInt("currentNode");
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
                JSONObject events = jo.getJSONArray("events").getJSONObject(0);
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
                s = AntForestRpcCall.patrolKeepGoing(currentNode, patrolId, materialType);
                TimeUtil.sleep(100);
            } while (true);
        } catch (Throwable t) {
            Log.err(TAG, "patrolKeepGoing err:", t);
        }
    }

    /* 新版保护地：遍历所有已开放的保护地，逐区域巡护（解决多个保护地并行时的覆盖问题） */
    private void monopolyPatrol() {
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
    private void patrolMonopolyRegion() {
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
            boolean tasksEnabled = monopolyTasks.getValue();
            // 掷骰会解锁新任务，所以骰子用完后需要再补跑一次任务
            boolean tasksMayHaveChanged = false;
            if (tasksEnabled) {
                monopolyTask(regionCode);
            }
            if (monopolyExchangeCert.getValue()) {
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
    private void exchangeMonopolyCertificate(String regionCode) {
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
    private boolean isMonopolyClosed(JSONObject activity) {
        long now = System.currentTimeMillis();
        long start = parseMonopolyDate(activity.optString("startDate"));
        long end = parseMonopolyDate(activity.optString("endDate"));
        return (start > 0 && now < start) || (end > 0 && now >= end);
    }

    /* 解析新版保护地的 "yyyy-MM-dd HH:mm:ss" 时间串（东八区），解析失败返回 0 */
    private long parseMonopolyDate(String date) {
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
    private JSONObject monopolyResponse(String raw) throws JSONException {
        JSONObject jo = new JSONObject(raw);
        JSONObject resData = jo.optJSONObject("resData");
        return resData == null ? jo : resData;
    }

    /** 当前保护地区域码：由 entryInfo 动态下发，区域轮换无需改代码；取不到返回空串 */
    private String getMonopolyRegionCode() {
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
    private boolean monopolyTask(String regionCode) {
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
    private String monopolyTaskTitle(JSONObject base, String taskType) {
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
    private boolean isMonopolyTaskBlacklisted(String title) {
        Set<String> blackList = MonopolyTaskList.getValue();
        return blackList != null && blackList.contains(blackTaskKey(title));
    }

    /* 失败响应按统一判据自动拉黑（可重试错误不会拉黑），受「自动黑名单」开关控制 */
    private void markMonopolyTaskBlackList(String title, JSONObject jo) {
        if (Boolean.TRUE.equals(AutoMonopolyTaskList.getValue())) {
            MessageUtil.checkResultCodeAndMarkTaskBlackList("MonopolyTaskList", blackTaskKey(title), jo);
        }
    }

    /* 新版保护地：按服务端下发的时长等待后提交任务完成 */
    private boolean monopolyFinishTask(JSONObject base, String taskType, String sceneCode, String title) {
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
                        blackTaskKey(title));
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
    private boolean monopolyReceiveTaskAward(String regionCode, String taskType, String sceneCode, String title) {
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
     * <p>保护地领奖响应没有可直接读取的到账量字段（不同于森林集市的 {@code receivedEnergyAmount} /
     * 绿色租赁的 {@code zulinshangpinliulan}），因此以领奖前后 {@code currentEnergy} 的差值作为
     * 实际到账量。差值需为正才计入，避免服务端并发扣减导致负数误记。
     *
     * @param energyBefore 领奖前查询到的能量余额；{@code < 0} 表示查询失败，放弃记账
     */
    private void recordMonopolyAward(String title, int energyBefore) {
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
        totalCollected += gained;
        Statistics.addData(Statistics.DataType.COLLECTED, gained);
    }

    /**
     * 查询账户当前能量余额（{@code userBaseInfo.currentEnergy}）。
     *
     * @return 余额；查询失败返回 {@code -1}
     */
    private int queryCurrentEnergy() {
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
    private TaskAttemptPolicy.ProbeResult probeMonopolyStatus(String regionCode, String taskType, String sceneCode) {
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
    private void collectMonopolyAnimalEnergy() {
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

    /* 新版动物伙伴：自动派遣（已有动物在巡护时不替换） */
    private boolean dispatchMonopolyAnimal(boolean canConsumeAnimalProp) {
        try {
            if (!canConsumeAnimalProp) {
                return false;
            }
            int consumePropType = consumeAnimalPropType.getValue();
            if (consumePropType == ConsumeAnimalPropType.NONE) {
                Log.record("新版动物伙伴🦩未开启派遣动物伙伴，跳过");
                return false;
            }
            JSONObject jo = new JSONObject(AntForestRpcCall.queryMonopolyEntryInfo());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            if (jo.optBoolean("usingMonopolyCreature")) {
                Log.record("新版动物伙伴🦩已有动物在巡护，保留当前伙伴");
                return true;
            }
            JSONArray creatureList = jo.optJSONArray("creatureList");
            if (creatureList == null) {
                Log.record("新版动物伙伴🦩入口缺少动物列表，跳过");
                return false;
            }
            List<JSONObject> candidates = new ArrayList<>();
            boolean allHasInitialRobEnergy = true;
            for (int i = 0; i < creatureList.length(); i++) {
                JSONObject creature = creatureList.optJSONObject(i);
                if (creature == null || !isMonopolyCreatureIdle(creature)) {
                    continue;
                }
                candidates.add(creature);
                if (creature.isNull("initialRobEnergy")) {
                    allHasInitialRobEnergy = false;
                }
            }
            if (candidates.isEmpty()) {
                Log.record("新版动物伙伴🦩当前没有可派遣的动物");
                return false;
            }
            // 按"派遣动物伙伴"配置选人：默认顺序取第一个；按最多能量派遣时新版按预计产出最高选
            JSONObject selected = candidates.get(0);
            if (consumePropType == ConsumeAnimalPropType.QUANTITY && allHasInitialRobEnergy) {
                for (JSONObject candidate : candidates) {
                    if (candidate.optInt("initialRobEnergy") > selected.optInt("initialRobEnergy")) {
                        selected = candidate;
                    }
                }
            }
            String creatureCode = selected.getString("creatureCode");
            JSONObject assigned = new JSONObject(AntForestRpcCall.assignMonopolyCreature(creatureCode));
            if (MessageUtil.checkResultCode(TAG, assigned)) {
                Log.forest("新版动物伙伴🦩派遣[" + selected.optString("creatureName", creatureCode) + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "dispatchMonopolyAnimal err:", t);
        }
        return false;
    }

    /* 新版动物是否空闲可派遣 */
    private boolean isMonopolyCreatureIdle(JSONObject creature) {
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

    // 查询可派遣伙伴（分页取全，返回是否派遣成功）
    private boolean queryAnimalPropList() {
        try {
            boolean sequence = consumeAnimalPropType.getValue() == ConsumeAnimalPropType.SEQUENCE;
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
            return consumeAnimalProp(animalProp);
        } catch (Throwable t) {
            Log.err(TAG, "queryAnimalPropList err:", t);
        }
        return false;
    }

    // 动物道具每日可获能量（"按最多能量派遣"按此最高选择，字段取自 partner.robAbility.robEnergyInDaily）
    private int animalPropEnergy(JSONObject animalProp) {
        JSONObject partner = animalProp.optJSONObject("partner");
        if (partner == null) {
            return 0;
        }
        JSONObject robAbility = partner.optJSONObject("robAbility");
        return robAbility == null ? 0 : robAbility.optInt("robEnergyInDaily", 0);
    }

    // 派遣伙伴，返回是否派遣成功
    private boolean consumeAnimalProp(JSONObject animalProp) {
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

    private int forFriendCollectEnergy(String targetUserId, long bubbleId) {
        int helped = 0;
        try {
            String s = AntForestRpcCall.forFriendCollectEnergy(targetUserId, bubbleId);
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray jaBubbles = jo.getJSONArray("bubbles");
                for (int i = 0; i < jaBubbles.length(); i++) {
                    jo = jaBubbles.getJSONObject(i);
                    helped += jo.getInt("collectedEnergy");
                }
                if (helped > 0) {
                    Log.forest("帮收能量🧺[" + UserIdMap.getMaskName(targetUserId) + "]#" + helped + "g");
                    totalHelpCollected += helped;
                    Statistics.addData(Statistics.DataType.HELPED, helped);
                } else {
                    Log.forest("帮[" + UserIdMap.getMaskName(targetUserId) + "]收取失败");
                    Log.i(TAG, "帮收取失败详情: UserID=" + targetUserId + ", BubbleId=" + bubbleId);
                }
            } else {
                Log.forest("[" + UserIdMap.getMaskName(targetUserId) + "]" + jo.getString("resultDesc"));
                Log.i(TAG, "帮收取响应: " + s);
            }
        } catch (Throwable t) {
            Log.err(TAG, "forFriendCollectEnergy err:", t);
        }
        return helped;
    }

    public static JSONArray getForestPropVOList() {
        JSONArray forestPropVOList = new JSONArray();
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryPropList(false));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                JSONArray propList = jo.optJSONArray("forestPropVOList");
                if (propList != null) {
                    forestPropVOList = propList;
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "getForestPropVOList err:", th);
        }
        return forestPropVOList;
    }

    // 获取道具组全部道具
    public static List<JSONObject> getPropGroup(JSONArray forestPropVOList, String propGroup) {
        List<JSONObject> list = new ArrayList<>();
        try {
            for (int i = 0; i < forestPropVOList.length(); i++) {
                JSONObject forestPropVO = forestPropVOList.getJSONObject(i);
                if (forestPropVO.getString("propGroup").equals(propGroup)) {
                    list.add(forestPropVO);
                }
            }
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject jsonObject1, JSONObject jsonObject2) {
                    try {
                        int durationTime1 = jsonObject1.getJSONObject("propConfigVO").getInt("durationTime");
                        int durationTime2 = jsonObject2.getJSONObject("propConfigVO").getInt("durationTime");
                        boolean hasExpireTime1 = jsonObject1.has("recentExpireTime");
                        boolean hasExpireTime2 = jsonObject2.has("recentExpireTime");
                        if (hasExpireTime1 && hasExpireTime2) {
                            long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(durationTime1);
                            long recentExpireTime = jsonObject2.getLong("recentExpireTime");
                            if (endTime < recentExpireTime) {
                                return -1;
                            } else {
                                return durationTime2 - durationTime1;
                            }
                        } else if (!hasExpireTime1 && !hasExpireTime2) {
                            return durationTime1 - durationTime2;
                        } else {
                            return hasExpireTime1 ? -1 : 1;
                        }
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        } catch (Throwable th) {
            Log.err(TAG, "getPropGroup err:", th);
        }
        return list;
    }

    /*
     * 查找背包道具
     * prop
     * propGroup, propType, holdsNum, propIdList[], propConfigVO[propName]
     */
    private JSONObject getForestPropVO(JSONArray forestPropVOList, String propType) {
        try {
            for (int i = 0; i < forestPropVOList.length(); i++) {
                JSONObject forestPropVO = forestPropVOList.getJSONObject(i);
                if (forestPropVO.getString("propType").equals(propType)) {
                    return forestPropVO;
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "getForestPropVO err:", th);
        }
        return null;
    }

    /*
     * 使用背包道具
     * prop
     * propGroup, propType, holdsNum, propIdList[], propConfigVO[propName]
     */
    public static Boolean consumeProp(JSONObject prop) {
        try {
            // 使用道具
            String propId = prop.getJSONArray("propIdList").getString(0);
            String propType = prop.getString("propType");
            String propGroup = prop.getString("propGroup");
            String propName = prop.getJSONObject("propConfigVO").getString("propName");
            return consumeProp(propGroup, propId, propType, propName);
        } catch (Throwable th) {
            Log.err(TAG, "consumeProp err:", th);
        }
        return false;
    }

    private static Boolean consumeProp(String propGroup, String propId, String propType, String propName) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.consumeProp(propGroup, propId, propType));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Log.forest("使用道具🎭[" + propName + "]");
                return true;
            }
        } catch (Throwable th) {
            Log.err(TAG, "consumeProp err:", th);
        }
        return false;
    }

    /** 活力值商店的分类：官方按这 5 个 labelType 分别拉取，少查一个列表就不全 */
    private static final String[] VITALITY_LABEL_TYPES = {"", "SC_ASSETS", "SKIN", "JEWELRY", "OTHER"};

    /** 单分类翻页上限：hasMore 异常一直为真时兜底 */
    private static final int VITALITY_ITEM_MAX_PAGES = 10;

    /** 活力值商店列表拉取失败的当日重试上限：服务端持续失败时避免每轮都重拉（单轮最多 5 分类 × 10 页） */
    private static final int VITALITY_SKU_RETRY_MAX = 3;

    /** 活力值商店列表拉取失败次数的当日计数 key */
    private static final String FLAG_VITALITY_SKU_RETRY = "forest::vitalitySkuListRetry";

    /**
     * 获取活力值商店所有商品信息：按 5 个 labelType 分别拉取并用响应里的 hasMore 翻页
     * （只查 SC_ASSETS 首页会漏掉大部分权益）。
     * <p>当日标记的判断放在方法内部，因为除了 vitalityExchangeBenefit() 外，
     * useEnergyRainCard()/兑换道具等路径也会直接调用它，那些路径同样需要"当日只拉一次"。
     *
     * @return 是否真的取到 SKU（取不到时留给下一轮重试，但当日重试次数有上限）
     */
    private boolean getAllSkuInfo() {
        try {
            // 当日标记只管「今天拉过」；本进程内存里的清单为空（如进程重启）时必须重拉，
            // 否则调用方会把配置里的每个 skuId 都当成失效并当天拉黑
            if (Status.hasFlagToday("forest::vitalitySkuList") && !skuInfo.isEmpty()) {
                // 当日标记只在完整拉取后才落盘，命中即代表清单完整；不同步会让失效 skuId 的去重路径失效
                skuListComplete = true;
                return true;
            }
            // 失败重试上限：服务端持续失败时避免每轮都重拉（单轮最多 5 分类 × 10 页）
            int retries = Status.getIntFlagToday(FLAG_VITALITY_SKU_RETRY);
            if (retries >= VITALITY_SKU_RETRY_MAX) {
                return false;
            }
            boolean complete = true;
            int got = 0;
            for (String labelType : VITALITY_LABEL_TYPES) {
                int cnt = 0;
                for (int page = 0; page < VITALITY_ITEM_MAX_PAGES; page++) {
                    JSONObject jo = new JSONObject(AntForestRpcCall.itemList(labelType, page * AntForestRpcCall.VITALITY_ITEM_PAGE_SIZE));
                    if (!MessageUtil.checkSuccess(TAG, jo)) {
                        complete = false;
                        break;
                    }
                    JSONArray itemInfoVOList = optItemInfoVOList(jo);
                    if (itemInfoVOList == null || itemInfoVOList.length() == 0) {
                        break;
                    }
                    for (int i = 0; i < itemInfoVOList.length(); i++) {
                        JSONObject spuItem = itemInfoVOList.optJSONObject(i);
                        if (spuItem == null) {
                            continue;
                        }
                        // 只统计真正入表的条数：解析失败不该被当成「拉到了」
                        if (getSkuInfoByItemInfoVO(spuItem)) {
                            cnt++;
                        }
                    }
                    if (!hasMore(jo)) {
                        break;
                    }
                }
                got += cnt;
                Log.i("活力值商店列表：[" + (labelType.isEmpty() ? "全部" : labelType) + "]取到" + cnt + "条");
            }
            VitalityBenefitIdMap.save(UserIdMap.getCurrentUid());
            Log.i("活力值商店列表：共取" + got + "条，清单共" + VitalityBenefitIdMap.getMap().size() + "条");
            skuListComplete = got > 0 && complete;
            if (got > 0) {
                if (complete) {
                    // 完整拉到才打当日标记：部分分类失败时打标会把残缺清单固化一整天
                    Status.flagToday("forest::vitalitySkuList");
                } else {
                    // 清单残缺：清掉可能残留的旧当日标记（升级前旧逻辑 got>0 就置标记），否则下一轮
                    // 早退分支会把这份残缺清单当成「完整」并置 skuListComplete，缺失的 skuId 就会被整天拉黑
                    Status.clearFlag("forest::vitalitySkuList");
                    // 计入重试次数留待下一轮；达到上限后本方法返回 false，调用方据此不判 skuId 失效
                    accountVitalitySkuRetry();
                }
                return true;
            }
            accountVitalitySkuRetry();
            return false;
        } catch (Throwable th) {
            Log.err(TAG, "getAllSkuInfo err:", th);
            skuListComplete = false;
            accountVitalitySkuRetry();
            return false;
        }
    }

    /** 活力值商店列表拉取失败计数：达上限后当日不再重拉，避免每轮都重试 */
    private static void accountVitalitySkuRetry() {
        int tries = Status.getIntFlagToday(FLAG_VITALITY_SKU_RETRY) + 1;
        Status.setIntFlagToday(FLAG_VITALITY_SKU_RETRY, tries);
        if (tries >= VITALITY_SKU_RETRY_MAX) {
            Log.i("活力值商店列表：连续" + tries + "次未取到，今日不再重试");
        }
    }

    /** 商品列表可能在顶层、也可能在 resData 下（抓包两层都出现过），两层都取 */
    private static JSONArray optItemInfoVOList(JSONObject jo) {
        JSONArray list = jo.optJSONArray("itemInfoVOList");
        if (list == null) {
            JSONObject resData = jo.optJSONObject("resData");
            if (resData != null) {
                list = resData.optJSONArray("itemInfoVOList");
            }
        }
        return list;
    }

    /** hasMore 表示还有下一页，同样两层都取 */
    private static boolean hasMore(JSONObject jo) {
        if (jo.has("hasMore")) {
            return jo.optBoolean("hasMore", false);
        }
        JSONObject resData = jo.optJSONObject("resData");
        return resData != null && resData.optBoolean("hasMore", false);
    }

    private void getSkuInfoBySpuId(String spuId) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.itemDetail(spuId));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONObject spuItemInfoVo = jo.getJSONObject("spuItemInfoVO");
            getSkuInfoByItemInfoVO(spuItemInfoVo);
            VitalityBenefitIdMap.save(UserIdMap.getCurrentUid());
        } catch (Throwable th) {
            Log.err(TAG, "getSkuInfoBySpuId err:", th);
        }
    }

    /**
     * 单个 SPU 的 SKU 入表；落盘由调用方整批结束后统一 save，避免逐条写文件。
     *
     * @return 是否至少入表一个 SKU——调用方据「成功条数」判是否拉到了清单，
     * 否则解析全失败也会被当成成功、打上当日标记
     */
    private boolean getSkuInfoByItemInfoVO(JSONObject spuItem) {
        try {
            String spuId = spuItem.optString("spuId");
            if (spuId.isEmpty()) {
                return false;
            }
            JSONArray skuModelList = spuItem.optJSONArray("skuModelList");
            if (skuModelList == null || skuModelList.length() == 0) {
                return false;
            }
            int added = 0;
            for (int i = 0; i < skuModelList.length(); i++) {
                JSONObject skuModel = skuModelList.optJSONObject(i);
                if (skuModel == null) {
                    continue;
                }
                String skuId = skuModel.optString("skuId");
                String skuName = skuModel.optString("skuName");
                if (skuId.isEmpty()) {
                    continue;
                }
                if (!skuModel.has("spuId")) {
                    skuModel.put("spuId", spuId);
                }
                skuInfo.put(skuId, skuModel);
                VitalityBenefitIdMap.add(skuId, skuName);
                added++;
            }
            return added > 0;
        } catch (Throwable th) {
            Log.err(TAG, "getSkuInfoByItemInfoVO err:", th);
            return false;
        }
    }

    /** 清单不可用时的过程日志：每天只提示一次，避免每轮刷屏（不计动作数，也不打 skuId 失效标记） */
    private static void warnSkuListUnavailableOnce() {
        if (!Status.hasFlagToday("forest::vitalitySkuListWarn")) {
            Log.i(TAG, "活力兑换⏭️商品清单未取到，本轮跳过（不判定 skuId 失效）");
            Status.flagToday("forest::vitalitySkuListWarn");
        }
    }

    /*
     * 兑换活力值商品
     * sku
     * spuId, skuId, skuName, exchangedCount, price[amount]
     * exchangedCount == 0......
     */
    private Boolean exchangeBenefit(String skuId) {
        // 清单为空、或上次只拉到一部分，都要重拉：残缺清单会漏掉配置里的 skuId
        if (skuInfo.isEmpty() || !skuListComplete) {
            getAllSkuInfo();
        }
        JSONObject sku = skuInfo.get(skuId);
        if (sku == null) {
            // 清单没取到或没取全（拉取失败、当日标记命中但内存为空）：无法判定 skuId 是否失效，
            // 不能拉黑，否则配置里的每项都会被当天判死，整天只刷「找不到要兑换的权益」
            if (skuInfo.isEmpty() || !skuListComplete) {
                warnSkuListUnavailableOnce();
                return false;
            }
            // 清单完整且不含该 skuId：失效 skuId（权益下架/换号/服务端改 ID），打当日标记当天不再重试
            if (!Status.hasFlagToday("forest::exchangeSkuMissing::" + skuId)) {
                Log.record("活力兑换🎐找不到要兑换的权益！" + skuId + "#今日不再重试");
                Status.flagToday("forest::exchangeSkuMissing::" + skuId);
            }
            return false;
        }
        try {
            String skuName = sku.getString("skuName");
            JSONArray itemStatusList = sku.getJSONArray("itemStatusList");
            for (int i = 0; i < itemStatusList.length(); i++) {
                String itemStatus = itemStatusList.getString(i);
                if (ItemStatus.REACH_LIMIT.name().equals(itemStatus) || ItemStatus.NO_ENOUGH_POINT.name().equals(itemStatus) || ItemStatus.NO_ENOUGH_STOCK.name().equals(itemStatus)) {
                    Log.record("活力兑换🎐[" + skuName + "]停止:" + ItemStatus.valueOf(itemStatus).nickName());
                    if (ItemStatus.REACH_LIMIT.name().equals(itemStatus)) {
                        Status.flagToday("forest::exchangeLimit::" + skuId);
                    }
                    return false;
                }
            }
            String spuId = sku.getString("spuId");
            if (exchangeBenefit(spuId, skuId, skuName)) {
                return true;
            }
            getSkuInfoBySpuId(spuId);
        } catch (Throwable th) {
            Log.err(TAG, "exchangeBenefit err:", th);
        }
        return false;
    }

    public static Boolean exchangeBenefit(String spuId, String skuId, String skuName) {
        try {
            if (exchangeBenefit(spuId, skuId)) {
                Status.vitalityExchangeBenefitToday(skuId);
                int exchangedCount = Status.getVitalityExchangeBenefitCountToday(skuId);
                Log.forest("活力兑换🎐[" + skuName + "]#第" + exchangedCount + "次");
                return true;
            }
        } catch (Throwable th) {
            Log.err(TAG, "exchangeBenefit err:", th);
        }
        return false;
    }

    private static Boolean exchangeBenefit(String spuId, String skuId) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.exchangeBenefit(spuId, skuId));
            if (jo.has("errorMessage")) {
                String errorMessage = jo.optString("errorMessage");
                // 系统繁忙是临时故障：按次计数，用尽才当日停（见 VITALITY_EXCHANGE_RETRY_MAX）
                if (errorMessage.equals("系统繁忙，请稍后再试。")) {
                    String retryKey = "forest::exchangeRetry::" + skuId;
                    int tries = Status.getIntFlagToday(retryKey) + 1;
                    Status.setIntFlagToday(retryKey, tries);
                    if (tries >= VITALITY_EXCHANGE_RETRY_MAX) {
                        Status.flagToday("forest::exchangeLimit::" + skuId);
                        Log.record("活力兑换🎐系统繁忙连续" + tries + "次，今日不再重试");
                    }
                }
            }
            return MessageUtil.checkResultCode(TAG, jo);
        } catch (Throwable th) {
            Log.err(TAG, "exchangeBenefit err:", th);
        }
        return false;
    }

    private void teamCooperateWater() {
        // 记录本次是否由本方法切到组队模式，只要是就得切回来
        boolean switchedToTeam = false;
        try {
            int userDailyTarget = Math.min(Math.max(partnerteamWaterNum.getValue(), 10), 5000);
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
            if (switchedToTeam && !teamPriority.getValue()) {
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

    /**
     * 组队合种浇水会临时把账号切到组队模式。若上次切回失败、或进程在切回前被杀，
     * 这里做一次校正，避免用户被永久留在组队模式（会影响个人主页 / 好友页的表现）。
     * <p>
     * 没开「组队合种浇水」也会执行：正是"开了功能 → 切换中途中断 → 用户随后把开关关掉"这种情况最需要校正。
     */
    private void fixTeamModeIfNeeded() {
        // 组队优先时组队是预期状态，不做"切回个人"的校正（否则刚切过去又被切回来）
        if (teamPriority.getValue()) {
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

    //needReturn:false切回个人模式，true切到组队模式
    private static boolean updateUserConfiginTeam(Boolean needReturn) {
        try {
            String updateStr = AntForestRpcCall.updateUserConfiginTeam(needReturn);
            JSONObject updateJo = new JSONObject(updateStr);
            if (!MessageUtil.checkResultCode(TAG, updateJo)) {
                Log.record("updateUserConfig 返回异常");
                return false;
            } else {
                Log.record("模式切换成功：" + (needReturn ? "切到组队模式" : "切到个人模式"));
                return true;
            }
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
    private static boolean isTeam(JSONObject homeObj) {
        if (homeObj == null) {
            return false;
        }
        JSONObject teamHomeResult = homeObj.optJSONObject("teamHomeResult");
        return teamHomeResult != null && teamHomeResult.optJSONObject("mainMember") != null;
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

    private static boolean updateUserConfigEnergyPvp() {
        try {
            String Str = AntForestRpcCall.queryUserTag();
            JSONObject Jo = new JSONObject(Str);
            if (!MessageUtil.checkResultCode(TAG, Jo)) {
                Log.record("queryUserTag 查询1V1状态返回异常");
                return false;
            }
            if (Jo.has("tagMap")) {
                JSONObject tagMap = Jo.getJSONObject("tagMap");
                if (!tagMap.has("energyPvp")) {
                    Log.record("查询1V1无状态返回[无法参加]若出现标识立马为大人开启");
                    return false;
                }
                String energyPvp = tagMap.getString("energyPvp");
                if (energyPvp.equals("Y")) {
                    return true;
                }
            }
            //自动开启挑战
            String updateStr = AntForestRpcCall.updateUserConfigEnergyPvp(true);
            JSONObject updateJo = new JSONObject(updateStr);
            if (!MessageUtil.checkResultCode(TAG, updateJo)) {
                Log.record("updateUserConfigEnergyPvp 返回异常");
            } else {
                Log.forest("比赛情况🆚1V1能量挑战成功开启");
                return true;
            }
            return false;
        } catch (Throwable th) {
            Log.err(TAG, "updateUserConfigEnergyPvp err:", th);
        }
        return false;
    }

    private static void queryPvpHomeInfo() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryPvpHomeInfo());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (jo.has("currentEnergyPvpBattleRecord")) {
                JSONObject currentEnergyPvpBattleRecord = jo.getJSONObject("currentEnergyPvpBattleRecord");
                //获取1v1比赛情况
                String attackerDisplayName = currentEnergyPvpBattleRecord.optString("attackerDisplayName");
                int attackerEnergy = currentEnergyPvpBattleRecord.optInt("attackerEnergy");
                int attackerWinCount = currentEnergyPvpBattleRecord.optInt("attackerWinCount");
                String battleType = currentEnergyPvpBattleRecord.optString("battleType");
                String defenderDisplayName = currentEnergyPvpBattleRecord.optString("defenderDisplayName");
                int defenderEnergy = currentEnergyPvpBattleRecord.optInt("defenderEnergy");
                int defenderWinCount = currentEnergyPvpBattleRecord.optInt("defenderWinCount");
                String battleStatus = currentEnergyPvpBattleRecord.optString("battleStatus");
                Log.record("比赛情况🆚" + battleType + "赛" + battleStatus + "[" + attackerDisplayName + "]" + attackerWinCount + "胜(" + attackerEnergy + "g)VS[" + defenderDisplayName + "]" + attackerWinCount + "胜(" + defenderEnergy + "g)");
                //领取奖励
                if (currentEnergyPvpBattleRecord.optBoolean("hasReward")) {
                    jo = new JSONObject(AntForestRpcCall.receivePvpRewards());
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        return;
                    }
                    if (jo.has("receivedRewards")) {
                        JSONArray receivedRewards = jo.getJSONArray("receivedRewards");
                        for (int i = 0; i < receivedRewards.length(); i++) {
                            JSONObject reward = receivedRewards.getJSONObject(i);
                            String rewardName = reward.getString("rewardName");
                            String rewardType = reward.getString("rewardType");
                            if ("energy".equals(rewardType)) {
                                int energy = reward.getInt("energy");
                                Log.forest("领取奖励🎖️1V1[" + rewardName + "]");
                                Statistics.addData(Statistics.DataType.COLLECTED, energy);
                            } else {
                                Log.forest("领取奖励🎖️1V1[" + rewardName + "]");
                            }
                        }
                    }

                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "queryPvpHomeInfo err:", th);
        }

    }

    private static void receivePvpRewards() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.checkRewardqueryMiscInfo());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (jo.has("combineHandlerVOMap")) {
                JSONObject combineHandlerVOMap = jo.getJSONObject("combineHandlerVOMap");
                if (combineHandlerVOMap.has("energyPvpInfo")) {
                    JSONObject energyPvpInfo = combineHandlerVOMap.getJSONObject("energyPvpInfo");
                    //领取奖励
                    if (energyPvpInfo.optBoolean("hasReward")) {
                        jo = new JSONObject(AntForestRpcCall.receivePvpRewards());
                        if (!MessageUtil.checkResultCode(TAG, jo)) {
                            return;
                        }
                        if (jo.has("receivedRewards")) {
                            JSONArray receivedRewards = jo.getJSONArray("receivedRewards");
                            for (int i = 0; i < receivedRewards.length(); i++) {
                                JSONObject reward = receivedRewards.getJSONObject(i);
                                String rewardName = reward.getString("rewardName");
                                String rewardType = reward.getString("rewardType");
                                if ("energy".equals(rewardType)) {
                                    int energy = reward.getInt("energy");
                                    Log.forest("领取奖励🎖️1V1[" + rewardName + "]");
                                    Statistics.addData(Statistics.DataType.COLLECTED, energy);
                                } else {
                                    Log.forest("领取奖励🎖️1V1[" + rewardName + "]");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "receivePvpRewards err:", th);
        }
    }


    private void dress() {
        String dressDetail = dressDetailList.getValue();
        if (dressDetail.isEmpty()) {
            setDressDetail(getDressDetail().toString());
        } else {
            checkDressDetail(dressDetail);
        }
    }

    private JSONObject getDressDetail() {
        JSONObject dressDetail = new JSONObject();
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryHomePage());
            JSONObject indexDressVO = jo.optJSONObject("indexDressVO");
            JSONArray ja = indexDressVO == null ? null : indexDressVO.optJSONArray("dressDetailList");
            if (ja != null) {
                for (int i = 0; i < ja.length(); i++) {
                    jo = ja.optJSONObject(i);
                    if (jo == null) {
                        continue;
                    }
                    String position = jo.optString("position", "");
                    String batchType = jo.optString("batchType", "");
                    if (!position.isEmpty()) {
                        dressDetail.put(position, batchType);
                    }
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "getDressDetail err:", th);
        }
        return dressDetail;
    }

    private void setDressDetail(String dressDetail) {
        dressDetailList.setValue(dressDetail);
        if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
            Log.forest("装扮保护🔐皮肤保存,芝麻粒将为你持续保护!");
        }
    }

    private void removeDressDetail(String position) {
        JSONObject jo = getDressDetail();
        jo.remove(position);
        setDressDetail(jo.toString());
    }

    private void checkDressDetail(String dressDetail) {
        String[] positions = {"tree__main", "bg__sky_0", "bg__sky_cloud", "bg__ground_a", "bg__ground_b", "bg__ground_c"};
        try {
            boolean isDressExchanged = false;
            JSONObject jo = new JSONObject(dressDetail);
            for (String position : positions) {
                String batchType = "";
                if (jo.has(position)) {
                    batchType = jo.getString(position);
                }
                if (queryUserDressForBackpack(dressMap.get(position), batchType)) {
                    isDressExchanged = true;
                }
            }
            if (isDressExchanged) {
                Log.forest("装扮保护🔐皮肤修改,芝麻粒已为你自动恢复!");
            }
        } catch (Throwable th) {
            Log.err(TAG, "checkDressDetail err:", th);
        }
    }

    private Boolean queryUserDressForBackpack(String positionType, String batchType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.listUserDressForBackpack(positionType));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            JSONArray userHoldDressVOList = jo.getJSONArray("userHoldDressVOList");
            boolean isTakeOff = false;
            for (int i = 0; i < userHoldDressVOList.length(); i++) {
                jo = userHoldDressVOList.getJSONObject(i);
                if (jo.optInt("remainNum", 1) == 0) {
                    if (batchType.equals(jo.getString("batchType"))) {
                        return false;
                    }
                    String position = jo.getJSONArray("posList").getString(0);
                    isTakeOff = takeOffDress(jo.getString("dressType"), position);
                } else if (batchType.equals(jo.getString("batchType"))) {
                    return wearDress(jo.getString("dressType"));
                }
            }

            if (!batchType.isEmpty()) {
                removeDressDetail(dressMap.get(positionType));
                Log.forest("装扮保护🔐皮肤过期,芝麻粒已为你恢复默认!");
            }
            return isTakeOff;
        } catch (Throwable th) {
            Log.err(TAG, "queryUserDressForBackpack err:", th);
        }
        return false;
    }

    private Boolean wearDress(String dressType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.wearDress(dressType));
            return MessageUtil.checkResultCode(TAG, jo);
        } catch (Throwable th) {
            Log.err(TAG, "wearDress err:", th);
        }
        return false;
    }

    private Boolean takeOffDress(String dressType, String position) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.takeOffDress(dressType, position));
            return MessageUtil.checkResultCode(TAG, jo);
        } catch (Throwable th) {
            Log.err(TAG, "takeOffDress err:", th);
        }
        return false;
    }

    /**
     * The enum Collect status.
     */
    public enum CollectStatus {
        /**
         * Available collect status.
         */
        AVAILABLE,
        /**
         * Waiting collect status.
         */
        WAITING,
        /**
         * Insufficient collect status.
         */
        INSUFFICIENT,
        /**
         * Robbed collect status.
         */
        ROBBED
    }

    /**
     * The type Bubble timer task.
     */
    private class BubbleTimerTask extends ChildModelTask {

        /**
         * The User id.
         */
        private final String userId;

        /**
         * The Bubble id.
         */
        private final long bubbleId;

        /**
         * The ProduceTime.
         */
        private final long produceTime;
        private final String userName;

        /**
         * Instantiates a new Bubble timer task.
         */
        BubbleTimerTask(String ui, long bi, long pt, String un) {
            super(AntForestV2.getBubbleTimerTid(ui, bi), pt - advanceTimeInt);
            userId = ui;
            bubbleId = bi;
            produceTime = pt;
            userName = un;
        }

        @Override
        public Runnable setRunnable() {
            return () -> {
                //String userName = UserIdMap.getMaskName(userId);
                int averageInteger = offsetTimeMath.getAverageInteger();
                long readyTime = produceTime - advanceTimeInt + averageInteger - delayTimeMath.getAverageInteger() - System.currentTimeMillis() + 70;
                if (readyTime > 0) {
                    try {
                        Thread.sleep(readyTime);
                    } catch (InterruptedException e) {
                        Log.i("终止[" + userName + "]蹲点收取任务, 任务ID[" + getId() + "]");
                        return;
                    }
                }
                Log.record("执行蹲点收取[" + userName + "]" + "时差[" + averageInteger + "]ms" + "提前[" + advanceTimeInt + "]ms");
                collectEnergy(new CollectEnergyEntity(userId, null, AntForestRpcCall.getCollectEnergyRpcEntity(null, userId, bubbleId)), userName);
            };
        }
    }

    public static String getBubbleTimerTid(String ui, long bi) {
        return "BT|" + ui + "|" + bi;
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

    public interface ConsumeAnimalPropType {

        int NONE = 0;
        int SEQUENCE = 1;
        int QUANTITY = 2;

        String[] nickNames = {"不派遣动物", "按默认顺序派遣", "按最多能量派遣"};
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

    public interface whackModeNames {
        int CLOSE = 0;
        int WHACK_MODE_COMPATIBLE = 1;
        int WHACK_MODE_AGGRESSIVE = 2;
        String[] nickNames = {"关闭", "兼容模式", "激进模式"};
    }
}
