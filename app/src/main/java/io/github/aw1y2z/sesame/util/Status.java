package io.github.aw1y2z.sesame.util;

import com.fasterxml.jackson.databind.JsonMappingException;

import lombok.Data;

import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.model.task.antFarm.AntFarm;
import io.github.aw1y2z.sesame.model.task.antForest.AntForestV2;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;
import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelField;

import java.io.File;
import java.util.*;

@Data
public class Status {
    
    private static final String TAG = Status.class.getSimpleName();
    
    public static final Status INSTANCE = new Status();

    /**
     * 保存失败是否已提示过：save() 每天会被调用几十次，持续失败时不该刷满日志与通知，
     * 成功一次后复位（静态字段不进 Jackson，不落盘）
     */
    private static volatile boolean saveFailureNotified = false;

    /** 本次加载是否解析失败：内存只剩默认值时不得落盘，否则当天标记被整份清空 */
    private static volatile boolean loadFailed = false;

    /**
     * 内存里这份状态属于哪个 uid（静态字段不进 Jackson、不落盘）。
     * <p>启动早期 uid 可能尚未就绪，此时 load() 读到的不是本账号的文件；若据此落盘，
     * 会覆盖真实账号当天的 status.json。uid 与之一致前，读、写都必须先按当前 uid 重载，
     * 见 {@link #ensureLoadedForCurrentUid()}。
     */
    private static volatile String loadedUid;

    /** 帮喂好友/家庭成员：当日总次数已达上限（服务端返回 resultCode=391），全局标记 */
    public static final String FLAG_FEED_FRIEND_ANIMAL_LIMIT = "farm::feedFriendAnimalLimit";
    
    // forest
    private final Map<String, Integer> waterFriendLogList = new HashMap<>();
    private final Map<String, Integer> wateredFriendLogList = new HashMap<>();
    private final Map<String, Integer> wateringFriendLogList = new HashMap<>();
    private final Map<String, Integer> forestHuntHelpLogList = new HashMap<>();
    private final Map<String, Integer> vitality_ExchangeBenefitLogList = new HashMap<>();
    private final Map<Integer, Integer> exchangeReserveLogList = new HashMap<>();
    private final Set<String> ancientTreeCityCodeList = new HashSet<>();
    
    private int doubleTimes = 0;
    
    // farm
    private final Map<String, Integer> feedFriendLogList = new HashMap<>();
    private final Map<String, Integer> visitFriendLogList = new HashMap<>();
    private final Map<String, Integer> gameCenterBuyMallItemList = new HashMap<>();
    private int useAccelerateToolCount = 0;
    private int useSpecialFoodCount = 0;
    
    // orchard
    private final Set<String> orchardShareP2PLogList = new HashSet<>();
    
    // stall
    private final Map<String, Integer> stallHelpedCountLogList = new HashMap<>();
    private final Set<String> stallShareP2PLogList = new HashSet<>();
    
    // member
    private final Set<String> memberPointExchangeBenefitLogList = new HashSet<>();
    
    // other
    private final Set<String> flagLogList = new HashSet<>();


    /**
     * 当日整型标记：tag -> 累计值（如金豆夺宝芝麻粒换豆当日已兑换金豆数）。
     * <p>随 status.json 的每日重置自动清空（updateDay -> unload -> new Status()）。
     */
    private final Map<String, Integer> intFlagLogList = new HashMap<>();
    
    // 保存时间
    private Long saveTime = 0L;
    
    /**
     * 绿色经营，收取好友金币已完成用户
     */
    private boolean greenFinancePointFriend = false;
    
    /**
     * 绿色经营，评级领奖已完成用户
     */
    private final Set<Integer> greenFinancePrizesSet = new HashSet<>();

    /**
     * 金豆，已领取奖励的任务ID
     */
    private final Set<String> goldenBeansTaskReceivedSet = new HashSet<>();
    
    public static synchronized Boolean hasFlagToday(String tag) {
        // 读也要先对齐归属，否则会拿「别的账号/空的状态」判断
        ensureLoadedForCurrentUid();
        return INSTANCE.flagLogList.contains(tag);
    }
    
    public static synchronized void flagToday(String tag) {
        if (!hasFlagToday(tag)) {
            INSTANCE.flagLogList.add(tag);
            save();
        }
    }
    
    //在写入status中时，重要数据提前记录Uid,一定程度上避免因支付宝账号切换导致标记到下一个账号的少数情况
    public static synchronized void flagToday(String tag, String taskUid) {
        if (!hasFlagToday(tag)) {
            if (taskUid.equals(UserIdMap.getCurrentUid())) {
                INSTANCE.flagLogList.add(tag);
                save();
            }
        }
    }
    
