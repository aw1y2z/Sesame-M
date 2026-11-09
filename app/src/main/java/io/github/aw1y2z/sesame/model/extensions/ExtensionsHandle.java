package io.github.aw1y2z.sesame.model.extensions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Objects;

import io.github.aw1y2z.sesame.data.TokenConfig;
import io.github.aw1y2z.sesame.hook.Toast;
import io.github.aw1y2z.sesame.model.task.antSports.AntSportsRpcCall;
import io.github.aw1y2z.sesame.model.task.protectEcology.ProtectTreeRpcCall;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.Status;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;
import io.github.aw1y2z.sesame.util.idMap.PathThemeMapListMap;

public class ExtensionsHandle {
    private static final String TAG = ExtensionsHandle.class.getSimpleName();

    public static void handleRequest(String type, String fun, Object data) {
        // type 来自广播 extra，可能取不到：switch(null) 会抛 NPE，而调用点在裸线程里
        if (type == null) {
            Log.i(TAG, "扩展请求缺少 type，已忽略");
            return;
        }
        if (handleAlphaRequest(type, fun, data) != null) {
            return;
        }
        switch (type) {
            case "antForest":
                if (Objects.equals("getWateredItems", fun)) {
                    getWateredItems();
                }else if (Objects.equals("getWateringItems", fun)) {
                    getWateringItems();
                }else if (Objects.equals("getTreeItems", fun)) {
                    getTreeItems();
                } else if (Objects.equals("getNewTreeItems", fun)) {
                    getNewTreeItems();
                } else if (Objects.equals("queryAreaTrees", fun)) {
                    queryAreaTrees();
                } else if (Objects.equals("getUnlockTreeItems", fun)) {
                    getUnlockTreeItems();
                } else if (Objects.equals("fillWateredFriendList", fun)) {
                    fillWateredFriendList();
                }
                break;
            case "setCustomWalkPathIdList":
                addCustomWalkPathIdList((String) data);
                break;
            case "setCustomWalkPathIdQueue":
                if (Objects.equals("addCustomWalkPathIdQueue", fun)) {
                    addCustomWalkPathIdQueue((String) data);
                } else if (Objects.equals("clearCustomWalkPathIdQueue", fun)) {
                    clearCustomWalkPathIdQueue();
                }
                break;
        }
    }

    public static Object handleAlphaRequest(String type, String fun, Object data) {
        try {
            return Class.forName("io.github.aw1y2z.sesame.model.extensions.ExtensionsHandleAlpha")
                    .getMethod("handleAlphaRequest", String.class, String.class, Object.class)
                    .invoke(null, type, fun, data);
        } catch (Throwable e) {
            // catch Throwable 而非 Exception：类缺失/构造失败/静态初始化异常都可能是 Error
            // （ExtensionAlpha 未打包、字段初始化抛 NoClassDefFoundError 等），
            // 调用方 AntSports.walk 等没有外层 try，漏出去会打断任务主循环
            return null;
        }
    }
    private static void getWateredItems() {
        Status.getWateredFriendToday();
    }
    
    private static void getWateringItems() {
        Status.getWateringFriendToday();
    }

    private static void fillWateredFriendList() {
        Status.fillWateredFriendList();
    }
    
