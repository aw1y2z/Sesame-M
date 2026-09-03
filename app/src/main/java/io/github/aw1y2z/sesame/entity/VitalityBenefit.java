package io.github.aw1y2z.sesame.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.aw1y2z.sesame.util.idMap.VitalityBenefitIdMap;

public class VitalityBenefit extends IdAndName {

    public VitalityBenefit(String i, String n) {
        id = i;
        name = n;
    }

    public static List<VitalityBenefit> getList() {
        List<VitalityBenefit> list = new ArrayList<>();
        Set<Map.Entry<String, String>> idSet = VitalityBenefitIdMap.getMap().entrySet();
        for (Map.Entry<String, String> entry: idSet) {
            list.add(new VitalityBenefit(entry.getKey(), entry.getValue()));
        }
        return list;
    }
}
