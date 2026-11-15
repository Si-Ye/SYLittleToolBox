package io.github.siye.littletoolbox.tools.audio

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.siye.littletoolbox.core.tool.ToolCategory
import io.github.siye.littletoolbox.core.tool.ToolInfo
import io.github.siye.littletoolbox.core.tool.UriInputTool
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AudioTool : UriInputTool {
    override val info = ToolInfo(
        id = "audio",
        name = "视频取声",
        category = ToolCategory.MEDIA,
        description = "从视频选取音轨，在本机导出 M4A 或 WAV。",
        keywords = listOf("提取音频", "音轨", "音频转换", "M4A", "WAV", "AAC", "PCM"),
        help = listOf(
            "从文件中选择一个视频，或在其他应用中通过系统分享将一个视频交给视频取声。",
            "M4A 可直接封装 AAC 音轨，保留原有编码和声道。WAV 将音轨解码成 16 位 PCM，保留采样率，可选择原声道、单声道或双声道。",
            "提取完成后，可另存为系统文件，或直接分享临时音频文件。",
        ),
    )

    @Composable
    override fun Content(initialText: String?) {
        AudioScreen(initialUri = null)
    }

    @Composable
    override fun Content(uris: List<Uri>) {
        AudioScreen(initialUri = uris.firstOrNull(), receivedMultiple = uris.size > 1)
    }
}

