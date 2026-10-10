package io.github.aw1y2z.sesame.hook;

import android.content.Context;
import android.os.Handler;

import io.github.aw1y2z.sesame.model.normal.base.BaseModel;
import io.github.aw1y2z.sesame.util.Log;

public class Toast {
    private static final String TAG = Toast.class.getSimpleName();

    public static void show(CharSequence cs) {
        show(cs, false);
    }

    public static void show(CharSequence cs, boolean force) {
        Context context = ApplicationHook.getContext();
        if (context != null && (force || io.github.aw1y2z.sesame.data.AppConfig.INSTANCE.getShowToast())) {
            show(context, ApplicationHook.getMainHandler(), cs);
        }
    }

    public static void show(Context context, Handler handler, CharSequence cs) {
        // Toast may be requested before the Service hook has initialized the main Handler.
        // A null Handler must not turn a user-facing notification into a NullPointerException.
        if (context == null || handler == null) {
            Log.i(TAG, "skip toast: context or main handler is not ready");
            return;
        }
        try {
            handler.post(() -> {
                try {
                    android.widget.Toast toast = android.widget.Toast.makeText(context, cs, android.widget.Toast.LENGTH_SHORT);
                    toast.setGravity(toast.getGravity(), toast.getXOffset(), io.github.aw1y2z.sesame.data.AppConfig.INSTANCE.getToastOffsetY());
                    toast.show();
                } catch (Throwable t) {
                    Log.err(TAG, "show.run err:", t);
                }
            });
        } catch (Throwable t) {
            Log.err(TAG, "show err:", t);
        }
    }
}
