package io.github.aw1y2z.sesame.util;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.model.task.antMember.AntMember;
import io.github.aw1y2z.sesame.util.idMap.AutoBlackListMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

public class MessageUtil {
    private static final String TAG = MessageUtil.class.getSimpleName();
    private static final String UNKNOWN_TAG = "Unknown TAG";

    /** 自动拉黑：模糊错误需连续命中的确认次数 */
    private static final int BLACKLIST_CONFIRM_HITS = 3;
    /** 自动拉黑：命中计数的有效期（天），超过该窗口未再命中则重新计数 */
    private static final int BLACKLIST_CONFIRM_WINDOW_DAYS = 3;
    /** 自动拉黑：满该天数后自动解禁并重试一次 */
    private static final int BLACKLIST_RETRY_DAYS = 3;
    /** 自动拉黑：解禁重试的最大次数，超过后永久拉黑、不再自动解禁 */
    private static final int BLACKLIST_MAX_RETRY = 3;

    public static JSONObject newJSONObject(String str) {
        try {
            return new JSONObject(str);
        } catch (Throwable t) {
            Log.err(TAG, "newJSONObject err:", t);
        }
        return null;
    }

    /**
     * 服务端繁忙（102）日志降噪：同一模块（tag）累计打印满该次数后，后续 102 不再打印。
     * <p>这里**只降噪、不拦截**：请求照发——不做跳过、不做退避 sleep、也不写"当日停试"标记。
     */
    private static final int SERVER_BUSY_LOG_LIMIT = 3;
    /** tag → 已打印过的 102 次数（只增不减，进程重启即清零） */
    private static final Map<String, int[]> SERVER_BUSY_LOG_COUNT = new ConcurrentHashMap<>();

    /**
     * 是否服务端繁忙：`resultCode=102` 或文案为"服务器正在开小差"。
     * <p>这类错误是临时性的，既不该拉黑，也不该几秒内连续重试。
     * <p>对调用方的用途：命中后可在"本轮"内放弃后续同类调用（见 {@code AntFarm.listFarmTask} 的领奖）。
     */
    public static boolean isServerBusy(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        if ("102".equals(jo.optString("resultCode", "").trim())) {
            return true;
        }
        return jo.optString("memo", "").contains("服务器正在开小差");
    }

