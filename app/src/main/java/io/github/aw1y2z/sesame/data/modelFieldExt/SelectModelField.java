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
import java.util.Set;

/**
 * 数据结构说明
 * Set<String> 表示已选择的数据
 * List<? extends IdAndName> 需要选择的数据
 */
public class SelectModelField extends ModelField<Set<String>> implements SelectModelFieldFunc {

    private SelectListFunc selectListFunc;

    private List<? extends IdAndName> expandValue;

    public SelectModelField(String code, String name, Set<String> value, List<? extends IdAndName> expandValue) {
        super(code, name, value);
        this.expandValue = expandValue;
    }

    public SelectModelField(String code, String name, Set<String> value, SelectListFunc selectListFunc) {
        super(code, name, value);
        this.selectListFunc = selectListFunc;
    }

    public SelectModelField(String code, String name, Set<String> value, List<? extends IdAndName> expandValue, String description) {
        super(code, name, value, description);
        this.expandValue = expandValue;
    }

    public SelectModelField(String code, String name, Set<String> value, SelectListFunc selectListFunc, String description) {
        super(code, name, value, description);
        this.selectListFunc = selectListFunc;
    }

    @Override
    public String getType() {
        return "SELECT";
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
        getValue().clear();
    }

    @Override
    public Integer get(String id) {
        return 0;
    }

    @Override
    public void add(String id, Integer count) {
        getValue().add(id);
    }

    @Override
    public void remove(String id) {
        getValue().remove(id);
    }

    @Override
    public Boolean contains(String id) {
        return getValue().contains(id);
    }

    public interface SelectListFunc {
        List<? extends IdAndName> getList();
    }
}
