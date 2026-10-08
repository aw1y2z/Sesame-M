package io.github.aw1y2z.sesame.model.task.antMember;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

import io.github.aw1y2z.sesame.entity.AlipayWelfareFundTaskList;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.WelfareFundTaskListMap;

/**
 * 网商银行福利金：签到与任务闭环（报名 → 完成领奖）。
 * <p>权益兑换已移除：{@code member.benefits.queryItemsInMemberV2} 在模块线程里会被宿主判为
 * {@code XRiverNotFound}（同一方法在福利金页面内调用正常），列表与兑换链路不可用。
 */
public class WelfareFund {

    private static final String TAG = WelfareFund.class.getSimpleName();

    /** 当日签到标记：服务端已签后不再重复调用 */
    private static final String FLAG_SIGN = "member::welfareFundSign";

    /** 任务候选与列表同步时机：随每次任务列表请求顺带刷新，活动换任务时自动跟随 */
    private static final String FLAG_INIT_LIST = "BlackList::initWelfareFund";

    /**
     * 福利金系接口只回 {@code success:true}（无 desc / resultCode），
     * 而 {@link MessageUtil#checkResultCode} 要求 desc="处理成功"，会把成功响应全判为失败，
     * 故以 success / isSuccess 为准再回退通用判定；勿为此改全局 checkResultCode。
     */
    private static boolean ok(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        if (jo.optBoolean("success") || jo.optBoolean("isSuccess")) {
            return true;
        }
        return MessageUtil.checkResultCode(TAG, jo);
    }