    private static void getNewTreeItems() {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeItemsForExchange("COMING", "project"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray ja = jo.getJSONArray("treeItems");
            if (ja.length() == 0) {
                Log.forest("新树上苗🌱[当前没有新树上苗信息!]");
                return;
            }
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                if (!jo.has("projectType"))
                    continue;
                if (!"TREE".equals(jo.getString("projectType")))
                    continue;
                if (!"COMING".equals(jo.getString("applyAction")))
                    continue;
                String projectId = jo.getString("itemId");
                queryTreeForExchange(projectId);
            }
        } catch (Throwable t) {
            Log.err(TAG, "getTreeItems err:", t);
        }
    }

    private static void queryTreeForExchange(String projectId) {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeForExchange(projectId));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject exchangeableTree = jo.optJSONObject("exchangeableTree");
            if (exchangeableTree == null) {
                return;
            }
            int currentBudget = exchangeableTree.optInt("currentBudget", 0);
            String region = exchangeableTree.optString("region", "");
            String treeName = exchangeableTree.optString("treeName", "");
            String tips = "不可合种";
            if (exchangeableTree.optBoolean("canCoexchange", false)) {
                JSONObject extendInfo = exchangeableTree.optJSONObject("extendInfo");
                String cooperateTypes = extendInfo == null ? "" : extendInfo.optString("cooperate_template_id_list", "");
                tips = StringUtil.isEmpty(cooperateTypes) ? "可以合种" : "可以合种-合种类型：" + cooperateTypes;
            }
            Log.forest("新树上苗🌱[" + region + "-" + treeName + "]#" + currentBudget + "株-" + tips);
        } catch (Throwable t) {
            Log.err(TAG, "queryTreeForExchange err:", t);
        }
    }

    private static void getTreeItems() {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeItemsForExchange("AVAILABLE,ENERGY_LACK", "project"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray ja = jo.getJSONArray("treeItems");
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                if (!jo.has("projectType"))
                    continue;
                String projectId = jo.getString("itemId");
                String itemName = jo.getString("itemName");
                getTreeCurrentBudget(projectId, itemName);
                TimeUtil.sleep(100);
            }
        } catch (Throwable t) {
            Log.err(TAG, "getTreeItems err:", t);
        }
    }

    private static void getTreeCurrentBudget(String projectId, String treeName) {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeForExchange(projectId));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                JSONObject exchangeableTree = jo.optJSONObject("exchangeableTree");
                if (exchangeableTree == null) {
                    return;
                }
                int currentBudget = exchangeableTree.optInt("currentBudget", 0);
                String region = exchangeableTree.optString("region", "");
                Log.forest("树苗查询🌱[" + region + "-" + treeName + "]#剩余:" + currentBudget);
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryTreeForExchange err:", t);
        }
    }

    private static void queryAreaTrees() {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryAreaTrees());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject areaTrees = jo.getJSONObject("areaTrees");
            JSONObject regionConfig = jo.getJSONObject("regionConfig");
            Iterator<String> regionKeys = regionConfig.keys();
            while (regionKeys.hasNext()) {
                String regionKey = regionKeys.next();
                if (!areaTrees.has(regionKey)) {
                    JSONObject region = regionConfig.getJSONObject(regionKey);
                    String regionName = region.optString("regionName");
                    Log.forest("未解锁地区🗺️[" + regionName + "]");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryAreaTrees err:", t);
        }
    }

    private static void getUnlockTreeItems() {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeItemsForExchange("", "project"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray ja = jo.getJSONArray("treeItems");
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                if (!jo.has("projectType"))
                    continue;
                int certCountForAlias = jo.optInt("certCountForAlias", -1);
                if (certCountForAlias == 0) {
                    String itemName = jo.optString("itemName");
                    String region = jo.optString("region");
                    String organization = jo.optString("organization");
                    Log.forest("未解锁项目🐘[" + region + "-" + itemName + "]#" + organization);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "getUnlockTreeItems err:", t);
        }
    }

    private static void addCustomWalkPathIdList(String pathId) {
        if (!StringUtil.isEmpty(pathId)) {
            String pathName = AntSportsRpcCall.queryPathName(pathId);
            if (pathName == null) {
                Toast.show("添加自定义路线列表失败:找不到路线信息");
                return;
            }
            PathThemeMapListMap.load();
            PathThemeMapListMap.add(pathId, pathName);
            PathThemeMapListMap.save();
            Toast.show("添加自定义路线列表成功:" + pathName);
        }
    }

    private static void addCustomWalkPathIdQueue(String pathId) {
        if (!StringUtil.isEmpty(pathId)) {
            String pathName = AntSportsRpcCall.queryPathName(pathId);
            if (pathName == null) {
                Toast.show("添加待行走路线队列失败:找不到路线信息");
                return;
            }
            if (TokenConfig.addCustomWalkPathIdQueue(pathId)) {
                Toast.show("添加待行走路线队列成功:" + pathName);
            }
        }
    }

    private static void clearCustomWalkPathIdQueue() {
        if (TokenConfig.clearCustomWalkPathIdQueue()) {
            Toast.show("清除待行走路线队列成功");
        }
    }
}
