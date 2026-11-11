package io.github.aw1y2z.sesame.model.task.antFarm;

import org.json.JSONArray;

import java.util.List;

import io.github.aw1y2z.sesame.hook.ApplicationHook;

/**
 * 小鸡大厨（AI 厨房）RPC。
 *
 * <p>与庄园主体不是同一套场景：任务列表的 sceneCode/taskSceneCode 是
 * {@code ANTFARM_AI_KITCHEN_TASK}，做菜走 cookAiKitchen，和旧「小鸡厨房」的
 * enterKitchen/cook 无关。
 */
public class AntKitchenRpcCall {

    private AntKitchenRpcCall() {
    }

    /** 小鸡大厨主页：厨神分/精通分/丰富度、饲料库存与消耗、食材库存、菜品解锁进度 */
    public static String enterAiKitchen() {
        return ApplicationHook.requestString("com.alipay.antfarm.enterAiKitchen",
                "[{\"requestType\":\"RPC\",\"sceneCode\":\"ANTFARM\",\"source\":\"aikitchen_daily\"}]");
    }

    /** 小鸡大厨任务列表：sceneCode 与 taskSceneCode 都取 ANTFARM_AI_KITCHEN_TASK，不能拿庄园列表顶替 */
    public static String listTask() {
        return ApplicationHook.requestString("com.alipay.antfarm.listFarmTask",
                "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ANTFARM_AI_KITCHEN_TASK\",\"signSceneCode\":\"\","
                        + "\"source\":\"H5\",\"taskSceneCode\":\"ANTFARM_AI_KITCHEN_TASK\",\"topTask\":\"\"}]");
    }

    /**
     * 申报任务完成（抓包：看美食小知识）。
     *
     * <p>bizKey / taskType 都取任务列表里的 taskId，outBizNo 为 userId + 毫秒时间戳，
     * 与领奖不是同一个接口：先 finishTaskantfarm 把状态推到 FINISHED，再用
     * receiveTaskAwardantfarm 领奖。
     */
    public static String finishTask(String taskId, String userId) {
        String outBizNo = userId + System.currentTimeMillis();
        return ApplicationHook.requestString("com.alipay.antieptask.finishTaskantfarm",
                "[{\"bizKey\":\"" + taskId + "\",\"outBizNo\":\"" + outBizNo + "\",\"requestType\":\"NORMAL\","
                        + "\"sceneCode\":\"ANTFARM_AI_KITCHEN_TASK\",\"source\":\"aikitchen_daily\","
                        + "\"taskSceneCode\":\"ANTFARM_AI_KITCHEN_TASK\",\"taskType\":\"" + taskId + "\","
                        + "\"userId\":\"" + userId + "\"}]");
    }

    /** 做美食：每次固定 3 份食材，烹饪方式与评分由服务端判定，客户端无法指定 */
    public static String cook(String seasonId, List<String> ingredientIds) {
        JSONArray ids = new JSONArray();
        for (String ingredientId : ingredientIds) {
            ids.put(ingredientId);
        }
        return ApplicationHook.requestString("com.alipay.antfarm.cookAiKitchen",
                "[{\"ingredientIds\":" + ids + ",\"requestType\":\"RPC\",\"sceneCode\":\"ANTFARM\","
                        + "\"seasonId\":\"" + seasonId + "\",\"source\":\"aikitchen_daily\"}]");
    }
}
