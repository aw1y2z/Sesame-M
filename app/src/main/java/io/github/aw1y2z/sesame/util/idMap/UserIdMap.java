package io.github.aw1y2z.sesame.util.idMap;

import android.os.Handler;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.aw1y2z.sesame.util.XHelpers;
import lombok.Getter;

import io.github.aw1y2z.sesame.entity.UserEntity;
import io.github.aw1y2z.sesame.hook.ApplicationHook;
import io.github.aw1y2z.sesame.util.FileUtil;
import io.github.aw1y2z.sesame.util.JsonUtil;
import io.github.aw1y2z.sesame.util.Log;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UserIdMap {
    
    private static final Map<String, UserEntity> userMap = new ConcurrentHashMap<>();

    private static final Map<String, UserEntity> readOnlyUserMap = Collections.unmodifiableMap(userMap);

    /**
     * 通讯录好友表是否已载入完成。
     * <p>未载入时 userMap 是空的，任何 id 都查不到，此时不能拿它来判定「这个人是不是好友」，
     * 否则会把真实好友也当成陌生人（例如好友统计）。
     */
    private static volatile boolean friendListLoaded = false;

    /** @see #friendListLoaded */
    public static boolean isFriendListLoaded() {
        return friendListLoaded;
    }
    
    /** 当前账号；volatile：切号在别的线程发生，读方必须能看到最新值 */
    @Getter
    private static volatile String currentUid = null;
    
    public static Map<String, UserEntity> getUserMap() {
        return readOnlyUserMap;
    }
    
    public static Set<String> getUserIdSet() {
        return userMap.keySet();
    }

    /**
     * uid -> 账号序号（从 1 开始）。首次遇到时分配「当前最大序号 + 1」并落盘，
     * 只增不改、账号删除后序号不回收：否则历史日志里的「账号N」会指向别的账号。
     */
    private static final Map<String, Integer> accountIndexMap = new ConcurrentHashMap<>();
    private static volatile boolean accountIndexLoaded = false;

    private static synchronized void ensureAccountIndexLoaded() {
        if (accountIndexLoaded) {
            return;
        }
        try {
            String body = FileUtil.readFromFile(FileUtil.getAccountIndexFile());
            if (!body.isEmpty()) {
                Map<String, Integer> loaded = JsonUtil.parseObject(body, new TypeReference<Map<String, Integer>>() {
                });
                if (loaded != null) {
                    accountIndexMap.putAll(loaded);
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        accountIndexLoaded = true;
    }

    public static synchronized int getAccountIndex(String userId) {
        if (userId == null || userId.isEmpty()) {
            return -1;
        }
        ensureAccountIndexLoaded();
        Integer index = accountIndexMap.get(userId);
        if (index != null) {
            return index;
        }
        int next = 1;
        for (Integer used : accountIndexMap.values()) {
            if (used != null && used >= next) {
                next = used + 1;
            }
        }
        accountIndexMap.put(userId, next);
        try {
            FileUtil.write2File(JsonUtil.toJsonString(accountIndexMap), FileUtil.getAccountIndexFile());
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        return next;
    }

    /**
     * 日志与界面统一使用的账号简称，如「账号2」；uid 为空或分配失败时返回 null。
     * <p>已分配过的走无锁快路径（一次 map 查询），适合每条日志调用。
     */
    public static String getAccountLabel(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        try {
            ensureAccountIndexLoaded();
            Integer cached = accountIndexMap.get(userId);
            if (cached != null) {
                return "账号" + cached;
            }
            int index = getAccountIndex(userId);
            return index > 0 ? "账号" + index : null;
        } catch (Throwable t) {
            return null;
        }
    }
    
    public static Collection<UserEntity> getUserEntityCollection() {
        return userMap.values();
    }
    
    public synchronized static void initUser(String currentUserId) {
        setCurrentUserId(currentUserId);
        // mainHandler 只在 Service onCreate hook 内赋值：未就绪说明模块还没起来，
        // 此时加载用户数据必然失败，安静跳过并等下一次触发，不再让 NPE 冒泡成「加载失败」
        Handler handler = ApplicationHook.getMainHandler();
        if (handler == null) {
            Log.record("跳过用户数据加载：mainHandler 尚未就绪");
            return;
        }
        handler.post(() -> {
            ClassLoader loader;
            try {
                loader = ApplicationHook.getClassLoader();
            } catch (Exception e) {
                Log.i("Error getting classloader");
                return;
            }
            try {
                UserIdMap.unload();
                String selfId = ApplicationHook.getUserId();

                // 优先从「我的账号模型」取自身信息：直接描述当前登录账号，
                // 不依赖是否在好友列表中，新版支付宝下更可靠（避免新账号拿不到 self.json）。
                UserEntity selfEntity = buildSelfFromAccountModel();

                // 遍历本地好友列表：补全好友映射；若自身仍在其中且上面没拿到，则作为兜底。
                try {
                    Class<?> clsUserIndependentCache = loader.loadClass("com.alipay.mobile.socialcommonsdk.bizdata.UserIndependentCache");
                    Class<?> clsAliAccountDaoOp = loader.loadClass("com.alipay.mobile.socialcommonsdk.bizdata.contact.data.AliAccountDaoOp");
                    Object aliAccountDaoOp = XHelpers.callStaticMethod(clsUserIndependentCache, "getCacheObj", clsAliAccountDaoOp);
                    List<?> allFriends = (List<?>) XHelpers.callMethod(aliAccountDaoOp, "getAllFriends", new Object[0]);
                    if (!allFriends.isEmpty()) {
                        Class<?> friendClass = allFriends.get(0).getClass();
                        Field userIdField = XHelpers.findField(friendClass, "userId");
                        Field accountField = XHelpers.findField(friendClass, "account");
                        Field nameField = XHelpers.findField(friendClass, "name");
                        Field nickNameField = XHelpers.findField(friendClass, "nickName");
                        Field remarkNameField = XHelpers.findField(friendClass, "remarkName");
                        Field friendStatusField = XHelpers.findField(friendClass, "friendStatus");
                        for (Object userObject : allFriends) {
                            try {
                                String userId = (String) userIdField.get(userObject);
                                String account = (String) accountField.get(userObject);
                                String name = (String) nameField.get(userObject);
                                String nickName = (String) nickNameField.get(userObject);
                                String remarkName = (String) remarkNameField.get(userObject);
                                Integer friendStatus = (Integer) friendStatusField.get(userObject);
                                UserEntity userEntity = new UserEntity(userId, account, friendStatus, name, nickName, remarkName);
                                if (Objects.equals(selfId, userId)) {
                                    // 账号模型实体通常只有 uid/account 可靠，昵称/姓名反射读不到；
                                    // 与好友库资料合并，避免配置页昵称显示为 null
                                    selfEntity = mergeSelfEntity(selfEntity, userEntity);
                                }
                                UserIdMap.add(userEntity);
                            } catch (Throwable t) {
                                Log.i("addUserObject err:");
                                Log.printStackTrace(t);
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.i("load friends err:");
                    Log.printStackTrace(t);
                }

                if (selfEntity != null) {
                    UserIdMap.add(selfEntity);
                    UserIdMap.saveSelf(selfEntity);
                } else {
                    Log.record("未能获取自身账号信息，配置页副标题将回退为用户ID");
                }
                UserIdMap.save(selfId);
                friendListLoaded = true;
            } catch (Throwable t) {
                Log.i("checkUnknownId.run err:");
                Log.printStackTrace(t);
            }
        });
    }
    
    /**
     * 从「我的账号模型」(SocialSdkContactService#getMyAccountInfoModelByLocal) 构造自身 UserEntity。
     * 该模型直接描述当前登录账号，比从好友列表中匹配自己更可靠，避免新账号拿不到 self.json。
     */
    private static UserEntity buildSelfFromAccountModel() {
        try {
            Object selfObj = ApplicationHook.getUserObject();
            if (selfObj == null) {
                return null;
            }
            String uid = readField(selfObj, "userId");
            if (uid == null || uid.isEmpty()) {
                return null;
            }
            return new UserEntity(
                    uid,
                    readField(selfObj, "account", "loginId"),
                    null,
                    readField(selfObj, "realName", "name", "userName"),
                    readField(selfObj, "nickName", "nick"),
                    readField(selfObj, "remarkName")
            );
        } catch (Throwable t) {
            Log.i("buildSelfFromAccountModel err:");
            Log.printStackTrace(t);
            return null;
        }
    }

    /**
     * 合并自身实体：账号模型的 uid/account 最可靠，昵称/姓名等展示字段优先取好友库资料补全。
     */
    private static UserEntity mergeSelfEntity(UserEntity base, UserEntity friend) {
        if (base == null) {
            return friend;
        }
        if (friend == null) {
            return base;
        }
        return new UserEntity(
                firstNonEmpty(base.getUserId(), friend.getUserId()),
                firstNonEmpty(base.getAccount(), friend.getAccount()),
                base.getFriendStatus() != null ? base.getFriendStatus() : friend.getFriendStatus(),
                firstNonEmpty(friend.getRealName(), base.getRealName()),
                firstNonEmpty(friend.getNickName(), base.getNickName()),
                firstNonEmpty(friend.getRemarkName(), base.getRemarkName())
        );
    }

    /** 取第一个非空字符串，都为空返回 null */
    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b != null && !b.isEmpty() ? b : null;
    }

    /** 安全读取字段：依次尝试多个候选字段名，缺失或抛错时返回 null */
    private static String readField(Object obj, String... names) {
        if (obj == null) {
            return null;
        }
        for (String name : names) {
            try {
                Object v = XHelpers.getObjectField(obj, name);
                if (v != null) {
                    String s = String.valueOf(v);
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
            } catch (Throwable ignored) {
                // 字段不存在属正常兼容情况，静默跳过
            }
        }
        return null;
    }

    public synchronized static void setCurrentUserId(String userId) {
        if (userId == null || userId.isEmpty()) {
            currentUid = null;
            Log.setAccountLabel(null);
            return;
        }
        currentUid = userId;
        // 日志前缀只在 uid 变化时算一次（首次会读/写 accountIndex.json），
        // 不再让 Log 每条日志回调 UserIdMap，避免 UI 进程被拖进 libxposed 类
        Log.setAccountLabel(getAccountLabel(userId));
    }
    
    public static String getCurrentMaskName() {
        return getMaskName(currentUid);
    }
    
    public static String getMaskName(String userId) {
        UserEntity userEntity = userMap.get(userId);
        if (userEntity == null) {
            return null;
        }
        return userEntity.getMaskName();
    }
    public static String getShowName(String userId) {
        if (userId == null || userId.isEmpty()) {
            return "未知用户";
        }
        UserEntity userEntity = userMap.get(userId);
        if (userEntity == null) {
            return userId; // 返回用户ID作为默认值
        }
        return userEntity.getShowName();
    }
    public static String getFullName(String userId) {
        UserEntity userEntity = userMap.get(userId);
        if (userEntity == null) {
            return null;
        }
        return userEntity.getFullName();
    }
    
    public static UserEntity get(String userId) {
        return userMap.get(userId);
    }
    
    public synchronized static void add(UserEntity userEntity) {
        String userId = userEntity.getUserId();
        if (userId == null || userId.isEmpty()) {
            return;
        }
        userMap.put(userId, userEntity);
    }
    
    public synchronized static void remove(String userId) {
        userMap.remove(userId);
    }
    
    public synchronized static void load(String userId) {
        userMap.clear();
        try {
            String body = FileUtil.readFromFile(FileUtil.getFriendIdMapFile(userId));
            if (!body.isEmpty()) {
                Map<String, UserEntity.UserDto> dtoMap = JsonUtil.parseObject(body, new TypeReference<Map<String, UserEntity.UserDto>>() {
                });
                for (UserEntity.UserDto dto : dtoMap.values()) {
                    userMap.put(dto.getUserId(), dto.toEntity());
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        friendListLoaded = true;
    }
    
    public synchronized static void unload() {
        userMap.clear();
        friendListLoaded = false;
    }
    
    public synchronized static boolean save(String userId) {
        return FileUtil.write2File(JsonUtil.toJsonString(userMap), FileUtil.getFriendIdMapFile(userId));
    }
    
    public synchronized static void loadSelf(String userId) {
        userMap.clear();
        try {
            String body = FileUtil.readFromFile(FileUtil.getSelfIdFile(userId));
            if (!body.isEmpty()) {
                UserEntity.UserDto dto = JsonUtil.parseObject(body, new TypeReference<UserEntity.UserDto>() {
                });
                userMap.put(dto.getUserId(), dto.toEntity());
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }
    
    public synchronized static boolean saveSelf(UserEntity userEntity) {
        return FileUtil.write2File(JsonUtil.toJsonString(userEntity), FileUtil.getSelfIdFile(userEntity.getUserId()));
    }
    
}
