package io.github.aw1y2z.sesame.util;

import android.content.Context;

import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.File;
import java.util.Calendar;
import java.util.List;

import io.github.aw1y2z.sesame.R;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;
import androidx.annotation.Keep;
import lombok.Data;

@Data
public class Statistics {
    
    private static final String TAG = Statistics.class.getSimpleName();
    
    public static final Statistics INSTANCE = new Statistics();
    
    private TimeStatistics year = new TimeStatistics();
    private TimeStatistics month = new TimeStatistics();
    private TimeStatistics day = new TimeStatistics();

    /**
     * 内存里这份统计属于哪个账号：与 {@code Status} 同构。
     * <p>统计文件按账号分目录，落盘若用「当前 uid」而不是「内存状态的归属」，
     * 切号落在两步之间就会把 A 账号的账写进 B 账号的文件。
     */
    private static volatile String loadedUid;

    /** 本轮（一次执行周期）收取的能量。仅内存计数、不落盘（静态字段不进 Jackson） */
    private static volatile int roundCollected = 0;

    /** 本次加载是否解析失败：内存只剩默认值时不得落盘，否则历史月/年累计被清零 */
    private static volatile boolean loadFailed = false;

    /** 本轮收取能量 */
    public static int getRoundCollected() {
        return roundCollected;
    }

    /**
     * 新一轮开始时清零本轮收取计数。
     * <p>synchronized：与 addData 的 {@code roundCollected += i} 互斥，
     * 否则清零点正好落在累加中间会丢掉这一次累加（表现为通知里「本轮收取能量」偏小）。
     */
    public static synchronized void resetRoundCollected() {
        roundCollected = 0;
    }
    
    /**
     * 用服务端权威值**覆盖**今日收取能量（而非累加）。
     * <p>逐点 {@link #addData} 只要漏掉一个入口，当日账目就永久偏小，故改由服务端明细直接定值。
     * <p>month/year 按差值同步追加：这两个是历史累计维度，被覆盖的今日值此前已计入它们。
     *
     * @param todayCollected 服务端给出的今日收取能量；传负数表示无效值、忽略
     * @return 本次覆盖造成的日维度增量（可能为负）
     */
    public static synchronized int setDayCollectedFromServer(int todayCollected) {
        if (todayCollected < 0) {
            return 0;
        }
        int delta = todayCollected - INSTANCE.day.collected;
        if (delta == 0) {
            return 0;
        }
        INSTANCE.day.collected = todayCollected;
        INSTANCE.month.collected += delta;
        INSTANCE.year.collected += delta;
        return delta;
    }

    /**
     * synchronized：与 save/load/unload 共用 Statistics.class 监视器。
     * <p>各个模块跑在各自线程上、都会累加同一个计数器，原先的 += 既会丢更新，
     * 也可能正好撞上 save() 的序列化过程。
     */
    public static synchronized void addData(DataType dt, int i) {
        // 累加前先对齐归属：否则这一笔会记到别的账号头上
        ensureLoadedForCurrentUid();
        Statistics stat = INSTANCE;
        switch (dt) {
            case COLLECTED:
                stat.day.collected += i;
                stat.month.collected += i;
                stat.year.collected += i;
                roundCollected += i;
                break;
            case HELPED:
                stat.day.helped += i;
                stat.month.helped += i;
                stat.year.helped += i;
                break;
            case WATERED:
                stat.day.watered += i;
                stat.month.watered += i;
                stat.year.watered += i;
                break;
            case WATEREDCOUNT:
                stat.day.wateredcount += i;
                stat.month.wateredcount += i;
                stat.year.wateredcount += i;
                break;
            case WATERINGCOUNT:
                stat.day.wateringcount += i;
                stat.month.wateringcount += i;
                stat.year.wateringcount += i;
                break;
        }
    }
    
    public static synchronized int getData(TimeType tt, DataType dt) {
        ensureLoadedForCurrentUid();
        return INSTANCE.dataOf(tt, dt);
    }

