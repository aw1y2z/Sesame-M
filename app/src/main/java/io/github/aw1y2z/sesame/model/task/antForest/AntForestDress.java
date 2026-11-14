package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.EmptyModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.TextModelField;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 装扮保护：记住当前装扮，被改动后自动恢复。
 */
public final class AntForestDress {

    private static final String TAG = AntForestV2.class.getSimpleName();

    /** 配置项 key 仍是 dress / dressDetailList / dressDetailListClear，不能改名，否则老用户的配置会丢。 */
    private static BooleanModelField dressEnabled;

    private static TextModelField dressDetailList;

    /** position 与 positionType 的双向映射，两份方向共用一个 map。 */
    private static final Map<String, String> DRESS_MAP = new HashMap<>();

    static {
        // position To positionType
        DRESS_MAP.put("tree__main", "treeMain");
        DRESS_MAP.put("bg__sky_0", "bgSky0");
        DRESS_MAP.put("bg__sky_cloud", "bgSkyCloud");
        DRESS_MAP.put("bg__ground_a", "bgGroundA");
        DRESS_MAP.put("bg__ground_b", "bgGroundB");
        DRESS_MAP.put("bg__ground_c", "bgGroundC");
        // positionType To position
        DRESS_MAP.put("treeMain", "tree__main");
        DRESS_MAP.put("bgSky0", "bg__sky_0");
        DRESS_MAP.put("bgSkyCloud", "bg__sky_cloud");
        DRESS_MAP.put("bgGroundA", "bg__ground_a");
        DRESS_MAP.put("bgGroundB", "bg__ground_b");
        DRESS_MAP.put("bgGroundC", "bg__ground_c");
    }

    private AntForestDress() {
    }

    /** 注册装扮相关配置项。必须在 getFields() 中被调用一次，否则字段为 null。 */
    public static void addFields(ModelFields modelFields) {
        modelFields.addField(dressEnabled = new BooleanModelField("dress", "装扮保护 | 开启", false));
        modelFields.addField(dressDetailList = new TextModelField("dressDetailList", "装扮保护 | " + "装扮信息", "").setDependsOn("dress"));
        modelFields.addField(new EmptyModelField("dressDetailListClear", "装扮保护 | 装扮信息清除", () -> dressDetailList.reset()).setDependsOn("dress"));
    }

    /** 开关注入后再判断，避免 getFields() 尚未执行时 NPE。 */
    public static boolean isEnabled() {
        return dressEnabled != null && Boolean.TRUE.equals(dressEnabled.getValue());
    }

    public static void run() {
        String detail = dressDetailList.getValue();
        if (detail.isEmpty()) {
            setDressDetail(getDressDetail().toString());
        } else {
            checkDressDetail(detail);
        }
    }

    private static JSONObject getDressDetail() {
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

    private static void setDressDetail(String dressDetail) {
        dressDetailList.setValue(dressDetail);
        if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
            Log.forest("装扮保护🔐皮肤保存,芝麻粒将为你持续保护!");
        }
    }

    private static void removeDressDetail(String position) {
        JSONObject jo = getDressDetail();
        jo.remove(position);
        setDressDetail(jo.toString());
    }

    private static void checkDressDetail(String dressDetail) {
        String[] positions = {"tree__main", "bg__sky_0", "bg__sky_cloud", "bg__ground_a", "bg__ground_b", "bg__ground_c"};
        try {
            boolean isDressExchanged = false;
            JSONObject jo = new JSONObject(dressDetail);
            for (String position : positions) {
                String batchType = "";
                if (jo.has(position)) {
                    batchType = jo.getString(position);
                }
                if (queryUserDressForBackpack(DRESS_MAP.get(position), batchType)) {
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

    private static Boolean queryUserDressForBackpack(String positionType, String batchType) {
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
                removeDressDetail(DRESS_MAP.get(positionType));
                Log.forest("装扮保护🔐皮肤过期,芝麻粒已为你恢复默认!");
            }
            return isTakeOff;
        } catch (Throwable th) {
            Log.err(TAG, "queryUserDressForBackpack err:", th);
        }
        return false;
    }

    private static Boolean wearDress(String dressType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.wearDress(dressType));
            return MessageUtil.checkResultCode(TAG, jo);
        } catch (Throwable th) {
            Log.err(TAG, "wearDress err:", th);
        }
        return false;
    }

    private static Boolean takeOffDress(String dressType, String position) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.takeOffDress(dressType, position));
            return MessageUtil.checkResultCode(TAG, jo);
        } catch (Throwable th) {
            Log.err(TAG, "takeOffDress err:", th);
        }
        return false;
    }
}
