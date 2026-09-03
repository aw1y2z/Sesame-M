package io.github.aw1y2z.sesame.util.compat;

/**
 * API 102 临时迁移兼容层：模拟旧 Xposed {@code XC_MethodReplacement}。
 */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        param.setResult(replaceHookedMethod(param));
    }

    public abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    public static XC_MethodReplacement returnConstant(final Object result) {
        return new XC_MethodReplacement() {
            @Override
            public Object replaceHookedMethod(MethodHookParam param) {
                return result;
            }
        };
    }

    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement() {
        @Override
        public Object replaceHookedMethod(MethodHookParam param) {
            return null;
        }
    };
}
