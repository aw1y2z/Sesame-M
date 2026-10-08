package io.github.aw1y2z.sesame.model.task.antMember;

import io.github.aw1y2z.sesame.hook.ApplicationHook;

/**
 * 网商银行福利金接口。固定参数（appletId / playId / sceneCode）取自官方页面实际调用。
 */
public class WelfareFundRpcCall {

    /** 任务中心 appletId：列表查询的 appletId 与 taskTrigger 的 taskCenId 都用它 */
    private static final String TASK_CENTER_APPLET_ID = "AP1269301";

    /** 签到玩法 ID */
    private static final String SIGN_PLAY_ID = "PLAY100177545";

    /** 签到：官方 operation=signConsult，服务端同时完成当日签到 */
    public static String signConsult() {
        return sign("signConsult", null);
    }

    /** 签到日历（未来 N 天奖励） */
    public static String signCalendarQuery(int size) {
        return sign("signCalendarQuery", size);
    }

    private static String sign(String operation, Integer size) {
        StringBuilder args = new StringBuilder("[{\"channel\":\"miniApp\",\"needMultiple\":false,\"operation\":\"")
                .append(operation).append("\",\"playId\":\"").append(SIGN_PLAY_ID).append("\"");
        if (size != null) {
            args.append(",\"size\":").append(size);
        }
        return ApplicationHook.requestString("com.alipay.loanpromoweb.member.play.signinPlay", args.append("}]").toString());
    }

    /** 任务列表（含已完成，completedBottom 与官方一致） */
    public static String taskQuery() {
        return ApplicationHook.requestString("com.alipay.loanpromoweb.promo.task.taskQuery",
                "[{\"appletId\":\"" + TASK_CENTER_APPLET_ID + "\",\"completedBottom\":true}]");
    }

    /** 任务报名（stageCode=signup）与任务完成领奖（stageCode=send） */
    public static String taskTrigger(String appletId, String stageCode) {
        return ApplicationHook.requestString("com.alipay.loanpromoweb.promo.task.taskTrigger",
                "[{\"appletId\":\"" + appletId + "\",\"stageCode\":\"" + stageCode + "\",\"taskCenId\":\""
                        + TASK_CENTER_APPLET_ID + "\"}]");
    }
}
