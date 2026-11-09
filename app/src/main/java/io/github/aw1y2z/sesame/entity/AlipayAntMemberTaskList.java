package io.github.aw1y2z.sesame.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.aw1y2z.sesame.util.idMap.AntMemberTaskListMap;

public class AlipayAntMemberTaskList extends IdAndName {
    private static List<AlipayAntMemberTaskList> list;

    public AlipayAntMemberTaskList(String i, String n) {
        id = i;
        name = n;
    }

    public static List<AlipayAntMemberTaskList> getList() {
        if (list == null) {
            list = new ArrayList<>();
            for (Map.Entry<String, String> entry : AntMemberTaskListMap.getMap().entrySet()) {
                list.add(new AlipayAntMemberTaskList(entry.getKey(), entry.getValue()));
            }
        }
        return list;
    }

    /** 清掉静态缓存，下次 getList() 重新从映射构建；配置进程载入新映射后必须调用。 */
    public static void clear() {
        list = null;
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
