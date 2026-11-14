package io.github.aw1y2z.sesame.model.task.antForest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;
import io.github.aw1y2z.sesame.util.idMap.VitalityBenefitIdMap;

/**
 * 蚂蚁森林 | 活力值商店（商品清单与兑换）。
 * <p>清单按 5 个 labelType 分别拉取并用 {@code hasMore} 翻页，当日只完整拉一次；
 * 残缺清单不能把缺失的 skuId 判为「已下架」，否则一次网络抖动就会把配置里的兑换项整天拉黑。
 */
final class AntForestMall {

    private static final String TAG = AntForestMall.class.getSimpleName();

    /** 系统繁忙属临时故障：按次计数，用尽才当日停 */
    private static final int VITALITY_EXCHANGE_RETRY_MAX = 3;

    /** 活力值商店的分类：官方按这 5 个 labelType 分别拉取，少查一个列表就不全 */
    private static final String[] VITALITY_LABEL_TYPES = {"", "SC_ASSETS", "SKIN", "JEWELRY", "OTHER"};

    /** 单分类翻页上限：hasMore 异常一直为真时兜底 */
    private static final int VITALITY_ITEM_MAX_PAGES = 10;

    /** 活力值商店列表拉取失败的当日重试上限：服务端持续失败时避免每轮都重拉（单轮最多 5 分类 × 10 页） */
    private static final int VITALITY_SKU_RETRY_MAX = 3;

    /** 活力值商店列表拉取失败次数的当日计数 key */
    private static final String FLAG_VITALITY_SKU_RETRY = "forest::vitalitySkuListRetry";

    // skuId, sku
    private static final Map<String, JSONObject> skuInfo = new HashMap<>();

    /**
     * 最近一次清单拉取是否完整：有分类请求失败即为 false。
     * 不完整时不能把缺失的 skuId 判为「已下架」，否则一次网络抖动会把配置里的兑换项全部拉黑。
     */
    private static boolean skuListComplete = false;

    private AntForestMall() {
    }

    /**
     * 获取活力值商店所有商品信息：按 5 个 labelType 分别拉取并用响应里的 hasMore 翻页
     * （只查 SC_ASSETS 首页会漏掉大部分权益）。
     * <p>当日标记的判断放在方法内部，因为除了 vitalityExchangeBenefit() 外，
     * 能量雨卡兑换（AntForestGame#useEnergyRainCard）/兑换道具等路径也会直接调用它，
     * 那些路径同样需要"当日只拉一次"。
     *
     * @return 是否真的取到 SKU（取不到时留给下一轮重试，但当日重试次数有上限）
     */
    static boolean getAllSkuInfo() {
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
            // 进程重启后内存清单是空的：先载入磁盘上的上次清单，才能判断本次拉取有没有真的变化
            String uid = UserIdMap.getCurrentUid();
            if (VitalityBenefitIdMap.getMap().isEmpty()) {
                VitalityBenefitIdMap.load(uid);
            }
            Set<String> beforeSkus = new HashSet<>(VitalityBenefitIdMap.getMap().keySet());
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
            }
            VitalityBenefitIdMap.save(uid);
            // 只有清单真的变了才写日志：无变化时连调试日志都不写，避免每次启动都刷
            Set<String> addedSkus = new LinkedHashSet<>(VitalityBenefitIdMap.getMap().keySet());
            addedSkus.removeAll(beforeSkus);
            if (!addedSkus.isEmpty()) {
                Log.i("活力值商店列表：新增" + addedSkus.size() + "项，清单共" + VitalityBenefitIdMap.getMap().size() + "条");
            }
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

    private static void getSkuInfoBySpuId(String spuId) {
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
    private static boolean getSkuInfoByItemInfoVO(JSONObject spuItem) {
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

    /** 兑换活力值商品：spuId/skuId/skuName/exchangedCount/price[amount] */
    static Boolean exchangeBenefit(String skuId) {
        // 当日已判定「达上限 / 连续繁忙停兑」：直接跳过，否则每轮都会重新判定并重复打同一条日志
        if (Boolean.TRUE.equals(Status.hasFlagToday("forest::exchangeLimit::" + skuId))) {
            return false;
        }
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
                if (AntForestV2.ItemStatus.REACH_LIMIT.name().equals(itemStatus) || AntForestV2.ItemStatus.NO_ENOUGH_POINT.name().equals(itemStatus) || AntForestV2.ItemStatus.NO_ENOUGH_STOCK.name().equals(itemStatus)) {
                    Log.record("活力兑换🎐[" + skuName + "]停止:" + AntForestV2.ItemStatus.valueOf(itemStatus).nickName());
                    if (AntForestV2.ItemStatus.REACH_LIMIT.name().equals(itemStatus)) {
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

    static Boolean exchangeBenefit(String spuId, String skuId, String skuName) {
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
}
