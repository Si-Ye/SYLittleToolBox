package io.github.siye.littletoolbox.tools.image

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.siye.littletoolbox.core.tool.ToolCategory
import io.github.siye.littletoolbox.core.tool.ToolInfo
import io.github.siye.littletoolbox.core.tool.UriInputTool
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageTool : UriInputTool {
    override val info = ToolInfo(
        id = "image",
        name = "图片快转",
        category = ToolCategory.IMAGE,
        description = "批量转换 JPG、PNG、WebP，按长边和目标体积压缩。",
        keywords = listOf("图片转换", "相册", "图片压缩", "JPG", "PNG", "WebP", "透明"),
        help = listOf(
            "从系统相册选择图片，或从其他应用把图片分享到一袖。选好预设或调整参数后，点转换。",
            "长边是像素上限，原图较小时不会放大。目标体积按每张文件计算，1 KiB = 1024 字节。为达到目标会依次降低编码质量和缩小尺寸。PNG 通过缩小尺寸控制体积。",
            "JPG 用白色填充透明区域。PNG 和 WebP 保留透明区域。动图只转换首帧。转换保留可见方向，输出会去除原图的位置等 EXIF 信息。",
            "点结果预览可以对比原图和输出。转换完成后选择文件夹保存，或直接分享。以上操作在本机完成。应用会记住上次转换使用的参数。",
        ),
    )

    @Composable
    override fun Content(initialText: String?) = Content(emptyList())

    @Composable
    override fun Content(uris: List<Uri>) {
        ImageScreen(uris, viewModel<ImageConversionViewModel>())
    }
}

@Composable
private fun ImageScreen(incoming: List<Uri>, model: ImageConversionViewModel) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), model::add)
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::save)
    }
    var showAllInputs by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageResult?>(null) }

    LaunchedEffect(incoming) { model.acceptShare(incoming) }

    val inputs = model.inputs
    val shownInputs = if (showAllInputs) inputs else inputs.take(3)
    val results = model.results
    val outputs = model.outputs
    val hasMoreInputs = inputs.size > 3
    val resultSectionIndex = 2 + shownInputs.size + if (hasMoreInputs) 1 else 0
    LaunchedEffect(results.isNotEmpty()) {
        if (results.isNotEmpty()) listState.animateScrollToItem(resultSectionIndex)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("image-import") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (inputs.isEmpty()) "添加图片" else "已选 ${inputs.size} 张图片", style = MaterialTheme.typography.titleMedium)
                        if (inputs.isEmpty()) {
                            Text("从相册选择，或从其他应用分享到一袖。", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                enabled = !model.busy,
                            ) {
                                Text(if (inputs.isEmpty()) "选择图片" else "继续添加")
                            }
                            if (inputs.isNotEmpty()) {
                                TextButton(onClick = model::clear, enabled = !model.busy) { Text("清空") }
                            }
                        }
                    }
                }
            }
            items(shownInputs, key = { "input-${it.uri}" }) { input ->
                InputRow(input, model.busy) { model.remove(input.uri) }
            }
            if (hasMoreInputs) {
                item("image-input-toggle") {
                    TextButton(onClick = { showAllInputs = !showAllInputs }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showAllInputs) "收起图片" else "查看全部 ${inputs.size} 张")
                    }
                }
            }
            item("image-options") { ImageSettings(model.draft, !model.busy, model::change) }
            if (results.isNotEmpty()) {
                item("image-results-header") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("转换结果", style = MaterialTheme.typography.titleMedium)
                            val failures = results.size - outputs.size
                            if (failures == 0) SupportingText("成功转换 ${outputs.size} 张。")
                            else SupportingText("成功 ${outputs.size} 张，失败 $failures 张。")
                        }
                        if (outputs.isNotEmpty() && outputs.size < results.size) {
                            TextButton(onClick = model::convert, enabled = !model.busy) { Text("重新转换") }
                        }
                    }
                }
                items(results, key = { "output-${it.input.uri}" }) { result ->
                    ResultRow(result) { preview = result }
                }
            }
        }

        HorizontalDivider()
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                model.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                val progress = model.progress
                when {
                    progress != null -> {
                        LinearProgressIndicator(
                            progress = { progress.completed.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("正在转换 ${progress.completed} / ${progress.total}", Modifier.weight(1f))
                            TextButton(onClick = model::cancelConversion) { Text("取消") }
                        }
                    }
                    outputs.isNotEmpty() -> {
                        if (model.saving) SupportingText("正在保存图片…")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { folder.launch(null) },
                                enabled = !model.busy,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (outputs.size == 1) "保存图片" else "保存全部") }
                            Button(
                                onClick = { model.share(context, outputs) },
                                enabled = !model.busy,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (outputs.size == 1) "分享图片" else "分享全部") }
                        }
                    }
                    else -> Button(
                        onClick = model::convert,
                        enabled = inputs.isNotEmpty() && model.draft.valid && !model.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (inputs.isEmpty()) "开始转换" else "转换 ${inputs.size} 张") }
                }
            }
        }
    }

    preview?.let { result ->
        result.output?.let { output ->
            ImageComparison(
                input = result.input,
                output = output,
                onClose = { preview = null },
                onShare = {
                    preview = null
                    model.share(context, listOf(output))
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageSettings(draft: ImageDraft, enabled: Boolean, onChange: (ImageDraft) -> Unit) {
    val options = draft.toOptions()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("转换设置", style = MaterialTheme.typography.titleMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ImagePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = options == preset.options,
                        onClick = { onChange(ImageDraft(preset.options)) },
                        label = { Text(preset.label, maxLines = 1) },
                        enabled = enabled,
                    )
                }
            }
            Text("输出格式", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ImageFormat.entries.forEachIndexed { index, format ->
                    SegmentedButton(
                        selected = draft.format == format,
                        onClick = { onChange(draft.copy(format = format)) },
                        shape = SegmentedButtonDefaults.itemShape(index, ImageFormat.entries.size),
                        enabled = enabled,
                    ) { Text(format.label) }
                }
            }
            if (draft.format == ImageFormat.JPG) {
                SupportingText("JPG 会用白色填充透明区域。")
            } else if (draft.format == ImageFormat.PNG) {
                SupportingText("PNG 保留透明区域，启用体积上限时会调整尺寸。")
            }
            OutlinedTextField(
                value = draft.longEdge,
                onValueChange = { onChange(draft.copy(longEdge = it.filter(Char::isDigit))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("长边上限（像素）") },
                enabled = enabled,
                singleLine = true,
                isError = draft.parsedEdge == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(if (draft.parsedEdge == null) "请输入 1–$MAX_LONG_EDGE。" else "保持原图比例，不放大小图。") },
            )
            if (draft.format.supportsQuality) {
                Text("最高质量 ${draft.quality}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = draft.quality.toFloat(),
                    onValueChange = { onChange(draft.copy(quality = it.roundToInt())) },
                    valueRange = 1f..100f,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "最高质量" },
                )
            }
            val invalidTarget = draft.targetKib.isNotEmpty() && draft.parsedTarget == null
            OutlinedTextField(
                value = draft.targetKib,
                onValueChange = { onChange(draft.copy(targetKib = it.filter(Char::isDigit))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("每张目标体积上限（KiB）") },
                enabled = enabled,
                singleLine = true,
                isError = invalidTarget,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(if (invalidTarget) "请输入 1–$MAX_TARGET_KIB。" else "留空则不限制体积。") },
            )
        }
    }
}

