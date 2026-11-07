package io.github.aw1y2z.sesame.model.task.antMember;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Objects;
import java.util.Set;

import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.model.extensions.ExtensionsHandle;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.AntMemberTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 蚂蚁会员任务的公共基类。
 * <p>会员任务与会员广告任务共用同一套任务列表接口(signPageTaskList / queryAllStatusTaskList)与
 * 浏览任务处理(doBrowseTask)，区别仅在于「是否只处理广告任务(adTask=true 且无 targetBusiness)」
 * 以及「非 BROWSE 分类如何上报」。这些差异通过参数(adOnly / blacklist)在调用时传入，
 * 由同一个 AntMember.run() 先后跑两遍（会员 + 广告），从而成为两个互相隔离的执行路径，
 * 各自有独立的开关与黑名单配置，但配置页只出现一块「会员任务」。
 * <p><b>取数去重</b>：两类共用同一次 RPC 取数结果（静态缓存 + 每轮 TTL），避免同一账号同时开启两类时
 * 每轮重复请求 signPageTaskList / queryAllStatusTaskList 加重风控；列表取不到(风控/1009)时写入
 * 共享的当日标记，两条路径一同跳过、当日不再重试。
 */
public abstract class BaseAntMember extends ModelTask {

    protected final String TAG = getClass().getSimpleName();

    /** 两个类共享的当日失败标记：列表取不到(风控/1009)即置位，后续轮次两条路径一起跳过，不再发 RPC */
    private static final String FLAG_SIGN_PAGE_TASK_FAILED = "MemberTask::signPageTaskListFailed";

    /** 两个类共享的当日"任务已完成"标记：列表为空(当天无可做任务)即置位，后续轮次跳过，不再发 RPC */
    private static final String FLAG_SIGN_PAGE_TASK_DONE = "MemberTask::signPageTaskListDone";
    private static final String FLAG_QUERY_ALL_TASK_DONE = "MemberTask::queryAllStatusTaskListDone";

    /** 共享取数缓存：同一账号、TTL 内只真正取一次 */
    private static final long CACHE_TTL = 60_000L;
    private static JSONObject cachedSignPage;
    private static JSONObject cachedQueryAll;
    private static long cacheTime = 0;
    private static String cacheUid = "";