    /** requestString 在请求体非法/宿主解析失败时会返回 null，统一在这里兜底 */
    private static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            Log.err(TAG, "响应解析失败:" + StringUtil.truncate(raw, 200), t);
            return null;
        }
    }

    public static void run(boolean sign, boolean task, boolean autoBlackList, Set<String> blackList) {
        if (sign) {
            signIn();
        }
        if (task) {
            runTasks(autoBlackList, blackList);
        }
    }

    private static void signIn() {
        if (Status.hasFlagToday(FLAG_SIGN)) {
            return;
        }
        try {
            JSONObject jo = parse(WelfareFundRpcCall.signConsult());
            if (!ok(jo)) {
                return;
            }
            JSONObject result = jo.optJSONObject("result");
            JSONObject info = result != null ? result.optJSONObject("todaySignInfo") : null;
            if (info == null) {
                Log.record("福利金签到：今日无签到信息");
                return;
            }
            int sent = optPoint(info, "signPrizeSentPoint");
            int expect = optPoint(info, "finalPoint");
            if (info.optBoolean("signApplyDone")) {
                Log.other("福利金📅签到" + (sent > 0 ? "#获得[" + sent + "积分]" : "")
                        + (expect > 0 ? "#本轮可得[" + expect + "积分]" : ""));
                Status.flagToday(FLAG_SIGN);
            } else {
                Log.record("福利金签到未生效");
            }
        } catch (Throwable t) {
            Log.err(TAG, "signIn err:", t);
        }
    }

    /** pointVO/finalPoint 等均为 {point, pointShowValue} 结构 */
    private static int optPoint(JSONObject jo, String key) {
        JSONObject po = jo.optJSONObject(key);
        return po != null ? po.optInt("point") : jo.optInt(key);
    }

    private static JSONArray queryTaskDetailList() {
        try {
            JSONObject jo = parse(WelfareFundRpcCall.taskQuery());
            if (!ok(jo)) {
                return null;
            }
            JSONObject result = jo.optJSONObject("result");
            return result != null ? result.optJSONArray("taskDetailList") : null;
        } catch (Throwable t) {
            Log.err(TAG, "queryTaskDetailList err:", t);
            return null;
        }
    }

    private static void runTasks(boolean autoBlackList, Set<String> blackList) {
        try {
            JSONArray list = queryTaskDetailList();
            if (list == null || list.length() == 0) {
                Log.record("福利金任务：今日无可做任务");
                return;
            }
            syncCandidates(list);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                // 任务标题在 taskExtProps.TASK_MORPHO_DETAIL（字符串化 JSON）里
                JSONObject detail = morphDetail(item);
                String title = detail.optString("title");
                String appletId = item.optString("taskId");
                if (appletId.isEmpty() || title.isEmpty()) {
                    continue;
                }
                if ("RECEIVE_SUCCESS".equalsIgnoreCase(item.optString("taskProcessStatus"))) {
                    continue;
                }
                if (blackList != null && blackList.contains(title)) {
                    Log.record("福利金任务⏭️跳过[" + title + "]#黑名单");
                    continue;
                }
                // NONE_SIGNUP 先报名再完成；SIGNUP_COMPLETE 直接领奖
                if ("NONE_SIGNUP".equalsIgnoreCase(item.optString("taskProcessStatus"))) {
                    triggerTask(appletId, "signup", title);
                    TimeUtil.sleep(500);
                }
                triggerTask(appletId, "send", title);
                TimeUtil.sleep(500);
            }
        } catch (Throwable t) {
            Log.err(TAG, "runTasks err:", t);
        }
    }

    /** 任务列表同步为黑名单候选；随每次列表请求刷新，活动任务轮换时不会让候选长期为空 */
    private static void syncCandidates(JSONArray list) {
        try {
            WelfareFundTaskListMap.load();
            int count = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String title = morphDetail(item).optString("title");
                if (title.isEmpty() || WelfareFundTaskListMap.get(title) != null) {
                    continue;
                }
                WelfareFundTaskListMap.add(title, title);
                count++;
            }
            if (count > 0) {
                WelfareFundTaskListMap.save();
                AlipayWelfareFundTaskList.clear();
                Log.record("同步任务🉑福利金任务列表[新增" + count + "]");
            }
        } catch (Throwable t) {
            Log.err(TAG, "syncCandidates err:", t);
        }
    }

    /** TASK_MORPHO_DETAIL 是字符串化 JSON，解析失败按空对象处理 */
    private static JSONObject morphDetail(JSONObject item) {
        JSONObject extProps = item.optJSONObject("taskExtProps");
        String raw = extProps != null ? extProps.optString("TASK_MORPHO_DETAIL") : "";
        if (raw.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void triggerTask(String appletId, String stageCode, String title) {
        try {
            JSONObject jo = parse(WelfareFundRpcCall.taskTrigger(appletId, stageCode));
            if (!ok(jo)) {
                MessageUtil.checkResultCodeAndMarkTaskBlackList("WelfareFundTaskList", title, jo);
                return;
            }
            JSONObject result = jo.optJSONObject("result");
            JSONObject order = result != null ? result.optJSONObject("campOrder") : null;
            String status = order != null ? order.optString("status") : "";
            // success:true 但 campOrder 非 SUCCESS 时不能算完成，否则该任务永远进不了黑名单生命周期
            if (!status.isEmpty() && !"SUCCESS".equalsIgnoreCase(status)) {
                MessageUtil.checkResultCodeAndMarkTaskBlackList("WelfareFundTaskList", title, jo);
                Log.record("福利金📋" + stageCode + "[" + title + "]#未完成[" + status + "]");
                return;
            }
            Log.other("福利金📋" + stageCode + "[" + title + "]" + (status.isEmpty() ? "" : "#" + status) + rewardText(result));
        } catch (Throwable t) {
            Log.err(TAG, "taskTrigger err:", t);
        }
    }

    /** 奖励按 prizeSendOrderList 的 budgetType/amount 汇总，字段以真机响应为准 */
    private static String rewardText(JSONObject result) {
        if (result == null) {
            return "";
        }
        JSONArray orders = result.optJSONArray("prizeSendOrderList");
        if (orders == null || orders.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < orders.length(); i++) {
            JSONObject o = orders.optJSONObject(i);
            if (o != null) {
                sb.append(o.optString("budgetType")).append("=").append(o.optString("amount")).append(" ");
            }
        }
        return sb.length() == 0 ? "" : "#奖励[" + sb.toString().trim() + "]";
    }
}