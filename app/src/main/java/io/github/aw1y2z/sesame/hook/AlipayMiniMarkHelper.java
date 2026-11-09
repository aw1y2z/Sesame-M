package io.github.aw1y2z.sesame.hook;

import io.github.aw1y2z.sesame.util.XHelpers;
import io.github.aw1y2z.sesame.util.Log;

/**
 * 支付宝小程序游戏获取alipayminimark
 * 用于调用目标应用的 H5HttpUtils.getAlipayMiniMark 方法
 */
public class AlipayMiniMarkHelper {
    private static final String TAG = "AlipayMiniMarkHelper";
    /** 承载 getAlipayMiniMark 的类名候选：不同支付宝版本路径不同 */
    private static final String[] CANDIDATE_CLASSES = {
            "com.alibaba.mobile.nebula.util.H5HttpUtils",
            "com.alibaba.ariver.nebula.util.H5HttpUtils",
    };
    private static ClassLoader classLoader;
    /** 已解析出的可用类（解析成功后缓存；本宿主没有时保持 null） */
    private static volatile Class<?> h5HttpUtilsClass;
    /** 是否已解析过（避免每个游戏都重新探测） */
    private static volatile boolean classResolved;
    /** 调用失败是否已记过日志（该方法按游戏逐次调用，失败会成批出现） */
    private static volatile boolean callFailureLogged;
    
    /**
     * 初始化 AlipayMiniMarkHelper
     * @param loader 应用类加载器
     */
    public static void init(ClassLoader loader) {
        classLoader = loader;
    }
    
    /**
     * 获取支付宝小程序标记
     * 通过调用 H5HttpUtils.getAlipayMiniMark 方法获取小程序标记
     * <p>实测当前支付宝版本里 {@code H5HttpUtils} 已不存在（探测两个候选类名都找不到），
     * 该能力在本宿主上不可用：这里只做**一次**探测并说明，之后每次调用直接返回空串，
     * 不再按游戏逐条记失败日志（原先一天能刷 200 多行）。
     *
     * @param str 游戏appid
     * @param str2 游戏版本号
     * @return 小程序标记字符串，失败或不可用返回空字符串
     */
    public static String getAlipayMiniMark(String str, String str2) {
        Class<?> clazz = resolveClass();
        if (clazz == null) {
            return "";
        }
        try {
            Object resultObj = XHelpers.callStaticMethod(clazz, "getAlipayMiniMark", str, str2);
            String result = (resultObj instanceof String) ? (String) resultObj : "";
            return result;
        } catch (Throwable e) {
            if (!callFailureLogged) {
                callFailureLogged = true;
                Log.i(TAG, "调用 getAlipayMiniMark 失败（后续同类失败不再记录）: " + e);
            }
            return "";
        }
    }
    
    /**
     * 解析承载类：按候选名单探测一次并缓存。
     * <p>都找不到时返回 null，且由于解析结果已缓存，不会每次调用都去探测。
     * <p>注意 classLoader 尚未初始化时不缓存结果，等 init 之后仍有机会解析成功。
     */
    private static Class<?> resolveClass() {
        Class<?> cached = h5HttpUtilsClass;
        if (cached != null) {
            return cached;
        }
        if (classLoader == null) {
            return null;
        }
        synchronized (AlipayMiniMarkHelper.class) {
            if (classResolved) {
                return h5HttpUtilsClass;
            }
            for (String name : CANDIDATE_CLASSES) {
                Class<?> clazz = XHelpers.findClassIfExists(name, classLoader);
                if (clazz != null) {
                    h5HttpUtilsClass = clazz;
                    Log.i(TAG, "使用类: " + name);
                    break;
                }
            }
            classResolved = true;
            if (h5HttpUtilsClass == null) {
                Log.i(TAG, "当前宿主没有 H5HttpUtils，小程序标记不可用（后续调用直接返回空串，不再记录）");
            }
            return h5HttpUtilsClass;
        }
    }
    
    // 私有化构造方法，避免类被实例化（对应Kotlin的object单例）
    private AlipayMiniMarkHelper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}