package io.github.aw1y2z.sesame.entity;

import java.util.ArrayList;
import java.util.List;

public class CustomOption extends IdAndName {

    public CustomOption(String i, String n) {
        id = i;
        name = n;
    }

    public static List<CustomOption>
    getEcoLifeOptions() {
        List<CustomOption> list = new ArrayList<>();
        list.add(new CustomOption("tick", "绿色行动打卡"));
        list.add(new CustomOption("dish", "光盘行动打卡"));
        return list;
    }
    public static List<CustomOption>
    getContinuousUseCardOptions() {
        List<CustomOption> list = new ArrayList<>();
        list.add(new CustomOption("doubleClick", "使用限时双击卡和兑换使用31天双击卡"));
        list.add(new CustomOption("robExpandCard", "先现倍率无生效则用最高倍收能量倍卡"));
        list.add(new CustomOption("stealthCard", "兑换和使用限制隐身卡"));
        return list;
    }

    public static List<CustomOption> getUseAccelerateToolOptions() {
        List<CustomOption> list = new ArrayList<>();
        list.add(new CustomOption("useAccelerateToolContinue", "连续使用"));
        list.add(new CustomOption("useAccelerateToolWhenMaxEmotion", "仅在满状态时使用"));
        return list;
    }

    /** 庄园可雇佣的 NPC 小鸡；id 与 AntFarm.HireNpc 的枚举名一一对应 */
    public static List<CustomOption> getAntFarmHireNpcOptions() {
        List<CustomOption> list = new ArrayList<>();
        list.add(new CustomOption("DA_BIAO_GE", "大表鸽"));
        list.add(new CustomOption("HUANG_JIN_JI", "黄金鸡"));
        list.add(new CustomOption("NONG_CHANG_XIAO_JI", "农场小鸡"));
        // 以下四只暂不启用，参数已在 AntFarm.HireNpc 备好
        // list.add(new CustomOption("TAO_JIN_BI_XIAO_JI", "淘金币小鸡"));
        // list.add(new CustomOption("DAO_DIAN_HONG_BAO_XIAO_JI", "到店红包小鸡"));
        // list.add(new CustomOption("TONG_YONG_HONG_BAO_XIAO_JI", "通用红包小鸡"));
        // list.add(new CustomOption("TONG_YONG_QUAN_BAO_XIAO_JI", "通用券包小鸡"));
        return list;
    }

    public static List<CustomOption> getAntFarmFamilyOptions() {
        List<CustomOption> list = new ArrayList<>();
        list.add(new CustomOption("familySign", "每日签到"));
        list.add(new CustomOption("familyFeed", "帮喂成员"));
        list.add(new CustomOption("familyEatTogether", "美食请客"));
        list.add(new CustomOption("familyClaimReward", "领取奖励"));
        list.add(new CustomOption("deliverMsgSend", "道早安"));
        list.add(new CustomOption("assignRights", "顶梁柱"));
        list.add(new CustomOption("shareToFriends", "分享给好友"));
        return list;
    }

    public static List<CustomOption> getAntDodoPropList() {
        List<CustomOption> list = new ArrayList<>();
        list.add(new CustomOption("COLLECT_TIMES_7_DAYS", "抽卡道具"));
        list.add(new CustomOption("COLLECT_HISTORY_ANIMAL_7_DAYS", "历史图鉴随机卡道具"));
        list.add(new CustomOption("COLLECT_TO_FRIEND_TIMES_7_DAYS", "抽好友卡道具"));
        list.add(new CustomOption("UNIVERSAL_CARD_7_DAYS", "万能卡道具"));
        return list;
    }

    public static List<CustomOption> getAntDodoBookMedalOptions() {
        List<CustomOption> list = new ArrayList<>();
        list.add(new CustomOption("generateBookMedal", "自动合成图鉴勋章"));
        list.add(new CustomOption("collectHistoryAnimal", "自动收集历史物种"));
        return list;
    }
}
