package io.github.aw1y2z.sesame.ui.miuix

import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aw1y2z.sesame.data.Agreement
import io.github.aw1y2z.sesame.util.PermissionUtil
import io.github.aw1y2z.sesame.util.ToastUtil
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 阅读倒计时（秒）；不用"滚动到底"判定，不可靠且无法律意义 */
private const val COUNTDOWN_SECONDS = 15

/** 返回键二次确认的等待窗口：超时未再按就撤销「再按一次」状态 */
private const val EXIT_CONFIRM_MILLIS = 2_000L

/** 一条条款：标题用于扫读，正文摘要自 docs/合规与免责.md */
private data class Term(val title: String, val body: String)

/** 正文用 `**` 标出加粗片段，由 [highlight] 转成 AnnotatedString；只标影响后果判断的短语 */
private val TERMS: List<Term> = listOf(
    Term(
        "项目性质",
        "本项目是**非官方**的个人开源项目，仅用于学习与技术研究，与蚂蚁集团、支付宝及其关联方**无任何隶属、合作、授权或背书关系**。「支付宝」「蚂蚁森林」「芝麻粒」等名称与标识归其权利人所有。"
    ),
    Term(
        "使用风险",
        "本模块通过注入方式改变支付宝的行为，**可能违反平台用户协议**，并可能导致**账号功能受限、已获权益被回收或账号被封禁**。上述后果由**你自行承担**。"
    ),
    Term(
        "无担保",
        "本项目**按「现状」提供**，**不附带任何明示或默示担保**，不保证可用性、正确性、连续性，也不保证不被平台识别或处理。"
    ),
    Term(
        "数据处理",
        "本模块自身**未申请网络权限**；注入后代码运行在支付宝进程内，网络请求经由宿主应用的网络权限发出，仅用于完成你已授权的操作。作者不运营任何接收用户数据的服务器，**不收集、不存储、不上传**你的账号凭证、好友列表、能量与任务数据。"
    ),
    Term(
        "AI 答题",
        "启用后答题所需的题目文本会发送至**你自行填写的第三方接口**，该环节受你与该服务商之间的条款约束，作者不参与、不控制、不代收。"
    ),
    Term(
        "使用义务",
        "仅在自己的账号上使用，**不代他人操作、不批量控制账号**，不用于商业牟利，不用于规避平台安全机制。"
    ),
    Term(
        "责任范围",
        "在适用法律允许的最大范围内，**作者不对因使用或无法使用本项目产生的任何直接或间接损失承担责任**。本条款不排除依法不能排除的责任。"
    ),
    Term(
        "权利通知",
        "如认为本项目内容侵犯了你的合法权益，请邮件联系 aw1y2z@foxmail.com，核实后将**立即删除或修改**。"
    )
)

/** 把 `**关键词**` 解析成加粗片段；未闭合的标记原样保留，不会吞掉后半句 */
private fun String.highlight(highlightColor: Color): AnnotatedString {
    // 必须存局部变量：buildAnnotatedString 作用域内的 length 是 builder 长度（恒从 0 起）
    val source = this
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < source.length) {
            val open = source.indexOf("**", cursor)
            val close = if (open >= 0) source.indexOf("**", open + 2) else -1
            if (close < 0) {
                append(source.substring(cursor))
                return@buildAnnotatedString
            }
            append(source.substring(cursor, open))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = highlightColor)) {
                append(source.substring(open + 2, close))
            }
            cursor = close + 2
        }
    }
}

/**
 * 首次使用门禁，入口只有 [MiuixMainActivity]，因此只在这一处分流、不引入路由。
 *
 * @param onAgree 已成功落盘同意状态后回调
 * @param onExit 不同意时的退出动作；必须存在，只给"同意"一个按钮不构成有效同意
 */
