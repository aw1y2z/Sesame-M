package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import io.github.aw1y2z.sesame.data.RuntimeInfo;
import io.github.aw1y2z.sesame.data.task.ModelTask.ChildModelTask;
import io.github.aw1y2z.sesame.entity.CollectEnergyEntity;
import io.github.aw1y2z.sesame.entity.FriendWatch;
import io.github.aw1y2z.sesame.entity.RpcEntity;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.model.normal.base.BaseModel;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.NotificationUtil;
import io.github.aw1y2z.sesame.util.RandomUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.XHelpers;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 蚂蚁森林 | 能量收取。
 * <p>承载首页/好友页查询、能量球收取（批量、双击、炸弹卡判定）、蹲点收取、
 * 礼盒与复活回赠、倍卡额外能量，以及医疗健康能量产出与收割。
 * <p>配置项与运行态由宿主 {@link AntForestV2} 持有（同包直读），本类只做流程编排。
 */
final class AntForestEnergy {

    private static final String TAG = AntForestEnergy.class.getSimpleName();

    private final AntForestV2 host;

    AntForestEnergy(AntForestV2 host) {
        this.host = host;
    }

    /** 能量总览：数量、活力值、榜单与今日得失 */
    void forestEnergyInfo() {
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
            Log.err(TAG, "forestEnergyInfo err:", th);
        }
    }

    /** 1V1 能量挑战收取 */
    void collectPkEnergy() {
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
                    if (Objects.equals(userId, host.selfId)) {
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

    /** 异步收取子任务结束回调：计数归零后唤醒宿主 run() 的等待 */
    void notifyMain() {
        if (host.taskCount.decrementAndGet() < 1) {
            synchronized (host) {
                host.notifyAll();
            }
        }
    }

    JSONObject querySelfHome() {
        JSONObject userHomeObject = null;
        try {
            long start = System.currentTimeMillis();
            userHomeObject = new JSONObject(AntForestRpcCall.queryHomePage());
            long end = System.currentTimeMillis();
            long serverTime = userHomeObject.getLong("now");
            // 对时偏差只反映本机时钟，喂给 offsetTimeMath 即可，不写日志
            AntForestV2.offsetTimeMath.nextInteger((int) ((start + end) / 2 - serverTime));
            //兼容组队模式
            if (AntForestTeam.isTeam(userHomeObject)) {
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
                        userHomeObject.put(key, value);
                    }
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return userHomeObject;
    }

    JSONObject queryFriendHome(String userId) {
        JSONObject userHomeObject = null;
        try {
            long start = System.currentTimeMillis();
            userHomeObject = new JSONObject(AntForestRpcCall.queryFriendHomePage(userId));
            long end = System.currentTimeMillis();
            long serverTime = userHomeObject.getLong("now");
            AntForestV2.offsetTimeMath.nextInteger((int) ((start + end) / 2 - serverTime));
            // 对时偏差喂给 offsetTimeMath 即可，不写日志
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return userHomeObject;
    }

    JSONObject collectSelfEnergy() {
        try {
            JSONObject selfHomeObject = querySelfHome();
            if (selfHomeObject != null) {
                if (AntForestGame.isWhackMoleClosed()) {
                    JSONObject propertiesObject = selfHomeObject.optJSONObject("properties");
                    if (propertiesObject != null) {
                        if (Objects.equals("Y", propertiesObject.optString("whackMole"))) {
                            if (WhackMole.closeWhackMole()) {
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
                    AntForestGame.checkAndHandleWhackMole();
                }
                return collectUserEnergy(UserIdMap.getCurrentUid(), selfHomeObject, "ordinary");
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return null;
    }

    JSONObject collectFriendEnergy(String userId, String getType) {
        if (AntForestV2.hasErrorWait) {
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

    JSONObject collectUserEnergy(String userId, JSONObject userHomeObject, String getType) {
        try {
            if (!MessageUtil.checkResultCode(TAG, userHomeObject)) {
                return userHomeObject;
            }

            long serverTime = userHomeObject.getLong("now");
            boolean isSelf = Objects.equals(userId, host.selfId);
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
                isCollectEnergy = host.collectEnergy.getValue() && !host.dontCollectMap.contains(userId);
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
                                    switch (AntForestV2.CollectStatus.valueOf(canbubble.getString("collectStatus"))) {
                                        case AVAILABLE:
                                            break;
                                        case WAITING:
                                            long produceTime = canbubble.getLong("produceTime");
                                            //如果保护罩不能覆盖能量成熟时间
                                            if (produceTime < joProp.getLong("endTime")) {
                                                break;
                                            }
                                            if (host.checkIntervalInt + host.checkIntervalInt / 2 > produceTime - serverTime) {
                                                if (host.hasChildTask(getBubbleTimerTid(userId, bubbleId))) {
                                                    break;
                                                }
                                                host.addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
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
                                        if (remainEnergy < host.CollectBombEnergyLimit.getValue()) {
                                            isBombCollectenergy = false;
                                        } else {
                                            Log.record("[" + userName + "]炸弹能量[" + remainEnergy + "g]>设定值[" + host.CollectBombEnergyLimit.getValue() + "g]");
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
                    switch (AntForestV2.CollectStatus.valueOf(bubble.getString("collectStatus"))) {
                        case AVAILABLE:
                            //用阈值判断单个能量球需收取情况
                            if (host.CollectSelfEnergyType.getValue() == AntForestV2.CollectSelfType.ALL) {
                                bubbleIdList.add(bubbleId);
                            } else if ((host.CollectSelfEnergyType.getValue() == AntForestV2.CollectSelfType.OVER_THRESHOLD) && (remainEnergy >= host.CollectSelfEnergyThreshold.getValue())) {
                                bubbleIdList.add(bubbleId);
                            } else if (((host.CollectSelfEnergyType.getValue() == AntForestV2.CollectSelfType.BELOW_THRESHOLD) && (remainEnergy <= host.CollectSelfEnergyThreshold.getValue()))) {
                                bubbleIdList.add(bubbleId);
                            }
                            break;
                        case WAITING:
                            long produceTime = bubble.getLong("produceTime");
                            if (host.checkIntervalInt + host.checkIntervalInt / 2 > produceTime - serverTime) {
                                if (host.hasChildTask(getBubbleTimerTid(userId, bubbleId))) {
                                    break;
                                }
                                if (host.CollectSelfEnergyType.getValue() == AntForestV2.CollectSelfType.ALL) {
                                    host.addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                    Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                } else if ((host.CollectSelfEnergyType.getValue() == AntForestV2.CollectSelfType.OVER_THRESHOLD) && (remainEnergy >= host.CollectSelfEnergyThreshold.getValue())) {
                                    host.addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                    Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                } else if (((host.CollectSelfEnergyType.getValue() == AntForestV2.CollectSelfType.BELOW_THRESHOLD) && (remainEnergy <= host.CollectSelfEnergyThreshold.getValue()))) {
                                    host.addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
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
                if (host.batchRobEnergy.getValue() && (host.CollectSelfEnergyType.getValue() == AntForestV2.CollectSelfType.ALL) && !AntForestTeam.isTeam(userHomeObject)) {
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

    void collectFriendsEnergy(List<String> idList, String getType) {
        try {
            if (AntForestV2.hasErrorWait) {
                return;
            }
            collectFriendsEnergy(new JSONObject(AntForestRpcCall.fillUserRobFlag(new JSONArray(idList).toString())), getType);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    void collectFriendsEnergy(JSONObject friendsObject, String getType) {
        if (AntForestV2.hasErrorWait) {
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
                    if (Objects.equals(userId, host.selfId)) {
                        continue;
                    }
                    JSONObject userHomeObject = null;
                    if (getType.equals("PK")) {
                        boolean collectEnergy = true;
                        if (!friendObject.optBoolean("canCollectEnergy")) {
                            long canCollectLaterTime = friendObject.getLong("canCollectLaterTime");
                            if (canCollectLaterTime <= 0 || (canCollectLaterTime - System.currentTimeMillis() > host.checkIntervalInt)) {
                                collectEnergy = false;
                            }
                        }
                        if (collectEnergy) {
                            userHomeObject = collectFriendEnergy(userId, getType);
                        }
                    } else {
                        if (host.collectEnergy.getValue() && !host.dontCollectMap.contains(userId)) {
                            boolean collectEnergy = true;
                            if (!friendObject.optBoolean("canCollectEnergy")) {
                                long canCollectLaterTime = friendObject.getLong("canCollectLaterTime");
                                if (canCollectLaterTime <= 0 || (canCollectLaterTime - System.currentTimeMillis() > host.checkIntervalInt)) {
                                    collectEnergy = false;
                                }
                            }
                            if (collectEnergy) {
                                userHomeObject = collectFriendEnergy(userId, getType);
                            } /* else {
                  Log.i("不收取[" + UserIdMap.getNameById(userId) + "], userId=" + userId);
              }*/
                        }

                        if (host.helpFriendCollectType.getValue() != AntForestV2.HelpFriendCollectType.NONE && friendObject.optBoolean("canProtectBubble") && !Status.hasFlagToday("forest::protectBubble")) {
                            boolean isHelpCollect = host.helpFriendCollectList.getValue().contains(userId);
                            if (host.helpFriendCollectType.getValue() != AntForestV2.HelpFriendCollectType.HELP) {
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
                        if (host.collectGiftBox.getValue() && friendObject.getBoolean("canCollectGiftBox")) {
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

    void collectGiftBox(JSONObject userHomeObject) {
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

    void protectFriendEnergy(JSONObject userHomeObject) {
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
                        if (fullEnergy < host.helpFriendCollectListLimit.getValue()) {
                            continue;
                        }
                        JSONObject joProtect = new JSONObject(AntForestRpcCall.protectBubble(userId));
                        if (!MessageUtil.checkResultCode(TAG, joProtect)) {
                            continue;
                        }
                        int vitalityAmount = joProtect.optInt("vitalityAmount", 0);

                        String str = "复活能量🧾[" + UserIdMap.getMaskName(userId) + "-" + fullEnergy + "g]" + (vitalityAmount > 0 ? "#活力值+" + vitalityAmount : "");
                        Log.forest(str);
                        AntForestV2.totalHelpCollected += fullEnergy;
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

    void collectEnergy(CollectEnergyEntity collectEnergyEntity, String username) {
        collectEnergy(collectEnergyEntity, false, username);
    }

    void collectEnergy(CollectEnergyEntity collectEnergyEntity, Boolean joinThread, String username) {
        if (AntForestV2.hasErrorWait) {
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
                synchronized (host.collectEnergyLockLimit) {
                    long sleep;
                    if (needDouble) {
                        collectEnergyEntity.unsetNeedDouble();
                        sleep = host.doubleCollectIntervalEntity.getInterval() - System.currentTimeMillis() + host.collectEnergyLockLimit.get();
                    } else if (needRetry) {
                        collectEnergyEntity.unsetNeedRetry();
                        sleep = host.retryIntervalInt - System.currentTimeMillis() + host.collectEnergyLockLimit.get();
                    } else {
                        sleep = host.collectIntervalEntity.getInterval() - System.currentTimeMillis() + host.collectEnergyLockLimit.get();
                    }
                    if (sleep > 0) {
                        TimeUtil.sleep(sleep);
                    }
                    startTime = System.currentTimeMillis();
                    host.collectEnergyLockLimit.setForce(startTime);
                }
                ApplicationHook.requestObject(rpcEntity, 0, 0);
                long spendTime = System.currentTimeMillis() - startTime;
                if (host.balanceNetworkDelay.getValue()) {
                    host.delayTimeMath.nextInteger((int) (spendTime / 3));
                }
                if (rpcEntity.getHasError()) {
                    String errorCode = (String) XHelpers.callMethod(rpcEntity.getResponseObject(), "getString", "error");
                    if (Objects.equals("1004", errorCode)) {
                        if (BaseModel.getWaitWhenException().getValue() > 0) {
                            long waitTime = System.currentTimeMillis() + BaseModel.getWaitWhenException().getValue();
                            RuntimeInfo.getInstance().put(RuntimeInfo.RuntimeInfoKey.ForestPauseTime, waitTime);
                            NotificationUtil.updateStatusText("异常");
                            Log.record("触发异常,等待至" + TimeUtil.getCommonDate(waitTime));
                            AntForestV2.hasErrorWait = true;
                            return;
                        }
                        TimeUtil.sleep(600 + RandomUtil.delay());
                    }
                    if (tryCount < host.tryCountInt) {
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
                    if (tryCount < host.tryCountInt) {
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
                        AntForestV2.totalCollected += collected;
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
                        AntForestV2.totalCollected += collected;
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
                    if (host.returnWater.getValue()) {
                        int returnCount = 0;
                        if (collected >= host.returnWater33.getValue()) {
                            returnCount = 33;
                        } else if (collected >= host.returnWater18.getValue()) {
                            returnCount = 18;
                        } else if (collected >= host.returnWater10.getValue()) {
                            returnCount = 10;
                        }
                        if (returnCount > 0) {
                            host.water.returnFriendWater(userId, bizNo, 1, returnCount);
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
        host.taskCount.incrementAndGet();
        if (joinThread) {
            runnable.run();
        } else {
            host.addChildTask(new ChildModelTask("CE|" + collectEnergyEntity.getUserId() + "|" + runnable.hashCode(), "CE", runnable));
        }
    }

    void updateUsingPropsEndTime() throws JSONException {
        JSONObject joHomePage = new JSONObject(AntForestRpcCall.queryHomePage());
        TimeUtil.sleep(100);
        updateUsingPropsEndTime(joHomePage);
    }

    void updateUsingPropsEndTime(JSONObject joHomePage) {
        try {
            JSONArray ja = joHomePage.getJSONArray("loginUserUsingPropNew");
            if (ja.length() == 0) {
                ja = joHomePage.getJSONArray("usingUserPropsNew");
            }
            for (int i = 0; i < ja.length(); i++) {
                JSONObject jo = ja.getJSONObject(i);
                AntForestV2.usingProps.put(jo.getString("propGroup"), jo.getLong("endTime"));
            }
            host.forestExtensions();
        } catch (Throwable th) {
            Log.err(TAG, "updateUsingPropsEndTime err:", th);
        }
    }

    /**
     * 收取倍卡额外能量（本轮收尾调用）。倍卡额度是本轮收取行为累积出来的，
     * 先收只能收到上一轮攒下的部分，本轮领到的要等到下一轮；且好友能量是异步收取，
     * 必须等子任务跑完再查，否则查到的仍是未累积的旧值。
     */
    void collectRobExpandEnergy() {
        if (!host.collectRobExpandEnergyEnable.getValue()) {
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
    void collectRobExpandEnergy(JSONObject joHomePage) {
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
                    if (jo == null || !AntForestV2.PropGroup.robExpandCard.name().equals(jo.optString("propGroup"))) {
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

    void collectRobExpandEnergy(String extInfo, String propId, String propType) {
        if (extInfo.isEmpty()) {
            return;
        }
        // 关闭"倍卡额外能量"开关：不收取额外能量
        if (!host.collectRobExpandEnergyEnable.getValue()) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(extInfo);
            double leftEnergy = jo.optDouble("leftEnergy", 0);
            int threshold = host.collectRobExpandEnergy.getValue();
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

    void collectRobExpandEnergy(String propId, String propType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.collectRobExpandEnergy(propId, propType));
            if ("SUCCESS".equalsIgnoreCase(jo.optString("resultCode"))) {
                int collectEnergy = jo.optInt("collectEnergy");
                Log.forest("额外能量🎄收取[" + collectEnergy + "g]");
                AntForestV2.totalCollected += collectEnergy;
                Statistics.addData(Statistics.DataType.COLLECTED, collectEnergy);
                return;
            }
            // 额外能量不足整数部分是服务端正常提示，非异常：按结果类记录，不打原始响应
            Log.forest("额外能量⏭️" + jo.optString("resultDesc"));
        } catch (Throwable th) {
            Log.err(TAG, "collectRobExpandEnergy err:", th);
        }
    }

    /** 医疗健康 / 电子小票：产出并收割绿色能量 */
    void queryForestEnergy(String scene) {
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

    JSONArray produceForestEnergy(String scene) {
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

    Boolean harvestForestEnergy(String scene, JSONArray bubbles) {
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
                AntForestV2.totalCollected += collectedEnergy;
                Statistics.addData(Statistics.DataType.COLLECTED, collectedEnergy);
                return true;
            }
        } catch (Throwable th) {
            Log.err(TAG, "harvestForestEnergy err:", th);
        }
        return false;
    }

    /** 能量球成熟前的蹲点收取任务：按服务端成熟时间提前 {@code advanceTimeInt} 触发 */
    class BubbleTimerTask extends ChildModelTask {

        private final String userId;

        private final long bubbleId;

        private final long produceTime;
        private final String userName;

        BubbleTimerTask(String ui, long bi, long pt, String un) {
            super(getBubbleTimerTid(ui, bi), pt - host.advanceTimeInt);
            userId = ui;
            bubbleId = bi;
            produceTime = pt;
            userName = un;
        }

        @Override
        public Runnable setRunnable() {
            return () -> {
                //String userName = UserIdMap.getMaskName(userId);
                int averageInteger = AntForestV2.offsetTimeMath.getAverageInteger();
                long readyTime = produceTime - host.advanceTimeInt + averageInteger - host.delayTimeMath.getAverageInteger() - System.currentTimeMillis() + 70;
                if (readyTime > 0) {
                    try {
                        Thread.sleep(readyTime);
                    } catch (InterruptedException e) {
                        Log.i("终止[" + userName + "]蹲点收取任务, 任务ID[" + getId() + "]");
                        return;
                    }
                }
                Log.record("执行蹲点收取[" + userName + "]" + "时差[" + averageInteger + "]ms" + "提前[" + host.advanceTimeInt + "]ms");
                collectEnergy(new CollectEnergyEntity(userId, null, AntForestRpcCall.getCollectEnergyRpcEntity(null, userId, bubbleId)), userName);
            };
        }
    }

    static String getBubbleTimerTid(String ui, long bi) {
        return "BT|" + ui + "|" + bi;
    }
}
