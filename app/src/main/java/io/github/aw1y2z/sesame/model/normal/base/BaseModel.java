package io.github.aw1y2z.sesame.model.normal.base;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import io.github.aw1y2z.sesame.data.modelFieldExt.SelectAndCountModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.entity.AlipayAntMemberAdTaskList;
import io.github.aw1y2z.sesame.entity.AlipayAntMemberTaskList;
import io.github.aw1y2z.sesame.entity.AlipayrpcRequest;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.hook.CaptchaHook;
import lombok.Getter;

import io.github.aw1y2z.sesame.data.Model;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ChoiceModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.ListModelField;
import io.github.aw1y2z.sesame.model.task.protectEcology.ProtectEcology;
import io.github.aw1y2z.sesame.util.*;
import io.github.aw1y2z.sesame.util.idMap.*;

/**
 * 基础配置模块
 */
public class BaseModel extends Model {
     @Getter
    private static final BooleanModelField stayAwake = new BooleanModelField("stayAwake", "保持唤醒", true);
    @Getter
    private static final IntegerModelField.MultiplyIntegerModelField checkInterval = new IntegerModelField.MultiplyIntegerModelField("checkInterval", "执行间隔(分钟)", 50, 1, 12 * 60, 60_000);
    @Getter
    private static final ListModelField.ListJoinCommaToStringModelField execAtTimeList = new ListModelField.ListJoinCommaToStringModelField("execAtTimeList", "定时执行(关闭:-1)", ListUtil.newArrayList("065530", "2359", "24"));
    @Getter
    private static final ListModelField.ListJoinCommaToStringModelField wakenAtTimeList = new ListModelField.ListJoinCommaToStringModelField("wakenAtTimeList", "定时唤醒(关闭:-1)", ListUtil.newArrayList("0650", "2350"));
    @Getter
    private static final ListModelField.ListJoinCommaToStringModelField energyTime = new ListModelField.ListJoinCommaToStringModelField("energyTime", "只收能量时间(范围)", ListUtil.newArrayList("0700-0731"));
    @Getter
    private static final ChoiceModelField timedTaskModel = new ChoiceModelField("timedTaskModel", "定时任务模式", TimedTaskModel.SYSTEM, TimedTaskModel.nickNames);
    @Getter
    private static final BooleanModelField timeoutRestart = new BooleanModelField("timeoutRestart", "超时重启", true);
    @Getter
    private static final IntegerModelField.MultiplyIntegerModelField waitWhenException = new IntegerModelField.MultiplyIntegerModelField("waitWhenException", "异常等待时间(分钟)", 60, 0, 24 * 60, 60_000);
    @Getter
    public static final IntegerModelField backupConfigDays = new IntegerModelField("backupConfigDays", "按天和修改备份配置保存数(滚动覆盖)", 5);
    @Getter
    private static final SelectAndCountModelField rpcRequestList = new SelectAndCountModelField("rpcRequestList", "RPC请求列表及每日执行数(慎用)", new LinkedHashMap<>(), AlipayrpcRequest::getList, "请填写每日执行次数", 1, 100);
    @Getter
    private static final SelectModelField rpcRequestTaskList= new SelectModelField("rpcRequestTaskList", "RPC可选任务列表(长按列表中的项仅移除用，内容需打开rpcResquest.json文件配置)", new LinkedHashSet<>(), AlipayrpcRequest::getList,"长按删除RPC列表项用");
    
    @Override
    public String getName() {
        return "基础";
    }
    
    @Override
    public ModelGroup getGroup() {
        return ModelGroup.BASE;
    }
    
    @Override
    public String getEnableFieldName() {
        return "启用模块";
    }
    
