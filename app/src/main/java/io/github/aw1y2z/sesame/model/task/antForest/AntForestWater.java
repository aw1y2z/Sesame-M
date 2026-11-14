package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONObject;

import java.util.Map;

import io.github.aw1y2z.sesame.entity.KVNode;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 蚂蚁森林 | 好友浇水。
 * <p>按配置的好友与次数浇水，只补「配置次数 - 当日已浇」的差额，避免部分失败后被重复浇满；
 * 收取好友能量后的回赠水也走 {@link #returnFriendWater}，与主动浇水共用同一套次数记账。
 */
final class AntForestWater {

    private static final String TAG = AntForestWater.class.getSimpleName();

    private final AntForestV2 host;

    AntForestWater(AntForestV2 host) {
        this.host = host;
    }

    /** 是否浇得动水：动作非"不浇水"且选了好友 */
    boolean canWaterFriend() {
        int type = host.waterFriendType.getValue();
        if (type < 0 || type >= AntForestV2.WaterFriendType.waterEnergy.length
                || AntForestV2.WaterFriendType.waterEnergy[type] == 0) {
            return false;
        }
        Map<String, Integer> friendMap = host.waterFriendList.getValue();
        return friendMap != null && !friendMap.isEmpty();
    }

    void waterFriendEnergy() {
        String taskUid = UserIdMap.getCurrentUid();
        int waterEnergy = AntForestV2.WaterFriendType.waterEnergy[host.waterFriendType.getValue()];
        if (waterEnergy == 0) {
            return;
        }
        Map<String, Integer> friendMap = host.waterFriendList.getValue();
        for (Map.Entry<String, Integer> friendEntry : friendMap.entrySet()) {
            String uid = friendEntry.getKey();
            if (host.selfId.equals(uid)) {
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

    KVNode<Integer, Boolean> returnFriendWater(String userId, String bizNo, int count, int waterEnergy) {
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
                s = AntForestRpcCall.transferEnergy(userId, bizNo, energyId, host.waterFriendEnergySendChat.getValue() ? "Y" : "N");
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

    /** 浇水能量档位对应的能量类型 ID */
    static int getEnergyId(int waterEnergy) {
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

    /** 强制检查重复：当日对配置好友都已浇满时，重置浇水次数以便下一轮重新浇 */
    void doubleWaterFriendEnergy() {
        String taskUid = UserIdMap.getCurrentUid();
        int waterEnergy = AntForestV2.WaterFriendType.waterEnergy[host.waterFriendType.getValue()];
        if (waterEnergy == 0) {
            return;
        }
        boolean reSet = true;
        Map<String, Integer> friendMap = host.waterFriendList.getValue();
        for (Map.Entry<String, Integer> friendEntry : friendMap.entrySet()) {
            String uid = friendEntry.getKey();
            if (host.selfId.equals(uid)) {
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
                if (host.selfId.equals(uid)) {
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
}
