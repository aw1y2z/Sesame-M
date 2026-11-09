package io.github.aw1y2z.sesame.model.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

import io.github.aw1y2z.sesame.entity.AlipayGoldenBeansMallItem;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.idMap.GoldenBeansMallItemMap;

/**
 * 金豆商城（芭芭农场金豆罐兑换页）的权益兑换。
 * <p>
 * 纯金豆判据：多数商品带 {@code moneyPrice}（0.01 元附加费），纯金豆商品没有该字段。
 * 所需罐数 = {@code price.cent} / 100，余额取主页 {@code jarInfo.jarCount}。
 * 兑换按 {@code itemInfoVOList[].skuModelList[]} 的 spuId + skuId 提交。
 */
public final class GoldenBeansMall {

    /** 候选同步的当日标记：可兑状态每天变化，无需每轮重扫 */
    private static final String FLAG_CATALOGUE = "goldenBeans::mallCatalogue";
    /** 每日兑换次数标记前缀（按商品名） */
    private static final String FLAG_EXCHANGE_PREFIX = "goldenBeans::mallExchange::";
    /** 单轮兑换次数封顶，防止勾选过多时连续下单 */
    private static final int MAX_EXCHANGE_PER_RUN = 5;
    private static final int PAGE_SIZE = 20;

    private GoldenBeansMall() {
    }

    /** 同步候选并兑换已勾选的商品；列表无条件同步，兑换受开关控制 */
    public static boolean run(int interval, Map<String, Integer> selected, boolean exchangeEnabled) {
        try {
            JSONArray items = fetchItems(interval);
            if (items == null) {
                return false;
            }
            int added = syncCatalogue(items);
            // 兑换关闭 / 未勾选权益都是用户配置的稳定状态，每轮相同；静默（列表已同步，供配置页勾选）
            if (!exchangeEnabled) {
                return true;
            }
            if (selected == null || selected.isEmpty()) {
                return true;
            }
            int exchanged = 0;
            for (int i = 0; i < items.length() && exchanged < MAX_EXCHANGE_PER_RUN; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String name = item.optString("spuName", "");
                if (name.isEmpty() || !selected.containsKey(name)) {
                    continue;
                }
                if (exchange(interval, item, selected.get(name))) {
                    exchanged++;
                }
            }
            Log.goldenBeans("金豆商城🛒本轮兑换[" + exchanged + "]项");
            return true;
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "mall err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
            return false;
        }
    }

    /** 拉取商城商品并摊平为兑换视图（每个 spu 只取第一个规格） */
    private static JSONArray fetchItems(int interval) throws Exception {
        GoldenBeansSupport.pause(interval);
        JSONObject jo = GoldenBeansSupport.parse(goldenbeansRpcCall.mallItems(0, PAGE_SIZE));
        if (!GoldenBeansSupport.ok(jo)) {
            Log.goldenBeans("金豆商城⚠️商品列表获取失败[" + GoldenBeansSupport.describe(jo) + "]");
            return null;
        }
        JSONArray itemList = jo.optJSONArray("itemInfoVOList");
        if (itemList == null) {
            Log.goldenBeans("金豆商城⚠️商品列表结构异常");
            return null;
        }
        JSONArray result = new JSONArray();
        for (int i = 0; i < itemList.length(); i++) {
            JSONObject spu = itemList.optJSONObject(i);
            if (spu == null) {
                continue;
            }
            JSONArray skuList = spu.optJSONArray("skuModelList");
            if (skuList == null || skuList.length() == 0) {
                continue;
            }
            JSONObject sku = skuList.optJSONObject(0);
            if (sku == null) {
                continue;
            }
            JSONObject price = sku.optJSONObject("price");
            JSONObject moneyPrice = sku.optJSONObject("moneyPrice");
            JSONObject view = new JSONObject();
            view.put("spuName", spu.optString("spuName", ""));
            view.put("spuId", spu.optString("spuId", ""));
            view.put("skuId", sku.optString("skuId", ""));
            view.put("skuName", sku.optString("skuName", ""));
            view.put("cost", price != null ? price.optInt("cent", -1) / 100 : -1);
            view.put("needMoney", moneyPrice != null && moneyPrice.optInt("cent", 0) > 0);
            view.put("dayLeft", sku.optInt("userDayLeftAmount", -1));
            result.put(view);
        }
        return result;
    }