    public void boot(ClassLoader classLoader) {
        try {
            // 开关已迁到全局配置 AppConfig（模块级，不分账号）
            CaptchaHook.updateHooks(io.github.aw1y2z.sesame.data.AppConfig.INSTANCE.getCloseCaptchaDialog());
            Log.record("✅ 验证码Hook配置已同步");
        } catch (Throwable t) {
            Log.printStackTrace("⚠️ 验证码Hook配置同步失败", t);
        }
    }
    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(stayAwake);
        modelFields.addField(checkInterval);
        modelFields.addField(execAtTimeList);
        modelFields.addField(wakenAtTimeList);
        modelFields.addField(energyTime);
        modelFields.addField(timedTaskModel);
        modelFields.addField(timeoutRestart);
        // 字段早已声明并被 AntForestV2 / OldRpcBridge 读取，但漏了注册，导致界面无法修改、恒为默认 60 分钟
        modelFields.addField(waitWhenException);
        modelFields.addField(backupConfigDays);
        modelFields.addField(rpcRequestList);
        modelFields.addField(rpcRequestTaskList);
        return modelFields;
    }
    
    public static void initData() {
        new Thread(() -> {
            try {
                TimeUtil.sleep(5000);
                ProtectEcology.initForest();
                ProtectEcology.initOcean();
            }
            catch (Exception e) {
                Log.printStackTrace(e);
            }
        }).start();
    }
    
    public static void destroyData() {
        try {
            TreeIdMap.clear();
            ReserveIdMap.clear();
            AnimalIdMap.clear();
            MarathonIdMap.clear();
            NewAncientTreeIdMap.clear();
            BeachIdMap.clear();
            PlantSceneIdMap.clear();
            ForestHuntIdMap.clear();
            MemberCreditSesameTaskListMap.clear();
            AntForestVitalityTaskListMap.clear();
            AntForestHuntTaskListMap.clear();
            AntFarmDoFarmTaskListMap.clear();
            AntFarmDrawMachineTaskListMap.clear();
            AntDodoTaskListMap.clear();
            AntOceanAntiepTaskListMap.clear();
            AntOceanFishBlackListMap.clear();
            AntOrchardTaskListMap.clear();
            OrchardChouChouLeTaskListMap.clear();
            WelfareFundTaskListMap.clear();
            AntStallTaskListMap.clear();
            AntSportsTaskListMap.clear();
            AntMemberTaskListMap.clear();
            AlipayAntMemberTaskList.clear();
            AntMemberAdTaskListMap.clear();
            AlipayAntMemberAdTaskList.clear();
            // 这两个列表原先漏了 clear，切换账号后候选项会残留上一个账号的任务
            MonopolyTaskListMap.clear();
            GoldenBeansTaskListMap.clear();
            GoldenBeansMallItemMap.clear();
            PathThemeMapListMap.clear();
        }
        catch (Exception e) {
            Log.printStackTrace(e);
        }
    }
    
    public interface TimedTaskModel {
        
        int SYSTEM = 0;
        
        int PROGRAM = 1;
        
        String[] nickNames = {"系统计时", "程序计时"};
        
    }
    
    public static void initRpcRequest() {
        rpcRequestMap.load();
        rpcRequestMap.add("{\"methodName\":\"alipay.antforest.forest.h5.queryMiscInfo\",\"requestData\":[{\"queryBizType\":\"usingProp\",\"source\":\"SELF_HOME\",\"version\":\"20240201\"}]}", "查询森林使用道具(示例)");
        rpcRequestMap.add("{\"methodName\":\"alipay.antforest.forest.h5.updateUserConfig\",\"requestData\":[{\"configMap\":{\"inTeam\":\"Y\"},\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]}", "切换到组队浇水(示例)");
        rpcRequestMap.add("{\"methodName\":\"alipay.antforest.forest.h5.updateUserConfig\",\"requestData\":[{\"configMap\":{\"inTeam\":\"N\"},\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]}", "切换到个人主页(示例)");
        
        rpcRequestMap.save();
        
    }
    public static void taskRpcRequest() {
        
        // 1. 获取Map集合，增加空判断避免NPE
        Map<String, Integer> taskRpcList = rpcRequestList.getValue();
        if (taskRpcList == null || taskRpcList.isEmpty()) {
            // 集合为空时直接返回，避免无效遍历
            return;
        }
        // 2. 遍历Map的键值对
        rpcRequestMap.load();
        for (Map.Entry<String, Integer> taskRpc : taskRpcList.entrySet()) {
            // 获取键（待解析的JSON字符串）和值（计数）
            String taskRpcRequestMethodAndData = taskRpc.getKey();
            Integer taskRpcCount = taskRpc.getValue();
            String taskRpcName = rpcRequestMap.get(taskRpcRequestMethodAndData);
            int taskRpcNameTodayCount = Status.getrpcRequestListToday(taskRpcName);
            if (taskRpcNameTodayCount >= taskRpcCount) {
                continue;
            }
            // 3. 解析JSON字符串，处理异常避免崩溃
            JSONObject taskRpcJo = null;
            try {
                //保守执行，不管是否异常均认为执行
                Status.rpcRequestListToday(taskRpcName, taskRpcNameTodayCount+1);
                // 先判空，再解析JSON
                if (taskRpcRequestMethodAndData == null || taskRpcRequestMethodAndData.isEmpty()) {
                    continue; // 跳过空字符串，继续下一次遍历
                }
                taskRpcJo = new JSONObject(taskRpcRequestMethodAndData);
                // 【可选】这里添加解析后的业务逻辑，比如获取JSON中的字段
                String methodName = taskRpcJo.getString("methodName"); // 假设JSON中有method字段
                String requestData = taskRpcJo.getString("requestData");     // 假设JSON中有data字段
                Log.debug("自主调用🈸RPC["+taskRpcName+"]第" + (taskRpcNameTodayCount+1)+"["+taskRpcCount+"]次\n方法：" + methodName + "\n参数：" + StringUtil.truncate(requestData, 200));
                //调用接口执行请求
                String taskRpcResult = ApplicationHook.requestString(methodName, requestData);
                Log.debug("自主调用🈸RPC["+taskRpcName+"]返回\n数据：" + StringUtil.truncate(taskRpcResult, 200));
            }
            catch (JSONException e) {
                // 捕获JSON解析异常，打印日志而不是崩溃
                e.printStackTrace();
                // 可选：记录错误日志，或跳过当前无效的JSON字符串
                Log.debug("JSON解析失败，字符串内容：" + StringUtil.truncate(taskRpcRequestMethodAndData, 200));
            }
        }
    }
}