@Composable
private fun AudioScreen(initialUri: Uri?, receivedMultiple: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by rememberSaveable(initialUri) { mutableStateOf(initialUri) }
    var source by remember(uri) { mutableStateOf<AudioSource?>(null) }
    var selectedTrackIndex by rememberSaveable(uri) { mutableStateOf<Int?>(null) }
    var selectedFormat by rememberSaveable(uri) { mutableStateOf<AudioOutputFormat?>(null) }
    var wavChannels by rememberSaveable(uri) { mutableStateOf(WavChannels.ORIGINAL) }
    var outputPath by rememberSaveable(uri) { mutableStateOf<String?>(null) }
    var issue by remember(uri) { mutableStateOf<String?>(null) }
    var status by remember(uri) { mutableStateOf<String?>(null) }
    var export by remember { mutableStateOf<Job?>(null) }
    var savedOutputPending by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Int?>(null) }
    val busy = export != null || saving || savedOutputPending
    val selectedTrack = source?.tracks?.firstOrNull { it.index == selectedTrackIndex }
    val result = outputPath?.let { File(context.audioExportDirectory, it) }

    fun clearResult() {
        outputPath = null
        status = null
        issue = null
    }

    LaunchedEffect(uri) {
        val selectedUri = uri ?: return@LaunchedEffect
        try {
            val scanned = withContext(Dispatchers.IO) { readAudioSource(context, selectedUri) }
            source = scanned
            val track = scanned.tracks.firstOrNull { it.index == selectedTrackIndex } ?: scanned.tracks.firstOrNull()
            selectedTrackIndex = track?.index
            if (track != null && (selectedFormat == null || selectedFormat == AudioOutputFormat.M4A && !track.isAac)) {
                selectedFormat = if (track.isAac) AudioOutputFormat.M4A else AudioOutputFormat.WAV
            }
            if (track == null) issue = "这个视频没有可提取的音轨。"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            issue = error.userMessage("无法读取视频，请检查文件是否完整、格式是否受设备支持。")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selectedUri ->
        if (selectedUri != null) uri = selectedUri
    }

    val onSave: (Uri?) -> Unit = { destination ->
        savedOutputPending = false
        val currentResult = result
        if (destination != null) {
            saving = true
            issue = null
            status = null
            scope.launch {
                var copied = false
                try {
                    withContext(Dispatchers.IO) {
                        if (currentResult == null || !currentResult.isFile) {
                            throw AudioExtractionException("临时音频已清理，请重新提取。")
                        }
                        copyAudio(context, currentResult, destination)
                        copied = true
                    }
                    status = "已保存"
                } catch (error: Exception) {
                    if (!copied) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                        }
                    }
                    if (error is CancellationException) throw error
                    issue = error.userMessage("无法保存到所选位置，可以重试或选择其他位置。")
                } finally {
                    saving = false
                }
            }
        }
    }
    val saveM4a = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AudioOutputFormat.M4A.mime), onSave,
    )
    val saveWav = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AudioOutputFormat.WAV.mime), onSave,
    )

    val startExport: () -> Unit = {
        val input = uri
        val video = source
        val track = selectedTrack
        val format = selectedFormat
        val channels = wavChannels
        if (input != null && video != null && track != null && format != null) {
            clearResult()
            progress = if ((track.durationUs ?: 0L) > 0L) 0 else null
            export = scope.launch {
                val folder = UUID.randomUUID().toString()
                val fileName = outputName(video.name, format)
                val relativePath = "$folder/$fileName"
                val file = File(context.audioExportDirectory, relativePath)
                var completed = false
                try {
                    withContext(Dispatchers.IO) {
                        removeOldExports(context.audioExportDirectory)
                        if (file.parentFile?.mkdirs() != true) {
                            throw AudioExtractionException("无法创建临时文件，请检查设备可用空间。")
                        }
                        extractAudio(context, input, track, format, channels, file) { percent ->
                            progress = percent
                        }
                    }
                    outputPath = relativePath
                    completed = true
                } catch (cancelled: CancellationException) {
                    status = "已取消提取"
                    throw cancelled
                } catch (error: Exception) {
                    issue = error.userMessage("无法提取此音轨，请检查视频或尝试其他格式。")
                } finally {
                    if (!completed) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            file.delete()
                            file.parentFile?.delete()
                        }
                    }
                    export = null
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source?.name ?: "视频文件", style = MaterialTheme.typography.titleMedium)
                if (uri == null) {
                    Text("选择视频，或从其他应用分享视频。", style = MaterialTheme.typography.bodyMedium)
                } else if (source == null && issue == null) {
                    Text("正在读取音轨…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (receivedMultiple && uri == initialUri) {
                    Text("每次处理一个视频。当前已载入第一个视频。", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("video/*")) },
                    enabled = !busy,
                ) {
                    Text(if (uri == null) "选择视频" else "更换视频")
                }
            }
        }

        val tracks = source?.tracks.orEmpty()
        if (tracks.isNotEmpty()) {
            AudioOptionGroup("音轨") {
                for ((position, track) in tracks.withIndex()) {
                    AudioOption(
                        label = "音轨 " + (position + 1),
                        detail = track.details(),
                        selected = track.index == selectedTrackIndex,
                        enabled = !busy,
                        onClick = {
                            selectedTrackIndex = track.index
                            if (!track.isAac && selectedFormat == AudioOutputFormat.M4A) {
                                selectedFormat = AudioOutputFormat.WAV
                            }
                            if ((track.channels ?: 2) > 2 && wavChannels == WavChannels.STEREO) {
                                wavChannels = WavChannels.ORIGINAL
                            }
                            clearResult()
                        },
                    )
                }
            }
            AudioOptionGroup("导出格式") {
                AudioOption(
                    label = "M4A",
                    detail = if (selectedTrack?.isAac == true) "保留 AAC 编码，文件通常较小" else "仅适用于 AAC 音轨",
                    selected = selectedFormat == AudioOutputFormat.M4A,
                    enabled = !busy && selectedTrack?.isAac == true,
                    onClick = {
                        selectedFormat = AudioOutputFormat.M4A
                        clearResult()
                    },
                )
                AudioOption(
                    label = "WAV",
                    detail = "16 位 PCM，保留原采样率",
                    selected = selectedFormat == AudioOutputFormat.WAV,
                    enabled = !busy,
                    onClick = {
                        selectedFormat = AudioOutputFormat.WAV
                        clearResult()
                    },
                )
            }
            if (selectedFormat == AudioOutputFormat.WAV) {
                AudioOptionGroup("声道") {
                    for (mode in WavChannels.entries) {
                        val supported = mode != WavChannels.STEREO || (selectedTrack?.channels ?: 2) <= 2
                        AudioOption(
                            label = mode.label,
                            selected = wavChannels == mode,
                            enabled = !busy && supported,
                            onClick = {
                                wavChannels = mode
                                clearResult()
                            },
                        )
                    }
                }
            }
            if (export != null) {
                val percent = progress
                Text(if (percent == null) "正在提取音频…" else "正在提取音频 $percent%")
                if (percent == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = { export?.cancel() }) { Text("取消提取") }
            } else {
                Button(
                    onClick = startExport,
                    enabled = !busy && selectedTrack != null && selectedFormat != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("提取音频")
                }
            }
        }

        val resultFormat = selectedFormat
        if (result != null && resultFormat != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        Formatter.formatShortFileSize(context, result.length()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                try {
                                    if (!result.isFile) throw AudioExtractionException("临时音频已清理，请重新提取。")
                                    savedOutputPending = true
                                    when (resultFormat) {
                                        AudioOutputFormat.M4A -> saveM4a.launch(result.name)
                                        AudioOutputFormat.WAV -> saveWav.launch(result.name)
                                    }
                                } catch (error: Exception) {
                                    savedOutputPending = false
                                    issue = error.userMessage("无法打开系统保存窗口。")
                                }
                            },
                            enabled = !busy,
                        ) { Text("另存为") }
                        TextButton(
                            onClick = {
                                try {
                                    shareAudio(context, result, resultFormat)
                                    issue = null
                                } catch (error: Exception) {
                                    issue = error.userMessage("无法打开系统分享窗口。")
                                }
                            },
                            enabled = !busy,
                        ) { Text("分享") }
                    }
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        issue?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        status?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun AudioOptionGroup(title: String, options: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Text(title, Modifier.padding(start = 16.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup().padding(vertical = 8.dp)) { options() }
    }
}

