package io.github.aw1y2z.sesame.data;

import io.github.aw1y2z.sesame.util.FileUtil;
import io.github.aw1y2z.sesame.util.StringUtil;
import io.github.aw1y2z.sesame.util.idMap.AnimalIdMap;
import io.github.aw1y2z.sesame.util.idMap.AntDodoTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntFarmDoFarmTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntFarmDrawMachineTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntForestHuntTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntForestVitalityTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntMemberTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntOceanAntiepTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntOceanFishBlackListMap;
import io.github.aw1y2z.sesame.util.idMap.AntOrchardTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntSportsTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.AntStallTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.BeachIdMap;
import io.github.aw1y2z.sesame.util.idMap.CooperationIdMap;
import io.github.aw1y2z.sesame.util.idMap.FarmOrnamentsIdMap;
import io.github.aw1y2z.sesame.util.idMap.GameCenterMallItemMap;
import io.github.aw1y2z.sesame.util.idMap.MarathonIdMap;
import io.github.aw1y2z.sesame.util.idMap.MemberBenefitIdMap;
import io.github.aw1y2z.sesame.util.idMap.MemberCreditSesameTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.NewAncientTreeIdMap;
import io.github.aw1y2z.sesame.util.idMap.PathThemeMapListMap;
import io.github.aw1y2z.sesame.util.idMap.PlantSceneIdMap;
import io.github.aw1y2z.sesame.util.idMap.PromiseSimpleTemplateIdMap;
import io.github.aw1y2z.sesame.util.idMap.ReserveIdMap;
import io.github.aw1y2z.sesame.util.idMap.TreeIdMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;
import io.github.aw1y2z.sesame.util.idMap.VitalityBenefitIdMap;
import io.github.aw1y2z.sesame.util.idMap.ForestHuntIdMap;
import io.github.aw1y2z.sesame.util.idMap.rpcRequestMap;

/**
 * 配置相关的预加载逻辑（原 SettingsActivity / NewSettingsActivity 中的初始化）。
 * miuix Compose 界面复用同一套数据，必须在这里完成 IdMap 加载与 ConfigV2.load，
 * 否则 SELECT_ONE / SELECT 等字段的选项列表为空。
 */
public final class ConfigPreload {

    private ConfigPreload() {
    }

    public static void prepare(String userId) {
        UserIdMap.setCurrentUserId(userId);
        UserIdMap.load(userId);
        CooperationIdMap.load(userId);
        VitalityBenefitIdMap.load(userId);
        GameCenterMallItemMap.load(userId);
        FarmOrnamentsIdMap.load(userId);
        MemberBenefitIdMap.load(userId);
        PromiseSimpleTemplateIdMap.load(userId);
        TreeIdMap.load();
        ReserveIdMap.load();
        AnimalIdMap.load();
        MarathonIdMap.load();
        NewAncientTreeIdMap.load();
        BeachIdMap.load();
        PlantSceneIdMap.load();
        rpcRequestMap.load();
        ForestHuntIdMap.load();
        MemberCreditSesameTaskListMap.load();
        AntForestVitalityTaskListMap.load();
        AntForestHuntTaskListMap.load();
        AntFarmDoFarmTaskListMap.load();
        AntFarmDrawMachineTaskListMap.load();
        AntDodoTaskListMap.load();
        AntOceanAntiepTaskListMap.load();
        AntOceanFishBlackListMap.load();
        AntOrchardTaskListMap.load();
        AntStallTaskListMap.load();
        AntSportsTaskListMap.load();
        PathThemeMapListMap.load();
        AntMemberTaskListMap.load();
        ConfigV2.load(userId);
    }

    public static boolean isEmpty(String userId) {
        return StringUtil.isEmpty(userId);
    }

    public static java.io.File getConfigFile(String userId) {
        if (StringUtil.isEmpty(userId)) {
            return FileUtil.getDefaultConfigV2File();
        }
        return FileUtil.getConfigV2File(userId);
    }
}
