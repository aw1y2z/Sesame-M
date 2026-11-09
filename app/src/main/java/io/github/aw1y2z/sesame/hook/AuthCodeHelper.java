package io.github.aw1y2z.sesame.hook;

import io.github.aw1y2z.sesame.util.XHelpers;
import io.github.aw1y2z.sesame.util.Log;
import java.util.HashMap;
import java.util.Collections;

/**
 * OAuth2 授权码服务助手类
 * 用于调用目标应用的 OpenAuthExtension.getAuthCode 方法
 */
public class AuthCodeHelper {
    private static final String TAG = "Oauth2AuthCodeHelper";
    private static ClassLoader classLoader;

    /**
     * 上一次失败的完整描述。宿主侧取授权码失败往往是**确定性**的（例如 facade 为 null），
     * 每次请求都打完整堆栈会把运行日志淹没；只在失败描述变化时打堆栈，其余只记一行。
     */
    private static volatile String lastFailDesc;
    
    /**
     * 初始化 Oauth2AuthCodeHelper
     * @param loader 应用类加载器
     */
    public static void init(ClassLoader loader) {
        classLoader = loader;
    }
    
    /**
     * 主动调用获取授权码
     * 通过反射调用 Oauth2AuthCodeService.getAuthSkipResult 方法获取授权码
     *
     * @param appId 应用ID
     * @return code，失败返回null
     */
    public static String getAuthCode(String appId) {
        try {
            if (classLoader == null) {
                Log.error("Oauth2AuthCodeHelper 未初始化，请先调用 init 方法");
                return null;
            }
            // 1. 获取并实例化 Oauth2AuthCodeServiceImpl 类
            Class<?> oauth2AuthCodeServiceImplClass = XHelpers.findClass(
                    "com.alibaba.ariver.rpc.biz.proxy.Oauth2AuthCodeServiceImpl",
                    classLoader
            );
            Object oauth2AuthCodeServiceImpl = XHelpers.newInstance(oauth2AuthCodeServiceImplClass);
            
            // 2. 获取并实例化 AuthSkipRequestModel 类
            Class<?> authSkipRequestModelClass = XHelpers.findClass(
                    "com.alibaba.ariver.permission.openauth.model.request.AuthSkipRequestModel",
                    classLoader
            );
            Object authSkipRequestModel = XHelpers.newInstance(authSkipRequestModelClass);
            
            // 3. 设置 AuthSkipRequestModel 的参数
            XHelpers.callMethod(authSkipRequestModel, "setAppId", appId);
            XHelpers.callMethod(
                    authSkipRequestModel,
                    "setCurrentPageUrl",
                    "https://" + appId + ".hybrid.alipay-eco.com/index.html"
            );
            XHelpers.callMethod(authSkipRequestModel, "setFromSystem", "mobilegw_android");
            // Java 中 List.of 是不可变列表，对应 Kotlin 的 listOf
            XHelpers.callMethod(authSkipRequestModel, "setScopeNicks", Collections.singletonList("auth_base"));
            XHelpers.callMethod(
                    authSkipRequestModel,
                    "setState",
                    "QnJpbmcgc21hbGwgYW5kIGJlYXV0aWZ1bCBjaGFuZ2VzIHRvIHRoZSB3b3JsZA=="
            );
            XHelpers.callMethod(authSkipRequestModel, "setIsvAppId", "");
            XHelpers.callMethod(authSkipRequestModel, "setExtInfo", new HashMap<String, String>());
            
            // 构建并设置 appExtInfo 参数
            HashMap<String, String> appExtInfo = new HashMap<>();
            appExtInfo.put("channel", "tinyapp");
            appExtInfo.put("clientAppId", appId);
            XHelpers.callMethod(authSkipRequestModel, "setAppExtInfo", appExtInfo);
            // 4. 调用 getAuthSkipResult 方法获取授权结果
            Object authSkipResult = XHelpers.callMethod(
                    oauth2AuthCodeServiceImpl,
                    "getAuthSkipResult",
                    "AP",
                    null,
                    authSkipRequestModel
            );
            
            // 5. 解析返回结果中的授权码
            if (authSkipResult != null) {
                Object authExecuteResult = XHelpers.callMethod(authSkipResult, "getAuthExecuteResult");
                if (authExecuteResult != null) {
                    Object authCodeObj = XHelpers.callMethod(authExecuteResult, "getAuthCode");
                    return authCodeObj instanceof String ? (String) authCodeObj : null;
                }
            }
            
            return null;
        } catch (Throwable e) {
            // 返回 null 与「确实没有授权码」无法区分，必须留痕（原先被注释掉，等于失败无迹可查）；
            // 但同一失败会随每次请求重复出现，故只在失败描述变化时打完整堆栈
            String failDesc = String.valueOf(e);
            if (!failDesc.equals(lastFailDesc)) {
                lastFailDesc = failDesc;
                Log.printStackTrace(TAG + " 主动调用获取授权码失败", e);
            } else {
                Log.error(TAG + " 主动调用获取授权码失败: " + failDesc);
            }
            return null;
        }
    }
    
    /**
     * 私有化构造方法，避免类被实例化（对应 Kotlin 的 object 单例）
     */
    private AuthCodeHelper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}