    /**
     * 读取当日整型标记（不存在时返回 0）
     */
    public static synchronized int getIntFlagToday(String tag) {
        Integer value = INSTANCE.intFlagLogList.get(tag);
        return value == null ? 0 : value;
    }
    
    /**
     * 写入当日整型标记（用于累计类额度，如芝麻粒换豆当日已兑换金豆数）
     */
    public static synchronized void setIntFlagToday(String tag, int value) {
        INSTANCE.intFlagLogList.put(tag, value);
        save();
    }
    
    // 清除单个指定Flag
    public static synchronized void clearFlag(String tag) {
        if (INSTANCE.flagLogList.contains(tag)) {
            INSTANCE.flagLogList.remove(tag);
            save(); // 清除后需保存状态，避免下次加载时恢复
        }
    }
    
    //根据助力场景记录助力次数
    public static synchronized void forestHuntHelpToday(String taskType, int count, String taskUid) {
        if (taskUid.equals(UserIdMap.getCurrentUid())) {
            INSTANCE.forestHuntHelpLogList.put(taskType, count);
            save();
        }
    }
    
    public static synchronized Integer getforestHuntHelpToday(String taskType) {
        Integer count = INSTANCE.forestHuntHelpLogList.get(taskType);
        if (count == null) {
            return 0;
        }
        else {
            return count;
        }
    }
    
    //记录完成任务次数
    public static synchronized void rpcRequestListToday(String taskName, int count) {
        INSTANCE.forestHuntHelpLogList.put(taskName, count);
        save();
    }
    
    public static synchronized Integer getrpcRequestListToday(String taskName) {
        Integer count = INSTANCE.forestHuntHelpLogList.get(taskName);
        if (count == null) {
            return 0;
        }
        else {
            return count;
        }
    }
    
    public static synchronized void wateredFriendToday(String id) {
        Integer count = INSTANCE.wateredFriendLogList.get(id);
        if (count == null) {
            count = 0; // 首次被浇水，次数初始化为0
        }
        INSTANCE.wateredFriendLogList.put(id, count + 1);
        save();
    }
    public static synchronized void getWateredFriendToday() {
        // 1. 基础统计：浇水好友数量（Map的key数量）
        int friendCount = INSTANCE.wateredFriendLogList.size();
        // 2. 统计总浇水量（遍历Map累加所有value）
        int totalWaterAmount = 0;
        
        // 3. 遍历Map的键值对并输出详细信息
        for (Map.Entry<String, Integer> entry : INSTANCE.wateredFriendLogList.entrySet()) {
            String friendId = entry.getKey();       // 好友ID
            Integer waterAmount = entry.getValue(); // 给该好友的浇水量（避免空指针）
            if (waterAmount == null) {
                waterAmount = 0;
            }
            
            // 可选：通过UserIdMap获取好友昵称（如果需要显示名称而非ID）
            String friendName = UserIdMap.getShowName(friendId);
            
            // 输出单条明细（日志/控制台）
            Log.forest("统计被水🍯被["+friendName+"]浇水"+waterAmount+"次");
            
            // 累加总浇水量
            totalWaterAmount += waterAmount;
        }
        
        // 4. 输出汇总统计信息
        Log.forest("统计被水🍯共计被"+friendCount+"个好友浇水"+ totalWaterAmount+"次");
    
        // 5. 从ConfigV2读取wateredFriendList配置值
        Map<String, Integer> configWateredFriendList = null;
        try {
            ModelFields antForestFields = ConfigV2.INSTANCE.getModelFieldsMap().get("AntForestV2");
            if (antForestFields != null) {
                ModelField<?> wateredFriendField = antForestFields.get("wateredFriendList");
                if (wateredFriendField != null && wateredFriendField.getValue() instanceof Map) {
                    configWateredFriendList = (Map<String, Integer>) wateredFriendField.getValue();
                }
            }
        } catch (Exception e) {
            Log.err(TAG, "获取wateredFriendList配置失败:", e);
        }
        
        // 6. 如果配置存在且不为空，输出预计统计和差别
        if (configWateredFriendList != null && !configWateredFriendList.isEmpty()) {
            // 6.1 输出预计统计信息
            int configFriendCount = 0;
            int configTotalWaterAmount = 0;
            for (Map.Entry<String, Integer> entry : configWateredFriendList.entrySet()) {
                Integer configWaterAmount = entry.getValue();
                if (configWaterAmount != null && configWaterAmount > 0) {
                    configFriendCount++;
                    configTotalWaterAmount += configWaterAmount;
                }
            }
            Log.forest("统计被水🍯预计被"+configFriendCount+"个好友浇水"+ configTotalWaterAmount+"次");

            // 6.2 对比配置和实际浇水情况，输出差别
            for (Map.Entry<String, Integer> entry : configWateredFriendList.entrySet()) {
                String friendId = entry.getKey();
                Integer configWaterAmount = entry.getValue();
                if (configWaterAmount != null && configWaterAmount > 0) {
                    Integer actualWaterAmount = INSTANCE.wateredFriendLogList.get(friendId);
                    String friendName = UserIdMap.getShowName(friendId);
                    int actual = actualWaterAmount == null ? 0 : actualWaterAmount;
                    int diff = configWaterAmount - actual;
                    if (diff > 0) {
                        Log.forest("统计被水🍯被[" + friendName + "]少浇" + diff + "次");
                    } else if (diff < 0) {
                        Log.forest("统计被水🍯被[" + friendName + "]多浇" + (-diff) + "次");
                    }
                }
            }
            // 6.3 检查不在预计列表中但实际被浇了水的好友
            for (Map.Entry<String, Integer> entry : INSTANCE.wateredFriendLogList.entrySet()) {
                String friendId = entry.getKey();
                if (!configWateredFriendList.containsKey(friendId)) {
                    Integer actualWaterAmount = entry.getValue();
                    if (actualWaterAmount != null && actualWaterAmount > 0) {
                        String friendName = UserIdMap.getShowName(friendId);
                        Log.forest("统计被水🍯被[" + friendName + "]多浇" + actualWaterAmount + "次");
                    }
                }
            }
        }
    }