    /** 读**本实例**的数据：界面要按账号分页并排显示，不能都去读单例 */
    public int dataOf(TimeType tt, DataType dt) {
        TimeStatistics ts;
        switch (tt) {
            case YEAR:
                ts = year;
                break;
            case MONTH:
                ts = month;
                break;
            default:
                ts = day;
                break;
        }
        switch (dt) {
            case TIME:
                return ts.time;
            case COLLECTED:
                return ts.collected;
            case HELPED:
                return ts.helped;
            case WATERED:
                return ts.watered;
            case WATEREDCOUNT:
                return ts.wateredcount;
            case WATERINGCOUNT:
                return ts.wateringcount;
            default:
                return 0;
        }
    }

    /**
     * 把若干账号的统计相加成「合计」：界面默认显示全部账号的合计。
     * <p>只用于展示；三档的 time 取各账号最大值，免得被 {@link #updateDay} 当成「从未跨天」而重置。
     */
    public static Statistics aggregate(List<Statistics> list) {
        Statistics total = new Statistics();
        int yearTime = 0;
        int monthTime = 0;
        int dayTime = 0;
        for (Statistics s : list) {
            if (s == null) {
                continue;
            }
            total.year.add(s.year);
            total.month.add(s.month);
            total.day.add(s.day);
            yearTime = Math.max(yearTime, s.year.time);
            monthTime = Math.max(monthTime, s.month.time);
            dayTime = Math.max(dayTime, s.day.time);
        }
        total.year.time = yearTime;
        total.month.time = monthTime;
        total.day.time = dayTime;
        return total;
    }
    
    public static String getText() {
        
        StringBuilder table = new StringBuilder();
        // 添加表头
        table.append("今年  收: ").append(getData(TimeType.YEAR, DataType.COLLECTED)).append(" 帮: ").append(getData(TimeType.YEAR, DataType.HELPED)).append(" 浇: ").append(getData(TimeType.YEAR, DataType.WATERED));
        table.append("\n今月  收: ").append(getData(TimeType.MONTH, DataType.COLLECTED)).append(" 帮: ").append(getData(TimeType.MONTH, DataType.HELPED)).append(" 浇: ").append(getData(TimeType.MONTH, DataType.WATERED));
        table.append("\n今日  收: ").append(getData(TimeType.DAY, DataType.COLLECTED)).append(" 帮: ").append(getData(TimeType.DAY, DataType.HELPED)).append(" 浇: ").append(getData(TimeType.DAY, DataType.WATERED));
        table.append("\n被水次数  日: ").append(getData(TimeType.DAY, DataType.WATEREDCOUNT)).append(" 月: ").append(getData(TimeType.MONTH, DataType.WATEREDCOUNT)).append(" 年: ").append(getData(TimeType.YEAR, DataType.WATEREDCOUNT));
        table.append("\n浇水次数  日: ").append(getData(TimeType.DAY, DataType.WATERINGCOUNT)).append(" 月: ").append(getData(TimeType.MONTH, DataType.WATERINGCOUNT)).append(" 年: ").append(getData(TimeType.YEAR, DataType.WATERINGCOUNT));
        return table.toString();
    }
    
    public static String getText(Context context) {
        return getText(context.getString(R.string.year), context.getString(R.string.month), context.getString(R.string.day), context.getString(R.string.collected), context.getString(R.string.helped), context.getString(R.string.watered), context.getString(R.string.wateredcount),
                context.getString(R.string.wateringcount));
    }
    
