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
import io.github.aw1y2z.sesame.data.modelFieldExt.common.SelectModelFieldFunc;
import io.github.aw1y2z.sesame.entity.IdAndName;

import java.util.List;
import java.util.Objects;

public class SelectOneModelField extends ModelField<String> implements SelectModelFieldFunc {

    private SelectListFunc selectListFunc;

    private List<? extends IdAndName> expandValue;

    public SelectOneModelField(String code, String name, String value, List<? extends IdAndName> expandValue) {
        super(code, name, value);
        this.expandValue = expandValue;
    }

    public SelectOneModelField(String code, String name, String value, SelectListFunc selectListFunc) {
        super(code, name, value);
        this.selectListFunc = selectListFunc;
    }

    @Override
    public String getType() {
        return "SELECT_ONE";
    }

    public List<? extends IdAndName> getExpandValue() {
        return selectListFunc == null ? expandValue : selectListFunc.getList();
    }

    @Override
    public View getView(Context context) {
        return null;
    }

    @Override
    public void clear() {
        value = defaultValue;
    }

    @Override
    public Integer get(String id) {
        return 0;
    }

    @Override
    public void add(String id, Integer count) {
        value = id;
    }

    @Override
    public void remove(String id) {
        if (Objects.equals(value, id)) {
            value = defaultValue;
        }
    }

    @Override
    public Boolean contains(String id) {
        return Objects.equals(value, id);
    }

    public interface SelectListFunc {
        List<? extends IdAndName> getList();
    }
}