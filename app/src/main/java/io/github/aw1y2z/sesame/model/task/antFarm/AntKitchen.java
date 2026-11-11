package io.github.aw1y2z.sesame.model.task.antFarm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAward;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.AntKitchenTaskListMap;

/**
 * 小鸡大厨（AI 厨房）执行逻辑。
 *
 * <p>开关放在庄园模型里（不单独成模组），庄园每次运行只调 {@link #execute} 一次，
 * 由这里负责进主页、领已完成的任务奖励、按食材组合做美食。
 *
 * <p>做美食一次固定消耗 3 份食材 + 饲料（feedCost），烹饪方式与评分全由服务端判定，
 * 客户端只能决定放哪三种食材；同一组合重复做只提升该道菜的历史最高分，
 * 首次做出才额外给丰富度分，故选材优先走没试过的组合。
 */
public class AntKitchen {

    private static final String TAG = "KITCHEN";

    /** 厨房任务场景码：任务列表与领奖都用它，不能拿庄园的 ANTFARM 顶替 */
    private static final String TASK_SCENE = "ANTFARM_AI_KITCHEN_TASK";

    /** 黑名单字段名：与庄园里的「小鸡大厨 | 黑名单列表」一致，供 MessageUtil 记账 */
    private static final String BLACKLIST_FIELD = "AntKitchenTaskList";

    /**
     * 做菜次数不设用户配置：一直做到"不能再做"为止（饲料不足 / 食材不足 3 种 / 服务端拒绝）。
     * <p>这个值只是异常情况下的死循环兜底（正常一次做菜必扣 3 份食材，库存见底自然停），
     * 按"食材最多几百份、每份 3 个一组"取一个远高于真实上限的数。
     */
    private static final int MAX_COOK_TIMES = 100;

    private AntKitchen() {
    }

    /**
     * @param autoCook       是否自动做美食
     * @param autoFinishTask 是否自动申报完成「浏览/签到」类任务
     * @param blackList      用户配置的任务黑名单（按标题），命中的任务不领也不申报
     * @param autoBlackList  是否自动拉黑：领奖失败且列表复核未领到时把任务写进黑名单
     */
    public static void execute(boolean autoCook, boolean autoFinishTask,
                               Set<String> blackList, boolean autoBlackList) {
        try {
            JSONObject state = loadState();
            if (state == null) {
                return;
            }
            Log.i(TAG, "小鸡大厨 厨神分" + state.optInt("chefScore")
                    + " 精通" + state.optInt("masteryScore")
                    + " 丰富" + state.optInt("richnessScore")
                    + " 饲料" + state.optInt("feedStock") + "g/" + state.optInt("feedCost") + "g"
                    + " 已解锁" + state.optInt("unlockedNormalDishCount") + "/" + state.optInt("totalNormalDishCount"));

            receiveTaskAwards(blackList, autoBlackList);

            if (autoFinishTask) {
                // 申报成功的任务会变 FINISHED，重拉列表紧接着领奖，任务奖励当轮就能到手
                finishTodoTasks(blackList, autoBlackList);
                receiveTaskAwards(blackList, autoBlackList);
            }

            if (autoCook) {
                // 领到的食材已进库存，做菜基线必须重拉，否则会按旧库存选材
                JSONObject latest = loadState();
                if (latest != null) {
                    state = latest;
                }
                cookLoop(state);
            }
        } catch (Throwable t) {
            Log.err(TAG, "execute err:", t);
        }
    }