@Composable
fun AgreementScreen(onAgree: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scrollState = rememberScrollState()
    // 用 rememberSaveable：重建后不该重来倒计时，也不该清空已输入的短语
    var remaining by rememberSaveable { mutableStateOf(COUNTDOWN_SECONDS) }
    var checked by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }

    // 授权发生在系统设置里，需随窗口焦点重查，否则回到本页仍显示未授权
    var hasStorage by remember { mutableStateOf(PermissionUtil.checkFilePermissions(context)) }

    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) hasStorage = PermissionUtil.checkFilePermissions(context)
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000L)
            remaining -= 1
        }
    }

    // 忽略空白后再比对，避免全角空格/换行导致"输对了却不通过"
    val phraseOk = input.filterNot { it.isWhitespace() } == Agreement.ACCEPT_PHRASE

    // 返回键不能一按就关窗（那等于悄悄「不同意并退出」），但也不该彻底拦死：
    // 连按两次才退出，语义与底部「不同意并退出」一致，超时未再按则回到未武装状态
    var exitArmed by remember { mutableStateOf(false) }
    BackHandler {
        if (exitArmed) {
            exitArmed = false
            onExit()
            return@BackHandler
        }
        exitArmed = true
        ToastUtil.show(context, "再按一次返回键即不同意并退出")
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(EXIT_CONFIRM_MILLIS)
            exitArmed = false
        }
    }

    Scaffold(topBar = {}, containerColor = MiuixTheme.colorScheme.surface) { padding ->
        // MiuixBaseActivity 关掉了 decor fits system windows，键盘不会挤压内容，
        // 不显式消费 ime inset，底部按钮会被键盘完全盖住。
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            Header()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
            ) {
                TermsCard()
                Spacer(Modifier.height(12.dp))
            }

            ActionBar(
                hasStorage = hasStorage,
                checked = checked,
                onToggleChecked = { checked = !checked },
                input = input,
                onInputChange = { input = it },
                remaining = remaining,
                onRequestStorage = {
                    ToastUtil.show(context, "请授予「所有文件访问权限」后返回")
                    (context as? AppCompatActivity)?.let { PermissionUtil.checkOrRequestFilePermissions(it) }
                },
                onExit = onExit,
                onAgree = {
                    // 先校验再落盘：落盘失败必须留在本页，否则下次启动又弹窗
                    when {
                        remaining > 0 -> ToastUtil.show(context, "请阅读满 $COUNTDOWN_SECONDS 秒")
                        !hasStorage -> ToastUtil.show(context, "需要「所有文件访问权限」才能保存同意状态")
                        !checked -> ToastUtil.show(context, "请先勾选同意")
                        !phraseOk -> ToastUtil.show(context, "请输入「${Agreement.ACCEPT_PHRASE}」")
                        !Agreement.accept() -> ToastUtil.show(context, "同意状态保存失败，请稍后重试")
                        else -> onAgree()
                    }
                }
            )
        }
    }
}

@Composable
private fun Header() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp)
    ) {
        Text(
            text = "使用前请阅读",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                // 用 primary 淡色做底而非 primaryContainer，后者与 primary 文字几乎同色
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = "条款版本 ${Agreement.VERSION}",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.primary
            )
        }
    }
}

/** 条款卡片：序号 + 标题 + 正文，条间用极细分割线分隔 */
@Composable
private fun TermsCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        TERMS.forEachIndexed { index, term ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f))
                )
            }
            // 加粗用 onBackground，与正文的次要灰拉开明度差
            val highlightColor = MiuixTheme.colorScheme.onBackground
            val body = remember(term.body, highlightColor) { term.body.highlight(highlightColor) }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = term.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = body,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

/** 底部操作区：勾选行 / 输入框 / 并排双按钮，不叠卡片 */
@Composable
private fun ActionBar(
    hasStorage: Boolean,
    checked: Boolean,
    onToggleChecked: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    remaining: Int,
    onRequestStorage: () -> Unit,
    onExit: () -> Unit,
    onAgree: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        // 细分隔线：滚动正文与固定操作区的边界
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.16f))
        )

        // 底部留 18dp，避开手势条
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 18.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggleChecked() })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CheckDot(checked = checked)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "我已阅读并理解上述内容，自愿承担使用风险",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Spacer(Modifier.height(10.dp))

            TextField(
                value = input,
                onValueChange = onInputChange,
                label = "请输入「${Agreement.ACCEPT_PHRASE}」",
                modifier = Modifier.fillMaxWidth()
            )

            if (!hasStorage) {
                // 主界面的授权入口在本页之后，这里不给入口就是死锁
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "需要「所有文件访问权限」才能保存同意状态",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "去授权",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onRequestStorage).padding(4.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // 等宽同高并排，靠底色分主次；12dp 间距避免两色块连成横带
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onExit,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Text(
                        text = "不同意并退出",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                }

                // 倒计时期间保持灰底而非 disabled：点了要能提示，disabled 无任何反馈
                Button(
                    onClick = onAgree,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = if (remaining > 0) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(
                        text = if (remaining > 0) "请阅读（${remaining}s）" else "同意并继续",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (remaining > 0) MiuixTheme.colorScheme.onBackground else Color.White
                    )
                }
            }
        }
    }
}

/** 自绘圆形勾选：避免为 Checkbox 再叠一块卡片 */
@Composable
private fun CheckDot(checked: Boolean) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(
                if (checked) scheme.primary else scheme.onBackground.copy(alpha = 0.10f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Canvas(modifier = Modifier.size(11.dp)) {
                val w = size.width
                val h = size.height
                drawLine(
                    color = Color.White,
                    start = Offset(w * 0.05f, h * 0.55f),
                    end = Offset(w * 0.38f, h * 0.88f),
                    strokeWidth = w * 0.18f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(w * 0.38f, h * 0.88f),
                    end = Offset(w * 0.95f, h * 0.12f),
                    strokeWidth = w * 0.18f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
