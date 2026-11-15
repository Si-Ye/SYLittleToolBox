package io.github.siye.littletoolbox

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.siye.littletoolbox.core.tool.ToolCategory

@Composable
internal fun HomePage(preferences: ToolboxPreferences, onSearch: () -> Unit, onTool: (String) -> Unit) {
    val all = ToolCatalog.ordered(preferences.homeOrder)
    val recent = preferences.recent.mapNotNull(ToolCatalog::find).filter { it.module != null }
    val gutter = LocalPageGutter.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(gutter, 18.dp, gutter, 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(43.dp))
                Text("一袖", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Surface(
                onClick = onSearch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 17.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_search), null, tint = MaterialTheme.colorScheme.primary)
                    Text("按名称或用途搜索工具", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeading("最近使用") }
            items(recent.chunked(2)) { row -> ToolCardRow(row, onTool) }
        }
        item { SectionHeading("全部工具") }
        items(all.chunked(2)) { row -> ToolCardRow(row, onTool) }
    }
}

@Composable
private fun ToolCardRow(row: List<ToolEntry>, onTool: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row.forEach { entry ->
            Card(
                onClick = { onTool(entry.info.id) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ToolIcon(entry)
                    Text(entry.info.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.info.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.module == null) {
                        Text("开发中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (row.size == 1) Box(Modifier.weight(1f))
    }
}

@Composable
internal fun CategoriesPage(onTool: (String) -> Unit) {
    val gutter = LocalPageGutter.current
    LazyColumn(contentPadding = PaddingValues(gutter, 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { PageHeading("分类") }
        ToolCategory.entries.forEach { category ->
            val matches = ToolCatalog.entries.filter { it.info.category == category }
            if (matches.isNotEmpty()) {
                item { SectionHeading(category.title) }
                items(matches, key = { it.info.id }) { entry -> ToolRow(entry, onTool) }
            }
        }
    }
}

@Composable
internal fun SearchPage(onTool: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val result = ToolCatalog.search(query)
    val gutter = LocalPageGutter.current
    LazyColumn(contentPadding = PaddingValues(gutter, 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageHeading("搜索") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                label = { Text("名称、别名或用途") },
                shape = RoundedCornerShape(18.dp),
            )
            LaunchedEffect(focus) { focus.requestFocus() }
        }
        if (result.isEmpty()) item { Text("没有匹配的工具。") }
        items(result, key = { it.info.id }) { entry -> ToolRow(entry, onTool) }
    }
}

@Composable
internal fun CollectionsPage(preferences: ToolboxPreferences, state: ToolboxState, onOpen: (String) -> Unit) {
    var create by rememberSaveable { mutableStateOf(false) }
    val gutter = LocalPageGutter.current
    LazyColumn(contentPadding = PaddingValues(gutter, 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                PageHeading("组合")
                Button(onClick = { create = true }) { Text("新建组合") }
            }
        }
        if (preferences.collections.isEmpty()) item { Text("把常一起用的工具放进一个组合。") }
        items(preferences.collections, key = { it.id }) { collection ->
            Card(onClick = { onOpen(collection.id) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(collection.name) },
                    supportingContent = { Text("${collection.tools.count { ToolCatalog.find(it) != null }} 个工具") },
                    leadingContent = { Icon(painterResource(R.drawable.ic_collections), null) },
                    trailingContent = {
                        ReorderControls(
                            before = { state.moveCollection(collection.id, -1) },
                            after = { state.moveCollection(collection.id, 1) },
                        )
                    },
                )
            }
        }
    }
    if (create) CollectionNameDialog("新建组合", "", { create = false }) {
        state.addCollection(it)
        create = false
    }
}

@Composable
internal fun CollectionPage(collection: ToolCollection, state: ToolboxState, onTool: (String) -> Unit) {
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val gutter = LocalPageGutter.current
    LazyColumn(contentPadding = PaddingValues(gutter, 8.dp, gutter, 22.dp)) {
        item {
            Row {
                TextButton(onClick = { rename = true }) { Text("重命名") }
                TextButton(onClick = { delete = true }) { Text("删除组合") }
            }
            SectionHeading("已加入")
        }
        val selected = collection.tools.mapNotNull(ToolCatalog::find)
        if (selected.isEmpty()) item { Text("从下方勾选要加入的工具。", Modifier.padding(vertical = 10.dp)) }
        items(selected, key = { it.info.id }) { entry ->
            ListItem(
                modifier = Modifier.clickable { onTool(entry.info.id) },
                headlineContent = { Text(entry.info.name) },
                trailingContent = {
                    ReorderControls(
                        before = { state.moveCollectionTool(collection.id, entry.info.id, -1) },
                        after = { state.moveCollectionTool(collection.id, entry.info.id, 1) },
                    )
                },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeading("选择工具")
        }
        items(ToolCatalog.entries) { entry ->
            val toggle = { state.toggleCollectionTool(collection.id, entry.info.id); Unit }
            ListItem(
                modifier = Modifier.clickable(onClick = toggle),
                headlineContent = { Text(entry.info.name) },
                trailingContent = { Checkbox(entry.info.id in collection.tools, onCheckedChange = { toggle() }) },
            )
        }
    }
    if (rename) CollectionNameDialog("重命名组合", collection.name, { rename = false }) {
        state.renameCollection(collection.id, it)
        rename = false
    }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text("删除这个组合？") },
        text = { Text("里面的工具仍可从首页和分类打开。") },
        confirmButton = { TextButton(onClick = { delete = false; state.deleteCollection(collection.id) }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } },
    )
}

@Composable
internal fun SettingsPage(preferences: ToolboxPreferences, state: ToolboxState, onRoute: (String) -> Unit) {
    val context = LocalContext.current
    val gutter = LocalPageGutter.current
    LazyColumn(contentPadding = PaddingValues(gutter, 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { PageHeading("设置") }
        item { SectionHeading("外观") }
        item { ChoiceRow("亮暗模式", preferences.colorMode, ColorMode.entries, { it.title }, { state.colorMode(it) }) }
        item { ChoiceRow("主题色", preferences.palette, Palette.entries, { it.title }, { state.palette(it) }) }
        item { ChoiceRow("文字大小", preferences.typeSize, TypeSize.entries, { it.title }, { state.typeSize(it) }) }
        item { ChoiceRow("布局密度", preferences.density, DensityMode.entries, { it.title }, { state.density(it) }) }
        item { ChoiceRow("页面动效", preferences.motion, Motion.entries, { it.title }, { state.motion(it) }) }
        item {
            SettingsRow("按键音", "首页、工具与底栏点击反馈", trailing = {
                Switch(preferences.sounds, onCheckedChange = { state.sounds(it) })
            })
        }
        item { SectionHeading("工具与权限") }
        item { SettingsRow("首页排序", "调整工具在首页中的顺序", { onRoute("home-order") }) }
        item {
            SettingsRow("即记桌面快捷方式", "从桌面直接进入记录", {
                if (!pinQuickNote(context)) {
                    Toast.makeText(context, "当前桌面不支持固定快捷方式，可长按一袖图标打开即记。", Toast.LENGTH_LONG).show()
                }
            })
        }
        item {
            SettingsRow("应用权限", "在系统设置中查看一袖的权限", {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            })
        }
        item { SectionHeading("应用") }
        item { SettingsRow("使用帮助", "查看操作步骤与工具说明", { onRoute("help") }) }
        item { SettingsRow("关于一袖", BuildConfig.VERSION_NAME, { onRoute("about") }) }
    }
}

@Composable
internal fun HomeOrderPage(preferences: ToolboxPreferences, state: ToolboxState) {
    val order = ToolCatalog.ordered(preferences.homeOrder)
    LazyColumn(contentPadding = PaddingValues(LocalPageGutter.current, 8.dp)) {
        items(order, key = { it.info.id }) { entry ->
            ListItem(
                headlineContent = { Text(entry.info.name) },
                leadingContent = { ToolIcon(entry) },
                trailingContent = {
                    ReorderControls(
                        before = { state.moveHomeTool(entry.info.id, -1) },
                        after = { state.moveHomeTool(entry.info.id, 1) },
                    )
                },
            )
        }
    }
}

@Composable
internal fun AboutPage() {
    val context = LocalContext.current
    DetailColumn {
        androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(58.dp))
        PageHeading("一袖")
        Text("版本 ${BuildConfig.VERSION_NAME} · 开发者 Si-Ye")
        Text("项目许可证 Apache-2.0")
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Si-Ye/SYLittleToolBox")))
        }) { Text("查看源代码") }
    }
}

