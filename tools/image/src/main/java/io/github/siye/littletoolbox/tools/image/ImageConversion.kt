package io.github.siye.littletoolbox.tools.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Size
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ImageExportProvider : FileProvider()

internal enum class ImageFormat(val label: String, val extension: String, val mimeType: String) {
    JPG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WebP", "webp", "image/webp");

    val supportsQuality: Boolean get() = this != PNG

    @Suppress("DEPRECATION")
    fun codec(): Bitmap.CompressFormat = when (this) {
        JPG -> Bitmap.CompressFormat.JPEG
        PNG -> Bitmap.CompressFormat.PNG
        WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }
}

internal const val MAX_LONG_EDGE = 4096
internal const val MAX_TARGET_KIB = 1_048_576

internal data class ImageOptions(
    val format: ImageFormat,
    val longEdge: Int,
    val quality: Int,
    val targetKib: Int?,
)

internal enum class ImagePreset(val label: String, val options: ImageOptions) {
    FORM("投表单", ImageOptions(ImageFormat.JPG, 1600, 88, 500)),
    CHAT("聊天发送", ImageOptions(ImageFormat.WEBP, 2048, 85, 1024)),
    ALPHA("保留透明", ImageOptions(ImageFormat.PNG, 2048, 90, null)),
}

internal data class ImageInput(val uri: Uri, val name: String, val bytes: Long?)

internal data class ImageOutput(
    val file: File,
    val format: ImageFormat,
    val bytes: Long,
    val dimensions: Size,
    val originalDimensions: Size,
)

internal data class ImageResult(
    val input: ImageInput,
    val output: ImageOutput? = null,
    val failure: String? = null,
)

internal fun exportCache(context: Context): File = File(context.cacheDir, "image-exports")

internal fun pruneImageExports(cache: File) {
    val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    cache.listFiles()?.filter { it.isDirectory && it.lastModified() < expiry }?.forEach { it.deleteRecursively() }
}

internal fun describeImage(resolver: ContentResolver, uri: Uri, fallbackIndex: Int): ImageInput {
    var name: String? = null
    var bytes: Long? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    bytes = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                }
            }
        }
    }
    if (bytes == null) {
        bytes = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }
            .getOrNull()?.takeIf { it >= 0 }
    }
    return ImageInput(uri, name?.takeIf(String::isNotBlank) ?: "图片 ${fallbackIndex + 1}", bytes)
}

private class ImageSizeLimitException(val edge: Int) : IOException()