    private static JSONObject loadState() {
        try {
            JSONObject jo = new JSONObject(AntKitchenRpcCall.enterAiKitchen());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return null;
            }
            return jo;
        } catch (Throwable t) {
            Log.err(TAG, "enterAiKitchen err:", t);
            return null;
        }
    }

    /**
     * 遍历 TODO 任务，逐个走统一申报流程（{@link TaskAttemptPolicy}）：
     * 先申报，完成与否以任务列表为准；400000040「不支持rpc调用」由统一流程代为伪申报；
     * 伪申报当天仍在待办则交自动黑名单；交易/充值类统一流程直接跳过并永久拉黑，绝不伪申报。
     * <p>申报只负责把 TODO 推到 FINISHED，领奖仍走 {@link #receiveTaskAwards}。
     */
    private static void finishTodoTasks(Set<String> blackList, boolean autoBlackList) {
        try {
            JSONObject jo = new JSONObject(AntKitchenRpcCall.listTask());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONArray taskList = jo.optJSONArray("farmTaskList");
            if (taskList == null) {
                return;
            }
            if (StringUtil.isEmpty(ApplicationHook.getUserId())) {
                Log.i(TAG, "小鸡大厨未取到 userId，跳过任务申报");
                return;
            }
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null || !"TODO".equals(task.optString("taskStatus", ""))) {
                    continue;
                }
                String taskId = task.optString("taskId", "");
                String title = task.optString("title", taskId);
                if (StringUtil.isEmpty(taskId) || (blackList != null && blackList.contains(title))) {
                    continue;
                }
                // 自动黑名单归属交给统一策略：关掉开关时 listField 传 null，策略只记账不落盘
                TaskAttemptPolicy.Site site = new TaskAttemptPolicy.Site(
                        autoBlackList ? BLACKLIST_FIELD : null, "小鸡大厨任务", taskId, TASK_SCENE,
                        (k) -> probeTaskStatus(taskId));
                TaskAttemptPolicy.handle("aikitchen::" + taskId, title, null,
                        () -> attemptFinishTask(taskId, title, autoBlackList), Log::farm, site);
                TimeUtil.sleep(800);
            }
        } catch (Throwable t) {
            Log.err(TAG, "finishTodoTasks err:", t);
        }
    }

    /** 完成上报：响应成功才算 DONE，其余交给统一策略分级（可重试的留待下一轮，400000040 走伪申报兜底） */
    private static TaskAttemptPolicy.Outcome attemptFinishTask(String taskId, String title, boolean autoBlackList) {
        try {
            String userId = ApplicationHook.getUserId();
            if (StringUtil.isEmpty(userId)) {
                return TaskAttemptPolicy.Outcome.UNABLE;
            }
            JSONObject jo = new JSONObject(AntKitchenRpcCall.finishTask(taskId, userId));
            if (autoBlackList) {
                // 统一判据：可重试/繁忙不拉黑，"不支持rpc调用"降级为连续确认
                MessageUtil.checkResultCodeAndMarkTaskBlackList(BLACKLIST_FIELD, title, jo);
            }
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.farm("小鸡大厨📝完成任务[" + title + "]");
                return TaskAttemptPolicy.Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return TaskAttemptPolicy.Outcome.RETRY;
            }
            if (MessageUtil.isUnsupportedRpc(jo)) {
                return TaskAttemptPolicy.Outcome.UNSUPPORTED;
            }
            return TaskAttemptPolicy.Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "finishTask err: " + taskId, t);
            return TaskAttemptPolicy.Outcome.RETRY;
        }
    }

    /** 任务状态机是 TODO → FINISHED(可领) → RECEIVED(已领)，只处理中间态 */
    private static void receiveTaskAwards(Set<String> blackList, boolean autoBlackList) {
        try {
            JSONObject jo = new JSONObject(AntKitchenRpcCall.listTask());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONArray taskList = jo.optJSONArray("farmTaskList");
            if (taskList == null) {
                return;
            }
            boolean changed = false;
            int finishedCount = 0;
            int receivedCount = 0;
            List<String> todoTitles = new ArrayList<>();
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String taskId = task.optString("taskId", "");
                String title = task.optString("title", taskId);
                String status = task.optString("taskStatus", "");
                if ("FINISHED".equals(status)) {
                    finishedCount++;
                } else if ("RECEIVED".equals(status)) {
                    receivedCount++;
                } else if (!StringUtil.isEmpty(title)) {
                    todoTitles.add(title);
                }
                // 候选项取自列表本身，配置页的黑名单下拉才能看到当天真实任务
                if (!StringUtil.isEmpty(title) && AntKitchenTaskListMap.get(title) == null) {
                    AntKitchenTaskListMap.add(title, title);
                    changed = true;
                }
                if (blackList != null && blackList.contains(title)) {
                    Log.record("小鸡大厨任务[" + title + "]在黑名单中，跳过");
                    continue;
                }
                int awardCount = task.optInt("canReceiveAwardCount", 0);
                if (!"FINISHED".equals(status) || awardCount <= 0 || StringUtil.isEmpty(taskId)) {
                    continue;
                }
                receiveTaskAward(taskId, awardCount, title, autoBlackList);
                TimeUtil.sleep(600);
            }
            Log.i(TAG, "小鸡大厨任务列表 " + taskList.length() + " 个：可领 " + finishedCount
                    + "、已领 " + receivedCount + "、未完成 " + todoTitles.size()
                    + (todoTitles.isEmpty() ? "" : "[" + String.join("、", todoTitles) + "]"));
            if (changed) {
                AntKitchenTaskListMap.save();
            }
        } catch (Throwable t) {
            Log.err(TAG, "receiveTaskAwards err:", t);
        }
    }

    private static void receiveTaskAward(String taskId, int awardCount, String title, boolean autoBlackList) {
        try {
            JSONObject jo = new JSONObject(
                    AntFarmRpcCall.receiveTaskAwardantfarm(awardCount, TASK_SCENE, taskId));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.farm("小鸡大厨🎖️领取[" + title + "]#获得[" + rewardText(jo.optString("returnData", "")) + "]");
                return;
            }
            // 领奖响应不可信：先按任务列表复核"已领到"，未确认才交自动拉黑（顺序由 TaskAward 固定）
            TaskAward.confirmReceivedOrBlackList("小鸡大厨🎖️任务",
                    AntKitchen::probeTaskStatus, taskId, title,
                    autoBlackList
                            ? () -> MessageUtil.checkResultCodeAndMarkTaskBlackList(BLACKLIST_FIELD, title, jo)
                            : null,
                    msg -> Log.farm(msg));
        } catch (Throwable t) {
            Log.err(TAG, "receiveTaskAward err: " + taskId, t);
        }
    }

    /** 领奖复核：重拉一次厨房任务列表，按 taskId 取最新状态（找不到视为已领） */
    private static TaskAttemptPolicy.ProbeResult probeTaskStatus(String taskId) {
        return TaskAttemptPolicy.probeByList(TAG, AntKitchenRpcCall::listTask, "farmTaskList",
                o -> MessageUtil.checkSuccess(TAG, o),
                t -> taskId.equals(t.optString("taskId", "")) ? t.optString("taskStatus", "") : null);
    }

    /** 奖励明细藏在 returnData 的多层转义 JSON 里（DO_PROVIDE_RIGHTS_NEW_V2 → 权益键 → {ingredientList}） */
    private static String rewardText(String returnData) {
        JSONArray list = findIngredientList(returnData);
        String text = ingredientText(list);
        return text.isEmpty() ? "随机风味食材" : text;
    }

    private static String ingredientText(JSONArray list) {
        if (list == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(item.optString("ingredientName", item.optString("ingredientId", "食材")))
                    .append("×").append(item.optInt("count", 1));
        }
        return sb.toString();
    }

    private static JSONArray findIngredientList(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONArray list = obj.optJSONArray("ingredientList");
            if (list != null) {
                return list;
            }
            // Android 内置 org.json 没有 keySet()，只能用 keys() 迭代
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                JSONArray found = findIngredientList(obj.opt(keys.next()));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node instanceof String) {
            String text = (String) node;
            if (text.length() > 1 && text.charAt(0) == '{') {
                try {
                    return findIngredientList(new JSONObject(text));
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 一直做菜到"做不动"为止：饲料不够、食材种类不足 3 种、服务端拒绝（{@link #cook} 返回 null）。
     * <p>不做次数限制——每次固定扣 3 份食材，库存见底自然停；{@link #MAX_COOK_TIMES} 与
     * "库存连续两轮不下降"只是防服务端计数不同步时的死循环。
     */
    private static void cookLoop(JSONObject state) {
        if (!state.optBoolean("canCook", true)) {
            Log.i(TAG, "小鸡大厨当前不可做美食（canCook=false）");
            return;
        }
        String seasonId = state.optString("seasonId", "");
        if (StringUtil.isEmpty(seasonId)) {
            Log.i(TAG, "小鸡大厨未取到赛季 id，跳过做菜");
            return;
        }
        int feedStock = state.optInt("feedStock", 0);
        int feedCost = state.optInt("feedCost", 0);
        Map<String, Integer> stock = parseStock(state.optJSONArray("ingredientList"));
        Set<String> tried = new HashSet<>();
        int cooked = 0;
        int notDecreased = 0;
        while (cooked < MAX_COOK_TIMES) {
            if (feedCost > 0 && feedStock < feedCost) {
                Log.i(TAG, "小鸡大厨饲料不足[" + feedStock + "g<" + feedCost + "g]，停止做菜");
                break;
            }
            List<String> ids = pickIngredients(stock, tried);
            if (ids == null) {
                Log.i(TAG, "小鸡大厨可做食材不足 3 种，停止做菜");
                break;
            }
            int before = totalCount(stock);
            JSONObject result = cook(seasonId, ids);
            if (result == null) {
                break;
            }
            cooked++;
            tried.add(comboKey(ids));
            // 库存与饲料以响应为准：本地扣减容易和服务端对不上
            feedStock = result.optInt("feedStock", feedStock);
            JSONArray left = result.optJSONArray("ingredientList");
            if (left != null) {
                stock = parseStock(left);
            }
            if (!result.optBoolean("canCook", true)) {
                Log.i(TAG, "小鸡大厨服务端返回 canCook=false，停止做菜");
                break;
            }
            // 服务端没扣食材时库存不降，继续做只会空转（也说明食材组合已被拒）
            notDecreased = totalCount(stock) >= before ? notDecreased + 1 : 0;
            if (notDecreased >= 2) {
                Log.i(TAG, "小鸡大厨食材库存未减少，停止做菜");
                break;
            }
            TimeUtil.sleep(1200);
        }
        if (cooked > 0) {
            Log.farm("小鸡大厨🍳本轮共做[" + cooked + "]道美食");
        } else {
            Log.i(TAG, "小鸡大厨本轮没有做出美食");
        }
    }

    private static int totalCount(Map<String, Integer> stock) {
        int sum = 0;
        for (Integer count : stock.values()) {
            sum += count == null ? 0 : count;
        }
        return sum;
    }

    private static JSONObject cook(String seasonId, List<String> ingredientIds) {
        try {
            JSONObject jo = new JSONObject(AntKitchenRpcCall.cook(seasonId, ingredientIds));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return null;
            }
            String dishName = jo.optString("dishName", "");
            String resultType = jo.optString("cookResultType", "");
            if (!"NORMAL".equals(resultType)) {
                // 炸锅不给分，但食材与饲料照扣，日志里要能看出来
                Log.farm("小鸡大厨💥" + resultType + "[" + dishName + "]#饲料剩" + jo.optInt("feedStock") + "g");
                return jo;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("小鸡大厨🥘制作[").append(dishName).append("]#")
                    .append(jo.optString("cookingMethod", "")).append(" ")
                    .append(jo.optInt("score")).append("分(").append(jo.optString("resultLevel", "")).append(")")
                    .append(" 厨神分+").append(jo.optInt("chefScoreDelta"))
                    .append(" 精通").append(jo.optInt("masteryScore"))
                    .append(" 丰富").append(jo.optInt("richnessScore"));
            if (jo.optBoolean("firstUnlock")) {
                sb.append(" 首次解锁🎉");
                JSONArray reward = jo.optJSONArray("unlockRewardIngredientList");
                String text = ingredientText(reward);
                if (!text.isEmpty()) {
                    sb.append("#解锁奖励[").append(text).append("]");
                }
            } else if (jo.optBoolean("highestScoreUpdated")) {
                sb.append(" 刷新最高分");
            }
            Log.farm(sb.toString());
            return jo;
        } catch (Throwable t) {
            Log.err(TAG, "cook err:", t);
            return null;
        }
    }

    /** 从库存挑 3 份食材：先试本轮没做过的组合，全试过就退化为库存最多的三种 */
    private static List<String> pickIngredients(Map<String, Integer> stock, Set<String> tried) {
        List<String> available = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : stock.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                available.add(entry.getKey());
            }
        }
        if (available.size() < 3) {
            return null;
        }
        // 库存多的先用，避免做到最后某种食材见底
        available.sort((a, b) -> Integer.compare(stock.get(b), stock.get(a)));
        for (int i = 0; i < available.size() - 2; i++) {
            for (int j = i + 1; j < available.size() - 1; j++) {
                for (int k = j + 1; k < available.size(); k++) {
                    List<String> combo = new ArrayList<>(3);
                    combo.add(available.get(i));
                    combo.add(available.get(j));
                    combo.add(available.get(k));
                    if (!tried.contains(comboKey(combo))) {
                        return combo;
                    }
                }
            }
        }
        return new ArrayList<>(available.subList(0, 3));
    }

    private static String comboKey(List<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        return String.join("|", sorted);
    }

    private static Map<String, Integer> parseStock(JSONArray list) {
        Map<String, Integer> stock = new LinkedHashMap<>();
        if (list == null) {
            return stock;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String ingredientId = item.optString("ingredientId", "");
            if (StringUtil.isEmpty(ingredientId)) {
                continue;
            }
            Integer old = stock.get(ingredientId);
            stock.put(ingredientId, (old == null ? 0 : old) + item.optInt("count", 0));
        }
        return stock;
    }
}
