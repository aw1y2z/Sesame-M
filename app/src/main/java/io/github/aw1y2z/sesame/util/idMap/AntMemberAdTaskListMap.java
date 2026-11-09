package io.github.aw1y2z.sesame.util.idMap;

import java.util.Map;

import io.github.aw1y2z.sesame.util.FileUtil;

/** 会员广告任务的任务名映射；与 {@link AntMemberTaskListMap} 分开存，两个下拉框各显示自己的候选。 */
public class AntMemberAdTaskListMap {

    private static final StringMapStore STORE = new StringMapStore(ignoredUserId -> FileUtil.getAntMemberAdTaskListMapFile());

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
