package io.github.aw1y2z.sesame.data.modelFieldExt;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.aw1y2z.sesame.R;
import io.github.aw1y2z.sesame.data.ModelField;

import java.util.ArrayList;
import java.util.List;

public class ListModelField extends ModelField<List<String>> {

    private static final TypeReference<List<String>> typeReference = new TypeReference<List<String>>() {
    };

    public ListModelField(String code, String name, List<String> value) {
        super(code, name, value);
    }

    @Override
    public String getType() {
        return "LIST";
    }

    @Override
    public View getView(Context context) {
        return null;
    }

    public static class ListJoinCommaToStringModelField extends ListModelField {

        public ListJoinCommaToStringModelField(String code, String name, List<String> value) {
            super(code, name, value);
        }

        @Override
        public void setConfigValue(String configValue) {
            if (configValue == null) {
                reset();
                return;
            }
            List<String> list = new ArrayList<>();
            String[] split = configValue.split(",");
            if (split.length == 1) {
                String str = split[0];
                if (!str.isEmpty()) {
                    list.add(str);
                }
            } else {
                for (String str : split) {
                    if (!str.isEmpty()) {
                        list.add(str);
                    }
                }
            }
            value = list;
        }

        @Override
        public String getConfigValue() {
            return String.join(",", value);
        }
    }

}
