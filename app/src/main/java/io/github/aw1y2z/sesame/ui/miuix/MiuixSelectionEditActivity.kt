package io.github.aw1y2z.sesame.ui.miuix

import android.content.Intent
import io.github.aw1y2z.sesame.ui.HostBridge
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aw1y2z.sesame.data.ConfigV2
import io.github.aw1y2z.sesame.data.Model
import io.github.aw1y2z.sesame.data.ModelField
import io.github.aw1y2z.sesame.data.ModelFields
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectAndCountModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectAndCountOneModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectOneModelField
import io.github.aw1y2z.sesame.entity.IdAndName
import io.github.aw1y2z.sesame.entity.KVNode
import io.github.aw1y2z.sesame.util.Log
import io.github.aw1y2z.sesame.util.MessageUtil
import io.github.aw1y2z.sesame.util.ToastUtil
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** 选填编辑页（四级）：编辑 SELECT* 类型字段，Intent 传入 userId/groupCode/fieldCode/modelCode */
class MiuixSelectionEditActivity : MiuixBaseActivity() {

    companion object {
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_GROUP_CODE = "groupCode"
        const val EXTRA_FIELD_CODE = "fieldCode"
        const val EXTRA_MODEL_CODE = "modelCode"
    }

    private var userId: String? = null
    private var groupCode: String? = null
    private var fieldCode: String? = null
    private var modelCode: String? = null

    /** 顶部返回与系统返回键都先调用它再退出，避免 dirty 变更因直接 finish 而丢失 */
    internal var saveHandler: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userId = intent.getStringExtra(EXTRA_USER_ID)
        groupCode = intent.getStringExtra(EXTRA_GROUP_CODE)
        fieldCode = intent.getStringExtra(EXTRA_FIELD_CODE)
        modelCode = intent.getStringExtra(EXTRA_MODEL_CODE)
        setAppContent {
            val modelCodeVal = modelCode
            val fieldCodeVal = fieldCode
            if (fieldCodeVal != null && modelCodeVal != null) {
                @Suppress("UNCHECKED_CAST")
                val field = (ConfigV2.INSTANCE.getModelFields(modelCodeVal) as? ModelFields)?.get(fieldCodeVal) as? ModelField<*>
                if (field != null) {
                    SelectionEditContent(
                        activity = this,
                        field = field,
                        modelCode = modelCodeVal,
                        userId = userId
                    )
                } else {
                    top.yukonga.miuix.kmp.basic.Text("字段不存在: $fieldCodeVal")
                }
            } else {
                top.yukonga.miuix.kmp.basic.Text("缺少参数")
            }
        }
    }

    override fun onBackPressed() {
        saveHandler?.invoke()
        finish()
    }

    /** 顶部返回按钮与系统返回统一入口：先保存再退出。 */
    fun saveAndFinish() {
        saveHandler?.invoke()
        finish()
    }
}