    public static synchronized void fillWateredFriendList() {
        // 1. 获取当前被浇水的好友数据
        Map<String, Integer> currentData = INSTANCE.wateredFriendLogList;
        if (currentData == null || currentData.isEmpty()) {
            Log.forest("填入被水🍯[当前没有被浇水数据]");
            return;
        }

        // 2. 从ConfigV2读取wateredFriendList配置值
        Map<String, Integer> configWateredFriendList = null;
        try {
            ModelFields antForestFields = ConfigV2.INSTANCE.getModelFieldsMap().get("AntForestV2");
            if (antForestFields != null) {
                ModelField<?> wateredFriendField = antForestFields.get("wateredFriendList");
                if (wateredFriendField != null && wateredFriendField.getValue() instanceof Map) {
                    configWateredFriendList = (Map<String, Integer>) wateredFriendField.getValue();
                }
            }
        } catch (Exception e) {
            Log.err(TAG, "获取wateredFriendList配置失败:", e);
        }

        // 3. 如果配置为空，创建一个新的LinkedHashMap
        if (configWateredFriendList == null) {
            configWateredFriendList = new LinkedHashMap<>();
        }

        // 4. 将当前被浇水数据填入配置
        int addedCount = 0;
        int updatedCount = 0;
        for (Map.Entry<String, Integer> entry : currentData.entrySet()) {
            String friendId = entry.getKey();
            Integer waterAmount = entry.getValue();
            if (waterAmount != null && waterAmount > 0) {
                if (configWateredFriendList.containsKey(friendId)) {
                    // 更新已有记录
                    configWateredFriendList.put(friendId, waterAmount);
                    updatedCount++;
                    String friendName = UserIdMap.getShowName(friendId);
                    Log.forest("填入被水🍯更新[" + friendName + "]为" + waterAmount + "次");
                } else {
                    // 新增记录
                    configWateredFriendList.put(friendId, waterAmount);
                    addedCount++;
                    String friendName = UserIdMap.getShowName(friendId);
                    Log.forest("填入被水🍯新增[" + friendName + "]" + waterAmount + "次");
                }
            }
        }

        // 5. 清除配置中不在实际被浇水列表里的项
        int removedCount = 0;
        Iterator<Map.Entry<String, Integer>> iterator = configWateredFriendList.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            String friendId = entry.getKey();
            if (!currentData.containsKey(friendId)) {
                    Integer waterAmount = entry.getValue();
                    iterator.remove();
                    removedCount++;
                    String friendName = UserIdMap.getShowName(friendId);
                    Log.forest("填入被水🍯清除[" + friendName + "]" + (waterAmount != null ? waterAmount : 0) + "次");
                }
        }