    public static String getText(String year, String month, String day, String collected, String helped, String watered, String wateredcount, String wateringcount) {
        return year + "  " + collected + ": " + getData(TimeType.YEAR, DataType.COLLECTED) + " " + helped + ": " + getData(TimeType.YEAR, DataType.HELPED) + " " + watered + ": " + getData(TimeType.YEAR, DataType.WATERED) +
               "\n" + month + "  " + collected + ": " + getData(TimeType.MONTH, DataType.COLLECTED) + " " + helped + ": " + getData(TimeType.MONTH, DataType.HELPED) + " " + watered + ": " + getData(TimeType.MONTH, DataType.WATERED) +
               "\n" + day + "  " + collected + ": " + getData(TimeType.DAY, DataType.COLLECTED) + " " + helped + ": " + getData(TimeType.DAY, DataType.HELPED) + " " + watered + ": " + getData(TimeType.DAY, DataType.WATERED) +
               "\n" + wateredcount + "  " + "日" + ": " + getData(TimeType.DAY, DataType.WATEREDCOUNT) + "; " + "月" + ": " + getData(TimeType.MONTH, DataType.WATEREDCOUNT) + ";  " + "年" + ": " + getData(TimeType.YEAR, DataType.WATEREDCOUNT) +
               "\n" + wateringcount + "  " + "日" + ": " + getData(TimeType.DAY, DataType.WATERINGCOUNT) + ";  " + "月" + ": " + getData(TimeType.MONTH, DataType.WATERINGCOUNT) + ";  " + "年" + ": " + getData(TimeType.YEAR, DataType.WATERINGCOUNT);
    }
    
