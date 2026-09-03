package io.github.aw1y2z.sesame.ui.miuix

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.aw1y2z.sesame.data.ConfigPreload
import io.github.aw1y2z.sesame.data.ConfigV2
import io.github.aw1y2z.sesame.data.Model
import io.github.aw1y2z.sesame.data.ModelField
import io.github.aw1y2z.sesame.data.ModelGroup
import io.github.aw1y2z.sesame.data.modelFieldExt.ChoiceModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.EmptyModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectAndCountModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectAndCountOneModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectOneModelField
import io.github.aw1y2z.sesame.entity.IdAndName
import io.github.aw1y2z.sesame.entity.KVNode
import io.github.aw1y2z.sesame.util.Log
import io.github.aw1y2z.sesame.util.StringUtil
import io.github.aw1y2z.sesame.util.ToastUtil
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MiuixSettingsActivity : MiuixBaseActivity() {

    private var userId: String? = null

    /** 三级导航状态：null=分组目录(二级)；非null=该分组字段页(三级) */
    var currentGroup by mutableStateOf<ModelGroup?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userId = intent.getStringExtra("userId")
        Model.initAllModel()
        ConfigPreload.prepare(userId)
        setAppContent {
            SettingsContent(this, userId)
        }
    }

    override fun onBackPressed() {
        if (currentGroup != null) {
            // 在分组字段页(三级)按返回 → 回到分组目录(二级)
            currentGroup = null
        } else {
            save()
            super.onBackPressed()
        }
    }

    fun save() {
        if (ConfigV2.isModify(userId) && ConfigV2.save(userId, false)) {
            ToastUtil.show(this, "保存成功！")
            sendRestartIfNeeded()
        }
    }

    private fun sendRestartIfNeeded() {
        if (!StringUtil.isEmpty(userId)) {
            try {
                val intent = Intent("com.eg.android.AlipayGphone.sesame.restart")
                intent.putExtra("userId", userId)
                sendBroadcast(intent)
            } catch (th: Throwable) {
                Log.printStackTrace(th)
            }
        }
    }
}

