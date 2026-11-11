package io.github.aw1y2z.sesame.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.aw1y2z.sesame.util.idMap.AntKitchenTaskListMap;

/** 小鸡大厨任务的黑名单候选项（配置页 SELECT 字段用） */
public class AlipayAntKitchenTaskList extends IdAndName {
    private static List<AlipayAntKitchenTaskList> list;

    public AlipayAntKitchenTaskList(String i, String n) {
        id = i;
        name = n;
    }

    public static List<AlipayAntKitchenTaskList> getList() {
        if (list == null) {
            list = new ArrayList<>();
            for (Map.Entry<String, String> entry : AntKitchenTaskListMap.getMap().entrySet()) {
                list.add(new AlipayAntKitchenTaskList(entry.getKey(), entry.getValue()));
            }
        }
        return list;
    }

    public static void remove(String id) {
        getList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
    }

}
