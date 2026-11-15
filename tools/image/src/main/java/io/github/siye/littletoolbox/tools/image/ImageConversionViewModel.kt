package io.github.siye.littletoolbox.tools.image

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ImageDraft(
    val format: ImageFormat,
    val longEdge: String,
    val quality: Int,
    val targetKib: String,
) {
    constructor(options: ImageOptions) : this(
        options.format,
        options.longEdge.toString(),
        options.quality,
        options.targetKib?.toString().orEmpty(),
    )

    val parsedEdge: Int? get() = longEdge.toIntOrNull()?.takeIf { it in 1..MAX_LONG_EDGE }
    val parsedTarget: Int? get() = targetKib.toIntOrNull()?.takeIf { it in 1..MAX_TARGET_KIB }
    val valid: Boolean get() = parsedEdge != null && (targetKib.isEmpty() || parsedTarget != null)

    fun toOptions(): ImageOptions? = if (valid) {
        parsedEdge?.let { ImageOptions(format, it, quality, parsedTarget) }
    } else {
        null
    }
}

internal data class ImageProgress(val completed: Int, val total: Int)

private data class ImageBatch(val directory: File, val results: List<ImageResult>, var shared: Boolean = false)

internal class ImageConversionViewModel(application: Application) : AndroidViewModel(application) {
    private val resolver = application.contentResolver
    private val cache = exportCache(application)
    private val preferences = application.getSharedPreferences("image_conversion", Context.MODE_PRIVATE)
    private var batch: ImageBatch? by mutableStateOf(null)
    private var conversion: Job? = null
    private var acceptedShare = emptyList<Uri>()

    var draft by mutableStateOf(ImageDraft(lastOptions()))
        private set
    var inputs by mutableStateOf(emptyList<ImageInput>())
        private set
    var progress by mutableStateOf<ImageProgress?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    val results: List<ImageResult> get() = batch?.results.orEmpty()
    val outputs: List<ImageOutput> get() = results.mapNotNull(ImageResult::output)
    val busy: Boolean get() = progress != null || saving

    init {
        viewModelScope.launch(Dispatchers.IO) { pruneImageExports(cache) }
    }

    fun acceptShare(images: List<Uri>) {
        if (images.isEmpty() || images == acceptedShare) return
        acceptedShare = images
        add(images)
    }

    fun add(images: List<Uri>) {
        if (busy) return
        val existing = inputs.mapTo(mutableSetOf(), ImageInput::uri)
        val pending = images.distinct().filterNot(existing::contains)
        if (pending.isEmpty()) return
        val firstIndex = inputs.size
        inputs = inputs + pending.mapIndexed { index, uri -> ImageInput(uri, "图片 ${firstIndex + index + 1}", null) }
        discardBatch()
        message = null
        viewModelScope.launch {
            val details = withContext(Dispatchers.IO) {
                pending.mapIndexed { index, uri ->
                    currentCoroutineContext().ensureActive()
                    describeImage(resolver, uri, firstIndex + index)
                }.associateBy(ImageInput::uri)
            }
            inputs = inputs.map { details[it.uri] ?: it }
        }
    }

    fun remove(uri: Uri) {
        if (busy) return
        inputs = inputs.filterNot { it.uri == uri }
        discardBatch()
        message = null
    }

    fun clear() {
        if (busy) return
        inputs = emptyList()
        discardBatch()
        message = null
    }

    fun change(next: ImageDraft) {
        if (busy || draft == next) return
        draft = next
        discardBatch()
        message = null
    }

    fun convert() {
        if (busy || inputs.isEmpty()) return
        val options = draft.toOptions() ?: return
        val selected = inputs
        rememberOptions(options)
        discardBatch()
        val directory = File(cache, UUID.randomUUID().toString())
        progress = ImageProgress(0, selected.size)
        message = null
        conversion = viewModelScope.launch {
            var published = false
            try {
                val completed = withContext(Dispatchers.IO) {
                    selected.mapIndexed { index, input ->
                        currentCoroutineContext().ensureActive()
                        val source = if (input.bytes == null) describeImage(resolver, input.uri, index) else input
                        val result = convertImage(resolver, source, options, File(directory, index.toString()))
                        withContext(Dispatchers.Main.immediate) {
                            progress = ImageProgress(index + 1, selected.size)
                        }
                        result
                    }
                }
                batch = ImageBatch(directory, completed)
                published = true
            } catch (failure: CancellationException) {
                message = "已取消转换。"
                throw failure
            } finally {
                if (!published) withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
                progress = null
            }
        }
    }

    fun cancelConversion() {
        conversion?.cancel()
    }

    fun save(tree: Uri) {
        val selected = outputs
        if (busy || selected.isEmpty()) return
        saving = true
        message = null
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { saveConvertedImages(resolver, tree, selected) }
                message = if (count == selected.size) "已保存 $count 张图片。"
                else "已保存 $count 张，${selected.size - count} 张写入失败，请检查文件夹权限或存储空间。"
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                message = "无法写入该文件夹，请重新选择。"
            } finally {
                saving = false
            }
        }
    }

    fun share(context: Context, selected: List<ImageOutput>) {
        if (busy || selected.isEmpty()) return
        try {
            val uris = selected.map {
                FileProvider.getUriForFile(context, "${context.packageName}.image-export", it.file)
            }
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = selected.map { it.format.mimeType }.distinct().singleOrNull() ?: "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = ClipData.newUri(context.contentResolver, "图片快转", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
            context.startActivity(Intent.createChooser(intent, "分享图片"))
            batch?.apply {
                shared = true
                directory.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
            message = "图片暂时无法分享，请重新转换后重试。"
        }
    }

    private fun discardBatch() {
        val old = batch
        batch = null
        if (old != null && !old.shared) viewModelScope.launch(Dispatchers.IO) { old.directory.deleteRecursively() }
    }

    private fun lastOptions(): ImageOptions {
        val default = ImagePreset.FORM.options
        val name = preferences.getString("format", default.format.name)
        val format = ImageFormat.entries.firstOrNull { it.name == name } ?: default.format
        return ImageOptions(
            format,
            preferences.getInt("long_edge", default.longEdge).coerceIn(1, MAX_LONG_EDGE),
            preferences.getInt("quality", default.quality).coerceIn(1, 100),
            preferences.getInt("target_kib", default.targetKib ?: 0).takeIf { it in 1..MAX_TARGET_KIB },
        )
    }

    private fun rememberOptions(options: ImageOptions) {
        preferences.edit()
            .putString("format", options.format.name)
            .putInt("long_edge", options.longEdge)
            .putInt("quality", options.quality)
            .putInt("target_kib", options.targetKib ?: 0)
            .apply()
    }

    override fun onCleared() {
        batch?.takeUnless(ImageBatch::shared)?.directory?.deleteRecursively()
        super.onCleared()
    }
}
