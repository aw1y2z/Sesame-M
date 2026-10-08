package io.github.aw1y2z.sesame.data.task;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import io.github.aw1y2z.sesame.data.RuntimeInfo;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;

/**
 * 任务尝试策略：服务端没有"保证成功"的完成接口、只能先尝试再以任务列表为准的一类任务（阅读听书、视频观看、出行、
 * 缴费、游戏中心…）的统一处理。
 * <p>本类只负责**策略**，不负责**实现**——任务怎么完成、奖励怎么领仍由各模块自己决定。它管四件事：
 * <ul>
 *   <li><b>当天只试一次</b>：做不了（{@link Outcome#UNABLE}）的打当日标记，次日随 status.json 跨天重置自动恢复；</li>
 *   <li><b>失败分级</b>：{@link Outcome#RETRY}（102 / 限流 / 异常，判据 {@code MessageUtil.isRetryable}、
 *       {@code isServerBusy}）**不打标记**、不记账，留待下一轮，避免白丢一天；</li>
 *   <li><b>兜底升级</b>：{@link Outcome#UNSUPPORTED}（400000040"不支持rpc调用"）由本类代为**伪申报**
 *       （走 {@code TaskAlternative.trigger} 发 doFarmTask；交易/履约类在其内部已被禁止，不会伪造）；
 *       调用点传了 {@link Forge} 时，优先把任务要求的"真实行为"用接口做出来，能伪造就不必退到伪申报；</li>
 *   <li><b>按规则记账</b>：伪申报后下一轮该任务仍在待办 ⇒ 兜底没写成 ⇒ 交既有自动黑名单
 *       （{@link MessageUtil#MarkTaskBlackList}，自动条目，照走"3 天解禁复核、累计 3 次永久"）。</li>
 * </ul>
 * <p>处理顺序：**正常尝试 → 行为伪造 → 伪申报 → 同天再核 → 仍不成就交自动黑名单**。
 * <p>领奖路径既不受当日标记约束、也不查黑名单，故不存在"拉黑后已完成的奖励也领不到"的问题。
 * <p>带同轮核对（{@code TaskAlternative.verify}）的调用点自行伪申报并返回 {@link Outcome#FORGED}，
 * 其 {@link Site} 传 null 即可（本类不代它伪申报、也不代它拉黑）。
 */
public class TaskAttemptPolicy {

    private static final String TAG = TaskAttemptPolicy.class.getSimpleName();

    /** 当日已尝试标记前缀，实际键为 {@code attempt::<定位键>} */
    private static final String FLAG_PREFIX = "attempt::";

    /** 已交给兜底方案的记录：{定位键: 记录时间戳}，跨天留存，用于下一轮判定兜底是否生效 */
    private static final String KEY_TRIGGERED = "taskAttemptPolicy.triggeredFallback";

    /** 兜底记录保留时长：任务消失后不会有人来清理，超过此时长直接丢弃 */
    private static final long TRIGGERED_STALE = 604800000L;

    /** 处理结论 */
    public enum Outcome {
        /** 尝试后服务端判定完成 */
        DONE,
        /** 临时故障（102 / 限流 / 异常）→ 不打标记，下一轮再试 */
        RETRY,
        /** 服务端明确表示客户端做不了（需真实操作）→ 打当日标记 */
        UNABLE,
        /** 400000040"不支持rpc调用"：由本类代为伪申报 */
        UNSUPPORTED,
        /** 调用点已自行伪申报（并已登记同轮核对） */
        FORGED,
        /** 返回给调用点：已伪申报，结果以任务列表为准 */
        TRIGGERED,
        /** 领奖成功 */
        AWARDED,
        /** 领奖失败 */
        FAILED,
        /** 今日已试过，本轮跳过 */
        TRIED_TODAY,
        /** 无可执行动作，未处理 */
        SKIPPED
    }

    /** 领奖动作，返回 true 表示领取成功 */
    public interface Award {
        boolean run();
    }

