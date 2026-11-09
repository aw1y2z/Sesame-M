package io.github.aw1y2z.sesame.util.idMap;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.github.aw1y2z.sesame.util.FileUtil;
import io.github.aw1y2z.sesame.util.JsonUtil;
import io.github.aw1y2z.sesame.util.Log;

/**
 * idMap 系列（29 个 `Map&lt;String,String&gt;` 缓存类）的公共实现。
 * <p>原先每个类都各写一遍完全相同的「内存 Map + 只读视图 + get/getMap/add/remove/load/save/clear」，
 * 现在逻辑集中在这里，各 idMap 类退化为只声明"文件从哪来"的门面，方法的静态签名保持不变（调用方无需改动）。
 * <p>同步语义与原先一致：原先是对各自的 Class 对象上锁，现在是对各自的 store 实例上锁——
 * 一个 idMap 类一个实例，互不干扰。
 * <p>不适用者保持原样：{@code AutoBlackListMap}（put/keys/懒加载/私有构造）与
 * {@code UserIdMap}（值为 UserEntity，含账号序号与反射）。
 */
class StringMapStore {

    private final Map<String, String> idMap = new ConcurrentHashMap<>();

    private final Map<String, String> readOnlyIdMap = Collections.unmodifiableMap(idMap);

    /**
     * 文件定位：不带账号的 idMap 忽略入参 userId；带账号的 idMap 用它拼出路径。
     */
    private final Function<String, File> fileProvider;

    /**
     * 内存是否已与磁盘同步过。注入进程里的调用点大多只 save 不 load，
     * 此时内存是空表，整份覆盖写会把模块 App 进程写入的条目全部抹掉。
     */
    private boolean loaded;

    /** 本次同步后被移除的键：从磁盘合并时不能把它们补回来 */
    private final Set<String> removedKeys = new HashSet<>();

    StringMapStore(Function<String, File> fileProvider) {
        this.fileProvider = fileProvider;
    }

    Map<String, String> getMap() {
        return readOnlyIdMap;
    }

    String get(String key) {
        return idMap.get(key);
    }

    synchronized void add(String key, String value) {
        removedKeys.remove(key);
        idMap.put(key, value);
    }

    synchronized void remove(String key) {
        removedKeys.add(key);
        idMap.remove(key);
    }

    /** 先清空再整份载入；文件不存在或内容为空时保持为空，解析失败只记栈不抛 */
    synchronized void load(String userId) {
        idMap.clear();
        removedKeys.clear();
        try {
            String body = FileUtil.readFromFile(fileProvider.apply(userId));
            if (!body.isEmpty()) {
                Map<String, String> newMap = JsonUtil.parseObject(body, new TypeReference<Map<String, String>>() {
                });
                idMap.putAll(newMap);
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        loaded = true;
    }

    /**
     * 整份覆盖写回。未同步过（只 save 没 load）时先把磁盘已有条目补进内存再写，
     * 否则一次保存就把另一个进程写进去的内容整份抹掉；内存里的新值优先。
     */
    synchronized boolean save(String userId) {
        if (!loaded) {
            mergeFromDisk(userId);
        }
        return FileUtil.write2File(JsonUtil.toJsonString(idMap), fileProvider.apply(userId));
    }

    /** 把磁盘上内存没有的条目补进来；本次被 remove 的键跳过，避免删除被撤销 */
    private void mergeFromDisk(String userId) {
        loaded = true;
        try {
            String body = FileUtil.readFromFile(fileProvider.apply(userId));
            if (body.isEmpty()) {
                return;
            }
            Map<String, String> onDisk = JsonUtil.parseObject(body, new TypeReference<Map<String, String>>() {
            });
            for (Map.Entry<String, String> entry : onDisk.entrySet()) {
                if (!removedKeys.contains(entry.getKey())) {
                    idMap.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 显式清空后内存即为权威状态，后续 save 不再从磁盘补回旧条目 */
    synchronized void clear() {
        idMap.clear();
        removedKeys.clear();
        loaded = true;
    }

}
