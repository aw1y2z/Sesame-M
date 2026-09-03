package io.github.aw1y2z.sesame.util.compat;

import java.lang.reflect.Member;

import io.github.aw1y2z.sesame.util.XHelpers;

/**
 * API 102 临时迁移兼容层：桥接旧 {@code XposedBridge.hookMethod} 到 {@link io.github.aw1y2z.sesame.util.XHelpers}。
 */
public class XposedBridge {

    public static void hookMethod(Member member, XC_MethodHook callback) {
        XHelpers.hookMember(member, callback);
    }
}
