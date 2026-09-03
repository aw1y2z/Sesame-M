package io.github.aw1y2z.sesame.util.compat;

/**
 * API 102 临时迁移兼容层：模拟旧 Xposed {@code XC_MethodHook} 的 before/after 模型。
 * 由 {@link io.github.aw1y2z.sesame.util.XHelpers} 桥接到 libxposed 的 Hook 链。
 */
public abstract class XC_MethodHook {

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result = null;
        public boolean hasResult = false;
        public Throwable exception = null;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.hasResult = true;
        }

        public boolean hasThrowable() {
            return exception != null;
        }

        public Throwable getThrowable() {
            return exception;
        }

        public void setThrowable(Throwable throwable) {
            this.exception = throwable;
        }
    }

    public interface Unhook {
        void unhook();
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public final void callBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public final void callAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }
}