@Composable
private fun InputRow(input: ImageInput, busy: Boolean, onRemove: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val preview = rememberPreview(input.uri, 128) { ImageDecoder.createSource(resolver, input.uri) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                preview?.bitmap?.let {
                    Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(input.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                input.bytes?.let { SupportingText(readableBytes(it)) }
            }
            TextButton(onClick = onRemove, enabled = !busy) { Text("移除") }
        }
    }
}

@Composable
private fun ResultRow(result: ImageResult, onPreview: () -> Unit) {
    val output = result.output
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(output?.file?.name ?: result.input.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (output != null) {
                    SupportingText("${output.dimensions.width} × ${output.dimensions.height} · ${readableBytes(output.bytes)}")
                } else {
                    SupportingText(result.failure ?: "转换失败。")
                }
            }
            if (output != null) TextButton(onClick = onPreview) { Text("对比预览") }
        }
    }
}

@Composable
private fun ImageComparison(input: ImageInput, output: ImageOutput, onClose: () -> Unit, onShare: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val originalPreview = rememberPreview(input.uri, 768) { ImageDecoder.createSource(resolver, input.uri) }
    val outputPreview = rememberPreview(output.file, 768) { ImageDecoder.createSource(output.file) }
    val original = originalPreview?.bitmap
    val converted = outputPreview?.bitmap
    var split by remember { mutableFloatStateOf(0.5f) }
    val divider = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("对比预览") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (converted != null) {
                        Image(converted.asImageBitmap(), "转换后", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        SupportingText(if (outputPreview == null) "正在读取预览…" else "无法读取转换后的预览。")
                    }
                    if (original != null && converted != null) {
                        Box(
                            Modifier.fillMaxSize().drawWithContent {
                                val x = size.width * split
                                clipRect(right = x) { this@drawWithContent.drawContent() }
                                drawLine(divider, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                            },
                        ) {
                            Image(
                                original.asImageBitmap(), "原图",
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
                if (originalPreview != null && original == null) SupportingText("原图无法预览。")
                if (original != null && converted != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SupportingText("原图")
                        SupportingText("转换后")
                    }
                    Slider(
                        value = split,
                        onValueChange = { split = it },
                        modifier = Modifier.semantics { contentDescription = "原图与转换结果的分界位置" },
                    )
                }
                val before = input.bytes?.let { "$it 字节" } ?: "体积未知"
                SupportingText("原图 ${output.originalDimensions.width} × ${output.originalDimensions.height} · $before")
                SupportingText("输出 ${output.dimensions.width} × ${output.dimensions.height} · ${output.bytes} 字节")
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("分享这张") } },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

private data class ImagePreview(val bitmap: Bitmap?)

@Composable
private fun rememberPreview(key: Any, edge: Int, source: () -> ImageDecoder.Source): ImagePreview? {
    val image by produceState<ImagePreview?>(null, key, edge) {
        value = withContext(Dispatchers.IO) {
            val bitmap = try {
                decodeImage(source(), edge)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            ImagePreview(bitmap)
        }
    }
    return image
}

@Composable
private fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun readableBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes 字节"
    bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024))
}