@Composable
fun SettingsContent(activity: MiuixSettingsActivity, userId: String?) {
    val context = LocalContext.current

    // 三级导航状态:null=分组目录(二级)
    val group = activity.currentGroup
    var showDeleteDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) {
            val file = ConfigPreload.getConfigFile(userId)
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    file.inputStream().use { it.copyTo(os) }
                }
                ToastUtil.show(context, "导出成功！")
            } catch (e: Exception) {
                ToastUtil.show(context, "导出失败！")
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val file = ConfigPreload.getConfigFile(userId)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                if (!StringUtil.isEmpty(userId)) {
                    try {
                        val intent = Intent("com.eg.android.AlipayGphone.sesame.restart")
                        intent.putExtra("userId", userId)
                        context.sendBroadcast(intent)
                    } catch (th: Throwable) {
                        Log.printStackTrace(th)
                    }
                }
                Model.initAllModel()
                ConfigPreload.prepare(userId)
                ToastUtil.show(context, "导入成功！")
            } catch (e: Exception) {
                ToastUtil.show(context, "导入失败！")
            }
        }
    }

    if (group != null) {
        GroupFieldsPage(activity, userId, group)
        return
    }

    // ============ 二级:分组目录(标题) ============
    Scaffold(
        topBar = {
            LogTopBar(
                title = "配置设置",
                onBack = { activity.finish() },
                onImport = { importLauncher.launch("*/*") },
                onExport = { exportLauncher.launch("[" + (userId ?: "默认") + "]-config_v2.json") },
                onClear = { showDeleteDialog = true }
            )
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            SmallTitle(text = "配置分组")
            CardColumn {
                ModelGroup.values().forEach { g ->
                    if (Model.getGroupModelConfig(g).isNotEmpty()) {
                        ArrowPreference(title = g.getName(), onClick = { activity.currentGroup = g })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (showDeleteDialog) {
                ConfirmDialog(
                    title = "警告",
                    text = "确认删除该配置？",
                    onConfirm = {
                        showDeleteDialog = false
                        if (ConfigPreload.getConfigFile(userId).let { io.github.aw1y2z.sesame.util.FileUtil.deleteFile(it) }) {
                            ToastUtil.show(context, "配置删除成功")
                        }
                        activity.finish()
                    },
                    onDismiss = { showDeleteDialog = false }
                )
            }
        }
    }
}

/** 三级:某大类分组下的全部配置字段(懒加载,避免整页全量组合造成的卡顿) */
@Composable
fun GroupFieldsPage(activity: MiuixSettingsActivity, userId: String?, group: ModelGroup) {
    val modelConfigs = Model.getGroupModelConfig(group).values.toList()
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = group.getName(),
                navigationIcon = {
                    IconButton(onClick = { activity.currentGroup = null }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                }
            )
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            modelConfigs.forEachIndexed { mcIdx, mc ->
                item(key = "group-title-$mcIdx") {
                    SmallTitle(text = mc.name ?: "")
                }
                mc.fields.values.toList().forEachIndexed { fIdx, field ->
                    item(key = "field-$mcIdx-$fIdx") {
                        FieldItem(field = field, onSave = { ConfigV2.save(userId, false) })
                    }
                }
                item(key = "group-spacer-$mcIdx") {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun FieldItem(field: ModelField<*>, onSave: () -> Unit) {
    when (field.type) {
        "BOOLEAN" -> {
            var checked by remember { mutableStateOf(field.value as? Boolean ?: false) }
            SwitchPreference(
                title = field.name ?: "",
                summary = field.description,
                checked = checked,
                onCheckedChange = {
                    checked = it
                    field.setObjectValue(it)
                    onSave()
                }
            )
        }

        "INTEGER", "MULTIPLY_INTEGER" -> {
            val imf = field as? IntegerModelField
            val rawMin = (imf?.minLimit ?: 0).toFloat()
            val rawMax = (imf?.maxLimit ?: 100).toFloat()
            val min = minOf(rawMin, rawMax)
            val max = if (maxOf(rawMin, rawMax) <= min) min + 1f else maxOf(rawMin, rawMax)
            var value by remember { mutableFloatStateOf((field.value as? Int ?: 0).toFloat().coerceIn(min, max)) }
            SliderPreference(
                title = field.name ?: "",
                summary = field.description,
                value = value.coerceIn(min, max),
                valueRange = min..max,
                valueText = value.roundToInt().toString(),
                onValueChange = { value = it.coerceIn(min, max) },
                onValueChangeFinished = {
                    field.setObjectValue(value.roundToInt())
                    onSave()
                }
            )
        }

        "STRING", "TEXT" -> {
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = field.name ?: "",
                summary = field.configValue,
                onClick = { showDialog = true }
            )
            if (showDialog) {
                EditDialog(
                    title = field.name ?: "",
                    initial = field.configValue ?: "",
                    multiline = false,
                    onConfirm = {
                        field.setObjectValue(it)
                        onSave()
                        showDialog = false
                    },
                    onDismiss = { showDialog = false }
                )
            }
        }

        "READ_TEXT", "URL_TEXT" -> {
            ArrowPreference(
                title = field.name ?: "",
                summary = field.configValue
            )
        }

        "SELECT_ONE" -> {
            val smf = field as? SelectOneModelField
            val options = smf?.expandValue ?: emptyList()
            val current = field.value as? String
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = field.name ?: "",
                summary = options.firstOrNull { it.id == current }?.name,
                onClick = { showDialog = true }
            )
            if (showDialog) {
                SelectionDialog(
                    title = field.name ?: "",
                    single = true,
                    withCount = false,
                    options = options,
                    selectedIds = if (current != null) setOf(current) else emptySet(),
                    onConfirm = { ids, _ ->
                        field.setObjectValue(ids.firstOrNull())
                        onSave()
                        showDialog = false
                    },
                    onDismiss = { showDialog = false }
                )
            }
        }

        "SELECT" -> {
            val smf = field as? SelectModelField
            val options = smf?.expandValue ?: emptyList()
            val current = (field.value as? Set<*>)?.mapNotNull { it?.toString() }?.toSet() ?: emptySet()
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = field.name ?: "",
                summary = "已选 ${current.size} 项",
                onClick = { showDialog = true }
            )
            if (showDialog) {
                SelectionDialog(
                    title = field.name ?: "",
                    single = false,
                    withCount = false,
                    options = options,
                    selectedIds = current,
                    onConfirm = { ids, _ ->
                        field.setObjectValue(ids)
                        onSave()
                        showDialog = false
                    },
                    onDismiss = { showDialog = false }
                )
            }
        }

        "SELECT_AND_COUNT_ONE" -> {
            val smf = field as? SelectAndCountOneModelField
            val options = smf?.expandValue ?: emptyList()
            val kv = field.value as? KVNode<*, *>
            val current = kv?.key?.toString()
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = field.name ?: "",
                summary = options.firstOrNull { it.id == current }?.name,
                onClick = { showDialog = true }
            )
            if (showDialog) {
                SelectionDialog(
                    title = field.name ?: "",
                    single = true,
                    withCount = true,
                    options = options,
                    selectedIds = if (current != null) setOf(current) else emptySet(),
                    onConfirm = { ids, counts ->
                        smf?.clear()
                        ids.firstOrNull()?.let { smf?.add(it, counts[it] ?: 1) }
                        onSave()
                        showDialog = false
                    },
                    onDismiss = { showDialog = false }
                )
            }
        }

        "SELECT_AND_COUNT" -> {
            val smf = field as? SelectAndCountModelField
            val options = smf?.expandValue ?: emptyList()
            val currentMap = field.value as? Map<*, *>
            val current = currentMap?.keys?.mapNotNull { it?.toString() }?.toSet() ?: emptySet()
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = field.name ?: "",
                summary = "已选 ${current.size} 项",
                onClick = { showDialog = true }
            )
            if (showDialog) {
                SelectionDialog(
                    title = field.name ?: "",
                    single = false,
                    withCount = true,
                    options = options,
                    selectedIds = current,
                    onConfirm = { ids, counts ->
                        smf?.clear()
                        ids.forEach { smf?.add(it, counts[it] ?: 1) }
                        onSave()
                        showDialog = false
                    },
                    onDismiss = { showDialog = false }
                )
            }
        }

        "LIST" -> {
            val list = (field.value as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = field.name ?: "",
                summary = list.joinToString(","),
                onClick = { showDialog = true }
            )
            if (showDialog) {
                EditDialog(
                    title = field.name ?: "",
                    initial = list.joinToString("\n"),
                    multiline = true,
                    onConfirm = { text ->
                        val newList = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        field.setObjectValue(newList)
                        onSave()
                        showDialog = false
                    },
                    onDismiss = { showDialog = false }
                )
            }
        }

        "CHOICE" -> {
            val cmf = field as? ChoiceModelField
            val choiceArray = cmf?.expandKey ?: emptyArray()
            val current = field.value as? Int ?: 0
            var showDialog by remember { mutableStateOf(false) }
            ArrowPreference(
                title = field.name ?: "",
                summary = choiceArray.getOrNull(current),
                onClick = { showDialog = true }
            )
            if (showDialog) {
                ChoiceDialog(
                    title = field.name ?: "",
                    options = choiceArray,
                    current = current,
                    onConfirm = { idx ->
                        field.setObjectValue(idx)
                        onSave()
                        showDialog = false
                    },
                    onDismiss = { showDialog = false }
                )
            }
        }

        "EMPTY" -> {
            val emf = field as? EmptyModelField
            ArrowPreference(
                title = field.name ?: "",
                onClick = { emf?.clickRunner?.run() }
            )
        }

        else -> {
            Text(
                text = field.name ?: "",
                color = MiuixTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
fun EditDialog(
    title: String,
    initial: String,
    multiline: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(title, color = MiuixTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = title,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = "保存", onClick = { onConfirm(text) })
                }
            }
        }
    }
}

