package io.github.aw1y2z.sesame.util.idMap;

import java.util.Map;

import io.github.aw1y2z.sesame.util.FileUtil;

/** 小鸡大厨（AI 厨房）任务标题表：供黑名单字段的候选项与执行时的跳过判定 */
public class AntKitchenTaskListMap {

    private static final StringMapStore STORE = new StringMapStore(ignoredUserId -> FileUtil.getAntKitchenTaskListMapFile());

    public static Map<String, String> getMap() {
        return STORE.getMap();
    }

    public static String get(String key) {
        return STORE.get(key);
    }

    public static void add(String key, String value) {
        STORE.add(key, value);
    }

    public static void remove(String key) {
        STORE.remove(key);
    }

    public static void load() {
        STORE.load(null);
    }

    public static boolean save() {
        return STORE.save(null);
    }

    public static void clear() {
        STORE.clear();
    }

}
