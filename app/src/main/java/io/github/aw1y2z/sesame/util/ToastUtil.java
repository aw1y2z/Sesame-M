package io.github.aw1y2z.sesame.util;

import android.content.Context;
import android.widget.Toast;

import io.github.aw1y2z.sesame.model.normal.base.BaseModel;

public class ToastUtil {

    public static void show(Context context, int resId) {
        show(context, context.getText(resId));
    }

    public static void show(Context context, CharSequence text) {
        Toast toast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
        show(toast);
    }

    private static void show(Toast toast) {
        toast.setGravity(toast.getGravity(), toast.getXOffset(), BaseModel.getToastOffsetY().getValue());
        toast.show();
    }
}
