package io.github.aw1y2z.sesame.data.modelFieldExt;


import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import lombok.Getter;
import io.github.aw1y2z.sesame.R;
import io.github.aw1y2z.sesame.data.ModelField;
import io.github.aw1y2z.sesame.util.Log;

@Getter
public class IntegerModelField extends ModelField<Integer> {

    protected final Integer minLimit;

    protected final Integer maxLimit;

    public IntegerModelField(String code, String name, Integer value) {
        super(code, name, value);
        this.minLimit = null;
        this.maxLimit = null;
    }

    public IntegerModelField(String code, String name, Integer value, Integer minLimit, Integer maxLimit) {
        super(code, name, value);
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
    }

    @Override
    public String getType() {
        return "INTEGER";
    }

    public Integer getMinLimit() {
        return minLimit;
    }

    public Integer getMaxLimit() {
        return maxLimit;
    }

    @Override
    public String getConfigValue() {
        return String.valueOf(value);
    }

    @Override
    public void setConfigValue(String configValue) {
        Integer newValue;
        if (configValue == null) {
            newValue = defaultValue;
        } else {
            try {
                newValue = Integer.parseInt(configValue);
            } catch (Exception e) {
                Log.printStackTrace(e);
                newValue = defaultValue;
            }
        }
        if (minLimit != null) {
            newValue = Math.max(minLimit, newValue);
        }
        if (maxLimit != null) {
            newValue = Math.min(maxLimit, newValue);
        }
        this.value = newValue;
    }

    @Override
    public View getView(Context context) {
        return null;
    }

    @Getter
    public static class MultiplyIntegerModelField extends IntegerModelField {

        private final Integer multiple;

        public MultiplyIntegerModelField(String code, String name, Integer value, Integer minLimit, Integer maxLimit, Integer multiple) {
            super(code, name, value * multiple, minLimit, maxLimit);
            this.multiple = multiple;
        }

        @Override
        public String getType() {
            return "MULTIPLY_INTEGER";
        }

        @Override
        public void setConfigValue(String configValue) {
            if (configValue == null) {
                reset();
                return;
            }
            super.setConfigValue(configValue);
            try {
                value = value * multiple;
                return;
            } catch (Exception e) {
                Log.printStackTrace(e);
            }
            reset();
        }

        @Override
        public String getConfigValue() {
            return String.valueOf(value / multiple);
        }

    }

}