internal suspend fun convertImage(
    resolver: ContentResolver,
    input: ImageInput,
    options: ImageOptions,
    outputDirectory: File,
): ImageResult {
    val stem = input.name.substringBeforeLast('.', input.name)
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim(' ', '.')
        .take(56)
        .ifBlank { "图片" }
    val outputFile = File(outputDirectory, "$stem-快转.${options.format.extension}")
    var bitmap: Bitmap? = null
    try {
        if (!outputDirectory.mkdirs()) throw IOException("无法创建缓存目录")
        var originalSize = Size(0, 0)
        var working = decodeImage(ImageDecoder.createSource(resolver, input.uri), options.longEdge, options.format) {
            originalSize = it
        }
        bitmap = working
        val trial = File(outputDirectory, "trial")
        val targetBytes = options.targetKib?.times(1024L)
        val minimumEdge = minOf(64, maxOf(working.width, working.height))
        while (true) {
            encode(working, options.format, options.quality, outputFile)
            if (targetBytes == null || outputFile.length() <= targetBytes) break

            var smallestSize = outputFile.length()
            if (options.format.supportsQuality) {
                encode(working, options.format, 0, trial)
                smallestSize = trial.length()
                if (smallestSize <= targetBytes) {
                    Files.move(trial.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    var low = 1
                    var high = options.quality - 1
                    while (low <= high) {
                        val quality = (low + high) / 2
                        encode(working, options.format, quality, trial)
                        if (trial.length() <= targetBytes) {
                            Files.move(trial.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            low = quality + 1
                        } else {
                            high = quality - 1
                        }
                    }
                    break
                }
            }

            val edge = maxOf(working.width, working.height)
            if (edge == minimumEdge) throw ImageSizeLimitException(edge)
            val shrink = sqrt(targetBytes.toDouble() / smallestSize).coerceIn(0.5, 0.85)
            val nextEdge = (edge * shrink).toInt().coerceIn(minimumEdge, edge - 1)
            val nextSize = fitLongEdge(Size(working.width, working.height), nextEdge)
            val resized = Bitmap.createScaledBitmap(working, nextSize.width, nextSize.height, true)
            working.recycle()
            working = resized
            bitmap = working
        }

        trial.delete()
        return ImageResult(
            input,
            ImageOutput(outputFile, options.format, outputFile.length(), Size(working.width, working.height), originalSize),
        )
    } catch (failure: CancellationException) {
        outputDirectory.deleteRecursively()
        throw failure
    } catch (failure: ImageSizeLimitException) {
        outputDirectory.deleteRecursively()
        return ImageResult(input, failure = "缩至长边 ${failure.edge} 像素后仍超过目标体积。")
    } catch (_: ImageDecoder.DecodeException) {
        outputDirectory.deleteRecursively()
        return ImageResult(input, failure = "无法解码图片，文件可能已损坏或格式不受支持。")
    } catch (_: SecurityException) {
        outputDirectory.deleteRecursively()
        return ImageResult(input, failure = "无法读取图片，请重新选择。")
    } catch (_: OutOfMemoryError) {
        outputDirectory.deleteRecursively()
        return ImageResult(input, failure = "图片过大，请调低长边上限后重试。")
    } catch (_: Exception) {
        outputDirectory.deleteRecursively()
        return ImageResult(input, failure = "读取或写入图片失败，请检查文件和可用存储空间。")
    } finally {
        bitmap?.recycle()
    }
}

private suspend fun encode(bitmap: Bitmap, format: ImageFormat, quality: Int, file: File) {
    currentCoroutineContext().ensureActive()
    file.outputStream().buffered().use { stream ->
        if (!bitmap.compress(format.codec(), quality, stream)) throw IOException("图片编码失败")
    }
}

internal fun decodeImage(
    source: ImageDecoder.Source,
    longEdge: Int,
    format: ImageFormat? = null,
    onSize: (Size) -> Unit = {},
): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    onSize(info.size)
    val size = fitLongEdge(info.size, longEdge)
    if (size != info.size) decoder.setTargetSize(size.width, size.height)
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    if (format == ImageFormat.JPG) {
        decoder.setPostProcessor { canvas ->
            canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
            PixelFormat.OPAQUE
        }
    }
}

private fun fitLongEdge(size: Size, limit: Int): Size {
    val edge = maxOf(size.width, size.height)
    if (edge <= limit) return size
    val scale = limit.toDouble() / edge
    return Size((size.width * scale).roundToInt().coerceAtLeast(1), (size.height * scale).roundToInt().coerceAtLeast(1))
}

internal suspend fun saveConvertedImages(
    resolver: ContentResolver,
    treeUri: Uri,
    outputs: List<ImageOutput>,
): Int {
    val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    var saved = 0
    for (output in outputs) {
        currentCoroutineContext().ensureActive()
        var destination: Uri? = null
        var complete = false
        try {
            destination = DocumentsContract.createDocument(resolver, root, output.format.mimeType, output.file.name)
                ?: continue
            val stream = resolver.openOutputStream(destination, "w") ?: continue
            stream.use { target -> output.file.inputStream().use { it.copyTo(target) } }
            complete = true
            saved++
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Continue saving other pictures, then report the number actually written.
        } finally {
            if (!complete && destination != null) runCatching { DocumentsContract.deleteDocument(resolver, destination) }
        }
    }
    return saved
}
