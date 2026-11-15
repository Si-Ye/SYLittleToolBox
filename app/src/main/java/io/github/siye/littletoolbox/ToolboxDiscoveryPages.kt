package io.github.siye.littletoolbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siye.littletoolbox.core.tool.ToolCategory

private val DiscoveryCardShape = RoundedCornerShape(20.dp)
private val DiscoveryGap = 12.dp

@Composable
internal fun HomePage(preferences: ToolboxPreferences, onSearch: () -> Unit, onTool: (String) -> Unit) {
    val all = ToolCatalog.ordered(preferences.homeOrder)
    val recent = preferences.recent.mapNotNull(ToolCatalog::find).filter { it.module != null }
    val minCardWidth = with(LocalDensity.current) { maxOf(148.dp, 136.sp.toDp()) }
    DiscoveryList { contentWidth ->
        val columns = when {
            contentWidth >= minCardWidth * 3 + DiscoveryGap * 2 -> 3
            contentWidth >= minCardWidth * 2 + DiscoveryGap -> 2
            else -> 1
        }
        item(key = "home-title") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrandMark(40.dp)
                Text(
                    "一袖",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        item(key = "home-search") { DiscoverySearch(onSearch) }
        if (recent.isNotEmpty()) {
            item(key = "recent-title") { ToolSectionHeading("最近使用") }
            homeToolCards("recent", recent, columns, onTool)
        }
        item(key = "all-title") { ToolSectionHeading("全部工具") }
        homeToolCards("all", all, columns, onTool)
    }
}

@Composable
internal fun CategoriesPage(onCategory: (ToolCategory) -> Unit) {
    DiscoveryList { _ ->
        item(key = "categories-title") {
            Text(
                "分类",
                modifier = Modifier.semantics { heading() }.padding(bottom = 2.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(ToolCategory.entries, key = { it.name }) { category ->
            Surface(
                onClick = { onCategory(category) },
                modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
                shape = DiscoveryCardShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 88.dp).padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DiscoveryGlyph(category.discoveryIcon, size = 48.dp)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            category.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            category.discoveryDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DiscoveryForwardIcon()
                }
            }
        }
    }
}

@Composable
internal fun CategoryToolsPage(category: ToolCategory, onTool: (String) -> Unit) {
    val tools = ToolCatalog.entries.filter { it.info.category == category }
    DiscoveryList { _ ->
        items(tools, key = { it.info.id }) { entry ->
            DiscoveryToolCard(entry, onTool, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DiscoveryList(content: LazyListScope.(Dp) -> Unit) {
    val gutter = LocalPageGutter.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            val contentWidth = maxWidth - gutter * 2
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(gutter, 16.dp, gutter, 24.dp),
                verticalArrangement = Arrangement.spacedBy(DiscoveryGap),
            ) { content(contentWidth) }
        }
    }
}

private fun LazyListScope.homeToolCards(
    section: String,
    tools: List<ToolEntry>,
    columns: Int,
    onTool: (String) -> Unit,
) {
    if (columns == 1) {
        items(tools, key = { "$section/${it.info.id}" }) { entry ->
            DiscoveryToolCard(entry, onTool, Modifier.fillMaxWidth())
        }
        return
    }
    items(tools.chunked(columns), key = { row -> "$section/${row.first().info.id}" }) { row ->
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(DiscoveryGap),
        ) {
            row.forEach { entry ->
                DiscoveryToolCard(entry, onTool, Modifier.weight(1f).fillMaxHeight(), stacked = true)
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DiscoverySearch(onSearch: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onSearch,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
        shape = DiscoveryCardShape,
        color = colors.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = colors.primary)
            Text("按名称或用途搜索工具", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolSectionHeading(title: String) {
    Text(
        title,
        modifier = Modifier.semantics { heading() }.padding(top = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DiscoveryToolCard(
    entry: ToolEntry,
    onTool: (String) -> Unit,
    modifier: Modifier,
    stacked: Boolean = false,
) {
    val icon = if (entry.info.id == QUICK_NOTE_ID) R.drawable.ic_quick_note else entry.info.category.discoveryIcon
    Surface(
        onClick = { onTool(entry.info.id) },
        modifier = modifier.semantics { role = Role.Button },
        shape = DiscoveryCardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        if (stacked) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DiscoveryGlyph(icon)
                    DiscoveryForwardIcon()
                }
                DiscoveryToolText(entry)
            }
        } else {
            Row(
                modifier = Modifier.heightIn(min = 80.dp).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiscoveryGlyph(icon)
                DiscoveryToolText(entry, Modifier.weight(1f))
                DiscoveryForwardIcon()
            }
        }
    }
}

@Composable
private fun DiscoveryToolText(entry: ToolEntry, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(entry.info.name, style = typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(entry.info.description, style = typography.bodyMedium, color = colors.onSurfaceVariant)
        if (entry.module == null) {
            Text("开发中", style = typography.labelMedium, color = colors.primary)
        }
    }
}

@Composable
internal fun BrandMark(size: Dp) = DiscoveryGlyph(R.drawable.ic_brand_mark, size)

@Composable
private fun DiscoveryGlyph(icon: Int, size: Dp = 40.dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(size).background(colors.primaryContainer, RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(size / 2), tint = colors.onPrimaryContainer)
    }
}

@Composable
private fun DiscoveryForwardIcon() {
    Icon(
        painterResource(R.drawable.ic_forward),
        contentDescription = null,
        modifier = Modifier.size(17.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val ToolCategory.discoveryIcon: Int
    get() = when (this) {
        ToolCategory.NETWORK -> R.drawable.ic_wifi
        ToolCategory.TRAVEL -> R.drawable.ic_trail
        ToolCategory.TEXT -> R.drawable.ic_type
        ToolCategory.IMAGE -> R.drawable.ic_image
        ToolCategory.MEDIA -> R.drawable.ic_audio
    }

private val ToolCategory.discoveryDescription: String
    get() = when (this) {
        ToolCategory.NETWORK -> "连接信息与网络诊断"
        ToolCategory.TRAVEL -> "轨迹记录与停车点定位"
        ToolCategory.TEXT -> "即时笔记与字数统计"
        ToolCategory.IMAGE -> "图片格式转换与压缩"
        ToolCategory.MEDIA -> "从视频中提取音频"
    }