    /** 在多个文案字段里找关键字（各接口字段不统一，不能只扫 desc） */
    private static boolean anyFieldContains(JSONObject jo, String keyword) {
        for (String field : FAIL_TEXT_FIELDS) {
            if (jo.optString(field, "").contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打印失败应答。唯一的特殊处理是**服务端繁忙（102）的日志降噪**：同一 tag 累计打印满
     * {@link #SERVER_BUSY_LOG_LIMIT} 次后不再打印，避免限流期间刷屏。
     * <p>注意：这里不拦截、不退避、不跳过——请求该发照发，只是少写几行日志。
     */
    public static void printErrorMessage(String tag, JSONObject jo, String errorMessageField) {
        try {
            String memo = jo.getString(errorMessageField);
            if (isServerBusy(jo)) {
                if (!shouldLogServerBusy(tag)) {
                    return;
                }
                // 102 高频错误：只打一行 JSON（去掉文案行，避免每次都刷两条）
                Log.i(tag, jo.toString());
                return;
            }
            recordFailure(tag, memo);
            Log.i(memo, jo.toString());
        } catch (Throwable t) {
            Log.err(TAG, "printErrorMessage err:", t);
        }
    }

    /**
     * 失败应答属「结果」，按模块 tag 落到对应分类文件（与 {@code Log.forest/farm/...} 一样会同时写一份运行日志）。
     * <p>未登记的 tag 落到「其他记录」：宁可走兜底分类，也不要让结果停在没有模块归属的运行日志里。
     */
    private static void recordFailure(String tag, String memo) {
        String s = tag + " error:" + memo;
        switch (StringUtil.isEmpty(tag) ? UNKNOWN_TAG : tag) {
            case "AntForestV2":
            case "AntDodo":
            case "ProtectEcology":
            case "WhackMole":
            case "Privilege":
                Log.forest(s);
                break;
            case "AntFarm":
            case "AntStall":
            case "AntOrchard":
                Log.farm(s);
                break;
            case "金豆夺宝":
                Log.goldenBeans(s);
                break;
            default:
                Log.other(s);
        }
    }

    /**
     * 服务端繁忙（102）是否还应该打印日志：同一 tag 前 {@link #SERVER_BUSY_LOG_LIMIT} 次打印，
     * 之后静默（并在最后一次打印时提示"已开始静默"，免得看日志的人以为 102 消失了）。
     */
    private static boolean shouldLogServerBusy(String tag) {
        String key = StringUtil.isEmpty(tag) ? UNKNOWN_TAG : tag;
        int printed;
        synchronized (MessageUtil.class) {
            int[] state = SERVER_BUSY_LOG_COUNT.computeIfAbsent(key, k -> new int[]{0});
            if (state[0] >= SERVER_BUSY_LOG_LIMIT) {
                return false;
            }
            state[0]++;
            printed = state[0];
        }
        if (printed == SERVER_BUSY_LOG_LIMIT) {
            Log.i(key, "服务端繁忙🌧️本模块 102 已累计" + printed + "次，后续 102 不再打印日志（请求照常发送）");
        }
        return true;
    }

    public static Boolean checkMemo(JSONObject jo) {
        return checkMemo(UNKNOWN_TAG, jo);
    }

    public static Boolean checkMemo(String tag, JSONObject jo) {
        try {
            if (!"SUCCESS".equals(jo.optString("memo"))) {
                if (jo.has("memo")) {
                    printErrorMessage(tag, jo, "memo");
                } else {
                    Log.i(tag, jo.toString());
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "checkMemo err:", t);
        }
        return false;
    }

    public static Boolean checkResultCode(JSONObject jo) {
        return checkResultCode(UNKNOWN_TAG, jo);
    }

    public static Boolean checkResultCode(String tag, JSONObject jo) {
        try {
            /// 添加空值检查
            if (jo == null) {
                Log.i(tag, "JSON对象为空");
                return false;
            }

            if (jo.optBoolean("success") && jo.optString("desc").equals("处理成功")) {
                return true;
            }

            Object resultCode = jo.opt("resultCode");
            if (resultCode == null) {
                Log.i(tag, jo.toString());
                return false;
            }
            if (resultCode instanceof Integer) {
                return checkResultCodeInteger(tag, jo);
            } else if (resultCode instanceof String) {
                return checkResultCodeString(tag, jo);
            }
            Log.i(tag, jo.toString());
            return false;
        } catch (Throwable t) {
            Log.err(TAG, "checkResultCode err:", t);
        }
        return false;
    }

    public static Boolean checkResultCodeString(String tag, JSONObject jo) {
        try {
            String resultCode = jo.optString("resultCode");
            if (!resultCode.equalsIgnoreCase("SUCCESS") && !resultCode.equals("100")) {
                // CONFIG_NOT_EXIST 是正常响应（用户未配置权益），不打印错误日志
                if ("CONFIG_NOT_EXIST".equals(resultCode)) {
                    return false;
                }
                if (jo.has("resultDesc")) {
                    printErrorMessage(tag, jo, "resultDesc");
                } else if (jo.has("resultView")) {
                    printErrorMessage(tag, jo, "resultView");
                } else {
                    // 服务端繁忙（102）已有 printErrorMessage 统一降噪，这里跳过避免重复刷行
                    if (!isServerBusy(jo)) {
                        Log.i(tag, jo.toString());
                    }
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "checkResultCodeString err:", t);
        }
        return false;
    }

    public static Boolean checkResultCodeInteger(String tag, JSONObject jo) {
        try {
            int resultCode = jo.optInt("resultCode");
            if (resultCode != 200) {
                if (jo.has("resultMsg")) {
                    printErrorMessage(tag, jo, "resultMsg");
                } else {
                    Log.i(tag, jo.toString());
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "checkResultCodeInteger err:", t);
        }
        return false;
    }

    public static Boolean checkSuccess(JSONObject jo) {
        return checkSuccess(UNKNOWN_TAG, jo);
    }

    public static Boolean checkSuccess(String tag, JSONObject jo) {
        try {
            if (!jo.optBoolean("success") && !jo.optBoolean("isSuccess")) {
                if (jo.has("errorMsg")) {
                    printErrorMessage(tag, jo, "errorMsg");
                } else if (jo.has("errorMessage")) {
                    printErrorMessage(tag, jo, "errorMessage");
                } else if (jo.has("desc")) {
                    printErrorMessage(tag, jo, "desc");
                } else if (jo.has("resultDesc")) {
                    printErrorMessage(tag, jo, "resultDesc");
                } else if (jo.has("resultView")) {
                    printErrorMessage(tag, jo, "resultView");
                } else {
                    Log.i(tag, jo.toString());
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "checkSuccess err:", t);
        }
        return false;
    }

    /**
     * 是否为"可重试错误"（限流、远端异常、网络抖动等）。
     * <p>这类错误只是临时故障，一律不拉黑，避免把任务永久跳过。
     */
    public static boolean isRetryable(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        String code = jo.optString("code", "").trim();
        if (code.isEmpty()) {
            code = jo.optString("resultCode", "").trim();
        }
        if (code.isEmpty()) {
            code = jo.optString("errorCode", "").trim();
        }
        return jo.optBoolean("retryable", false)
                || jo.optBoolean("retriable", false)
                || "3000".equals(code)
                || "REMOTE_INVOKE_EXCEPTION".equals(code);
    }

    /**
     * 拉黑判定要扫的文案字段：各接口字段不统一（desc / resultDesc / resultView / memo / errorMsg …），
     * 原先只扫 desc，会把文案放在其它字段的失败漏掉（表现为"该拉黑却没拉黑、每天白试一次"）。
     */
    private static final String[] FAIL_TEXT_FIELDS = {
            "desc", "resultDesc", "resultView", "memo", "errorMsg", "errorMessage", "resultMsg"
    };

    /**
     * 拉黑目标表：listTitle → {ModelFieldsType, 列表中文名}。
     * <p>决定"拉黑写进哪个模块的哪个字段、日志里怎么称呼这个列表"。
     * <p>**新增任务列表只需在这里加一行**：判据绝大多数走默认
     * （desc 字段命中"不支持rpc调用"＝立即拉黑，其它字段命中＝连续确认），
     * 只有少数列表有额外错误码/文案判据时才需要在 {@link #checkResultCodeAndMarkTaskBlackList} 里加分支。
     */
    private static final Map<String, String[]> BLACKLIST_LIST_TARGETS = new LinkedHashMap<>();

    static {
        BLACKLIST_LIST_TARGETS.put("AntForestVitalityTaskList", new String[]{"AntForestV2", "蚂蚁森林活力值任务"});
        BLACKLIST_LIST_TARGETS.put("AntForestHuntTaskList", new String[]{"AntForestV2", "蚂蚁森林抽抽乐任务"});
        BLACKLIST_LIST_TARGETS.put("MonopolyTaskList", new String[]{"AntForestV2", "新版保护地任务"});
        BLACKLIST_LIST_TARGETS.put("AntFarmDoFarmTaskList", new String[]{"AntFarm", "庄园饲料任务"});
        BLACKLIST_LIST_TARGETS.put("AntFarmDrawMachineTaskList", new String[]{"AntFarm", "庄园装扮抽抽乐任务"});
        BLACKLIST_LIST_TARGETS.put("AntDodoTaskList", new String[]{"AntDodo", "神奇物种任务"});
        BLACKLIST_LIST_TARGETS.put("AntOceanAntiepTaskList", new String[]{"AntOcean", "神奇海洋普通任务"});
        BLACKLIST_LIST_TARGETS.put("AntOceanFishBlackList", new String[]{"AntOcean", "神奇海洋去摸鱼任务"});
        BLACKLIST_LIST_TARGETS.put("AntOrchardTaskList", new String[]{"AntOrchard", "农场肥料任务"});
        BLACKLIST_LIST_TARGETS.put("OrchardChouChouLeTaskList", new String[]{"AntOrchard", "农场抽抽乐任务"});
        BLACKLIST_LIST_TARGETS.put("WelfareFundTaskList", new String[]{"AntMember", "福利金任务"});
        BLACKLIST_LIST_TARGETS.put("GoldenBeansTaskList", new String[]{"goldenbeans", "金豆夺宝任务"});
        BLACKLIST_LIST_TARGETS.put("AntStallTaskList", new String[]{"AntStall", "新村任务"});
        BLACKLIST_LIST_TARGETS.put("AntSportsTaskList", new String[]{"AntSports", "运动任务"});
        BLACKLIST_LIST_TARGETS.put("AntMemberTaskList", new String[]{"AntMember", "会员任务"});
        BLACKLIST_LIST_TARGETS.put("MemberCreditSesameTaskList", new String[]{"AntMember", "会员芝麻信用任务芝麻粒"});
    }

    /**
     * 按列表字段名反查拉黑目标 {模块名, 列表中文名}；未登记的列表返回 null。
     * <p>供模块外的通用逻辑（如 {@code TaskAttemptPolicy}）在判定"该任务做不了"时按规则记账。
     */
    public static String[] autoBlackListTarget(String listField) {
        return BLACKLIST_LIST_TARGETS.get(listField);
    }

    /**
     * 各任务列表的白名单：键为 listTitle（字段名，与 {@link #BLACKLIST_LIST_TARGETS} 一致），
     * 值为不应被「自动拉黑」机制添加的任务标题集合。
     * <p>只用于拦截自动拉黑入口（{@link #MarkTaskBlackList}），<b>绝不删除用户手动加入黑名单的条目</b>。
     * 由各模块的 {@link #syncTaskBlackList} 在初始化时登记。
     */
    private static final Map<String, Set<String>> TASK_WHITE_LIST = new HashMap<>();

    /** 400000040「不支持rpc调用」；它不等于任务做不了，见另一种实现方案。 */
    public static final String CODE_UNSUPPORTED_RPC = "400000040";

    /** 失败响应是否命中"接口不支持调用"（按错误码，不按文案）。 */
    public static boolean isUnsupportedRpc(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        return CODE_UNSUPPORTED_RPC.equals(jo.optString("code", "").trim())
                || CODE_UNSUPPORTED_RPC.equals(jo.optString("errorCode", "").trim());
    }

    public static void checkResultCodeAndMarkTaskBlackList(String listTitle, String taskTitle, JSONObject jo) {
        try {
            if (jo == null) {
                Log.i(listTitle, "JSON对象为空");
                return;
            }
            // 可重试错误（限流、远端异常、网络抖动）一律不拉黑，对全部任务列生效
            if (isRetryable(jo)) {
                return;
            }
            // 关键字判定：desc 命中沿用原有"立即拉黑"语义；其它字段命中走"连续确认"（字段不统一，
            // 放宽判定范围必须更保守，避免一次误判就把任务停掉 3 天）
            boolean strongHit = false;
            boolean weakHit = false;
            for (String field : FAIL_TEXT_FIELDS) {
                String text = jo.optString(field, "");
                if (text.isEmpty() || !(text.contains("不支持rpc调用") || text.contains("不支持RPC调用"))) {
                    continue;
                }
                if ("desc".equals(field)) {
                    strongHit = true;
                } else {
                    weakHit = true;
                }
            }

            String[] target = BLACKLIST_LIST_TARGETS.get(listTitle);
            if (target == null) {
                // 未登记的列表不做拉黑（原先 switch 无匹配分支时也是什么都不做）
                Log.i(listTitle, "未登记拉黑目标，跳过");
                return;
            }

            // 判据：desc 命中"不支持rpc调用"＝立即拉黑；其它字段命中＝只作为"连续命中确认"的依据
            // （字段不统一，放宽判定范围必须更保守，避免一次误判就把任务停掉 3 天）
            // 例外：400000040（同一文案的规范化错误码）**不等于任务做不了**——2026-09-22 实测庄园抽抽乐/
            // 芭芭农场/金豆乐园都能用 com.alipay.antfarm.doFarmTask 做成，且响应可能撒谎（回 102 但已生效）。
            // 据它立即拉黑会把能做的任务永久拉黑 ⇒ 降级为"连续确认"，给另一种实现方案与列表核对留出机会
            boolean unsupportedRpc = isUnsupportedRpc(jo);
            boolean canAddBlackList = strongHit && !unsupportedRpc;
            boolean needConfirm = (weakHit && !strongHit) || unsupportedRpc;
            // 少数列表有自己的额外判据（服务端错误码、特有文案），其余列表走上面的默认判据
            switch (listTitle) {
                // 运动任务：错误码/文案指明任务 id 非法时可直接拉黑
                case "AntSportsTaskList":
                    if (jo.has("errorCode") && jo.optString("errorCode").contains("TASK_ID_INVALID")) {
                        // {"ariverRpcTraceId":"...","errorCode":"TASK_ID_INVALID","errorMsg":"海豚任务id非法","retryable":false,"success":false}
                        canAddBlackList = true;
                    }
                    if (jo.has("errorMsg") && jo.optString("errorMsg").contains("海豚活动触发不可重试错误")) {
                        canAddBlackList = true;
                    }
                    needConfirm = weakHit;
                    break;

                // 农场肥料任务：文案模糊（可能只是活动当天未配置）也算待确认
                case "AntOrchardTaskList":
                    needConfirm = weakHit || anyFieldContains(jo, "任务全局配置不存在");
                    break;

                // 会员芝麻信用任务：另有三种模糊文案同样走连续确认
                case "MemberCreditSesameTaskList":
                    needConfirm = weakHit
                            || anyFieldContains(jo, "不是有效的入参")
                            || anyFieldContains(jo, "存在进行中的生活记录")
                            || anyFieldContains(jo, "生活记录模板不存在");
                    break;

                // 福利金任务：事件规则任务被 10000005「不允许完成事件规则任务」拒绝，文案里没有
                // 「不支持rpc调用」关键字，须按错误码单独接入连续确认，不能靠 strongHit
                case "WelfareFundTaskList":
                    needConfirm = weakHit
                            || "10000005".equals(jo.optString("errorCode", "").trim())
                            || anyFieldContains(jo, "不允许完成事件规则任务");
                    break;

                // 金豆夺宝任务：错误码/文案（code 或 resultCode 或 errorCode + desc/resultDesc/memo）
                // 明确不可恢复时可直接拉黑（可重试错误已在入口统一拦截）
                case "GoldenBeansTaskList": {
                    String code = jo.optString("code", "").trim();
                    if (code.isEmpty()) {
                        code = jo.optString("resultCode", "").trim();
                    }
                    if (code.isEmpty()) {
                        code = jo.optString("errorCode", "").trim();
                    }
                    String message = jo.optString("desc", "");
                    if (message.isEmpty()) {
                        message = jo.optString("resultDesc", "");
                    }
                    if (message.isEmpty()) {
                        message = jo.optString("memo", "");
                    }
                    // 任务Id非法、入参非法等不可恢复错误码可直接拉黑；400000040「不支持rpc调用」不在此列
                    // （金豆已有另一种实现方案 doFarmTask + 列表核对，见 GoldenBeansTasks），降级为连续确认
                    boolean invalid = code.contains("20020012")
                            || code.contains("TASK_ID_INVALID")
                            || code.contains("ILLEGAL_ARGUMENT");
                    if (invalid) {
                        canAddBlackList = true;
                    }
                    needConfirm = needConfirm || message.contains("任务全局配置不存在");
                    break;
                }

                default:
                    // 其余列表（森林活力值/抽抽乐、庄园饲料/装扮抽抽乐、物种、海洋普通/摸鱼、新村、会员）
                    // 都只用默认判据，无需分支
                    break;
            }

            if (canAddBlackList) {
                MarkTaskBlackList(target[0], listTitle, target[1], taskTitle);
            } else if (needConfirm) {
                MarkTaskBlackListConfirm(target[0], listTitle, target[1], taskTitle);
            }
        } catch (Throwable t) {
            Log.err(TAG, "checkSuccess err:", t);
        }
    }

    public static void MarkTaskBlackList(String ModelFieldsType, String listTitle, String TaskListName, String taskTitle) {
        ConfigV2 config = ConfigV2.INSTANCE;
        ModelFields TaskModelFields = config.getModelFieldsMap().get(ModelFieldsType);
        if (TaskModelFields == null) {
            // BLACKLIST_LIST_TARGETS 的第一项必须是模型类名，写错时这里要能看到原因而不是 NPE
            Log.record("添加" + TaskListName + "黑名单失败：" + taskTitle + "#未找到模块[" + ModelFieldsType + "]");
            return;
        }
        SelectModelField TaskSelectModelField = (SelectModelField) TaskModelFields.get(listTitle);
        if (TaskSelectModelField == null) {
            Log.record("添加" + TaskListName + "黑名单失败：" + taskTitle);
            return;
        }
        // 白名单拦截：白名单任务只由"用户手动"决定是否拉黑，自动拉黑机制不插手
        Set<String> white = TASK_WHITE_LIST.get(listTitle);
        if (white != null && white.contains(taskTitle)) {
            Log.record("[" + TaskListName + "]任务[" + taskTitle + "]在白名单中，跳过自动拉黑");
            return;
        }
        if (!TaskSelectModelField.contains(taskTitle)) {
            TaskSelectModelField.add(taskTitle, 0); // 数组类型忽略count，传0
        }
        if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
            Log.record("自动拉黑🏴在[" + TaskListName + "]中添加[" + taskTitle + "]黑名单:" + TaskSelectModelField.getValue());
            // 记录拉黑日期，供"超期自动解禁重试"使用（只记自动项，用户手动加的不会被解禁）
            recordAutoBlack(ModelFieldsType, listTitle, taskTitle);
        } else {
            Log.record("添加" + TaskListName + "黑名单失败：" + taskTitle);
        }
    }

    /**
     * 自动拉黑（需连续命中确认）：用于错误文案模糊、可能只是临时状态的任务。
     * <p>连续命中 {@link #BLACKLIST_CONFIRM_HITS} 次才真正拉黑；未达标时只记录命中并打日志，
     * 避免一次性的临时故障（活动当天未配置等）被永久跳过。
     */
    public static void MarkTaskBlackListConfirm(String ModelFieldsType, String listTitle, String TaskListName, String taskTitle) {
        try {
            String key = autoBlackKey(ModelFieldsType, listTitle, taskTitle);
            // 必须先确保已从磁盘载入：AutoBlackListMap 是懒加载的，新进程里不调 ensureLoaded
            // 会读不到历史记录，命中次数永远从 0 起算（表现为始终"第1/3次命中"，永远拉不上）
            AutoBlackListMap.ensureLoaded();
            AutoBlackRecord record = AutoBlackRecord.parse(AutoBlackListMap.get(key));
            long today = todayIndex();
            if (record == null || today - record.lastDay > BLACKLIST_CONFIRM_WINDOW_DAYS) {
                record = new AutoBlackRecord();
            }
            if (record.hits + 1 < BLACKLIST_CONFIRM_HITS) {
                record.hits = record.hits + 1;
                record.lastDay = today;
                record.blackDay = 0L;
                AutoBlackListMap.put(key, record.format());
                AutoBlackListMap.save();
                Log.record("自动拉黑🕵️[" + TaskListName + "][" + taskTitle + "]可疑错误第"
                        + record.hits + "/" + BLACKLIST_CONFIRM_HITS + "次命中，暂不拉黑");
                return;
            }
            MarkTaskBlackList(ModelFieldsType, listTitle, TaskListName, taskTitle);
        } catch (Throwable t) {
            Log.err(TAG, "MarkTaskBlackListConfirm err:", t);
        }
    }

    /**
     * 解禁超期的"自动拉黑"任务：自动拉黑满 {@link #BLACKLIST_RETRY_DAYS} 天后移出黑名单、重新尝试一次；
     * 若再次失败会重新拉黑并重新计时。
     * <p>只处理模块自动加入的项，用户手动加入的黑名单不受影响。
     */
    public static void sweepExpiredBlackList() {
        try {
            AutoBlackListMap.load();
            if (AutoBlackListMap.getMap().isEmpty()) {
                return;
            }
            long today = todayIndex();
            boolean changed = false;
            for (String key : new ArrayList<>(AutoBlackListMap.keys())) {
                AutoBlackRecord record = AutoBlackRecord.parse(AutoBlackListMap.get(key));
                if (record == null) {
                    AutoBlackListMap.remove(key);
                    changed = true;
                    continue;
                }
                if (record.blackDay == AutoBlackRecord.PERMANENT) {
                    // 已永久拉黑（解禁重试次数用尽），不再自动解禁
                    continue;
                }
                if (record.blackDay == 0L) {
                    // 未拉黑：仅"观察期"记录会在窗口过期后丢弃（解禁后的记录保留重试次数）
                    if (record.retry == 0 && today - record.lastDay > BLACKLIST_CONFIRM_WINDOW_DAYS) {
                        AutoBlackListMap.remove(key);
                        changed = true;
                    }
                    continue;
                }
                if (today - record.blackDay < BLACKLIST_RETRY_DAYS) {
                    continue;
                }
                String[] parts = key.split("\\|", 3);
                if (parts.length < 3) {
                    AutoBlackListMap.remove(key);
                    changed = true;
                    continue;
                }
                String taskTitle = parts[2];
                SelectModelField field = findTaskListField(parts[0], parts[1]);
                if (field != null) {
                    field.remove(taskTitle);
                    record.hits = 0;
                    record.lastDay = today;
                    record.blackDay = 0L;
                    record.retry = record.retry + 1;
                    // 保留重试次数，供"再失败满 BLACKLIST_MAX_RETRY 次则永久拉黑"判断
                    AutoBlackListMap.put(key, record.format());
                    Log.record("自动解禁🕊️[" + taskTitle + "]自动拉黑已满" + BLACKLIST_RETRY_DAYS + "天，移出黑名单重试(第"
                            + record.retry + "/" + BLACKLIST_MAX_RETRY + "次)");
                } else {
                    AutoBlackListMap.remove(key);
                }
                changed = true;
            }
            AutoBlackListMap.save();
            if (changed) {
                ConfigV2.save(UserIdMap.getCurrentUid(), false);
            }
        } catch (Throwable t) {
            Log.err(TAG, "sweepExpiredBlackList err:", t);
        }
    }

    /**
     * 原先被"预置拉黑"的技术性不可自动化项：不再由各模块每日 init 预置，
     * 改由自动拉黑机制自行判定（首跑尝试 → 失败即拉黑 → 满 N 天解禁复核 → 重试满 N 次永久拉黑）。
     * <p>键为 {@code module|listTitle}，值为需要释放的条目（与写入黑名单时的键一致）。
     * <p>注意：这些条目同时必须从各模块 init 的默认黑名单里删除，否则会被每日补回。
     */
    private static final Map<String, String[]> RELEASED_DEFAULTS = new LinkedHashMap<>();

    static {
        RELEASED_DEFAULTS.put("AntOcean|AntOceanAntiepTaskList", new String[]{
                "随机任务：玩一玩得拼图"});
        RELEASED_DEFAULTS.put("AntOcean|AntOceanFishBlackList", new String[]{
                "玩一玩向僵尸开炮"});
        RELEASED_DEFAULTS.put("AntForestV2|AntForestVitalityTaskList", new String[]{
                "三国大冒险过1关征战"});
        RELEASED_DEFAULTS.put("AntForestV2|AntForestHuntTaskList", new String[]{
                "【限时】玩游戏得2次机会", "去乐园开宝箱得机会"});
        RELEASED_DEFAULTS.put("AntFarm|AntFarmDrawMachineTaskList", new String[]{
                "【限时】玩游戏得新机会", "【限时】玩游戏得3次机会", "限时玩游戏得新机会",
                "【限时】开宝箱得2次机会", "【限时】开宝箱得3次机会"});
        RELEASED_DEFAULTS.put("AntOrchard|AntOrchardTaskList", new String[]{
                "逛助农好货得肥料", "钓鱼1次", "逛一逛闪购外卖", "逛好物最高得1500肥料"});
        // 芝麻粒游戏/浏览/签到类：实测（2026-09-22 抓包 logs/chk_sesame3）服务端对 taskFeedback 不校验是否真参与过，
        // 未报名任务一发即 success ⇒ 原先"预置拉黑"的这些条目全部释放，交给任务循环自动完成；
        // 保留预置的只剩真实交易/履约类（下单/租赁/订酒店/回收/雇佣/付钱/查车）
        RELEASED_DEFAULTS.put("AntMember|MemberCreditSesameTaskList", new String[]{
                "去玩小游戏", "去玩这城有良田", "去玩三国冰河时代", "去玩青云诀之伏魔", "去玩龙迹之城",
                "去玩智商乐消消", "去玩挪了个箭", "去玩斗破苍穹", "去玩灵画师", "去玩浪漫餐厅",
                "去玩烈焰觉醒", "去玩时光杂货店", "玩小游戏30秒", "玩任意游戏30秒", "玩30秒三国冰河时代",
                "玩任意1个游戏", "添加桌面小组件", "坚持签到领奖励", "坚持逛裹酱领福利", "坚持看直播领福利",
                "坚持种水果", "每日施肥领水果", "逛淘宝签到", "头条刷热点领现金", "618去淘金币赢20亿",
                "去点淘逛一逛", "去淘金币逛一逛", "逛逛淘金币", "来淘金币赢20亿", "去逛一逛消消乐"});
    }

    /**
     * 释放原先预置拉黑的技术性不可自动化项（幂等，可重复执行）。
     * <p>只移除**尚未被自动拉黑接管**的条目；一旦已被接管，就交给"解禁 / 永久拉黑"生命周期，不再干预。
     */
    public static void sweepReleasedDefaults() {
        try {
            boolean changed = false;
            for (Map.Entry<String, String[]> entry : RELEASED_DEFAULTS.entrySet()) {
                String[] keys = entry.getKey().split("\\|", 2);
                if (keys.length < 2) {
                    continue;
                }
                SelectModelField field = findTaskListField(keys[0], keys[1]);
                if (field == null || field.getValue() == null) {
                    continue;
                }
                for (String task : entry.getValue()) {
                    if (field.getValue().contains(task) && !isAutoBlackTracked(keys[0], keys[1], task)) {
                        field.remove(task);
                        changed = true;
                        Log.record("黑名单治理🧹[" + task + "]不再预置拉黑，改由自动拉黑机制判定");
                    }
                }
            }
            if (changed) {
                ConfigV2.save(UserIdMap.getCurrentUid(), false);
            }
        } catch (Throwable t) {
            Log.err(TAG, "sweepReleasedDefaults err:", t);
        }
    }

    /**
     * 该任务是否已在自动拉黑记录中（观察期 / 已拉黑 / 已解禁待重试 / 永久拉黑）。
     * <p>供各模块把"技术性不可自动化"的默认项作为**一次性种子**写入：
     * 只在该任务从未进入过自动拉黑生命周期时写一次，之后完全交给
     * "满 N 天自动解禁 / 重试满 N 次永久拉黑" 接管，不再由每日 init 反复补回。
     */
    public static boolean isAutoBlackTracked(String module, String listTitle, String taskTitle) {
        try {
            AutoBlackListMap.ensureLoaded();
            return AutoBlackListMap.get(autoBlackKey(module, listTitle, taskTitle)) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static SelectModelField findTaskListField(String module, String listTitle) {
        try {
            ModelFields modelFields = ConfigV2.INSTANCE.getModelFieldsMap().get(module);
            return modelFields == null ? null : (SelectModelField) modelFields.get(listTitle);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 各模块"黑白名单初始化"的收尾：把预置黑名单补进列表、登记预置白名单，并保存配置。
     * <p>原先 9 个模块 × 13 个任务列表块各自抄了一遍「遍历 blackList → add(已存在则跳过) ／
     * 遍历 whiteList → remove ／ ConfigV2.save ＋ 成功/失败日志」，逻辑完全相同、只有中文名不同。
     * <p>日志文案由 displayName 拼出，与原先逐字一致：成功 `黑白名单🈲<名>自动设置: <列表>`，
     * 失败 `<名>黑白名单设置失败`。
     *
     * <p><b>白名单语义变更</b>：白名单只用于「阻止自动拉黑机制去添加该任务」，
     * 不再从列表中移除任何条目——用户手动加入黑名单的任务会被保留，不会被白名单悄悄删掉。
     *
     * @param displayName 列表中文名（如 "森林活力值任务"），仅用于日志
     * @param listTitle   列表字段名（如 "AntForestHuntTaskList"），用于登记白名单、与自动拉黑入口对应
     * @param blackList   预置拉黑项（键＝任务标题）
     * @param whiteList   预置白名单项（仅阻止自动拉黑添加，不删除用户条目）
     * @param field       目标 SelectModelField（可用性由调用方先判空；为 null 时直接返回）
     */
    public static void syncTaskBlackList(String displayName, String listTitle, Set<String> blackList,
                                         Set<String> whiteList, SelectModelField field) {
        if (field == null) {
            return;
        }
        // 登记白名单：仅用于阻止自动拉黑添加，绝不删除用户手动加入黑名单的条目
        if (listTitle != null && whiteList != null && !whiteList.isEmpty()) {
            TASK_WHITE_LIST.put(listTitle, new HashSet<>(whiteList));
        }
        Set<String> currentValues = field.getValue();
        if (currentValues != null) {
            for (String task : blackList) {
                // 白名单优先：若预置黑名单与白名单冲突，以白名单为准，不自动添加
                if (whiteList != null && whiteList.contains(task)) {
                    continue;
                }
                if (!currentValues.contains(task)) {
                    field.add(task, 0);
                }
            }
            // 注意：不再遍历 whiteList 调用 remove。白名单只挡"自动拉黑去加它"，
            // 用户手动加入黑名单的条目（无论是否为白名单任务）一律保留。
        }
        if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
            Log.record("黑白名单🈲" + displayName + "自动设置: " + field.getValue());
        } else {
            Log.record(displayName + "黑白名单设置失败");
        }
    }

    private static String autoBlackKey(String module, String listTitle, String taskTitle) {
        return module + "|" + listTitle + "|" + taskTitle;
    }

    /** 当前天序号，用于按天比较 */
    private static long todayIndex() {
        return System.currentTimeMillis() / 86400000L;
    }

    private static void recordAutoBlack(String module, String listTitle, String taskTitle) {
        try {
            String key = autoBlackKey(module, listTitle, taskTitle);
            // 同上：先确保载入，否则读不到旧的 retry，会把"解禁重试次数"反复重置为 0
            AutoBlackListMap.ensureLoaded();
            AutoBlackRecord old = AutoBlackRecord.parse(AutoBlackListMap.get(key));
            AutoBlackRecord record = new AutoBlackRecord();
            record.hits = 0;
            record.lastDay = todayIndex();
            record.retry = old == null ? 0 : old.retry;
            if (record.retry >= BLACKLIST_MAX_RETRY) {
                // 解禁重试满 BLACKLIST_MAX_RETRY 次仍失败：永久拉黑，不再自动解禁
                record.blackDay = AutoBlackRecord.PERMANENT;
                Log.record("自动拉黑🔒[" + taskTitle + "]解禁重试" + record.retry + "次仍失败，永久拉黑");
            } else {
                record.blackDay = todayIndex();
            }
            AutoBlackListMap.put(key, record.format());
            AutoBlackListMap.save();
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 自动拉黑记录：命中次数 / 最后命中天 / 拉黑天 / 解禁重试次数。
     * <p>{@code blackDay}：{@code >0} 已拉黑；{@code 0} 未拉黑（观察中或已解禁待重试）；{@link #PERMANENT} 永久拉黑。
     */
    private static final class AutoBlackRecord {
        private static final long PERMANENT = -1L;
        private int hits;
        private long lastDay;
        private long blackDay;
        private int retry;

        private static AutoBlackRecord parse(String str) {
            if (str == null || str.isEmpty()) {
                return null;
            }
            String[] parts = str.split(";");
            if (parts.length < 3) {
                return null;
            }
            try {
                AutoBlackRecord record = new AutoBlackRecord();
                record.hits = Integer.parseInt(parts[0].trim());
                record.lastDay = Long.parseLong(parts[1].trim());
                record.blackDay = Long.parseLong(parts[2].trim());
                record.retry = parts.length > 3 ? Integer.parseInt(parts[3].trim()) : 0;
                return record;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private String format() {
            return hits + ";" + lastDay + ";" + blackDay + ";" + retry;
        }
    }

}
