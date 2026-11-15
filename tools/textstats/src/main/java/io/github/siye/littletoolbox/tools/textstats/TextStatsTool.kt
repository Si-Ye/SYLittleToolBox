package io.github.siye.littletoolbox.tools.textstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.siye.littletoolbox.core.tool.ToolCategory
import io.github.siye.littletoolbox.core.tool.ToolInfo
import io.github.siye.littletoolbox.core.tool.ToolModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object TextStatsTool : ToolModule {
    override val info = ToolInfo(
        id = "text-stats",
        name = "字数统计",
        category = ToolCategory.TEXT,
        description = "实时统计字数、字符数、英文词数、行数和编码字节数。",
        keywords = listOf("字符计数", "汉字", "英文词数", "行数", "字节", "UTF-8", "UTF-16"),
        help = listOf(
            "输入、长按粘贴，或从其他应用分享文本。正文仅在当前页面内离线处理。",
            "字数按包含 Unicode 字母或数字的字素簇计数，排除空白、标点和表情。字符数包含空白、标点和换行，组合重音和连写表情分别按一个字素簇计数。",
            "汉字数统计汉字书写系统的字母和数字字符，例如「你好」与「〇」。",
            "英文词数按连续拉丁字母计数，支持重音字母和组合重音。词中单个连字符或撇号连接为一词，例如 well-known、don't 和 we're。数字和其他符号会断开词。",
            "详细统计中的不含空白字符数排除完全由 Unicode 空白组成的字素簇。总行数从 1 开始，CRLF、单独的 CR 或 LF，以及 Unicode 的 NEL、LS、PS 各算一次换行。只含空白的行算空行。",
            "字节数以输入原文编码计算，UTF-16 不含 BOM。",
        ),
    )

    @Composable
    override fun Content(initialText: String?) {
        var input by remember(initialText) { mutableStateOf(initialText.orEmpty()) }
        var expanded by remember { mutableStateOf(false) }
        var statistics by remember(input) {
            mutableStateOf<TextStatistics?>(if (input.isEmpty()) TextStatistics() else null)
        }

        LaunchedEffect(input) {
            val text = input
            if (text.isNotEmpty()) {
                if (text.length >= 64 * 1024) delay(100)
                statistics = withContext(Dispatchers.Default) { calculateTextStatistics(text) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 8,
                label = { Text("待统计文本") },
                placeholder = { Text("输入或长按粘贴文本") },
            )

            val result = statistics
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Statistic("字数", result?.words, Modifier.weight(1f), highlighted = true)
                Statistic("字符数", result?.characters, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Statistic("汉字数", result?.hanCharacters, Modifier.weight(1f))
                Statistic("英文词数", result?.englishWords, Modifier.weight(1f))
            }

            if (result == null) {
                Text(
                    "正在统计…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Card(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                ) {
                    Text(if (expanded) "收起详细统计" else "详细统计")
                }
                if (expanded) {
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatisticDetail("不含换行的字符数", result?.charactersWithoutLineBreaks)
                        StatisticDetail("不含空白的字符数", result?.charactersWithoutWhitespace)
                        StatisticDetail("总行数", result?.lines)
                        StatisticDetail("非空行数", result?.nonBlankLines)
                        StatisticDetail("UTF-8 字节数", result?.utf8Bytes)
                        StatisticDetail("UTF-16 字节数（无 BOM）", result?.utf16Bytes)
                    }
                }
            }
        }
    }
}

@Composable
private fun Statistic(
    label: String,
    value: Int?,
    modifier: Modifier,
    highlighted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        colors = if (highlighted) {
            CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                value?.toString() ?: "—",
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Composable
private fun StatisticDetail(label: String, value: Number?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            value?.toString() ?: "—",
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        )
    }
}
