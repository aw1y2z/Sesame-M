package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Statistics;

/**
 * 1V1 能量挑战：自动开启、查询对局、领取奖励。
 * <p>只依赖 RPC 与统计，不引用配置字段与收取运行态。
 */
public final class AntForestPvp {

    private static final String TAG = AntForestV2.class.getSimpleName();

    private AntForestPvp() {
    }

    /**
     * 查询 1V1 状态，未开启则自动开启。
     *
     * @return true=当前可参与（已开启或本次开启成功）
     */
    public static boolean updateUserConfigEnergyPvp() {
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

    /** 查询 1V1 主页信息，有奖励顺手领取。 */
    public static void queryPvpHomeInfo() {
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
                    receiveRewards(jo);
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "queryPvpHomeInfo err:", th);
        }

    }

    /** 检查并领取 1V1 待领奖励。 */
    public static void receivePvpRewards() {
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
                        receiveRewards(jo);
                    }
                }
            }
        } catch (Throwable th) {
            Log.err(TAG, "receivePvpRewards err:", th);
        }
    }

    /** 两处领奖返回结构一致，抽出一份避免重复。调用点都在 try 块内，异常直接上抛。 */
    private static void receiveRewards(JSONObject jo) throws JSONException {
        if (!jo.has("receivedRewards")) {
            return;
        }
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