@Composable
private fun AudioOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    detail: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton) {
            if (!selected) onClick()
        }.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val colors = MaterialTheme.colorScheme
            Text(label, color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
            if (detail != null) {
                Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun AudioTrack.details(): String = listOfNotNull(
    if (isAac) "AAC" else mime.substringAfter('/').uppercase(Locale.ROOT),
    channels?.let { "$it 声道" },
    sampleRate?.let { "$it Hz" },
    durationUs?.takeIf { it > 0 }?.let {
        val seconds = it / 1_000_000L
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    },
    language,
).joinToString(" · ")

private fun outputName(inputName: String, format: AudioOutputFormat): String {
    val base = inputName.substringBeforeLast('.')
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .take(48)
        .trim(' ', '.')
        .ifBlank { "视频取声" }
    return "$base." + format.extension
}

private fun removeOldExports(directory: File) {
    val cutoff = System.currentTimeMillis() - 86_400_000L
    directory.listFiles()?.forEach { folder ->
        if (folder.lastModified() < cutoff) {
            folder.listFiles()?.forEach(File::delete)
            folder.delete()
        }
    }
}

private suspend fun copyAudio(context: Context, result: File, destination: Uri) {
    val output = context.contentResolver.openOutputStream(destination, "wt")
        ?: throw AudioExtractionException("保存位置无法写入。")
    output.use { target ->
        result.inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                target.write(buffer, 0, read)
            }
        }
    }
}

private fun shareAudio(context: Context, result: File, format: AudioOutputFormat) {
    if (!result.isFile) throw AudioExtractionException("临时音频已清理，请重新提取。")
    val uri = FileProvider.getUriForFile(context, context.packageName + ".audio-exports", result)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = format.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, result.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "分享音频"))
}

private fun Exception.userMessage(fallback: String): String =
    if (this is AudioExtractionException) message ?: fallback else fallback