    /** 登记可兑商品为配置候选；用 spuName 作键，避免每日次数变化导致勾选失效 */
    private static int syncCatalogue(JSONArray items) {
        GoldenBeansMallItemMap.load();
        int added = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("spuName", "");
            if (name.isEmpty() || GoldenBeansMallItemMap.get(name) != null) {
                continue;
            }
            GoldenBeansMallItemMap.add(name, name);
            added++;
        }
        if (added > 0 && GoldenBeansMallItemMap.save()) {
            AlipayGoldenBeansMallItem.clear();
            Status.flagToday(FLAG_CATALOGUE);
            Log.goldenBeans("同步权益🉑金豆商城可兑列表[新增" + added + "]");
        }
        return added;
    }

    /**
     * 兑换单个商品：纯金豆与当日次数过滤 → 每日限次 → 罐余额预检 → canBuy → 订单回查。
     *
     * @param dailyLimit 该商品每日兑换次数，0 表示不限
     */
    private static boolean exchange(int interval, JSONObject item, int dailyLimit) throws Exception {
        String name = item.optString("spuName", "");
        String spuId = item.optString("spuId", "");
        String skuId = item.optString("skuId", "");
        int cost = item.optInt("cost", -1);
        int dayLeft = item.optInt("dayLeft", -1);
        if (spuId.isEmpty() || skuId.isEmpty()) {
            return false;
        }
        if (item.optBoolean("needMoney", false)) {
            Log.goldenBeans("金豆商城⏭️跳过需附加人民币商品[" + name + "]");
            return false;
        }
        if (dayLeft == 0) {
            Log.goldenBeans("金豆商城⏭️今日已兑完[" + name + "]");
            return false;
        }
        if (dailyLimit > 0) {
            int done = Status.getIntFlagToday(FLAG_EXCHANGE_PREFIX + name);
            if (done >= dailyLimit) {
                Log.goldenBeans("金豆商城⏭️已达每日兑换次数[" + name + "#" + done + "/" + dailyLimit + "]");
                return false;
            }
        }
        if (cost > 0) {
            int jars = jarCount(interval);
            if (jars >= 0 && jars < cost) {
                Log.goldenBeans("金豆商城⏭️金豆罐不足[" + name + "]#需[" + cost + "罐]余[" + jars + "罐]");
                return false;
            }
        }
        try {
            int before = orderCount(interval);
            GoldenBeansSupport.pause(interval);
            JSONObject jo = GoldenBeansSupport.parse(goldenbeansRpcCall.mallExchange(spuId, skuId));
            if (!GoldenBeansSupport.ok(jo)) {
                Log.goldenBeans("金豆商城🎁兑换失败[" + name + "]#" + GoldenBeansSupport.describe(jo));
                return false;
            }
            // 成功体可能整体包在 data 下（本项目其它接口多为该形态），canBuy / orderNo 同层取值
            JSONObject data = jo.optJSONObject("data");
            boolean hasCanBuyAtRoot = jo.has("canBuy");
            boolean hasCanBuyInData = data != null && data.has("canBuy");
            boolean canBuy = hasCanBuyAtRoot ? jo.optBoolean("canBuy", false)
                    : (hasCanBuyInData && data.optBoolean("canBuy", false));
            if (!canBuy) {
                // 区分「服务端明确拒绝」与「响应里没有 canBuy 字段」，后者说明字段层级还有别的可能
                Log.goldenBeans("金豆商城⏭️服务端判定不可兑[" + name + "]#"
                        + (hasCanBuyAtRoot || hasCanBuyInData ? "canBuy=false" : "响应未返回canBuy字段"));
                return false;
            }
            // 订单列表只取首页 20 条：满页之后新订单不会让条数增加，原判定会把成功当失败，
            // 命中一次就再也不敢记次数。优先用响应里的订单号确认落单，没有才回退到前后条数对比
            String orderNo = jo.optString("orderNo", "");
            if (orderNo.isEmpty() && data != null) {
                orderNo = data.optString("orderNo", "");
            }
            if (orderNo.isEmpty()) {
                int after = orderCount(interval);
                if (after <= before) {
                    Log.record("金豆商城⚠️兑换未落单[" + name + "]#订单数未增加");
                    return false;
                }
            }
            Log.goldenBeans("金豆商城🎁兑换[" + name + "]#花费[" + cost + "罐]#订单[" + orderNo + "]");
            Status.setIntFlagToday(FLAG_EXCHANGE_PREFIX + name, Status.getIntFlagToday(FLAG_EXCHANGE_PREFIX + name) + 1);
            return true;
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "mall exchange err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
            return false;
        }
    }

    /** 商城订单条数；失败按 0 计，仅作兑换前后对比 */
    private static int orderCount(int interval) throws Exception {
        GoldenBeansSupport.pause(interval);
        JSONObject jo = GoldenBeansSupport.parse(goldenbeansRpcCall.mallOrders(1, 20));
        if (!GoldenBeansSupport.ok(jo)) {
            return 0;
        }
        JSONArray orders = jo.optJSONArray("orderInfos");
        if (orders == null) {
            // 与兑换接口一致：订单列表也可能包在 data 下
            JSONObject data = jo.optJSONObject("data");
            orders = data != null ? data.optJSONArray("orderInfos") : null;
        }
        return orders != null ? orders.length() : 0;
    }

    /** 当前持有的金豆罐数；查询失败返回 -1 表示未知，此时不拦兑换 */
    private static int jarCount(int interval) throws Exception {
        GoldenBeansSupport.pause(interval);
        JSONObject jo = GoldenBeansSupport.parse(goldenbeansRpcCall.home());
        if (!GoldenBeansSupport.ok(jo)) {
            return -1;
        }
        JSONObject jarInfo = GoldenBeansSupport.findObject(jo, "jarInfo");
        return jarInfo != null ? jarInfo.optInt("jarCount", -1) : -1;
    }
}