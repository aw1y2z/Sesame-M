package io.github.aw1y2z.sesame.model.task.ancientTree;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.entity.AreaCode;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;

import java.util.Calendar;
import java.util.Collection;
import java.util.LinkedHashSet;

public class AncientTree extends ModelTask {
    private static final String TAG = AncientTree.class.getSimpleName();

    @Override
    public String getName() {
        return "古树";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.PROTECT;
    }

    private BooleanModelField ancientTreeOnlyWeek;
    private SelectModelField ancientTreeCityCodeList;

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(ancientTreeOnlyWeek = new BooleanModelField("ancientTreeOnlyWeek", "仅星期一、三、五运行保护古树",
                false));
        modelFields.addField(ancientTreeCityCodeList = new SelectModelField("ancientTreeCityCodeList", "古树区划代码列表",
                new LinkedHashSet<>(), AreaCode::getList));
        return modelFields;
    }

    @Override
    public Boolean check() {
        if (!TaskCommon.IS_ENERGY_TIME && TaskCommon.IS_AFTER_8AM) {
            if (!ancientTreeOnlyWeek.getValue()) {
                return true;
            }
            // 星期用系统常量判定：原先按 "星期一" 这类本地化文本比较，非中文系统下永远匹配不上，
            // 勾选后功能等于永久关闭
            int dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            return dayOfWeek == Calendar.MONDAY
                    || dayOfWeek == Calendar.WEDNESDAY
                    || dayOfWeek == Calendar.FRIDAY;
        } return false;
    }

    @Override
    public void run() {
        try {
            Log.record("开始检测古树保护"); ancientTree(ancientTreeCityCodeList.getValue());
        } catch (Throwable t) {
            Log.i(TAG, "start.run err:"); Log.printStackTrace(TAG, t);
        }
    }

    private static void ancientTree(Collection<String> ancientTreeCityCodeList) {
        try {
            for (String cityCode : ancientTreeCityCodeList) {
                if (!Status.canAncientTreeToday(cityCode))
                    continue; ancientTreeProtect(cityCode); Thread.sleep(1000L);
            }
        } catch (Throwable th) {
            Log.i(TAG, "ancientTree err:"); Log.printStackTrace(TAG, th);
        }
    }

    private static void ancientTreeProtect(String cityCode) {
        try {
            JSONObject jo = new JSONObject(AncientTreeRpcCall.homePage(cityCode));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            } JSONObject data = jo.optJSONObject("data"); JSONArray districtBriefInfoList = data == null ? null : data.optJSONArray("districtBriefInfoList"); if (districtBriefInfoList == null) {
                return;
            }
            for (int i = 0; i < districtBriefInfoList.length(); i++) {
                JSONObject districtBriefInfo = districtBriefInfoList.optJSONObject(i);
                if (districtBriefInfo == null)
                    continue;
                int userCanProtectTreeNum = districtBriefInfo.optInt("userCanProtectTreeNum", 0);
                if (userCanProtectTreeNum < 1)
                    continue; JSONObject districtInfo = districtBriefInfo.optJSONObject("districtInfo");
                if (districtInfo == null)
                    continue;
                String districtCode = districtInfo.optString("districtCode", ""); if (districtCode.isEmpty())
                    continue;
                districtDetail(districtCode);
                TimeUtil.sleep(1000L);
            } Status.ancientTreeToday(cityCode);
        } catch (Throwable th) {
            Log.i(TAG, "ancientTreeProtect err:"); Log.printStackTrace(TAG, th);
        }
    }

    private static void districtDetail(String districtCode) {
        try {
            JSONObject jo = new JSONObject(AncientTreeRpcCall.districtDetail(districtCode));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            } JSONObject data = jo.optJSONObject("data"); JSONArray ancientTreeList = data == null ? null : data.optJSONArray("ancientTreeList"); if (ancientTreeList == null) {
                return;
            } JSONObject districtInfo = data.optJSONObject("districtInfo");
            if (districtInfo == null) {
                return;
            }
            String cityCode = districtInfo.optString("cityCode", ""); String cityName = districtInfo.optString("cityName", "");
            String districtName = districtInfo.optString("districtName", "");
            for (int i = 0; i < ancientTreeList.length(); i++) {
                JSONObject ancientTreeItem = ancientTreeList.optJSONObject(i);
                if (ancientTreeItem == null)
                    continue;
                if (ancientTreeItem.optBoolean("hasProtected", false))
                    continue;
                JSONObject ancientTreeControlInfo = ancientTreeItem.optJSONObject("ancientTreeControlInfo");
                if (ancientTreeControlInfo == null)
                    continue;
                int quota = ancientTreeControlInfo.optInt("quota", 0);
                int useQuota = ancientTreeControlInfo.optInt("useQuota", 0); if (quota <= useQuota)
                    continue; String itemId = ancientTreeItem.optString("projectId", ""); if (itemId.isEmpty())
                    continue;
                JSONObject ancientTreeDetail = new JSONObject(AncientTreeRpcCall.projectDetail(itemId, cityCode));
                if (!MessageUtil.checkResultCode(TAG, ancientTreeDetail)) {
                    continue;
                } data = ancientTreeDetail.optJSONObject("data"); if (data != null && data.optBoolean("canProtect", false)) {
                    int currentEnergy = data.optInt("currentEnergy", 0);
                    JSONObject ancientTree = data.optJSONObject("ancientTree");
                    if (ancientTree == null)
                        continue;
                    String activityId = ancientTree.optString("activityId", "");
                    String projectId = ancientTree.optString("projectId", "");
                    JSONObject ancientTreeInfo = ancientTree.optJSONObject("ancientTreeInfo");
                    if (ancientTreeInfo == null)
                        continue;
                    String name = ancientTreeInfo.optString("name", ""); int age = ancientTreeInfo.optInt("age", 0);
                    int protectExpense = ancientTreeInfo.optInt("protectExpense", 0);
                    cityCode = ancientTreeInfo.optString("cityCode", cityCode); if (currentEnergy < protectExpense)
                        break; TimeUtil.sleep(200);
                    jo = new JSONObject(AncientTreeRpcCall.protect(activityId, projectId, cityCode));
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        Log.forest("保护古树🎐[" + cityName + "-" + districtName + "]#" + age + "年" + name + ",消耗能量" + protectExpense + "g");
                    }
                } TimeUtil.sleep(500L);
            }
        } catch (Throwable th) {
            Log.i(TAG, "districtDetail err:"); Log.printStackTrace(TAG, th);
        }
    }
}
