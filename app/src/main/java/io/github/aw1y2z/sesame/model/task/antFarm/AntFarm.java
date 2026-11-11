package io.github.aw1y2z.sesame.model.task.antFarm;

import android.os.Build;

import io.github.aw1y2z.sesame.entity.AlipayAntFarmDoFarmTaskList;
import io.github.aw1y2z.sesame.entity.AlipayAntFarmDrawMachineTaskList;
import io.github.aw1y2z.sesame.entity.AlipayAntKitchenTaskList;
import io.github.aw1y2z.sesame.entity.GameCenterMallItem;
import io.github.aw1y2z.sesame.model.task.antGame.GameTask;
import io.github.aw1y2z.sesame.util.idMap.AntFarmDoFarmTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntFarmDrawMachineTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.GameCenterMallItemMap;
import lombok.Getter;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.data.*;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.TokenConfig;
import io.github.aw1y2z.sesame.data.modelFieldExt.*;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAward;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy.Outcome;
import io.github.aw1y2z.sesame.data.modelFieldExt.ChoiceModelField;
import io.github.aw1y2z.sesame.entity.AlipayUser;
import io.github.aw1y2z.sesame.entity.CustomOption;
import io.github.aw1y2z.sesame.entity.FarmOrnaments;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.model.extensions.ExtensionsHandle;
import io.github.aw1y2z.sesame.model.normal.answerAI.AnswerAI;
import io.github.aw1y2z.sesame.rpc.intervallimit.RpcIntervalLimit;
import io.github.aw1y2z.sesame.util.*;
import io.github.aw1y2z.sesame.util.idMap.FarmOrnamentsIdMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class AntFarm extends ModelTask {
    private static final String TAG = AntFarm.class.getSimpleName();
    /** 家庭分享：当日累计"邀请全部失败"次数 */
    private static final String FLAG_FAMILY_SHARE_FAIL_COUNT = "antFarm::familyShareToFriends::failCount";
    /** 家庭分享：当日最多尝试几次，超过后当天不再重试（避免每轮任务都重发邀请请求） */
    private static final int MAX_FAMILY_SHARE_ATTEMPT = 3;
    /** 雇佣NPC：当天已处理过的 NPC 标记前缀（后接枚举名）；农场同时只有一只 NPC，按天标记避免每轮遣返重雇 */
    private static final String FLAG_HIRE_NPC_DONE = "antFarm::hireNpcDone::";

    /**
     * 喂鸡「使用美食」：当天被服务端判定"食谱未配置"的菜品标记前缀（后接 cuisineId）。
     * <p>这类菜是服务端没配喂鸡效果（厨房赛季菜上线初期常见），发了只会回 U05；
     * 按天记忆后不再重复发，第二天自动重试——服务端配好后无需改代码就能喂上。
     */
    private static final String FLAG_UNUSABLE_CUISINE = "antFarm::unusableCuisineV2::";

    /**
     * 公益捐蛋当日标记：当天已经捐过一次公益蛋。只归「每日捐蛋」自己用。
     * <p>持久化 key，**不能改名**。
     */
    private static final String FLAG_CHARITY_DONATION_DONE = "farm::donation";

    /**
     * S2 捐蛋「今日已捐成」标记：只归「爱心鸡结号 | 自动捐蛋」自己用。
     * <p>持久化 key，**不能改名**。
     */
    private static final String FLAG_COMPETITION_DONATED_TODAY = "antFarm::competitionDonatedToday";

    /**
     * S2 捐蛋「今日已尝试」标记：每天最多给 S2 发一次捐蛋请求。
     * <p>有了它，响应没判定成功时不会在后续每轮运行里重发（那种情况由公益兜底）。
     * <p>持久化 key，**不能改名**。
     */
    private static final String FLAG_COMPETITION_DONATE_TRIED = "antFarm::competitionDonateTried";

    /**
     * 小鸡乐园「刷任务」当日已试次数（整型计数，取代原先的整日跳过标记）。
     * <p>上报成功后支付宝侧是**异步**入账的，而回读只做一次且紧跟上报，很容易把已入账的
     * 读成旧值；原先凭这一眼就打「今日不再刷」，当天剩下的宝箱额度就全丢了。
     * <p>改为按次计数：用尽才当日不再试，次日标记随 status.json 跨天清空即恢复。
     */
    private static final String FLAG_GAME_DRAW_TASK_TRY = "antFarm::gameDrawTaskTry";

    /** 刷任务当日最多试几次：既容服务端异步落态，也避免每轮都重发一遍任务上报 */
    private static final int MAX_GAME_DRAW_TASK_TRY = 5;

    /**
     * 小鸡乐园开箱+刷任务的同轮连做上限：上报拿到的新额度要当场开掉，
     * 免得白等下一轮任务运行。正常由「当日额度已满」或「上报未推进」提前收手，
     * 此上限只防服务端恒回可开时停不下来。
     */
    private static final int MAX_GAME_DRAW_ROUND = 6;

    /** 小鸡乐园单次对局的轮次上限：防 remainingGameCount 恒回正值时每 2s 无限重刷 */
    private static final int MAX_GAME_ROUNDS = 20;

    /** 投喂被服务端拒绝的「饲料槽已满」结果码（按码判据，不认文案） */
    private static final String CODE_FEED_TROUGH_FULL = "331";
    /** 小鸡所在空间标识：家庭空间。睡觉/起床靠它区分走家庭接口还是个人小屋接口 */
    private static final String SPACE_TYPE_CHICK_FAMILY = "ChickFamily";

    private String ownerFarmId;
    private String ownerUserId;
    private String ownerGroupId;
    private Animal[] animals;
    private Animal ownerAnimal = new Animal();
    private int foodStock;
    private int foodStockLimit;
    private String rewardProductNum;
    private RewardFriend[] rewardList;
    private double benevolenceScore;
    private double harvestBenevolenceScore;
    private int unReceiveTaskAward = 0;
    /**
     * 本轮是否已被服务端限流（领奖返回 102）。
     * <p>命中后本轮不再继续领其余饲料任务：服务端对领奖的 102 是临时性的，
     * 一轮里连着刷 3 次只是白刷（每轮开头重置，见 {@link #run()}）。
     */
    private boolean farmTaskAwardBusy = false;
    /**
     * 本轮投喂时服务端回了「饲料槽已满」(结果码见 CODE_FEED_TROUGH_FULL)：本轮不再投喂；
     * 槽满后每 10 秒级的重试只是白打接口，每轮开头重置 (见 run())。
     */
    private boolean feedTroughFullThisRun = false;
    /**
     * 本轮领奖时服务端回了「饲料槽已满」（同 {@link #CODE_FEED_TROUGH_FULL}）：本轮不再领其余饲料任务。
     * <p>槽满与任务无关，继续领只会逐个被拒、刷出一串相同的错误（每轮开头重置，见 {@link #run()}）。
     */
    private boolean awardTroughFullThisRun = false;
    private double finalScore = 0d;
    private int foodInTrough = 0;

    private FarmTool[] farmTools;

    @Override
    public String getName() {
        return "庄园";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.FARM;
    }

    private BooleanModelField AutoAntFarmDoFarmTaskList;
    private SelectModelField AntFarmDoFarmTaskList;
    private StringModelField sleepTime;
    private IntegerModelField sleepMinutes;
    private BooleanModelField enableSleep;      // 小鸡睡觉开关
    private BooleanModelField feedAnimal;
    private BooleanModelField rewardFriend;
    private ChoiceModelField sendBackAnimalWay;
    private ChoiceModelField sendBackAnimalType;
    private SelectModelField sendBackAnimalList;
    private ChoiceModelField recallAnimalType;
    private BooleanModelField receiveFarmToolReward;
    private BooleanModelField recordFarmGame;
    private ListModelField.ListJoinCommaToStringModelField farmGameTime;
    private BooleanModelField gameCenterBuyMallItem;
    private SelectAndCountModelField gameCenterBuyMallItemList;
    private BooleanModelField kitchen;
    private BooleanModelField aiKitchen;                       // 小鸡大厨(AI 厨房)
    private BooleanModelField aiKitchenCook;                   // 小鸡大厨 | 自动做美食
    private BooleanModelField aiKitchenFinishTask;             // 小鸡大厨 | 自动完成任务
    private BooleanModelField AutoAntKitchenTaskList;          // 小鸡大厨 | 自动黑名单
    private SelectModelField AntKitchenTaskList;               // 小鸡大厨 | 黑名单列表
    private BooleanModelField useSpecialFood;
    @Getter
    private IntegerModelField useSpecialFoodCountLimit;
    private BooleanModelField useNewEggTool;
    private BooleanModelField harvestProduce;
    private ChoiceModelField donationType;
    private IntegerModelField donationAmount;
    private BooleanModelField receiveFarmTaskAward;
    private BooleanModelField useAccelerateTool;
    private SelectModelField useAccelerateToolOptions;
    private BooleanModelField feedFriendAnimal;
    private SelectAndCountModelField feedFriendAnimalList;
    private ChoiceModelField notifyFriendType;
    private SelectModelField notifyFriendList;
    private BooleanModelField acceptGift;
    private SelectAndCountModelField visitFriendList;
    private BooleanModelField chickenDiary;
    private BooleanModelField drawMachine;
    private BooleanModelField AutoAntFarmDrawMachineTaskList;
    private SelectModelField AntFarmDrawMachineTaskList;
    private BooleanModelField IPexchangeBenefit;
    private BooleanModelField ornamentsDressUp;
    private SelectModelField ornamentsDressUpList;
    private IntegerModelField ornamentsDressUpDays;
    private BooleanModelField hireAnimalEnable;
    private ChoiceModelField hireAnimalType;
    private SelectModelField hireAnimalList;
    private SelectModelField hireNpcList;
    private BooleanModelField drawGameCenterAward;
    private BooleanModelField competition;                    // 爱心鸡结号(S2) | 开启
    private BooleanModelField competitionReceiveTask;          // 爱心鸡结号 | 领取奖励
    private BooleanModelField competitionDonate;               // 爱心鸡结号 | 自动捐蛋
    private BooleanModelField competitionStealRank;            // 爱心鸡结号 | 偷榜
    private IntegerModelField competitionStealMinutes;         // 爱心鸡结号 | 偷榜提前分钟数
    private IntegerModelField competitionStealLimit;           // 爱心鸡结号 | 偷榜捐献上限
    private IntegerModelField competitionDonateAmount;         // 爱心鸡结号 | 自动捐蛋数量
    private BooleanModelField useBigEaterTool;
    //private ChoiceModelField getFeedType;
    private SelectModelField getFeedList;
    private BooleanModelField family;
    private SelectModelField familyOptions;
    private SelectModelField notInviteList; // 新增：不邀请列表

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(receiveFarmTaskAward = new BooleanModelField("receiveFarmTaskAward", "饲料任务及奖励", false));
        modelFields.addField(AutoAntFarmDoFarmTaskList = new BooleanModelField("AutoAntFarmDoFarmTaskList", "庄园饲料 | 自动黑名单", true).setDependsOn("receiveFarmTaskAward"));
        modelFields.addField(AntFarmDoFarmTaskList = new SelectModelField("AntFarmDoFarmTaskList", "庄园饲料 | 黑名单列表", new LinkedHashSet<>(), AlipayAntFarmDoFarmTaskList::getList).setDependsOn("AutoAntFarmDoFarmTaskList"));
        modelFields.addField(useNewEggTool = new BooleanModelField("useNewEggTool", "新蛋卡 | 使用", false));
        modelFields.addField(useAccelerateTool = new BooleanModelField("useAccelerateTool", "加速卡 | 使用", false));
        modelFields.addField(useAccelerateToolOptions = new SelectModelField("useAccelerateToolOptions", "加速卡 | 选项", new LinkedHashSet<>(), CustomOption::getUseAccelerateToolOptions).setDependsOn("useAccelerateTool"));
        modelFields.addField(useBigEaterTool = new BooleanModelField("useBigEaterTool", "加饭卡 | 使用", false));
        modelFields.addField(useSpecialFood = new BooleanModelField("useSpecialFood", "特殊食品 | 使用", false));
        modelFields.addField(useSpecialFoodCountLimit = new IntegerModelField("useSpecialFoodCountLimit", "特殊食品 | " + "使用上限(无限:0)", 0).setDependsOn("useSpecialFood"));
        modelFields.addField(rewardFriend = new BooleanModelField("rewardFriend", "打赏好友", false));
        modelFields.addField(recallAnimalType = new ChoiceModelField("recallAnimalType", "召回小鸡", RecallAnimalType.ALWAYS, RecallAnimalType.nickNames));
        modelFields.addField(feedAnimal = new BooleanModelField("feedAnimal", "投喂小鸡", false));
        modelFields.addField(feedFriendAnimal = new BooleanModelField("feedFriendAnimal", "帮喂小鸡 | 开启", true));
        modelFields.addField(feedFriendAnimalList = new SelectAndCountModelField("feedFriendAnimalList", "帮喂小鸡 | " + "好友列表", new LinkedHashMap<>(), AlipayUser::getList, "请填写帮喂次数(每日)", 1, 100).setDependsOn("feedFriendAnimal"));
        modelFields.addField(hireAnimalEnable = new BooleanModelField("hireAnimal", "雇佣小鸡 | 开关", false));
        modelFields.addField(hireAnimalType = new ChoiceModelField("hireAnimalType", "雇佣小鸡 | 动作", HireAnimalType.NONE, HireAnimalType.nickNames).setDependsOn("hireAnimal"));
        modelFields.addField(hireAnimalList = new SelectModelField("hireAnimalList", "雇佣小鸡 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList).setDependsOn("hireAnimalType"));
        modelFields.addField(hireNpcList = new SelectModelField("hireNpcList", "雇佣小鸡 | NPC选项", new LinkedHashSet<>(), CustomOption::getAntFarmHireNpcOptions).setDependsOn("hireAnimal"));
        modelFields.addField(sendBackAnimalWay = new ChoiceModelField("sendBackAnimalWay", "遣返小鸡 | 方式", SendBackAnimalWay.NORMAL, SendBackAnimalWay.nickNames));
        modelFields.addField(sendBackAnimalType = new ChoiceModelField("sendBackAnimalType", "遣返小鸡 | 动作", SendBackAnimalType.NONE, SendBackAnimalType.nickNames));
        modelFields.addField(sendBackAnimalList = new SelectModelField("sendFriendList", "遣返小鸡 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList).setDependsOn("sendBackAnimalType"));
        modelFields.addField(notifyFriendType = new ChoiceModelField("notifyFriendType", "通知赶鸡 | 动作", NotifyFriendType.NONE, NotifyFriendType.nickNames));
        modelFields.addField(notifyFriendList = new SelectModelField("notifyFriendList", "通知赶鸡 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList).setDependsOn("notifyFriendType"));
        modelFields.addField(ornamentsDressUp = new BooleanModelField("ornamentsDressUp", "装扮焕新 | 开启", false));
        modelFields.addField(ornamentsDressUpList = new SelectModelField("ornamentsDressUpList", "装扮焕新 | 套装列表", new LinkedHashSet<>(), FarmOrnaments::getList).setDependsOn("ornamentsDressUp"));
        modelFields.addField(ornamentsDressUpDays = new IntegerModelField("ornamentsDressUpDays", "装扮焕新 | 焕新频率(天)", 7, 1, 30).setDependsOn("ornamentsDressUp"));
        modelFields.addField(drawMachine = new BooleanModelField("drawMachine", "装扮抽抽乐", false));
        modelFields.addField(AutoAntFarmDrawMachineTaskList = new BooleanModelField("AutoAntFarmDrawMachineTaskList", "抽抽乐 | 自动黑名单", true).setDependsOn("drawMachine"));
        modelFields.addField(AntFarmDrawMachineTaskList = new SelectModelField("AntFarmDrawMachineTaskList", "抽抽乐 | 黑名单列表", new LinkedHashSet<>(), AlipayAntFarmDrawMachineTaskList::getList).setDependsOn("AutoAntFarmDrawMachineTaskList"));
        modelFields.addField(IPexchangeBenefit = new BooleanModelField("IPexchangeBenefit", "抽抽乐兑换 | 开启", false));
        modelFields.addField(donationType = new ChoiceModelField("donationType", "每日捐蛋 | 方式", DonationType.ZERO, DonationType.nickNames));
        modelFields.addField(donationAmount = new IntegerModelField("donationAmount", "每日捐蛋 | 倍数(每项)", 1).setDependsOn("donationType"));
        modelFields.addField(competition = new BooleanModelField("competition", "爱心鸡结号 | 开启", false));
        modelFields.addField(competitionReceiveTask = new BooleanModelField("competitionReceiveTask", "爱心鸡结号 | 领取奖励", false).setDependsOn("competition"));
        modelFields.addField(competitionDonate = new BooleanModelField("competitionDonate", "爱心鸡结号 | 自动捐蛋", false).setDependsOn("competition"));
        modelFields.addField(competitionDonateAmount = new IntegerModelField("competitionDonateAmount", "爱心鸡结号 | 自动捐蛋数量", 5, 0, 1000).setDependsOn("competitionDonate"));
        modelFields.addField(competitionStealRank = new BooleanModelField("competitionStealRank", "爱心鸡结号 | 偷榜", false).setDependsOn("competition"));
        modelFields.addField(competitionStealMinutes = new IntegerModelField("competitionStealMinutes", "爱心鸡结号 | 偷榜提前分钟数", 30, 0, 240).setDependsOn("competitionStealRank"));
        modelFields.addField(competitionStealLimit = new IntegerModelField("competitionStealLimit", "爱心鸡结号 | 偷榜捐献上限(0不限)", 0, 0, 1000).setDependsOn("competitionStealRank"));
        modelFields.addField(family = new BooleanModelField("family", "亲密家庭 | 开启", false));
        modelFields.addField(familyOptions = new SelectModelField("familyOptions", "亲密家庭 | 选项", new LinkedHashSet<>(), CustomOption::getAntFarmFamilyOptions).setDependsOn("family"));
        modelFields.addField(notInviteList = new SelectModelField("notInviteList", "亲密家庭 | 不邀请列表", new LinkedHashSet<>(), AlipayUser::getList).setDependsOn("family"));
        modelFields.addField(enableSleep = new BooleanModelField("enableSleep", "小鸡睡觉 | 允许睡觉", false));
        modelFields.addField(sleepTime = new StringModelField("sleepTime", "小鸡睡觉 | 时间", "2001").setDependsOn("enableSleep"));
        modelFields.addField(sleepMinutes = new IntegerModelField("sleepMinutes", "小鸡睡觉 | 时长(分钟)", 10 * 59, 1, 10 * 60).setDependsOn("enableSleep"));
        modelFields.addField(recordFarmGame = new BooleanModelField("recordFarmGame", "小鸡乐园 | 游戏改分(星星球、登山赛、飞行赛、揍小鸡)", false));
        List<String> farmGameTimeList = new ArrayList<>();
        farmGameTimeList.add("2200-2400");
        modelFields.addField(farmGameTime = new ListModelField.ListJoinCommaToStringModelField("farmGameTime", "小鸡乐园 " + "| 游戏时间(范围)", farmGameTimeList).setDependsOn("recordFarmGame"));
        modelFields.addField(drawGameCenterAward = new BooleanModelField("drawGameCenterAward", "小鸡乐园 | 游戏宝箱", false));
        modelFields.addField(gameCenterBuyMallItem = new BooleanModelField("gameCenterBuyMallItem", "小鸡乐园 | 乐园集市", false));
        // 兑奖次数下限设为 1：勾选即至少兑奖 1 次，避免默认 0 导致勾选不生效
        modelFields.addField(gameCenterBuyMallItemList = new SelectAndCountModelField("gameCenterBuyMallItemList", "小鸡乐园 | 兑奖", new LinkedHashMap<>(), GameCenterMallItem::getList, "请填写兑奖次数(每日)", 1, 100).setDependsOn("gameCenterBuyMallItem"));
        modelFields.addField(kitchen = new BooleanModelField("kitchen", "小鸡厨房", false));
        modelFields.addField(aiKitchen = new BooleanModelField("aiKitchen", "小鸡大厨", false));
        modelFields.addField(aiKitchenCook = new BooleanModelField("aiKitchenCook", "小鸡大厨 | 自动做美食", false)
                .setDependsOn("aiKitchen"));
        // 只申报浏览/签到类任务，充值、游戏、社交类不会自动做
        modelFields.addField(aiKitchenFinishTask = new BooleanModelField("aiKitchenFinishTask", "小鸡大厨 | 自动完成任务", true)
                .setDependsOn("aiKitchen"));
        modelFields.addField(AutoAntKitchenTaskList = new BooleanModelField("AutoAntKitchenTaskList", "小鸡大厨 | 自动黑名单", true).setDependsOn("aiKitchen"));
        modelFields.addField(AntKitchenTaskList = new SelectModelField("AntKitchenTaskList", "小鸡大厨 | 黑名单列表", new LinkedHashSet<>(), AlipayAntKitchenTaskList::getList).setDependsOn("AutoAntKitchenTaskList"));
        modelFields.addField(chickenDiary = new BooleanModelField("chickenDiary", "小鸡日记", false));
        modelFields.addField(harvestProduce = new BooleanModelField("harvestProduce", "收取爱心鸡蛋", false));
        modelFields.addField(receiveFarmToolReward = new BooleanModelField("receiveFarmToolReward", "收取道具奖励", false));
        //modelFields.addField(getFeedType = new ChoiceModelField("getFeedType", "一起拿饲料 | 动作", GetFeedType.NONE, GetFeedType.nickNames));
        //modelFields.addField(getFeedList = new SelectModelField("getFeedList", "一起拿饲料 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(acceptGift = new BooleanModelField("acceptGift", "收麦子", false));
        modelFields.addField(visitFriendList = new SelectAndCountModelField("visitFriendList", "送麦子 | 好友列表", new LinkedHashMap<>(), AlipayUser::getList, "请填写赠送次数(每日)", 1, 100));
        return modelFields;
    }

    @Override
    public void boot(ClassLoader classLoader) {
        super.boot(classLoader);
        RpcIntervalLimit.addIntervalLimit("com.alipay.antfarm.enterFarm", 2000);
    }

    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.i("任务暂停⏸️蚂蚁庄园:当前为仅收能量时间");
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        try {
            farmTaskAwardBusy = false;
            feedTroughFullThisRun = false;
            awardTroughFullThisRun = false;
            if (enterFarm() == null) {
                return;
            }

            //初始任务列表
            if (!Status.hasFlagToday("BlackList::initAntFarm")) {
                initAntFarmTaskListMap(AutoAntFarmDoFarmTaskList.getValue(), AutoAntFarmDrawMachineTaskList.getValue(), drawMachine.getValue());
                Status.flagToday("BlackList::initAntFarm");
            }

            if (rewardFriend.getValue()) {
                rewardFriend();
            }

            if (sendBackAnimalType.getValue() != SendBackAnimalType.NONE) {
                sendBackAnimal();
            }

            if (!AnimalInteractStatus.HOME.name().equals(ownerAnimal.animalInteractStatus)) {
                if ("ORCHARD".equals(ownerAnimal.locationType)) {
                    Log.farm("庄园通知📣[你家的小鸡给拉去除草了！]");
                    JSONObject joRecallAnimal = new JSONObject(AntFarmRpcCall.orchardRecallAnimal(ownerAnimal.animalId, ownerAnimal.currentFarmMasterUserId));
                    int manureCount = joRecallAnimal.getInt("manureCount");
                    Log.farm("召回小鸡📣收获[" + manureCount + "g肥料]");
                } else {
                    syncAnimalStatusAtOtherFarm(ownerAnimal.currentFarmId);
                    boolean guest = false;
                    // 服务端可能下发枚举外的新状态：安全解析，未知值只记一条日志，不能让整轮任务（含后面的喂食）被异常带走
                    SubAnimalType subAnimalType = null;
                    try {
                        subAnimalType = SubAnimalType.valueOf(ownerAnimal.subAnimalType);
                    } catch (Throwable ignored) {
                    }
                    if (subAnimalType == null) {
                        Log.record("小鸡不在庄园" + " " + ownerAnimal.subAnimalType);
                    } else {
                        switch (subAnimalType) {
                            case GUEST:
                                guest = true;
                                Log.record("小鸡到好友家去做客了");
                                break;
                            case NORMAL:
                                Log.record("小鸡太饿，离家出走了");
                                break;
                            case PIRATE:
                                Log.record("小鸡外出探险了");
                                break;
                            case WORK:
                                Log.record("小鸡出去工作啦");
                                break;
                        }
                    }

                    boolean hungry = false;
                    String userName = UserIdMap.getMaskName(AntFarmRpcCall.farmId2UserId(ownerAnimal.currentFarmId));
                    AnimalFeedStatus feedStatus = null;
                    try {
                        feedStatus = AnimalFeedStatus.valueOf(ownerAnimal.animalFeedStatus);
                    } catch (Throwable ignored) {
                    }
                    if (feedStatus == AnimalFeedStatus.HUNGRY) {
                        hungry = true;
                        Log.record("小鸡在[" + userName + "]的庄园里挨饿");
                    } else if (feedStatus == AnimalFeedStatus.EATING) {
                        Log.record("小鸡在[" + userName + "]的庄园里吃得津津有味");
                    } else if (feedStatus == null) {
                        Log.record("小鸡状态未知" + " " + ownerAnimal.animalFeedStatus);
                    }

                    boolean recall = false;
                    switch ((int) recallAnimalType.getValue()) {
                        case RecallAnimalType.ALWAYS:
                            recall = true;
                            break;
                        case RecallAnimalType.WHEN_THIEF:
                            recall = !guest;
                            break;
                        case RecallAnimalType.WHEN_HUNGRY:
                            recall = hungry;
                            break;
                    }
                    if (recall) {
                        recallAnimal(ownerAnimal.animalId, ownerAnimal.currentFarmId, ownerFarmId, userName);
                        syncAnimalStatus(ownerFarmId);
                        // 召回是异步生效的：立刻 sync 可能还读到"在别家"，隔几秒补一次，免得本轮漏喂
                        if (!AnimalInteractStatus.HOME.name().equals(ownerAnimal.animalInteractStatus)) {
                            TimeUtil.sleep(3000);
                            syncAnimalStatus(ownerFarmId);
                        }
                    }
                }
            }

            if (receiveFarmToolReward.getValue()) {
                listFarmTool();
                receiveToolTaskReward();
            }

            if (recordFarmGame.getValue()) {
                long currentTimeMillis = System.currentTimeMillis();
                for (String time : farmGameTime.getValue()) {
                    if (TimeUtil.checkInTimeRange(currentTimeMillis, time)) {
                        recordFarmGame(GameType.starGame);
                        recordFarmGame(GameType.jumpGame);
                        recordFarmGame(GameType.flyGame);
                        recordFarmGame(GameType.hitGame);
                        break;
                    }
                }
            }

            if (gameCenterBuyMallItem.getValue()) {
                gameCenterBuyMallItem();
            }

            if (kitchen.getValue()) {
                collectDailyFoodMaterial(ownerUserId);
                collectDailyLimitedFoodMaterial();
                // 新增：判断小鸡是否在睡觉，如果在睡觉则跳过厨房操作
                if (AnimalFeedStatus.SLEEPY.name().equals(ownerAnimal.animalFeedStatus)) {
                    Log.record("小鸡正在睡觉🛌，跳过小鸡厨房👨🏻‍🍳制作");
                } else {
                    cook(ownerUserId);
                }
            }

            // 小鸡大厨（AI 厨房）：与上面的小鸡厨房是两套独立玩法，逻辑在 AntKitchen
            if (aiKitchen.getValue()) {
                AntKitchen.execute(aiKitchenCook.getValue(), aiKitchenFinishTask.getValue(),
                        AntKitchenTaskList.getValue(), AutoAntKitchenTaskList.getValue());
            }

            if (chickenDiary.getValue()) {
                queryChickenDiary("");
                queryChickenDiaryList();
            }

            if (useNewEggTool.getValue()) {
                useFarmTool(ownerFarmId, ToolType.NEWEGGTOOL);
                syncAnimalStatus(ownerFarmId);
            }

            if (harvestProduce.getValue() && benevolenceScore >= 1) {
                Log.record("有可收取的爱心鸡蛋");
                harvestProduce(ownerFarmId);
            }

            // 捐蛋每天一次，S2 优先：competition() 返回 true 表示"今天的捐蛋已由 S2 完成"（或今天已捐过），
            // 此时不再做公益；返回 false 表示 S2 没捐成（无活动/未开自动捐蛋/没蛋/取不到项目/请求失败），
            // 回退公益捐蛋，保证这一天至少捐出一次。
            if (competition.getValue()) {
                if (!competition()) {
                    if (donationType.getValue() != DonationType.ZERO) {
                        donation();
                    }
                }
            } else if (donationType.getValue() != DonationType.ZERO) {
                donation();
            }

            if (receiveFarmTaskAward.getValue()) {
                listFarmTask(TaskStatus.TODO);
                // 第一趟已确认槽满时第二趟不必再拉列表：领奖必被拒，只是白打接口
                if (!awardTroughFullThisRun) {
                    listFarmTask(TaskStatus.FINISHED);
                }
            }

            if (AnimalInteractStatus.HOME.name().equals(ownerAnimal.animalInteractStatus)) {
                if (AnimalFeedStatus.HUNGRY.name().equals(ownerAnimal.animalFeedStatus)) {
                    Log.record("小鸡在挨饿");
                    if (feedAnimal.getValue()) {
                        feedAnimal(ownerFarmId);
                    }
                } else if (AnimalFeedStatus.EATING.name().equals(ownerAnimal.animalFeedStatus)) {
                    if (useAccelerateTool.getValue()) {
                        useAccelerateTool();
                        TimeUtil.sleep(1000);
                    }
                    //使用加饭卡
                    if (useBigEaterTool.getValue()) {
                        useFarmTool(ownerFarmId, AntFarm.ToolType.BIG_EATER_TOOL);
                    }
                    if (feedAnimal.getValue()) {
                        autoFeedAnimal();
                        TimeUtil.sleep(1000);
                    }
                }

                checkUnReceiveTaskAward();
            }

            // 小鸡换装
            if (ornamentsDressUp.getValue()) {
                ornamentsDressUp();
            }

            // 到访小鸡送礼
            visitAnimal();

            // 送麦子
            visitFriend();

            // 帮好友喂鸡
            if (feedFriendAnimal.getValue()) {
                feedFriend();
            }

            // 通知好友赶鸡
            if (notifyFriendType.getValue() != NotifyFriendType.NONE) {
                notifyFriend();
            }

            // 抽抽乐
            if (drawMachine.getValue()) {
                drawMachineGroups();

            }

            // 雇佣小鸡：NPC 放在好友之后，避免 NPC 计入可雇佣好友的位置数
            if (hireAnimalEnable.getValue()) {
                if (hireAnimalType.getValue() != HireAnimalType.NONE) {
                    hireAnimal();
                }
                hireNpc();
            }
            
            /*  注释掉有问题的代码
             if (getFeedType.getValue() != GetFeedType.NONE) {
                letsGetChickenFeedTogether();
            }*/

            if (family.getValue()) {
                family();
            }

            // 开宝箱
            if (drawGameCenterAward.getValue()) {
                drawGameCenterAward();
            }

            // 小鸡睡觉&起床
            animalSleepAndWake();

        } catch (Throwable t) {
            Log.err(TAG, "AntFarm.start.run err:", t);
        }
    }

    public static void initAntFarmTaskListMap(boolean AutoAntFarmDoFarmTaskList, boolean AutoAntFarmDrawMachineTaskList, boolean drawMachine) {
        try {
            //初始化AntFarmDoFarmTaskListMap
            AntFarmDoFarmTaskListMap.load();
            // 预置黑名单登记在 MessageUtil（单一真相，配置页据此标注"默认"）
            Set<String> blackList = MessageUtil.presetBlackList("AntFarm", "AntFarmDoFarmTaskList");
            Set<String> whiteList = new HashSet<>();
            //whiteList.add("逛一逛树");
            for (String task : blackList) {
                AntFarmDoFarmTaskListMap.add(task, task);
            }

            JSONObject jo = new JSONObject(AntFarmRpcCall.listFarmTask());
            if (MessageUtil.checkMemo(TAG, jo)) {
                JSONArray ja = jo.optJSONArray("farmTaskList");
                if (ja != null) {
                    for (int i = 0; i < ja.length(); i++) {
                        jo = ja.optJSONObject(i);
                        if (jo == null) {
                            continue;
                        }
                        String title = jo.optString("title", "");
                        if (!title.isEmpty()) {
                            AntFarmDoFarmTaskListMap.add(title, title);
                        }
                    }
                }
            }
            //保存任务到配置文件
            AntFarmDoFarmTaskListMap.save();
            Log.record("同步任务🉑庄园饲料任务列表");

            //自动按模块初始化设定调整黑名单和白名单
            if (AutoAntFarmDoFarmTaskList) {
                // 初始化黑白名单（使用集合统一操作）
                ConfigV2 config = ConfigV2.INSTANCE;
                ModelFields AntFarm = config.getModelFieldsMap().get("AntFarm");
                SelectModelField AntFarmDoFarmTaskList = (SelectModelField) AntFarm.get("AntFarmDoFarmTaskList");
                if (AntFarmDoFarmTaskList == null) {
                    return;
                }
                // 2~4. 批量写回黑/白名单并保存
                MessageUtil.syncTaskBlackList("庄园饲料任务", "AntFarmDoFarmTaskList", blackList, whiteList, AntFarmDoFarmTaskList);
            }

            //初始化AntFarmDrawMachineTaskListMap
            AntFarmDrawMachineTaskListMap.load();
            // 注：游戏/开宝箱类不再预置拉黑，交由自动拉黑机制判定；
            // "伸出援手，点亮希望"（需真实捐赠）与"消耗饲料换机会"（需消耗资源）保留
            blackList = MessageUtil.presetBlackList("AntFarm", "AntFarmDrawMachineTaskList");

            whiteList = new HashSet<>();// 从黑名单中移除该任务
            //whiteList.add("逛一逛树");
            for (String task : blackList) {
                AntFarmDrawMachineTaskListMap.add(task, task);
            }

            if (drawMachine) {
                jo = new JSONObject(AntFarmRpcCall.queryLoveCabin(UserIdMap.getCurrentUid()));
                if (MessageUtil.checkMemo(TAG, jo)) {
                    jo = new JSONObject(AntFarmRpcCall.listFarmDrawTask("ANTFARM_DAILY_DRAW_TASK"));
                    if (MessageUtil.checkMemo(TAG, jo)) {
                        JSONArray farmTaskList = jo.optJSONArray("farmTaskList");
                        if (farmTaskList != null) {
                            for (int i = 0; i < farmTaskList.length(); i++) {
                                jo = farmTaskList.optJSONObject(i);
                                if (jo == null) {
                                    continue;
                                }
                                String title = jo.optString("title", "");
                                if (!title.isEmpty()) {
                                    AntFarmDrawMachineTaskListMap.add(title, title);
                                }
                            }
                        }
                        JSONObject queryDrawMachineActivityjo = new JSONObject(AntFarmRpcCall.queryDrawMachineActivity("ipDrawMachine", "dailyDrawMachine"));
                        if (MessageUtil.checkMemo(TAG, queryDrawMachineActivityjo)) {
                            if (queryDrawMachineActivityjo.has("otherDrawMachineActivityIds")) {
                                if (queryDrawMachineActivityjo.getJSONArray("otherDrawMachineActivityIds").length() > 0) {
                                    jo = new JSONObject(AntFarmRpcCall.listFarmDrawTask("ANTFARM_IP_DRAW_TASK"));
                                    if (MessageUtil.checkMemo(TAG, jo)) {
                                        farmTaskList = jo.optJSONArray("farmTaskList");
                                        if (farmTaskList != null) {
                                            for (int i = 0; i < farmTaskList.length(); i++) {
                                                jo = farmTaskList.optJSONObject(i);
                                                if (jo == null) {
                                                    continue;
                                                }
                                                String title = jo.optString("title", "");
                                                if (!title.isEmpty()) {
                                                    AntFarmDrawMachineTaskListMap.add(title, title);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                //保存任务到配置文件
                AntFarmDrawMachineTaskListMap.save();
                Log.record("同步任务🉑庄园装扮抽抽乐任务列表");

                //自动按模块初始化设定调整黑名单和白名单
                if (AutoAntFarmDrawMachineTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntFarm = config.getModelFieldsMap().get("AntFarm");
                    SelectModelField AntFarmDrawMachineTaskList = (SelectModelField) AntFarm.get("AntFarmDrawMachineTaskList");
                    if (AntFarmDrawMachineTaskList == null) {
                        return;
                    }
                    // 批量写回黑/白名单并保存
                    MessageUtil.syncTaskBlackList("庄园装扮抽抽乐任务", "AntFarmDrawMachineTaskList", blackList, whiteList, AntFarmDrawMachineTaskList);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "initAntFarmTaskListMap err:", t);
        }
    }

    private void animalSleepAndWake() {
        if (!enableSleep.getValue()) {
            Log.record("小鸡睡觉开关已关闭，跳过睡觉逻辑");
            return;
        }
        String sleepTimeStr = sleepTime.getValue();
        if ("-1".equals(sleepTimeStr)) {
            return;
        }
        animalWakeUpNow();
        Calendar animalSleepTimeCalendar = TimeUtil.getTodayCalendarByTimeStr(sleepTimeStr);
        if (animalSleepTimeCalendar == null) {
            return;
        }
        Integer sleepMinutesInt = sleepMinutes.getValue();
        Calendar animalWakeUpTimeCalendar = (Calendar) animalSleepTimeCalendar.clone();
        animalWakeUpTimeCalendar.add(Calendar.MINUTE, sleepMinutesInt);
        long animalSleepTime = animalSleepTimeCalendar.getTimeInMillis();
        long animalWakeUpTime = animalWakeUpTimeCalendar.getTimeInMillis();
        if (animalSleepTime > animalWakeUpTime) {
            Log.record("小鸡睡觉设置有误，请重新设置");
            return;
        }
        Calendar now = TimeUtil.getNow();
        boolean afterSleepTime = now.compareTo(animalSleepTimeCalendar) > 0;
        boolean afterWakeUpTime = now.compareTo(animalWakeUpTimeCalendar) > 0;
        if (afterSleepTime && afterWakeUpTime) {
            // 睡觉时间后
            if (hasSleepToday()) {
                return;
            }
            Log.record("已错过小鸡今日睡觉时间");
            return;
        }
        if (afterSleepTime) {
            // 睡觉时间内
            if (!hasSleepToday()) {
                animalSleepNow();
            }
            animalWakeUpTime(animalWakeUpTime);
            return;
        }
        // 睡觉时间前
        animalWakeUpTimeCalendar.add(Calendar.HOUR_OF_DAY, -24);
        if (now.compareTo(animalWakeUpTimeCalendar) <= 0) {
            animalWakeUpTime(animalWakeUpTimeCalendar.getTimeInMillis());
        }
        animalSleepTime(animalSleepTime);
        animalWakeUpTime(animalWakeUpTime);
    }

    private JSONObject enterFarm() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterFarm("", UserIdMap.getCurrentUid()));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return null;
            }
            JSONObject globalConfig = jo.optJSONObject("dynamicGlobalConfig");
            rewardProductNum = globalConfig != null ? globalConfig.optString("rewardProductNum") : "";
            JSONObject joFarmVO = jo.optJSONObject("farmVO");
            if (joFarmVO == null) {
                Log.i(TAG, "enterFarm 响应缺少 farmVO，本轮跳过");
                return null;
            }
            foodStock = joFarmVO.optInt("foodStock", 0);
            foodStockLimit = joFarmVO.optInt("foodStockLimit", 0);
            harvestBenevolenceScore = joFarmVO.optDouble("harvestBenevolenceScore", 0d);
            parseSyncAnimalStatusResponse(joFarmVO.toString());
            JSONObject masterUserInfo = joFarmVO.optJSONObject("masterUserInfoVO");
            ownerUserId = masterUserInfo != null ? masterUserInfo.optString("userId") : "";
            if (ownerUserId.isEmpty()) {
                Log.i(TAG, "enterFarm 响应缺少农场主信息，本轮跳过");
                return null;
            }
            ownerGroupId = getFamilyGroupId(ownerUserId);

            // activityData 字段存在但值为 null 时 has() 为真、optJSONObject 为 null：原写法在这里 NPE，
            // 后面的喂食/领奖/捐蛋整轮都被跳过
            JSONObject activityData = jo.optJSONObject("activityData");
            if (activityData != null && activityData.has("springGifts")) {
                JSONArray springGifts = activityData.optJSONArray("springGifts");
                if (springGifts != null) {
                    for (int i = 0; i < springGifts.length(); i++) {
                        JSONObject springGift = springGifts.getJSONObject(i);
                        String foodType = springGift.optString("foodType");
                        int giftIndex = springGift.optInt("giftIndex");
                        String foodSubType = springGift.optString("foodSubType");
                        int foodCount = springGift.optInt("foodCount");
                        AntFarmRpcCall.clickForGiftV2(foodType, giftIndex);
                        if (MessageUtil.checkMemo(TAG, jo)) {
                            Log.farm("惊喜礼包🎁[" + foodSubType + "*" + foodCount + "]");
                        }
                    }
                }
            }

            if (useSpecialFood.getValue()) {
                if (jo.has("cuisineList")) {
                    JSONArray cuisineList = jo.getJSONArray("cuisineList");
                    if (AnimalInteractStatus.HOME.name().equals(ownerAnimal.animalInteractStatus) && !AnimalFeedStatus.SLEEPY.name().equals(ownerAnimal.animalFeedStatus) && Status.canUseSpecialFoodToday()) {
                        useFarmFood(cuisineList);
                    }
                }
            }

            if (jo.has("lotteryPlusInfo")) {
                drawLotteryPlus(jo.getJSONObject("lotteryPlusInfo"));
            }
            JSONObject subFarmVO = joFarmVO.optJSONObject("subFarmVO");
            if (acceptGift.getValue() && subFarmVO != null && subFarmVO.has("giftRecord") && foodStockLimit - foodStock >= 10) {
                acceptGift();
            }
            return jo;
        } catch (Throwable t) {
            Log.err(TAG, "enterFarm err:", t);
        }
        return null;
    }

    private void autoFeedAnimal() {
        syncAnimalStatus(ownerFarmId);
        if (!AnimalFeedStatus.EATING.name().equals(ownerAnimal.animalFeedStatus)) {
            return;
        }
        double foodHaveEatten = 0d;
        double consumeSpeed = 0d;
        long nowTime = System.currentTimeMillis();
        for (Animal animal : animals) {
            foodHaveEatten += (nowTime - animal.startEatTime) / 1000 * animal.consumeSpeed;
            consumeSpeed += animal.consumeSpeed;
        }
        long nextFeedTime = nowTime + (long) ((foodInTrough - foodHaveEatten) / consumeSpeed) * 1000;
        String taskId = "FA|" + ownerFarmId;
        if (hasChildTask(taskId)) {
            removeChildTask(taskId);
        }
        addChildTask(new ChildModelTask(taskId, "FA", () -> feedAnimal(ownerFarmId), nextFeedTime));
        Log.record("添加蹲点投喂🥣[" + UserIdMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(nextFeedTime) + "]执行");
    }

    private void animalSleepTime(long animalSleepTime) {
        String sleepTaskId = "AS|" + animalSleepTime;
        if (!hasChildTask(sleepTaskId)) {
            addChildTask(new ChildModelTask(sleepTaskId, "AS", this::animalSleepNow, animalSleepTime));
            Log.record("添加定时睡觉🛌[" + UserIdMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(animalSleepTime) + "]执行");
        }
    }

    private void animalWakeUpTime(long animalWakeUpTime) {
        String wakeUpTaskId = "AW|" + animalWakeUpTime;
        if (!hasChildTask(wakeUpTaskId)) {
            addChildTask(new ChildModelTask(wakeUpTaskId, "AW", this::animalWakeUpNow, animalWakeUpTime));
            Log.record("添加定时起床🔆[" + UserIdMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(animalWakeUpTime) + "]执行");
        }
    }

    private Boolean hasSleepToday() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryLoveCabin(ownerUserId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return false;
            }
            jo = jo.getJSONObject("sleepNotifyInfo");
            return jo.optBoolean("hasSleepToday", false);
        } catch (Throwable t) {
            Log.i(TAG, "hasSleepToday err:");
            Log.printStackTrace(t);
        }
        return false;
    }

    private Boolean animalSleepNow() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryLoveCabin(UserIdMap.getCurrentUid()));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return false;
            }
            JSONObject sleepNotifyInfo = jo.getJSONObject("sleepNotifyInfo");
            if (!sleepNotifyInfo.optBoolean("canSleep", false)) {
                Log.record("小鸡无需睡觉🛌");
                return false;
            }
            // 路由优先级：亲密家庭开关开启时优先走"家庭"接口；开关关着时再按 spaceType 兜底（处理小鸡恰在家庭空间、或在好友家等情形）：
            // 开关关着、但小鸡人在家庭空间时，原先会去调个人小屋的睡觉接口 → 小鸡不在那儿，静默失败，
            // 于是"家庭里的小鸡不睡觉"。（起床逻辑本来就按 spaceType 判断，两边不一致才是根因）
            // 只判字段是否存在不够：其它空间（如小鸡在好友家）也可能带 spaceType，必须比对取值
            // 亲密家庭开启时优先在"家庭"睡觉，避免跑到个人小窝（外面）睡；
            // 开关关着时也按 spaceType 兜底（小鸡恰在家庭空间时仍走家庭接口）
            if (family.getValue() || SPACE_TYPE_CHICK_FAMILY.equals(jo.optString("spaceType"))) {
                return familySleep(resolveFamilyGroupId(jo));
            }
            return animalSleep();
        } catch (Throwable t) {
            Log.i(TAG, "animalSleepNow err:");
            Log.printStackTrace(t);
        }
        return false;
    }

    /** 家庭空间睡觉要用的 groupId：优先用本次响应里的，其次用已缓存的 ownerGroupId */
    private String resolveFamilyGroupId(JSONObject loveCabin) {
        String groupId = loveCabin.optString("groupId");
        return StringUtil.isEmpty(groupId) ? ownerGroupId : groupId;
    }

    private Boolean animalWakeUpNow() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryLoveCabin(UserIdMap.getCurrentUid()));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return false;
            }
            JSONObject ownAnimal = jo.getJSONObject("ownAnimal");
            JSONObject sleepInfo = ownAnimal.getJSONObject("sleepInfo");
            if (sleepInfo.getInt("countDown") == 0) {
                return false;
            }
            if (sleepInfo.getLong("sleepBeginTime") + TimeUnit.MINUTES.toMillis(sleepMinutes.getValue()) <= System.currentTimeMillis()) {
                // 亲密家庭开启时优先在"家庭"起床；否则按 spaceType 兜底
                if (family.getValue() || SPACE_TYPE_CHICK_FAMILY.equals(jo.optString("spaceType"))) {
                    return familyWakeUp();
                }
                return animalWakeUp();
            } else {
                Log.record("小鸡无需起床🔆");
            }
        } catch (Throwable t) {
            Log.i(TAG, "animalWakeUpNow err:");
            Log.printStackTrace(t);
        }
        return false;
    }

    private Boolean animalSleep() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.sleep());
            if (MessageUtil.checkMemo(TAG, jo)) {
                Log.farm("小鸡睡觉🛌");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "animalSleep err:", t);
        }
        return false;
    }

    private Boolean animalWakeUp() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.wakeUp());
            if (MessageUtil.checkMemo(TAG, jo)) {
                Log.farm("小鸡起床🔆");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "animalWakeUp err:", t);
        }
        return false;
    }

    private void syncAnimalStatus(String farmId) {
        try {
            String s = AntFarmRpcCall.syncAnimalStatus(farmId);
            parseSyncAnimalStatusResponse(s);
        } catch (Throwable t) {
            Log.err(TAG, "syncAnimalStatus err:", t);
        }
    }

    private void syncAnimalStatusAtOtherFarm(String farmId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterFarm(farmId, ""));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            jo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO");
            JSONArray jaAnimals = jo.getJSONArray("animals");
            for (int i = 0; i < jaAnimals.length(); i++) {
                jo = jaAnimals.getJSONObject(i);
                if (jo.getString("masterFarmId").equals(ownerFarmId)) {
                    Animal newOwnerAnimal = new Animal();
                    JSONObject animal = jaAnimals.getJSONObject(i);
                    newOwnerAnimal.animalId = animal.getString("animalId");
                    newOwnerAnimal.currentFarmId = animal.getString("currentFarmId");
                    newOwnerAnimal.currentFarmMasterUserId = animal.getString("currentFarmMasterUserId");
                    newOwnerAnimal.masterFarmId = ownerFarmId;
                    newOwnerAnimal.animalBuff = animal.getString("animalBuff");
                    newOwnerAnimal.locationType = animal.optString("locationType", "");
                    newOwnerAnimal.subAnimalType = animal.getString("subAnimalType");
                    animal = animal.getJSONObject("animalStatusVO");
                    newOwnerAnimal.animalFeedStatus = animal.getString("animalFeedStatus");
                    newOwnerAnimal.animalInteractStatus = animal.getString("animalInteractStatus");
                    ownerAnimal = newOwnerAnimal;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "syncAnimalStatusAtOtherFarm err:", t);
        }
    }

    private void rewardFriend() {
        try {
            if (rewardList != null) {
                for (RewardFriend rewardFriend : rewardList) {
                    JSONObject jo = new JSONObject(AntFarmRpcCall.rewardFriend(rewardFriend.consistencyKey, rewardFriend.friendId, rewardProductNum, rewardFriend.time));
                    if (MessageUtil.checkMemo(TAG, jo)) {
                        double rewardCount = benevolenceScore - jo.getDouble("farmProduct");
                        benevolenceScore -= rewardCount;
                        Log.farm("打赏好友💰[" + UserIdMap.getMaskName(rewardFriend.friendId) + "]#得" + rewardCount + "颗爱心鸡蛋");
                    }
                }
                rewardList = null;
            }
        } catch (Throwable t) {
            Log.err(TAG, "rewardFriend err:", t);
        }
    }

    private void recallAnimal(String animalId, String currentFarmId, String masterFarmId, String user) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.recallAnimal(animalId, currentFarmId, masterFarmId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                retryRecallAnimal(animalId, currentFarmId, masterFarmId, user);
                return;
            }
            double foodHaveStolen = jo.getDouble("foodHaveStolen");
            Log.farm("召回小鸡📣偷吃[" + user + "]饲料" + foodHaveStolen + "g");
            // 这里不需要加
        } catch (Throwable t) {
            Log.err(TAG, "recallAnimal err:", t);
            retryRecallAnimal(animalId, currentFarmId, masterFarmId, user);
        }
    }

    /** 召回失败后 10 秒静默重试一次：重试失败不再记日志（失败原因上一条已记过），成功照常记录 */
    private void retryRecallAnimal(String animalId, String currentFarmId, String masterFarmId, String user) {
        String taskId = "RA|" + animalId;
        if (hasChildTask(taskId)) {
            removeChildTask(taskId);
        }
        addChildTask(new ChildModelTask(taskId, "RA", () -> {
            try {
                JSONObject jo = new JSONObject(AntFarmRpcCall.recallAnimal(animalId, currentFarmId, masterFarmId));
                if ("SUCCESS".equals(jo.optString("memo"))) {
                    double foodHaveStolen = jo.getDouble("foodHaveStolen");
                    Log.farm("召回小鸡📣偷吃[" + user + "]饲料" + foodHaveStolen + "g");
                }
            } catch (Throwable t) {
                Log.err(TAG, "retryRecallAnimal err:", t);
            }
        }, System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)));
    }

    private void sendBackAnimal() {
        if (animals == null) {
            return;
        }
        try {
            for (Animal animal : animals) {
                // NPC 是雇主自己雇来的，不属于该遣返的偷吃小鸡
                if (AnimalInteractStatus.STEALING.name().equals(animal.animalInteractStatus) && !SubAnimalType.GUEST.name().equals(animal.subAnimalType) && !SubAnimalType.WORK.name().equals(animal.subAnimalType) && !isNpcAnimal(animal)) {
                    // 赶鸡
                    String user = AntFarmRpcCall.farmId2UserId(animal.masterFarmId);
                    boolean isSendBackAnimal = sendBackAnimalList.getValue().contains(user);
                    if (sendBackAnimalType.getValue() != SendBackAnimalType.BACK) {
                        isSendBackAnimal = !isSendBackAnimal;
                    }
                    if (!isSendBackAnimal) {
                        continue;
                    }
                    int sendTypeInt = sendBackAnimalWay.getValue();
                    user = UserIdMap.getMaskName(user);
                    JSONObject jo = new JSONObject(AntFarmRpcCall.sendBackAnimal(SendBackAnimalWay.nickNames[sendTypeInt], animal.animalId, animal.currentFarmId, animal.masterFarmId));
                    if (MessageUtil.checkMemo(TAG, jo)) {
                        String s;
                        if (sendTypeInt == SendBackAnimalWay.HIT) {
                            if (jo.has("hitLossFood")) {
                                s = "胖揍小鸡🤺[" + user + "]，掉落[" + jo.getInt("hitLossFood") + "g]";
                                if (jo.has("finalFoodStorage")) {
                                    foodStock = jo.getInt("finalFoodStorage");
                                }
                            } else {
                                s = "[" + user + "]的小鸡躲开了攻击";
                            }
                        } else {
                            s = "驱赶小鸡🧶[" + user + "]";
                        }
                        Log.farm(s);
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "sendBackAnimal err:", t);
        }
    }

    private void receiveToolTaskReward() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.listToolTaskDetails());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONArray jaList = jo.getJSONArray("list");
            for (int i = 0; i < jaList.length(); i++) {
                JSONObject joItem = jaList.getJSONObject(i);
                if (!TaskStatus.FINISHED.name().equals(joItem.optString("taskStatus"))) {
                    continue;
                }
                JSONObject bizInfo = new JSONObject(joItem.getString("bizInfo"));
                String awardType = bizInfo.optString("awardType");
                ToolType toolType = ToolType.valueOf(awardType);
                boolean isFull = false;
                for (FarmTool farmTool : farmTools) {
                    if (farmTool.toolType == toolType) {
                        if (farmTool.toolCount == farmTool.toolHoldLimit) {
                            isFull = true;
                        }
                        break;
                    }
                }
                if (isFull) {
                    if (toolType.equals(ToolType.NEWEGGTOOL)) {
                        useFarmTool(ownerFarmId, ToolType.NEWEGGTOOL);
                    } else {
                        Log.record("领取道具[" + toolType.nickName() + "]#已满，暂不领取");
                        continue;
                    }
                }
                int awardCount = bizInfo.getInt("awardCount");
                String taskType = joItem.getString("taskType");
                String taskTitle = bizInfo.getString("taskTitle");
                jo = new JSONObject(AntFarmRpcCall.receiveToolTaskReward(awardType, awardCount, taskType));
                if (MessageUtil.checkMemo(TAG, jo)) {
                    Log.farm("领取道具🎖️[" + taskTitle + "-" + toolType.nickName() + "]#" + awardCount + "张");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "receiveToolTaskReward err:", t);
        }
    }

    private void harvestProduce(String farmId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.harvestProduce(farmId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            double harvest = jo.getDouble("harvestBenevolenceScore");
            harvestBenevolenceScore = jo.getDouble("finalBenevolenceScore");
            Log.farm("收取鸡蛋🥚[" + harvest + "颗]#剩余" + harvestBenevolenceScore + "颗");
        } catch (Throwable t) {
            Log.err(TAG, "harvestProduce err:", t);
        }
    }

    /* 捐赠爱心鸡蛋 */
    private void donation() {
        if (!canDonationToday()) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.listActivityInfo());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONArray activityInfos = jo.getJSONArray("activityInfos");
            for (int i = 0; i < activityInfos.length(); i++) {
                jo = activityInfos.getJSONObject(i);
                int donationTotal = jo.getInt("donationTotal");
                int donationLimit = jo.getInt("donationLimit");

                int donationNum = Math.min(donationAmount.getValue(), donationLimit - donationTotal);
                if (donationNum == 0) {
                    continue;
                }
                String activityId = jo.getString("activityId");
                String projectName = jo.getString("projectName");
                String projectId = jo.getString("projectId");
                int projectDonationNum = getProjectDonationNum(projectId);
                donationNum = Math.min(donationNum, donationAmount.getValue() - projectDonationNum % donationAmount.getValue());
                boolean isDonation;
                if (donationNum == donationAmount.getValue()) {
                    isDonation = donation(activityId, projectName, donationNum, 1);
                } else {
                    isDonation = donation(activityId, projectName, 1, donationNum);
                }
                if (isDonation && donationType.getValue() != DonationType.ALL) {
                    return;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "donation err:", t);
        }
    }

    private void donation(int donateNum) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.listActivityInfo());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            if (!jo.has("activityInfos")) {
                return;
            }

            JSONArray activityInfos = jo.getJSONArray("activityInfos");

            // 收集可捐蛋项目
            java.util.ArrayList<JSONObject> availableList = new java.util.ArrayList<>();
            for (int i = 0; i < activityInfos.length(); i++) {
                JSONObject activityInfo = activityInfos.getJSONObject(i);
                int available = activityInfo.getInt("donationLimit") - activityInfo.getInt("donationTotal");
                if (available > 0) {
                    availableList.add(activityInfo);
                }
            }

            if (availableList.isEmpty()) {
                return;
            }

            // 平均分配
            int remaining = donateNum;
            int size = availableList.size();
            for (int i = 0; i < size && remaining > 0; i++) {
                JSONObject activityInfo = availableList.get(i);
                int available = activityInfo.getInt("donationLimit") - activityInfo.getInt("donationTotal");

                // 计算分配数量
                int assign;
                if (i == size - 1) {
                    assign = remaining;
                } else {
                    assign = donateNum / size;
                    if (i < donateNum % size) {
                        assign++;
                    }
                }

                // 不超过可捐限额和剩余数量
                assign = Math.min(assign, available);
                assign = Math.min(assign, remaining);

                if (assign > 0) {
                    String activityId = activityInfo.getString("activityId");
                    String projectName = activityInfo.getString("projectName");
                    donation(activityId, projectName, assign);
                    remaining -= assign;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "donation err:", t);
        }
    }

    private Boolean donation(String activityId, String activityName, int donationAmount, int count) {
        boolean isDonation = false;
        for (int i = 0; i < count; i++) {
            if (!donation(activityId, activityName, donationAmount)) {
                break;
            }
            isDonation = true;
            TimeUtil.sleep(1000L);
        }
        return isDonation;
    }

    private Boolean donation(String activityId, String activityName, int donationAmount) {
        if (harvestBenevolenceScore < donationAmount) {
            return false;
        }
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.donation(activityId, donationAmount));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return false;
            }
            jo = jo.getJSONObject("donation");
            harvestBenevolenceScore = jo.getDouble("harvestBenevolenceScore");
            int donationTimesStat = jo.getInt("donationTimesStat");
            Log.farm("公益捐赠❤️[捐爱心蛋:" + activityName + "]捐赠" + donationAmount + "颗爱心蛋#累计捐赠" + donationTimesStat + "次");
            // 与 S2 共用当日标记：同一天只捐一次（谁先捐成都算数）
            Status.flagToday(FLAG_CHARITY_DONATION_DONE);
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "donation err:", t);
        }
        return false;
    }

    /**
     * 爱心鸡结号(S2赛季)自动化。
     * <p>
     * S2 为<b>周活动</b>：每周一 00:00:00 ~ 周日 20:00:00 为一轮（旧排位赛为按天）。
     * <p>
     * 返回值 = 「<b>当天的捐蛋是否已经完成</b>」，供调用方决定要不要回退公益捐蛋：
     * <ul>
     *   <li>{@code true}：本轮已把蛋捐给 S2（或今天已经捐过）——S2 优先，不再做公益；</li>
     *   <li>{@code false}：S2 没捐成（无活动 / 「自动捐蛋」没开 / 没蛋 / 取不到项目 / 请求失败），
     *       由调用方回退公益捐蛋，保证这一天至少捐出一次。</li>
     * </ul>
     * <p>
     * 活跃判据用「多信号取或」：轮次号、活动数据字段、捐蛋项目三者任一命中即算有活动。
     * 原先只看 {@code rankRoundId} + 单组活动字段，服务端字段一改就永远判成「无活动」，
     * 表现为每天都跳过爱心鸡结号、只做公益捐蛋。
     */
    private boolean competition() {
        boolean s2DonatedToday = false;
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterDonationCompetitionRank());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                Log.record("爱心鸡结号❤️入口信息查询失败，本轮回退公益捐蛋：" + jo);
                return false;
            }
            Log.i("爱心鸡结号❤️当前爱心值" + jo.optInt("benevolenceScore"));

            String roundId = findCompetitionRoundId(jo);
            String projectId = "";
            String projectName = "";
            boolean s2Active;
            if (!isCompetitionRoundActive()) {
                s2Active = false;
                Log.record("爱心鸡结号❤️已过周活动时间窗口(周日20:00后)，回退公益捐蛋");
            } else if (!roundId.isEmpty() || hasCompetitionActivityData(jo)) {
                s2Active = true;
                Log.i("爱心鸡结号❤️判定 S2 活跃#轮次=" + (roundId.isEmpty() ? "未取到" : roundId));
            } else {
                // 兜底判据：字段对不上时，能取到捐蛋项目也算 S2 有活动
                JSONObject project = queryCompetitionProject();
                projectId = project == null ? "" : project.optString("projectId", "");
                projectName = project == null ? "" : project.optString("projectName", "");
                s2Active = !projectId.isEmpty();
                Log.record(s2Active ? "爱心鸡结号❤️活动字段未命中，但能取到捐蛋项目，按有活动处理"
                        : "爱心鸡结号❤️未确认到活跃轮次，回退公益捐蛋");
            }

            if (s2Active) {
                // 优先捐 S2。「当天合计一次」：S2 自己已捐成、或公益今天已捐过，都不再多捐
                // （两个标记各记各的，这里只是合起来看）
                if (Status.hasFlagToday(FLAG_COMPETITION_DONATED_TODAY)
                        || Status.hasFlagToday(FLAG_CHARITY_DONATION_DONE)) {
                    s2DonatedToday = true;
                    Log.i("爱心鸡结号❤️今日已捐过蛋，跳过");
                } else {
                    s2DonatedToday = donateToCompetition(projectId, projectName);
                }
                // 偷榜定时任务（周日20:00前 N 分钟执行一次）
                if (competitionStealRank.getValue()) {
                    setupStealRankTask();
                }
            }

            // 领取任务 + 成就奖励（与捐蛋互不影响）
            if (competitionReceiveTask.getValue()) {
                receiveCompetitionAward(jo);
                receiveCompetitionTaskAwards();
            }
        } catch (Throwable t) {
            Log.err(TAG, "competition err:", t);
        }
        return s2DonatedToday;
    }

    /**
     * S2 轮次号：服务端字段名/层级不唯一，按候选路径探测，取不到返回空串。
     */
    private static String findCompetitionRoundId(JSONObject jo) {
        String[] keys = {"rankRoundId", "roundId"};
        for (String key : keys) {
            String value = jo.optString(key, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        for (String wrapper : new String[]{"rankRoundInfo", "competitionRankInfo", "donationCompetitionInfo"}) {
            JSONObject sub = jo.optJSONObject(wrapper);
            if (sub == null) {
                continue;
            }
            for (String key : keys) {
                String value = sub.optString(key, "");
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    /**
     * S2 活动数据是否存在：已知字段任一命中即算（比只看单个字段更抗字段改名/改层级）。
     */
    private static boolean hasCompetitionActivityData(JSONObject jo) {
        for (String key : new String[]{"competitionTaskInfo", "donationCompetitionLevelConfigs", "benevolenceScore"}) {
            if (jo.has(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查 S2 捐蛋项目（{@code animationInfo.competitionProjectInfo}），失败返回 {@code null}。
     */
    private static JSONObject queryCompetitionProject() {
        try {
            JSONObject info = new JSONObject(AntFarmRpcCall.queryCompetitionEntranceInfo());
            JSONObject animationInfo = info.optJSONObject("animationInfo");
            return animationInfo == null ? null : animationInfo.optJSONObject("competitionProjectInfo");
        } catch (Throwable t) {
            Log.err(TAG, "queryCompetitionProject err:", t);
        }
        return null;
    }

    /**
     * 爱心鸡结号(S2赛季)奖励领取：等级爱心值奖励。
     * <p>
     * 等级奖励列表来自 {@code enterDonationCompetitionRank} 响应的 {@code levelAwardInfoList}，
     * 每项含 {@code rightsId}（形如 "0915_1"）与 {@code status}（unattained/unclaimed/received）。
     * 对 {@code status=="unclaimed"} 的项调用 {@code receiveDonationLevelReward(rightsId)} 领取。
     */
    private void receiveCompetitionAward(JSONObject jo) {
        try {
            JSONArray levelAwardInfoList = jo.optJSONArray("levelAwardInfoList");
            if (levelAwardInfoList == null || levelAwardInfoList.length() == 0) {
                // 无等级奖励列表属每轮恒定的否定状态；静默（真有可领项时下方会打 🎖️领取日志）
                return;
            }
            int claimed = 0;
            for (int i = 0; i < levelAwardInfoList.length(); i++) {
                JSONObject item = levelAwardInfoList.getJSONObject(i);
                String status = item.optString("status");
                // unclaimed=可领；received=已领；unattained=未达成
                if (!"unclaimed".equals(status)) {
                    continue;
                }
                String rightsId = item.optString("rightsId");
                if (rightsId.isEmpty()) {
                    continue;
                }
                JSONObject rjo = new JSONObject(AntFarmRpcCall.receiveDonationLevelReward(rightsId));
                if (MessageUtil.checkMemo(TAG, rjo)) {
                    claimed++;
                    Log.farm("爱心鸡结号❤️领取等级奖励[" + item.optString("levelName") + "]");
                }
                TimeUtil.sleep(1000L);
            }
            if (claimed > 0) {
                Log.farm("爱心鸡结号❤️[等级爱心值]#本次领取" + claimed + "个");
            } else {
                Log.farm("爱心鸡结号❤️[等级爱心值]#无待领取");
            }
        } catch (Throwable t) {
            Log.err(TAG, "receiveCompetitionAward err:", t);
        }
    }

    /**
     * 爱心鸡结号(S2赛季)任务奖励领取。
     * <p>
     * 任务列表来自 {@code listCompetitionTask} 响应的 {@code taskList}，
     * 每项含 {@code taskType}（如 "TEAM_TASK_ROUND_1_TASK_500"）与 {@code canReceiveAwardCount}（可领数）。
     * 对 {@code canReceiveAwardCount > 0} 的任务调用 {@code receiveCompetitionTaskAward(taskType, count)} 领取。
     */
    private void receiveCompetitionTaskAwards() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.listCompetitionTask());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONArray taskList = jo.optJSONArray("taskList");
            if (taskList == null || taskList.length() == 0) {
                Log.record("爱心鸡结号❤️任务奖励：无任务列表");
                return;
            }
            int claimed = 0;
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.getJSONObject(i);
                int canReceive = task.optInt("canReceiveAwardCount");
                if (canReceive <= 0) {
                    continue;
                }
                String taskType = task.optString("taskType");
                if (taskType.isEmpty()) {
                    continue;
                }
                JSONObject rjo = new JSONObject(AntFarmRpcCall.receiveCompetitionTaskAward(taskType, canReceive));
                if (MessageUtil.checkMemo(TAG, rjo)) {
                    claimed++;
                    Log.farm("爱心鸡结号❤️领取任务奖励[" + task.optString("title") + "]");
                }
                TimeUtil.sleep(1000L);
            }
            // 只在真领到时打（"无待领取"是每轮恒定的否定状态，静默）
            if (claimed > 0) {
                Log.farm("爱心鸡结号❤️[任务奖励]#本次领取" + claimed + "个");
            }
        } catch (Throwable t) {
            Log.err(TAG, "receiveCompetitionTaskAwards err:", t);
        }
    }

    /**
     * S2 轮次判断：每周一 00:00:00 ~ 周日 20:00:00 为活动轮次内。
     * 周日 20:00 起轮次结束（结算/发奖期），不捐蛋、不偷榜。
     */
    private boolean isCompetitionRoundActive() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK); // SUNDAY=1
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        // 周日 20:00 之后到周一 00:00 之前为轮次外
        return !(dow == java.util.Calendar.SUNDAY && hour >= 20);
    }

    /**
     * 优先给 S2 定向捐蛋（每天一次，数量取「爱心鸡结号 | 自动捐蛋数量」）。
     * <p>返回「今天的捐蛋是否已完成」：捐成功返回 true；「自动捐蛋」没开 / 没蛋 / 取不到项目 /
     * 请求失败一律返回 false，由调用方回退公益捐蛋（S2 捐不了不能让这一天一次都捐不出）。
     * <p>两道防重：
     * <ul>
     *   <li>① 发请求前落「今日已尝试」标记 {@link #FLAG_COMPETITION_DONATE_TRIED}：
     *       S2 每天最多发一次请求，响应判定不成也不会每轮重发；</li>
     *   <li>② 响应没判定成功时回读项目累计捐赠数（{@code userProjectDonationNum}），
     *       涨了就认定其实捐上了，不再回退公益（避免"实际已捐 + 公益再捐一次"）。</li>
     * </ul>
     * <p>确认捐上才落 S2 自己的当日标记 {@link #FLAG_COMPETITION_DONATED_TODAY}：
     * 与公益是「当天合计一次」，但各自记各自的标记。
     */
    private boolean donateToCompetition(String projectId, String projectName) {
        try {
            if (!competitionDonate.getValue()) {
                return false;
            }
            int amount = competitionDonateAmount.getValue();
            if (amount <= 0) {
                return false;
            }
            int have = (int) harvestBenevolenceScore;
            if (have <= 0) {
                Log.record("爱心鸡结号❤️当前无蛋可捐，回退公益捐蛋");
                return false;
            }
            if (Status.hasFlagToday(FLAG_COMPETITION_DONATE_TRIED)) {
                // ① 今天已经给 S2 发过请求（真捐上了会命中 FARM_DONATION_DONE，这里只兜"发起过但没判定成功"）
                Log.record("爱心鸡结号❤️今日已尝试过 S2 捐蛋，回退公益捐蛋");
                return false;
            }
            if (projectId.isEmpty()) {
                JSONObject project = queryCompetitionProject();
                projectId = project == null ? "" : project.optString("projectId", "");
                projectName = project == null ? "" : project.optString("projectName", "");
            }
            if (projectId.isEmpty()) {
                Log.record("爱心鸡结号❤️未获取到捐蛋项目，回退公益捐蛋");
                return false;
            }
            int n = Math.min(amount, have);
            int beforeNum = queryProjectDonationNum(projectId);   // ② 捐前基准（-1=读不到）
            Log.farm("爱心鸡结号❤️自动捐蛋" + n + "枚到项目[" + projectName + "]");
            Status.flagToday(FLAG_COMPETITION_DONATE_TRIED);
            if (Boolean.TRUE.equals(donationCompetition(projectId, projectName, n))) {
                Status.flagToday(FLAG_COMPETITION_DONATED_TODAY);
                return true;
            }
            // ② 响应没判定成功：回读累计捐赠数，涨了说明其实捐上了
            int afterNum = queryProjectDonationNum(projectId);
            if (beforeNum >= 0 && afterNum > beforeNum) {
                Log.record("爱心鸡结号❤️响应未判定成功，但项目累计捐赠" + beforeNum + "→" + afterNum + "，按已捐处理");
                Status.flagToday(FLAG_COMPETITION_DONATED_TODAY);
                return true;
            }
            Log.record("爱心鸡结号❤️本轮 S2 捐蛋未成功(累计" + beforeNum + "→" + afterNum + ")，回退公益捐蛋");
            return false;
        } catch (Throwable t) {
            Log.err(TAG, "donateToCompetition err:", t);
        }
        return false;
    }

    /**
     * 设置偷榜定时任务：在每周日 20:00 前 {@code competitionStealMinutes} 分钟执行一次。
     */
    private void setupStealRankTask() {
        int minutes = competitionStealMinutes.getValue();
        if (minutes <= 0) {
            return;
        }
        java.util.Calendar target = java.util.Calendar.getInstance();
        target.set(java.util.Calendar.HOUR_OF_DAY, 20);
        target.set(java.util.Calendar.MINUTE, 0);
        target.set(java.util.Calendar.SECOND, 0);
        target.set(java.util.Calendar.MILLISECOND, 0);
        int dow = target.get(java.util.Calendar.DAY_OF_WEEK);
        int daysUntilSunday = (java.util.Calendar.SATURDAY - dow + 1) % 7;
        target.add(java.util.Calendar.DAY_OF_MONTH, daysUntilSunday);
        long stealRankTime = target.getTimeInMillis() - (long) minutes * 60 * 1000;
        if (stealRankTime <= System.currentTimeMillis()) {
            target.add(java.util.Calendar.DAY_OF_MONTH, 7);
            stealRankTime = target.getTimeInMillis() - (long) minutes * 60 * 1000;
        }
        String taskId = "competitionStealRank_" + minutes;
        if (!hasChildTask(taskId)) {
            addChildTask(new ChildModelTask(taskId, "COMPETITION_STEAL", this::stealRankS2, stealRankTime));
            Log.record("爱心鸡结号❤️已设置偷榜[定时]在 " + TimeUtil.getCommonDate(stealRankTime) + " 执行");
        }
    }

    /**
     * 偷榜：读取排行，捐赠至超过当前第1名（定向捐到 S2 项目）。
     */
    private void stealRankS2() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterDonationCompetitionRank());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONObject home = jo.optJSONObject("donationRankHomeInfo");
            if (home == null) {
                Log.record("爱心鸡结号❤️偷榜：无排行信息");
                return;
            }
            JSONArray list = home.optJSONArray("userDonationRankList");
            if (list == null || list.length() == 0) {
                Log.record("爱心鸡结号❤️偷榜：排行榜为空");
                return;
            }
            String myId = UserIdMap.getCurrentUid();
            int myDonation = 0, myRank = 0, rank1Donation = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject u = list.getJSONObject(i);
                int rank = u.optInt("rankOrder");
                int dn = u.optInt("donationNum");
                if (rank == 1) {
                    rank1Donation = dn;
                }
                if (myId.equals(u.optString("userId"))) {
                    myDonation = dn;
                    myRank = rank;
                }
            }
            if (myRank == 1) {
                Log.record("爱心鸡结号❤️偷榜：已第1名(捐" + myDonation + ")，无需操作");
                return;
            }
            int need = rank1Donation - myDonation + 1;
            if (need <= 0) {
                need = 1;
            }
            int have = (int) harvestBenevolenceScore;
            if (have <= 0) {
                Log.record("爱心鸡结号❤️偷榜：当前无蛋可捐");
                return;
            }
            // 蛋不够就不捐：捐了也超不过第1名
            if (have < need) {
                Log.record("爱心鸡结号❤️偷榜⏭️跳过：手上的蛋不够超过第1名(有" + have + "需" + need
                        + "，当前第" + myRank + "名捐" + myDonation + "，第1名捐" + rank1Donation + ")");
                return;
            }
            int n = need;
            // 上限按最终捐献量判定
            int stealLimit = competitionStealLimit.getValue();
            if (stealLimit > 0 && n > stealLimit) {
                Log.record("爱心鸡结号❤️偷榜⏭️跳过：需捐" + n + "超过上限" + stealLimit
                        + "(当前第" + myRank + "名捐" + myDonation + "，第1名捐" + rank1Donation + ")");
                return;
            }
            // 定向捐到 S2 项目
            JSONObject info = new JSONObject(AntFarmRpcCall.queryCompetitionEntranceInfo());
            String projectId = null, projectName = null;
            JSONObject anim = info.optJSONObject("animationInfo");
            if (anim != null) {
                JSONObject cpi = anim.optJSONObject("competitionProjectInfo");
                if (cpi != null) {
                    projectId = cpi.optString("projectId");
                    projectName = cpi.optString("projectName");
                }
            }
            if (projectId == null || projectId.isEmpty()) {
                Log.record("爱心鸡结号❤️偷榜：未获取到捐蛋项目，跳过（无法定向到 S2）");
                return;
            }
            Log.i("爱心鸡结号❤️偷榜：当前第" + myRank + "名捐" + myDonation + "，第1名捐" + rank1Donation + "，尝试再捐" + n);
            donationCompetition(projectId, projectName, n);
        } catch (Throwable t) {
            Log.err(TAG, "stealRankS2 err:", t);
        }
    }

    /**
     * S2 定向捐蛋：使用 projectId（抓包确认字段），成功后刷新爱心蛋余额。
     */
    private Boolean donationCompetition(String projectId, String projectName, int donationAmount) {
        if (harvestBenevolenceScore < donationAmount) {
            return false;
        }
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.donationCompetition(projectId, donationAmount));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return false;
            }
            // 只用响应刷新余额；缺字段时保留原值，不做本地估算（估算会与服务端漂移叠加）
            try {
                JSONObject d = jo.getJSONObject("donation");
                harvestBenevolenceScore = d.getDouble("harvestBenevolenceScore");
            } catch (Throwable ignore) {
                Log.record("爱心鸡结号❤️捐蛋响应缺少 donation 字段，余额暂不更新");
            }
            Log.farm("爱心鸡结号❤️[捐爱心蛋:" + projectName + "]捐赠" + donationAmount + "颗爱心蛋");
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "donationCompetition err:", t);
        }
        return false;
    }

    private int getProjectDonationNum(String projectId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.getProjectInfo(projectId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return 0;
            }
            return jo.optInt("userProjectDonationNum");
        } catch (Throwable t) {
            Log.err(TAG, "getProjectDonationNum err:", t);
        }
        return 0;
    }

    /**
     * 读项目累计捐赠数（{@code userProjectDonationNum}）。
     * <p>与 {@link #getProjectDonationNum} 的区别：查询失败 / 字段缺失返回 {@code -1}，
     * 把「读不到」与「真的是 0 次」区分开（读不到时不做"是否已捐上"的推断）。
     */
    private int queryProjectDonationNum(String projectId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.getProjectInfo(projectId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return -1;
            }
            return jo.optInt("userProjectDonationNum", -1);
        } catch (Throwable t) {
            Log.err(TAG, "queryProjectDonationNum err:", t);
        }
        return -1;
    }

    private Boolean canDonationToday() {
        if (Status.hasFlagToday(FLAG_CHARITY_DONATION_DONE)) {
            return false;
        }
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.getCharityAccount(ownerUserId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return false;
            }
            JSONArray charityRecords = jo.getJSONArray("charityRecords");
            if (charityRecords.length() == 0) {
                return true;
            }
            jo = charityRecords.getJSONObject(0);
            long charityTime = jo.optLong("charityTime", System.currentTimeMillis());
            if (TimeUtil.isLessThanNowOfDays(charityTime)) {
                return true;
            }
            Status.flagToday(FLAG_CHARITY_DONATION_DONE);
        } catch (Throwable t) {
            Log.err(TAG, "canDonationToday err:", t);
        }
        return false;
    }

    private void recordFarmGame(GameType gameType) {
        try {
            // remainingGameCount 由服务端下发，一直回正值就会每 2s 无限刷一遍；
            // 加轮次上限，正常对局次数远小于此值
            int loopCount = 0;
            do {
                if (++loopCount > MAX_GAME_ROUNDS) {
                    Log.i(TAG, "小鸡乐园🎮游玩次数超过上限，本轮停止");
                    break;
                }
                try {
                    JSONObject jo = new JSONObject(AntFarmRpcCall.initFarmGame(gameType.name()));
                    if (!MessageUtil.checkMemo(TAG, jo)) {
                        return;
                    }
                    if (jo.getJSONObject("gameAward").getBoolean("level3Get")) {
                        return;
                    }
                    if (jo.optInt("remainingGameCount", 1) == 0) {
                        return;
                    }
                    jo = new JSONObject(AntFarmRpcCall.recordFarmGame(gameType.name()));
                    if (!MessageUtil.checkMemo(TAG, jo)) {
                        return;
                    }
                    JSONArray awardInfos = jo.getJSONArray("awardInfos");
                    StringBuilder award = new StringBuilder();
                    for (int i = 0; i < awardInfos.length(); i++) {
                        JSONObject awardInfo = awardInfos.getJSONObject(i);
                        award.append(awardInfo.getString("awardName")).append("*").append(awardInfo.getInt("awardCount"));
                    }
                    if (jo.has("receiveFoodCount")) {
                        award.append(";肥料*").append(jo.getString("receiveFoodCount"));
                    }
                    Log.farm("小鸡乐园🎮游玩[" + gameType.gameName() + "]#获得[" + award + "]");
                    if (jo.optInt("remainingGameCount", 0) > 0) {
                        continue;
                    }
                    break;
                } finally {
                    TimeUtil.sleep(2000);
                }
            } while (true);
        } catch (Throwable t) {
            Log.err(TAG, "recordFarmGame err:", t);
        }
    }

    private void listFarmTask(TaskStatus Mode) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.listFarmTask());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONObject signList = jo.getJSONObject("signList");
            if (sign(signList)) {
                TimeUtil.sleep(1000);
            }
            JSONArray ja = jo.getJSONArray("farmTaskList");
            for (int i = 0; i < ja.length(); i++) {
                // 本轮已遇 102（服务端繁忙）时不再继续领奖，留到下一轮再试
                if (farmTaskAwardBusy) {
                    break;
                }
                // 槽已满：后续任务同样领不下，逐个请求只会被拒（提示已在置位时打过，这里不再重复）
                if (awardTroughFullThisRun) {
                    break;
                }
                jo = ja.getJSONObject(i);
                TaskStatus taskStatus = TaskStatus.valueOf(jo.getString("taskStatus"));
                String title = jo.getString("title");
                //黑名单只限制「自动去做」，不限制「手动完成后领奖」；
                //但必须遵守本次 Mode：调用方 TODO/FINISHED 各跑一趟，不判 Mode 会重复领同一个任务
                if (AntFarmDoFarmTaskList.getValue().contains(title)) {
                    if (taskStatus == TaskStatus.FINISHED && Mode == TaskStatus.FINISHED) {
                        receiveFarmTaskAward(jo);
                    }
                    continue;
                }
                if (taskStatus == TaskStatus.RECEIVED || taskStatus != Mode) {
                    continue;
                }
                if (taskStatus == TaskStatus.TODO && !doFarmTask(jo)) {
                    continue;
                }
                if (taskStatus == TaskStatus.FINISHED && !receiveFarmTaskAward(jo)) {
                    continue;
                }
                TimeUtil.sleep(1000);
            }
        } catch (Throwable t) {
            Log.err(TAG, "listFarmTask err:", t);
        }
    }

    private Boolean sign(JSONObject SignList) {
        if (Status.hasFlagToday("farm::sign")) {
            return false;
        }
        boolean signed = false;
        try {
            String currentSignKey = SignList.getString("currentSignKey");
            JSONArray signList = SignList.getJSONArray("signList");
            for (int i = 0; i < signList.length(); i++) {
                JSONObject jo = signList.getJSONObject(i);
                if (!currentSignKey.equals(jo.getString("signKey"))) {
                    continue;
                }
                if (jo.optBoolean("signed")) {
                    Log.record("庄园今日已签到");
                    signed = true;
                    return false;
                }
                int awardCount = jo.getInt("awardCount");
                if (awardCount + foodStock > foodStockLimit) {
                    return false;
                }
                int currentContinuousCount = jo.getInt("currentContinuousCount");
                jo = new JSONObject(AntFarmRpcCall.sign());
                if (MessageUtil.checkMemo(TAG, jo)) {
                    foodStock = jo.getInt("foodStock");
                    Log.farm("饲料任务📅签到[坚持" + currentContinuousCount + "天]#获得[" + awardCount + "g饲料]");
                    signed = true;
                    return true;
                }
                return false;
            }
        } catch (Throwable t) {
            Log.err(TAG, "sign err:", t);
        } finally {
            if (signed) {
                Status.flagToday("farm::sign");
            }
        }
        return false;
    }

    /**
     * 行为伪造：取视频 → 上报播放 → 等 15 秒 → 触发完成。
     * <p>结论按 {@link Outcome} 分类，交由 {@link TaskAttemptPolicy} 决定当天是否再试。
     */
    private Outcome doVideoTask(String title) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryTabVideoUrl());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                //检查并标记黑名单任务
                MessageUtil.checkResultCodeAndMarkTaskBlackList("AntFarmDoFarmTaskList", title, jo);
                return retryableOrUnable(jo);
            }
            String videoUrl = jo.getString("videoUrl");
            String contentId = videoUrl.substring(videoUrl.indexOf("&contentId=") + 1, videoUrl.indexOf("&refer"));
            jo = new JSONObject(AntFarmRpcCall.videoDeliverModule(contentId));
            if (jo.optBoolean("success")) {
                TimeUtil.sleep(15100);
                jo = new JSONObject(AntFarmRpcCall.videoTrigger(contentId));
                if (jo.optBoolean("success")) {
                    return Outcome.DONE;
                }
            }
            Log.record(jo.optString("resultMsg"));
            Log.i(jo.toString());
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntFarmDoFarmTaskList", title, jo);
            return retryableOrUnable(jo);
        } catch (Throwable t) {
            Log.err(TAG, "doVideoTask err:", t);
        }
        return Outcome.RETRY;
    }

    /** 临时故障（102/限流/异常）按可重试处理，其余按做不了 */
    private Outcome retryableOrUnable(JSONObject jo) {
        return MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo) ? Outcome.RETRY : Outcome.UNABLE;
    }

    /** 视频任务的普通完成尝试：饲料任务的通用申报接口对它无效，失败即交给行为伪造（doVideoTask）接手 */
    private Outcome attemptVideoTask(String title, String bizKey) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.doFarmTask(bizKey));
            //检查并标记黑名单任务（此处是庄园饲料任务，应写入饲料黑名单而非抽抽乐）
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntFarmDoFarmTaskList", title, jo);
            if (MessageUtil.checkResultCode(TAG, jo)) {
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
        } catch (Throwable t) {
            Log.err(TAG, "attemptVideoTask err:", t);
            return Outcome.RETRY;
        }
        return Outcome.UNSUPPORTED;
    }

    /** 饲料任务普通完成尝试：完成与否以任务列表为准（probeFarmStatus 复核），故此处只转译响应。 */
    private Outcome attemptFarmTask(String title, String bizKey) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.doFarmTask(bizKey));
            //检查并标记黑名单任务（此处是庄园饲料任务，应写入饲料黑名单而非抽抽乐）
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntFarmDoFarmTaskList", title, jo);
            if (MessageUtil.checkResultCode(TAG, jo)) {
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            // 不支持rpc调用（400000040）→ 由 TaskAttemptPolicy 代为伪申报
            if (TaskAlternative.hit(jo, "")) {
                return Outcome.UNSUPPORTED;
            }
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "attemptFarmTask err:", t);
        }
        return Outcome.RETRY;
    }

    /** 列表状态探针：重拉庄园饲料任务列表，按 bizKey 匹配该任务当前状态。 */
    private TaskAttemptPolicy.ProbeResult probeFarmStatus(String bizKey) {
        return TaskAttemptPolicy.probeByList(TAG, AntFarmRpcCall::listFarmTask, "farmTaskList",
                jo -> MessageUtil.checkMemo(TAG, jo),
                t -> bizKey.equals(t.optString("bizKey")) ? t.optString("taskStatus") : null);
    }

    private Boolean doAnswerTask(String title) {
        try {
            JSONObject jo = new JSONObject(DadaDailyRpcCall.home("100"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                //检查并标记黑名单任务
                MessageUtil.checkResultCodeAndMarkTaskBlackList("AntFarmDoFarmTaskList", title, jo);
                return false;
            }
            JSONObject question = jo.getJSONObject("question");
            long questionId = question.getLong("questionId");
            JSONArray labels = question.getJSONArray("label");
            if (labels.length() == 0) {
                Log.record("庄园答题跳过：选项为空");
                return false;
            }
            // title 用 optString：缺该字段时不应让整条答题失败（AI 仍可凭选项作答）
            String answer = AnswerAI.getAnswer(question.optString("title"), JsonUtil.jsonArrayToList(labels));
            jo = new JSONObject(DadaDailyRpcCall.submit("100", answer, questionId));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                //检查并标记黑名单任务
                MessageUtil.checkResultCodeAndMarkTaskBlackList("AntFarmDoFarmTaskList", title, jo);
                return false;
            }
            JSONObject extInfo = jo.getJSONObject("extInfo");
            boolean correct = jo.getBoolean("correct");
            String award = extInfo.getString("award");
            Log.farm("庄园答题📝回答" + (correct ? "正确" : "错误") + "#获得[" + award + "g饲料]");
            if (jo.has("operationConfigList")) {
                JSONArray operationConfigList = jo.getJSONArray("operationConfigList");
                savePreviewQuestion(operationConfigList);
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "doAnswerTask err:", t);
        }
        return false;
    }

    private void savePreviewQuestion(JSONArray operationConfigList) {
        try {
            for (int i = 0; i < operationConfigList.length(); i++) {
                JSONObject jo = operationConfigList.getJSONObject(i);
                String type = jo.getString("type");
                if (Objects.equals(type, "PREVIEW_QUESTION")) {
                    String question = jo.getString("title");
                    JSONArray ja = new JSONArray(jo.getString("actionTitle"));
                    for (int j = 0; j < ja.length(); j++) {
                        jo = ja.getJSONObject(j);
                        if (jo.getBoolean("correct")) {
                            TokenConfig.saveAnswer(question, jo.getString("title"));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "saveAnswerList err:", t);
        }
    }

    private Boolean doFarmTask(JSONObject task) {
        boolean isDoTask = false;
        try {
            String title = task.getString("title");
            String bizKey = task.getString("bizKey");
            String taskId = task.optString("taskId");
            // 诊断：多阶段游戏任务的 bizKey 仅在运行时由服务端下发，App 未写死中文名，
            // 单独打印 title 便于定位具体是哪个小游戏（命中 1009 风控前也能看到）
            if (bizKey.contains("multistage_gametask")) {
                Log.record("庄园多阶段游戏任务🎮[" + title + "]#bizKey=" + bizKey);
            }
            if (bizKey.contains("HEART_DONAT") || bizKey.equals("BAIDUJS_202512")) {
                return false;
            }
            // 按稳定 taskId 分派（2026-09-22 抓包实测）：
            //   视频任务 taskId="VIDEO_TASK"、答题任务 taskId="ANSWER"
            // 注意视频任务的标题实际是「看庄园小视频」——原先写的是 equals("庄园小视频")，
            // 少一个"看"字，导致这两类任务**从来没有走对过接口**（一直落到通用 doFarmTask 分支）
            if ("VIDEO_TASK".equals(taskId)) {
                // 视频任务：普通申报接口做不了，必须把"观看行为"伪造出来（见 doVideoTask）。
                // 完成判定以任务列表为准（探针复核），响应不可信。
                // 由 TaskAttemptPolicy 管节流与失败分类：同一天同一任务只伪造一次，失败不再每轮白等 15 秒
                Outcome outcome = TaskAttemptPolicy.handle("farm::video::" + title, title, null,
                        () -> attemptVideoTask(title, bizKey), Log::farm,
                        new TaskAttemptPolicy.Site("AntFarmDoFarmTaskList", "庄园饲料任务", bizKey, "",
                                TaskAlternative.DEFAULT_VERSION,
                                () -> doVideoTask(title) == Outcome.DONE,
                                (k) -> probeFarmStatus(bizKey)));
                isDoTask = outcome == Outcome.DONE || outcome == Outcome.TRIGGERED;
            } else if ("ANSWER".equals(taskId)) {
                isDoTask = doAnswerTask(title);
            } else {
                // 完成判定以任务列表为准，不以 doFarmTask 响应为准（见 TaskAttemptPolicy）
                Outcome outcome = TaskAttemptPolicy.handle("farm::task::" + bizKey, title, null,
                        () -> attemptFarmTask(title, bizKey), Log::farm,
                        new TaskAttemptPolicy.Site("AntFarmDoFarmTaskList", "庄园饲料任务", bizKey, "",
                                (k) -> probeFarmStatus(bizKey)));
                isDoTask = outcome == Outcome.DONE || outcome == Outcome.TRIGGERED;
            }

            if (isDoTask) {
                Log.farm("饲料任务🧾完成[" + title + "]");
            } else {
            }
        } catch (Throwable t) {
            Log.err(TAG, "doFarmTask err:", t);
        }
        return isDoTask;
    }

    private Boolean receiveFarmTaskAward(JSONObject task) {
        try {
            String taskId = task.getString("taskId");
            String awardType = task.optString("awardType", "");
            int awardCount = task.optInt("awardCount", 0);
            if (Objects.equals(awardType, "ALLPURPOSE")) {
                if (awardCount + foodStock > foodStockLimit) {
                    unReceiveTaskAward++;
                    return false;
                }
            }
            JSONObject jo = new JSONObject(AntFarmRpcCall.receiveFarmTaskAward(taskId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                // 领奖收口：先按任务列表复核"已领到"，未确认才交自动拉黑（顺序由 TaskAward 固定）
                if (TaskAward.confirmReceivedOrBlackList("饲料领取🎖️任务",
                        k -> probeFarmStatus(task.optString("bizKey")), task.optString("bizKey"),
                        task.optString("title", ""),
                        () -> MessageUtil.checkResultCodeAndMarkTaskBlackList("AntFarmDoFarmTaskList",
                                task.optString("title", ""), jo),
                        msg -> Log.farm(msg))) {
                    return true;
                }
                // 服务端繁忙(102)：本轮不再继续领其余任务，避免整轮反复白刷（请求本身已经发出过）
                if (MessageUtil.isServerBusy(jo) && !farmTaskAwardBusy) {
                    farmTaskAwardBusy = true;
                    Log.record("服务端繁忙🌧️本轮跳过剩余饲料任务领取");
                }
                // 槽已满(331)：与具体任务无关，继续领只会逐个被拒
                if (CODE_FEED_TROUGH_FULL.equals(jo.optString("resultCode")) && !awardTroughFullThisRun) {
                    awardTroughFullThisRun = true;
                    Log.record("饲料槽已满，本轮跳过剩余饲料任务领取");
                }
                return false;
            }
            if (awardType.equals("ALLPURPOSE")) {
                add2FoodStock(awardCount);
                String title = task.optString("title", "");
                Log.farm("饲料领取🎖️任务[" + title + "]奖励#获得[" + awardCount + "g]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "receiveFarmTaskAward err:", t);
        }
        return false;
    }

    private void checkUnReceiveTaskAward() {
        if (unReceiveTaskAward > 0) {
            // 计数来自 receiveFarmTaskAward：奖励会撑破饲料仓（awardCount + foodStock > foodStockLimit），
            // 服务端拒收。说清是"仓满领不下"，而不是含糊的"还有待领取"
            Log.farm("饲料仓已满[" + foodStock + "/" + foodStockLimit + "g]#"
                    + unReceiveTaskAward + "个任务的饲料奖励暂无法领取");
            unReceiveTaskAward = 0;
            listFarmTask(TaskStatus.FINISHED);
        }
    }

    private void feedAnimal(String farmId) {
        if (feedTroughFullThisRun) {
            return;
        }
        try {
            syncAnimalStatus(ownerFarmId);
            if (foodStock < 180) {
                Log.record("剩余饲料不足以投喂小鸡");
                return;
            }
            JSONObject jo = new JSONObject(AntFarmRpcCall.feedAnimal(farmId));
            boolean ok = MessageUtil.checkMemo(TAG, jo);
            if (!ok && CODE_FEED_TROUGH_FULL.equals(jo.optString("resultCode"))) {
                // 服务端回读：饲料槽已满，本轮不再投喂 (下一轮再看)
                feedTroughFullThisRun = true;
                Log.record("投喂小鸡⏭️饲料槽已满，本轮不再投喂");
                return;
            }
            if (ok) {
                int feedFood = foodStock - jo.getInt("foodStock");
                add2FoodStock(-feedFood);
                Log.farm("投喂小鸡🥣消耗[" + feedFood + "g]#剩余[" + foodStock + "g饲料]");
                if (useAccelerateTool.getValue()) {
                    TimeUtil.sleep(1000);
                    useAccelerateTool();
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "feedAnimal err:", t);
        } finally {
            long updateTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
            String taskId = "UPDATE|FA|" + farmId;
            addChildTask(new ChildModelTask(taskId, "UPDATE", this::autoFeedAnimal, updateTime));
        }
    }

    private void listFarmTool() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.listFarmTool());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONArray jaToolList = jo.getJSONArray("toolList");
            farmTools = new FarmTool[jaToolList.length()];
            for (int i = 0; i < jaToolList.length(); i++) {
                jo = jaToolList.getJSONObject(i);
                farmTools[i] = new FarmTool();
                farmTools[i].toolId = jo.optString("toolId", "");
                farmTools[i].toolType = ToolType.valueOf(jo.getString("toolType"));
                farmTools[i].toolCount = jo.getInt("toolCount");
                farmTools[i].toolHoldLimit = jo.optInt("toolHoldLimit", 20);
            }
        } catch (Throwable t) {
            Log.err(TAG, "listFarmTool err:", t);
        }
    }

    private void useAccelerateTool() {
        if (!Status.canUseAccelerateToolToday()) {
            return;
        }
        syncAnimalStatus(ownerFarmId);
        if ((!useAccelerateToolOptions.getValue().contains("useAccelerateToolContinue") && AnimalBuff.ACCELERATING.name().equals(ownerAnimal.animalBuff)) || (useAccelerateToolOptions.getValue().contains("useAccelerateToolWhenMaxEmotion") && finalScore != 100)) {
            return;
        }
        double consumeSpeed = 0d;
        double foodHaveEatten = 0d;
        long nowTime = System.currentTimeMillis() / 1000;
        for (Animal animal : animals) {
            if (animal.masterFarmId.equals(ownerFarmId)) {
                consumeSpeed = animal.consumeSpeed;
            }
            foodHaveEatten += animal.consumeSpeed * (nowTime - animal.startEatTime / 1000);
        }
        // consumeSpeed: g/s
        // AccelerateTool: -1h = -60m = -3600s
        while (foodInTrough - foodHaveEatten >= consumeSpeed * 3600 && useFarmTool(ownerFarmId, ToolType.ACCELERATETOOL)) {
            TimeUtil.sleep(1000);
            foodHaveEatten += consumeSpeed * 3600;
            Status.useAccelerateToolToday();
            if (!Status.canUseAccelerateToolToday()) {
                break;
            }
            if (!useAccelerateToolOptions.getValue().contains("useAccelerateToolContinue")) {
                break;
            }
        }
    }

    private Boolean useFarmTool(String targetFarmId, ToolType toolType) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.listFarmTool());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return false;
            }
            JSONArray jaToolList = jo.getJSONArray("toolList");
            for (int i = 0; i < jaToolList.length(); i++) {
                jo = jaToolList.getJSONObject(i);
                if (!toolType.name().equals(jo.getString("toolType"))) {
                    continue;
                }
                int toolCount = jo.optInt("toolCount");
                if (toolCount == 0) {
                    return false;
                }
                String toolId = jo.optString("toolId");
                jo = new JSONObject(AntFarmRpcCall.useFarmTool(targetFarmId, toolId, toolType.name()));
                if (MessageUtil.checkMemo(TAG, jo)) {
                    Log.farm("使用道具🎭[" + toolType.nickName() + "]#剩余" + (toolCount - 1) + "张");
                    return true;
                } else if (Objects.equals("3D16", jo.getString("resultCode"))) {
                    Status.flagToday("farm::useFarmToolLimit::" + toolType);
                }
                break;
            }
        } catch (Throwable t) {
            Log.err(TAG, "useFarmTool err:", t);
        }
        return false;
    }

    private void feedFriend() {
        try {
            Map<String, Integer> feedFriendAnimalMap = feedFriendAnimalList.getValue();
            for (Map.Entry<String, Integer> entry : feedFriendAnimalMap.entrySet()) {
                String userId = entry.getKey();
                if (userId.equals(UserIdMap.getCurrentUid())) {
                    continue;
                }
                if (!Status.canFeedFriendToday(userId, entry.getValue())) {
                    continue;
                }
                JSONObject jo = new JSONObject(AntFarmRpcCall.enterFarm("", userId));
                if (!MessageUtil.checkMemo(TAG, jo)) {
                    continue;
                }
                jo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO");
                String friendFarmId = jo.getString("farmId");
                int foodInTrough = jo.optInt("foodInTrough", 0);
                
                // 食槽为空时帮喂
                if (foodInTrough == 0) {
                    JSONArray jaAnimals = jo.getJSONArray("animals");
                    for (int j = 0; j < jaAnimals.length(); j++) {
                        JSONObject animal = jaAnimals.getJSONObject(j);
                        String masterFarmId = animal.getString("masterFarmId");
                        
                        // 只处理好友自己的小鸡
                        if (masterFarmId.equals(friendFarmId)) {
                            // 检查小鸡是否太小
                            if (animal.optBoolean("littleChick", false)) {
                                Log.record("跳过帮喂：好友的小鸡太小");
                                break;
                            }
                            
                            JSONObject animalStatusVO = animal.getJSONObject("animalStatusVO");
                            String animalInteractStatus = animalStatusVO.getString("animalInteractStatus");
                            String animalFeedStatus = animalStatusVO.getString("animalFeedStatus");
                            
                            // 好友自己的小鸡在家且饥饿 → 帮喂
                            if (AnimalInteractStatus.HOME.name().equals(animalInteractStatus) 
                                && AnimalFeedStatus.HUNGRY.name().equals(animalFeedStatus)) {
                                feedFriendAnimal(friendFarmId);
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "feedFriend err:", t);
        }
    }

    private void feedFriendAnimal(String friendFarmId) {
        // 当日帮喂总数已达上限（服务端 391 已记录）时直接跳过，避免逐个好友白跑请求
        if (Status.hasFlagToday(Status.FLAG_FEED_FRIEND_ANIMAL_LIMIT)) {
            Log.record("今日帮喂次数已达上限🥣，跳过喂养");
            return;
        }
        try {
            String userId = AntFarmRpcCall.farmId2UserId(friendFarmId);
            String maskName = UserIdMap.getMaskName(userId);
            Log.record("[" + maskName + "]的小鸡在挨饿");
            if (foodStock < 180) {
                Log.record("喂鸡饲料不足");
                checkUnReceiveTaskAward();
                if (foodStock < 180) {
                    return;
                }
            }
            String groupId = null;
            if (family.getValue()) {
                groupId = getFamilyGroupId(userId);
                if (StringUtil.isEmpty(groupId) || !Objects.equals(ownerGroupId, groupId)) {
                    groupId = null;
                }
            }
            if (feedFriendAnimal(friendFarmId, groupId)) {
                String s = StringUtil.isEmpty(groupId) ? "帮喂小鸡🥣帮喂好友" : "亲密家庭🏠帮喂成员";
                s = s + "[" + maskName + "]" + "的小鸡#剩余[" + foodStock + "g饲料]";
                Log.farm(s);
                Status.feedFriendToday(AntFarmRpcCall.farmId2UserId(friendFarmId));
            }
        } catch (Throwable t) {
            Log.err(TAG, "feedFriendAnimal err:", t);
        }
    }

    private Boolean feedFriendAnimal(String friendFarmId, String groupId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.feedFriendAnimal(friendFarmId, groupId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                if (Objects.equals("391", jo.optString("resultCode"))) {
                    Status.flagToday(Status.FLAG_FEED_FRIEND_ANIMAL_LIMIT);
                }
                return false;
            }
            int feedFood = foodStock - jo.getInt("foodStock");
            if (feedFood > 0) {
                add2FoodStock(-feedFood);
                return true;
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "feedFriendAnimal err:", t);
        }
        return false;
    }

    private void notifyFriend() {
        if (foodStock >= foodStockLimit) {
            return;
        }
        try {
            boolean hasNext = false;
            int pageStartSum = 0;
            String s;
            JSONObject jo;
            do {
                s = AntFarmRpcCall.rankingList(pageStartSum);
                jo = new JSONObject(s);
                if (!MessageUtil.checkMemo(TAG, jo)) {
                    break;
                }
                hasNext = jo.getBoolean("hasNext");
                JSONArray jaRankingList = jo.getJSONArray("rankingList");
                pageStartSum += jaRankingList.length();
                for (int i = 0; i < jaRankingList.length(); i++) {
                    jo = jaRankingList.getJSONObject(i);
                    String userId = jo.getString("userId");
                    String userName = UserIdMap.getMaskName(userId);
                    boolean isNotifyFriend = notifyFriendList.getValue().contains(userId);
                    if (notifyFriendType.getValue() != NotifyFriendType.NOTIFY) {
                        isNotifyFriend = !isNotifyFriend;
                    }
                    if (!isNotifyFriend || userId.equals(UserIdMap.getCurrentUid())) {
                        continue;
                    }
                    boolean starve = jo.has("actionType") && "starve_action".equals(jo.getString("actionType"));
                    if (jo.getBoolean("stealingAnimal") && !starve) {
                        jo = new JSONObject(AntFarmRpcCall.enterFarm("", userId));
                        if (!MessageUtil.checkMemo(TAG, jo)) {
                            continue;
                        }
                        jo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO");
                        String friendFarmId = jo.getString("farmId");
                        JSONArray jaAnimals = jo.getJSONArray("animals");
                        for (int j = 0; j < jaAnimals.length(); j++) {
                            jo = jaAnimals.getJSONObject(j);
                            String animalId = jo.getString("animalId");
                            String masterFarmId = jo.getString("masterFarmId");
                            if (!masterFarmId.equals(friendFarmId) && !masterFarmId.equals(ownerFarmId)) {
                                jo = jo.getJSONObject("animalStatusVO");
                                if (notifyFriend(jo, friendFarmId, animalId, userName)) {
                                    break;
                                }
                            }
                        }
                    }
                }
            } while (hasNext);
            Log.record("饲料剩余[" + foodStock + "g]");
        } catch (Throwable t) {
            Log.err(TAG, "notifyFriend err:", t);
        }
    }

    private Boolean notifyFriend(JSONObject joAnimalStatusVO, String friendFarmId, String animalId, String user) {
        try {
            if (AnimalInteractStatus.STEALING.name().equals(joAnimalStatusVO.getString("animalInteractStatus")) && AnimalFeedStatus.EATING.name().equals(joAnimalStatusVO.getString("animalFeedStatus"))) {
                JSONObject jo = new JSONObject(AntFarmRpcCall.notifyFriend(animalId, friendFarmId));
                if (!MessageUtil.checkMemo(TAG, jo)) {
                    return false;
                }
                int rewardCount = (int) jo.getDouble("rewardCount");
                if (jo.getBoolean("refreshFoodStock")) {
                    foodStock = (int) jo.getDouble("finalFoodStock");
                } else {
                    add2FoodStock(rewardCount);
                }
                Log.farm("通知赶鸡📧提醒[" + user + "]被偷吃#获得[" + rewardCount + "g饲料]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "notifyFriend err:", t);
        }
        return false;
    }

    private void parseSyncAnimalStatusResponse(String resp) {
        try {
            JSONObject jo = new JSONObject(resp);
            if (!jo.has("subFarmVO")) {
                return;
            }
            if (jo.has("emotionInfo")) {
                finalScore = jo.getJSONObject("emotionInfo").getDouble("finalScore");
            }
            JSONObject subFarmVO = jo.getJSONObject("subFarmVO");
            if (subFarmVO.has("foodStock")) {
                foodStock = subFarmVO.getInt("foodStock");
            }
            if (subFarmVO.has("foodInTrough")) {
                foodInTrough = subFarmVO.getInt("foodInTrough");
            }
            if (subFarmVO.has("manureVO")) {
                JSONArray manurePotList = subFarmVO.getJSONObject("manureVO").getJSONArray("manurePotList");
                for (int i = 0; i < manurePotList.length(); i++) {
                    JSONObject manurePot = manurePotList.getJSONObject(i);
                    if (manurePot.getInt("manurePotNum") >= 100) {
                        JSONObject joManurePot = new JSONObject(AntFarmRpcCall.collectManurePot(manurePot.getString("manurePotNO")));
                        if (joManurePot.optBoolean("success")) {
                            int collectManurePotNum = joManurePot.getInt("collectManurePotNum");
                            Log.farm("打扫鸡屎🧹获得[" + collectManurePotNum + "g肥料]");
                        }
                    }
                }
            }
            ownerFarmId = subFarmVO.getString("farmId");
            JSONObject farmProduce = subFarmVO.getJSONObject("farmProduce");
            benevolenceScore = farmProduce.getDouble("benevolenceScore");
            if (subFarmVO.has("rewardList")) {
                JSONArray jaRewardList = subFarmVO.getJSONArray("rewardList");
                if (jaRewardList.length() > 0) {
                    rewardList = new RewardFriend[jaRewardList.length()];
                    for (int i = 0; i < rewardList.length; i++) {
                        JSONObject joRewardList = jaRewardList.getJSONObject(i);
                        if (rewardList[i] == null) {
                            rewardList[i] = new RewardFriend();
                        }
                        rewardList[i].consistencyKey = joRewardList.getString("consistencyKey");
                        rewardList[i].friendId = joRewardList.getString("friendId");
                        rewardList[i].time = joRewardList.getString("time");
                    }
                }
            }
            JSONArray jaAnimals = subFarmVO.getJSONArray("animals");
            animals = new Animal[jaAnimals.length()];
            for (int i = 0; i < animals.length; i++) {
                Animal animal = new Animal();
                JSONObject animalJsonObject = jaAnimals.getJSONObject(i);
                animal.animalId = animalJsonObject.optString("animalId", "");
                animal.currentFarmId = animalJsonObject.optString("currentFarmId", "");
                animal.masterFarmId = animalJsonObject.optString("masterFarmId", "");
                // NPC 条目可能缺字段：一律 opt 取值，避免一只 NPC 中断整段 animals 解析
                animal.animalBuff = animalJsonObject.optString("animalBuff", "");
                animal.subAnimalType = animalJsonObject.optString("subAnimalType", "");
                animal.currentFarmMasterUserId = animalJsonObject.optString("currentFarmMasterUserId", "");
                animal.locationType = animalJsonObject.optString("locationType", "");
                JSONObject animalStatusVO = animalJsonObject.optJSONObject("animalStatusVO");
                if (animalStatusVO != null) {
                    animal.animalFeedStatus = animalStatusVO.optString("animalFeedStatus", "");
                    animal.animalInteractStatus = animalStatusVO.optString("animalInteractStatus", "");
                }
                animal.startEatTime = animalJsonObject.optLong("startEatTime");
                animal.beHiredEndTime = animalJsonObject.optLong("beHiredEndTime");
                animal.consumeSpeed = animalJsonObject.optDouble("consumeSpeed");
                animal.foodHaveEatten = animalJsonObject.optDouble("foodHaveEatten");
                if (animal.masterFarmId.equals(ownerFarmId)) {
                    ownerAnimal = animal;
                }
                animals[i] = animal;
            }
        } catch (Throwable t) {
            Log.err(TAG, "parseSyncAnimalStatusResponse err:", t);
        }
    }

    private void add2FoodStock(int i) {
        foodStock += i;
        if (foodStock > foodStockLimit) {
            foodStock = foodStockLimit;
        }
        if (foodStock < 0) {
            foodStock = 0;
        }
    }

    private void collectDailyFoodMaterial(String userId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterKitchen(userId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            boolean canCollectDailyFoodMaterial = jo.getBoolean("canCollectDailyFoodMaterial");
            int dailyFoodMaterialAmount = jo.getInt("dailyFoodMaterialAmount");
            int garbageAmount = jo.optInt("garbageAmount", 0);
            if (jo.has("orchardFoodMaterialStatus")) {
                JSONObject orchardFoodMaterialStatus = jo.getJSONObject("orchardFoodMaterialStatus");
                if ("FINISHED".equals(orchardFoodMaterialStatus.optString("foodStatus"))) {
                    jo = new JSONObject(AntFarmRpcCall.farmFoodMaterialCollect());
                    if ("100".equals(jo.getString("resultCode"))) {
                        Log.farm("小鸡厨房👨🏻‍🍳农场食材#领取[" + jo.getInt("foodMaterialAddCount") + "g食材]");
                    } else {
                        Log.i(TAG, jo.toString());
                    }
                }
            }
            if (canCollectDailyFoodMaterial) {
                jo = new JSONObject(AntFarmRpcCall.collectDailyFoodMaterial(dailyFoodMaterialAmount));
                if (MessageUtil.checkMemo(TAG, jo)) {
                    Log.farm("小鸡厨房👨🏻‍🍳今日食材#领取[" + dailyFoodMaterialAmount + "g食材]");
                }
            }
            if (garbageAmount > 0) {
                jo = new JSONObject(AntFarmRpcCall.collectKitchenGarbage());
                if (MessageUtil.checkMemo(TAG, jo)) {
                    Log.farm("小鸡厨房👨🏻‍🍳收集厨余#获得[" + jo.getInt("recievedKitchenGarbageAmount") + "g肥料]");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "collectDailyFoodMaterial err:", t);
        }
    }

    private void collectDailyLimitedFoodMaterial() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryFoodMaterialPack());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            boolean canCollectDailyLimitedFoodMaterial = jo.optBoolean("canCollectDailyLimitedFoodMaterial", false);
            if (canCollectDailyLimitedFoodMaterial) {
                int dailyLimitedFoodMaterialAmount = jo.optInt("dailyLimitedFoodMaterialAmount", 0);
                if (dailyLimitedFoodMaterialAmount <= 0) {
                    return;
                }
                jo = new JSONObject(AntFarmRpcCall.collectDailyLimitedFoodMaterial(dailyLimitedFoodMaterialAmount));
                if (MessageUtil.checkMemo(TAG, jo)) {
                    Log.farm("小鸡厨房👨🏻‍🍳领取[爱心食材店食材]#" + dailyLimitedFoodMaterialAmount + "g");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "collectDailyLimitedFoodMaterial err:", t);
        }
    }

    private void cook(String userId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterKitchen(userId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            int cookTimesAllowed = jo.getInt("cookTimesAllowed");
            if (cookTimesAllowed > 0) {
                for (int i = 0; i < cookTimesAllowed; i++) {
                    jo = new JSONObject(AntFarmRpcCall.cook(userId));
                    if (MessageUtil.checkMemo(TAG, jo)) {
                        JSONObject cuisineVO = jo.getJSONObject("cuisineVO");
                        Log.farm("小鸡厨房👨🏻‍🍳制作[" + cuisineVO.getString("name") + "]");
                    }
                    TimeUtil.sleep(RandomUtil.delay());
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "cook err:", t);
        }
    }

    private List<JSONObject> getSortedCuisineList(JSONArray cuisineList) {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < cuisineList.length(); i++) {
            list.add(cuisineList.optJSONObject(i));
        }
        Collections.sort(list, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject jsonObject1, JSONObject jsonObject2) {
                int count1 = jsonObject1.optInt("count");
                int count2 = jsonObject2.optInt("count");
                return count2 - count1;
            }
        });
        return list;
    }

    private void useFarmFood(JSONArray cuisineList) {
        try {
            List<JSONObject> list = getSortedCuisineList(cuisineList);
            for (int i = 0; i < list.size(); i++) {
                if (!useFarmFood(list.get(i))) {
                    return;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "useFarmFood err:", t);
        }
    }

    private Boolean useFarmFood(JSONObject cuisine) {
        if (!Status.canUseSpecialFoodToday()) {
            return false;
        }
        try {
            String cookbookId = cuisine.getString("cookbookId");
            String cuisineId = cuisine.getString("cuisineId");
            String name = cuisine.getString("name");
            int count = cuisine.getInt("count");
            // 厨房赛季菜必须原样带上 cuisineType/resultLevel，否则服务端定位不到赛季食谱（回 U05 食谱未配置）
            String cuisineType = cuisine.optString("cuisineType", "");
            String resultLevel = cuisine.optString("resultLevel", "");
            String unusableFlag = FLAG_UNUSABLE_CUISINE + cuisineId;
            if (Status.hasFlagToday(unusableFlag)) {
                // 当天已确认这道菜没配喂鸡食谱：不再发请求（厨房赛季菜配好后第二天自动就能喂）
                return true;
            }
            for (int j = 0; j < count; j++) {
                JSONObject jo = new JSONObject(AntFarmRpcCall.useFarmFood(cookbookId, cuisineId, cuisineType, resultLevel));
                if (!MessageUtil.checkMemo(TAG, jo)) {
                    if (jo.optString("memo", "").contains("食谱未配置")) {
                        // 只是这一道菜不在喂鸡食谱里：跳过它继续下一道，别让整轮就此停住
                        Status.flagToday(unusableFlag);
                        return true;
                    }
                    return false;
                }
                double deltaProduce = jo.getJSONObject("foodEffect").getDouble("deltaProduce");
                Log.farm("使用美食🍱[" + name + "]#加速" + deltaProduce + "颗爱心鸡蛋");
                Status.useSpecialFoodToday();
                if (!Status.canUseSpecialFoodToday()) {
                    break;
                }
            }
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "useFarmFood err:", t);
        }
        return false;
    }

    private void drawLotteryPlus(JSONObject lotteryPlusInfo) {
        try {
            if (!lotteryPlusInfo.has("userSevenDaysGiftsItem")) {
                return;
            }
            String itemId = lotteryPlusInfo.getString("itemId");
            JSONObject jo = lotteryPlusInfo.getJSONObject("userSevenDaysGiftsItem");
            JSONArray ja = jo.getJSONArray("userEverydayGiftItems");
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                if (jo.getString("itemId").equals(itemId)) {
                    if (!jo.getBoolean("received")) {
                        String singleDesc = jo.getString("singleDesc");
                        int awardCount = jo.getInt("awardCount");
                        if (singleDesc.contains("饲料") && awardCount + foodStock > foodStockLimit) {
                            Log.record("暂停领取[" + awardCount + "]克饲料，上限为[" + foodStockLimit + "]克");
                            break;
                        }
                        jo = new JSONObject(AntFarmRpcCall.drawLotteryPlus());
                        if (MessageUtil.checkMemo(TAG, jo)) {
                            Log.farm("惊喜礼包🎁[" + singleDesc + "*" + awardCount + "]");
                        }
                    } else {
                        Log.record("当日奖励已领取");
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "drawLotteryPlus err:", t);
        }
    }

    private void visitFriend() {
        Map<String, Integer> map = visitFriendList.getValue();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            String userId = entry.getKey();
            Integer countLimit = entry.getValue();
            if (userId.equals(UserIdMap.getCurrentUid())) {
                continue;
            }
            if (Status.canVisitFriendToday(userId, countLimit)) {
                visitFriend(userId, countLimit);
            }
        }
    }

    private void visitFriend(String userId, int countLimit) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterFarm(userId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONObject farmVO = jo.getJSONObject("farmVO");
            foodStock = farmVO.getInt("foodStock");
            JSONObject subFarmVO = farmVO.getJSONObject("subFarmVO");
            if (subFarmVO.optBoolean("visitedToday", true)) {
                Status.flagToday("farm::visitFriendLimit::" + userId);
                return;
            }
            String farmId = subFarmVO.getString("farmId");
            while (Status.canVisitFriendToday(userId, countLimit) && foodStock >= 10) {
                jo = new JSONObject(AntFarmRpcCall.visitFriend(farmId));
                if (!MessageUtil.checkMemo(TAG, jo)) {
                    break;
                }
                TimeUtil.sleep(1000);
                Status.visitFriendToday(userId);
                foodStock = jo.getInt("foodStock");
                Log.farm("赠送麦子🌾赠送[" + UserIdMap.getMaskName(userId) + "]麦子#消耗[" + jo.getInt("giveFoodNum") + "g饲料]");
                if (jo.optBoolean("isReachLimit")) {
                    Log.record("今日给[" + UserIdMap.getMaskName(userId) + "]送麦子已达上限");
                    Status.flagToday("farm::visitFriendLimit::" + userId);
                    break;
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "visitFriend err:", t);
        }
    }

    private void acceptGift() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.acceptGift());
            if (MessageUtil.checkMemo(TAG, jo)) {
                int receiveFoodNum = jo.getInt("receiveFoodNum");
                Log.farm("收取麦子🌾[" + receiveFoodNum + "g]");
            }
        } catch (Throwable t) {
            Log.err(TAG, "acceptGift err:", t);
        }
    }

    private void queryChickenDiary(String queryDayStr) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryChickenDiary(queryDayStr));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject data = jo.getJSONObject("data");
            JSONObject chickenDiary = data.getJSONObject("chickenDiary");
            String diaryDateStr = chickenDiary.getString("diaryDateStr");
            if (data.has("hasTietie")) {
                if (!data.optBoolean("hasTietie", true)) {
                    jo = new JSONObject(AntFarmRpcCall.diaryTietie(diaryDateStr, "NEW"));
                    if (MessageUtil.checkMemo(TAG, jo)) {
                        String prizeType = jo.getString("prizeType");
                        int prizeNum = jo.optInt("prizeNum", 0);
                        Log.farm("贴贴小鸡💞奖励[" + prizeType + "*" + prizeNum + "]");
                    }
                    if (!chickenDiary.has("statisticsList")) {
                        return;
                    }
                    JSONArray statisticsList = chickenDiary.getJSONArray("statisticsList");
                    if (statisticsList.length() > 0) {
                        for (int i = 0; i < statisticsList.length(); i++) {
                            JSONObject tietieStatus = statisticsList.getJSONObject(i);
                            String tietieRoleId = tietieStatus.getString("tietieRoleId");
                            jo = new JSONObject(AntFarmRpcCall.diaryTietie(diaryDateStr, tietieRoleId));
                            if (MessageUtil.checkMemo(TAG, jo)) {
                                String prizeType = jo.getString("prizeType");
                                int prizeNum = jo.optInt("prizeNum", 0);
                                Log.farm("贴贴小鸡💞奖励[" + prizeType + "*" + prizeNum + "]");
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryChickenDiary err:", t);
        }
    }

    private void queryChickenDiaryList() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryChickenDiaryList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray chickenDiaryBriefList = jo.getJSONObject("data").optJSONArray("chickenDiaryBriefList");
            if (chickenDiaryBriefList != null && chickenDiaryBriefList.length() > 0) {
                for (int i = 0; i < chickenDiaryBriefList.length(); i++) {
                    jo = chickenDiaryBriefList.getJSONObject(i);
                    if (!jo.optBoolean("read", true)) {
                        String dateStr = jo.getString("dateStr");
                        queryChickenDiary(dateStr);
                        TimeUtil.sleep(300);
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryChickenDiaryList err:", t);
        }
    }

    private void visitAnimal() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.visitAnimal());
            if (!MessageUtil.checkMemo(TAG, jo) || !jo.has("talkConfigs")) {
                return;
            }

            JSONArray talkNodes = jo.getJSONArray("talkNodes");
            JSONArray talkConfigs = jo.getJSONArray("talkConfigs");
            JSONObject data = talkConfigs.getJSONObject(0);
            String farmId = data.getString("farmId");
            jo = new JSONObject(AntFarmRpcCall.feedFriendAnimalVisit(farmId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONArray actionNodes = null;
            for (int i = 0; i < talkNodes.length(); i++) {
                jo = talkNodes.getJSONObject(i);
                if (jo.has("actionNodes")) {
                    actionNodes = jo.getJSONArray("actionNodes");
                    break;
                }
            }
            if (actionNodes == null) {
                return;
            }
            for (int i = 0; i < actionNodes.length(); i++) {
                jo = actionNodes.getJSONObject(i);
                if (!"FEED".equals(jo.getString("type"))) {
                    continue;
                }
                String consistencyKey = jo.getString("consistencyKey");
                jo = new JSONObject(AntFarmRpcCall.visitAnimalSendPrize(consistencyKey));
                if (MessageUtil.checkMemo(TAG, jo)) {
                    String prizeName = jo.getString("prizeName");
                    String userMaskName = UserIdMap.getMaskName(AntFarmRpcCall.farmId2UserId(farmId));
                    Log.farm("小鸡到访💞投喂[" + userMaskName + "]#获得[" + prizeName + "]");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "visitAnimal err:", t);
        }
    }

    //乐园限定活动（庄园 → 乐园限定活动，bizType=ANTFARM / sceneCode=ANTFARM_COMMON）
    //奖励类型 gameCoin（乐园币），场景码 ANTFARM_LEYUAN_DAILY_TASK
    private void queryOptionalPlay() {
        try {
            // 每个任务的每个阶段（taskType#rightsTimes）每轮最多动作一次：
            // 同一阶段重复动作会白跑，但阶段推进（领取后 rightsTimes 递增）要允许再做，故去重键带序号与轮次
            Set<String> attempted = new HashSet<>();
            int round = 0;
            while (round++ < MAX_OPTIONAL_PLAY_ROUNDS) {
                JSONObject jo = new JSONObject(AntFarmRpcCall.queryOptionalPlay());
                if (!MessageUtil.checkSuccess(TAG, jo)) {
                    return;
                }
                if (!jo.has("taskTriggerPlayInfo")) {
                    return;
                }
                JSONObject taskTriggerPlayInfo = jo.optJSONObject("taskTriggerPlayInfo");
                if (taskTriggerPlayInfo == null || !taskTriggerPlayInfo.has("taskList")) {
                    return;
                }
                JSONArray taskList = taskTriggerPlayInfo.getJSONArray("taskList");
                // 本轮是否还有新的动作（不含已试过的），没有就收工
                boolean progressed = false;

                for (int j = 0; j < taskList.length(); j++) {
                    JSONObject task = taskList.getJSONObject(j);
                    String taskType = task.optString("taskType");
                    String taskStatus = task.optString("taskStatus");
                    String sceneCode = task.optString("sceneCode");
                    String groupId = task.optString("groupId");
                    int alreadyReceiveAwardCount = task.optInt("alreadyReceiveAwardCount");
                    int awardCount = task.optInt("awardCount");
                    int awardCountForReceive = awardCount - alreadyReceiveAwardCount;
                    int rightsTimesLimit = task.optInt("rightsTimesLimit");
                    int rightsTimes = task.optInt("rightsTimes");
                    JSONObject bizInfo = task.optJSONObject("bizInfo");
                    if (bizInfo == null) {
                        continue;
                    }
                    String title = bizInfo.optString("title");
                    // 开箱类任务的完成条件是**服务端的开箱计数**，申报完成不会让计数推进
                    // （申报只会被拒，伪申报被拒后又会被判"兜底未生效"拉黑），只能真去开箱
                    String actionType = bizInfo.optString("actionType", "");
                    // 去重键带轮次与阶段序号：同一轮内同阶段只动作一次（防重复请求），
                    // 但下一轮可再试——服务端状态推进有延迟，跨轮仍可能落到新阶段
                    String attemptKey = taskType + "#" + rightsTimes + "@" + round;
                    // 领奖去重**不带轮次**且**跨调用生效**：发奖是幂等的，服务端对已领过的任务仍会成功
                    // 返回同一个 incAwardCount；只按轮次或单次调用去重会让重跑的每一轮都重复领一次。
                    // 故按"任务+阶段"打当日标记，一天只领一次。
                    String awardKey = "farm::optionalplay::award::" + sceneCode + "/" + taskType
                            + "#" + rightsTimes;

                    // 黑名单任务跳过
                    if (AntFarmDrawMachineTaskList.getValue().contains(title)) {
                        continue;
                    }
                    // 底线：交易/支付/充值类一律不申报、一次即永久拉黑，绝不伪造。
                    // 必须同时查 groupId：充值的 taskType 是 2026cc_1000lyb_A&h（无关键词），
                    // 真正的判据在 groupId=2026cc_cz6ylyb_fz（cz=充值）
                    if (TaskAlternative.isTransactionTask(taskType)
                            || TaskAlternative.isTransactionTask(groupId)
                            || TaskAlternative.isTransactionTask(sceneCode)) {
                        MessageUtil.MarkTaskBlackListPermanent("AntFarm", "AntFarmDrawMachineTaskList",
                                "庄园乐园限定任务", title);
                        Log.farm("小鸡乐园⏭️交易/履约类[" + title + "]#不申报，已永久拉黑");
                        continue;
                    }

                    // 已完成待领：领奖。已领满（awardCountForReceive<=0）不再重复请求；
                    // 当日已领过的同一阶段也不再重复请求（见 awardKey 注释）
                    if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                        if (awardCountForReceive > 0 && !Status.hasFlagToday(awardKey)) {
                            progressed = true;
                            JSONObject joReceived = new JSONObject(AntFarmRpcCall.receiveTaskAwardantfarm(awardCountForReceive, sceneCode, taskType));
                            if (MessageUtil.checkSuccess(TAG, joReceived)) {
                                Status.flagToday(awardKey);
                                int incAwardCount = joReceived.optInt("incAwardCount");
                                JSONObject taskConfigResultVO = joReceived.optJSONObject("taskConfigResultVO");
                                String awardType = taskConfigResultVO == null ? "乐园币" : taskConfigResultVO.optString("awardType", "乐园币");
                                Log.farm("小鸡乐园🎖️领取[" + title + "]奖励[" + awardType + "*" + incAwardCount + "]");
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
                        String policyKey = "farm::optionalplay::" + sceneCode + "/" + taskType
                                + (rightsTimesLimit > 1 ? "#" + rightsTimes : "");
                        // 走统一策略：完成后按列表核对，400000040 时由策略代为伪申报；
                        // 开箱类任务的尝试动作 = 真去开箱（申报完成对它是无效动作）
                        final boolean unwrapTask = isUnwrapAction(actionType);
                        final String taskTitleForLog = title;
                        TaskAttemptPolicy.Site playSite = new TaskAttemptPolicy.Site(
                                "AntFarmDrawMachineTaskList", "庄园乐园限定任务",
                                taskType, sceneCode, TaskAlternative.DEFAULT_VERSION, null,
                                (k) -> probeOptionalPlayStatus(sceneCode, taskType),
                                (k) -> probeOptionalPlayExhausted(sceneCode, taskType),
                                unwrapTask);
                        if (rightsTimesLimit > 1 && rightsTimes + 1 < rightsTimesLimit) {
                            // 多档里程碑的中间档（这次做完还有余量）：列表要打满才变完成，
                            // 列表复核会把它误判"未完成"，进而挡住后续档位
                            playSite = playSite.withTrustResponse();
                        }
                        Outcome outcome = TaskAttemptPolicy.handle(
                                policyKey, label, null,
                                () -> unwrapTask
                                        ? openFarmLeyuanBoxes(taskTitleForLog)
                                        : attemptOptionalPlayTask(sceneCode, taskType, label), Log::farm,
                                playSite);
                        // 只有真正推进了才算"本轮有新动作"：做不了（UNABLE）与临时故障（RETRY）
                        // 不该让外层重拉列表空转，被当日标记挡下（TRIED_TODAY）同样不算
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
     * 开箱类乐园任务的尝试动作：把当前可开宝箱逐个开掉（庄园侧接口一次开一个）。
     * <p>没有可开的箱子时返回 UNABLE——任务没完成是"箱子不够"，不是接口不行，不能退到伪申报。
     */
    private Outcome openFarmLeyuanBoxes(String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryGameList());
            if (!jo.optBoolean("success")) {
                return Outcome.RETRY;
            }
            JSONObject drawRights = jo.optJSONObject("gameCenterDrawRights");
            if (drawRights == null) {
                return Outcome.RETRY;
            }
            int quotaCanUse = drawRights.optInt("quotaCanUse");
            if (quotaCanUse <= 0) {
                Log.farm("小鸡乐园🎁[开宝箱]暂无可开宝箱#待攒够箱子");
                return Outcome.UNABLE;
            }
            int opened = 0;
            // 上限防服务端不扣额度时死循环；正常由 quotaCanUse 归零退出
            for (int i = 0; i < MAX_GAME_DRAW_ROUND && quotaCanUse > 0; i++) {
                JSONObject drawRes = new JSONObject(AntFarmRpcCall.drawGameCenterAward(1));
                if (!drawRes.optBoolean("success")) {
                    break;
                }
                JSONObject nextRights = drawRes.optJSONObject("gameCenterDrawRights");
                quotaCanUse = nextRights != null ? nextRights.optInt("quotaCanUse") : (quotaCanUse - 1);
                opened++;
                TimeUtil.sleep(3000);
            }
            Log.farm("小鸡乐园🎁开宝箱×" + opened + "#用于[" + taskTitle + "]");
            return opened > 0 ? Outcome.DONE : Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "openFarmLeyuanBoxes err:", t);
        }
        return Outcome.RETRY;
    }

    /** 乐园限定任务完成上报：{@code com.alipay.antiep.finishTask}。 */
    private Outcome attemptOptionalPlayTask(String sceneCode, String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.finishTask(taskType, sceneCode));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.farm("小鸡乐园🧾完成[" + taskTitle + "]");
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            // 400000040 不支持 rpc 调用 → 交回统一策略代为伪申报
            if (TaskAlternative.hit(jo, sceneCode)) {
                return Outcome.UNSUPPORTED;
            }
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "attemptOptionalPlayTask err:", t);
        }
        return Outcome.RETRY;
    }

    /** 乐园限定任务打满判据。 */
    private boolean probeOptionalPlayExhausted(String sceneCode, String taskType) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryOptionalPlay());
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

    /** 乐园限定任务探针：重拉列表，按 sceneCode+taskType 匹配该任务当前状态。 */
    private TaskAttemptPolicy.ProbeResult probeOptionalPlayStatus(String sceneCode, String taskType) {
        // 任务数组嵌在 taskTriggerPlayInfo 内层
        return TaskAttemptPolicy.probeByNestedList(TAG, AntFarmRpcCall::queryOptionalPlay,
                jo -> MessageUtil.checkSuccess(TAG, jo),
                jo -> {
                    JSONObject playInfo = jo.optJSONObject("taskTriggerPlayInfo");
                    return playInfo == null ? null : playInfo.optJSONArray("taskList");
                },
                task -> taskType.equals(task.optString("taskType")) && sceneCode.equals(task.optString("sceneCode"))
                        ? task.optString("taskStatus") : null);
    }

    /**
     * 乐园限定任务单轮最大重拉次数：正常由「本轮无任何可执行/可领取动作」提前收工，
     * 此上限只防服务端恒回 TODO 时死循环。
     */
    private static final int MAX_OPTIONAL_PLAY_ROUNDS = 12;

    //小鸡乐园兑奖
    // skuId, sku
    Map<String, JSONObject> skuInfo = new HashMap<>();

    private void gameCenterBuyMallItem() {
        try {
            // 清单当日拉一次即可：一次商城首页 + 每个 SPU 一次详情，且它同时是配置页兑奖选项的来源，
            // 不能完全不拉；拉不到 SKU 就不打标记，留给下一轮重试
            if (!Status.hasFlagToday("farm::mallSkuList") && getAllSkuInfo()) {
                Status.flagToday("farm::mallSkuList");
            }
            Map<String, Integer> buyList = gameCenterBuyMallItemList.getValue();
            for (Map.Entry<String, Integer> entry : buyList.entrySet()) {
                String skuId = entry.getKey();
                Integer count = entry.getValue();
                if (count == null || count < 0) {
                    continue;
                }
                while (Status.canGameCenterBuyMallItemToday(skuId, count) && BuyMallItem(skuId)) {
                    TimeUtil.sleep(3000);
                }
            }
            queryOptionalPlay();
        } catch (Throwable t) {
            Log.err(TAG, "gameCenterBuyMallItem err:", t);
        }
    }

    // 获取乐币购买商店列表
    private JSONArray getGameCenterMallItemList(String bizType) {
        JSONArray mallItemSimpleList = null;
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.getMallHome(bizType));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                mallItemSimpleList = jo.optJSONArray("mallItemSimpleList");
            }
        } catch (Throwable th) {
            Log.err(TAG, "getGameCenterMallItemList err:", th);
        }
        return mallItemSimpleList;
    }

    /**
     * 获取乐园商店所有商品信息。
     *
     * @return 是否真的取到 SKU（调用方据此决定要不要打当日标记，取不到时留给下一轮重试）
     */
    private boolean getAllSkuInfo() {
        try {
            int before = skuInfo.size();
            JSONArray mallItemSimpleList = getGameCenterMallItemList("ANTFARM_GAME_CENTER");
            if (mallItemSimpleList == null) {
                return false;
            }
            for (int i = 0; i < mallItemSimpleList.length(); i++) {
                JSONObject itemInfoVO = mallItemSimpleList.getJSONObject(i);
                getSkuInfoByItemInfoVO(itemInfoVO);
            }
            return skuInfo.size() > before;
        } catch (Throwable th) {
            Log.err(TAG, "getAllSkuInfo err:", th);
            return false;
        }
    }

    private void getSkuInfoBySpuId(String spuId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.getMallItemDetail(spuId));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            if (!jo.has("spuItemInfoVo")) {
                return;
            }
            JSONObject spuItemInfoVo = jo.optJSONObject("spuItemInfoVO");
            getSkuInfoByItemInfoVO(spuItemInfoVo);
        } catch (Throwable th) {
            Log.err(TAG, "getSkuInfoBySpuId err:", th);
        }
    }

    private void getSkuInfoByItemInfoVO(JSONObject spuItem) {
        try {
            String spuId = spuItem.getString("spuId");
            JSONObject jo = new JSONObject(AntFarmRpcCall.getMallItemDetail(spuId));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONObject mallItemDetail = jo.optJSONObject("mallItemDetail");
            if (!mallItemDetail.has("mallSubItemDetailList")) {
                return;
            }
            JSONArray mallSubItemDetailList = mallItemDetail.getJSONArray("mallSubItemDetailList");
            for (int i = 0; i < mallSubItemDetailList.length(); i++) {
                JSONObject skuModel = mallSubItemDetailList.getJSONObject(i);
                String skuId = skuModel.getString("skuId");
                String skuName = skuModel.getString("skuName");
                if (!skuModel.has("spuId")) {
                    skuModel.put("spuId", spuId);
                }
                skuInfo.put(skuId, skuModel);
                GameCenterMallItemMap.add(skuId, skuName);
            }
            GameCenterMallItemMap.save(UserIdMap.getCurrentUid());
        } catch (Throwable th) {
            Log.err(TAG, "getSkuInfoByItemInfoVO err:", th);
        }
    }

    private Boolean BuyMallItem(String skuId) {
        if (skuInfo.isEmpty()) {
            getAllSkuInfo();
        }
        JSONObject sku = skuInfo.get(skuId);
        if (sku == null) {
            Log.record("小鸡乐园🎐找不到要兑奖的权益！");
            return false;
        }
        try {
            String skuName = sku.getString("skuName");
            JSONArray itemStatusList = sku.getJSONArray("itemStatusList");
            for (int i = 0; i < itemStatusList.length(); i++) {
                String itemStatus = itemStatusList.getString(i);
                if (ItemStatus.REACH_LIMIT.name().equals(itemStatus) || ItemStatus.REACH_USER_HOLD_LIMIT.name().equals(itemStatus) || ItemStatus.NO_ENOUGH_POINT.name().equals(itemStatus)) {
                    Log.record("乐币兑奖🎐[" + skuName + "]停止:" + AntFarm.ItemStatus.valueOf(itemStatus).nickName());
                    if (AntFarm.ItemStatus.REACH_LIMIT.name().equals(itemStatus)) {
                        Status.flagToday("farm::buyLimit::" + skuId);
                    }
                    return false;
                }
            }
            String spuId = sku.getString("spuId");
            if (BuyMallItem(spuId, skuId, skuName)) {
                return true;
            }
            getSkuInfoBySpuId(spuId);
        } catch (Throwable th) {
            Log.err(TAG, "BuyMallItem err:", th);
        }
        return false;
    }

    public static Boolean BuyMallItem(String spuId, String skuId, String skuName) {
        try {
            if (BuyMallItem(spuId, skuId)) {
                Status.gameCenterBuyMallItemToday(skuId);
                int buyedCount = Status.getGameCenterBuyMallItemCountToday(skuId);
                Log.farm("乐币兑奖🎐[" + skuName + "]#第" + buyedCount + "次");
                return true;
            } else {
                // 失败不累加当日次数：该计数用于控制当日兑换额度，成功才算一次，
                // 否则一次可重试的失败会吃掉额度导致当天不再重试（与 AntForestV2.exchangeBenefit 保持一致）
                return false;
            }
        } catch (Throwable th) {
            Log.err(TAG, "BuyMallItem err:", th);
        }
        return false;
    }

    private static Boolean BuyMallItem(String spuId, String skuId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.buyMallItem(spuId, skuId));
            if (jo.has("errorMessage")) {
                String errorMessage = jo.optString("errorMessage");
                //如果出错今天停止兑换
                if (errorMessage.equals("系统繁忙，请稍后再试。")) {
                    Status.flagToday("farm::buyLimit::" + skuId);
                }
            }
            return MessageUtil.checkResultCode(TAG, jo);
        } catch (Throwable th) {
            Log.err(TAG, "BuyMallItem err:", th);
        }
        return false;
    }

    // 抽抽乐任务统计：跨场景累加，整个抽抽乐流程跑完只打一行
    // （原先在 doFarmDrawTask 里打，日场/IP 场各一条，一轮下来同一条统计重复出现）
    private int drawStatScenes;
    private int drawStatTotal;
    private int drawStatTodo;
    private int drawStatDone;
    private int drawStatSkipped;
    private int drawStatBlack;

    private void drawMachineGroups() {
        drawStatScenes = 0;
        drawStatTotal = 0;
        drawStatTodo = 0;
        drawStatDone = 0;
        drawStatSkipped = 0;
        drawStatBlack = 0;
        try {
            drawMachineGroupsInner();
        } finally {
            // 抽抽乐统计不再单独打：模块收尾统一由 ModelTask 打一条汇总（"庄园✅本轮完成"），
            // 这张统计表与之信息重叠；需要细节时可查配置页任务列表或在 doFarmDrawTask 临时留痕
            if (drawStatTodo > 0 || (drawStatSkipped - drawStatBlack) > 0) {
                Log.markUnresolved();
            }
        }
    }

    private void drawMachineGroupsInner() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryLoveCabin(UserIdMap.getCurrentUid()));
            if (MessageUtil.checkMemo(TAG, jo)) {
                //乐园限定活动（庄园 → 乐园限定活动，乐园币任务）：先做任务再抽奖
                queryOptionalPlay();

                drawMachine("ANTFARM_DAILY_DRAW_TASK", "dailyDrawMachine", "ipDrawMachine");

                JSONObject queryDrawMachineActivityjo = new JSONObject(AntFarmRpcCall.queryDrawMachineActivity("ipDrawMachine", "dailyDrawMachine"));
                if (MessageUtil.checkMemo(TAG, queryDrawMachineActivityjo)) {
                    if (!queryDrawMachineActivityjo.has("otherDrawMachineActivityIds")) {
                        return;
                    }
                    if (queryDrawMachineActivityjo.getJSONArray("otherDrawMachineActivityIds").length() > 0) {
                        drawMachine("ANTFARM_IP_DRAW_TASK", "ipDrawMachine", "dailyDrawMachine");
                        //自动抽奖
                        if (IPexchangeBenefit.getValue()) {
                            try {
                                jo = new JSONObject(AntFarmRpcCall.queryDrawMachineActivity("dailyDrawMachine", "ipDrawMachine"));
                                if (!MessageUtil.checkResultCode(TAG, jo)) {
                                    return;
                                }
                                JSONObject activity = jo.optJSONObject("drawMachineActivity");
                                if (activity == null) {
                                    return;
                                }
                                String activityId = activity.optString("activityId");
                                if (!activityId.isEmpty()) {
                                    //IPexchangeBenefit选择某种类型商品兑换
                                    //返回false表示有兑换的或碎片不足，返回true表示全部兑换完毕
                                    if (IPexchangeBenefit(activityId, "DRESS")) {
                                        if (IPexchangeBenefit(activityId, "REISSUE_CARD")) {
                                            if (IPexchangeBenefit(activityId, "DELICIOUS_FOOD")) {
                                                IPexchangeBenefit(activityId, "ANTFARM_IP_DRAW_MALL");
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                Log.err(TAG, "drawMachine err:", t);
                            }

                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryLoveCabin err:");
            Log.printStackTrace(t);
        }
    }

    private void drawMachine(String taskSceneCode, String scene, String otherScenes) {
        doFarmDrawTask(taskSceneCode);
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryDrawMachineActivity(otherScenes, scene));
            int drawTimes = jo.optInt("drawTimes", 0);
            for (int i = 0; i < drawTimes; i++) {
                if (!drawMachine(scene)) {
                    return;
                }
                TimeUtil.sleep(5000);
            }
        } catch (Throwable t) {
            Log.err(TAG, "drawMachine err:", t);
        }
    }

    private void doFarmDrawTask(String taskSceneCode) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.listFarmDrawTask(taskSceneCode));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONArray farmTaskList = jo.getJSONArray("farmTaskList");
            int total = farmTaskList.length();
            int todo = 0, todoDone = 0, todoSkipped = 0;
            int blackSkipped = 0;
            for (int i = 0; i < farmTaskList.length(); i++) {
                jo = farmTaskList.getJSONObject(i);
                String taskStatus = jo.getString("taskStatus");
                String title = jo.getString("title");
                String taskId = jo.optString("taskId");
                if (TaskStatus.RECEIVED.name().equals(taskStatus)) {
                    continue;
                }
                if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                    String awardType = jo.optString("awardType");
                    receiveFarmDrawTaskAward(taskId, title, awardType, taskSceneCode);
                    continue;
                }
                //黑名单任务跳过静默：条目由用户在配置页维护，内容恒定，不打日志
                if (AntFarmDrawMachineTaskList.getValue().contains(title)) {
                    todoSkipped++;
                    blackSkipped++;
                    continue;
                }

                if (TaskStatus.TODO.name().equals(taskStatus)) {
                    todo++;
                    int rightsTimesLimit = jo.optInt("rightsTimesLimit");
                    int rightsTimes = jo.optInt("rightsTimes");
                    int remain = rightsTimesLimit - rightsTimes;
                    // 本日已做成：不再重复上报（省一轮全量重做）。但**多次任务**还有剩余次数时不能跳过，
                    // 否则当天只做成第一次就卡住、永远推不满。
                    if (remain <= 0 && TaskAlternative.verifiedToday(taskId)) {
                        continue;
                    }
                    boolean matched = false;

                    if (taskId.contains("EXCHANGE") || taskId.contains("FKDWChuodong") || taskId.contains("GYG2") || taskId.equals("jiatingdongrirongrongwu")) {
                        boolean allSent = remain > 0;
                        for (int j = 0; j < remain; j++) {
                            JSONObject jodoFarmTask = new JSONObject(AntFarmRpcCall.doFarmTask(jo.optString("bizKey"), taskSceneCode));
                            //检查并标记黑名单任务
                            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntFarmDrawMachineTaskList", title, jodoFarmTask);
                            if (!MessageUtil.checkSuccess(TAG, jodoFarmTask)) {
                                allSent = false;
                            }
                        }
                        TimeUtil.sleep(1000);
                        if (allSent) {
                            TaskAlternative.markVerifiedToday(taskId);
                        }
                        matched = true;
                    }
                    // 兜底：其余任务（商业化/玩游戏/开宝箱/浏览等）统一走完成接口。
                    // 不再按 title/desc 关键字分派：服务端文案一改就会整类任务不执行（列表拿到了却没有任何动作），
                    // 这里改为全部尝试，确实做不了的交给自动拉黑机制剔除
                    if (!matched) {
                        String bizKey = jo.optString("bizKey");
                        // 交易/履约类：finishTask 与 doFarmTask 都会被服务端判风险，直接不申报并交自动黑名单
                        // （本路径不经 TaskAttemptPolicy.handle，拉黑只能在这里做）
                        if (TaskAlternative.isTransactionTask(bizKey)) {
                            // 底线：交易/支付类一次即永久拉黑，绝不伪造
                            MessageUtil.MarkTaskBlackListPermanent("AntFarm", "AntFarmDrawMachineTaskList",
                                    "庄园装扮抽抽乐任务", title);
                            todoSkipped++;
                            Log.farm("抽抽乐⏭️交易/履约类[" + title + "]#不申报，已永久拉黑");
                            continue;
                        }
                        // 服务端偶发返回 limit==times 的 TODO 任务，此时仍尝试一次，避免有任务却整轮不执行
                        int tryTimes = remain > 0 ? remain : 1;
                        String via = null;
                        for (int j = 0; j < tryTimes; j++) {
                            JSONObject jofinishTask = new JSONObject(AntFarmRpcCall.finishTask(taskId, taskSceneCode));
                            if (MessageUtil.checkSuccess(TAG, jofinishTask)) {
                                via = "finishTask";
                                continue;
                            }
                            // 游戏类任务的 taskType 服务端拒绝走 finishTask（400000040 不支持rpc调用），
                            // 再用任务的 bizKey 走一次 doFarmTask，两条路都失败才判定该任务做不了
                            JSONObject jodoFarmTask = bizKey.isEmpty()
                                    ? null
                                    : new JSONObject(AntFarmRpcCall.doFarmTask(bizKey, taskSceneCode));
                            if (jodoFarmTask != null && MessageUtil.checkSuccess(TAG, jodoFarmTask)) {
                                via = "doFarmTask";
                                continue;
                            }
                            //检查并标记黑名单任务
                            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntFarmDrawMachineTaskList", title, jofinishTask);
                        }
                        TimeUtil.sleep(2000);
                        if (via != null) {
                            todoDone++;
                            // 做成即打当日标记，后续轮次不再重复上报
                            TaskAlternative.markVerifiedToday(taskId);
                            Log.farm("抽抽乐🧾完成[" + title + "]");
                        } else {
                            todoSkipped++;
                            Log.farm("抽抽乐⚠️未完成[" + title + "]，需在支付宝内手动完成");
                        }
                    } else {
                        todoDone++;
                    }
                    TimeUtil.sleep(1000);
                }
                TimeUtil.sleep(2000);
                String awardType = jo.optString("awardType");
                receiveFarmDrawTaskAward(taskId, title, awardType, taskSceneCode);
            }

            // 统计累加到 drawStat*，由 drawMachineGroups 在抽抽乐全部场景跑完后统一打一行
            drawStatBlack += blackSkipped;
            drawStatScenes++;
            drawStatTotal += total;
            drawStatTodo += todo;
            drawStatDone += todoDone;
            drawStatSkipped += todoSkipped;
        } catch (Throwable t) {
            Log.err(TAG, "doFarmDrawActivityTimeTask err:", t);
        }
    }

    private void receiveFarmDrawTaskAward(String taskId, String title, String awardType, String taskSceneCode) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.receiveFarmDrawTimesTaskAward(taskId, awardType, taskSceneCode));
            if (MessageUtil.checkMemo(TAG, jo)) {
                Log.farm("装扮抽奖🎖️领取[" + title + "]奖励");
            }
        } catch (Throwable t) {
            Log.err(TAG, "receiveFarmDrawTimesTaskAward err:", t);
        }
    }

    private Boolean drawMachine(String scene) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.drawMachine(scene));
            if (MessageUtil.checkMemo(TAG, jo)) {
                if (!jo.has("title")) {
                    jo = jo.optJSONObject("drawMachinePrize");
                }
                String title = jo.optString("title");
                Log.farm("装扮抽奖🎁抽中[" + title + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "drawMachine err:", t);
        }
        return false;
    }

    //返回false表示有兑换的或碎片不足，返回true表示全部兑换完毕
    public boolean IPexchangeBenefit(String activityId, String labelType) {
        try {
            String response = AntFarmRpcCall.getItemList(activityId, 10, 0);
            JSONObject respJson = new JSONObject(response);

            if (respJson.optBoolean("success", false) || "100000000".equals(respJson.optString("code"))) {
                int totalCent = 0;
                JSONObject mallAccount = respJson.optJSONObject("mallAccountInfoVO");
                if (mallAccount != null) {
                    JSONObject holdingCount = mallAccount.optJSONObject("holdingCount");
                    if (holdingCount != null) {
                        totalCent = holdingCount.optInt("cent", 0);
                    }
                }
                JSONArray itemVOList = respJson.optJSONArray("itemInfoVOList");
                if (itemVOList == null) {
                    return true;
                }

                List<JSONObject> allSkus = new ArrayList<>();
                for (int i = 0; i < itemVOList.length(); i++) {
                    JSONObject item = itemVOList.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    JSONArray labelTypeList = item.optJSONArray("labelTypeList");
                    if (labelTypeList == null) {
                        continue;
                    }
                    boolean isRightItem = false;
                    for (int j = 0; j < labelTypeList.length(); j++) {
                        String itemLabelType = labelTypeList.optString(j);
                        if (itemLabelType.contains(labelType)) {
                            isRightItem = true;
                        }
                    }
                    if (!isRightItem) {
                        continue;
                    }
                    boolean itemReachedLimit = isReachedLimit(item);
                    JSONObject minPriceObj = item.optJSONObject("minPrice");
                    int cent = minPriceObj != null ? minPriceObj.optInt("cent", 0) : 0;

                    JSONArray skuList = item.optJSONArray("skuModelList");
                    if (skuList == null) {
                        continue;
                    }
                    for (int j = 0; j < skuList.length(); j++) {
                        JSONObject sku = skuList.optJSONObject(j);
                        if (sku == null) {
                            continue;
                        }
                        sku.put("_spuId", item.optString("spuId"));
                        sku.put("_spuName", item.optString("spuName"));
                        sku.put("_isReachLimit", itemReachedLimit || isReachedLimit(sku));
                        sku.put("_cent", cent);
                        allSkus.add(sku);
                    }
                }
                // 按价格从高到低排序
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    allSkus.sort((JSONObject a, JSONObject b) -> Integer.compare(b.optInt("_cent", 0), a.optInt("_cent", 0)));
                } else {
                    // 低版本用 Collections.sort 兼容
                    Collections.sort(allSkus, new Comparator<JSONObject>() {
                        @Override
                        public int compare(JSONObject a, JSONObject b) {
                            return Integer.compare(b.optInt("_cent", 0), a.optInt("_cent", 0));
                        }
                    });
                }

                for (JSONObject sku : allSkus) {
                    if (sku.optBoolean("_isReachLimit")) {
                        continue;
                    }
                    int cent = sku.optInt("_cent", 0);
                    String skuName = sku.optString("skuName");

                    if (isNoEnoughPoint(sku) || (cent > 0 && totalCent < cent)) {
                        Log.farm("兑换[" + labelType + "]类最高价值[" + skuName + "]碎片不足(持有" + (totalCent / 100) + "需" + (cent / 100) + ")");
                        return false;
                    }
                    break;
                }

                // 执行顺序兑换，按价格从高到低
                for (JSONObject sku : allSkus) {
                    if (sku.optBoolean("_isReachLimit")) {
                        continue;
                    }

                    String skuName = sku.optString("skuName");
                    int cent = sku.optInt("_cent", 0);
                    String extendInfo = sku.optString("skuExtendInfo");
                    int limitCount = extendInfo.contains("20次") ? 20 : (extendInfo.contains("5次") ? 5 : 1);

                    // 【核心逻辑】：如果当前项买不起，直接 return 停止，不再尝试后续更便宜的项目
                    if (isNoEnoughPoint(sku) || (cent > 0 && totalCent < cent)) {
                        Log.farm("剩余碎片不足以兑换[" + labelType + "]类优先级项 [" + skuName + "] (需 " + (cent / 100) + ")，停止后续兑换任务");
                        return false;
                    }

                    int sessionExchangedCount = 0;
                    while (sessionExchangedCount < limitCount) {
                        // 预检查当前余额
                        if (cent > 0 && totalCent < cent) {
                            break;
                        }

                        String result = AntFarmRpcCall.exchangeBenefit(sku.optString("_spuId"), sku.optString("skuId"), activityId, "ANTFARM_IP_DRAW_MALL", "antfarm_villa");

                        JSONObject resObj = new JSONObject(result);
                        String resultCode = resObj.optString("resultCode");

                        if ("SUCCESS".equals(resultCode)) {
                            sessionExchangedCount++;
                            totalCent -= cent; // 减去花费
                            Log.farm("兑换装扮👔[" + labelType + "]类[" + skuName + "]#剩余碎片" + (totalCent / 100));
                            TimeUtil.sleep(800);
                        } else if ("NO_ENOUGH_POINT".equals(resultCode)) {
                            return false;
                        } else if (resultCode.contains("LIMIT") || resultCode.contains("MAX")) {
                            break;
                        } else {
                            break;
                        }
                    }
                }
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            Log.printStackTrace("自动兑换异常", e);
        }
        return false;
    }

    private boolean isReachedLimit(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        if ("REACH_LIMIT".equals(jo.optString("itemStatus"))) {
            return true;
        }
        JSONArray list = jo.optJSONArray("itemStatusList");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                String status = list.optString(i);
                if ("REACH_LIMIT".equals(status) || status.contains("LIMIT")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isNoEnoughPoint(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        if ("NO_ENOUGH_POINT".equals(jo.optString("itemStatus"))) {
            return true;
        }
        JSONArray list = jo.optJSONArray("itemStatusList");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                if ("NO_ENOUGH_POINT".equals(list.optString(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 雇佣自家农场的 NPC 并做它的任务：勾选里有当前 NPC 就只做任务，不在才遣返换雇；每只每天只处理一次。
     * <p>失败只记一条日志、不做当天封禁：批次失效/冷却/数量超限都可能在后续轮次自行恢复。
     */
    private void hireNpc() {
        try {
            Set<String> selected = hireNpcList.getValue();
            if (selected == null || selected.isEmpty()) {
                return;
            }
            HireNpc target = null;
            for (HireNpc npc : HireNpc.values()) {
                if (selected.contains(npc.name()) && !Status.hasFlagToday(FLAG_HIRE_NPC_DONE + npc.name())) {
                    target = npc;
                    break;
                }
            }
            if (target == null) {
                // 勾选的 NPC 今天都处理过：保持现状，不再换雇，免得每轮重复遣返与雇佣
                return;
            }

            syncAnimalStatus(ownerFarmId);
            Animal current = findNpcAnimal();
            if (current == null && hiredAnyNpcToday()) {
                // 雇过却认不出来时打印真实字段：认不出会反复雇佣并报数量超限
                if (animals != null) {
                    for (Animal a : animals) {
                        Log.i(TAG, "未识别到NPC：animalId=" + a.animalId + " subAnimalType=" + a.subAnimalType
                                + " masterFarmId=" + a.masterFarmId + " interact=" + a.animalInteractStatus);
                    }
                }
            }
            if (current != null && !target.animalId.equals(current.animalId)) {
                if (!sendBackNpc(current)) {
                    return;
                }
                syncAnimalStatus(ownerFarmId);
                current = null;
            }
            if (current == null) {
                if (!hireNpcAnimal(target)) {
                    return;
                }
                TimeUtil.sleep(1000);
                syncAnimalStatus(ownerFarmId);
            }

            // 任务没拉成功就不打当日标记，下一轮继续补做（此时当前 NPC 已是对的，不会再换雇）
            if (doNpcTask(target)) {
                Status.flagToday(FLAG_HIRE_NPC_DONE + target.name());
            }
        } catch (Throwable t) {
            Log.err(TAG, "hireNpc err:", t);
        }
    }

    private Animal findNpcAnimal() {
        if (animals == null) {
            return null;
        }
        for (Animal animal : animals) {
            if (isNpcAnimal(animal)) {
                return animal;
            }
        }
        return null;
    }

    /** 服务端子类型或登记的活动批次 ID 任一命中即为 NPC */
    private boolean isNpcAnimal(Animal animal) {
        return SubAnimalType.NPC.name().equals(animal.subAnimalType) || HireNpc.of(animal.animalId) != null;
    }

    /** 今天是否已经处理过任意一只 NPC（用来区分「本来就没有 NPC」和「雇了但认不出来」）。 */
    private boolean hiredAnyNpcToday() {
        for (HireNpc npc : HireNpc.values()) {
            if (Status.hasFlagToday(FLAG_HIRE_NPC_DONE + npc.name())) {
                return true;
            }
        }
        return false;
    }

    private boolean sendBackNpc(Animal npc) {
        HireNpc leaving = HireNpc.of(npc.animalId);
        String name = leaving == null ? "当前NPC" : leaving.nickName;
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.sendBackNpcAnimal(npc.animalId, npc.currentFarmId, npc.masterFarmId, true));
            String memo = jo.optString("memo");
            if ("SUCCESS".equals(memo)) {
                Log.farm("雇佣NPC🐤遣返[" + name + "]");
                return true;
            }
            // 领奖失败但动物其实已离开：按实际状态确认，否则会永远卡在换雇这一步
            if (jo.toString().contains("NPC_ANIMAL03")) {
                syncAnimalStatus(ownerFarmId);
                if (findNpcAnimal() == null) {
                    Log.farm("雇佣NPC🐤遣返[" + name + "]");
                    return true;
                }
            }
            Log.record("雇佣NPC🐤遣返[" + name + "]失败#" + memo);
        } catch (Throwable t) {
            Log.err(TAG, "sendBackNpc err:", t);
        }
        return false;
    }

    private boolean hireNpcAnimal(HireNpc npc) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.hireNpcAnimal(npc.animalId, npc.source));
            String memo = jo.optString("memo");
            if ("SUCCESS".equals(memo)) {
                Log.farm("雇佣NPC🐤[" + npc.nickName + "]");
                return true;
            }
            Log.record("雇佣NPC🐤[" + npc.nickName + "]雇佣失败#" + memo);
        } catch (Throwable t) {
            Log.err(TAG, "hireNpcAnimal err:", t);
        }
        return false;
    }

    /**
     * 完成 NPC 任务并领奖：只申报 {@code taskMode=VIEW} 的任务，申报后重拉列表，最多两趟。
     *
     * @return 任务列表是否成功拿到（拿不到时不打当日标记，下一轮重试）
     */
    private boolean doNpcTask(HireNpc npc) {
        boolean listed = false;
        for (int round = 0; round < 2; round++) {
            JSONObject jo;
            try {
                jo = new JSONObject(AntFarmRpcCall.listNpcFarmTask(npc.taskSceneCode));
            } catch (Throwable t) {
                Log.err(TAG, "listNpcFarmTask err:", t);
                return listed;
            }
            String memo = jo.optString("memo");
            if (!"SUCCESS".equals(memo)) {
                Log.record("雇佣NPC🐤[" + npc.nickName + "]任务列表失败#" + memo);
                return listed;
            }
            listed = true;
            JSONArray ja = jo.optJSONArray("farmTaskList");
            if (ja == null || ja.length() == 0) {
                return true;
            }
            boolean acted = false;
            for (int i = 0; i < ja.length(); i++) {
                JSONObject task = ja.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String status = task.optString("taskStatus");
                String taskId = task.optString("taskId");
                String bizKey = task.optString("bizKey");
                if ("TODO".equals(status) && "VIEW".equals(task.optString("taskMode")) && !bizKey.isEmpty()) {
                    JSONObject r;
                    try {
                        r = new JSONObject(AntFarmRpcCall.doFarmTask(bizKey, npc.taskSceneCode));
                    } catch (Throwable t) {
                        Log.err(TAG, "doNpcTask err:", t);
                        continue;
                    }
                    if ("SUCCESS".equals(r.optString("memo"))) {
                        acted = true;
                    } else {
                        Log.record("雇佣NPC🐤[" + npc.nickName + "]任务申报失败#" + r.optString("memo"));
                    }
                } else if ("FINISHED".equals(status) && !taskId.isEmpty()) {
                    String awardType = task.optString("awardType", "NPC_ANIMAL_FOOD");
                    int awardCount = task.optInt("awardCount");
                    JSONObject r;
                    try {
                        r = new JSONObject(AntFarmRpcCall.receiveNpcTaskAward(taskId, npc.taskSceneCode, awardType));
                    } catch (Throwable t) {
                        Log.err(TAG, "receiveNpcTaskAward err:", t);
                        continue;
                    }
                    if ("SUCCESS".equals(r.optString("memo"))) {
                        acted = true;
                        Log.farm("雇佣NPC🐤[" + npc.nickName + "]任务奖励[" + awardCount + npc.awardUnitOf(awardType) + "]");
                    } else {
                        Log.record("雇佣NPC🐤[" + npc.nickName + "]领奖失败#" + r.optString("memo"));
                    }
                }
            }
            if (!acted) {
                return true;
            }
        }
        return true;
    }

    /* 雇佣好友小鸡 */
    private void hireAnimal() {
        try {
            syncAnimalStatus(ownerFarmId);
            if (!AnimalFeedStatus.EATING.name().equals(ownerAnimal.animalFeedStatus)) {
                return;
            }
            int count = 3 - animals.length;
            if (count <= 0) {
                return;
            }
            Log.farm("雇佣小鸡👷[当前可雇佣小鸡数量:" + count + "只]");
            if (foodStock < 50) {
                Log.record("饲料不足，暂不雇佣");
                return;
            }

            boolean hasNext;
            int pageStartSum = 0;
            Set<String> hireAnimalSet = hireAnimalList.getValue();
            do {
                JSONObject jo = new JSONObject(AntFarmRpcCall.rankingList(pageStartSum));
                if (!MessageUtil.checkMemo(TAG, jo)) {
                    return;
                }
                JSONArray rankingList = jo.getJSONArray("rankingList");
                hasNext = jo.getBoolean("hasNext");
                pageStartSum += rankingList.length();
                for (int i = 0; i < rankingList.length() && count > 0; i++) {
                    jo = rankingList.getJSONObject(i);
                    String userId = jo.getString("userId");
                    boolean isHireAnimal = hireAnimalSet.contains(userId);
                    if (hireAnimalType.getValue() != HireAnimalType.HIRE) {
                        isHireAnimal = !isHireAnimal;
                    }
                    if (!isHireAnimal || userId.equals(UserIdMap.getCurrentUid())) {
                        continue;
                    }
                    String actionTypeListStr = jo.getJSONArray("actionTypeList").toString();
                    if (actionTypeListStr.contains("can_hire_action")) {
                        if (hireAnimalAction(userId)) {
                            count--;
                            autoFeedAnimal();
                        }
                    }
                }
            } while (hasNext && count > 0);

            if (count > 0) {
                Log.farm("没有足够的小鸡可以雇佣");
            }
        } catch (Throwable t) {
            Log.err(TAG, "hireAnimal err:", t);
        } finally {
            long updateTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
            String taskId = "UPDATE|HIRE|" + ownerFarmId;
            addChildTask(new ChildModelTask(taskId, "UPDATE", this::autoHireAnimal, updateTime));
        }
    }

    private void autoHireAnimal() {
        try {
            syncAnimalStatus(ownerFarmId);
            for (Animal animal : animals) {
                if (!SubAnimalType.WORK.name().equals(animal.subAnimalType) || isNpcAnimal(animal)) {
                    continue;
                }
                String taskId = "HIRE|" + animal.animalId;
                if (!hasChildTask(taskId)) {
                    long beHiredEndTime = animal.beHiredEndTime;
                    addChildTask(new ChildModelTask(taskId, "HIRE", this::hireAnimal, beHiredEndTime));
                    Log.record("添加蹲点雇佣👷在[" + TimeUtil.getCommonDate(beHiredEndTime) + "]执行");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "autoHireAnimal err:", t);
        }
    }

    private Boolean hireAnimalAction(String userId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterFarm("", userId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return false;
            }
            jo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO");
            String farmId = jo.getString("farmId");
            JSONArray animals = jo.getJSONArray("animals");
            for (int i = 0, len = animals.length(); i < len; i++) {
                JSONObject animal = animals.getJSONObject(i);
                if (Objects.equals(animal.getJSONObject("masterUserInfoVO").getString("userId"), userId)) {
                    String animalId = animal.getString("animalId");
                    jo = new JSONObject(AntFarmRpcCall.hireAnimal(farmId, animalId));
                    if (MessageUtil.checkMemo(TAG, jo)) {
                        foodStock = jo.getInt("foodStock");
                        int reduceFoodNum = jo.getInt("reduceFoodNum");
                        Log.farm("雇佣小鸡👷雇佣[" + UserIdMap.getMaskName(userId) + "]#消耗[" + reduceFoodNum + "g饲料]");
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "hireAnimalAction err:", t);
        }
        return false;
    }

    private void drawGameCenterAward() {
        try {
            // 同轮内连做：上报新拿到的额度当场开掉，不必等下一轮任务运行
            for (int round = 0; round < MAX_GAME_DRAW_ROUND; round++) {
                JSONObject jo = new JSONObject(AntFarmRpcCall.queryGameList());
                if (!jo.optBoolean("success")) {
                    return;
                }
                // 2. 获取宝箱领取权限数据
                JSONObject drawRights = jo.optJSONObject("gameCenterDrawRights");
                if (drawRights == null) {
                    return;
                }
                // 3. 处理当前可开启的宝箱
                int quotaCanUse = drawRights.optInt("quotaCanUse"); // 当前可开宝箱数
                boolean openFailed = false;
                if (quotaCanUse > 0) {
                    Log.record("当前有 " + quotaCanUse + " 个宝箱待开启...");

                    while (quotaCanUse > 0) {
                        // 调用开启宝箱接口
                        String drawResStr = AntFarmRpcCall.drawGameCenterAward(1);
                        JSONObject drawRes = new JSONObject(drawResStr);

                        if (drawRes.optBoolean("success")) {
                            // 更新剩余可开启次数
                            JSONObject nextRights = drawRes.optJSONObject("gameCenterDrawRights");
                            quotaCanUse = (nextRights != null) ? nextRights.optInt("quotaCanUse") : (quotaCanUse - 1);

                            // 解析奖励列表并拼接日志
                            JSONArray awardList = drawRes.optJSONArray("gameCenterDrawAwardList");
                            List<String> awardStrings = new ArrayList<>();
                            if (awardList != null) {
                                for (int i = 0; i < awardList.length(); i++) {
                                    JSONObject item = awardList.getJSONObject(i);
                                    String awardName = item.optString("awardName");
                                    int awardCount = item.optInt("awardCount");
                                    awardStrings.add(awardName + "*" + awardCount);
                                }
                            }
                            String awardLog = String.join(",", awardStrings);
                            Log.farm("小鸡乐园🎁开宝箱得[" + awardLog + "]");
                            TimeUtil.sleep(3000);
                        } else {
                            Log.farm("小鸡乐园开启宝箱失败: " + drawRes.optString("desc"));
                            openFailed = true;
                            break; // 开启失败则退出循环
                        }
                    }
                }
                if (openFailed) {
                    return;
                }

                // 4. 处理剩余任务（判断是否需要刷任务）
                int limit = drawRights.optInt("quotaLimit"); // 每日上限
                int used = drawRights.optInt("usedQuota");   // 今日已开数量
                int remainToTask = limit - used;
                if (remainToTask <= 0) {
                    Log.record("今日 " + limit + " 个金蛋任务已全部满额");
                    return;
                }
                if (quotaCanUse > 0) {
                    // 开箱中途退出（次数没归零）：服务端可能已入账新额度，重新读一遍再决定
                    continue;
                }
                if (Status.getIntFlagToday(FLAG_GAME_DRAW_TASK_TRY) >= MAX_GAME_DRAW_TASK_TRY) {
                    return;
                }
                // 本地 quotaCanUse/used 已被本次开箱流程改过，不能当基线，先回读一次真实值
                JSONObject beforeJo = new JSONObject(AntFarmRpcCall.queryGameList());
                JSONObject beforeRights = beforeJo.optJSONObject("gameCenterDrawRights");
                int beforeUsed = beforeRights != null ? beforeRights.optInt("usedQuota", used) : used;
                int beforeQuota = beforeRights != null ? beforeRights.optInt("quotaCanUse", quotaCanUse) : quotaCanUse;
                // report 是异步线程，上报完立刻回读会读到旧状态，这里用同步版
                int successes = GameTask.Farm_ddply.reportSync("庄园", remainToTask);
                if (successes <= 0) {
                    return;
                }
                // 支付宝侧入账仍是异步的：等一会儿再回读，否则会把已入账的读成旧值
                TimeUtil.sleep(3000);
                JSONObject afterJo = new JSONObject(AntFarmRpcCall.queryGameList());
                JSONObject afterRights = afterJo.optJSONObject("gameCenterDrawRights");
                int afterQuota = afterRights != null ? afterRights.optInt("quotaCanUse", beforeQuota) : beforeQuota;
                int afterUsed = afterRights != null ? afterRights.optInt("usedQuota", beforeUsed) : beforeUsed;
                if (afterQuota <= beforeQuota && afterUsed <= beforeUsed) {
                    Status.setIntFlagToday(FLAG_GAME_DRAW_TASK_TRY,
                            Status.getIntFlagToday(FLAG_GAME_DRAW_TASK_TRY) + 1);
                    Log.record("小鸡乐园🎁刷任务未推进#稍后重试[第"
                            + Status.getIntFlagToday(FLAG_GAME_DRAW_TASK_TRY) + "/"
                            + MAX_GAME_DRAW_TASK_TRY + "次]");
                    return;
                }
                // 有推进就重置计数，回到循环开头把新额度开掉
                Status.setIntFlagToday(FLAG_GAME_DRAW_TASK_TRY, 0);
                Log.record("小鸡乐园🎁刷任务生效#可用次数[" + beforeQuota + "→" + afterQuota + "]");
            }

            /*
                JSONObject gameDrawAwardActivity = jo.getJSONObject("gameDrawAwardActivity");
                int canUseTimes = gameDrawAwardActivity.getInt("canUseTimes");
                while (canUseTimes > 0) {
                    try {
                        jo = new JSONObject(AntFarmRpcCall.drawGameCenterAward());
                        if (jo.optBoolean("success")) {
                            canUseTimes = jo.getInt("drawRightsTimes");
                            JSONArray gameCenterDrawAwardList = jo.optJSONArray("gameCenterDrawAwardList");
                            if (gameCenterDrawAwardList != null) {
                                ArrayList<String> awards = new ArrayList<String>();
                                for (int i = 0; i < gameCenterDrawAwardList.length(); i++) {
                                    JSONObject gameCenterDrawAward = gameCenterDrawAwardList.optJSONObject(i);
                                    if (gameCenterDrawAward == null) {
                                        continue;
                                    }
                                    int awardCount = gameCenterDrawAward.optInt("awardCount", 0);
                                    String awardName = gameCenterDrawAward.optString("awardName", "");
                                    if (!awardName.isEmpty()) {
                                        awards.add(awardName + "*" + awardCount);
                                    }
                                }
                                Log.farm("小鸡乐园🎮开宝箱得[" + StringUtil.collectionJoinString(",", awards) + "]");
                            }
                        }
                        else {
                            Log.i(TAG, "drawGameCenterAward falsed result: " + jo.toString());
                        }
                    }
                    catch (Throwable t) {
                        Log.printStackTrace(TAG, t);
                    }
                    finally {
                        TimeUtil.sleep(3000);
                    }
                }*/
        } catch (Throwable t) {
            Log.err(TAG, "drawGameCenterAward err:", t);
        }
    }

    // 装扮焕新
    private void ornamentsDressUp() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.listOrnaments());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            List<JSONObject> list = new ArrayList<>();
            JSONArray achievementOrnaments = jo.getJSONArray("achievementOrnaments");
            long takeOffTime = System.currentTimeMillis();
            for (int i = 0; i < achievementOrnaments.length(); i++) {
                jo = achievementOrnaments.getJSONObject(i);
                if (!jo.optBoolean("acquired")) {
                    continue;
                }
                if (jo.has("takeOffTime")) {
                    takeOffTime = jo.getLong("takeOffTime");
                }
                String resourceKey = jo.getString("resourceKey");
                String name = jo.getString("name");
                if (ornamentsDressUpList.getValue().contains(resourceKey)) {
                    list.add(jo);
                }
                FarmOrnamentsIdMap.add(resourceKey, name);
            }
            FarmOrnamentsIdMap.save(UserIdMap.getCurrentUid());
            if (list.isEmpty() || takeOffTime + TimeUnit.DAYS.toMillis(ornamentsDressUpDays.getValue() - 15) > System.currentTimeMillis()) {
                return;
            }

            jo = list.get(RandomUtil.nextInt(0, list.size() - 1));
            if (saveOrnaments(jo)) {
                Log.farm("装扮焕新✨[" + jo.getString("name") + "]");
            }
        } catch (Throwable t) {
            Log.err(TAG, "ornamentsDressUp err:", t);
        }
    }

    private Boolean saveOrnaments(JSONObject ornaments) {
        try {
            String animalId = ownerAnimal.animalId;
            String farmId = ownerFarmId;
            String ornamentsSets = getOrnamentsSets(ornaments.getJSONArray("sets"));
            JSONObject jo = new JSONObject(AntFarmRpcCall.saveOrnaments(animalId, farmId, ornamentsSets));
            return MessageUtil.checkMemo(TAG, jo);
        } catch (Throwable t) {
            Log.err(TAG, "saveOrnaments err:", t);
        }
        return false;
    }

    private String getOrnamentsSets(JSONArray sets) {
        StringBuilder ornamentsSets = new StringBuilder();
        try {
            for (int i = 0; i < sets.length(); i++) {
                JSONObject set = sets.getJSONObject(i);
                if (i > 0) {
                    ornamentsSets.append(",");
                }
                ornamentsSets.append(set.getString("id"));
            }
        } catch (Throwable t) {
            Log.err(TAG, "getOrnamentsSets err:", t);
        }
        return ornamentsSets.toString();
    }

    // 一起拿小鸡饲料
  /*  private void letsGetChickenFeedTogether() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.letsGetChickenFeedTogether());
            if (jo.optBoolean("success")) {
                String bizTraceId = jo.getString("bizTraceId");
                JSONArray p2pCanInvitePersonDetailList = jo.getJSONArray("p2pCanInvitePersonDetailList");
                
                int canInviteCount = 0;
                int hasInvitedCount = 0;
                List<String> userIdList = new ArrayList<>(); // 保存 userId
                for (int i = 0; i < p2pCanInvitePersonDetailList.length(); i++) {
                    JSONObject personDetail = p2pCanInvitePersonDetailList.optJSONObject(i);
                    if (personDetail == null) {
                        continue;
                    }
                    String inviteStatus = personDetail.optString("inviteStatus", "");
                    String userId = personDetail.optString("userId", "");
                    if (userId.isEmpty()) {
                        continue;
                    }
                    
                    if (inviteStatus.equals("CAN_INVITE")) {
                        userIdList.add(userId);
                        canInviteCount++;
                    }
                    else if (inviteStatus.equals("HAS_INVITED")) {
                        hasInvitedCount++;
                    }
                }
                
                int invitedToday = hasInvitedCount;
                
                int remainingInvites = 5 - invitedToday;
                int invitesToSend = Math.min(canInviteCount, remainingInvites);
                
                if (invitesToSend == 0) {
                    return;
                }
                
                Set<String> getFeedSet = getFeedList.getValue();
                
                //if (getFeedType.getValue() == GetFeedType.GIVE) {
                    //for (String userId : userIdList) {
                        //if (invitesToSend <= 0) {
                            //                            Log.record("已达到最大邀请次数限制，停止发送邀请。");
                            //break;
                        //}
                        //if (getFeedSet.contains(userId)) {
                         //   jo = new JSONObject(AntFarmRpcCall.giftOfFeed(bizTraceId, userId));
                            //if (jo.optBoolean("success")) {
                                //Log.record("一起拿小鸡饲料🥡 [送饲料：" + UserIdMap.getMaskName(userId) + "]");
                                //invitesToSend--; // 每成功发送一次邀请，减少一次邀请次数
                            //}
                            //else {
                                //Log.farm("邀请失败：" + jo);
                                //break;
                            //}
                        //}
                        //else {
                            //                            Log.record("用户 " + UserIdMap.getMaskName(userId) + "
                            // 不在勾选的好友列表中，不发送邀请。");
                     //   }
                  //  }
             //   }
                else {
                    Random random = new Random();
                    for (int j = 0; j < invitesToSend; j++) {
                        int randomIndex = random.nextInt(userIdList.size());
                        String userId = userIdList.get(randomIndex);
                        
                        jo = new JSONObject(AntFarmRpcCall.giftOfFeed(bizTraceId, userId));
                        if (jo.optBoolean("success")) {
                            Log.record("一起拿小鸡饲料🥡 [送饲料：" + UserIdMap.getMaskName(userId) + "]");
                        }
                        else {
                            Log.farm("邀请失败：" + jo);
                            break;
                        }
                        userIdList.remove(randomIndex);
                    }
                }
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "letsGetChickenFeedTogether err:");
            Log.printStackTrace(t);
        }
    }  */

    private void family() {
        if (StringUtil.isEmpty(ownerGroupId)) {
            return;
        }
        // 检查 ExtensionsHandle 是否存在
        try {
            Class.forName("io.github.aw1y2z.sesame.model.extensions.ExtensionsHandle");
            ExtensionsHandle.handleAlphaRequest("antFarm", "doFamilyTask", null);
        } catch (ClassNotFoundException e) {
            Log.record("ExtensionsHandle 类未找到，跳过扩展处理");
        }
        try {
            JSONObject joenterFamily = enterFamily();
            JSONObject jo;
            if (joenterFamily == null) {
                return;
            }
            ownerGroupId = joenterFamily.getString("groupId");
            int familyAwardNum = joenterFamily.getInt("familyAwardNum");
            boolean familySignTips = joenterFamily.getBoolean("familySignTips");
            JSONObject assignFamilyMemberInfo = joenterFamily.optJSONObject("assignFamilyMemberInfo");
            boolean feedFriendLimit = joenterFamily.optBoolean("feedFriendLimit", false);
            JSONArray familyAnimals = joenterFamily.getJSONArray("animals");
            JSONArray EatTogetherUserIds = new JSONArray();
            // 修复：创建新的 JSONArray 副本，避免修改原始 familyAnimals
            JSONArray familyAnimalsExceptUser = new JSONArray();
            for (int i = 0; i < familyAnimals.length(); i++) {
                jo = familyAnimals.getJSONObject(i);
                String userId = jo.getString("userId");
                EatTogetherUserIds.put(userId);
                if (!userId.equals(UserIdMap.getCurrentUid())) {
                    familyAnimalsExceptUser.put(jo);
                }
            }
            // 获取家庭成员ID列表
            List<String> familyUserIds = new ArrayList<>();
            for (int i = 0; i < familyAnimals.length(); i++) {
                jo = familyAnimals.getJSONObject(i);
                String animalId = jo.getString("animalId");
                String userId = jo.getString("userId");
                familyUserIds.add(userId);
                if (animalId.equals(ownerAnimal.animalId)) {
                    continue;
                }
                String farmId = jo.getString("farmId");
                JSONObject animalStatusVO = jo.getJSONObject("animalStatusVO");
                String animalFeedStatus = animalStatusVO.getString("animalFeedStatus");
                String animalInteractStatus = animalStatusVO.getString("animalInteractStatus");
                if (AnimalInteractStatus.HOME.name().equals(animalInteractStatus) && AnimalFeedStatus.HUNGRY.name().equals(animalFeedStatus)) {
                    // feedFriendLimit 是服务端"今日帮喂已达上限"的信号，已满就不必逐个成员再试
                    if (familyOptions.getValue().contains("familyFeed") && !feedFriendLimit) {
                        feedFriendAnimal(farmId);
                    }
                }
            }

            // 家庭签到
            if (familySignTips && familyOptions.getValue().contains("familySign")) {
                familySign();
            }
            // 顶梁柱功能
            if (assignFamilyMemberInfo != null && familyOptions.getValue().contains("assignRights") && !"USED".equals(assignFamilyMemberInfo.getJSONObject("assignRights").optString("status"))) {
                if (UserIdMap.getCurrentUid().equals(assignFamilyMemberInfo.getJSONObject("assignRights").optString("assignRightsOwner"))) {
                    assignFamilyMember(assignFamilyMemberInfo, familyUserIds);
                }
                /*else {
                    Log.record("家庭任务🏡[使用顶梁柱特权] 不是家里的顶梁柱！");
                     移除选项，避免重复检查
                    familyOptions.getValue().remove("assignRights");
                }*/
            }

            // 领取家庭奖励
            if (familyOptions.getValue().contains("familyClaimReward") && familyAwardNum > 0) {
                familyAwardList();
            }

            JSONArray familyInteractActions = joenterFamily.optJSONArray("familyInteractActions");
            JSONObject eatTogetherConfig = joenterFamily.getJSONObject("eatTogetherConfig");
            //家庭请客吃饭
            boolean canEatTogether = true;
            if (familyInteractActions != null) {
                for (int i = 0; i < familyInteractActions.length(); i++) {
                    JSONObject familyInteractAction = familyInteractActions.getJSONObject(i);
                    if ("EatTogether".equals(familyInteractAction.optString("familyInteractType"))) {
                        canEatTogether = false;
                    }
                }
            }
            // 一起吃饭
            if (canEatTogether && familyOptions.getValue().contains("familyEatTogether") && eatTogetherConfig.has("periodItemList")) {
                familyEatTogether(ownerGroupId, EatTogetherUserIds);
            }

            // 道早安
            if (familyOptions.getValue().contains("deliverMsgSend")) {
                deliverMsgSend(familyAnimalsExceptUser, familyUserIds);
            }

            // 分享给好友
            if (familyOptions.getValue().contains("shareToFriends")) {
                familyShareToFriends(ownerGroupId, familyUserIds, notInviteList);
            }
        } catch (Throwable t) {
            Log.err(TAG, "family err:", t);
        }
    }

    /**
     * 顶梁柱功能
     */
    private void assignFamilyMember(JSONObject jsonObject, List<String> userIds) {
        try {
            userIds.remove(UserIdMap.getCurrentUid());
            if (userIds.isEmpty()) {
                return;
            }
            // RandomUtil.nextInt 是右开区间 [min, max)，这里要传 size()/length() 才能取到最后一个
            String beAssignUser = userIds.get(RandomUtil.nextInt(0, userIds.size()));
            JSONArray assignConfigList = jsonObject.getJSONArray("assignConfigList");
            JSONObject assignConfig = assignConfigList.getJSONObject(RandomUtil.nextInt(0, assignConfigList.length()));
            JSONObject jo = new JSONObject(AntFarmRpcCall.assignFamilyMember(assignConfig.getString("assignAction"), beAssignUser));
            if (MessageUtil.checkMemo(TAG, jo)) {
                Log.farm("家庭任务🏡[使用顶梁柱特权] " + assignConfig.getString("assignDesc"));
            }
        } catch (Throwable t) {
            Log.err(TAG, "assignFamilyMember err:", t);
        }
    }

    private void familyEatTogether(String groupId, JSONArray EatTogetherUserIds) {
        long currentTime = System.currentTimeMillis();
        String periodName;
        if (TimeUtil.isAfterTimeStr(currentTime, "0600") && TimeUtil.isBeforeTimeStr(currentTime, "1100")) {
            periodName = "早餐";
        } else if (TimeUtil.isAfterTimeStr(currentTime, "1100") && TimeUtil.isBeforeTimeStr(currentTime, "1600")) {
            periodName = "午餐";
        } else if (TimeUtil.isAfterTimeStr(currentTime, "1600") && TimeUtil.isBeforeTimeStr(currentTime, "2000")) {
            periodName = "晚餐";
        } else {
            return;
        }
        try {
            JSONArray cuisines = queryRecentFarmFood(EatTogetherUserIds.length());
            if (cuisines == null) {
                return;
            }
            JSONObject jo = new JSONObject(AntFarmRpcCall.familyEatTogether(groupId, cuisines, EatTogetherUserIds));
            if (MessageUtil.checkMemo(TAG, jo)) {
                Log.farm("亲密家庭🏠请客[" + periodName + "]#消耗美食" + EatTogetherUserIds.length() + "份");
                syncFamilyStatus(groupId);
            }
        } catch (Throwable t) {
            Log.err(TAG, "familyEatTogether err:", t);
        }
    }

    /**
     * 家庭「道早安」任务
     * <p>
     * <p>
     * <p>
     * 1）先通过 familyTaskTips 判断今日是否还有「道早安」任务：
     * - 请求方法：com.alipay.antfarm.familyTaskTips
     * - 请求体关键字段：
     * animals      -> 直接复用 enterFamily 返回的家庭 animals 列表
     * taskSceneCode-> "ANTFARM_FAMILY_TASK"
     * sceneCode    -> "ANTFARM"
     * source       -> "H5"
     * requestType  -> "NORMAL"
     * timeZoneId   -> "Asia/Shanghai"
     * - 响应 familyTaskTips 数组中存在 bizKey="GREETING" 且 taskStatus="TODO" 时，说明可以道早安
     * <p>
     * 2）未完成早安任务时，按顺序调用以下 RPC 获取 AI 文案并发送：
     * a. com.alipay.antfarm.deliverSubjectRecommend
     * -> 入参：friendUserIds（家庭其他成员 userId 列表），sceneCode="ChickFamily"，source="H5"
     * -> 取出：ariverRpcTraceId、eventId、eventName、sceneId、sceneName 等上下文
     * b. com.alipay.antfarm.DeliverContentExpand
     * -> 入参：上一步取到的 ariverRpcTraceId / eventId / eventName / sceneId / sceneName 等 + friendUserIds
     * -> 返回：AI 生成的 content 以及 deliverId
     * c. com.alipay.antfarm.QueryExpandContent
     * -> 入参：deliverId
     * -> 用于再次确认 content 与场景（可选安全校验）
     * d. com.alipay.antfarm.DeliverMsgSend
     * -> 入参：content、deliverId、friendUserIds、groupId（家庭 groupId）、sceneCode="ANTFARM"、spaceType="ChickFamily" 等
     * <p>
     * 额外增加保护：
     * - 仅在每天 06:00~10:00 之间执行
     * - 每日仅发送一次（本地 Status 标记 + 远端 familyTaskTips 双重判断）
     * - 自动从家庭成员列表中移除自己，避免接口报参数错误
     *
     * @param familyUserIds 家庭成员 userId 列表（包含自己，方法内部会移除当前账号）
     */
    private void deliverMsgSend(JSONArray familyAnimalsExceptUser, List<String> familyUserIds) {
        try {
            // 时间窗口控制：仅允许在「早安时间段」内自动发送（06:00 ~ 10:00）
            Calendar now = Calendar.getInstance();
            Calendar startTime = Calendar.getInstance();
            startTime.set(Calendar.HOUR_OF_DAY, 6);
            startTime.set(Calendar.MINUTE, 0);
            startTime.set(Calendar.SECOND, 0);
            startTime.set(Calendar.MILLISECOND, 0);

            Calendar endTime = Calendar.getInstance();
            endTime.set(Calendar.HOUR_OF_DAY, 10);
            endTime.set(Calendar.MINUTE, 0);
            endTime.set(Calendar.SECOND, 0);
            endTime.set(Calendar.MILLISECOND, 0);

            if (now.before(startTime) || now.after(endTime)) {
                return;
            }

            if (StringUtil.isEmpty(ownerGroupId)) {
                Log.record("家庭任务🏠道早安#未检测到家庭 groupId，可能尚未加入家庭，跳过");
                return;
            }

            // 本地去重：一天只发送一次
            if (Status.hasFlagToday("antFarm::deliverMsgSend")) {
                return;
            }

            // 远端任务状态校验
            try {
                JSONObject taskTipsRes = new JSONObject(AntFarmRpcCall.familyTaskTips(familyAnimalsExceptUser));
                if (!MessageUtil.checkMemo(TAG, taskTipsRes)) {
                    Log.record("家庭任务🏠道早安#familyTaskTips 调用失败，跳过");
                    return;
                }

                JSONArray taskTips = taskTipsRes.optJSONArray("familyTaskTips");
                if (taskTips == null || taskTips.length() == 0) {
                    // 列表为空既可能是今日已完成，也可能是响应缺项——不能凭猜就打整日标记，
                    // 否则那天真来任务时也不再发送。留给下一轮重试。
                    Log.record("家庭任务🏠道早安#远端未取到任务列表#稍后重试");
                    return;
                }

                boolean hasGreetingTodo = false;
                for (int i = 0; i < taskTips.length(); i++) {
                    JSONObject item = taskTips.getJSONObject(i);
                    String bizKey = item.optString("bizKey");
                    String taskStatus = item.optString("taskStatus");
                    if ("GREETING".equals(bizKey) && "TODO".equals(taskStatus)) {
                        hasGreetingTodo = true;
                        break;
                    }
                }

                if (!hasGreetingTodo) {
                    // 「非 TODO」不等于「已完成」——也可能是今天还没生成这项。
                    // 真正发成功的那条路径才会打当日标记，这里只跳过本轮，留给下一轮再看。
                    Log.record("家庭任务🏠道早安#GREETING 任务非 TODO 状态，本轮跳过");
                    return;
                }
            } catch (Throwable e) {
                Log.printStackTrace("familyTaskTips 解析失败，出于安全考虑跳过道早安：", e);
                return;
            }

            // 构建好友 userId 列表（去掉自己）
            List<String> userIdsCopy = new ArrayList<>(familyUserIds);
            userIdsCopy.remove(UserIdMap.getCurrentUid());
            if (userIdsCopy.isEmpty()) {
                Log.record("家庭任务🏠道早安#家庭成员仅自己一人，跳过");
                return;
            }

            JSONArray userIds = new JSONArray();
            for (String userId : userIdsCopy) {
                userIds.put(userId);
            }

            // 确认 AI 隐私协议
            JSONObject resp0 = new JSONObject(AntFarmRpcCall.OpenAIPrivatePolicy());
            if (!MessageUtil.checkMemo(TAG, resp0)) {
                Log.record("家庭任务🏠道早安#OpenAIPrivatePolicy 调用失败");
                return;
            }

            // 请求推荐早安场景
            JSONObject resp1 = new JSONObject(AntFarmRpcCall.deliverSubjectRecommend(userIds));
            if (!MessageUtil.checkMemo(TAG, resp1)) {
                Log.record("家庭任务🏠道早安#deliverSubjectRecommend 调用失败");
                return;
            }

            String ariverRpcTraceId = resp1.getString("ariverRpcTraceId");
            String eventId = resp1.getString("eventId");
            String eventName = resp1.getString("eventName");
            String memo = resp1.optString("memo");
            String resultCode = resp1.optString("resultCode");
            String sceneId = resp1.getString("sceneId");
            String sceneName = resp1.getString("sceneName");
            boolean success = resp1.optBoolean("success", true);

            // 调用 DeliverContentExpand
            JSONObject resp2 = new JSONObject(AntFarmRpcCall.deliverContentExpand(ariverRpcTraceId, eventId, eventName, memo, resultCode, sceneId, sceneName, success, userIds));
            if (!MessageUtil.checkMemo(TAG, resp2)) {
                Log.record("家庭任务🏠道早安#DeliverContentExpand 调用失败");
                return;
            }

            String deliverId = resp2.getString("deliverId");
            //String deliverId = System.currentTimeMillis()+UserIdMap.getCurrentUid();

            // 使用 deliverId 确认扩展内容
            JSONObject resp3 = new JSONObject(AntFarmRpcCall.QueryExpandContent(deliverId));
            if (!MessageUtil.checkMemo(TAG, resp3)) {
                Log.record("家庭任务🏠道早安#QueryExpandContent 调用失败");
                return;
            }

            String content = resp3.getString("content");

            // 最终发送早安消息
            JSONObject resp4 = new JSONObject(AntFarmRpcCall.deliverMsgSend(ownerGroupId, userIds, content, deliverId));
            if (MessageUtil.checkMemo(TAG, resp4)) {
                Log.farm("家庭任务🌈[道早安]" + StringUtil.truncate(content, 200));
                Status.flagToday("antFarm::deliverMsgSend");
            }
        } catch (Throwable t) {
            Log.err(TAG, "deliverMsgSend err:", t);
        }
    }

    /**
     * 好友分享家庭
     */
    private void familyShareToFriends(String ownerGroupId, List<String> familyUserIds, SelectModelField notInviteList) {
        try {
            if (Status.hasFlagToday("antFarm::familyShareToFriends")) {
                return;
            }

            Set<String> notInviteSet = notInviteList.getValue();
            List<AlipayUser> allUser = AlipayUser.getList();
            if (allUser.isEmpty()) {
                Log.record("allUser is empty");
                return;
            }

            // 打乱顺序，实现随机选取
            List<AlipayUser> shuffledUsers = new ArrayList<>(allUser);
            Collections.shuffle(shuffledUsers);
            JSONArray inviteList = new JSONArray();
            for (AlipayUser user : shuffledUsers) {
                if (!familyUserIds.contains(user.getId()) && !notInviteSet.contains(user.getId()) && (!user.getId().equals(UserIdMap.getCurrentUid()))) {
                    inviteList.put(user.getId());
                    if (inviteList.length() >= 2) {
                        break;
                    }
                }
            }

            if (inviteList.length() == 0) {
                Log.record("没有符合分享条件的好友");
                return;
            }
            Log.record("家庭分享🏠邀请:" + inviteList);

            //JSONObject jo = new JSONObject(AntFarmRpcCall.inviteFriendVisitFamily(inviteList));
            int invitedCount = 0;
            for (int i = 0; i < inviteList.length(); i++) {
                String inviteUID = inviteList.getString(i);
                JSONObject jo = new JSONObject(AntFarmRpcCall.batchInviteP2P(ownerGroupId, inviteUID));
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    Log.farm("家庭任务🏠分享给好友[" + UserIdMap.getShowName(inviteUID) + "]");
                    invitedCount++;
                }
            }
            // 全部失败时累计失败次数，达到次数上限才置标记：既不会"一次失败就整天不试"，也不会每轮都重发邀请
            if (invitedCount > 0) {
                Status.flagToday("antFarm::familyShareToFriends");
            } else {
                int failCount = Status.getIntFlagToday(FLAG_FAMILY_SHARE_FAIL_COUNT) + 1;
                if (failCount >= MAX_FAMILY_SHARE_ATTEMPT) {
                    Status.flagToday("antFarm::familyShareToFriends");
                    Log.farm("家庭分享🏠邀请已连续失败" + failCount + "次，今日不再尝试");
                } else {
                    Status.setIntFlagToday(FLAG_FAMILY_SHARE_FAIL_COUNT, failCount);
                    Log.farm("家庭分享🏠邀请全部失败(第" + failCount + "/" + MAX_FAMILY_SHARE_ATTEMPT + "次)，稍后重试");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "familyShareToFriends err:", t);
        }
    }

    /**
     * 时间差格式化
     */
    private String formatDuration(long diffMillis) {
        long absSeconds = Math.abs(diffMillis) / 1000;

        long value;
        String unit;
        if (absSeconds < 60) {
            value = absSeconds;
            unit = "秒";
        } else if (absSeconds < 3600) {
            value = absSeconds / 60;
            unit = "分钟";
        } else if (absSeconds < 86400) {
            value = absSeconds / 3600;
            unit = "小时";
        } else if (absSeconds < 2592000) {
            value = absSeconds / 86400;
            unit = "天";
        } else if (absSeconds < 31536000) {
            value = absSeconds / 2592000;
            unit = "个月";
        } else {
            value = absSeconds / 31536000;
            unit = "年";
        }

        if (absSeconds < 1) {
            return "刚刚";
        } else if (diffMillis > 0) {
            return value + unit + "后";
        } else {
            return value + unit + "前";
        }
    }

    private String getFamilyGroupId(String userId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryLoveCabin(userId));
            if (MessageUtil.checkMemo(TAG, jo)) {
                return jo.optString("groupId");
            }
        } catch (Throwable t) {
            Log.i(TAG, "getGroupId err:");
            Log.printStackTrace(t);
        }
        return null;
    }

    private JSONObject enterFamily() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterFamily());
            if (MessageUtil.checkMemo(TAG, jo)) {
                return jo;
            }
        } catch (Throwable t) {
            Log.err(TAG, "enterFamily err:", t);
        }
        return null;
    }

    private Boolean familySleep(String groupId) {
        if (StringUtil.isEmpty(groupId)) {
            Log.record("小鸡在亲密家庭🏠中，但未取到家庭 groupId，跳过睡觉");
            return false;
        }
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.familySleep(groupId));
            if (MessageUtil.checkMemo(TAG, jo)) {
                Log.farm("亲密家庭🏠小鸡睡觉");
                syncFamilyStatus(groupId);
                return true;
            }
            Log.farm("亲密家庭🏠[小鸡睡觉]#失败:" + jo.optString("memo"));
        } catch (Throwable t) {
            Log.err(TAG, "familySleep err:", t);
        }
        return false;
    }

    private Boolean familyWakeUp() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.familyWakeUp());
            if (MessageUtil.checkMemo(TAG, jo)) {
                Log.farm("亲密家庭🏠小鸡起床");
                return true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "familyWakeUp err:", t);
        }
        return false;
    }

    private void familyAwardList() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.familyAwardList());
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return;
            }
            JSONArray ja = jo.getJSONArray("familyAwardRecordList");
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                if (jo.optBoolean("expired") || jo.optBoolean("received", true) || jo.has("linkUrl") || (jo.has("operability") && !jo.getBoolean("operability"))) {
                    continue;
                }
                String rightId = jo.getString("rightId");
                String awardName = jo.getString("awardName");
                int count = jo.optInt("count", 1);
                receiveFamilyAward(rightId, awardName, count);
            }
        } catch (Throwable t) {
            Log.err(TAG, "familyAwardList err:", t);
        }
    }

    private void receiveFamilyAward(String rightId, String awardName, int count) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.receiveFamilyAward(rightId));
            if (MessageUtil.checkMemo(TAG, jo)) {
                Log.farm("亲密家庭🏠领取奖励[" + awardName + "*" + count + "]");
            }
        } catch (Throwable t) {
            Log.err(TAG, "familyAwardList err:", t);
        }
    }

    private void familyReceiveFarmTaskAward(String taskId, String title) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.familyReceiveFarmTaskAward(taskId));
            if (MessageUtil.checkMemo(TAG, jo)) {
                Log.farm("亲密家庭🏠提交任务[" + title + "]");
            }
        } catch (Throwable t) {
            Log.err(TAG, "familyReceiveFarmTaskAward err:", t);
        }
    }

    private JSONArray queryRecentFarmFood(int needCount) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.syncAnimalStatus(ownerFarmId));
            if (!MessageUtil.checkMemo(TAG, jo)) {
                return null;
            }
            JSONArray cuisineList = jo.getJSONArray("cuisineList");
            if (cuisineList.length() == 0) {
                return null;
            }
            List<JSONObject> list = getSortedCuisineList(cuisineList);
            JSONArray result = new JSONArray();
            int count = 0;
            for (int i = 0; i < list.size() && count < needCount; i++) {
                jo = list.get(i);
                int countTemp = jo.getInt("count");
                if (count + countTemp >= needCount) {
                    countTemp = needCount - count;
                    jo.put("count", countTemp);
                }
                count += countTemp;
                result.put(jo);
            }
            if (count == needCount) {
                return result;
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryRecentFarmFood err:", t);
        }
        return null;
    }

    private void familySign() {
        familyReceiveFarmTaskAward("FAMILY_SIGN_TASK", "每日签到");
    }

    private void syncFamilyStatus(String groupId) {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.syncFamilyStatus(groupId, "INTIMACY_VALUE", ownerUserId));
            MessageUtil.checkMemo(TAG, jo);
        } catch (Throwable t) {
            Log.err(TAG, "syncFamilyStatus err:", t);
        }
    }

    public interface RecallAnimalType {

        int ALWAYS = 0;
        int WHEN_THIEF = 1;
        int WHEN_HUNGRY = 2;
        int NEVER = 3;

        String[] nickNames = {"始终召回", "偷吃召回", "饥饿召回", "暂不召回"};
    }

    public interface SendBackAnimalWay {

        int HIT = 0;
        int NORMAL = 1;

        String[] nickNames = {"攻击", "常规"};
    }

    public interface SendBackAnimalType {

        int NONE = 0;
        int BACK = 1;
        int NOT_BACK = 2;

        String[] nickNames = {"不遣返小鸡", "遣返已选好友", "遣返未选好友"};
    }

    public enum AnimalBuff {
        ACCELERATING, INJURED, NONE
    }

    public enum AnimalFeedStatus {
        HUNGRY, EATING, SLEEPY
    }

    public enum AnimalInteractStatus {
        HOME, GOTOSTEAL, STEALING
    }

    public enum SubAnimalType {
        NORMAL, GUEST, PIRATE, WORK, NPC
    }

    /** 可雇佣的庄园 NPC：animalId 是活动批次 ID，活动切换后失效（失效只让雇佣失败，不影响其它功能） */
    public enum HireNpc {
        DA_BIAO_GE("大表鸽", "20250901105101013088000000000006", "zhimaxiaoji_lianjin", "ANTFARM_ZHIMA_NPC_TASK", "粒芝麻"),
        // awardType 与饲料任务同值，单位只能按 NPC 定
        HUANG_JIN_JI("黄金鸡", "20250725105101013088000000000004", "licaixiaoji_2025_1", "ANTFARM_CAIFU_NPC_TASK", "份黄金票"),
        NONG_CHANG_XIAO_JI("农场小鸡", "20250613105101013088000000000002", "H5", "feiliaoji_202507", "g肥料");
        // 以下四只暂不启用；解除时把上行分号改回逗号
        // 淘金币需先 npcVisitAnimal 查到访并上报 IMPRESSION 才能雇，现有流程未实现
        // TAO_JIN_BI_XIAO_JI("淘金币小鸡", "202609201051013088000000000018", "H5", "ANTFARM_TAOJINBI_NPC_TASK", "个淘金币"),
        // DAO_DIAN_HONG_BAO_XIAO_JI("到店红包小鸡", "20260115105101013088000000000013", "H5", "ANTFARM_OFFLINE_PAY_NPC_TASK", "元红包"),
        // TONG_YONG_HONG_BAO_XIAO_JI("通用红包小鸡", "202607281051013088000000000015", "H5", "ANTFARM_PWGROWTH_NPC_TASK_A", "元红包"),
        // TONG_YONG_QUAN_BAO_XIAO_JI("通用券包小鸡", "202607281051013088000000000016", "H5", "ANTFARM_PWGROWTH_NPC_TASK_B", "元券包");

        private final String nickName;
        private final String animalId;
        private final String source;
        private final String taskSceneCode;
        /** 奖励单位；null 时按 awardType 推断 */
        private final String awardUnit;

        HireNpc(String nickName, String animalId, String source, String taskSceneCode, String awardUnit) {
            this.nickName = nickName;
            this.animalId = animalId;
            this.source = source;
            this.taskSceneCode = taskSceneCode;
            this.awardUnit = awardUnit;
        }

        /** 未指定单位时按 awardType 推断；认不出的类型原样显示，便于排查 */
        String awardUnitOf(String awardType) {
            if (awardUnit != null) {
                return awardUnit;
            }
            if (awardType == null || awardType.isEmpty()) {
                return "";
            }
            if (awardType.contains("FOOD")) {
                return "g饲料";
            }
            if (awardType.contains("GOLD")) {
                return "份黄金票";
            }
            return "份(" + awardType + ")";
        }

        /** 按 animalId 反查活动批次；批次失效或换了新批次时返回 null。 */
        static HireNpc of(String animalId) {
            for (HireNpc npc : values()) {
                if (npc.animalId.equals(animalId)) {
                    return npc;
                }
            }
            return null;
        }
    }

    public enum ToolType {
        STEALTOOL, ACCELERATETOOL, SHARETOOL, FENCETOOL, NEWEGGTOOL, DOLLTOOL, BIG_EATER_TOOL, ADVANCE_ORNAMENT_TOOL, ORDINARY_ORNAMENT_TOOL, RARE_ORNAMENT_TOOL;

        public static final CharSequence[] nickNames = {"蹭饭卡", "加速卡", "救济卡", "篱笆卡", "新蛋卡", "公仔补签卡", "加饭卡", "高级装扮补签", "普通装扮补签卡", "稀有装扮补签卡"};

        public CharSequence nickName() {
            return nickNames[ordinal()];
        }
    }

    public enum GameType {
        starGame, jumpGame, flyGame, hitGame;

        public static final CharSequence[] gameNames = {"星星球", "登山赛", "飞行赛", "欢乐揍小鸡"};

        public CharSequence gameName() {
            return gameNames[ordinal()];
        }
    }

    private static class Animal {
        public String animalId, currentFarmId, masterFarmId, animalBuff, subAnimalType, animalFeedStatus, animalInteractStatus;
        public String locationType;

        public String currentFarmMasterUserId;

        public Long startEatTime, beHiredEndTime;

        public Double consumeSpeed;

        public Double foodHaveEatten;
    }

    public enum TaskStatus {
        TODO, FINISHED, RECEIVED
    }

    private static class RewardFriend {
        public String consistencyKey, friendId, time;
    }

    private static class FarmTool {
        public ToolType toolType;
        public String toolId;
        public int toolCount, toolHoldLimit;
    }

    public interface HireAnimalType {

        int NONE = 0;
        int HIRE = 1;
        int NOT_HIRE = 2;

        String[] nickNames = {"不雇佣小鸡", "雇佣已选好友", "雇佣未选好友"};
    }

    //  public interface GetFeedType {

    //      int NONE = 0;
    //      int GIVE = 1;
    //     int RANDOM = 2;

    //    String[] nickNames = {"不赠送饲料", "赠送已选好友", "赠送随机好友"};
    // }

    public interface NotifyFriendType {

        int NONE = 0;
        int NOTIFY = 1;
        int NOT_NOTIFY = 2;

        String[] nickNames = {"不通知赶鸡", "通知已选好友", "通知未选好友"};
    }

    public interface DonationType {

        int ZERO = 0;
        int ONE = 1;
        int ALL = 2;

        String[] nickNames = {"不捐赠", "捐赠一个项目", "捐赠所有项目"};
    }

    public enum ItemStatus {
        NO_ENOUGH_POINT, REACH_LIMIT, REACH_USER_HOLD_LIMIT;

        public static final String[] nickNames = {"乐园币不足", "兑换达到上限", "达到用户持有上限"};

        public String nickName() {
            return nickNames[ordinal()];
        }
    }
}