        // 6. 保存配置到ConfigV2
        try {
            ModelFields antForestFields = ConfigV2.INSTANCE.getModelFieldsMap().get("AntForestV2");
            if (antForestFields != null) {
                ModelField<?> wateredFriendField = antForestFields.get("wateredFriendList");
                if (wateredFriendField != null) {
                    wateredFriendField.setObjectValue(configWateredFriendList);
                    String currentUid = UserIdMap.getCurrentUid();
                    ConfigV2.save(currentUid, true);
                    Log.forest("填入被水🍯完成新增" + addedCount + "个更新" + updatedCount + "个清除" + removedCount + "个");
                }
            }
        } catch (Exception e) {
            Log.err(TAG, "保存wateredFriendList配置失败:", e);
        }
    }
    
    public static synchronized void wateringFriendToday(String id) {
        Integer count = INSTANCE.wateringFriendLogList.get(id);
        if (count == null) {
            count = 0; // 首次被浇水，次数初始化为0
        }
        INSTANCE.wateringFriendLogList.put(id, count + 1);
        // 与 wateredFriendToday 对齐：不落盘的话进程被重启后「今日浇水」统计会丢失
        save();
    }
    
    public static synchronized void getWateringFriendToday() {
        // 1. 基础统计：浇水好友数量（Map的key数量）
        int friendCount = INSTANCE.wateringFriendLogList.size();
        // 2. 统计总浇水量（遍历Map累加所有value）
        int totalWaterAmount = 0;
        
        // 3. 遍历Map的键值对并输出详细信息
        for (Map.Entry<String, Integer> entry : INSTANCE.wateringFriendLogList.entrySet()) {
            String friendId = entry.getKey();       // 好友ID
            Integer waterAmount = entry.getValue(); // 给该好友的浇水量（避免空指针）
            if (waterAmount == null) {
                waterAmount = 0;
            }
            
            // 可选：通过UserIdMap获取好友昵称（如果需要显示名称而非ID）
            String friendName = UserIdMap.getShowName(friendId);
            
            // 输出单条明细（日志/控制台）
            Log.forest("统计浇水🚿给["+friendName+"]浇水"+waterAmount+"次");
            
            // 累加总浇水量
            totalWaterAmount += waterAmount;
        }
        
        // 4. 输出汇总统计信息
        Log.forest("统计浇水🚿共计给"+friendCount+"个好友浇水"+ totalWaterAmount+"次");
        
        // 5. 从ConfigV2读取waterFriendList配置值
        Map<String, Integer> configWaterFriendList = null;
        try {
            ModelFields antForestFields = ConfigV2.INSTANCE.getModelFieldsMap().get("AntForestV2");
            if (antForestFields != null) {
                ModelField<?> waterFriendField = antForestFields.get("waterFriendList");
                if (waterFriendField != null && waterFriendField.getValue() instanceof Map) {
                    configWaterFriendList = (Map<String, Integer>) waterFriendField.getValue();
                }
            }
        } catch (Exception e) {
            Log.err(TAG, "获取waterFriendList配置失败:", e);
        }
        
        // 6. 如果配置存在且不为空，输出预计统计和差别
        if (configWaterFriendList != null && !configWaterFriendList.isEmpty()) {
            // 6.1 输出预计统计信息
            int configFriendCount = 0;
            int configTotalWaterAmount = 0;
            for (Map.Entry<String, Integer> entry : configWaterFriendList.entrySet()) {
                Integer configWaterAmount = entry.getValue();
                if (configWaterAmount != null && configWaterAmount > 0) {
                    configFriendCount++;
                    configTotalWaterAmount += configWaterAmount;
                }
            }
            Log.forest("统计浇水🚿预计给"+configFriendCount+"个好友浇水"+ configTotalWaterAmount+"次");

            // 6.2 对比配置和实际浇水情况，输出差别
            for (Map.Entry<String, Integer> entry : configWaterFriendList.entrySet()) {
                String friendId = entry.getKey();
                Integer configWaterAmount = entry.getValue();
                if (configWaterAmount != null && configWaterAmount > 0) {
                    Integer actualWaterAmount = INSTANCE.wateringFriendLogList.get(friendId);
                    String friendName = UserIdMap.getShowName(friendId);
                    int actual = actualWaterAmount == null ? 0 : actualWaterAmount;
                    int diff = configWaterAmount - actual;
                    if (diff != 0) {
                        Log.forest("统计浇水🚿给[" + friendName + "]缺少" + diff + "次");       
                    }
                }
            }
        }
    }
    
    public static synchronized Boolean canWaterFriendToday(String id, int newCount) {
        Integer count = INSTANCE.waterFriendLogList.get(id);
        if (count == null) {
            return true;
        }
        return count < newCount;
    }
    
    public static synchronized int getWaterFriendToday(String id) {
        Integer count = INSTANCE.waterFriendLogList.get(id);
        return count == null ? 0 : count;
    }

    public static synchronized void waterFriendToday(String id, int count, String taskUid) {
        if (taskUid.equals(UserIdMap.getCurrentUid())) {
            // 累加当日已浇次数：覆盖式记账会让「部分失败后再浇满」越过用户配置
            INSTANCE.waterFriendLogList.put(id, getWaterFriendToday(id) + count);
            save();
        }
    }

    /** 显式清零当日浇水次数：累加语义下不能用「加 0」代替重置 */
    public static synchronized void resetWaterFriendToday(String id, String taskUid) {
        if (taskUid.equals(UserIdMap.getCurrentUid())) {
            INSTANCE.waterFriendLogList.put(id, 0);
            save();
        }
    }


    
    public static synchronized int getVitalityExchangeBenefitCountToday(String skuId) {
        Integer exchangedCount = INSTANCE.vitality_ExchangeBenefitLogList.get(skuId);
        if (exchangedCount == null) {
            exchangedCount = 0;
        }
        return exchangedCount;
    }
    
    public static synchronized Boolean canVitalityExchangeBenefitToday(String skuId, int count) {
        return !hasFlagToday("forest::exchangeLimit::" + skuId) && getVitalityExchangeBenefitCountToday(skuId) < count;
    }
    
    public static synchronized void vitalityExchangeBenefitToday(String skuId) {
        int count = getVitalityExchangeBenefitCountToday(skuId) + 1;
        INSTANCE.vitality_ExchangeBenefitLogList.put(skuId, count);
        save();
    }
    
    public static synchronized int getGameCenterBuyMallItemCountToday(String skuId) {
        Integer buyedCount = INSTANCE.gameCenterBuyMallItemList.get(skuId);
        if (buyedCount == null) {
            buyedCount = 0;
        }
        return buyedCount;
    }
    
    public static synchronized Boolean canGameCenterBuyMallItemToday(String skuId, int count) {
        return !hasFlagToday("farm::buyLimit::" + skuId) && getGameCenterBuyMallItemCountToday(skuId) < count;
    }
    
    public static synchronized void gameCenterBuyMallItemToday(String skuId) {
        int count = getGameCenterBuyMallItemCountToday(skuId) + 1;
        INSTANCE.gameCenterBuyMallItemList.put(skuId, count);
        save();
    }
    
    public static synchronized int getExchangeReserveCountToday(int id) {
        Integer count = INSTANCE.exchangeReserveLogList.get(id);
        return count == null ? 0 : count;
    }
    
    public static synchronized Boolean canExchangeReserveToday(int id, int count) {
        return getExchangeReserveCountToday(id) < count;
    }
    
    public static synchronized void exchangeReserveToday(int id) {
        int count = getExchangeReserveCountToday(id) + 1;
        INSTANCE.exchangeReserveLogList.put(id, count);
        save();
    }
    
    public static synchronized Boolean canMemberPointExchangeBenefitToday(String benefitId) {
        return !INSTANCE.memberPointExchangeBenefitLogList.contains(benefitId);
    }
    
    public static synchronized void memberPointExchangeBenefitToday(String benefitId) {
        if (canMemberPointExchangeBenefitToday(benefitId)) {
            INSTANCE.memberPointExchangeBenefitLogList.add(benefitId);
            save();
        }
    }
    
    public static synchronized Boolean canAncientTreeToday(String cityCode) {
        return !INSTANCE.ancientTreeCityCodeList.contains(cityCode);
    }
    
    public static synchronized void ancientTreeToday(String cityCode) {
        Status stat = INSTANCE;
        if (!stat.ancientTreeCityCodeList.contains(cityCode)) {
            stat.ancientTreeCityCodeList.add(cityCode);
            save();
        }
    }
    
    private static int getFeedFriendCountToday(String id) {
        Integer count = INSTANCE.feedFriendLogList.get(id);
        return count == null ? 0 : count;
    }
    
    public static synchronized Boolean canFeedFriendToday(String id, int countLimit) {
        return !hasFlagToday(FLAG_FEED_FRIEND_ANIMAL_LIMIT) && getFeedFriendCountToday(id) < countLimit;
    }
    
    public static synchronized void feedFriendToday(String id) {
        int count = getFeedFriendCountToday(id) + 1;
        INSTANCE.feedFriendLogList.put(id, count);
        save();
    }
    
    private static int getVisitFriendCountToday(String id) {
        Integer count = INSTANCE.visitFriendLogList.get(id);
        return count == null ? 0 : count;
    }
    
    public static synchronized Boolean canVisitFriendToday(String id, int countLimit) {
        countLimit = Math.max(countLimit, 0);
        countLimit = Math.min(countLimit, 3);
        return !hasFlagToday("farm::visitFriendLimit::" + id) && getVisitFriendCountToday(id) < countLimit;
    }
    
    public static synchronized void visitFriendToday(String id) {
        int count = getVisitFriendCountToday(id) + 1;
        INSTANCE.visitFriendLogList.put(id, count);
        save();
    }
    
    public static synchronized void visitFriendToday(String id, int count) {
        INSTANCE.visitFriendLogList.put(id, count);
        save();
    }
    
    public static synchronized boolean canStallHelpToday(String id) {
        Integer count = INSTANCE.stallHelpedCountLogList.get(id);
        if (count == null) {
            return true;
        }
        return count < 3;
    }
    
    public static synchronized void stallHelpToday(String id, boolean limited) {
        Integer count = INSTANCE.stallHelpedCountLogList.get(id);
        if (count == null) {
            count = 0;
        }
        if (limited) {
            count = 3;
        }
        else {
            count += 1;
        }
        INSTANCE.stallHelpedCountLogList.put(id, count);
        save();
    }
    
    public static synchronized Boolean canUseAccelerateToolToday() {
        return !hasFlagToday("farm::useFarmToolLimit::" + "ACCELERATE" + "TOOL") && INSTANCE.useAccelerateToolCount < 8;
    }
    
    public static synchronized void useAccelerateToolToday() {
        INSTANCE.useAccelerateToolCount += 1;
        save();
    }
    
    public static synchronized Boolean canUseSpecialFoodToday() {
        AntFarm task = ModelTask.getModel(AntFarm.class);
        if (task == null) {
            return false;
        }
        int countLimit = task.getUseSpecialFoodCountLimit().getValue();
        if (countLimit == 0) {
            return true;
        }
        return INSTANCE.useSpecialFoodCount < countLimit;
    }
    
    public static synchronized void useSpecialFoodToday() {
        INSTANCE.useSpecialFoodCount += 1;
        save();
    }
    
    public static synchronized Boolean canOrchardShareP2PToday(String friendUserId) {
        return !hasFlagToday("orchard::shareP2PLimit") && !hasFlagToday("orchard::shareP2PLimit::" + friendUserId) && !INSTANCE.orchardShareP2PLogList.contains(friendUserId);
    }
    
    public static synchronized void orchardShareP2PToday(String friendUserId) {
        if (canOrchardShareP2PToday(friendUserId)) {
            INSTANCE.orchardShareP2PLogList.add(friendUserId);
            save();
        }
    }
    
    public static synchronized Boolean canStallShareP2PToday(String friendUserId) {
        return !hasFlagToday("stall::shareP2PLimit") && !hasFlagToday("stall::shareP2PLimit::" + friendUserId) && !INSTANCE.stallShareP2PLogList.contains(friendUserId);
    }
    
    public static synchronized void stallShareP2PToday(String friendUserId) {
        if (canStallShareP2PToday(friendUserId)) {
            INSTANCE.stallShareP2PLogList.add(friendUserId);
            save();
        }
    }
    
    public static synchronized boolean canDoubleToday() {
        AntForestV2 task = ModelTask.getModel(AntForestV2.class);
        if (task == null) {
            return false;
        }
        // 双击卡使用次数配置已停用（AntForestV2 里对应的 addField 被注释）→ 字段必为 null，
        // 按"当日不能再双击"处理，避免 NPE（与其它双击卡配置的停用语义保持一致）
        if (task.getDoubleCountLimit() == null || task.getDoubleCountLimit().getValue() == null) {
            return false;
        }
        return INSTANCE.doubleTimes < task.getDoubleCountLimit().getValue();
    }
    
    public static synchronized void DoubleToday() {
        INSTANCE.doubleTimes += 1;
        save();
    }
    
    /**
     * 绿色经营-是否可以收好友金币
     *
     * @return true是，false否
     */
    public static synchronized boolean canGreenFinancePointFriend() {
        return !INSTANCE.greenFinancePointFriend;
    }
    
    /**
     * 绿色经营-收好友金币完了
     */
    public static synchronized void greenFinancePointFriend() {
        Status stat = INSTANCE;
        if (!stat.greenFinancePointFriend) {
            stat.greenFinancePointFriend = true;
            save();
        }
    }
    
    /**
     * 绿色经营-是否可以做评级任务
     *
     * @return true是，false否
     */
    public static synchronized boolean canGreenFinancePrizesMap() {
        int week = TimeUtil.getWeekNumber(new Date());
        return !INSTANCE.greenFinancePrizesSet.contains(week);
    }
    
    /**
     * 绿色经营-评级任务完了
     */
    public static synchronized void greenFinancePrizesMap() {
        int week = TimeUtil.getWeekNumber(new Date());
        Status stat = INSTANCE;
        if (!stat.greenFinancePrizesSet.contains(week)) {
            stat.greenFinancePrizesSet.add(week);
            save();
        }
    }

    /**
     * 金豆-是否已领取任务奖励
     */
    public static synchronized boolean canGoldenBeansTaskReceive(String taskId) {
        return !INSTANCE.goldenBeansTaskReceivedSet.contains(taskId);
    }

    /**
     * 金豆-任务奖励已领取
     */
    public static synchronized void goldenBeansTaskReceived(String taskId) {
        Status stat = INSTANCE;
        if (!stat.goldenBeansTaskReceivedSet.contains(taskId)) {
            stat.goldenBeansTaskReceivedSet.add(taskId);
            save();
        }
    }
    
    /**
     * uid 与内存状态归属不一致（启动早期 uid 未就绪、切号）时，按当前 uid 重新加载。
     * <p>读写两条路径都要过：只在 save() 里挡，挡不住用错状态做判断；只在 hasFlagToday()
     * 里挡，挡不住把错状态写回真实账号。切号不丢内存标记——置位入口都即时落盘。
     *
     * @return 对齐后**内存状态的归属 uid**。落盘目标必须以它为准：切号可能正好落在
     *         「读到当前 uid」与「按该 uid 写盘」之间，拿新 uid 去写旧状态就成了
     *         「A 账号的标记写进 B 账号的文件」。
     */
    private static String ensureLoadedForCurrentUid() {
        String currentUid = UserIdMap.getCurrentUid();
        if (StringUtil.isEmpty(currentUid) || currentUid.equals(loadedUid)) {
            return loadedUid;
        }
        Log.system(TAG, "状态归属由[" + loadedUid + "]变为[" + currentUid + "]，按当前账号重新加载");
        load();
        return loadedUid;
    }

    public static synchronized Status load() {
        loadFailed = false;
        String currentUid = UserIdMap.getCurrentUid();
        try {
            if (StringUtil.isEmpty(currentUid)) {
                Log.i(TAG, "用户为空，状态加载失败");
                throw new RuntimeException("用户为空，状态加载失败");
            }
            // 先声明归属再动文件：本方法内部会写文件，若该链路回调到 hasFlagToday()，
            // loadedUid 未更新就会再进 load() 形成递归
            loadedUid = currentUid;
            File statusFile = FileUtil.getStatusFile(currentUid);
            if (statusFile.exists()) {
                String json = FileUtil.readFromFile(statusFile);
                JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(json);
                String formatted = JsonUtil.toFormatJsonString(INSTANCE);
                if (formatted != null && !formatted.equals(json)) {
                    // 回写只允许改格式、不许改数据
                    if (JsonUtil.isRewriteLossless(json, formatted)) {
                        Log.i(TAG, "重新格式化 status.json");
                        FileUtil.write2File(formatted, FileUtil.getStatusFile(currentUid));
                    } else {
                        Log.i(TAG, "status.json 加载结果与磁盘数据不一致，已跳过重新格式化");
                    }
                }
            }
            else {
                JsonUtil.copyMapper().updateValue(INSTANCE, new Status());
                Log.i(TAG, "初始化 status.json");
                FileUtil.write2File(JsonUtil.toFormatJsonString(INSTANCE), FileUtil.getStatusFile(currentUid));
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            // 只回落内存默认值、不写盘：写盘会把当天标记整份清空
            loadFailed = true;
            Log.record("状态文件解析失败，本次使用默认值（不写盘，原文件保留）");
            try {
                JsonUtil.copyMapper().updateValue(INSTANCE, new Status());
            }
            catch (JsonMappingException e) {
                Log.printStackTrace(TAG, e);
            }
        }
        if (INSTANCE.saveTime == 0) {
            INSTANCE.saveTime = System.currentTimeMillis();
        }
        return INSTANCE;
    }
    
    public static synchronized void unload() {
        try {
            JsonUtil.copyMapper().updateValue(INSTANCE, new Status());
        }
        catch (JsonMappingException e) {
            Log.printStackTrace(TAG, e);
        }
    }
    
    public static synchronized void save() {
        save(Calendar.getInstance());
    }
    
    public static synchronized void save(Calendar nowCalendar) {
        if (StringUtil.isEmpty(UserIdMap.getCurrentUid())) {
            Log.record("用户为空，状态保存失败");
            throw new RuntimeException("用户为空，状态保存失败");
        }
        // 落盘前先对齐归属：uid 未就绪时内存可能是空状态，直接写会清掉真实账号当天的 status.json。
        // 目标文件按「内存状态的归属」取，而不是再读一次当前 uid：切号可能正好落在两步之间，
        // 拿新 uid 去写旧状态就是「把 A 账号的标记写进 B 账号的文件」。
        String currentUid = ensureLoadedForCurrentUid();
        if (loadFailed) {
            // 内存只是默认值：写盘会把当天标记清空
            Log.debug(TAG + ", 状态文件加载失败，跳过保存");
            return;
        }
        if (updateDay(nowCalendar)) {
            Log.system(TAG, "重置 status.json");
        }
        else {
            // 每次落盘都记一行会淹没有效日志（实测约 68 行/天），降为由「抓包记录」开关控制的调试日志
            Log.debug(TAG + ", 保存 status.json");
        }
        // 注意：saveTime 不因失败回退。它同时是 updateDay()「是否跨天」的判据，回退会让同一天里
        // 每次保存都重新判定跨天并 unload()，把当天已置的标记一并清掉，比丢掉一次落盘更糟。
        INSTANCE.saveTime = System.currentTimeMillis();
        boolean saved;
        try {
            // write2File 内部把异常全吞了并只返回 boolean，原先丢弃返回值等于「写失败也算保存成功」
            saved = FileUtil.write2File(JsonUtil.toFormatJsonString(INSTANCE), FileUtil.getStatusFile(currentUid));
        }
        catch (Exception e) {
            notifySaveFailure(currentUid, "序列化失败", e);
            throw e;
        }
        if (saved) {
            if (saveFailureNotified) {
                saveFailureNotified = false;
                Log.system(TAG, "保存 status.json 恢复正常");
            }
        }
        else {
            notifySaveFailure(currentUid, "写入失败", null);
        }
    }

    /**
     * 落盘失败不再静默：内存里的标记保留（下一次 save 会整体补写，不会重复执行受标记守卫的动作），
     * 但必须留下可归因的线索——否则「内存说做过、磁盘没记录」的状态只有进程重启才暴露。
     */
    private static void notifySaveFailure(String currentUid, String reason, Throwable error) {
        if (saveFailureNotified) {
            return;
        }
        saveFailureNotified = true;
        String path = "未知路径";
        boolean permissionToastShown = false;
        try {
            File statusFile = FileUtil.getStatusFile(currentUid);
            path = statusFile.getAbsolutePath();
            // 「已存在但不可写」这一情形 write2File 已自行 Toast，这里不重复弹
            permissionToastShown = statusFile.exists() && !statusFile.canWrite();
        } catch (Throwable t) {
            // 路径取不到不影响后续提示（path 已有兜底文案），留痕即可
            Log.debug(TAG + " 取状态文件路径失败: " + t);
        }
        Log.system(TAG, "保存 status.json " + reason + "，当日进度可能未被持久化: " + path);
        if (error != null) {
            Log.printStackTrace(TAG, error);
        }
        if (permissionToastShown) {
            return;
        }
        try {
            Toast.show("状态保存失败，当日进度可能丢失", true);
        } catch (Throwable t) {
            Log.debug("Toast 提示失败(状态保存失败): " + t);
        }
    }
    
    public static synchronized Boolean updateDay(Calendar nowCalendar) {
        if (TimeUtil.isLessThanSecondOfDays(INSTANCE.saveTime, nowCalendar.getTimeInMillis())) {
            Status.unload();
            // 跨天：解禁超期的"自动拉黑"任务，给它们一次重试机会
            MessageUtil.sweepExpiredBlackList();
            // 跨天：释放原先预置拉黑的技术性不可自动化项，交给自动拉黑机制判定
            MessageUtil.sweepReleasedDefaults();
            return true;
        }
        else {
            return false;
        }
    }
    
    @Data
    private static class WaterFriendLog {
        String userId;
        int waterCount = 0;
        
        public WaterFriendLog() {
        }
        
        public WaterFriendLog(String id) {
            userId = id;
        }
    }
    
    @Data
    private static class ReserveLog {
        String projectId;
        int applyCount = 0;
        
        public ReserveLog() {
        }
        
        public ReserveLog(String id) {
            projectId = id;
        }
    }
    
    @Data
    private static class BeachLog {
        String cultivationCode;
        int applyCount = 0;
        
        public BeachLog() {
        }
        
        public BeachLog(String id) {
            cultivationCode = id;
        }
    }
    
    @Data
    private static class FeedFriendLog {
        String userId;
        int feedCount = 0;
        
        public FeedFriendLog() {
        }
        
        public FeedFriendLog(String id) {
            userId = id;
        }
    }
    
    @Data
    private static class VisitFriendLog {
        String userId;
        int visitCount = 0;
        
        public VisitFriendLog() {
        }
        
        public VisitFriendLog(String id) {
            userId = id;
        }
    }
    
    @Data
    private static class StallShareIdLog {
        String userId;
        String shareId;
        
        public StallShareIdLog() {
        }
        
        public StallShareIdLog(String uid, String sid) {
            userId = uid;
            shareId = sid;
        }
    }
    
    @Data
    private static class StallHelpedCountLog {
        String userId;
        int helpedCount = 0;
        int beHelpedCount = 0;
        
        public StallHelpedCountLog() {
        }
        
        public StallHelpedCountLog(String id) {
            userId = id;
        }
    }
    
}