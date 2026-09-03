package io.github.aw1y2z.sesame.data.modelFieldExt;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.aw1y2z.sesame.R;
import io.github.aw1y2z.sesame.data.ModelField;

public class TextModelField extends ModelField<String> {

    public TextModelField(String code, String name, String value) {
        super(code, name, value);
    }

    @Override
    public String getType() {
        return "TEXT";
    }

    @Override
    public String getConfigValue() {
        return value;
    }

    @Override
    public void setConfigValue(String configValue) {
        value = configValue;
    }

    @JsonIgnore
    public View getView(Context context) {
        return null;
    }

    public static class ReadOnlyTextModelField extends TextModelField {

        public ReadOnlyTextModelField(String code, String name, String value) {
            super(code, name, value);
        }

        @Override
        public String getType() {
            return "READ_TEXT";
        }

        @Override
        public String getValue() {
            return null;
        }

        @Override
        public void setConfigValue(String configValue) {
        }

    }

    public static class UrlTextModelField extends ReadOnlyTextModelField {

        public UrlTextModelField(String code, String name, String value) {
            super(code, name, value);
        }

        @Override
        public String getType() {
            return "URL_TEXT";
        }

        @JsonIgnore
        public View getView(Context context) {
            return null;
        }

    }

}
