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

    @JsonIgnore
    private boolean init;

    private Boolean newUI = true;
    private Boolean languageSimplifiedChinese = true;

    private Boolean darkMode = false;
    private Boolean followSystem = true;

    private Boolean enableForestLog = true;
    private Boolean enableFarmLog = true;
    private Boolean enableOtherLog = true;
    private Boolean enableDebugLog = false;
    private Boolean enableViewErrorLog = true;
    private Boolean enableViewRuntimeLog = true;

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

    public static Boolean save() {
        return FileUtil.write2File(toSaveStr(), new File(APP_CONFIG_DIRECTORY_FILE, "appConfig.json"));
    }

    public static synchronized AppConfig load() {
        File appConfigFile = new File(APP_CONFIG_DIRECTORY_FILE, "appConfig.json");
        try {
            if (appConfigFile.exists()) {
                Log.i("加载APP配置");
                String json = FileUtil.readFromFile(appConfigFile);
                JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(json);
                String formatted = toSaveStr();
                if (formatted != null && !formatted.equals(json)) {
                    Log.i(TAG, "格式化APP配置");
                    Log.system(TAG, "格式化APP配置");
                    FileUtil.write2File(formatted, appConfigFile);
                }
            } else {
                unload();
                Log.i(TAG, "初始APP配置");
                Log.system(TAG, "初始APP配置");
                FileUtil.write2File(toSaveStr(), appConfigFile);
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            Log.i(TAG, "重置APP配置");
            Log.system(TAG, "重置APP配置");
            try {
                unload();
                FileUtil.write2File(toSaveStr(), appConfigFile);
            } catch (Exception e) {
                Log.printStackTrace(TAG, t);
            }
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