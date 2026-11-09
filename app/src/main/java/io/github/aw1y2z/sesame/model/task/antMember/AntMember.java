package io.github.aw1y2z.sesame.model.task.antMember;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.ModelFields;

import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.data.task.ModelTask;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy;
import io.github.aw1y2z.sesame.data.task.TaskAward;
import io.github.aw1y2z.sesame.data.task.TaskAttemptPolicy.Outcome;
import io.github.aw1y2z.sesame.entity.AlipayAntMemberAdTaskList;
import io.github.aw1y2z.sesame.entity.AlipayAntMemberTaskList;
import io.github.aw1y2z.sesame.entity.AlipayMemberCreditSesameTaskList;
import io.github.aw1y2z.sesame.entity.AlipayWelfareFundTaskList;
import io.github.aw1y2z.sesame.entity.AlipayGoldTicketTaskList;
import io.github.aw1y2z.sesame.entity.MemberBenefit;
import io.github.aw1y2z.sesame.util.idMap.GoldTicketTaskListMap;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.model.base.TaskCommon;
import io.github.aw1y2z.sesame.model.base.TaskAlternative;
import io.github.aw1y2z.sesame.model.extensions.ExtensionsHandle;
import io.github.aw1y2z.sesame.model.task.antOrchard.AntOrchard;
import io.github.aw1y2z.sesame.model.task.antOrchard.AntOrchardRpcCall;
import io.github.aw1y2z.sesame.util.*;
import io.github.aw1y2z.sesame.util.idMap.AntMemberAdTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntMemberTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.MemberBenefitIdMap;
import io.github.aw1y2z.sesame.util.idMap.MemberCreditSesameTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.PromiseSimpleTemplateIdMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class AntMember extends BaseAntMember {

    private static final String TAG = AntMember.class.getSimpleName();

    /**
     * `doFarmTask` 已发出、但响应不足以判定成败的游戏中心任务：{@code taskId -> 任务标题}。
     * <p>由 {@link #verifyPendingTasks()} 在列表处理完后按任务列表状态核对。
     */
    private final Map<String, String> pendingVerifyTasks = new LinkedHashMap<>();


    /** 同轮核对配置（见 TaskAlternative.verify） */
    private static final TaskAlternative.VerifyConfig VERIFY_CFG = new TaskAlternative.VerifyConfig(
            "AntMember", "AntMemberTaskList", "会员任务", "游戏中心", true, msg -> Log.other(msg));

    /** 游戏中心宝箱每日上限（首页 {@code taskModule.dailyTaskCertCntUpperLimit} 的兜底值）。 */
    private static final int GAME_CENTER_CERT_LIMIT = 30;

    @Override
    public String getName() {
        return "会员";
    }
    
    @Override
    public ModelGroup getGroup() {
        return ModelGroup.MEMBER;
    }

    private BooleanModelField AntMemberTask;
    private BooleanModelField AutoAntMemberTaskList;
    private SelectModelField AntMemberTaskList;

    // 会员广告任务：独立开关 + 独立黑名单，与会员任务互不干扰（运行共用取数、各自过滤）
    private BooleanModelField enableAdTask;
    private BooleanModelField AutoAntMemberAdTaskList;
    private SelectModelField AntMemberAdTaskList;

    private BooleanModelField memberSign;
    private BooleanModelField memberPointExchangeBenefit;
    private SelectModelField memberPointExchangeBenefitList;
    
    private BooleanModelField collectSesame;
    private BooleanModelField AutoMemberCreditSesameTaskList;
    private SelectModelField MemberCreditSesameTaskList;
    private BooleanModelField SesameGrowthBehavior;
    private BooleanModelField promise;
    private SelectModelField promiseList;
    private BooleanModelField enableGameCenter;

    /** 黄金票：总开关 enableGoldTicketTask 之下分为签到 / 提取 / 任务三路 */
    private BooleanModelField enableGoldTicketTask;
    private BooleanModelField enableGoldTicketSign;
    private BooleanModelField enableGoldTicketConsume;
    private BooleanModelField enableGoldTicketBrowse;
    private BooleanModelField AutoGoldTicketTaskList;
    private SelectModelField GoldTicketTaskList;

    private BooleanModelField KuaiDiFuLiJia;

    /** 网商银行福利金 */
    private BooleanModelField welfareFund;
    private BooleanModelField welfareFundSign;
    private BooleanModelField welfareFundTask;
    private BooleanModelField AutoWelfareFundTaskList;
    private SelectModelField WelfareFundTaskList;



    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(AntMemberTask = new BooleanModelField("AntMemberTask", "会员任务", false));
        modelFields.addField(AutoAntMemberTaskList = new BooleanModelField("AutoAntMemberTaskList", "会员任务 | 自动黑名单", true).setDependsOn("AntMemberTask"));
        modelFields.addField(AntMemberTaskList = new SelectModelField("AntMemberTaskList", "会员任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntMemberTaskList::getList).setDependsOn("AutoAntMemberTaskList"));
        modelFields.addField(enableAdTask = new BooleanModelField("AntMemberAdTask", "会员广告任务", false));
        modelFields.addField(AutoAntMemberAdTaskList = new BooleanModelField("AutoAntMemberAdTaskList", "会员广告任务 | 自动黑名单", true).setDependsOn("AntMemberAdTask"));
        modelFields.addField(AntMemberAdTaskList = new SelectModelField("AntMemberAdTaskList", "会员广告任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntMemberAdTaskList::getList).setDependsOn("AutoAntMemberAdTaskList"));
        modelFields.addField(memberSign = new BooleanModelField("memberSign", "会员签到", false));
        modelFields.addField(memberPointExchangeBenefit = new BooleanModelField("memberPointExchangeBenefit", "会员积分 | 兑换权益", false));
        modelFields.addField(memberPointExchangeBenefitList = new SelectModelField("memberPointExchangeBenefitList", "会员积分 | 权益列表", new LinkedHashSet<>(), MemberBenefit::getList).setDependsOn("memberPointExchangeBenefit"));
        modelFields.addField(collectSesame = new BooleanModelField("collectSesame", "芝麻粒 | 领取", false));
        modelFields.addField(AutoMemberCreditSesameTaskList = new BooleanModelField("AutoMemberCreditSesameTaskList", "芝麻粒任务 | 自动黑名单", true).setDependsOn("collectSesame"));
        modelFields.addField(MemberCreditSesameTaskList = new SelectModelField("MemberCreditSesameTaskList", "芝麻粒任务 | 黑名单列表", new LinkedHashSet<>(), AlipayMemberCreditSesameTaskList::getList).setDependsOn("AutoMemberCreditSesameTaskList"));
        modelFields.addField(SesameGrowthBehavior = new BooleanModelField("SesameGrowthBehavior", "攒芝麻分进度", false));
        modelFields.addField(enableGameCenter = new BooleanModelField("enableGameCenter", "游戏中心 | 得乐园豆", false));
        //modelFields.addField(promise = new BooleanModelField("promise", "生活记录 | 坚持做", false));
        //modelFields.addField(promiseList = new SelectModelField("promiseList", "生活记录 | 坚持做列表", new LinkedHashSet<>(), PromiseSimpleTemplate::getList));
        modelFields.addField(KuaiDiFuLiJia = new BooleanModelField("KuaiDiFuLiJia", "我的快递 | 福利加", false));
        modelFields.addField(enableGoldTicketTask = new BooleanModelField("enableGoldTicketTask", "黄金票 | 开启", false));
        modelFields.addField(enableGoldTicketSign = new BooleanModelField("enableGoldTicketSign", "黄金票 | 签到", false).setDependsOn("enableGoldTicketTask"));
        modelFields.addField(enableGoldTicketConsume = new BooleanModelField("enableGoldTicketConsume", "黄金票 | 提取", false).setDependsOn("enableGoldTicketTask"));
        modelFields.addField(enableGoldTicketBrowse = new BooleanModelField("enableGoldTicketBrowse", "黄金票 | 任务", false).setDependsOn("enableGoldTicketTask"));
        modelFields.addField(AutoGoldTicketTaskList = new BooleanModelField("AutoGoldTicketTaskList", "黄金票任务 | 自动黑名单", true).setDependsOn("enableGoldTicketBrowse"));
        modelFields.addField(GoldTicketTaskList = new SelectModelField("GoldTicketTaskList", "黄金票任务 | 黑名单列表", new LinkedHashSet<>(), AlipayGoldTicketTaskList::getList).setDependsOn("AutoGoldTicketTaskList"));
        modelFields.addField(welfareFund = new BooleanModelField("welfareFund", "福利金 | 开启", false));
        modelFields.addField(welfareFundSign = new BooleanModelField("welfareFundSign", "福利金 | 签到", true).setDependsOn("welfareFund"));
        modelFields.addField(welfareFundTask = new BooleanModelField("welfareFundTask", "福利金 | 任务", true).setDependsOn("welfareFund"));
        modelFields.addField(AutoWelfareFundTaskList = new BooleanModelField("AutoWelfareFundTaskList", "福利金任务 | 自动黑名单", true).setDependsOn("welfareFundTask"));
        modelFields.addField(WelfareFundTaskList = new SelectModelField("WelfareFundTaskList", "福利金任务 | 黑名单列表", new LinkedHashSet<>(), AlipayWelfareFundTaskList::getList).setDependsOn("AutoWelfareFundTaskList"));
        return modelFields;
    }
    
    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.i("任务暂停⏸️蚂蚁会员:当前为仅收能量时间");
            return false;
        }
        return true;
    }
    
    @Override
    public void run() {
        try {
            // 映射是进程内存态，重启后为空，每轮先按账号载入（当天不会重复拉列表）
            AntMemberTaskListMap.load();
            AntMemberAdTaskListMap.load();
            //初始任务列表
            if (!Status.hasFlagToday("BlackList::initMember")) {
                initMemberTaskListMap(AutoAntMemberTaskList.getValue(), AutoMemberCreditSesameTaskList.getValue(), AntMemberTask.getValue(), collectSesame.getValue());
                Status.flagToday("BlackList::initMember");
            }
            else if (AntMemberTaskListMap.getMap().isEmpty() && !Status.hasFlagToday("BlackList::initMemberRecover")) {
                // 当天已初始化过、映射却为空：补拉一次，否则配置页候选项会一直是空的
                Status.flagToday("BlackList::initMemberRecover");
                initMemberTaskListMap(AutoAntMemberTaskList.getValue(), AutoMemberCreditSesameTaskList.getValue(), AntMemberTask.getValue(), collectSesame.getValue());
            }
            
            if (memberSign.getValue()) {
                memberSign();
            }
            
            if (AntMemberTask.getValue()) {
                if (!Status.hasFlagToday("MemberTask::queryPointCertDone")) {
                    if (queryPointCert(1, 8)) {
                        Status.flagToday("MemberTask::queryPointCertDone");
                    }
                }
                // 先判断任务列表能否取到：风控(1009等)拿不到列表时，不再请求其它任务接口，避免加重风控
                if (runSignPageTaskList(false, AntMemberTaskList.getValue())) {
                    runQueryAllStatusTaskList(false, AntMemberTaskList.getValue());
                }
            }
            if (enableAdTask.getValue()) {
                if (AutoAntMemberAdTaskList.getValue()) {
                    Set<String> blackList = MessageUtil.presetBlackList("AntMember", "AntMemberAdTaskList");
                    Set<String> whiteList = new LinkedHashSet<>();
                    whiteList.add("会员签到");
                    whiteList.add("会员积分");
                    whiteList.add("游戏中心");
                    whiteList.add("黄金票");
                    whiteList.add("福利金");
                    whiteList.add("网商银行");
                    whiteList.add("蚂蚁积分");
                    whiteList.add("攒积分");
                    MessageUtil.syncTaskBlackList("会员广告任务", "AntMemberAdTaskList", blackList, whiteList, AntMemberAdTaskList);
                }
                // 广告任务：复用会员任务的取数结果(共享缓存)，只处理 adTask=true 且无 targetBusiness 的任务
                if (runSignPageTaskList(true, AntMemberAdTaskList.getValue())) {
                    runQueryAllStatusTaskList(true, AntMemberAdTaskList.getValue());
                }
            }
            
            if (!Status.hasFlagToday("MemberTask::memberPointExchangeBenefitDone")) {
                if (memberPointExchangeBenefit()) {
                    Status.flagToday("MemberTask::memberPointExchangeBenefitDone");
                }
            }
            if (collectSesame.getValue()) {
                CheckInTaskRpcManager();
                collectSesame();
            }
            
            //芝麻积攒进度
            if (SesameGrowthBehavior.getValue()) {
                handleGrowthGuideTasks();
                queryAndCollect();
            }
            // 我的快递任务
            if (KuaiDiFuLiJia.getValue()) {
                RecommendTask();
                OrdinaryTask();
            }
            if (enableGoldTicketTask.getValue()) {
                goldTicket();
            }
            if (enableGameCenter.getValue()) {
                //检查并执行签到
                checkAndDoSignIn();
                //查询并处理任务列表
                queryAndProcessTaskList();
                //游戏中心任务奖励（整场景一键领取；游戏类任务需真实游玩才能推进，不做完成尝试）
                gameCenterTaskPrize();

                //查询玩乐豆小球列表，有则领取
                queryPointBallList();
                
            }
            if (welfareFund.getValue()) {
                WelfareFund.run(welfareFundSign.getValue(), welfareFundTask.getValue(),
                        AutoWelfareFundTaskList.getValue(), WelfareFundTaskList.getValue());
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }
    
    public static void initMemberTaskListMap(boolean AutoAntMemberTaskList, boolean AutoMemberCreditSesameTaskList, boolean AntMemberTask, boolean collectSesame) {
        try {
            //初始化AntMemberTaskListMap
            AntMemberTaskListMap.load();
            AntMemberAdTaskListMap.load();
            // 广告任务的预置黑名单（当前为空）
            for (String task : MessageUtil.presetBlackList("AntMember", "AntMemberAdTaskList")) {
                AntMemberAdTaskListMap.add(task, task);
            }
            // 预置黑名单登记在 MessageUtil（单一真相，配置页据此标注"默认"）
            Set<String> blackList = MessageUtil.presetBlackList("AntMember", "AntMemberTaskList");
            // 可继续添加更多黑名单任务
            
            Set<String> whiteList = new HashSet<>();// 从黑名单中移除该任务
            //whiteList.add("逛一逛芝麻树");
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                AntMemberTaskListMap.add(task, task);
            }
            
            JSONObject jo;
            if (AntMemberTask) {
                boolean hasNextPage = true;
                int page = 1;
                do {
                    jo = new JSONObject(AntMemberRpcCall.queryPointCert(page, 8));
                    TimeUtil.sleep(500);
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        break;
                    }
                    hasNextPage = jo.getBoolean("hasNextPage");
                    page++;
                    JSONArray jaCertList = jo.getJSONArray("certList");
                    for (int i = 0; i < jaCertList.length(); i++) {
                        jo = jaCertList.getJSONObject(i);
                        String bizTitle = jo.getString("bizTitle");
                        AntMemberTaskListMap.add(bizTitle, bizTitle);
                    }
                }
                while (hasNextPage);
                
                jo = new JSONObject(AntMemberRpcCall.queryAllStatusTaskList());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    JSONArray availableTaskList = jo.optJSONArray("availableTaskList");
                    if (availableTaskList != null) {
                        for (int i = 0; i < availableTaskList.length(); i++) {
                            JSONObject task = availableTaskList.optJSONObject(i);
                            JSONObject taskConfigInfo = task == null ? null : task.optJSONObject("taskConfigInfo");
                            String name = taskConfigInfo == null ? "" : taskConfigInfo.optString("name", "");
                            if (!name.isEmpty()) {
                                AntMemberTaskListMap.add(name, name);
                            }
                        }
                    }
                    JSONArray taskHistoryList = jo.optJSONArray("taskHistoryList");
                    if (taskHistoryList != null) {
                        for (int i = 0; i < taskHistoryList.length(); i++) {
                            JSONObject task = taskHistoryList.optJSONObject(i);
                            JSONObject taskConfigInfo = task == null ? null : task.optJSONObject("taskConfigInfo");
                            String name = taskConfigInfo == null ? "" : taskConfigInfo.optString("name", "");
                            if (!name.isEmpty()) {
                                AntMemberTaskListMap.add(name, name);
                            }
                        }
                    }
                }
                
                //保存任务到配置文件
                AntMemberTaskListMap.save();
                if (!AntMemberAdTaskListMap.getMap().isEmpty()) {
                    AntMemberAdTaskListMap.save();
                }
                Log.record("同步任务🉑会员任务列表");
                
                //自动按模块初始化设定调整黑名单和白名单
                if (AutoAntMemberTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields antMember = config.getModelFieldsMap().get("AntMember");
                    SelectModelField AntMemberTaskList = (SelectModelField) antMember.get("AntMemberTaskList");
                    if (AntMemberTaskList == null) {
                        return;
                    }
                    
                    // 2~4. 批量写回黑/白名单并保存
                    MessageUtil.syncTaskBlackList("会员任务", "AntMemberTaskList", blackList, whiteList, AntMemberTaskList);
                }
            }
            //初始化MemberCreditSesameTaskListMap
            MemberCreditSesameTaskListMap.load();
            // 只预置拉黑真实交易/履约类（下单/租赁/订酒店/回收/雇佣/付钱/查车）：没真做就申报"完成"属虚假履约，有风控风险
            // 其余任务类型交给任务循环自动完成；预置集登记在 MessageUtil（单一真相，配置页据此标注"默认"）
            blackList = MessageUtil.presetBlackList("AntMember", "MemberCreditSesameTaskList");
            // 可继续添加更多黑名单任务
            
            whiteList = new HashSet<>();
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                MemberCreditSesameTaskListMap.add(task, task);
            }
            
            if (collectSesame) {
                jo = new JSONObject(AntMemberRpcCall.queryHome());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    JSONObject entrance = jo.getJSONObject("entrance");
                    if (entrance.optBoolean("openApp")) {
                        jo = new JSONObject(AntMemberRpcCall.CreditAccumulateStrategyRpcManager());
                        TimeUtil.sleep(300);
                        if (MessageUtil.checkResultCode(TAG, jo)) {
                            if (jo.has("data")) {
                                JSONObject data = jo.getJSONObject("data");
                                if (data.has("completeVOS")) {
                                    JSONArray completeVOS = data.getJSONArray("completeVOS");
                                    for (int i = 0; i < completeVOS.length(); i++) {
                                        JSONObject toCompleteVO = completeVOS.getJSONObject(i);
                                        String title = toCompleteVO.optString("title");
                                        if (title.isEmpty()) {
                                            continue;
                                        }
                                        MemberCreditSesameTaskListMap.add(title, title);
                                    }
                                }
                                if (data.has("toCompleteVOS")) {
                                    JSONArray toCompleteVOS = data.getJSONArray("toCompleteVOS");
                                    for (int i = 0; i < toCompleteVOS.length(); i++) {
                                        JSONObject toCompleteVO = toCompleteVOS.getJSONObject(i);
                                        String title = toCompleteVO.optString("title");
                                        if (title.isEmpty()) {
                                            continue;
                                        }
                                        MemberCreditSesameTaskListMap.add(title, title);
                                    }
                                }
                            }
                        }
                    }
                }
                //保存任务到配置文件
                MemberCreditSesameTaskListMap.save();
                Log.record("同步任务🉑会员芝麻信用任务芝麻粒列表");
                
                //自动按模块初始化设定调整黑名单和白名单
                if (AutoMemberCreditSesameTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields antMember = config.getModelFieldsMap().get("AntMember");
                    SelectModelField MemberCreditSesameTaskList = (SelectModelField) antMember.get("MemberCreditSesameTaskList");
                    if (MemberCreditSesameTaskList == null) {
                        return;
                    }
                    
                    // 2~4. 批量写回黑/白名单并保存
                    MessageUtil.syncTaskBlackList("会员芝麻信用任务芝麻粒", "MemberCreditSesameTaskList", blackList, whiteList, MemberCreditSesameTaskList);
                }
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "initMemberTaskListMap err:", t);
        }
    }
    
    private void memberSign() {
        try {
            if (!Status.hasFlagToday("member::sign")) {
                JSONObject jo = new JSONObject(AntMemberRpcCall.queryMemberSigninCalendar());
                TimeUtil.sleep(500);
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    if (jo.getBoolean("autoSignInSuccess")) {
                        Log.other("会员任务📅签到[坚持" + jo.getString("signinSumDay") + "天]#获得[" + jo.getString("signinPoint") + "积分]");
                    }
                    Status.flagToday("member::sign");
                }
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "memberSign err:", t);
        }
    }
    
    /**
     * 列表状态探针：重拉积分凭证列表（该列表是"待领"清单），按 id 逐页匹配——
     * 仍在列表＝确实没领到；消失了＝已领到。一次都没拉到则返回 UNKNOWN（不据此判"已领到"）。
     */
    private static TaskAttemptPolicy.ProbeResult probePointCertStatus(int page, int pageSize, String certId) {
        try {
            boolean anyFetched = false;
            for (int p = 1; p <= page; p++) {
                JSONObject jo = new JSONObject(AntMemberRpcCall.queryPointCert(p, pageSize));
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    continue;
                }
                anyFetched = true;
                JSONArray certList = jo.optJSONArray("certList");
                if (certList == null) {
                    continue;
                }
                for (int i = 0; i < certList.length(); i++) {
                    JSONObject cert = certList.optJSONObject(i);
                    if (cert != null && certId.equals(cert.optString("id"))) {
                        return TaskAttemptPolicy.ProbeResult.TODO;
                    }
                }
            }
            // 凭证已不在待领清单里：视为已领到；但一次都没拉到列表时不可据此判定
            return anyFetched ? TaskAttemptPolicy.ProbeResult.RECEIVED : TaskAttemptPolicy.ProbeResult.UNKNOWN;
        } catch (Throwable t) {
            Log.err(TAG, "probePointCertStatus err:", t);
            return TaskAttemptPolicy.ProbeResult.UNKNOWN;
        }
    }

    private boolean queryPointCert(int page, int pageSize) {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryPointCert(page, pageSize));
            TimeUtil.sleep(500);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                Log.i(TAG, "queryPointCert page=" + page + " 接口返回失败");
                return false;
            }
            boolean hasNextPage = jo.getBoolean("hasNextPage");
            JSONArray jaCertList = jo.getJSONArray("certList");
            Log.i(TAG, "queryPointCert page=" + page + " certList.size=" + jaCertList.length() + " hasNextPage=" + hasNextPage);
            for (int i = 0; i < jaCertList.length(); i++) {
                jo = jaCertList.getJSONObject(i);
                String bizTitle = jo.getString("bizTitle");
                //黑名单任务跳过
                if (AntMemberTaskList.getValue().contains(bizTitle)) {
                    continue;
                }
                String id = jo.getString("id");
                int pointAmount = jo.getInt("pointAmount");
                jo = new JSONObject(AntMemberRpcCall.receivePointByUser(id));
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    Log.other("会员任务🎖️领取[" + bizTitle + "]奖励#获得[" + pointAmount + "积分]");
                } else {
                    // 领奖收口：先按积分凭证列表复核"已领到"，未确认才交自动拉黑（顺序由 TaskAward 固定）
                    // 失败响应另存 final：jo 在循环里会被反复赋值，不能直接被 lambda 捕获
                    final JSONObject failJo = jo;
                    TaskAward.confirmReceivedOrBlackList("会员任务🎖️领取",
                            k -> probePointCertStatus(page, pageSize, id), id, bizTitle,
                            () -> MessageUtil.checkResultCodeAndMarkTaskBlackList("AntMemberTaskList", bizTitle, failJo),
                            msg -> Log.other(msg));
                }
            }
            if (hasNextPage) {
                queryPointCert(page + 1, pageSize);
            }
            return true;
        }
        catch (Throwable t) {
            Log.err(TAG, "queryPointCert err:", t);
            return false;
        }
    }
    
    
    
    // 生活记录
    private void promise() {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.promiseQueryHome());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            jo = jo.getJSONObject("data");
            JSONArray promiseSimpleTemplates = jo.getJSONArray("promiseSimpleTemplates");
            for (int i = 0; i < promiseSimpleTemplates.length(); i++) {
                jo = promiseSimpleTemplates.getJSONObject(i);
                String templateId = jo.getString("templateId");
                String promiseName = jo.getString("promiseName");
                String status = jo.getString("status");
                if ("un_join".equals(status) && promiseList.getValue().contains(templateId)) {
                    promiseJoin(querySingleTemplate(templateId));
                }
                PromiseSimpleTemplateIdMap.add(templateId, promiseName);
            }
            PromiseSimpleTemplateIdMap.save(UserIdMap.getCurrentUid());
        }
        catch (Throwable t) {
            Log.err(TAG, "promise err:", t);
        }
    }
    
    private JSONObject querySingleTemplate(String templateId) {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.querySingleTemplate(templateId));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return null;
            }
            jo = jo.getJSONObject("data");
            JSONObject result = new JSONObject();
            
            result.put("joinFromOuter", false);
            result.put("templateId", jo.getString("templateId"));
            result.put("autoRenewStatus", Boolean.valueOf(jo.getString("autoRenewStatus")));
            
            JSONObject joinGuarantyRule = jo.getJSONObject("joinGuarantyRule");
            joinGuarantyRule.put("selectValue", joinGuarantyRule.getJSONArray("canSelectValues").getString(0));
            joinGuarantyRule.remove("canSelectValues");
            result.put("joinGuarantyRule", joinGuarantyRule);
            
            JSONObject joinRule = jo.getJSONObject("joinRule");
            joinRule.put("selectValue", joinRule.getJSONArray("canSelectValues").getString(0));
            joinRule.remove("joinRule");
            result.put("joinRule", joinRule);
            
            JSONObject periodTargetRule = jo.getJSONObject("periodTargetRule");
            periodTargetRule.put("selectValue", periodTargetRule.getJSONArray("canSelectValues").getString(0));
            periodTargetRule.remove("canSelectValues");
            result.put("periodTargetRule", periodTargetRule);
            
            JSONObject dataSourceRule = jo.getJSONObject("dataSourceRule");
            dataSourceRule.put("selectValue", dataSourceRule.getJSONArray("canSelectValues").getJSONObject(0).getString("merchantId"));
            dataSourceRule.remove("canSelectValues");
            result.put("dataSourceRule", dataSourceRule);
            return result;
        }
        catch (Throwable t) {
            Log.err(TAG, "querySingleTemplate err:", t);
        }
        return null;
    }
    
    private void promiseJoin(JSONObject data) {
        if (data == null) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.promiseJoin(data));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            jo = jo.getJSONObject("data");
            String promiseName = jo.getString("promiseName");
            Log.other("生活记录📝加入[" + promiseName + "]");
        }
        catch (Throwable t) {
            Log.err(TAG, "promiseJoin err:", t);
        }
    }
    
    // 查询持续做明细任务
    private JSONObject promiseQueryDetail(String recordId) throws JSONException {
        JSONObject jo = new JSONObject(AntMemberRpcCall.promiseQueryDetail(recordId));
        if (!jo.optBoolean("success")) {
            return null;
        }
        return jo;
    }
    
    

    
    private void goldTicket() {
        try {
            boolean doSignIn = enableGoldTicketSign.getValue();
            boolean doTask = enableGoldTicketBrowse.getValue();
            boolean doConsume = enableGoldTicketConsume.getValue();
            if (!doSignIn && !doTask && !doConsume) {
                return;
            }
            // 收取随签到：待收的票是签到等行为攒下的，归在「签到」这一路
            boolean needCollect = doSignIn && !Status.hasFlagToday("goldTicket::collect");
            boolean needSignIn = doSignIn && !Status.hasFlagToday("goldTicket::sign");
            boolean needHomeCheck = doTask && !Status.hasFlagToday("goldTicket::home");
            boolean needWelfare = doTask && !Status.hasFlagToday("goldTicket::welfare");
            boolean needConsume = doConsume && !Status.hasFlagToday("goldTicket::consume");
            if (!needCollect && !needSignIn && !needHomeCheck && !needWelfare && !needConsume) {
                Log.i("黄金票🎫[今日已处理，跳过]");
                return;
            }
            Log.i("黄金票🎫[开始执行]");
            // 先把预置黑名单落进字段值：任务处理是在循环里读该字段的，晚一步就会先发请求再拉黑
            syncGoldTicketCandidates(null);
            JSONObject home = null;
            if (needCollect || needSignIn || needHomeCheck) {
                home = queryGoldTicketHome();
            }
            if (needCollect || needSignIn) {
                if (home == null) {
                    Log.error("黄金票🎫[首页查询失败]跳过签到与收取");
                } else {
                    if (needCollect) {
                        doGoldTicketCollect(home);
                        Status.flagToday("goldTicket::collect");
                    }
                    if (needSignIn && doGoldTicketSignIn(home)) {
                        Status.flagToday("goldTicket::sign");
                    }
                }
            }
            // 首页与福利中心的任务列表有重叠，本轮已提交过的任务名记在这里，避免重复提交
            Set<String> handledTitles = new HashSet<>();
            if (needHomeCheck) {
                if (home == null) {
                    Log.error("黄金票🎫[首页查询失败]跳过任务扫描");
                } else if (handleGoldTicketHomeTasks(home, handledTitles)) {
                    Status.flagToday("goldTicket::home");
                }
            }
            if (needWelfare) {
                if (handleGoldTicketWelfareTasks(handledTitles)) {
                    Status.flagToday("goldTicket::welfare");
                }
            }
            if (needConsume) {
                doGoldTicketConsume();
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 黄金票系接口只返回 success:true（无 desc / resultCode），而 checkResultCode 要求 desc="处理成功"，
     * 会把这些响应全部误判为失败；故这里以 success/isSuccess 为准，再回退通用判定。
     */
    private static boolean goldTicketOk(String tag, JSONObject jo) {
        if (jo == null) {
            return false;
        }
        if (jo.optBoolean("success") || jo.optBoolean("isSuccess")) {
            return true;
        }
        return MessageUtil.checkResultCode(tag, jo);
    }

    private JSONObject queryGoldTicketHome() {
        try {
            String res = AntMemberRpcCall.queryGoldTicketHome();
            if (res == null || res.isEmpty()) {
                return null;
            }
            JSONObject jo = new JSONObject(res);
            if (!goldTicketOk(TAG, jo)) {
                return null;
            }
            return jo;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    private JSONObject getGoldTicketAssetInfo(JSONObject home) {
        if (home == null) {
            return null;
        }
        // 首页响应在 result.upsertData.assetInfo 下，旧的 result.assetInfo 作兜底
        JSONObject asset = home.optJSONObject("assetInfo");
        if (asset == null) {
            JSONObject result = home.optJSONObject("result");
            if (result != null) {
                JSONObject upsertData = result.optJSONObject("upsertData");
                if (upsertData != null) {
                    asset = upsertData.optJSONObject("assetInfo");
                }
                if (asset == null) {
                    asset = result.optJSONObject("assetInfo");
                }
            }
        }
        return asset;
    }

    private boolean doGoldTicketSignIn(JSONObject home) {
        try {
            JSONObject assetInfo = getGoldTicketAssetInfo(home);
            boolean canSign = assetInfo != null && assetInfo.optBoolean("canSign", false);
            if (!canSign) {
                Log.i("黄金票🎫[今日已签到]");
                return true;
            }
            Log.i("黄金票🎫[准备签到]");
            boolean signSuccess = false;
            int collectCount = doGoldTicketIndexCollect("签到尝试");
            JSONObject refreshed = queryGoldTicketHome();
            if (refreshed != null) {
                JSONObject ra = getGoldTicketAssetInfo(refreshed);
                boolean stillCanSign = ra != null && ra.optBoolean("canSign", false);
                if (!stillCanSign) {
                    refreshGoldTicketWelfareCenter("首页收取签到");
                    Log.other(collectCount > 0 ? "黄金票🎫[签到成功]#通过首页收取完成签到" : "黄金票🎫[签到成功]");
                    signSuccess = true;
                }
            }
            if (!signSuccess) {
                String signRes = AntMemberRpcCall.welfareCenterTrigger("SIGN");
                if (signRes != null && !signRes.isEmpty()) {
                    JSONObject signJson = new JSONObject(signRes);
                    if (goldTicketOk(TAG, signJson)) {
                        JSONObject signResult = signJson.optJSONObject("result");
                        String amount = "";
                        if (signResult != null) {
                            JSONObject prize = signResult.optJSONObject("prize");
                            if (prize != null) {
                                amount = prize.optString("amount");
                            }
                        }
                        refreshGoldTicketWelfareCenter("签到");
                        JSONObject refreshed2 = queryGoldTicketHome();
                        JSONObject ra2 = refreshed2 != null ? getGoldTicketAssetInfo(refreshed2) : null;
                        signSuccess = refreshed2 != null && (ra2 == null || !ra2.optBoolean("canSign", false));
                        if (signSuccess || (amount != null && !amount.isEmpty())) {
                            Log.other(amount != null && !amount.isEmpty()
                                    ? "黄金票🎫[签到成功]#获得[" + amount + "]" : "黄金票🎫[签到成功]");
                            signSuccess = true;
                        }
                    }
                }
            }
            if (!signSuccess) {
                Log.error("黄金票🎫[签到失败]未找到可用签到返回");
            }
            return signSuccess;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return false;
        }
    }

    private int doGoldTicketIndexCollect(String source) {
        String needleResponse = AntMemberRpcCall.goldTicketIndexCollect();
        if (needleResponse != null && !needleResponse.isEmpty()) {
            return logGoldTicketCollectResponse(needleResponse, source);
        }
        return logGoldTicketCollectResponse(AntMemberRpcCall.goldBillCollect(), source + "-旧版兼容");
    }

    private boolean refreshGoldTicketWelfareCenter(String source) {
        try {
            String updateResponse = AntMemberRpcCall.welfareCenterUpdate(9);
            if (updateResponse == null || updateResponse.isEmpty()) {
                Log.error("黄金票🎫[" + source + "]福利中心刷新无返回");
                return false;
            }
            JSONObject updateJson = new JSONObject(updateResponse);
            if (!goldTicketOk(TAG, updateJson)) {
                Log.error("黄金票🎫[" + source + "]福利中心刷新失败："
                        + updateJson.optString("resultDesc", updateJson.optString("memo")));
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return false;
        }
    }

    private int logGoldTicketCollectResponse(String response, String source) {
        if (response == null || response.isEmpty()) {
            return 0;
        }
        try {
            JSONObject collectJson = new JSONObject(response);
            if (!goldTicketOk(TAG, collectJson)) {
                String message = collectJson.optString("resultDesc", collectJson.optString("memo"));
                if (message != null && !message.isEmpty()) {
                    Log.other("黄金票🎫[" + source + "]" + message);
                }
                return 0;
            }
            JSONObject result = collectJson.optJSONObject("result");
            if (result == null) {
                return 0;
            }
            JSONArray collectedList = result.optJSONArray("collectedList");
            if (collectedList == null) {
                return 0;
            }
            int count = 0;
            for (int i = 0; i < collectedList.length(); i++) {
                String item = collectedList.optString(i);
                if (item == null || item.isEmpty()) {
                    continue;
                }
                count++;
                Log.other("黄金票🎫[" + source + "]#" + item);
            }
            if (count > 0) {
                JSONObject collectedCamp = result.optJSONObject("collectedCamp");
                String totalAmount = collectedCamp != null ? collectedCamp.optString("amount") : "";
                if (totalAmount != null && !totalAmount.isEmpty()) {
                    Log.other("黄金票🎫[" + source + "]#本次共得[" + totalAmount + "]份");
                }
            }
            return count;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return 0;
        }
    }

    private void doGoldTicketCollect(JSONObject home) {
        try {
            JSONObject assetInfo = getGoldTicketAssetInfo(home);
            JSONObject toBeCollectInfo = assetInfo != null ? assetInfo.optJSONObject("toBeCollectInfo") : null;
            int totalProfitValue = toBeCollectInfo != null ? toBeCollectInfo.optInt("totalProfitValue", 0) : 0;
            if (totalProfitValue <= 0) {
                return;
            }
            int collectCount = doGoldTicketIndexCollect("场景收取");
            if (collectCount == 0) {
                Log.i("黄金票🎫[场景收取]暂无可领取奖励");
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    private boolean isGoldTicketRewardReady(String status) {
        return "TO_RECEIVE".equals(status) || "WAIT_RECEIVE".equals(status)
                || "FINISHED".equals(status) || "COMPLETE".equals(status);
    }

    private boolean isGoldTicketSignupRequired(String status) {
        return "NONE_SIGNUP".equals(status) || "SIGNUP_EXPIRED".equals(status);
    }

    /**
     * 任务候选同步为黑名单选项。原先是写死 6 个 taskId 的白名单，活动一换任务就全部跳过；
     * 改为随每次列表请求刷新候选，准入交给状态与黑名单生命周期。
     */
    private void syncGoldTicketCandidates(JSONArray list) {
        try {
            GoldTicketTaskListMap.load();
            // 预置黑名单登记在 MessageUtil（单一真相，配置页据此标注"默认"）
            Set<String> preset = MessageUtil.presetBlackList("AntMember", "GoldTicketTaskList");
            int count = 0;
            for (String title : preset) {
                if (GoldTicketTaskListMap.get(title) == null) {
                    GoldTicketTaskListMap.add(title, title);
                    count++;
                }
            }
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject task = list.optJSONObject(i);
                    if (task == null) {
                        continue;
                    }
                    String title = task.optString("title");
                    if (title.isEmpty() || GoldTicketTaskListMap.get(title) != null) {
                        continue;
                    }
                    GoldTicketTaskListMap.add(title, title);
                    count++;
                }
            }
            if (count > 0) {
                GoldTicketTaskListMap.save();
                AlipayGoldTicketTaskList.clear();
                Log.record("同步任务🉑黄金票任务列表[新增" + count + "]");
            }
            // 让预置项出现在配置页勾选列表里（注入进程写盘、配置页独立进程另读）
            MessageUtil.syncTaskBlackList("黄金票任务", "GoldTicketTaskList",
                    preset, new HashSet<>(), GoldTicketTaskList);
        } catch (Throwable t) {
            Log.err(TAG, "syncGoldTicketCandidates err:", t);
        }
    }

    private JSONArray extractGoldTicketHomeTodoTasks(JSONObject home) {
        // 首页响应在 result.upsertData.task 下，旧的 result.task 作兜底
        JSONObject task = home.optJSONObject("task");
        if (task == null) {
            JSONObject result = home.optJSONObject("result");
            if (result != null) {
                JSONObject upsertData = result.optJSONObject("upsertData");
                if (upsertData != null) {
                    task = upsertData.optJSONObject("task");
                }
                if (task == null) {
                    task = result.optJSONObject("task");
                }
            }
        }
        if (task == null) {
            return new JSONArray();
        }
        JSONObject tasks = task.optJSONObject("tasks");
        if (tasks == null) {
            return new JSONArray();
        }
        JSONArray todo = tasks.optJSONArray("todo");
        return todo != null ? todo : new JSONArray();
    }

    private JSONArray queryGoldTicketWelfareTodoTasks() {
        try {
            String welfareResponse = AntMemberRpcCall.queryWelfareHome();
            if (welfareResponse == null || welfareResponse.isEmpty()) {
                return null;
            }
            JSONObject welfareJson = new JSONObject(welfareResponse);
            if (!goldTicketOk(TAG, welfareJson)) {
                return null;
            }
            JSONObject result = welfareJson.optJSONObject("result");
            if (result == null) {
                return null;
            }
            JSONObject goldbillTasks = result.optJSONObject("goldbillTasks");
            if (goldbillTasks == null) {
                return new JSONArray();
            }
            JSONArray todo = goldbillTasks.optJSONArray("todo");
            return todo != null ? todo : new JSONArray();
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    /**
     * 推送任务。返回 null 表示未完成，且响应体已放进 {@link #goldTicketPushLastResponse}
     * 供拉黑判定使用（拉黑逻辑读的是响应里的错误文案，丢了响应就无法计数）。
     */
    private JSONObject pushGoldTicketTask(String taskId) {
        goldTicketPushLastResponse = null;
        try {
            String response = AntMemberRpcCall.taskQueryPush(taskId);
            if (response == null || response.isEmpty()) {
                return null;
            }
            JSONObject result = new JSONObject(response);
            goldTicketPushLastResponse = result;
            if (!goldTicketOk(TAG, result)) {
                return null;
            }
            JSONObject r = result.optJSONObject("result");
            JSONObject pushResult = r != null ? r.optJSONObject("pushResult") : null;
            boolean done = pushResult != null ? pushResult.optBoolean("done", true) : true;
            return done ? result : null;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    /** 最近一次推送的响应体，仅用于拉黑判定 */
    private JSONObject goldTicketPushLastResponse;

    /**
     * 未完成项计数；列表自身可能含同名重复项，按任务名去重后再数。
     * 本轮已提交过的与黑名单内的都不计入：前者推送后不会立即置 RECEIVE_SUCCESS，
     * 后者是主动不做；两者若计入会永远残留、当日完成标记永不置位，导致每轮重扫重发。
     */
    private int countGoldTicketPendingAutoTasks(JSONArray todo, Set<String> handledTitles, Set<String> blackList) {
        if (todo == null || todo.length() == 0) {
            return 0;
        }
        Set<String> titles = new HashSet<>();
        for (int i = 0; i < todo.length(); i++) {
            JSONObject task = todo.optJSONObject(i);
            if (task == null) {
                continue;
            }
            if (!"RECEIVE_SUCCESS".equalsIgnoreCase(task.optString("taskProcessStatus"))) {
                String title = task.optString("title", task.optString("taskId"));
                if (handledTitles != null && handledTitles.contains(title)) {
                    continue;
                }
                if (blackList != null && blackList.contains(title)) {
                    continue;
                }
                titles.add(title);
            }
        }
        return titles.size();
    }

    /**
     * 处理黄金票任务列表（首页 / 福利中心共用）。
     * 准入按状态而不按 taskId：未完成就尝试报名+完成，失败交自动黑名单，
     * 由它按「连续 3 次命中」的周期决定何时停手。
     */
    private int processGoldTicketTasks(JSONArray todo, String source, Set<String> blackList) {
        return processGoldTicketTasks(todo, source, blackList, null);
    }

    private int processGoldTicketTasks(JSONArray todo, String source, Set<String> blackList, Set<String> handledTitles) {
        if (todo == null || todo.length() == 0) {
            return 0;
        }
        syncGoldTicketCandidates(todo);
        int pending = 0;
        for (int i = 0; i < todo.length(); i++) {
            JSONObject task = todo.optJSONObject(i);
            if (task == null) {
                continue;
            }
            String taskId = task.optString("taskId");
            String title = task.optString("title", taskId);
            String status = task.optString("taskProcessStatus");
            if (taskId.isEmpty() || title.isEmpty()) {
                continue;
            }
            if ("RECEIVE_SUCCESS".equalsIgnoreCase(status)) {
                continue;
            }
            // 黑名单跳过静默：条目由用户在配置页维护，内容恒定，不打日志
            if (blackList != null && blackList.contains(title)) {
                continue;
            }
            // 两处列表重叠时只提交一次
            if (handledTitles != null && !handledTitles.add(title)) {
                continue;
            }
            String amount = task.optString("amount");
            if (isGoldTicketSignupRequired(status)) {
                String triggerRes = AntMemberRpcCall.goldBillTaskTrigger(taskId);
                JSONObject triggerJson = parseGoldTicket(triggerRes);
                if (!goldTicketOk(TAG, triggerJson)) {
                    markGoldTicketBlackList(source, title, triggerJson);
                    pending++;
                    continue;
                }
                Log.i("黄金票🎫[" + source + "任务报名成功]#" + title);
                TimeUtil.sleep(500);
            }
            // 推送响应只有 success，没有回执也没有金额：这里只能记「已提交」，
            // 是否真完成交给随后的复查（复查未完成即进黑名单）
            if (pushGoldTicketTask(taskId) != null) {
                Log.other("黄金票🎫[" + source + "任务已提交]#" + title
                        + (amount.isEmpty() ? "" : "#可得[" + amount + "份]"));
            } else {
                markGoldTicketBlackList(source, title, goldTicketPushLastResponse);
                pending++;
            }
        }
        return pending;
    }

    /**
     * 任务未完成时交自动黑名单计数。响应体为空（连报文都没拿到）时无法判定原因，
     * 只记日志不计数，避免网络抖动被当成"任务不可做"而误拉黑。
     */
    private void markGoldTicketBlackList(String source, String title, JSONObject jo) {
        Log.record("黄金票📋[" + source + "任务未完成]#" + title);
        if (jo == null) {
            return;
        }
        MessageUtil.checkResultCodeAndMarkTaskBlackList("GoldTicketTaskList", title, jo);
    }

    /** requestString 在请求体非法/宿主解析失败时返回 null，统一兜底 */
    private JSONObject parseGoldTicket(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            Log.err(TAG, "黄金票响应解析失败:" + StringUtil.truncate(raw, 200), t);
            return null;
        }
    }

    private boolean handleGoldTicketHomeTasks(JSONObject home, Set<String> handledTitles) {
        try {
            JSONArray todo = extractGoldTicketHomeTodoTasks(home);
            processGoldTicketTasks(todo, "首页", GoldTicketTaskList.getValue(), handledTitles);
            JSONObject refreshed = queryGoldTicketHome();
            if (refreshed == null) {
                Log.other("黄金票🎫[首页任务复查失败]暂不写入今日完成");
                return false;
            }
            int pending = countGoldTicketPendingAutoTasks(extractGoldTicketHomeTodoTasks(refreshed), handledTitles, GoldTicketTaskList.getValue());
            if (pending > 0) {
                Log.i("黄金票🎫[首页任务]#保留" + pending + "项待重试");
            }
            return pending == 0;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return false;
        }
    }

    /**
     * 福利中心与首页的任务列表有重叠（同一条任务两处都在列），
     * 上一路已提交过的不再重复提交一遍，否则同一条会被打两次且多挨一次风控。
     */
    private boolean handleGoldTicketWelfareTasks(Set<String> handledTitles) {
        try {
            JSONArray todo = queryGoldTicketWelfareTodoTasks();
            if (todo == null) {
                Log.error("黄金票🎫[福利中心任务查询失败]");
                return false;
            }
            processGoldTicketTasks(todo, "福利中心", GoldTicketTaskList.getValue(), handledTitles);
            JSONArray refreshed = queryGoldTicketWelfareTodoTasks();
            if (refreshed == null) {
                Log.other("黄金票🎫[福利中心任务复查失败]暂不写入今日完成");
                return false;
            }
            int pendingRetry = countGoldTicketPendingAutoTasks(refreshed, handledTitles, GoldTicketTaskList.getValue());
            if (pendingRetry > 0) {
                Log.i("黄金票🎫[福利中心任务]#保留" + pendingRetry + "项待重试");
            }
            return pendingRetry == 0;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return false;
        }
    }

    private void doGoldTicketConsume() {
        boolean consumeDone = false;
        try {
            Log.i("黄金票🎫[准备检查余额及提取]");
            String queryRes = AntMemberRpcCall.queryConsumeHome();
            if (queryRes == null || queryRes.isEmpty()) {
                return;
            }
            JSONObject queryJson = new JSONObject(queryRes);
            if (!goldTicketOk(TAG, queryJson)) {
                return;
            }
            JSONObject result = queryJson.optJSONObject("result");
            if (result == null) {
                return;
            }
            JSONObject assetInfo = result.optJSONObject("assetInfo");
            if (assetInfo == null) {
                return;
            }
            int availableAmount = assetInfo.optInt("availableAmount", 0);
            int minExchangeAmount = assetInfo.optInt("minExchangeAmount", 100);
            int exchangeAmountUnit = assetInfo.optInt("exchangeAmountUnit", minExchangeAmount);
            if (exchangeAmountUnit < 1) {
                exchangeAmountUnit = 1;
            }
            int extractAmount = (availableAmount / exchangeAmountUnit) * exchangeAmountUnit;
            if (extractAmount < minExchangeAmount) {
                Log.other("黄金票🎫[余额不足]#当前[" + availableAmount + "]最低需[" + minExchangeAmount + "]");
                consumeDone = true;
                return;
            }
            String productId = "";
            JSONObject product = result.optJSONObject("product");
            if (product != null) {
                productId = product.optString("productId");
            } else if (result.has("productList")) {
                JSONArray productList = result.optJSONArray("productList");
                if (productList != null && productList.length() > 0) {
                    productId = productList.optJSONObject(0).optString("productId");
                }
            } else if (assetInfo.has("mainExchangePrizeList")) {
                JSONArray list = assetInfo.optJSONArray("mainExchangePrizeList");
                if (list != null && list.length() > 0) {
                    productId = list.optJSONObject(0).optString("bizNo");
                }
            } else if (assetInfo.has("footerExchangePrizeList")) {
                JSONArray list = assetInfo.optJSONArray("footerExchangePrizeList");
                if (list != null && list.length() > 0) {
                    productId = list.optJSONObject(0).optString("bizNo");
                }
            } else {
                JSONObject backupPrize = assetInfo.optJSONObject("backupPrize");
                if (backupPrize != null && "GOLD".equalsIgnoreCase(backupPrize.optString("prizeType"))) {
                    productId = backupPrize.optString("bizNo");
                }
            }
            if (productId == null || productId.isEmpty()) {
                Log.error("黄金票🎫[提取异常]未找到有效的基金ID");
                return;
            }
            int bonusAmount = 0;
            JSONObject bonusInfo = result.optJSONObject("bonusInfo");
            if (bonusInfo != null) {
                bonusAmount = bonusInfo.optInt("bonusAmount", 0);
            }
            String exchangeMoney = null;
            JSONObject calcInfo = result.optJSONObject("calcInfo");
            if (calcInfo != null) {
                exchangeMoney = calcInfo.optString("exchangeMoney");
            }
            if (exchangeMoney == null || exchangeMoney.isEmpty()) {
                exchangeMoney = String.format("%.2f", (double) extractAmount / 1000.0);
            }
            Log.i("黄金票🎫[开始提取]#计划[" + extractAmount + "份]预计[" + exchangeMoney
                    + "元]持有[" + availableAmount + "]");
            String submitRes = AntMemberRpcCall.submitConsume(extractAmount, productId, bonusAmount);
            if (submitRes == null || submitRes.isEmpty()) {
                Log.error("黄金票🎫[提取失败]接口无返回");
                return;
            }
            JSONObject submitJson = new JSONObject(submitRes);
            if (!goldTicketOk(TAG, submitJson)) {
                String desc = submitJson.optString("resultDesc", submitJson.optString("memo"));
                if (desc != null && !desc.isEmpty()) {
                    Log.error("黄金票🎫[提取失败]" + desc);
                }
                return;
            }
            JSONObject submitResult = submitJson.optJSONObject("result");
            String writeOffNo = submitResult != null ? submitResult.optString("writeOffNo") : "";
            String successTitle = submitResult != null ? submitResult.optString("successTitle") : "";
            if ((writeOffNo != null && !writeOffNo.isEmpty())
                    || (successTitle != null && successTitle.contains("成功"))) {
                Log.other("黄金票🎫[提取成功]#" + exchangeMoney + "元#" + extractAmount + "份");
                consumeDone = true;
            } else {
                Log.error("黄金票🎫[提取失败]未返回核销码");
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        } finally {
            if (consumeDone) {
                Status.flagToday("goldTicket::consume");
            }
        }
    }
    
    /**
     * 芝麻分任务处理（每日问答、公益任务、芭芭农场施肥等）
     */
    private void handleGrowthGuideTasks() {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryHome());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject root = new JSONObject(AntMemberRpcCall.queryGrowthBehaviorToDoList());
            if (!MessageUtil.checkResultCode(TAG, root)) {
                return;
            }
            
            // 待处理任务列表
            JSONArray toDoList = root.optJSONArray("toDoList");
            int toDoCount = toDoList == null ? 0 : toDoList.length();
            if (toDoList == null || toDoCount == 0) {
                return;
            }
            
            for (int i = 0; i < toDoList.length(); i++) {
                JSONObject task = toDoList.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                
                String behaviorId = task.optString("behaviorId", "");
                String title = task.optString("title", "");
                String status = task.optString("status", "");
                String subTitle = task.optString("subTitle", "");
                
                // 公益类任务（待领取）
                if ("wait_receive".equals(status)) {
                    String openResp = AntMemberRpcCall.openBehaviorCollect(behaviorId);
                    JSONObject openJo = new JSONObject(openResp);
                    if (MessageUtil.checkResultCode(TAG, openJo)) {
                        Log.other("攒芝麻分🧾[任务领取]#" + title);
                    }
                    continue;
                }
                
                // 每日问答
                if ("meiriwenda".equals(behaviorId) && "wait_doing".equals(status)) {
                    if (subTitle.contains("今日已参与")) {
                        Log.i("攒芝麻分🧾[每日问答] " + subTitle + "（跳过答题）");
                        continue;
                    }
                    
                    // 查询题目
                    JSONObject quizJo = new JSONObject(AntMemberRpcCall.queryDailyQuiz(behaviorId));
                    if (!MessageUtil.checkSuccess(TAG, quizJo)) {
                        continue;
                    }
                    JSONObject data = quizJo.optJSONObject("data");
                    if (data == null) {
                        continue;
                    }
                    
                    JSONObject qVo = data.optJSONObject("questionVo");
                    if (qVo == null) {
                        continue;
                    }
                    
                    JSONObject rightAnswer = qVo.optJSONObject("rightAnswer");
                    if (rightAnswer == null) {
                        continue;
                    }
                    
                    long bizDate = data.optLong("bizDate", 0L);
                    String questionId = qVo.optString("questionId", "");
                    String questionContent = qVo.optString("questionContent", "");
                    String answerId = rightAnswer.optString("answerId", "");
                    String answerContent = rightAnswer.optString("answerContent", "");
                    
                    if (bizDate <= 0 || questionId.isEmpty() || answerId.isEmpty()) {
                        continue;
                    }
                    
                    // 提交答案
                    JSONObject pushJo = new JSONObject(AntMemberRpcCall.pushDailyQuizAnswer(behaviorId, bizDate, answerId, questionId, "RIGHT"));
                    if (MessageUtil.checkResultCode(TAG, pushJo)) {
                        Log.other("攒芝麻分🎖️[每日答题成功] " + StringUtil.truncate(questionContent, 200)
                                + " | 答案=" + StringUtil.truncate(answerContent, 200) + "(" + answerId + ")"
                                + (subTitle.isEmpty() ? "" : " | " + subTitle));
                    }
                }
                
                // 视频问答
                if ("shipingwenda".equals(behaviorId) && "wait_doing".equals(status)) {
                    long bizDate = System.currentTimeMillis();
                    String questionId = "question3";
                    String answerId = "A";
                    String answerType = "RIGHT";
                    
                    jo = new JSONObject(AntMemberRpcCall.pushDailyQuizAnswer(behaviorId, bizDate, answerId, questionId, answerType));
                    
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        Log.other("攒芝麻分🎖️[视频问答提交成功]");
                    }
                }
                
                // 芭芭农场施肥
                if ("babanongchang_7d".equals(behaviorId) && "wait_doing".equals(status)) {
                    
                    // 获取WUA
                    String wua = new AntOrchard().getWua();
                    String source = "DNHZ_NC_zhimajingnangSF";
                    
                    JSONObject spreadManureData = new JSONObject(AntOrchardRpcCall.orchardSpreadManure("main", false, wua));
                    
                    if (!"100".equals(spreadManureData.optString("resultCode"))) {
                        continue;
                    }
                    
                    String taobaoDataStr = spreadManureData.optString("taobaoData", "");
                    if (taobaoDataStr.isEmpty()) {
                        continue;
                    }
                    
                    JSONObject spreadTaobaoData = new JSONObject(taobaoDataStr);
                    
                    JSONObject currentStage = spreadTaobaoData.optJSONObject("currentStage");
                    if (currentStage == null) {
                        Log.error(TAG + "GrowthGuideTasks" + "芭芭农场[缺少currentStage]");
                        continue;
                    }
                    
                    String stageText = currentStage.optString("stageText", "");
                    JSONObject statistics = spreadTaobaoData.optJSONObject("statistics");
                    int dailyAppWateringCount = statistics == null ? 0 : statistics.optInt("dailyAppWateringCount", 0);
                    
                    Log.farm("芭芭农场🌳施肥" + dailyAppWateringCount + "次[" + stageText + "]");
                    Log.other("攒芝麻分🎖️芭芭农场施肥[" + title + "]已施肥" + dailyAppWateringCount + "次");
                    
                }
            }
        }
        catch (Throwable e) {
            Log.printStackTrace(TAG + ".handleGrowthGuideTasks", e);
        }
    }

    
    public static void queryAndCollect() {
        try {
            // 1. 查询进度球状态
            String queryResp = AntMemberRpcCall.queryScoreProgress();
            if (queryResp == null || queryResp.isEmpty()) {
                return;
            }
            
            JSONObject json = new JSONObject(queryResp);
            
            // 检查 success
            if (!MessageUtil.checkSuccess(TAG, json)) {
                return;
            }
            
            JSONObject totalWait = json.optJSONObject("totalWaitProcessVO");
            if (totalWait == null) {
                return;
            }
            
            JSONArray idList = totalWait.optJSONArray("totalProgressIdList");
            if (idList == null || idList.length() == 0) {
                return;
            }
            
            // 直接传 JSONArray
            String collectResp = AntMemberRpcCall.collectProgressBall(idList);
            if (collectResp == null) {
                return;
            }
            
            JSONObject collectJson = new JSONObject(collectResp);
            int collectedAccelerateProgress = collectJson.optInt("collectedAccelerateProgress", -1);
            int currentAccelerateValue = collectJson.optInt("currentAccelerateValue", 0);
            int totalAccelerateProgress = collectJson.optInt("totalAccelerateProgress", 0);
            Log.other("攒芝麻分🎁领取#本次加速进度:" + collectedAccelerateProgress + "(总" + totalAccelerateProgress + "%)加速倍率:" + currentAccelerateValue);
        }
        catch (JSONException e) {
            Log.printStackTrace(TAG + "queryAndCollect JSON err", e);
        }
        catch (Exception e) {
            Log.printStackTrace(TAG + "queryAndCollect err", e);
        }
    }
    
    /**
     * 收取黄金票
     */
    private void goldBillCollect(String signInfo) {
        try {
            String str = AntMemberRpcCall.goldBillCollect(signInfo);
            JSONObject jsonObject = new JSONObject(str);
            if (!jsonObject.optBoolean("success")) {
                Log.i(TAG + ".goldBillCollect.goldBillCollect", jsonObject.optString("resultDesc"));
                return;
            }
            JSONObject object = jsonObject.getJSONObject("result");
            JSONArray jsonArray = object.getJSONArray("collectedList");
            int length = jsonArray.length();
            if (length == 0) {
                return;
            }
            for (int i = 0; i < length; i++) {
                Log.other("黄金票🎫[" + jsonArray.getString(i) + "]");
            }
            Log.other("黄金票🏦本次总共获得[" + JsonUtil.getValueByPath(object, "collectedCamp.amount") + "]");
        }
        catch (Throwable th) {
            Log.err(TAG, "signIn err:", th);
        }
    }
    //游戏中心任务
    
    /**
     * 批量领取玩乐豆
     */
    public static void batchReceivePointBall() {
        try {
            JSONObject jsonObject = new JSONObject(AntMemberRpcCall.batchReceivePointBall());
            if (MessageUtil.checkSuccess(TAG, jsonObject)) {
                JSONObject dataObj = jsonObject.getJSONObject("data");
                String totalAmount = dataObj.getString("totalAmount");
                Log.other("游戏中心🎮批量领取#获得[" + totalAmount + "玩乐豆]");
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "batchReceivePointBall err:", t);
        }
    }
    
    /**
     * 每日签到
     *
     * @return 签到是否成功
     */
    public static boolean dailySignIn() {
        try {
            JSONObject jsonObject = new JSONObject(AntMemberRpcCall.continueSignIn());
            if (MessageUtil.checkSuccess(TAG, jsonObject)) {
                JSONObject toastModule = jsonObject.getJSONObject("data").getJSONObject("autoSignInToastModule");
                String desc = toastModule.getString("desc");
                String beanNum = desc.substring(desc.indexOf("玩乐豆+") + 4);
                Log.other("游戏中心🎮每日签到#获得[" + beanNum + "玩乐豆]");
                return true;
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "continueSignIn err:", t);
        }
        return false;
    }
    
    /**
     * 处理单个任务
     *
     * @param taskObj 任务JSON对象
     */
    public void processTask(JSONObject taskObj) {
        try {
            String actionType = taskObj.optString("actionType");
            String taskId = taskObj.getString("taskId");
            String subTitle = taskObj.getString("subTitle");
            String taskStatus = taskObj.getString("taskStatus");
            int prizeAmount = taskObj.optInt("prizeAmount", 0);

            //黑名单任务跳过
            if (AntMemberTaskList.getValue().contains(subTitle)) {
                return;
            }
            // 底线：交易/支付类任务一律不申报、一次即永久拉黑（不进"满 N 天解禁重试"生命周期）。
            // 本调用点的 TaskAttemptPolicy.Site 未带 bizKey/listField，handle 顶部那条通用判定到不了这里，
            // 故在此显式拦；bizKey 取值与 attemptDoTask 伪申报时一致。
            String txBizKey = taskObj.optString("bizKey", "").trim();
            if (txBizKey.isEmpty()) {
                txBizKey = taskId;
            }
            if (TaskAlternative.isTransactionTask(txBizKey)) {
                MessageUtil.MarkTaskBlackListPermanent("AntMember", "AntMemberTaskList", "会员任务", subTitle);
                Log.other("游戏中心⏭️交易/履约类[" + subTitle + "]#不申报，已永久拉黑");
                return;
            }
            // 任务未完成且需要报名（needSignUp 可能缺字段，用 optBoolean 避免整条任务被异常打断）
            if ("NOT_DONE".equals(taskStatus) && taskObj.optBoolean("needSignUp", false)) {
                JSONObject jsonObject = new JSONObject(AntMemberRpcCall.doTaskSignup(taskId));
                if (!MessageUtil.checkSuccess(TAG, jsonObject)) {
                    //检查并标记黑名单任务
                    MessageUtil.checkResultCodeAndMarkTaskBlackList("AntMemberTaskList", subTitle, jsonObject);
                    return;
                }
            }

            // 各类任务都尝试一次；做不了的当天只试一次（见 TaskAttemptPolicy）
            // 完成与否一律以任务列表为准（探针 probeMemberStatus 复核），响应不可信；
            // 兜底后的核对交给本模块的 verifyPendingTasks，故 listField 传 null（黑名单由 verify 管）
            TaskAttemptPolicy.handle("member::game::" + taskId, subTitle, null,
                    () -> attemptDoTask(taskObj, taskId, subTitle, actionType, prizeAmount), Log::other,
                    new TaskAttemptPolicy.Site(null, "游戏中心", null, taskObj.optString("sceneCode", "").trim(),
                            (k) -> probeMemberStatus(taskId))
                            // 场景码取任务自带（会员各入口不同），不能用庄园的
                            .withScene(taskObj.optString("sceneCode", "").trim()));
        }
        catch (Throwable t) {
            Log.err(TAG, "doTask err:", t);
        }
    }

    /** 游戏中心任务上报：doTaskSend 常被 400000040 拒绝，改用兜底方案；做不了的不写任务黑名单 */
    private Outcome attemptDoTask(JSONObject taskObj, String taskId, String subTitle, String actionType, int prizeAmount) {
        try {
            JSONObject doTaskjo = new JSONObject(AntMemberRpcCall.doTaskSend(taskId));
            if (MessageUtil.checkSuccess(TAG, doTaskjo)) {
                Log.other("游戏中心🎮完成任务[" + subTitle + "]#待领[" + prizeAmount + "玩乐豆]");
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(doTaskjo) || MessageUtil.isServerBusy(doTaskjo)) {
                return Outcome.RETRY;
            }
            // 另一种实现方案（见 TaskAlternative）；version 传本模块原值。
            // bizKey 优先取任务自带的：游戏中心任务流的 bizKey/gameId 与 v3 的 taskId 不是一回事
            String sceneCode = taskObj.optString("sceneCode", "").trim();
            // 本模块自行伪申报并登记同轮核对（verifyPendingTasks），故返回 FORGED 而不是交给通用类
            if (TaskAlternative.hit(doTaskjo, sceneCode)) {
                String bizKey = taskObj.optString("bizKey", "").trim();
                if (bizKey.isEmpty()) {
                    bizKey = taskId;
                }
                // 场景码也用任务自带的（原先走兜底默认的庄园，属跨模组冒用）
                TaskAlternative.trigger(pendingVerifyTasks, taskId, subTitle, bizKey, sceneCode,
                        AntMemberRpcCall.DO_FARM_TASK_VERSION, sceneCode, "游戏中心", msg -> Log.other(msg));
                return Outcome.FORGED;
            }
            Log.other("游戏中心⚠️未完成[" + subTitle + "]");
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntMemberTaskList", subTitle, doTaskjo);
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.err(TAG, "doTaskSend err:", t);
        }
        return Outcome.RETRY;
    }

    /**
     * 核对「已触发但响应不可信」的游戏中心任务：重拉任务列表，仍为 NOT_DONE 的才计入自动拉黑。
     * <p>状态由服务端异步推进，只有列表里的 {@code taskStatus} 才是最终判据。
     */
    private void verifyPendingTasks() {
        TaskAlternative.verify(pendingVerifyTasks, VERIFY_CFG, () -> {
            Set<String> notDone = new LinkedHashSet<>();
            collectNotDoneIds(AntMemberRpcCall.queryModularTaskList(), notDone);
            return notDone;
        });
    }

    /** 列表状态探针：重拉游戏中心任务列表，按 taskId 匹配；仅 NOT_DONE 视为未完成（消失即已完成）。 */
    private static TaskAttemptPolicy.ProbeResult probeMemberStatus(String taskId) {
        // 结构为 data.taskModuleList[].taskList[]；用模块数组作"列表"，statusOf 里再向下找任务
        return TaskAttemptPolicy.probeByNestedList(TAG, AntMemberRpcCall::queryModularTaskList,
                jo -> true,
                jo -> {
                    JSONObject data = jo.optJSONObject("data");
                    return data == null ? null : data.optJSONArray("taskModuleList");
                },
                module -> {
                    JSONArray tasks = module.optJSONArray("taskList");
                    if (tasks == null) {
                        return null;
                    }
                    for (int j = 0; j < tasks.length(); j++) {
                        JSONObject task = tasks.optJSONObject(j);
                        if (task == null || !taskId.equals(task.optString("taskId", "").trim())) {
                            continue;
                        }
                        String status = task.optString("taskStatus", "").trim();
                        // 状态词与通用映射不同，这里显式给出
                        if ("NOT_DONE".equals(status)) {
                            return "TODO";
                        }
                        if ("RECEIVED".equals(status)) {
                            return "RECEIVED";
                        }
                        return "FINISHED";
                    }
                    return null;
                });
    }

    /**
     * 从 v3 任务列表响应里收集 {@code taskStatus=NOT_DONE} 的 taskId
     * （{@code data.taskModuleList[].taskList[]}）。
     */
    private static void collectNotDoneIds(String response, Set<String> out) {
        try {
            JSONObject data = new JSONObject(response).optJSONObject("data");
            if (data == null) {
                return;
            }
            JSONArray modules = data.optJSONArray("taskModuleList");
            if (modules != null) {
                for (int i = 0; i < modules.length(); i++) {
                    JSONObject module = modules.optJSONObject(i);
                    collectNotDoneFromArray(module == null ? null : module.optJSONArray("taskList"), out);
                }
            }

        } catch (Throwable t) {
            Log.err(TAG, "collectNotDoneIds err:", t);
        }
    }


    private static void collectNotDoneFromArray(JSONArray tasks, Set<String> out) {
        if (tasks == null) {
            return;
        }
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null || !"NOT_DONE".equals(task.optString("taskStatus", "").trim())) {
                continue;
            }
            String taskId = task.optString("taskId", "").trim();
            if (!taskId.isEmpty()) {
                out.add(taskId);
            }
        }
    }
    
    /**
     * 查询并处理任务列表
     */
    public void queryAndProcessTaskList() {
        try {
            JSONObject jsonObject = new JSONObject(AntMemberRpcCall.queryModularTaskList());
            if (!MessageUtil.checkSuccess(TAG, jsonObject)) {
                return;
            }
            if (!jsonObject.has("data")) {
                return;
            }
            JSONArray taskModuleList = jsonObject.getJSONObject("data").getJSONArray("taskModuleList");
            for (int i = 0; i < taskModuleList.length(); i++) {
                JSONObject moduleObj = taskModuleList.getJSONObject(i);
                JSONArray taskList = moduleObj.getJSONArray("taskList");
                for (int j = 0; j < taskList.length(); j++) {
                    processTask(taskList.getJSONObject(j));
                }
            }
            // 核对本轮 doFarmTask 的结果（响应不可信，以任务列表为准）
            verifyPendingTasks();
        }
        catch (Throwable t) {
            Log.err(TAG, "queryModularTaskList err:", t);
        }
    }
    
    /**
     * 游戏中心（会员场景 xlyy_WJCNJT）宝箱领取：任务流条目无 taskId/taskStatus，不对其发完成请求，
     * 宝箱由游戏侧上报才 +1，只领首页有待领的（上限 {@code dailyTaskCertCntUpperLimit}）；
     * 任务名仍进「会员任务」黑名单候选供手动剔除。
     */
    public void gameCenterTaskPrize() {
        try {
            JSONObject homeJo = new JSONObject(AntMemberRpcCall.gameCenterHomePage());
            if (!MessageUtil.checkSuccess(TAG, homeJo)) {
                Log.record("游戏中心首页异常：" + homeJo);
                return;
            }
            JSONObject homeData = homeJo.optJSONObject("data");
            JSONObject taskModule = homeData == null ? null : homeData.optJSONObject("taskModule");
            if (taskModule == null) {
                return;
            }
            if (taskModule.optBoolean("reachTaskCertLimit", false)) {
                Log.record("游戏中心宝箱已达今日上限（"
                        + taskModule.optString("taskProgressDesc", GAME_CENTER_CERT_LIMIT + "/天") + "）");
                return;
            }
            boolean needReceive = homeData.has("taskPrizePopup") || taskModule.optBoolean("needReceive", false)
                    || taskModule.optInt("needReceiveTaskCertCnt", 0) > 0;
            if (!needReceive) {
                return;
            }

            JSONObject receiveJo = new JSONObject(AntMemberRpcCall.batchReceiveTaskPrize());
            if (!MessageUtil.checkSuccess(TAG, receiveJo)) {
                Log.record("游戏中心宝箱领取失败：" + receiveJo);
                return;
            }
            JSONObject receiveData = receiveJo.optJSONObject("data");
            JSONObject prizePopup = receiveData == null ? null : receiveData.optJSONObject("prizePopup");
            String tip = prizePopup == null ? "" : prizePopup.optString("subTitle", "").trim();
            if (tip.isEmpty() && prizePopup != null) {
                tip = prizePopup.optString("actionText", "").trim();
            }
            String desc = taskModule.optString("taskProgressDesc", "").trim();
            Log.other("游戏中心🎁[领取宝箱]" + (tip.isEmpty() ? "" : "#" + tip) + (desc.isEmpty() ? "" : "#" + desc));
        }
        catch (Throwable t) {
            Log.err(TAG, "gameCenterTaskPrize err:", t);
        }
    }

    
    /**
     * 查询玩乐豆小球列表，有则领取
     */
    public static void queryPointBallList() {
        try {
            String response = ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.queryPointBallList", "[{}]");
            JSONObject jsonObject = new JSONObject(response);
            if (MessageUtil.checkSuccess(TAG, jsonObject)) {
                JSONArray pointBallList = jsonObject.optJSONObject("data") == null
                        ? null : jsonObject.getJSONObject("data").optJSONArray("pointBallList");
                if (pointBallList != null && pointBallList.length() > 0) {
                    batchReceivePointBall();
                }
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "queryPointBallList err:", t);
        }
    }
    
    /**
     * 检查并执行签到
     */
    public static void checkAndDoSignIn() {
        if (Status.hasFlagToday("gameCenterSignIn")) {
            return;
        }
        
        try {
            String str = AntMemberRpcCall.queryPointBallList();
            if (str == null || str.isEmpty()) {
                return;
            }
            JSONObject jsonObject = new JSONObject(str);
            // 风控/异常时响应可能无 data 字段，用 optJSONObject 避免 JSONException
            if (MessageUtil.checkSuccess(TAG, jsonObject)) {
                JSONObject dataObj = jsonObject.optJSONObject("data");
                if (dataObj != null && dataObj.has("signInBallModule")) {
                    JSONObject signInModule = dataObj.optJSONObject("signInBallModule");
                    if (signInModule != null && !signInModule.optBoolean("signInStatus")) {
                        if (dailySignIn()) {
                            Status.flagToday("gameCenterSignIn");
                        }
                    }
                }
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "querySignInBall err:", t);
        }
    }
    
    /*
    private void enableGameCenter() {
        try {
            try {
                String str = AntMemberRpcCall.querySignInBall();
                JSONObject jsonObject = new JSONObject(str);
                if (!jsonObject.optBoolean("success")) {
                    Log.i(TAG + ".signIn.querySignInBall", jsonObject.optString("resultDesc"));
                    return;
                }
                str = JsonUtil.getValueByPath(jsonObject, "data.signInBallModule.signInStatus");
                if (String.valueOf(true).equals(str)) {
                    return;
                }
                str = AntMemberRpcCall.continueSignIn();
                TimeUtil.sleep(300);
                jsonObject = new JSONObject(str);
                if (!jsonObject.optBoolean("success")) {
                    Log.i(TAG + ".signIn.continueSignIn", jsonObject.optString("resultDesc"));
                    return;
                }
                Log.other("游戏中心🎮签到成功");
            }
            catch (Throwable th) {
                Log.err(TAG, "signIn err:", th);
            }
            try {
                String str = AntMemberRpcCall.queryPointBallList();
                JSONObject jsonObject = new JSONObject(str);
                if (!jsonObject.optBoolean("success")) {
                    Log.i(TAG + ".batchReceive.queryPointBallList", jsonObject.optString("resultDesc"));
                    return;
                }
                JSONArray jsonArray = (JSONArray) JsonUtil.getValueByPathObject(jsonObject, "data.pointBallList");
                if (jsonArray == null || jsonArray.length() == 0) {
                    return;
                }
                str = AntMemberRpcCall.batchReceivePointBall();
                TimeUtil.sleep(300);
                jsonObject = new JSONObject(str);
                if (jsonObject.optBoolean("success")) {
                    Log.other("游戏中心🎮全部领取成功[" + JsonUtil.getValueByPath(jsonObject, "data.totalAmount") + "]乐豆");
                }
                else {
                    Log.i(TAG + ".batchReceive.batchReceivePointBall", jsonObject.optString("resultDesc"));
                }
            }
            catch (Throwable th) {
                Log.err(TAG, "batchReceive err:", th);
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }
    */
    // 会员积分兑换 - 获取权益列表（无条件）+ 兑换（受开关控制）
    private boolean memberPointExchangeBenefit() {
        try {
            String userId = UserIdMap.getCurrentUid();
            // 依次尝试多个 deliveryId，找到可用的分类
            String[] deliveryIds = {"94000SR2023102305988003", "94000SR2024011106752003", "94000SR2024071108523003", "94000SR2024071808609003"};
            JSONObject jo = null;
            for (String deliveryId : deliveryIds) {
                String raw = AntMemberRpcCall.queryDeliveryZoneDetail(userId, deliveryId);
                jo = new JSONObject(raw);
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    JSONArray entityInfoList = jo.optJSONArray("entityInfoList");
                    if (entityInfoList != null && entityInfoList.length() > 0) {
                        Log.i(TAG, "queryDeliveryZoneDetail deliveryId=" + deliveryId + " 成功，权益数=" + entityInfoList.length());
                        break;
                    }
                }
                Log.i(TAG, "queryDeliveryZoneDetail deliveryId=" + deliveryId + " 失败，尝试下一个");
                jo = null;
            }
            if (jo == null) {
                // 所有 deliveryId 都失败，尝试备用接口
                Log.i(TAG, "queryDeliveryZoneDetail 全部失败，尝试备用接口");
                fetchBenefitsFromNavi(userId);
                MemberBenefitIdMap.save(userId);
                return true;
            }
            JSONArray entityInfoList = jo.optJSONArray("entityInfoList");
            if (entityInfoList == null || entityInfoList.length() == 0) {
                Log.record("会员积分[当前分类无可兑换权益，尝试备用接口]");
                fetchBenefitsFromNavi(userId);
                MemberBenefitIdMap.save(userId);
                return true;
            }
            // 无条件保存权益列表（供用户勾选）
            java.util.Set<String> selectedIds = memberPointExchangeBenefitList.getValue();
            for (int i = 0; i < entityInfoList.length(); i++) {
                JSONObject entityInfo = entityInfoList.getJSONObject(i);
                JSONObject benefitInfo = entityInfo.getJSONObject("benefitInfo");
                JSONObject pricePresentation = benefitInfo.getJSONObject("pricePresentation");
                if (!"POINT_PAY".equals(pricePresentation.optString("strategyType"))) {
                    continue;
                }
                String name = benefitInfo.getString("name");
                String benefitId = benefitInfo.getString("benefitId");
                MemberBenefitIdMap.add(benefitId, name);
            }
            MemberBenefitIdMap.save(userId);

            // 开关关闭则不兑换
            if (!memberPointExchangeBenefit.getValue()) {
                Log.i(TAG, "会员积分兑换开关已关闭，仅更新权益列表");
                return true;
            }
            if (selectedIds.isEmpty()) {
                Log.i(TAG, "会员积分兑换已开启，请在列表中选择要兑换的权益");
            }
            for (int i = 0; i < entityInfoList.length(); i++) {
                JSONObject entityInfo = entityInfoList.getJSONObject(i);
                JSONObject benefitInfo = entityInfo.getJSONObject("benefitInfo");
                JSONObject pricePresentation = benefitInfo.getJSONObject("pricePresentation");
                if (!"POINT_PAY".equals(pricePresentation.optString("strategyType"))) {
                    continue;
                }
                String name = benefitInfo.getString("name");
                String benefitId = benefitInfo.getString("benefitId");
                // 只兑换用户在列表中勾选的权益
                if (!selectedIds.contains(benefitId)) {
                    continue;
                }
                if (!Status.canMemberPointExchangeBenefitToday(benefitId)) {
                    continue;
                }
                String itemId = benefitInfo.getString("itemId");
                if (exchangeBenefit(benefitId, itemId)) {
                    String point = pricePresentation.getString("point");
                    Log.other("会员积分🎐兑换[" + name + "]#花费[" + point + "积分]");
                }
            }
            return true;
        }
        catch (Throwable t) {
            Log.err(TAG, "memberPointExchangeBenefit err:", t);
            return false;
        }
    }

    // 备用接口：通过导航分类码查询权益列表（支持分页）
    private void fetchBenefitsFromNavi(String userId) {
        try {
            // 依次尝试各导航分类码：特色(14)、出行(1)、美食(11)、日用(12)
            String[] naviCodes = {"14", "1", "11", "12", "13", "bb82b", ""};
            for (String naviCode : naviCodes) {
                int pageNum = 1;
                int totalPages = 1;
                Log.i(TAG, "fetchBenefitsFromNavi start naviCode=" + naviCode);
                do {
                    String raw = AntMemberRpcCall.queryIndexNaviBenefitFlowV2(userId, naviCode, pageNum);
                    JSONObject jo = new JSONObject(raw);
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        Log.i(TAG, "queryIndexNaviBenefitFlowV2 naviCode=" + naviCode + " pageNum=" + pageNum + " resultCode=" + jo.optString("resultCode") + " desc=" + jo.optString("desc"));
                        break;
                    }
                    JSONArray benefitList = jo.optJSONArray("entityInfoList");
                    if (benefitList == null || benefitList.length() == 0) {
                        benefitList = jo.optJSONObject("data") != null ? jo.getJSONObject("data").optJSONArray("entityInfoList") : null;
                    }
                    if (benefitList == null || benefitList.length() == 0) {
                        Log.i(TAG, "queryIndexNaviBenefitFlowV2 naviCode=" + naviCode + " pageNum=" + pageNum + " 无权益数据，raw=" + raw.substring(0, Math.min(300, raw.length())));
                        break;
                    }
                    // 获取总页数
                    if (pageNum == 1) {
                        int next = jo.optInt("nextPageNum", 0);
                        int adNext = jo.optInt("nextAdPageNum", 0);
                        Log.i(TAG, "queryIndexNaviBenefitFlowV2 naviCode=" + naviCode + " pageNum=" + pageNum + " nextPageNum=" + next + " nextAdPageNum=" + adNext);
                        if (next > 1) {
                            totalPages = next;
                        } else if (adNext > 1) {
                            totalPages = adNext;
                        }
                    }
                    // 从 entityInfoList 解析完整权益信息
                    int saved = 0, skipped = 0;
                    for (int i = 0; i < benefitList.length(); i++) {
                        JSONObject entity = benefitList.getJSONObject(i);
                        JSONObject benefitInfo = entity.optJSONObject("benefitInfo");
                        if (benefitInfo == null) { skipped++; continue; }
                        JSONObject pricePresentation = benefitInfo.optJSONObject("pricePresentation");
                        if (pricePresentation == null) { skipped++; continue; }
                        String strategyType = pricePresentation.optString("strategyType");
                        if (!"POINT_PAY".equals(strategyType)) {
                            Log.i(TAG, "  跳过[" + benefitInfo.optString("name") + "] strategyType=" + strategyType);
                            skipped++;
                            continue;
                        }
                        String name = benefitInfo.optString("name");
                        String benefitId = benefitInfo.optString("benefitId");
                        if (benefitId.isEmpty()) { skipped++; continue; }
                        Log.i(TAG, "  保存[" + name + "] benefitId=" + benefitId);
                        MemberBenefitIdMap.add(benefitId, name);
                        saved++;
                    }
                    // 从 extInfo 补充 entityInfoList 中缺失的 benefitId
                    JSONObject extInfo = jo.optJSONObject("extInfo");
                    if (extInfo != null && extInfo.length() > 0) {
                        int extAdded = 0;
                        for (Iterator<String> it = extInfo.keys(); it.hasNext(); ) {
                            String key = it.next();
                            // 跳过非 benefitId 的元数据 key
                            if ("promoSceneCode".equals(key) || key.startsWith("AMS") == false && key.length() < 10) {
                                continue;
                            }
                            if (!MemberBenefitIdMap.getMap().containsKey(key)) {
                                Log.i(TAG, "  补充[extInfo] benefitId=" + key);
                                MemberBenefitIdMap.add(key, "会员积分权益");
                                extAdded++;
                            }
                        }
                        if (extAdded > 0) {
                            Log.i(TAG, "queryIndexNaviBenefitFlowV2 naviCode=" + naviCode + " extInfo补充" + extAdded + "个");
                        }
                    }
                    Log.i(TAG, "queryIndexNaviBenefitFlowV2 naviCode=" + naviCode + " 保存" + saved + "个，补充" + (extInfo != null ? extInfo.length() : 0) + "个，累计" + MemberBenefitIdMap.getMap().size());
                    pageNum++;
                } while (pageNum <= totalPages);
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "fetchBenefitsFromNavi err:", t);
        }
    }
    
    private Boolean exchangeBenefit(String benefitId, String itemId) {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.exchangeBenefit(benefitId, itemId));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Status.memberPointExchangeBenefitToday(benefitId);
                return true;
            }
        }
        catch (Throwable t) {
            Log.err(TAG, "exchangeBenefit err:", t);
        }
        return false;
    }
    
    private void collectSesame() {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryHome());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject entrance = jo.getJSONObject("entrance");
            if (!entrance.optBoolean("openApp")) {
                Log.other("芝麻信用💌未开通");
                return;
            }
            
            jo = new JSONObject(AntMemberRpcCall.CreditAccumulateStrategyRpcManager());
            TimeUtil.sleep(300);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("data")) {
                return;
            }
            JSONObject data = jo.getJSONObject("data");
            if (!data.has("toCompleteVOS")) {
                return;
            }
            JSONArray toCompleteVOS = data.getJSONArray("toCompleteVOS");
            // 本轮已上报的任务（templateId → 标题）及上报前完成度，用于回读校验
            LinkedHashMap<String, String> reported = new LinkedHashMap<>();
            LinkedHashMap<String, Integer> beforeComplete = new LinkedHashMap<>();
            for (int i = 0; i < toCompleteVOS.length(); i++) {
                JSONObject toCompleteVO = toCompleteVOS.getJSONObject(i);
                String taskTitle = toCompleteVO.has("title") ? toCompleteVO.getString("title") : "未知任务";
                //黑名单任务跳过
                if (MemberCreditSesameTaskList.getValue().contains(taskTitle)) {
                    continue;
                }
                
                boolean finishFlag = toCompleteVO.optBoolean("finishFlag", false);
                String actionText = toCompleteVO.optString("actionText", "");
                
                // 检查任务是否已完成
                if (finishFlag || "已完成".equals(actionText)) {
                    continue;
                }
                
                if (!toCompleteVO.has("templateId")) {
                    continue;
                }
                
                String taskTemplateId = toCompleteVO.getString("templateId");
                int needCompleteNum = toCompleteVO.has("needCompleteNum") ? toCompleteVO.getInt("needCompleteNum") : 1;
                int completedNum = toCompleteVO.optInt("completedNum", 0);
                // 今日已上报且回读确认状态没推进：不再重复上报（次日再试），避免每轮刷同一批
                if (Status.hasFlagToday("AntMember::sesame::" + taskTitle)) {
                    continue;
                }
                
                // 上报被受理 ≠ 任务完成：无论走到哪一步都登记回读，真实状态一律以回读为准
                String recordId = activeSesameRecordId(data, taskTemplateId);
                if (recordId == null || !reportSesameTask(taskTitle, taskTemplateId, recordId)) {
                    continue;
                }
                reported.put(taskTemplateId, taskTitle);
                beforeComplete.put(taskTemplateId, completedNum);
                
                jo = new JSONObject(AntMemberRpcCall.queryCreditFeedback());
                TimeUtil.sleep(300);
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                JSONArray ja = jo.getJSONArray("creditFeedbackVOS");
                for (int j = 0; j < ja.length(); j++) {
                    jo = ja.getJSONObject(j);
                    if (!"UNCLAIMED".equals(jo.getString("status"))) {
                        continue;
                    }
                    //String title = jo.getString("title");
                    String creditFeedbackId = jo.getString("creditFeedbackId");
                    String potentialSize = jo.getString("potentialSize");
                    jo = new JSONObject(AntMemberRpcCall.collectCreditFeedback(creditFeedbackId));
                    TimeUtil.sleep(300);
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        Log.other("收芝麻粒🙇🏻‍♂️领取[" + taskTitle + "]奖励[芝麻粒*" + potentialSize + "]");
                    }
                }
            }
            
            // 回读校验：上报被受理不等于任务完成，按服务端最新完成度判定，日志只写真实结果
            if (!reported.isEmpty()) {
                TimeUtil.sleep(500);
                JSONObject freshJo = new JSONObject(AntMemberRpcCall.CreditAccumulateStrategyRpcManager());
                LinkedHashMap<String, String> freshProgress = new LinkedHashMap<>();
                boolean freshOk = false;
                if (MessageUtil.checkResultCode(TAG, freshJo) && freshJo.has("data")) {
                    JSONArray freshList = freshJo.getJSONObject("data").optJSONArray("toCompleteVOS");
                    freshOk = true;
                    if (freshList != null) {
                        for (int k = 0; k < freshList.length(); k++) {
                            JSONObject vo = freshList.getJSONObject(k);
                            String tid = vo.optString("templateId", "");
                            if (tid.isEmpty()) {
                                continue;
                            }
                            int need = vo.has("needCompleteNum") ? vo.getInt("needCompleteNum") : 1;
                            if (vo.optBoolean("finishFlag", false) || "已完成".equals(vo.optString("actionText", ""))) {
                                freshProgress.put(tid, "DONE");
                            } else {
                                freshProgress.put(tid, vo.optInt("completedNum", 0) + "/" + need);
                            }
                        }
                    }
                }
                for (Map.Entry<String, String> entry : reported.entrySet()) {
                    String tid = entry.getKey();
                    String title = entry.getValue();
                    Integer before = beforeComplete.get(tid);
                    int beforeNum = before == null ? 0 : before;
                    if (!freshOk) {
                        Log.other("芝麻信用💳[" + title + "]已上报，回读校验未执行");
                        continue;
                    }
                    String progress = freshProgress.get(tid);
                    if (progress == null) {
                        Log.other("芝麻信用💳完成任务[" + title + "]#已不在待完成列表");
                        continue;
                    }
                    if ("DONE".equals(progress)) {
                        Log.other("芝麻信用💳完成任务[" + title + "]#已标记完成");
                        continue;
                    }
                    int slash = progress.indexOf('/');
                    int nowNum = slash <= 0 ? 0 : Integer.parseInt(progress.substring(0, slash));
                    int needNum = slash <= 0 ? 1 : Integer.parseInt(progress.substring(slash + 1));
                    if (nowNum <= beforeNum) {
                        Log.other("芝麻信用💳[" + title + "]上报未生效#仍为(" + progress + "天)，今日不再重试");
                        Status.flagToday("AntMember::sesame::" + title);
                        // 这类任务服务端要求真实参与（push 的 promiseActivityExtCheck 校验），自动完成不了：
                        // 走"连续命中确认"（累计 3 次才真拉黑），避免把服务端异步未落状态的正常任务一次误杀
                        if (AutoMemberCreditSesameTaskList.getValue()) {
                            MessageUtil.MarkTaskBlackListConfirm("AntMember", "MemberCreditSesameTaskList", "芝麻粒任务", title);
                        }
                    } else if (nowNum >= needNum) {
                        Log.other("芝麻信用💳完成任务[" + title + "]#(" + progress + "天)");
                    } else {
                        // 多天任务：今天只推进一份，剩下的留给次日
                        Log.other("芝麻信用💳完成任务[" + title + "]#(" + progress + "天)，剩余次日继续");
                        Status.flagToday("AntMember::sesame::" + title);
                    }
                }
            }
            jo = new JSONObject(AntMemberRpcCall.queryCreditFeedback());
            TimeUtil.sleep(300);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray creditFeedbackVOS = jo.getJSONArray("creditFeedbackVOS");
            if (creditFeedbackVOS.length() != 0) {
                jo = new JSONObject(AntMemberRpcCall.collectAllCreditFeedback());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    String resultCode = jo.optString("resultCode");
                    Log.other("收芝麻粒🙇🏻‍♂️[一键收取]" + resultCode);
                }
            }
            
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }
    
    /**
     * 芝麻粒任务上报：joinActivity → taskFeedback → pushActivity 须依次全部发送；
     * join 因"存在进行中的记录"被拒时，刷新列表取本任务那条记录继续推完。
     * <p>返回是否走到 push；未发送 push 的不计失败，也不登记回读。
     *
     * @param recordId 任务列表已给出的进行中记录，为空时才发 join
     */
    private boolean reportSesameTask(String taskTitle, String taskTemplateId, String recordId) {
        try {
            if (StringUtil.isEmpty(recordId)) {
                JSONObject joinJo = new JSONObject(AntMemberRpcCall.joinSesameTask(taskTemplateId));
                TimeUtil.sleep(500);
                if (MessageUtil.checkResultCode(TAG, joinJo)) {
                    JSONObject joinData = joinJo.optJSONObject("data");
                    recordId = joinData == null ? "" : joinData.optString("recordId", "");
                } else {
                    // 全局只允许一条进行中记录：刷新列表取本任务那条，取不到再回落到最近操作记录
                    if ("PROMISE_HAS_PROCESSING_TEMPLATE".equals(joinJo.optString("resultCode"))) {
                        JSONObject fresh = new JSONObject(AntMemberRpcCall.queryAvailableSesameTask());
                        TimeUtil.sleep(300);
                        if (MessageUtil.checkResultCode(TAG, fresh) && fresh.optJSONObject("data") != null) {
                            recordId = activeSesameRecordId(fresh.optJSONObject("data"), taskTemplateId);
                            if (recordId == null) {
                                return false;
                            }
                        }
                    }
                    if (StringUtil.isEmpty(recordId)) {
                        recordId = lastOperateRecordId(taskTemplateId);
                    }
                    if (!StringUtil.isEmpty(recordId)) {
                        Log.other("芝麻信用💳[" + taskTitle + "]沿用进行中的记录");
                    }
                }
            }
            if (StringUtil.isEmpty(recordId)) {
                Log.other("芝麻信用💳上报[" + taskTitle + "]无可用记录#未发送反馈与push，不计失败");
                return false;
            }
            
            JSONObject feedbackJo = new JSONObject(AntMemberRpcCall.feedBackSesameTaskNew(taskTemplateId));
            TimeUtil.sleep(500);
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("MemberCreditSesameTaskList", taskTitle, feedbackJo);
            if (!MessageUtil.checkResultCode(TAG, feedbackJo)) {
                Log.other("芝麻信用💳上报[" + taskTitle + "]未受理#未发送push");
                return false;
            }
            
            JSONObject pushJo = new JSONObject(AntMemberRpcCall.finishSesameTask(recordId, ""));
            TimeUtil.sleep(500);
            if ("ILLEGAL_ARGUMENT".equals(pushJo.optString("resultCode"))) {
                // 真实跳转类任务不可能自动完成，服务端固定拒 push：一次即永久拉黑，该错误不再打印
                if (AutoMemberCreditSesameTaskList.getValue()) {
                    MessageUtil.MarkTaskBlackListPermanent("AntMember", "MemberCreditSesameTaskList", "芝麻粒任务", taskTitle);
                    Log.other("芝麻信用💳[" + taskTitle + "]#真实跳转类，无法自动完成，已加入永久黑名单");
                } else {
                    Log.other("芝麻信用💳[" + taskTitle + "]#真实跳转类，无法自动完成");
                }
                return false;
            }
            if (!MessageUtil.checkResultCode(TAG, pushJo)) {
                Log.other("芝麻信用💳[" + taskTitle + "]push被拒#不影响完成判定");
            }
            return true;
            
        } catch (Throwable t) {
            Log.err(TAG, "reportSesameTask err:", t);
        }
        return false;
    }
    
    /**
     * 取回「最近一次操作任务」的 recordId：仅当它就是本任务且仍在进行中（finishFlag=false）时返回，否则 null。
     * <p>用途见 {@link #reportSesameTask}：join 被「存在进行中的生活记录」拒绝时，必须用原记录的 recordId 才能推完它。
     */
    private String lastOperateRecordId(String taskTemplateId) {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryLastOperateTask());
            TimeUtil.sleep(300);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return null;
            }
            JSONObject data = jo.optJSONObject("data");
            JSONObject vo = data == null ? null : data.optJSONObject("lastOperateTaskVO");
            if (vo == null || !taskTemplateId.equals(vo.optString("templateId", ""))) {
                return null;
            }
            if (vo.optBoolean("finishFlag", false)) {
                return null;
            }
            String recordId = vo.optString("recordId", "");
            return StringUtil.isEmpty(recordId) ? null : recordId;
        } catch (Throwable t) {
            Log.err(TAG, "lastOperateRecordId err:", t);
        }
        return null;
    }
    
    /**
     * 从任务列表取本任务进行中的记录：recordId 须为字符串且仍在进行中，同一任务出现多条不一致记录时返回 null。
     *
     * @return 进行中的 recordId；空串表示列表无本任务记录，null 表示列表数据可疑
     */
    private String activeSesameRecordId(JSONObject data, String taskTemplateId) {
        JSONObject daily = data.optJSONObject("dailyTaskListVO");
        JSONArray[] lists = {data.optJSONArray("toCompleteVOS"),
                daily == null ? null : daily.optJSONArray("waitCompleteTaskVOS"),
                daily == null ? null : daily.optJSONArray("waitJoinTaskVOS")};
        String recordId = "";
        for (JSONArray list : lists) {
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject task = list.optJSONObject(i);
                if (task == null || !taskTemplateId.equals(task.optString("templateId", ""))) {
                    continue;
                }
                Object value = task.opt("recordId");
                if (value == null || JSONObject.NULL.equals(value) || "".equals(value)) {
                    continue;
                }
                boolean valid = value instanceof String && !((String) value).trim().isEmpty()
                        && Boolean.FALSE.equals(task.opt("finishFlag"))
                        && task.optInt("completedNum", 0) < task.optInt("needCompleteNum", 1)
                        && (recordId.isEmpty() || recordId.equals(value));
                if (!valid) {
                    Log.other("芝麻信用💳记录校验未通过#[" + taskTemplateId + "]跳过");
                    return null;
                }
                recordId = (String) value;
            }
        }
        return recordId;
    }
    
    private void CheckInTaskRpcManager() {
        if (Status.hasFlagToday("AntMember::zmlCheckIn")) {
            return;
        }
        // 领取是否失败：失败时不置今日标记，留给下一轮重试（否则当天不再重试 → 漏领）
        boolean claimFailed = false;
        try {
            
            String checkInRes = AntMemberRpcCall.alchemyQueryCheckIn("zml");
            JSONObject checkInJo = new JSONObject(checkInRes);
            if (MessageUtil.checkResultCode(TAG, checkInJo)) {
                JSONObject data = checkInJo.optJSONObject("data");
                if (data != null) {
                    JSONObject currentDay = data.optJSONObject("currentDateCheckInTaskVO");
                    if (currentDay != null) {
                        String status = currentDay.optString("status");
                        String checkInDate = currentDay.optString("checkInDate");
                        if ("CAN_COMPLETE".equals(status) && !checkInDate.isEmpty()) {
                            String completeRes = AntMemberRpcCall.zmCheckInCompleteTask(checkInDate, "zml");
                            try {
                                JSONObject completeJo = new JSONObject(completeRes);
                                if (MessageUtil.checkResultCode(TAG, completeJo)) {
                                    JSONObject prize = completeJo.optJSONObject("data");
                                    int num = 0;
                                    if (prize != null) {
                                        num = prize.optInt("zmlNum", prize.optJSONObject("prize") != null ? prize.optJSONObject("prize").optInt("num", 0) : 0);
                                    }
                                    Log.other("收芝麻粒🙇🏻‍♂️领取[每日签到成功]#获得" + num + "粒");
                                }
                                else {
                                    claimFailed = true;
                                    Log.error(".doSesameAlchemy#" + "签到失败:" + completeRes);
                                }
                            }
                            catch (Throwable e) {
                                claimFailed = true;
                                Log.printStackTrace(TAG + ".doSesameAlchemy.alchemyCheckInComplete", e);
                            }
                        }
                    }
                }
            }
            if (claimFailed) {
                Log.other("收芝麻粒🙇🏻‍♂️签到领取失败#本轮不置今日标记，稍后重试");
            }
            else {
                Status.flagToday("AntMember::zmlCheckIn");
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG + ".doSesameZmlCheckIn", t);
        }
    }
    
    // 我的快递任务
    private void RecommendTask() {
        try {
            // 调用 AntMemberRpcCall.queryRecommendTask() 获取 JSON 数据
            String response = AntMemberRpcCall.queryRecommendTask();
            JSONObject jsonResponse = new JSONObject(response);
            // 获取 taskDetailList 数组
            JSONArray taskDetailList = jsonResponse.optJSONArray("taskDetailList");
            if (taskDetailList == null) {
                return;
            }
            // 遍历 taskDetailList
            for (int i = 0; i < taskDetailList.length(); i++) {
                JSONObject taskDetail = taskDetailList.optJSONObject(i);
                if (taskDetail == null) {
                    continue;
                }
                // 检查 "canAccess" 的值是否为 true
                boolean canAccess = taskDetail.optBoolean("canAccess", false);
                if (!canAccess) {
                    // 如果 "canAccess" 不为 true，跳过
                    continue;
                }
                // 获取 taskMaterial 对象
                JSONObject taskMaterial = taskDetail.optJSONObject("taskMaterial");
                // 获取 taskBaseInfo 对象
                JSONObject taskBaseInfo = taskDetail.optJSONObject("taskBaseInfo");
                // 获取 taskCode
                String taskCode = taskMaterial.optString("taskCode", "");
                // 根据 taskCode 执行不同的操作
                if ("WELFARE_PLUS_ANT_FOREST".equals(taskCode) || "WELFARE_PLUS_ANT_OCEAN".equals(taskCode)) {
                    // 福利加任务每日只做一次：服务端在完成后仍把该条目留在列表里（状态不翻），
                    // 不做标记会每轮重复调用触发与领奖接口
                    String welfareFlag = "AntMember::welfarePlus::" + taskCode;
                    if (Status.hasFlagToday(welfareFlag)) {
                        continue;
                    }
                    if ("WELFARE_PLUS_ANT_FOREST".equals(taskCode)) {
                        //String forestHomePageResponse = AntMemberRpcCall.queryforestHomePage();
                        String forestTaskResponse = AntMemberRpcCall.forestTask();
                        TimeUtil.sleep(500);
                        String forestreceiveTaskAward = AntMemberRpcCall.forestreceiveTaskAward();
                    }
                    else if ("WELFARE_PLUS_ANT_OCEAN".equals(taskCode)) {
                        //String oceanHomePageResponse = AntMemberRpcCall.queryoceanHomePage();
                        String oceanTaskResponse = AntMemberRpcCall.oceanTask();
                        TimeUtil.sleep(500);
                        String oceanreceiveTaskAward = AntMemberRpcCall.oceanreceiveTaskAward();
                    }
                    Status.flagToday(welfareFlag);
                    if (taskBaseInfo != null) {
                        String appletName = taskBaseInfo.optString("appletName", "Unknown Applet");
                        Log.other("我的快递💌完成[" + appletName + "]");
                    }
                }
                if (taskMaterial == null || !taskMaterial.has("taskId")) {
                    // 如果 taskMaterial 为 null 或者不包含 taskId，跳过
                    continue;
                }
                // 获取 taskId
                String taskId = taskMaterial.getString("taskId");
                // 调用 trigger 方法
                String triggerResponse = AntMemberRpcCall.trigger(taskId);
                JSONObject triggerResult = new JSONObject(triggerResponse);
                // 检查 success 字段
                boolean success = triggerResult.getBoolean("success");
                if (success) {
                    // 从 triggerResponse 中获取 prizeSendInfo 数组
                    JSONArray prizeSendInfo = triggerResult.getJSONArray("prizeSendInfo");
                    if (prizeSendInfo.length() > 0) {
                        JSONObject prizeInfo = prizeSendInfo.getJSONObject(0);
                        JSONObject extInfo = prizeInfo.getJSONObject("extInfo");
                        // 获取 promoCampName
                        String promoCampName = extInfo.optString("promoCampName", "Unknown Promo Campaign");
                        // 输出日志信息
                        Log.other("我的快递💌完成[" + promoCampName + "]");
                    }
                }
            }
        }
        catch (Throwable th) {
            Log.err(TAG, "RecommendTask err:", th);
        }
    }
    
    private void OrdinaryTask() {
        try {
            // 调用 AntMemberRpcCall.queryOrdinaryTask() 获取 JSON 数据
            String response = AntMemberRpcCall.queryOrdinaryTask();
            JSONObject jsonResponse = new JSONObject(response);
            // 检查是否请求成功
            if (jsonResponse.getBoolean("success")) {
                // 获取任务详细列表
                JSONArray taskDetailList = jsonResponse.getJSONArray("taskDetailList");
                // 遍历任务详细列表
                for (int i = 0; i < taskDetailList.length(); i++) {
                    // 获取当前任务对象
                    JSONObject task = taskDetailList.getJSONObject(i);
                    // 提取任务 ID、处理状态和触发类型
                    String taskId = task.optString("taskId");
                    String taskProcessStatus = task.optString("taskProcessStatus");
                    String sendCampTriggerType = task.optString("sendCampTriggerType");
                    // 检查任务状态和触发类型，执行触发操作
                    if (!"RECEIVE_SUCCESS".equals(taskProcessStatus) && !"EVENT_TRIGGER".equals(sendCampTriggerType)) {
                        // 调用 signuptrigger 方法
                        String signuptriggerResponse = AntMemberRpcCall.signuptrigger(taskId);
                        // 调用 sendtrigger 方法
                        String sendtriggerResponse = AntMemberRpcCall.sendtrigger(taskId);
                        // 解析 sendtriggerResponse
                        JSONObject sendTriggerJson = new JSONObject(sendtriggerResponse);
                        // 判断任务是否成功
                        if (sendTriggerJson.getBoolean("success")) {
                            // 从 sendtriggerResponse 中获取 prizeSendInfo 数组
                            JSONArray prizeSendInfo = sendTriggerJson.getJSONArray("prizeSendInfo");
                            // 获取 prizeName
                            String prizeName = prizeSendInfo.getJSONObject(0).getString("prizeName");
                            Log.other("我的快递💌完成[" + prizeName + "]");
                        }
                        else {
                            Log.i(TAG, "sendtrigger failed for taskId: " + taskId);
                        }
                        TimeUtil.sleep(1000);
                    }
                }
            }
        }
        catch (Throwable th) {
            Log.err(TAG, "OrdinaryTask err:", th);
        }
    }
}
