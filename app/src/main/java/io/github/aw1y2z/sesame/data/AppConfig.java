package io.github.aw1y2z.sesame.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.Data;
import io.github.aw1y2z.sesame.util.FileUtil;
import io.github.aw1y2z.sesame.util.JsonUtil;
import io.github.aw1y2z.sesame.util.Log;

import java.io.File;

@Data
public class AppConfig {

    private static final String TAG = AppConfig.class.getSimpleName();

    // 存到与注入进程（支付宝模块）共享的 sesame 目录，确保日志开关在模块进程同样生效
    private static final File APP_CONFIG_DIRECTORY_FILE = FileUtil.MAIN_DIRECTORY_FILE;

    public static final AppConfig INSTANCE = new AppConfig();

    /** 本进程是否已打印过「加载APP配置」，避免启动多阶段重复初始化时刷重复日志 */
    private static volatile boolean loadLogged = false;

    /** 上次 load 解析失败：内存此时只是默认值，必须禁止写盘，否则会把默认值整份固化 */
    private static volatile boolean loadFailed = false;

    @JsonIgnore
    private boolean init;

    private Boolean newUI = true;
    private Boolean languageSimplifiedChinese = true;

    private Boolean darkMode = false;
    private Boolean followSystem = true;

    private Boolean enableForestLog = true;
    private Boolean enableGoldenBeansLog = true;
    private Boolean enableFarmLog = true;
    private Boolean enableOtherLog = true;
    /**
     * 抓包记录。
     * <p>同时充当 /debugHandler（PC 调用任意 RPC）的门禁：两者都是调试专用、默认关闭，
     * 且实际使用场景高度重合（要调 RPC 的人本来就要抓包），合并成一个开关避免用户维护两个「调试」开关。
     * <p>⚠ 若将来这个开关面向普通用户开放，必须拆出独立的执行开关——
     * 否则「多记点日志」会顺带变成「开放远程执行」。
     */
    private Boolean enableDebugLog = false;
    private Boolean enableViewErrorLog = true;
    private Boolean enableViewRuntimeLog = true;
    private Boolean logViewByRun = false;
    private Boolean batteryPerm = true;

    // 模块级开关：原先是按账号存在 BaseModel 里，现改为全局（注入进程与模块 App 共用同一份）
    private Boolean newRpc = true;
    private Boolean showToast = true;
    private Integer toastOffsetY = 0;
    private Boolean enableOnGoing = false;
    private Boolean closeCaptchaDialog = true;

    public Boolean getNewRpc() {
        return newRpc;
    }

    public void setNewRpc(Boolean value) {
        newRpc = value;
    }

    public Boolean getShowToast() {
        return showToast;
    }

    public void setShowToast(Boolean value) {
        showToast = value;
    }

    public Integer getToastOffsetY() {
        return toastOffsetY;
    }

    public void setToastOffsetY(Integer value) {
        toastOffsetY = value;
    }

    public Boolean getEnableOnGoing() {
        return enableOnGoing;
    }

    public void setEnableOnGoing(Boolean value) {
        enableOnGoing = value;
    }

    public Boolean getCloseCaptchaDialog() {
        return closeCaptchaDialog;
    }

    public void setCloseCaptchaDialog(Boolean value) {
        closeCaptchaDialog = value;
    }

    public Boolean getLogViewByRun() {
        return logViewByRun;
    }

    public void setLogViewByRun(Boolean value) {
        logViewByRun = value;
    }

    public Boolean getLanguageSimplifiedChinese() {
        return languageSimplifiedChinese;
    }

    public void setLanguageSimplifiedChinese(Boolean value) {
        languageSimplifiedChinese = value;
    }

    public Boolean getDarkMode() {
        return darkMode;
    }

    public void setDarkMode(Boolean value) {
        darkMode = value;
    }

    public Boolean getFollowSystem() {
        return followSystem;
    }

    public void setFollowSystem(Boolean value) {
        followSystem = value;
    }

    public Boolean getEnableForestLog() { return enableForestLog; }
    public void setEnableForestLog(Boolean value) { enableForestLog = value; }

    public Boolean getEnableGoldenBeansLog() { return enableGoldenBeansLog; }
    public void setEnableGoldenBeansLog(Boolean value) { enableGoldenBeansLog = value; }

    public Boolean getEnableFarmLog() { return enableFarmLog; }
    public void setEnableFarmLog(Boolean value) { enableFarmLog = value; }

    public Boolean getEnableOtherLog() { return enableOtherLog; }
    public void setEnableOtherLog(Boolean value) { enableOtherLog = value; }

    public Boolean getEnableDebugLog() { return enableDebugLog; }
    public void setEnableDebugLog(Boolean value) { enableDebugLog = value; }

    public Boolean getEnableViewErrorLog() { return enableViewErrorLog; }
    public void setEnableViewErrorLog(Boolean value) { enableViewErrorLog = value; }

    public Boolean getEnableViewRuntimeLog() { return enableViewRuntimeLog; }
    public void setEnableViewRuntimeLog(Boolean value) { enableViewRuntimeLog = value; }

    public Boolean getBatteryPerm() { return batteryPerm; }
    public void setBatteryPerm(Boolean value) { batteryPerm = value; }

    public static Boolean save() {
        if (loadFailed) {
            // 上次加载失败，内存只是默认值：写盘会把用户配置整份固化，拒绝
            Log.i(TAG, "上次APP配置加载失败，本次不写盘");
            return false;
        }
        return FileUtil.write2File(toSaveStr(), new File(APP_CONFIG_DIRECTORY_FILE, "appConfig.json"));
    }

    public static synchronized AppConfig load() {
        File appConfigFile = new File(APP_CONFIG_DIRECTORY_FILE, "appConfig.json");
        loadFailed = false;
        try {
            if (appConfigFile.exists()) {
                String json = FileUtil.readFromFile(appConfigFile);
                JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(json);
                // 注意：必须先加载文件再打印日志，否则开关判断仍取默认值 true，
                // 导致「运行日志已关闭」时启动进程仍写入加载日志
                if (!loadLogged) {
                    loadLogged = true;
                    Log.i("加载APP配置");
                }
                String formatted = toSaveStr();
                if (formatted != null && !formatted.equals(json)) {
                    Log.i(TAG, "格式化APP配置");
                    FileUtil.write2File(formatted, appConfigFile);
                }
            } else {
                unload();
                Log.i(TAG, "初始APP配置");
                FileUtil.write2File(toSaveStr(), appConfigFile);
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            // 解析失败只回落默认值、绝不写盘：瞬时 IO 抖动或半份文件不该把用户配置整份重置
            Log.i(TAG, "解析APP配置失败，本次使用默认值（不写盘）");
            loadFailed = true;
            unload();
        }
        INSTANCE.setInit(true);
        return INSTANCE;
    }

    public static synchronized void unload() {
        try {
            JsonUtil.copyMapper().updateValue(INSTANCE, new AppConfig());
        } catch (JsonMappingException e) {
            Log.printStackTrace(TAG, e);
        }
    }

    public static String toSaveStr() {
        return JsonUtil.toFormatJsonString(INSTANCE);
    }

}