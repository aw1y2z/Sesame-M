package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 蚂蚁森林 | 道具：连续用卡（双击卡/保护罩/倍卡/隐身卡）、背包查询、道具消费与赠送。
 * <p>状态里的 {@code usingProps} 仍留在主类（收取域也要读），本类按宿主引用访问。
 */
final class AntForestProp {

    private static final String TAG = AntForestProp.class.getSimpleName();

    private final AntForestV2 host;

    /** 双击卡"查一次用一次"的互斥，避免多线程重复消耗 */
    private final Object usePropLockObj = new Object();

    AntForestProp(AntForestV2 host) {
        this.host = host;
    }

    void continuousUseCardOptions() {
        //双击卡
        continuousUseAndExchangeCard("doubleClick", "SK20240805004754");
        //收能量倍卡
        continuousUseAndExchangeCard("robExpandCard", "");
        //保护罩：自动续用为独立开关，不受「连续兑换使用道具卡片」选项限制
        if (host.autoUseShieldCard.getValue()) {
            continuousUseAndExchangeCard("shield", "CR20230516000370");
        }
        //隐身卡
        continuousUseAndExchangeCard("stealthCard", "SK20230521000206");
        //炸弹卡
        //continuousUseAndExchangeCard("energyBombCard", "SK20250219006517");
    }

    private void continuousUseAndExchangeCard(String propGroupType, String exchangeProp) {
        try {
            if (propGroupType.equals("shield") || host.continuousUseCardOptions.contains(propGroupType)) {
                long continuousUseCardSecond = continuousUseCardCheak(propGroupType);
                // 未使用/已过期（==0）需新开；shield 且在用且剩余<阈值（>0）需续用延长；其余（-1 充足）不处理
                if (continuousUseCardSecond == 0
                        || (propGroupType.equals("shield") && continuousUseCardSecond > 0)) {
                    // 每日使用次数上限（0 = 不限制）：达到后当天不再消耗该卡片，也不再兑换补充
                    Integer optLimit = host.continuousUseCardOptions.get(propGroupType);
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
                            AntForestMall.exchangeBenefit(exchangeProp);
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
     * 该道具类型"当前没在使用（接口没出现 / 已过期）"时是否应再启用一张新的。
     * <p>只有保护罩需要：过期后必须再动一张，否则等于没保护。其余道具维持原行为
     * （隐身卡不用；双击卡/倍率卡按阈值接续，改动会额外消耗道具）。
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
                            return duringTime / (1000 * 60) < 60 * host.continuousUseShieldHour.getValue()
                                    ? duringTime : -1;

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
        return duringTime / (1000 * 60) < 60 * host.continuousUseShieldHour.getValue() ? duringTime : -1;
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
        if (Objects.equals(host.selfId, userId)) {
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
        if (host.doubleClickType == null || host.doubleClickType.getValue() == AntForestV2.UsePropType.CLOSE) {
            return false;
        }
        Long doubleClickEndTime = host.usingProps.get(AntForestV2.PropGroup.doubleClick.name());
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
                List<JSONObject> list = getPropGroup(forestPropVOList, AntForestV2.PropGroup.doubleClick.name());
                if (!list.isEmpty()) {
                    jo = list.get(0);
                }
                if (jo == null || !jo.has("recentExpireTime")) {
                    // 配置已停用则字段为 null，按"关闭"处理
                    if (host.doubleCardConstant != null && host.doubleCardConstant.getValue()) {
                        // 商店兑换 限时能量双击卡
                        if (AntForestMall.exchangeBenefit("SK20240805004754")) {
                            jo = getForestPropVO(getForestPropVOList(), "ENERGY_DOUBLE_CLICK_31DAYS");
                        } else if (AntForestMall.exchangeBenefit("CR20230516000363")) {
                            jo = getForestPropVO(getForestPropVOList(), "LIMIT_TIME_ENERGY_DOUBLE_CLICK");
                        }
                    }
                }
                if (jo == null) {
                    return;
                }
                if (!jo.has("recentExpireTime")
                        && (host.doubleClickType == null || host.doubleClickType.getValue() == AntForestV2.UsePropType.ONLY_LIMIT_TIME)) {
                    return;
                }
                // 使用能量双击卡
                if (consumeProp(jo)) {
                    Long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(jo.getJSONObject("propConfigVO").getLong("durationTime"));
                    host.usingProps.put(AntForestV2.PropGroup.doubleClick.name(), endTime);
                    Status.DoubleToday();
                } else {
                    host.energy.updateUsingPropsEndTime();
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "useDoubleCard err:", th);
        }
    }

    private boolean hasDoubleCardTime() {
        // 配置已停用（addField 被注释）→ 字段为 null，视为"不在使用时间范围"
        if (host.doubleCardTime == null) {
            return false;
        }
        long currentTimeMillis = System.currentTimeMillis();
        return TimeUtil.checkInTimeRange(currentTimeMillis, host.doubleCardTime.getValue());
    }

    /* 赠送道具 */
    void giveProp() {
        Set<String> set = host.whoYouWantToGiveTo.getValue();
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
    int forFriendCollectEnergy(String targetUserId, long bubbleId) {
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
                    host.totalHelpCollected += helped;
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

    static JSONArray getForestPropVOList() {
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
    static List<JSONObject> getPropGroup(JSONArray forestPropVOList, String propGroup) {
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

    /** 背包道具字段：propGroup/propType/holdsNum/propIdList[]/propConfigVO[propName] */
    private static JSONObject getForestPropVO(JSONArray forestPropVOList, String propType) {
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

    /** 使用背包道具（字段同 {@link #getForestPropVO}） */
    static Boolean consumeProp(JSONObject prop) {
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
}
