package io.github.aw1y2z.sesame.data.modelFieldExt;


import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import io.github.aw1y2z.sesame.R;
import io.github.aw1y2z.sesame.data.ModelField;

public class ChoiceModelField extends ModelField<Integer> {

    private String[] choiceArray;

    public ChoiceModelField(String code, String name, Integer value) {
        super(code, name, value);
    }

    public ChoiceModelField(String code, String name, Integer value, String[] choiceArray) {
        super(code, name, value);
        this.choiceArray = choiceArray;
    }

    @Override
    public String getType() {
        return "CHOICE";
    }

    public String[] getExpandKey() {
        return choiceArray;
    }

    @Override
    public View getView(Context context) {
        return null;
    }

}
