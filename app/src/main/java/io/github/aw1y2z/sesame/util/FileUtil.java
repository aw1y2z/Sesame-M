package io.github.aw1y2z.sesame.util;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.model.normal.base.BaseModel;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class FileUtil {
    private static final String TAG = FileUtil.class.getSimpleName();
    /** 账号目录名白名单：uid 由宿主给出，拼进路径前必须挡住「..」与路径分隔符 */
    private static final Pattern USER_ID_PATTERN = Pattern.compile("[0-9A-Za-z_-]+");
    //路径
    public static final String CONFIG_DIRECTORY_NAME = "sesame-M";
    public static final File MAIN_DIRECTORY_FILE = getMainDirectoryFile();
    public static final File CONFIG_DIRECTORY_FILE = getConfigDirectoryFile();
    public static final File LOG_DIRECTORY_FILE = getLogDirectoryFile();
    /** 宿主私有目录下的同名子目录，只放凭据类文件（令牌等），共享目录的替代 */
    private static volatile File privateDirectoryFile;
    private static File cityCodeFile;
    private static File wuaFile;
    
    // 备份相关配置（可根据需求调整n值，比如n=3则A/B/C循环）
    private static int BACKUP_MAX_COUNT = 5; // 配置读取失败时的默认值
    private static final String BACKUP_DIR_NAME = "bak"; // 备份子目录名
    public static final String BACKUP_FILE_PREFIX = "config_v2_";
    public static final String BACKUP_FILE_EXT = ".json";
    
    /** 备份保留份数：读 BaseModel 配置，失败回退 5 */
    public static int getBackupMaxCountFromConfig() {
        try {
            // 库里的字段名就拼作 bakupConfigDays，别"修正"成 backupConfigDays
            Integer configCount = BaseModel.backupConfigDays.getValue();
            
            //校验配置值有效性（非正数则用默认值）
            if (configCount != null && configCount > 0) {
                // 限制最大备份数量，防止配置错误导致问题
                int maxLimit = 26; // A-Z最多26个
                return Math.min(configCount, maxLimit);
            } else {
                Log.error("BaseModel中备份份数配置无效（值：" + configCount + "），使用默认值" + 5);
                return 5;
            }
        } catch (Exception e) {
            // 捕获所有异常（BaseModel实例获取失败/方法调用失败等）
            Log.error("读取BaseModel备份配置失败，使用默认值" + 5);
            Log.printStackTrace("FileUtil.getBackupMaxCountFromConfig", e);
            return 5;
        }
    }
    
    /** 备份根目录 sesame/bak */
    public static File getBackupDirectoryFile() {
        File mainDir = getMainDirectoryFile();
        File backupDir = new File(mainDir, BACKUP_DIR_NAME);
        if (!backupDir.exists()) {
            backupDir.mkdirs(); // 不存在则创建
        }
        return backupDir;
    }
    
    /** 备份后缀 A/B/C…（index 0=A） */
    public static String getBackupSuffix(int index) {
        return String.valueOf((char) ('A' + index));
    }
    
    /** 指定用户当天是否已备份过 config_v2 */
    public static boolean isConfigV2BackedUpTodayForUser(String userId) {
        String safeUserId = StringUtil.isEmpty(userId) ? "default" : userId;
        File backupDir = getBackupDirectoryFile();
        int maxCount = getBackupMaxCountFromConfig(); // 动态获取份数
        
        // 遍历所有可能的备份后缀（A/B/C...）
        for (int i = 0; i < maxCount; i++) {
            String suffix = getBackupSuffix(i);
            File backupFile = new File(backupDir, BACKUP_FILE_PREFIX + safeUserId + "_" + suffix + BACKUP_FILE_EXT);
            if (backupFile.exists() && isFileModifiedToday(backupFile)) {
                return true; // 当天已有备份
            }
        }
        return false;
    }
    
    /** 文件是否为当天修改 */
    private static boolean isFileModifiedToday(File file) {
        long fileLastModified = file.lastModified();
        // 防护：文件不存在/无修改时间
        if (fileLastModified == 0L) {
            return false;
        }
        // 复用 Log 类的线程安全日期格式化器
        SimpleDateFormat dateFormat = Log.DATE_FORMAT_THREAD_LOCAL.get();
        if (dateFormat == null) {
            dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        }
        // 格式化文件修改日期和当前日期
        String fileDate = dateFormat.format(new Date(fileLastModified));
        String todayDate = dateFormat.format(new Date());
        // 对比日期字符串
        return fileDate.equals(todayDate);
    }
    
    /**
     * 该用户下一个要用的备份文件：按 A→B→C 顺序循环覆盖，不按修改时间挑最旧。
     */
    private static File findNextBackupFileForUser(String userId) {
        String safeUserId = StringUtil.isEmpty(userId) ? "default" : userId;
        File backupDir = getBackupDirectoryFile();
        int maxCount = getBackupMaxCountFromConfig(); // 动态获取份数
        
        // 步骤1：遍历所有后缀（A→B→C），找到第一个「不存在」的文件
        for (int i = 0; i < maxCount; i++) {
            String suffix = getBackupSuffix(i);
            File file = new File(backupDir, BACKUP_FILE_PREFIX + safeUserId + "_" + suffix + BACKUP_FILE_EXT);
            if (!file.exists()) {
                return file; // 找到未使用的后缀，返回该文件
            }
        }
        
        // 步骤2：所有后缀都已使用，找到「修改时间最早」的后缀文件（循环覆盖）
        // （注：这里保留时间排序是为了循环时覆盖最早的，保证A→B→C→A的逻辑）
        File oldestFile = null;
        long oldestTime = Long.MAX_VALUE;
        for (int i = 0; i < maxCount; i++) {
            String suffix = getBackupSuffix(i);
            File file = new File(backupDir, BACKUP_FILE_PREFIX + safeUserId + "_" + suffix + BACKUP_FILE_EXT);
            long fileTime = file.lastModified();
            if (fileTime < oldestTime) {
                oldestTime = fileTime;
                oldestFile = file;
            }
        }
        
        // 兜底：理论上不会为空，因为步骤1已确认所有文件都存在
        return oldestFile != null ? oldestFile : new File(backupDir, BACKUP_FILE_PREFIX + safeUserId + "_A" + BACKUP_FILE_EXT);
    }
    
    /**
     * 写盘前快照（覆盖式，不受「每日一次」限制）：保证每次覆盖前都留一份上一版，
     * 与 backupConfigV2WithRolling 的每日滚动解耦。
     */
    public static void backupConfigV2BeforeWrite(String userId) {
        try {
            File originalFile = StringUtil.isEmpty(userId) ? getDefaultConfigV2File() : getConfigV2File(userId);
            if (!originalFile.exists()) {
                return;
            }
            File prevFile = new File(originalFile.getParentFile(), "config_v2.prev.json");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.copy(originalFile.toPath(), prevFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Log.printStackTrace(FileUtil.class.getSimpleName(), e);
        }
    }

    /** config_v2 的 n 天滚动备份：每日一次，按 A→B→C 循环 */
    public static void backupConfigV2WithRolling(String userId) {
        BACKUP_MAX_COUNT = getBackupMaxCountFromConfig();
        // 1. 校验：当天已备份则跳过
        if (isConfigV2BackedUpTodayForUser(userId)) {
            Log.record(FileUtil.class.getSimpleName()+"#用户[" + (StringUtil.isEmpty(userId) ? "default" : userId) + "]当天已备份，跳过");
            return;
        }
        
        // 2. 获取原配置文件
        File originalFile = StringUtil.isEmpty(userId) ? getDefaultConfigV2File() : getConfigV2File(userId);
        if (!originalFile.exists()) {
            Log.error("原配置文件不存在，跳过备份: " + originalFile.getPath());
            return;
        }
        
        // 3. 找到该用户下一个要使用的备份文件（按A→B→C顺序）
        File targetFile = findNextBackupFileForUser(userId); // 替换为新的方法
        
        // 5. 执行备份（覆盖目标文件）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.copy(originalFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Log.record("备份成功🔄配置覆盖滚动" + BACKUP_MAX_COUNT + "次循环#用户:" + (StringUtil.isEmpty(userId) ? "default" : io.github.aw1y2z.sesame.util.idMap.UserIdMap.getAccountLabel(userId)) + "#备份文件:" + getBackupDirectoryFile().getPath() + "/" + targetFile.getName());
        } catch (IOException e) {
            Log.printStackTrace(FileUtil.class.getSimpleName(), e);
            Log.error("备份失败|用户: " + (StringUtil.isEmpty(userId) ? "default" : userId) + "|原因: " + e.getMessage());
        }
    }
    
    /** @return 该用户最新的备份文件 */
    public static File findLatestBackupFile(String userId) {
        String safeUserId = StringUtil.isEmpty(userId) ? "default" : userId;
        File backupDir = getBackupDirectoryFile();
        int maxCount = getBackupMaxCountFromConfig();
        
        File latestFile = null;
        long latestTime = 0;
        
        for (int i = 0; i < maxCount; i++) {
            String suffix = getBackupSuffix(i);
            File file = new File(backupDir, BACKUP_FILE_PREFIX + safeUserId + "_" + suffix + BACKUP_FILE_EXT);
            if (file.exists()) {
                long fileTime = file.lastModified();
                if (fileTime > latestTime) {
                    latestTime = fileTime;
                    latestFile = file;
                }
            }
        }
        
        return latestFile;
    }
    
    @SuppressWarnings("deprecation")
    private static File getMainDirectoryFile() {
        String storageDirStr = Environment.getExternalStorageDirectory() + File.separator + "Android" + File.separator + "media" + File.separator + ClassUtil.PACKAGE_NAME;
        File storageDir = new File(storageDirStr);
        File mainDir = new File(storageDir, CONFIG_DIRECTORY_NAME);
        if (mainDir.exists()) {
            if (mainDir.isFile()) {
                mainDir.delete();
                mainDir.mkdirs();
            }
        }
        else {
            mainDir.mkdirs();
            /*File oldDirectory = new File(Environment.getExternalStorageDirectory(), CONFIG_DIRECTORY_NAME);
            if (oldDirectory.exists()) {
                File deprecatedFile = new File(oldDirectory, "deprecated");
                if (!deprecatedFile.exists()) {
                    copyFile(oldDirectory, mainDirectory, "config.json");
                    copyFile(oldDirectory, mainDirectory, "friendId.list");
                    copyFile(oldDirectory, mainDirectory, "cooperationId.list");
                    copyFile(oldDirectory, mainDirectory, "reserveId.list");
                    copyFile(oldDirectory, mainDirectory, "statistics.json");
                    copyFile(oldDirectory, mainDirectory, "cityCode.json");
                    try {
                        deprecatedFile.createNewFile();
                    } catch (Throwable ignored) {
                    }
                }
            }*/
        }
        return mainDir;
    }
    
    private static File getLogDirectoryFile() {
        File logDir = new File(MAIN_DIRECTORY_FILE, "log");
        if (logDir.exists()) {
            if (logDir.isFile()) {
                logDir.delete();
                logDir.mkdirs();
            }
        }
        else {
            logDir.mkdirs();
        }
        return logDir;
    }
    
    private static File getConfigDirectoryFile() {
        File configDir = new File(MAIN_DIRECTORY_FILE, "config");
        if (configDir.exists()) {
            if (configDir.isFile()) {
                configDir.delete();
                configDir.mkdirs();
            }
        }
        else {
            configDir.mkdirs();
        }
        return configDir;
    }
    
    public static File getUserConfigDirectoryFile(String userId) {
        File configDir = getUserDataDirectoryFile(userId);
        if (configDir.exists()) {
            if (configDir.isFile()) {
                configDir.delete();
                configDir.mkdirs();
            }
        }
        else {
            configDir.mkdirs();
        }
        return configDir;
    }
    
    public static File getDefaultConfigV2File() {
        return new File(MAIN_DIRECTORY_FILE, "config_v2.json");
    }
    
    public static boolean setDefaultConfigV2File(String json) {
        return write2File(json, new File(MAIN_DIRECTORY_FILE, "config_v2.json"));
    }
    
    public static File getConfigV2File(String userId) {
        File file = getFile(getUserDataDirectoryFile(userId), "config_v2.json");
        if (!file.exists() && isSafeUserId(userId)) {
            File oldFile = new File(CONFIG_DIRECTORY_FILE, "config_v2-" + userId + ".json");
            if (oldFile.exists()) {
                if (write2File(readFromFile(oldFile), file)) {
                    oldFile.delete();
                }
                else {
                    file = oldFile;
                }
            }
        }
        return file;
    }
    
    public static boolean setConfigV2File(String userId, String json) {
        return write2File(json, getFile(getUserDataDirectoryFile(userId), "config_v2.json"));
    }
    
    public static File getTokenConfigFile() {
        return new File(MAIN_DIRECTORY_FILE, "token_config.json");
    }

    /**
     * 宿主私有目录 sesame-M（与共享目录同名，仅宿主可读写）。必须在读令牌前调用：
     * 未初始化时 {@link #getHttpTokenFile()} 直接抛错而不回退共享目录——那里的令牌等于公开。
     */
    public static void initPrivateDirectory(Context context) {
        if (privateDirectoryFile != null || context == null) {
            return;
        }
        File dir = new File(context.getFilesDir(), CONFIG_DIRECTORY_NAME);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.error("私有目录创建失败：" + dir);
            return;
        }
        privateDirectoryFile = dir;
    }

    /** 本地 HTTP 鉴权令牌：首次启动随机生成，之后复用（硬编码会随 APK 分发泄漏） */
    public static File getHttpTokenFile() {
        File dir = privateDirectoryFile;
        if (dir == null) {
            throw new IllegalStateException("私有目录尚未初始化，拒绝在共享目录读写令牌");
        }
        return new File(dir, "http_token.txt");
    }

    /** 迁移前令牌所在的共享路径：兼容期只写副本，不再作为读取来源 */
    public static File getLegacyHttpTokenFile() {
        return new File(MAIN_DIRECTORY_FILE, "http_token.txt");
    }
    
    public static boolean setTokenConfigFile(String json) {
        return write2File(json, new File(MAIN_DIRECTORY_FILE, "token_config.json"));
    }
    
    /** 账号序号映射（uid -> N）：首次出现时分配并持久化，只增不改，保证日志与配置页的「账号N」指向同一账号 */
    public static File getAccountIndexFile() {
        return getFile(MAIN_DIRECTORY_FILE, "accountIndex.json");
    }
    
    public static File getSelfIdFile(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "self.json");
    }
    
    public static File getFriendIdMapFile(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "friend.json");
    }
    
    public static File runtimeInfoFile(String userId) {
        return ensureFileExists(getFile(getUserDataDirectoryFile(userId), "runtimeInfo.json"));
    }
    
    public static File getCooperationIdMapFile(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "cooperation.json");
    }
    
    public static File getVitalityBenefitIdMap(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "vitalityBenefit.json");
    }
    
    public static File getGameCenterMallItemMap(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "gameCenterMallItem.json");
    }
    
    public static File getFarmOrnamentsIdMapFile(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "farmOrnaments.json");
    }
    
    public static File getMemberBenefitIdMapFile(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "memberBenefit.json");
    }
    
    public static File getPromiseSimpleTemplateIdMapFile(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "promiseSimpleTemplate.json");
    }
    
    /**
     * 账号数据目录；userId 为空或不合法时回退主目录：既避免 {@code new File(dir, null)} 抛 NPE，
     * 也防止 {@code ..} 拼出目录之外的路径。
     */
    private static File getUserDataDirectoryFile(String userId) {
        if (!isSafeUserId(userId)) {
            return MAIN_DIRECTORY_FILE;
        }
        return new File(CONFIG_DIRECTORY_FILE, userId);
    }

    /** uid 能否安全用作目录名（空 uid 表示「默认」配置，走主目录回退） */
    private static boolean isSafeUserId(String userId) {
        return userId != null && USER_ID_PATTERN.matcher(userId).matches();
    }

    /**
     * 取文件助手：路径同名处若被历史脏数据占成目录，先删掉再返回。
     * <p>⚠️ 方法体必须自己 {@code new File(...)}，改成调用自身会立刻 StackOverflowError。
     */
    private static File getFile(File dir, String name) {
        File file = new File(dir, name);
        if (file.exists() && file.isDirectory()) {
            file.delete();
        }
        return file;
    }

    /**
     * 运行时/日志文件首次使用时落地空文件，否则后续追加会失败。
     * <p>⚠️ 同理必须自己做 exists/createNewFile，不能改成调用自身。
     */
    private static File ensureFileExists(File file) {
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (Throwable t) {
                // 建不出空文件不致命（后续写入会各自报错），但要留痕，否则只看到「写不进去」
                Log.debug(TAG + " 建文件失败: " + file.getName() + " " + t);
            }
        }
        return file;
    }

    public static File getStatusFile(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "status.json");
    }
    
    /**
     * 账号维度的统计文件；userId 为空时回退主目录。
     * <p>必须按账号分文件，否则切号会把上一个账号的日/月值带过来。
     */
    public static File getStatisticsFile(String userId) {
        return getFile(getUserDataDirectoryFile(userId), "statistics.json");
    }

    /** 未按账号分目录的旧统计文件：只作一次性迁移的来源，迁完不再使用 */
    public static File getLegacyStatisticsFile() {
        return getFile(MAIN_DIRECTORY_FILE, "statistics.json");
    }

    /** 已有统计文件的账号 uid（按目录名排序）；也用于判断旧的全局文件还能否当迁移来源 */
    public static List<String> listAccountStatisticsUids() {
        List<String> uids = new ArrayList<>();
        File[] dirs = CONFIG_DIRECTORY_FILE.listFiles();
        if (dirs == null) {
            return uids;
        }
        Arrays.sort(dirs, Comparator.comparing(File::getName));
        for (File dir : dirs) {
            if (dir.isDirectory() && new File(dir, "statistics.json").exists()) {
                uids.add(dir.getName());
            }
        }
        return uids;
    }
    
    public static File getTreeIdMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "tree.json");
    }
    
    public static File getReserveIdMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "reserve.json");
    }
    
    public static File getAnimalIdMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "animal.json");
    }
    
    public static File getMarathonIdMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "marathon.json");
    }
    
    public static File getNewAncientTreeIdMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "newAncientTree.json");
    }
    
    public static File getPlantSceneIdMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "PlantScene.json");
    }
    
    public static File getrpcRequestMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "rpcRequest.json");
    }
    
    public static File getBeachIdMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "beach.json");
    }
    
    public static File getForestHuntIdMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "ForestHunt.json");
    }
    
    public static File getMemberCreditSesameTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "MemberCreditSesameTask.json");
    }
    
    public static File getAntForestVitalityTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntForestVitalityTask.json");
    }
    
    public static File getAntForestHuntTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntForestHuntTask.json");
    }
    
    public static File getMonopolyTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "MonopolyTask.json");
    }
    
    public static File getAntFarmDoFarmTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntFarmDoFarmTask.json");
    }
    
    public static File getAntFarmDrawMachineTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntFarmDrawMachineTask.json");
    }

    public static File getAntKitchenTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntKitchenTask.json");
    }

    public static File getAntDodoTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntDodoTask.json");
    }

    public static File getAntOceanAntiepTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntOceanAntiepTask.json");
    }

    public static File getAntOceanFishBlackListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntOceanFishBlack.json");
    }
    
    public static File getAntOrchardTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntOrchardTask.json");
    }

    public static File getOrchardChouChouLeTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "OrchardChouChouLeTask.json");
    }

    public static File getWelfareFundTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "WelfareFundTask.json");
    }

    public static File getGoldTicketTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "GoldTicketTask.json");
    }

    public static File getGoldenBeansTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "GoldenBeansTask.json");
    }

    public static File getGoldenBeansMallItemMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "GoldenBeansMallItem.json");
    }
    
    /** 自动拉黑记录（含日期），用于"超期自动解禁重试" */
    public static File getAutoBlackListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AutoBlackList.json");
    }
    
    public static File getAntStallTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntStallTask.json");
    }
    
    public static File getAntSportsTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntSportsTask.json");
    }

    public static File getPathThemeMapListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "PathThemeMapList.json");
    }
    
    public static File getAntMemberTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntMemberTask.json");
    }

    public static File getAntMemberAdTaskListMapFile() {
        return getFile(MAIN_DIRECTORY_FILE, "AntMemberAdTask.json");
    }
    
    public static File getExportedStatisticsFile() {
        String storageDirStr = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + File.separator + CONFIG_DIRECTORY_NAME;
        File storageDir = new File(storageDirStr);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        File exportedStatisticsFile = getFile(storageDir, "statistics.json");
        return exportedStatisticsFile;
    }
    
    public static File getFriendWatchFile() {
        File friendWatchFile = getFile(MAIN_DIRECTORY_FILE, "friendWatch.json");
        return friendWatchFile;
    }
    
    public static File getWuaFile() {
        if (wuaFile == null) {
            wuaFile = new File(MAIN_DIRECTORY_FILE, "wua.list");
        }
        return wuaFile;
    }
    
    public static File exportFile(File file) {
        String exportDirStr = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + File.separator + CONFIG_DIRECTORY_NAME;
        File exportDir = new File(exportDirStr);
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        File exportFile = getFile(exportDir, file.getName());
        if (FileUtil.copyTo(file, exportFile)) {
            return exportFile;
        }
        return null;
    }
    
    public static File getCityCodeFile() {
        if (cityCodeFile == null) {
            cityCodeFile = getFile(MAIN_DIRECTORY_FILE, "cityCode.json");
        }
        return cityCodeFile;
    }
    
    public static File getRuntimeLogFile() {
        File runtimeLogFile = getFile(LOG_DIRECTORY_FILE, Log.getLogFileName("runtime"));
        return ensureFileExists(runtimeLogFile);
    }
    
    public static File getDebugLogFile() {
        File debugLogFile = getFile(LOG_DIRECTORY_FILE, Log.getLogFileName("debug"));
        return ensureFileExists(debugLogFile);
    }
    
    public static File getForestLogFile() {
        File forestLogFile = getFile(LOG_DIRECTORY_FILE, Log.getLogFileName("forest"));
        return ensureFileExists(forestLogFile);
    }
    
    public static File getFarmLogFile() {
        File farmLogFile = getFile(LOG_DIRECTORY_FILE, Log.getLogFileName("farm"));
        return ensureFileExists(farmLogFile);
    }
    
    public static File getOtherLogFile() {
        File otherLogFile = getFile(LOG_DIRECTORY_FILE, Log.getLogFileName("other"));
        return ensureFileExists(otherLogFile);
    }
    
    public static File getGoldenBeansLogFile() {
        File goldenBeansLogFile = getFile(LOG_DIRECTORY_FILE, Log.getLogFileName("goldenbeans"));
        return ensureFileExists(goldenBeansLogFile);
    }
    
    public static File getErrorLogFile() {
        File errorLogFile = getFile(LOG_DIRECTORY_FILE, Log.getLogFileName("error"));
        return ensureFileExists(errorLogFile);
    }
    
    public static void clearLog() {
        File[] files = LOG_DIRECTORY_FILE.listFiles();
        if (files == null) {
            return;
        }
        SimpleDateFormat sdf = Log.DATE_FORMAT_THREAD_LOCAL.get();
        if (sdf == null) {
            sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        }
        String today = sdf.format(new Date());
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(today + ".log")) {
                if (file.length() < 104_857_600) {
                    continue;
                }
            }
            try {
                file.delete();
            }
            catch (Exception e) {
                Log.printStackTrace(e);
            }
        }
    }
    
    public static String readFromFile(File f) {
        if (!f.exists()) {
            return "";
        }
        if (!f.canRead()) {
            try {
                Toast.show(f.getName() + "没有读取权限！", true);
            } catch (Throwable t) {
                // LSPosed 未注入时（独立 APP 模式）XposedModule 不可用，忽略 Toast 报错
            }
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (InputStreamReader isr = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            char[] chs = new char[1024];
            int len;
            while ((len = isr.read(chs)) >= 0) {
                result.append(chs, 0, len);
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
        return result.toString();
    }
    
    public static boolean write2File(String s, File f) {
        // 只对"已存在"的文件做可写性检查：File.canWrite() 对不存在的路径恒为 false
        // （access() 返回 ENOENT），无条件检查会让所有新文件都写不出来并误报没有写入权限。
        if (f.exists() && !f.canWrite()) {
            try {
                Toast.show(f.getAbsoluteFile() + "没有写入权限！", true);
            } catch (Throwable t) {
                // 「没有写入权限」已由返回 false 传达，Toast 失败只做低优先级留痕
                Log.debug("Toast 提示失败(没有写入权限): " + t);
            }
            return false;
        }
        if (f.isDirectory()) {
            f.delete();
        }
        f.getParentFile().mkdirs();
        // 临时文件名带上进程/线程/纳秒：模块 App 与注入进程可能同时写同一个文件，
        // 固定名 tmp 会被两方交叉写入，替换后得到的是损坏的目标文件
        File tmpFile = new File(f.getParentFile(), f.getName() + ".tmp"
                + android.os.Process.myPid() + "-" + Thread.currentThread().getId() + "-" + System.nanoTime());
        boolean written = false;
        boolean success = false;
        try {
            OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(tmpFile), StandardCharsets.UTF_8);
            try {
                osw.write(s);
                osw.flush();
                written = true;
            } finally {
                try {
                    osw.close();
                } catch (Throwable t) {
                    // 关闭失败说明数据可能没落全，按失败处理（原文件不动）
                    written = false;
                    Log.printStackTrace(TAG, t);
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
        if (written) {
            // rename() 自身就能原子覆盖目标文件，**不要先删原文件**：
            // 删除与重命名之间目标处于"不存在"状态，此刻进程被杀就等于配置丢了
            success = tmpFile.renameTo(f);
            if (!success) {
                // renameTo 在个别挂载点上会失败：退回 REPLACE_EXISTING
                // （不带 ATOMIC_MOVE —— 不支持原子移动的文件系统会直接抛异常）
                try {
                    java.nio.file.Files.move(tmpFile.toPath(), f.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    success = true;
                } catch (Throwable t) {
                    Log.printStackTrace(TAG, t);
                }
            }
        }
        if (!success && tmpFile.exists()) {
            // 替换失败：删掉临时文件，保留原文件（原实现是先删原文件，两次都失败就啥都不剩）
            tmpFile.delete();
        }
        return success;
    }
    
    public static boolean append2File(String s, File f) {
        if (f.exists() && !f.canWrite()) {
            try {
                Toast.show(f.getAbsoluteFile() + "没有写入权限！", true);
            } catch (Throwable t) {
                // 「没有写入权限」已由返回 false 传达，Toast 失败只做低优先级留痕
                Log.debug("Toast 提示失败(没有写入权限): " + t);
            }
            return false;
        }
        boolean success = false;
        OutputStreamWriter osw = null;
        try {
            osw = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
            osw.append(s);
            osw.flush();
            success = true;
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
        close(osw);
        return success;
    }
    
    public static boolean copyTo(File source, File dest) {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(createFile(dest))) {
            FileChannel inputChannel = fis.getChannel();
            FileChannel outputChannel = fos.getChannel();
            outputChannel.transferFrom(inputChannel, 0, inputChannel.size());
            return true;
        }
        catch (IOException e) {
            Log.printStackTrace(e);
        }
        return false;
    }
    
    public static boolean streamTo(InputStream source, OutputStream dest) {
        try {
            byte[] b = new byte[1024];
            int length;
            while ((length = source.read(b)) > 0) {
                dest.write(b, 0, length);
                dest.flush();
            }
            return true;
        }
        catch (IOException e) {
            Log.printStackTrace(e);
        }
        finally {
            try {
                if (source != null) {
                    source.close();
                }
            }
            catch (IOException e) {
                Log.printStackTrace(e);
            }
            try {
                if (dest != null) {
                    dest.close();
                }
            }
            catch (IOException e) {
                Log.printStackTrace(e);
            }
        }
        return false;
    }
    
    public static void close(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }
    
    public static File createFile(File file) {
        if (file.exists() && file.isDirectory()) {
            if (!file.delete()) {
                return null;
            }
        }
        if (!file.exists()) {
            try {
                File parentFile = file.getParentFile();
                if (parentFile != null) {
                    parentFile.mkdirs();
                }
                if (!file.createNewFile()) {
                    return null;
                }
            }
            catch (Exception e) {
                Log.printStackTrace(e);
                return null;
            }
        }
        return file;
    }
    
    public static File createDirectory(File file) {
        if (file.exists() && file.isFile()) {
            if (!file.delete()) {
                return null;
            }
        }
        if (!file.exists()) {
            try {
                if (!file.mkdirs()) {
                    return null;
                }
            }
            catch (Exception e) {
                Log.printStackTrace(e);
                return null;
            }
        }
        return file;
    }
    
    public static Boolean clearFile(File file) {
        if (file.exists()) {
            OutputStreamWriter fileWriter = null;
            try {
                fileWriter = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
                fileWriter.write("");
                fileWriter.flush();
                return true;
            }
            catch (IOException e) {
                Log.printStackTrace(e);
            }
            finally {
                try {
                    if (fileWriter != null) {
                        fileWriter.close();
                    }
                }
                catch (IOException e) {
                    Log.printStackTrace(e);
                }
            }
        }
        return false;
    }
    
    /**
     * 清空某类日志：截断该类所有日期文件，保留文件本身以免破坏已打开的句柄。
     * @param logName runtime/record/forest/farm/other/debug/error
     */
    public static void clearLog(String logName) {
        try {
            File[] files = LOG_DIRECTORY_FILE.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f.isFile() && f.getName().startsWith(logName + ".")) {
                    clearFile(f);
                }
            }
        } catch (Throwable t) {
            Log.debug(TAG + " 清空日志失败: " + logName + " " + t);
        }
    }

    public static Boolean deleteFile(File file) {
        if (!file.exists()) {
            return false;
        }
        if (file.isFile()) {
            return file.delete();
        }
        File[] files = file.listFiles();
        if (files == null) {
            return file.delete();
        }
        for (File innerFile : files) {
            deleteFile(innerFile);
        }
        return file.delete();
    }
}