@Composable
fun SelectionDialog(
    title: String,
    single: Boolean,
    withCount: Boolean,
    options: List<IdAndName>,
    selectedIds: Set<String>,
    onConfirm: (Set<String>, Map<String, Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var sel by remember { mutableStateOf(selectedIds) }
    var counts by remember { mutableStateOf(selectedIds.associateWith { 1 }) }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(title, color = MiuixTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                options.forEach { opt ->
                    if (single) {
                        RadioButtonPreference(
                            title = opt.name,
                            selected = sel.contains(opt.id),
                            onClick = { sel = setOf(opt.id) }
                        )
                    } else {
                        val checked = sel.contains(opt.id)
                        CheckboxPreference(
                            title = opt.name,
                            checked = checked,
                            onCheckedChange = { c ->
                                sel = if (c) sel + opt.id else sel - opt.id
                            }
                        )
                        if (withCount && checked) {
                            var c by remember(opt.id) { mutableFloatStateOf((counts[opt.id] ?: 1).toFloat()) }
                            SliderPreference(
                                title = "数量",
                                value = c,
                                valueRange = 0f..100f,
                                valueText = c.roundToInt().toString(),
                                onValueChange = { c = it },
                                onValueChangeFinished = { counts = counts + (opt.id to c.roundToInt()) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = "保存", onClick = { onConfirm(sel, counts) })
                }
            }
        }
    }
}

@Composable
fun ChoiceDialog(
    title: String,
    options: Array<String>,
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var sel by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(title, color = MiuixTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { index, opt ->
                    RadioButtonPreference(
                        title = opt,
                        selected = sel == index,
                        onClick = { sel = index }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = "保存", onClick = { onConfirm(sel) })
                }
            }
        }
    }
}
