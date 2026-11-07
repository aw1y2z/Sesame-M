package io.github.aw1y2z.sesame.model.normal.answerAI;

import io.github.aw1y2z.sesame.data.Model;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.TokenConfig;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.StringModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.TextModelField.ReadOnlyTextModelField;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.StringUtil;

import java.util.List;
import java.util.Objects;

public class AnswerAI extends Model {

    private static final String TAG = AnswerAI.class.getSimpleName();

    /** 任务线程读取、boot 线程写入，用 volatile 保证可见性 */
    private static volatile Boolean enable = false;

    /** 日志里题目与选项列表的最大字数：整题与全部选项都写进日志会显著撑大日志 */
    private static final int LOG_TITLE_MAX_LENGTH = 60;
    private static final int LOG_OPTIONS_MAX_LENGTH = 80;

    @Override
    public String getName() {
        return "AI答";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.ANSWER_AI;
    }

    /** 当前生效的自定义AI实现；未配置时为 null，表示不调用AI */
    private static volatile CustomAI customAI;

    private final StringModelField customAIUrl = new StringModelField("customAIUrl", "自定义AI | 接口地址(根地址,如/v1)", "");
    private final StringModelField customAIModel = new StringModelField("customAIModel", "自定义AI | 模型名", "");
    private final StringModelField customAIKey = new StringModelField("customAIKey", "自定义AI | 令牌", "");
    private final IntegerModelField customAIMaxTokens = new IntegerModelField("customAIMaxTokens", "自定义AI | 输出Token上限(0=不发)", 1024, 0, 8192);
    /** 只读提示：模块 App 已不申请 INTERNET，没有「测试响应」入口，参数只能靠用户自己填对 */
    private final ReadOnlyTextModelField customAITestTip = new ReadOnlyTextModelField("customAITestTip",
            "自定义AI | 模块不提供响应测试", "请确保地址、模型名与令牌填写正确，填错时答题会退回题库或首个选项");

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(customAIUrl);
        modelFields.addField(customAIModel);
        modelFields.addField(customAIKey);
        modelFields.addField(customAIMaxTokens);
        modelFields.addField(customAITestTip);
        return modelFields;
    }

    @Override
    public void boot(ClassLoader classLoader) {
        enable = getEnableField().getValue();
        customAI = new CustomAI(customAIUrl.getValue(), customAIModel.getValue(), customAIKey.getValue(), customAIMaxTokens.getValue());
        if (!customAI.isConfigured()) {
            customAI = null;
            Log.record("AI🧠接口地址/模型名/令牌未填齐，答题不会调用AI，将直接使用题库或首个选项");
        }
    }

    /** 日志用：压缩空白并超长截断，避免整题与全部选项把日志撑大 */
    private static String trimForLog(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return StringUtil.truncate(text.replaceAll("\\s+", " ").trim(), maxLength);
    }

    /**
     * 获取答案
     *
     * @param text       问题
     * @param answerList 答案集合
     * @return 选中的选项文本；AI 不可用或未返回有效答案时取第一个选项，选项集合为空时返回空串
     */
    public static String getAnswer(String text, List<String> answerList) {
        String answerStr = "";
        try {
            // 题目与选项都截断：整题 + 全部选项全量写入会显著撑大日志
            Log.record("知识问答🧠题目[" + trimForLog(text, LOG_TITLE_MAX_LENGTH)
                    + "]#共" + answerList.size() + "项" + trimForLog(answerList.toString(), LOG_OPTIONS_MAX_LENGTH));
            // enable 是 Boolean，配置缺失时为 null，用 TRUE.equals 避免拆箱 NPE
            if (Boolean.TRUE.equals(enable) && customAI != null) {
                Integer answer = customAI.getAnswer(text, answerList);
                if (answer != null && answer >= 0 && answer < answerList.size()) {
                    answerStr = answerList.get(answer);
                    Log.record("智能回答🧠[" + answerStr + "]");
                } else {
                    Log.record("AI🧠未返回有效答案");
                }
            } else {
                Log.record("AI🧠未启用或未配置，不使用AI作答");
            }
            // AI 不可用、未返回有效答案时统一兜底取第一个选项，并记录原因便于排查
            if (answerStr.isEmpty() && !answerList.isEmpty()) {
                answerStr = answerList.get(0);
                Log.record("兜底回答🤖[" + answerStr + "]");
            }
            // 题库纠错：TokenConfig 里存的是服务端回传过的正确答案（庄园答题结束后会带出次日题目与答案），
            // 命中时以它为准覆盖 AI 的结果；但必须仍在候选选项内，否则题目变了会提交无效答案。
            // 放在 try 内：题库查询异常只该少一次纠错，不能让整条答题失败
            String doubleCheckAnswer = TokenConfig.getAnswer(text);
            if (doubleCheckAnswer != null && !Objects.equals(answerStr, doubleCheckAnswer)) {
                if (answerList.contains(doubleCheckAnswer)) {
                    answerStr = doubleCheckAnswer;
                    Log.record("检测即将提交错误的回答，已自动纠正!新回答:" + answerStr);
                } else {
                    Log.record("题库答案[" + doubleCheckAnswer + "]不在选项内，忽略本次纠错");
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
        return answerStr;
    }

}