    /**
     * 尝试动作。
     * <p>只应返回 {@link Outcome#DONE} / {@link Outcome#RETRY} / {@link Outcome#UNABLE}
     * / {@link Outcome#UNSUPPORTED}（交给本类伪申报）/ {@link Outcome#FORGED}（调用点自行伪申报）。
     */
    public interface Attempt {
        Outcome run();
    }

    /**
     * 行为伪造动作：把该任务要求的"真实行为"用接口做出来（浏览任务按 viewSec 等待、行走线路直接 map.go 等）。
     * <p>返回 true 表示已伪造出去，成败以任务列表为准。没有可用行为接口的任务不要传。
     */
    public interface Forge {
        boolean run();
    }

    /** 调用点信息：自动黑名单归属 + 伪申报参数（二者都可为 null 表示"不参与"） */
    public static final class Site {
        /** 自动黑名单列表字段名（如 {@code AntOceanAntiepTaskList}）；null 表示调用点自行按规则记账 */
        final String listField;
        /** 伪申报日志前缀（如"海洋任务"） */
        final String logPrefix;
        /** 伪申报 bizKey（通常就是 taskType / taskId）；null 表示不代该调用点伪申报 */
        final String bizKey;
        final String taskSceneCode;
        final String version;
        /** 行为伪造动作；null 表示该任务没有可用的行为接口 */
        final Forge forge;

        public Site(String listField, String logPrefix, String bizKey, String taskSceneCode) {
            this(listField, logPrefix, bizKey, taskSceneCode, TaskAlternative.DEFAULT_VERSION, null);
        }

        public Site(String listField, String logPrefix, String bizKey, String taskSceneCode, Forge forge) {
            this(listField, logPrefix, bizKey, taskSceneCode, TaskAlternative.DEFAULT_VERSION, forge);
        }

        public Site(String listField, String logPrefix, String bizKey, String taskSceneCode, String version) {
            this(listField, logPrefix, bizKey, taskSceneCode, version, null);
        }

        public Site(String listField, String logPrefix, String bizKey, String taskSceneCode, String version,
                    Forge forge) {
            this.listField = listField;
            this.logPrefix = logPrefix;
            this.bizKey = bizKey;
            this.taskSceneCode = taskSceneCode;
            this.version = version;
            this.forge = forge;
        }
    }

    private TaskAttemptPolicy() {
    }

    /**
     * @param key     任务定位键，需跨轮稳定且同模块内唯一
     * @param title   任务展示名
     * @param award   领奖动作，null 表示当前无可领的奖
     * @param attempt 完成尝试动作，null 表示不可尝试
     * @param log     日志出口：森林传 {@code Log::forest}，其余传 {@code Log::other}
     * @param site    调用点信息，null 表示该调用点自行伪申报与记账（如已带同轮核对的模块）
     */
    public static Outcome handle(String key, String title, Award award, Attempt attempt,
                                 Consumer<String> log, Site site) {
        // 领奖不受当日标记约束：任务被服务端判定完成后，任何一轮都要能领到（否则当日漏领就没了）
        if (award != null) {
            if (award.run()) {
                clearTriggered(key);
                return Outcome.AWARDED;
            }
            log.accept("任务尝试⚠️领奖失败[" + title + "]");
            return Outcome.FAILED;
        }
        if (attempt == null) {
            return Outcome.SKIPPED;
        }
        String flag = FLAG_PREFIX + sanitize(key);
        // **同一天内**又见到它（上次已伪申报、这轮仍在待办）⇒ 兜底没写成 ⇒ 按自动黑名单规则停掉。
        // 必须限定同一天：日任务次日会重新回到待办，那是新实例，据此拉黑会误伤能做的任务。
        // 可靠性依据：伪申报被服务端接受后任务会很快判定完成，故"同一天内再次出现"才是兜底没写成的可靠证据；
        // 跨天出现一律按新实例处理。
        if (isSameDayTriggered(key) && autoBlackList(site, title)) {
            clearTriggered(key);
            Status.flagToday(flag);
            log.accept("任务尝试🧊兜底未生效[" + title + "]#已交自动黑名单");
            return Outcome.UNABLE;
        }
        if (Status.hasFlagToday(flag)) {
            Log.i(TAG, "今日已试[" + title + "]#跳过");
            return Outcome.TRIED_TODAY;
        }
        Outcome outcome = attempt.run();
        // 行为伪造：把任务要求的真实行为用接口做出来；能伪造就不必退到伪申报
        if ((outcome == Outcome.UNABLE || outcome == Outcome.UNSUPPORTED)
                && site != null && site.forge != null && site.forge.run()) {
            markTriggered(key);
            Status.flagToday(flag);
            return Outcome.TRIGGERED;
        }
        if (outcome == Outcome.UNSUPPORTED) {
            if (site == null || site.bizKey == null) {
                outcome = Outcome.UNABLE;
            } else {
                // 伪申报：doFarmTask 响应不可信（常回 102 而任务已生效），成败一律以任务列表为准
                TaskAlternative.trigger(null, null, title, site.bizKey, site.taskSceneCode,
                        site.version, site.logPrefix, log::accept);
                markTriggered(key);
                Status.flagToday(flag);
                return Outcome.TRIGGERED;
            }
        } else if (outcome == Outcome.FORGED) {
            markTriggered(key);
            Status.flagToday(flag);
            return Outcome.TRIGGERED;
        }
        if (outcome == Outcome.DONE || outcome == Outcome.RETRY) {
            return outcome;
        }
        if (outcome == Outcome.UNABLE) {
            log.accept("任务尝试⏳未完成[" + title + "]#今日不再尝试");
        }
        Status.flagToday(flag);
        return outcome;
    }

