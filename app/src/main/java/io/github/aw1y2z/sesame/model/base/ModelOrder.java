package io.github.aw1y2z.sesame.model.base;

import java.util.ArrayList;
import java.util.List;

import io.github.aw1y2z.sesame.data.Model;
import io.github.aw1y2z.sesame.model.extensions.ExtensionsHandle;
import io.github.aw1y2z.sesame.model.normal.answerAI.AnswerAI;
import io.github.aw1y2z.sesame.model.normal.base.BaseModel;
import io.github.aw1y2z.sesame.model.task.antDodo.AntDodo;
import io.github.aw1y2z.sesame.model.task.antFarm.AntFarm;
import io.github.aw1y2z.sesame.model.task.antForest.AntForestV2;
import io.github.aw1y2z.sesame.model.task.antMember.AntMember;
import io.github.aw1y2z.sesame.model.task.antOcean.AntOcean;
import io.github.aw1y2z.sesame.model.task.antOrchard.AntOrchard;
import io.github.aw1y2z.sesame.model.task.antSports.AntSports;
import io.github.aw1y2z.sesame.model.task.antStall.AntStall;
import io.github.aw1y2z.sesame.model.task.greenFinance.GreenFinance;
import io.github.aw1y2z.sesame.model.task.protectEcology.ProtectEcology;
import lombok.Getter;

public class ModelOrder {

    @Getter
    private static final List<Class<? extends Model>> clazzList = new ArrayList<>();

    static {
        clazzList.add(BaseModel.class);
        clazzList.add(AntForestV2.class);
        clazzList.add(AntFarm.class);
        clazzList.add(AntStall.class);
        clazzList.add(AntOrchard.class);
        clazzList.add(ProtectEcology.class);
        clazzList.add(AntDodo.class);
        clazzList.add(AntOcean.class);
        clazzList.add(AntSports.class);
        clazzList.add(AntMember.class);
        clazzList.add(GreenFinance.class);
        clazzList.add(AnswerAI.class);

        ExtensionsHandle.handleAlphaRequest("ModelOrder", "addExtensionsClass", clazzList);
    }
}