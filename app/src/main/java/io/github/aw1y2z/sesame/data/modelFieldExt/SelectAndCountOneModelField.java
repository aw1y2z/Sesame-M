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
import io.github.aw1y2z.sesame.entity.KVNode;

import java.util.List;
import java.util.Objects;

public class SelectAndCountOneModelField extends ModelField<KVNode<String, Integer>> implements SelectModelFieldFunc {

    private SelectListFunc selectListFunc;

    private List<? extends IdAndName> expandValue;

    public SelectAndCountOneModelField(String code, String name, KVNode<String, Integer> value, List<? extends IdAndName> expandValue) {
        super(code, name, value);
        this.expandValue = expandValue;
    }

    public SelectAndCountOneModelField(String code, String name, KVNode<String, Integer> value, SelectListFunc selectListFunc) {
        super(code, name, value);
        this.selectListFunc = selectListFunc;
    }

    @Override
    public String getType() {
        return "SELECT_AND_COUNT_ONE";
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
        KVNode<String, Integer> kvNode = getValue();
        if (kvNode != null && Objects.equals(kvNode.getKey(), id)) {
            return kvNode.getValue();
        }
        return 0;
    }

    @Override
    public void add(String id, Integer count) {
        value = new KVNode<>(id, count);
    }

    @Override
    public void remove(String id) {
        KVNode<String, Integer> kvNode = getValue();
        if (kvNode != null && Objects.equals(kvNode.getKey(), id)) {
            value = defaultValue;
        }
    }

    @Override
    public Boolean contains(String id) {
        KVNode<String, Integer> kvNode = getValue();
        return kvNode != null && Objects.equals(kvNode.getKey(), id);
    }

    public interface SelectListFunc {
        List<? extends IdAndName> getList();
    }
}