@Composable
internal fun HelpPage(onOpen: (String) -> Unit) {
    DetailColumn {
        Text("首页可按名称或用途查找工具，也可以用底栏切换分类。常用工具可收入组合，并在设置里调整首页顺序。")
        ToolCatalog.entries.filter { it.info.help.isNotEmpty() }.forEach { entry ->
            SettingsRow(entry.info.name, entry.info.description, { onOpen(entry.info.id) })
        }
    }
}

@Composable
internal fun ToolHelpPage(entry: ToolEntry) {
    DetailColumn {
        entry.info.help.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
internal fun PendingToolPage(entry: ToolEntry) {
    DetailColumn {
        ToolIcon(entry)
        SectionHeading("开发中")
        Text(entry.info.description)
    }
}

@Composable
private fun DetailColumn(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(LocalPageGutter.current, 16.dp),
    ) {
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    }
}

@Composable
private fun ToolRow(entry: ToolEntry, onTool: (String) -> Unit) {
    Surface(onClick = { onTool(entry.info.id) }, shape = RoundedCornerShape(16.dp)) {
        ListItem(
            headlineContent = { Text(entry.info.name) },
            supportingContent = { Text(entry.info.description) },
            leadingContent = { ToolIcon(entry) },
            trailingContent = if (entry.module == null) ({ Text("开发中") }) else null,
        )
    }
}

@Composable
private fun ToolIcon(entry: ToolEntry) {
    val icon = if (entry.info.id == QUICK_NOTE_ID) R.drawable.ic_quick_note else when (entry.info.category) {
        ToolCategory.NETWORK -> R.drawable.ic_wifi
        ToolCategory.TRAVEL -> R.drawable.ic_trail
        ToolCategory.TEXT -> R.drawable.ic_type
        ToolCategory.IMAGE -> R.drawable.ic_image
        ToolCategory.MEDIA -> R.drawable.ic_audio
    }
    Box(
        Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun PageHeading(title: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, Modifier.padding(vertical = 5.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = trailing,
        )
    }
}

@Composable
private fun <T> ChoiceRow(title: String, selected: T, choices: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsRow(title, label(selected), { open = true })
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    choices.forEach { choice ->
                        TextButton(
                            onClick = { onSelect(choice); open = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label(choice)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CollectionNameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it.take(24) }, singleLine = true, label = { Text("名称") }) },
        confirmButton = { TextButton(onClick = { save(value.trim()) }, enabled = value.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}

@Composable
private fun ReorderControls(before: () -> Unit, after: () -> Unit) {
    Row {
        IconButton(onClick = before) { Icon(painterResource(R.drawable.ic_up), "上移") }
        IconButton(onClick = after) { Icon(painterResource(R.drawable.ic_down), "下移") }
    }
}