    /** 日志标签：adOnly=true 用「会员广告任务」，false 用「会员任务」 */
    private static String labelFor(boolean adOnly) {
        return adOnly ? "会员广告任务" : "会员任务";
    }

    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.i(TAG, "任务暂停⏸️" + getName() + ":当前为仅收能量时间");
            return false;
        }
        return true;
    }

    /**
     * 跑 signPageTaskList：取数（两类共用）、解析、按职责处理。
     *
     * @param adOnly    true=只处理广告任务，false=处理除广告任务外的其它会员任务
     * @param blacklist 本路径生效的黑名单任务名集合
     * @return 是否成功取到任务列表；取不到(风控/无列表)返回 false，调用方不应再发起其它任务 RPC
     */
    protected boolean runSignPageTaskList(boolean adOnly, Set<String> blacklist) {
        if (Status.hasFlagToday(FLAG_SIGN_PAGE_TASK_FAILED)) {
            Log.i(TAG, "今日会员任务列表获取失败(风控)，跳过");
            return false;
        }
        JSONObject jo = fetchSignPageTaskList(false);
        if (jo == null) {
            return false;
        }
        boolean doubleCheck;
        do {
            doubleCheck = processSignPageTaskList(jo, adOnly, blacklist);
            if (doubleCheck) {
                jo = fetchSignPageTaskList(true); // 有任务完成，强制刷新再跑一遍
            }
        }
        while (doubleCheck && jo != null);
        return true;
    }

    /** 跑 queryAllStatusTaskList：取数（共用）、解析、按职责处理 */
    protected void runQueryAllStatusTaskList(boolean adOnly, Set<String> blacklist) {
        if (Status.hasFlagToday(FLAG_SIGN_PAGE_TASK_FAILED)) {
            Log.i(TAG, "今日可领任务列表获取失败(风控)，跳过");
            return;
        }
        JSONObject jo = fetchQueryAllStatusTaskList(false);
        if (jo == null) {
            return;
        }
        boolean doubleCheck;
        do {
            doubleCheck = processQueryAllStatusTaskList(jo, adOnly, blacklist);
            if (doubleCheck) {
                jo = fetchQueryAllStatusTaskList(true);
            }
        }
        while (doubleCheck && jo != null);
    }

    /**
     * 取 signPageTaskList（两类共用一次）。
     *
     * @param force true=忽略缓存强制重新请求（用于 doubleCheck 后再查一遍）
     */
    private JSONObject fetchSignPageTaskList(boolean force) {
        String uid = UserIdMap.getCurrentUid();
        long now = System.currentTimeMillis();
        if (!force && cachedSignPage != null && Objects.equals(cacheUid, uid) && (now - cacheTime) < CACHE_TTL) {
            return cachedSignPage;
        }
        // 当日已确认取不到列表(风控/1009)：两条路径一起跳过，不再请求
        if (Status.hasFlagToday(FLAG_SIGN_PAGE_TASK_FAILED)) {
            Log.i(TAG, "今日任务列表获取失败(风控)，跳过");
            return null;
        }
        // 当日任务已完成(列表为空)：后续轮次跳过，不再请求
        if (Status.hasFlagToday(FLAG_SIGN_PAGE_TASK_DONE)) {
            Log.i(TAG, "今日无可做任务列表，跳过");
            return null;
        }
        try {
            String rawJson = AntMemberRpcCall.signPageTaskList();
            JSONObject jo = new JSONObject(rawJson);
            TimeUtil.sleep(500);
            if (!MessageUtil.checkResultCode(TAG + " signPageTaskList", jo)) {
                Status.flagToday(FLAG_SIGN_PAGE_TASK_FAILED);
                return null;
            }
            // 成功响应即缓存(即使任务列表为空)，确保广告路径复用、不再重复请求；空列表由 process 处理
            cacheUid = uid;
            cachedSignPage = jo;
            cacheTime = now;
            return jo;
        } catch (Throwable t) {
            Log.err(TAG, "signPageTaskList err:", t);
            Status.flagToday(FLAG_SIGN_PAGE_TASK_FAILED);
            return null;
        }
    }

    /**
     * 取 queryAllStatusTaskList（两类共用一次）。
     */
    private JSONObject fetchQueryAllStatusTaskList(boolean force) {
        String uid = UserIdMap.getCurrentUid();
        long now = System.currentTimeMillis();
        if (!force && cachedQueryAll != null && Objects.equals(cacheUid, uid) && (now - cacheTime) < CACHE_TTL) {
            return cachedQueryAll;
        }
        if (Status.hasFlagToday(FLAG_SIGN_PAGE_TASK_FAILED)) {
            return null;
        }
        // 当日可领任务已完成(列表为空)：后续轮次跳过，不再请求
        if (Status.hasFlagToday(FLAG_QUERY_ALL_TASK_DONE)) {
            Log.i(TAG, "今日无可做任务列表，跳过");
            return null;
        }
        try {
            String rawJson = AntMemberRpcCall.queryAllStatusTaskList();
            JSONObject jo = new JSONObject(rawJson);
            TimeUtil.sleep(500);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                Log.i(TAG, "queryAllStatusTaskList 接口返回失败");
                return null;
            }
            // 成功响应即缓存(即使 availableTaskList 为空)，确保广告路径复用、不再重复请求；空列表由 process 处理
            cacheUid = uid;
            cachedQueryAll = jo;
            cacheTime = now;
            return jo;
        } catch (Throwable t) {
            Log.err(TAG, "queryAllStatusTaskList err:", t);
            return null;
        }
    }

    /** 解析 signPageTaskList 结果：登记任务名 + 遍历分类，按职责处理 */
    private boolean processSignPageTaskList(JSONObject jo, boolean adOnly, Set<String> blacklist) {
        boolean doubleCheck = false;
        String label = labelFor(adOnly);
        try {
            JSONArray categoryTaskList = jo.optJSONArray("categoryTaskList");
            if (categoryTaskList == null || categoryTaskList.length() == 0) {
                Status.flagToday(FLAG_SIGN_PAGE_TASK_DONE);
                Log.i(TAG, labelFor(adOnly) + "无可做任务列表");
                return false;
            }
            for (int i = 0; i < categoryTaskList.length(); i++) {
                JSONObject category = categoryTaskList.getJSONObject(i);
                JSONArray taskList = category.optJSONArray("taskList");
                if (taskList == null || taskList.length() == 0) {
                    continue;
                }
                String type = category.optString("type");
                // 登记任务名到黑名单候选项：signPageTaskList 的任务(含广告任务)不在其它列表里，否则设置页搜不到
                for (int j = 0; j < taskList.length(); j++) {
                    JSONObject task = taskList.optJSONObject(j);
                    JSONObject cfg = task == null ? null : task.optJSONObject("taskConfigInfo");
                    if (cfg == null && task != null) {
                        cfg = task.optJSONObject("simpleTaskConfig");
                    }
                    if (cfg == null) {
                        continue;
                    }
                    String taskName = cfg.optString("name", "");
                    if (taskName.isEmpty()) {
                        taskName = cfg.optString("title", "");
                    }
                    if (!taskName.isEmpty()) {
                        AntMemberTaskListMap.add(taskName, taskName);
                    }
                }
                if ("BROWSE".equals(type)) {
                    doubleCheck = doBrowseTask(taskList, adOnly, blacklist) || doubleCheck;
                } else {
                    // 组类型不是 BROWSE，组内仍可能混着浏览类任务：如「做任务赚积分」的 TASK_CONV 组 type=OTHERS，
                    // 里面的「逛一逛淘宝斗地主/消消乐」targetBusiness 是 BROWSE#UNLIMITED#xxx，按组过滤会整组丢掉。
                    // 这里按任务自身的 targetBusiness 挑出浏览类交给 doBrowseTask，其余保持原样给扩展处理。
                    JSONArray browseOnly = pickBrowseTask(taskList);
                    if (browseOnly.length() > 0) {
                        doubleCheck = doBrowseTask(browseOnly, adOnly, blacklist) || doubleCheck;
                    }
                    // 广告路径只处理 BROWSE 下的广告任务，非 BROWSE 分类的任务不是广告任务，跳过
                    if (!adOnly) {
                        ExtensionsHandle.handleAlphaRequest("antMember", "doMoreTask", category);
                    }
                }
            }
            AntMemberTaskListMap.save();
        } catch (Throwable t) {
            Log.err(TAG, "processSignPageTaskList err:", t);
        }
        return doubleCheck;
    }

    /** 从任意分类里挑出 targetBusiness 首段为 BROWSE 的任务（分类的 type 不可靠时用） */
    private static JSONArray pickBrowseTask(JSONArray taskList) {
        JSONArray picked = new JSONArray();
        for (int i = 0; i < taskList.length(); i++) {
            JSONObject task = taskList.optJSONObject(i);
            if (task == null) {
                continue;
            }
            JSONObject cfg = task.optJSONObject("taskConfigInfo");
            if (cfg == null) {
                cfg = task.optJSONObject("simpleTaskConfig");
            }
            JSONArray targetBusinessArr = cfg == null ? null : cfg.optJSONArray("targetBusiness");
            if (targetBusinessArr == null || targetBusinessArr.length() == 0) {
                targetBusinessArr = task.optJSONArray("targetBusiness");
            }
            if (targetBusinessArr == null || targetBusinessArr.length() == 0) {
                continue;
            }
            if (targetBusinessArr.optString(0, "").startsWith("BROWSE#")) {
                picked.put(task);
            }
        }
        return picked;
    }

    /** 解析 queryAllStatusTaskList 结果，按职责处理 */
    private boolean processQueryAllStatusTaskList(JSONObject jo, boolean adOnly, Set<String> blacklist) {
        boolean doubleCheck = false;
        try {
            JSONArray availableTaskList = jo.optJSONArray("availableTaskList");
            if (availableTaskList == null || availableTaskList.length() == 0) {
                Log.i(TAG, labelFor(adOnly) + "无可做任务列表");
                Status.flagToday(FLAG_QUERY_ALL_TASK_DONE);
                return false;
            }
            doubleCheck = doBrowseTask(availableTaskList, adOnly, blacklist);
        } catch (Throwable t) {
            Log.err(TAG, "processQueryAllStatusTaskList err:", t);
        }
        return doubleCheck;
    }

    // 浏览任务：会员任务与会员广告任务共用
    private Boolean doBrowseTask(JSONArray taskList, boolean adOnly, Set<String> blacklist) {
        boolean doubleCheck = false;
        try {
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.getJSONObject(i);
                if (task.optBoolean("hybrid")) {
                    int periodCurrentCount = Integer.parseInt(task.getJSONObject("extInfo").getString("PERIOD_CURRENT_COUNT"));
                    int periodTargetCount = Integer.parseInt(task.getJSONObject("extInfo").getString("PERIOD_TARGET_COUNT"));
                    int count = periodTargetCount > periodCurrentCount ? periodTargetCount - periodCurrentCount : 0;
                    if (count > 0) {
                        doubleCheck = doubleCheck || doBrowseTask(task, periodTargetCount, periodTargetCount, adOnly, blacklist);
                    }
                } else {
                    doubleCheck = doubleCheck || doBrowseTask(task, 1, 1, adOnly, blacklist);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "doBrowseTask err:", t);
        }
        return doubleCheck;
    }

    private Boolean doBrowseTask(JSONObject task, int left, int right, boolean adOnly, Set<String> blacklist) {
        boolean doubleCheck = false;
        String label = labelFor(adOnly);
        try {
            // 旧结构用 taskConfigInfo(name/id/awardParam/targetBusiness)，新结构改用 simpleTaskConfig(title/configId/stageVOList) 且 targetBusiness 提到 task 顶层
            JSONObject taskConfigInfo = task.optJSONObject("taskConfigInfo");
            JSONObject simpleTaskConfig = task.optJSONObject("simpleTaskConfig");
            JSONObject config = taskConfigInfo != null ? taskConfigInfo : simpleTaskConfig;
            if (config == null) {
                Log.other(label + "⏭️跳过#缺少任务配置");
                return false;
            }
            String name = config.optString("name", "");
            if (name.isEmpty()) {
                name = config.optString("title", "");
            }
            //黑名单任务跳过
            if (blacklist.contains(name)) {
                return false;
            }
            Long id = config.has("id") ? config.optLong("id") : config.optLong("configId");
            String awardParamPoint = taskAwardPoint(config);
            JSONArray targetBusinessArr = config.optJSONArray("targetBusiness");
            if (targetBusinessArr == null || targetBusinessArr.length() == 0) {
                targetBusinessArr = task.optJSONArray("targetBusiness");
            }
            if (targetBusinessArr == null || targetBusinessArr.length() == 0) {
                String tb = task.optString("targetBusiness", "");
                if (!tb.isEmpty()) {
                    targetBusinessArr = new JSONArray().put(tb);
                }
            }
            // 广告任务判定：adTask=true 且无 targetBusiness
            boolean isAd = task.optBoolean("adTask") && (targetBusinessArr == null || targetBusinessArr.length() == 0);
            if (adOnly) {
                // 仅处理广告任务的路径：非广告任务直接跳过，交给会员路径
                if (!isAd) {
                    return false;
                }
            } else {
                // 会员路径：广告任务交给广告路径处理，这里跳过
                if (isAd) {
                    return false;
                }
            }
            if (targetBusinessArr == null || targetBusinessArr.length() == 0) {
                // 走到这里说明是广告任务(仅广告路径会进入)，走广告任务申请->等待->完成流程
                if (isAd) {
                    return doAdTask(task, config, name, awardParamPoint);
                }
                Log.other(label + "⏭️跳过[" + name + "]#无 targetBusiness 配置");
                return false;
            }
            String targetBusiness = targetBusinessArr.getString(0);
            // targetBusiness 格式为 bizType#bizSubType#bizParam，第 1 段是 bizType。
            // 仅处理 BROWSE 类任务：其它类型（如 CALL_APP）与 executeTask 固定的 bizType=BROWSE
            // 组合不被服务端支持，跳过并打当日标记，避免每轮 applyTask/executeTask 反复失败刷屏
            String[] targetBusinessArray = targetBusiness.split("#");
            if (targetBusinessArray.length < 2 || !"BROWSE".equals(targetBusinessArray[0])) {
                String bizType = targetBusinessArray.length > 0 ? targetBusinessArray[0] : "";
                if (!Status.hasFlagToday("antmember::skipBizType::" + name)) {
                    Log.other(label + "⏭️跳过[" + name + "]#非浏览类任务(" + bizType + ")");
                    Status.flagToday("antmember::skipBizType::" + name);
                }
                return false;
            }
            for (int i = left; i <= right; i++) {
                JSONObject jo = new JSONObject(AntMemberRpcCall.applyTask(name, id));
                TimeUtil.sleep(300);
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    //检查并标记黑名单任务
                    MessageUtil.checkResultCodeAndMarkTaskBlackList("AntMemberTaskList", name, jo);
                    continue;
                }
                String bizParam;
                String bizSubType;
                if (targetBusinessArray.length > 2) {
                    bizParam = targetBusinessArray[2];
                    bizSubType = targetBusinessArray[1];
                } else {
                    bizParam = targetBusinessArray[1];
                    bizSubType = targetBusinessArray[0];
                }
                jo = new JSONObject(AntMemberRpcCall.executeTask(bizParam, bizSubType));
                TimeUtil.sleep(300);
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    //检查并标记黑名单任务
                    MessageUtil.checkResultCodeAndMarkTaskBlackList("AntMemberTaskList", name, jo);
                    continue;
                }
                String ex = left == right && left == 1 ? "" : "(" + (i + 1) + "/" + right + ")";
                Log.other(label + "🎖️完成[" + name + ex + "]#获得[" + awardParamPoint + "积分]");
                doubleCheck = true;
            }
        } catch (Throwable t) {
            Log.err(TAG, "doBrowseTask err:", t);
        }
        return doubleCheck;
    }

    // 广告任务: 申请(applyAdTask) -> 等待浏览时长 -> 完成(finishAdTask)
    private Boolean doAdTask(JSONObject task, JSONObject config, String name, String awardParamPoint) {
        String label = labelFor(true);
        try {
            JSONObject extMap = task.optJSONObject("lightsAdExtMap");
            if (extMap == null) {
                Log.record(label + "⏭️跳过[" + name + "]#广告任务缺少扩展参数");
                return false;
            }
            String bizId = extMap.optString("bizId", "");
            String adId = extMap.optString("adId", "");
            if (bizId.isEmpty() || adId.isEmpty()) {
                Log.record(label + "⏭️跳过[" + name + "]#广告任务缺少bizId/adId");
                return false;
            }
            int awardNum = 0;
            try {
                awardNum = Integer.parseInt(awardParamPoint);
            } catch (Throwable ignored) {
            }
            JSONObject applyJo = new JSONObject(AntMemberRpcCall.applyAdTask(bizId, adId, awardNum, extMap));
            if (!isAdTaskOk(applyJo)) {
                Log.record(label + "⏭️跳过[" + name + "]#广告任务申请失败");
                return false;
            }
            // 广告任务需浏览一段时间(约15秒)后才能完成
            int browseSeconds = config.optInt("browseSeconds", 15);
            TimeUtil.sleep(Math.max(browseSeconds, 15) * 1000L);
            JSONObject finishJo = new JSONObject(AntMemberRpcCall.finishAdTask(bizId));
            if (!isAdTaskOk(finishJo)) {
                Log.record(label + "⏭️跳过[" + name + "]#广告任务完成失败");
                return false;
            }
            Log.other(label + "🎖️完成[" + name + "]#获得[" + awardNum + "积分]");
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "doAdTask err:", t);
        }
        return false;
    }

    private boolean isAdTaskOk(JSONObject jo) {
        return jo.optBoolean("success") || "SUCCESS".equals(jo.optString("resultCode"));
    }

    // 任务积分：旧结构 awardParam.awardParamPoint，新结构 stageVOList[0].awardParam.awardParamPoint
    private String taskAwardPoint(JSONObject config) {
        JSONObject awardParam = config.optJSONObject("awardParam");
        if (awardParam != null) {
            return awardParam.optString("awardParamPoint", "");
        }
        JSONArray stageVOList = config.optJSONArray("stageVOList");
        if (stageVOList != null && stageVOList.length() > 0) {
            JSONObject stage = stageVOList.optJSONObject(0);
            if (stage != null) {
                JSONObject stageAwardParam = stage.optJSONObject("awardParam");
                if (stageAwardParam != null) {
                    return stageAwardParam.optString("awardParamPoint", "");
                }
            }
        }
        return "";
    }
}