@Composable
fun SelectionEditContent(
    activity: MiuixSelectionEditActivity,
    field: ModelField<*>,
    modelCode: String,
    userId: String?
) {
    // 始终以 ConfigV2.INSTANCE 中的实时字段读取当前值，
    // 避免传入引用与单例不一致时读不到已保存的勾选。
    val liveField = ConfigV2.INSTANCE.getModelFields(modelCode)?.get(field.code) ?: field
    val single = liveField.type == "SELECT_ONE" || liveField.type == "SELECT_AND_COUNT_ONE"
    // 所有 SELECT_AND_COUNT 类型字段都带"数量/次数"语义，统一显示次数编辑（滑块），
    // 例如小鸡乐园兑奖、帮喂小鸡、送麦子等"请填写X次数(每日)"字段。
    val withCount = liveField.type == "SELECT_AND_COUNT"
    // withCount 字段的新勾选项默认值取字段数值下限（合种浇水=0、浇水好友=1）；非 withCount 维持 1 不变，避免影响其它列表
    val defaultCount = if (withCount) ((liveField as? SelectAndCountModelField)?.valueRangeMin?.toInt() ?: 1) else 1
    // 合种浇水两列表用数值输入框而非滑块
    val useInputBox = liveField.code == "cooperateWaterList" || liveField.code == "cooperateWaterTotalLimitList"

    @Suppress("UNCHECKED_CAST")
    val smf = when {
        liveField.type == "SELECT" -> liveField as? SelectModelField
        liveField.type == "SELECT_ONE" -> liveField as? SelectOneModelField
        liveField.type == "SELECT_AND_COUNT" -> liveField as? SelectAndCountModelField
        liveField.type == "SELECT_AND_COUNT_ONE" -> liveField as? SelectAndCountOneModelField
        else -> null
    }

    // 选项列表与初始勾选只在进入页面时解析一次（remember 键为不变量）：
    // getExpandValue() 走 AlipayUser::getList 时是「遍历全部好友 + 逐个 new 对象」的 O(n) 分配，
    // 若每次重组都执行，还会让下游 filteredOptions 的 remember(options, ...) 缓存永久失效。
    val initialState = remember(modelCode, field.code) {
        @Suppress("UNCHECKED_CAST")
        val options: List<IdAndName> = (smf?.expandValue ?: emptyList<Any>()) as List<IdAndName>
        val v = liveField.value
        val ids: Set<String> = when {
            liveField.type == "SELECT" -> (v as? Set<*>)?.mapNotNull { it?.toString() }?.toSet() ?: emptySet()
            liveField.type == "SELECT_ONE" -> {
                val sv = v as? String
                if (sv != null && sv.isNotEmpty()) setOf(sv) else emptySet()
            }
            liveField.type == "SELECT_AND_COUNT_ONE" -> {
                // value 是 KVNode<String, Integer>，取 key
                val key = (v as? KVNode<*, *>)?.key?.toString()
                if (key != null && key.isNotEmpty()) setOf(key) else emptySet()
            }
            else -> {
                // SELECT_AND_COUNT：value 是 Map<String, Integer>，取 key 集合
                (v as? Map<*, *>)?.keys?.mapNotNull { it?.toString() }?.toSet() ?: emptySet()
            }
        }
        val initialCounts: Map<String, Int> = when {
            withCount -> (v as? Map<*, *>)
                ?.mapValues { (_, value) -> (value as? Int) ?: defaultCount }
                ?.mapKeys { (k, _) -> k as? String ?: "" }
                ?.filterKeys { it in ids } ?: emptyMap()
            liveField.type == "SELECT_AND_COUNT_ONE" -> {
                val kv = v as? KVNode<*, *>
                val key = kv?.key?.toString()
                val count = (kv?.value as? Int) ?: 1
                if (key != null && key.isNotEmpty()) mapOf(key to count) else emptyMap()
            }
            else -> emptyMap()
        }
        Triple(options, ids, initialCounts)
    }
    val options = initialState.first
    val selectedIds = initialState.second
    val initialCounts = initialState.third

    // 黑名单来源标注（id -> "（手动）"/"（默认）"/"（自动）"/"（自动·永久）"）：进入页面时算一次，
    // 避免在列表渲染循环里逐项查表。非黑名单字段反查不到目标，标注为空。
    // 注意：候选项里含"从未拉黑"的任务，所以"手动"只标注**确实在黑名单里**的（进入页面时的选中集），
    // 否则会把未拉黑的候选项全标成手动。
    val blackListMarks: Map<String, String> = remember(modelCode, field.code, selectedIds) {
        val target = MessageUtil.autoBlackListTarget(field.code) ?: return@remember emptyMap()
        options.mapNotNull { opt ->
            when (MessageUtil.blackListOrigin(target[0], field.code, opt.id)) {
                MessageUtil.ORIGIN_AUTO_PERMANENT -> opt.id to "（自动·永久）"
                MessageUtil.ORIGIN_AUTO -> opt.id to "（自动）"
                MessageUtil.ORIGIN_PRESET -> opt.id to "（默认）"
                else -> if (opt.id in selectedIds) opt.id to "（手动）" else null
            }
        }.toMap()
    }

    // 诊断日志只写一次：放在 Composable 主体会导致每次重组都做 O(n) 的 value.toString() 并入队写盘。
    LaunchedEffect(Unit) {
        Log.i("SelectionEdit", "Entry: field=${liveField.code}, type=${liveField.type}, value=${liveField.value}, selectedIds=$selectedIds")
    }

    var sel by remember { mutableStateOf(selectedIds) }
    var counts by remember {
        mutableStateOf(selectedIds.associateWith { initialCounts[it] ?: defaultCount })
    }
    var searchQuery by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }

    val filteredOptions = remember(options, searchQuery) {
        if (searchQuery.isBlank()) options
        else options.filter { it.name.contains(searchQuery, ignoreCase = true) || it.id.contains(searchQuery) }
    }

    // 选中项自动置顶
    val sortedOptions = remember(filteredOptions, sel) {
        filteredOptions.sortedByDescending { it.id in sel }
    }

    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun applyAndSave() {
        // 始终以 ConfigV2.INSTANCE 中的字段为准，避免传入引用与单例不一致导致写入丢失。
        val configField = ConfigV2.INSTANCE.getModelFields(modelCode)?.get(field.code) ?: field
        when (configField.type) {
            "SELECT" -> configField.setObjectValue(sel)
            "SELECT_ONE" -> configField.setObjectValue(sel.firstOrNull())
            "SELECT_AND_COUNT" -> {
                val csmf = configField as? SelectAndCountModelField
                csmf?.clear()
                sel.forEach { id -> csmf?.add(id, counts[id] ?: 1) }
            }
            "SELECT_AND_COUNT_ONE" -> {
                val csmf = configField as? SelectAndCountOneModelField
                csmf?.clear()
                csmf?.add(sel.firstOrNull() ?: "", counts[sel.firstOrNull()] ?: 1)
            }
        }
        val saved = if (userId != null) ConfigV2.save(userId, true) else false
        Log.i("SelectionEdit", "applyAndSave: field=${field.code}, saved=$saved, value=${configField.value}")
        if (saved) {
            // 本页的保存是"退出时隐式落盘"，成功不弹气泡（失败才提示）
            dirty = false
            if (userId != null) {
                HostBridge.send(activity, "restart", userId, null, false)
            }
        } else {
            ToastUtil.show(activity, "保存失败")
        }
    }

    // 退出前统一先保存：顶部返回按钮与系统返回键（手势/物理键）共用同一逻辑。
    androidx.compose.runtime.SideEffect {
        activity.saveHandler = { if (dirty) applyAndSave() }
    }

    Scaffold(
        topBar = {
            LogTopBar(
                title = field.name ?: "",
                // 无改动时静默退出，不再提示"没有未保存的更改"
                onBack = { activity.saveAndFinish() },
                actions = {
                    if (!single) {
                        top.yukonga.miuix.kmp.basic.IconButton(onClick = {
                            val added = filteredOptions.map { it.id }.filter { it !in sel }
                            sel = sel + added
                            var nc = counts
                            added.forEach { id -> if (id !in nc) nc = nc + (id to (initialCounts[id] ?: defaultCount)) }
                            counts = nc
                            dirty = true
                        }) {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = Icons.Filled.SelectAll,
                                contentDescription = "全选",
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                        top.yukonga.miuix.kmp.basic.IconButton(onClick = {
                            var ns = sel
                            var nc = counts
                            filteredOptions.forEach { opt ->
                                if (opt.id in ns) {
                                    ns = ns - opt.id
                                } else {
                                    ns = ns + opt.id
                                    if (opt.id !in nc) nc = nc + (opt.id to (initialCounts[opt.id] ?: defaultCount))
                                }
                            }
                            sel = ns
                            counts = nc
                            dirty = true
                        }) {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = Icons.Filled.FlipToBack,
                                contentDescription = "反选",
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                        top.yukonga.miuix.kmp.basic.IconButton(onClick = {
                            // 取消本次操作：恢复进入时的勾选快照，不写盘并退出
                            sel = selectedIds
                            counts = selectedIds.associateWith { initialCounts[it] ?: defaultCount }
                            dirty = false
                            activity.saveAndFinish()
                        }) {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "取消",
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            )
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (!single) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = "",
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.Search,
                                contentDescription = "搜索",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                top.yukonga.miuix.kmp.basic.Text(
                                    "×",
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(end = 12.dp).clickable { searchQuery = "" }
                                )
                            }
                        }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    // 先裁到圆角再画底；并且**不在这里加左右内边距**：
                    // 行的文字缩进由行自身的 insideMargin 提供，行顶满卡片宽度后
                    // 条目的按压效果才是通栏色带，否则会露出方框
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            ) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(count = sortedOptions.size, key = { idx -> sortedOptions[idx].id }) { idx ->
                        val opt = sortedOptions[idx]
                        val isChecked = sel.contains(opt.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (single) {
                                RadioButtonPreference(
                                    title = opt.name + (blackListMarks[opt.id] ?: ""),
                                    selected = isChecked,
                                    onClick = {
                                        sel = setOf(opt.id)
                                        dirty = true
                                    }
                                )
                            } else {
                                CheckboxPreference(
                                    title = opt.name + (blackListMarks[opt.id] ?: ""),
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            sel = sel + opt.id
                                            if (!counts.containsKey(opt.id)) {
                                                counts = counts + (opt.id to (initialCounts[opt.id] ?: defaultCount))
                                            }
                                        } else {
                                            sel = sel - opt.id
                                        }
                                        dirty = true
                                    }
                                )
                            }
                        }
                        if (withCount && isChecked) {
                            key(opt.id) {
                                if (useInputBox) {
                                    var text by remember(opt.id) { mutableStateOf((counts[opt.id] ?: defaultCount).toString()) }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            "数量(克)",
                                            fontSize = 14.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                        TextField(
                                            value = text,
                                            onValueChange = { input ->
                                                val filtered = input.filter { it.isDigit() }
                                                text = filtered
                                                counts = counts + (opt.id to (filtered.toIntOrNull() ?: 0))
                                                dirty = true
                                            },
                                            label = "",
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                } else {
                                    var sliderValue by remember(opt.id) { mutableFloatStateOf((counts[opt.id] ?: defaultCount).toFloat()) }
                                    SliderPreference(
                                        title = "数量",
                                        value = sliderValue,
                                        valueRange = run {
                                            val f = liveField as? SelectAndCountModelField
                                            (f?.valueRangeMin ?: 0f)..(f?.valueRangeMax ?: 100f)
                                        },
                                        valueText = sliderValue.roundToInt().toString(),
                                        onValueChange = { sliderValue = it },
                                        onValueChangeFinished = {
                                            counts = counts + (opt.id to sliderValue.roundToInt())
                                            dirty = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (sortedOptions.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}