    public static synchronized Statistics load() {
        loadFailed = false;
        String currentUid = UserIdMap.getCurrentUid();
        try {
            // 先声明归属再动文件：本方法内部会写文件，若该链路回调到 getData()，
            // loadedUid 未更新就会再进 load() 形成递归
            loadedUid = currentUid;
            // uid 未就绪时 getStatisticsFile 回退主目录，与「单账号时期」同路径
            File statisticsFile = FileUtil.getStatisticsFile(currentUid);
            if (!statisticsFile.exists()) {
                migrateLegacyStatistics(statisticsFile);
            }
            if (statisticsFile.exists()) {
                String json = FileUtil.readFromFile(statisticsFile);
                JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(json);
                String formatted = JsonUtil.toFormatJsonString(INSTANCE);
                if (formatted != null && !formatted.equals(json)) {
                    // 回写只允许改格式、不许改数据
                    if (JsonUtil.isRewriteLossless(json, formatted)) {
                        Log.i(TAG, "重新格式化 statistics.json");
                        FileUtil.write2File(formatted, statisticsFile);
                    } else {
                        Log.i(TAG, "statistics.json 加载结果与磁盘数据不一致，已跳过重新格式化");
                    }
                }
            }
            else {
                JsonUtil.copyMapper().updateValue(INSTANCE, new Statistics());
                Log.i(TAG, "初始化 statistics.json");
                FileUtil.write2File(JsonUtil.toFormatJsonString(INSTANCE), statisticsFile);
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            // 只回落内存默认值、不写盘：写盘会把历史累计清零
            loadFailed = true;
            Log.record("统计文件解析失败，本次使用默认值（不写盘，原文件保留）");
            try {
                JsonUtil.copyMapper().updateValue(INSTANCE, new Statistics());
            }
            catch (JsonMappingException e) {
                Log.printStackTrace(TAG, e);
            }
        }
        return INSTANCE;
    }

    /**
     * 读指定账号的统计，**不改动**单例：界面要按账号分页显示，一个账号读一份。
     * <p>与 {@link #load()} 不同，这里**不建文件、也不做迁移**：只读已存在的；没有就返回全零。
     * 否则界面一浏览就会给所有账号铺出空统计文件，令下文的一次性迁移判据失效。
     */
    public static Statistics readFrom(String userId) {
        Statistics statistics = new Statistics();
        try {
            File file = FileUtil.getStatisticsFile(userId);
            if (!file.exists()) {
                return statistics;
            }
            String json = FileUtil.readFromFile(file);
            JsonUtil.copyMapper().readerForUpdating(statistics).readValue(json);
            // 只动内存地按当前时间归零：模块当天还没跑时，首页不该还显示昨天的「今日」
            statistics.updateDayOf(Calendar.getInstance());
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
        return statistics;
    }

    /**
     * uid 与内存统计归属不一致（启动早期 uid 未就绪、切号）时，按当前 uid 重新加载。
     *
     * @return 对齐后**内存统计的归属 uid**；落盘目标必须以它为准
     */
    private static synchronized String ensureLoadedForCurrentUid() {
        String currentUid = UserIdMap.getCurrentUid();
        if (StringUtil.isEmpty(currentUid) || currentUid.equals(loadedUid)) {
            return loadedUid;
        }
        load();
        return loadedUid;
    }

    /**
     * 一次性迁移：账号维度的统计文件不存在、且还**没有任何账号**落过统计时，
     * 把单账号时期的全局文件复制过来，免得历史月/年累计归零。
     * <p>已有账号落过盘就不再迁：那份全局数据是多账号混在一起的，不该再让别的账号继承。
     */
    private static void migrateLegacyStatistics(File target) {
        try {
            File legacy = FileUtil.getLegacyStatisticsFile();
            if (legacy.equals(target) || !legacy.exists() || !FileUtil.listAccountStatisticsUids().isEmpty()) {
                return;
            }
            if (FileUtil.copyTo(legacy, target)) {
                Log.i(TAG, "已把单账号时期的 statistics.json 迁移到当前账号");
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }
    
    public static synchronized void unload() {
        try {
            JsonUtil.copyMapper().updateValue(INSTANCE, new Statistics());
        }
        catch (JsonMappingException e) {
            Log.printStackTrace(TAG, e);
        }
    }
    
    public static synchronized void save() {
        save(Calendar.getInstance());
    }
    
    public static synchronized void save(Calendar nowCalendar) {
        // 落盘目标按「内存统计的归属」取：切号可能正好落在「读到当前 uid」与「按该 uid 写盘」之间
        String uid = ensureLoadedForCurrentUid();
        if (StringUtil.isEmpty(uid)) {
            Log.i(TAG, "用户为空，统计保存跳过");
            return;
        }
        if (loadFailed) {
            // 内存只是默认值：写盘会把历史累计清零
            Log.debug(TAG + ", 统计文件加载失败，跳过保存");
            return;
        }
        if (updateDay(nowCalendar)) {
            Log.system(TAG, "重置 statistics.json");
        }
        else {
            // 每次落盘都记一行会淹没有效日志（实测约 75 行/天），降为由「抓包记录」开关控制的调试日志
            Log.debug(TAG + ", 保存 statistics.json");
        }
        FileUtil.write2File(JsonUtil.toFormatJsonString(INSTANCE), FileUtil.getStatisticsFile(uid));
    }
    
    public static Boolean updateDay(Calendar nowCalendar) {
        return INSTANCE.updateDayOf(nowCalendar);
    }

    /**
     * 按给定时刻把**本实例**的日/月/年计数归零（若已跨天）；只动内存、不落盘。
     * <p>界面按账号读文件时同样要就地归零：模块当天还没跑时，首页不该还显示昨天的「今日」。
     */
    public boolean updateDayOf(Calendar nowCalendar) {
        int ye = nowCalendar.get(Calendar.YEAR);
        int mo = nowCalendar.get(Calendar.MONTH) + 1;
        int da = nowCalendar.get(Calendar.DAY_OF_MONTH);
        if (ye != year.time) {
            year.reset(ye);
            month.reset(mo);
            day.reset(da);
        }
        else if (mo != month.time) {
            month.reset(mo);
            day.reset(da);
        }
        else if (da != day.time) {
            day.reset(da);
        }
        else {
            return false;
        }
        return true;
    }
    
    public enum TimeType {
        YEAR, MONTH, DAY
    }
    
    public enum DataType {
        TIME, COLLECTED, HELPED, WATERED, WATEREDCOUNT, WATERINGCOUNT
    }
    
    @Data
    @Keep
    public static class TimeStatistics {
        int time;
        int collected, helped, watered, wateredcount, wateringcount;
        
        public TimeStatistics() {
        }
        
        TimeStatistics(int i) {
            reset(i);
        }
        
        public void reset(int i) {
            time = i;
            collected = 0;
            helped = 0;
            watered = 0;
            wateredcount = 0;
            wateringcount = 0;
        }

        /** 供「全部账号合计」相加；time 不是计数，由调用方单独取最大值 */
        void add(TimeStatistics other) {
            collected += other.collected;
            helped += other.helped;
            watered += other.watered;
            wateredcount += other.wateredcount;
            wateringcount += other.wateringcount;
        }
    }
    
}