    /** 按自动黑名单规则记账（{@link MessageUtil#MarkTaskBlackList} 会跳过用户手动加入的条目与白名单项） */
    private static boolean autoBlackList(Site site, String title) {
        if (site == null || site.listField == null || site.listField.isEmpty()) {
            return false;
        }
        String[] target = MessageUtil.autoBlackListTarget(site.listField);
        if (target == null) {
            Log.i(TAG, "自动拉黑列表未登记:" + site.listField);
            return false;
        }
        MessageUtil.MarkTaskBlackList(target[0], site.listField, target[1], title);
        return true;
    }

    /**
     * 上次伪申报是否发生在**同一天**；跨天则清掉记录并返回 false（日任务次日属新实例，不能据此判失败）。
     */
    private static boolean isSameDayTriggered(String key) {
        String safeKey = sanitize(key);
        JSONObject jo = readTriggered();
        if (!jo.has(safeKey)) {
            return false;
        }
        if (TimeUtil.isLessThanSecondOfDays(jo.optLong(safeKey), System.currentTimeMillis())) {
            clearTriggered(key);
            return false;
        }
        return true;
    }

    private static void markTriggered(String key) {
        try {
            JSONObject jo = readTriggered();
            pruneTriggered(jo);
            jo.put(sanitize(key), System.currentTimeMillis());
            RuntimeInfo.getInstance().put(KEY_TRIGGERED, jo.toString());
        } catch (Throwable t) {
            Log.err(TAG, "markTriggered err:", t);
        }
    }

    private static void clearTriggered(String key) {
        try {
            JSONObject jo = readTriggered();
            if (jo.remove(sanitize(key)) != null) {
                RuntimeInfo.getInstance().put(KEY_TRIGGERED, jo.toString());
            }
        } catch (Throwable t) {
            Log.err(TAG, "clearTriggered err:", t);
        }
    }

    private static JSONObject readTriggered() {
        try {
            return new JSONObject(RuntimeInfo.getInstance().getString(KEY_TRIGGERED));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 任务已不在列表里时不会有人来清理，故写记录时顺手丢掉过期的 */
    private static void pruneTriggered(JSONObject jo) {
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        Iterator<String> keys = jo.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (now - jo.optLong(key) > TRIGGERED_STALE) {
                stale.add(key);
            }
        }
        for (String key : stale) {
            jo.remove(key);
        }
    }

    /** 定位键可能含 : / | 等字符，压成安全形式并限长（当日标记落进 status.json，伪申报记录落进 runtimeInfo.json） */
    private static String sanitize(String key) {
        String safe = key.replaceAll("[^0-9A-Za-z_.\\-]", "_");
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }
}
