package io.github.siye.littletoolbox.tools.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class AudioOutputFormat(val extension: String, val mime: String) {
    M4A("m4a", "audio/mp4"),
    WAV("wav", "audio/wav"),
}

internal enum class WavChannels(val label: String) {
    ORIGINAL("原声道"),
    MONO("单声道"),
    STEREO("立体声"),
}

internal data class AudioTrack(
    val index: Int,
    val mime: String,
    val language: String?,
    val channels: Int?,
    val sampleRate: Int?,
    val durationUs: Long?,
) {
    val isAac: Boolean get() = mime == MediaFormat.MIMETYPE_AUDIO_AAC
}

internal data class AudioSource(val name: String, val tracks: List<AudioTrack>)

internal class AudioExtractionException(message: String) : IOException(message)

internal val Context.audioExportDirectory: File get() = File(cacheDir, "audio-exports")

internal fun readAudioSource(context: Context, uri: Uri): AudioSource {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: "视频"

    return withExtractor(context, uri) { extractor ->
        val tracks = (0 until extractor.trackCount).mapNotNull { index ->
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
            if (!mime.startsWith("audio/")) return@mapNotNull null
            AudioTrack(
                index = index,
                mime = mime,
                language = format.getString(MediaFormat.KEY_LANGUAGE)?.takeUnless { it == "und" },
                channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    null
                },
            )
        }
        AudioSource(name, tracks)
    }
}

internal suspend fun extractAudio(
    context: Context,
    uri: Uri,
    track: AudioTrack,
    outputFormat: AudioOutputFormat,
    wavChannels: WavChannels,
    destination: File,
    onProgress: (Int) -> Unit,
) = withExtractor(context, uri) { extractor ->
    val format = extractor.getTrackFormat(track.index)
    extractor.selectTrack(track.index)
    var lastPercent = 0
    var firstTimestamp = -1L
    val reportTimestamp: (Long) -> Unit = { timestamp ->
        val duration = track.durationUs
        if (timestamp >= 0L && duration != null && duration > 0L) {
            if (firstTimestamp < 0L) firstTimestamp = timestamp
            val percent = ((timestamp - firstTimestamp).toDouble() * 100 / duration)
                .toInt().coerceIn(0, 99)
            if (percent > lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
        }
    }
    when (outputFormat) {
        AudioOutputFormat.M4A -> remuxAac(extractor, format, destination, reportTimestamp)
        AudioOutputFormat.WAV -> decodeWav(extractor, format, destination, wavChannels, reportTimestamp)
    }
}

private inline fun <T> withExtractor(context: Context, uri: Uri, block: (MediaExtractor) -> T): T {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        return block(extractor)
    } finally {
        extractor.release()
    }
}

private suspend fun remuxAac(
    extractor: MediaExtractor,
    format: MediaFormat,
    destination: File,
    reportTimestamp: (Long) -> Unit,
) {
    if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) {
        throw AudioExtractionException("这条音轨不是 AAC，请改用 WAV。")
    }
    val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
        val outputTrack = muxer.addTrack(format)

        var wroteSamples = false
        var timestampBase = 0L
        var sample = ByteBuffer.allocateDirect(64 * 1024)
        val info = MediaCodec.BufferInfo()
        while (extractor.sampleTrackIndex >= 0) {
            currentCoroutineContext().ensureActive()
            val sampleFlags = extractor.sampleFlags
            checkClearSample(sampleFlags)
            if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                throw AudioExtractionException("这条 AAC 音轨无法直接封装，请改用 WAV。")
            }
            val sampleSize = extractor.sampleSize
            if (sampleSize > Int.MAX_VALUE) throw AudioExtractionException("音轨数据过大，无法读取。")
            if (sampleSize > sample.capacity()) sample = ByteBuffer.allocateDirect(sampleSize.toInt())
            sample.clear()
            val size = extractor.readSampleData(sample, 0)
            if (size < 0) break
            if (size > 0) {
                val timestamp = extractor.sampleTime
                if (!wroteSamples) {
                    timestampBase = timestamp
                    muxer.start()
                }
                val muxerFlags = if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, timestamp - timestampBase, muxerFlags)
                muxer.writeSampleData(outputTrack, sample, info)
                reportTimestamp(timestamp)
                wroteSamples = true
            }
            if (!extractor.advance()) break
        }
        if (!wroteSamples) throw AudioExtractionException("这条音轨没有可导出的音频数据。")
        currentCoroutineContext().ensureActive()
        muxer.stop()
    } finally {
        muxer.release()
    }
}

private suspend fun decodeWav(
    extractor: MediaExtractor,
    format: MediaFormat,
    destination: File,
    channelMode: WavChannels,
    reportTimestamp: (Long) -> Unit,
) {
    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        ?: throw AudioExtractionException("这台设备无法解码所选音轨。")
    val decoder = MediaCodec.createByCodecName(decoderName)
    try {
        decoder.configure(format, null, null, 0)
        decoder.start()
        RandomAccessFile(destination, "rw").use { output ->
            output.setLength(WAV_HEADER_BYTES.toLong())
            output.seek(WAV_HEADER_BYTES.toLong())

            var inputEnded = false
            var outputEnded = false
            var writer: WavWriter? = null
            val info = MediaCodec.BufferInfo()
            var lastProgress = SystemClock.elapsedRealtime()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_WAIT_US)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(decoder.getInputBuffer(inputIndex)).apply { clear() }
                        if (extractor.sampleSize > input.capacity()) {
                            throw AudioExtractionException("所选音轨的单个样本超出了设备解码缓冲区。")
                        }
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val sampleFlags = extractor.sampleFlags
                            checkClearSample(sampleFlags)
                            val inputFlags = if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                                MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                            } else {
                                0
                            }
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, inputFlags)
                            extractor.advance()
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_WAIT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val pcm = readPcmFormat(decoder.outputFormat)
                        val currentWriter = writer
                        if (currentWriter == null) {
                            writer = WavWriter(output, pcm, channelMode)
                        } else if (currentWriter.format != pcm) {
                            throw AudioExtractionException("音轨中途改变了采样率、声道数或采样格式，无法导出 WAV。")
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                    outputIndex >= 0 -> {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val pcm = checkNotNull(decoder.getOutputBuffer(outputIndex))
                                    .order(ByteOrder.nativeOrder())
                                pcm.limit(info.offset + info.size)
                                pcm.position(info.offset)
                                val currentWriter = writer ?: WavWriter(
                                    output, readPcmFormat(decoder.outputFormat), channelMode,
                                ).also { writer = it }
                                currentWriter.write(pcm)
                                reportTimestamp(info.presentationTimeUs)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                if (SystemClock.elapsedRealtime() - lastProgress > 20_000L) {
                    throw AudioExtractionException("音轨解码超时，设备无法处理这段音频。")
                }
            }
            currentCoroutineContext().ensureActive()
            val currentWriter = writer ?: throw AudioExtractionException("这条音轨没有可导出的音频数据。")
            currentWriter.finish()
        }
    } finally {
        decoder.release()
    }
}

private data class PcmFormat(val channels: Int, val sampleRate: Int, val encoding: Int) {
    val bytesPerSample: Int get() = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
}

private fun readPcmFormat(format: MediaFormat): PcmFormat {
    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val encoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
    if (channels < 1 || sampleRate < 1 ||
        (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
    ) {
        throw AudioExtractionException("设备输出了当前不支持的 PCM 格式。")
    }
    return PcmFormat(channels, sampleRate, encoding)
}

private class WavWriter(
    private val output: RandomAccessFile,
    val format: PcmFormat,
    channelMode: WavChannels,
) {
    private val outputChannels = when (channelMode) {
        WavChannels.ORIGINAL -> format.channels
        WavChannels.MONO -> 1
        WavChannels.STEREO -> if (format.channels <= 2) 2 else {
            throw AudioExtractionException("这条音轨超过两个声道，请选择原声道或单声道。")
        }
    }
    private val blockAlign = outputChannels * 2
    private var dataBytes = 0L
    private var bytes = ByteArray(0)

    fun write(pcm: ByteBuffer) {
        val inputBlockAlign = format.channels * format.bytesPerSample
        if (pcm.remaining() % inputBlockAlign != 0) {
            throw AudioExtractionException("音轨包含不完整的 PCM 数据。")
        }
        val frameCount = pcm.remaining() / inputBlockAlign
        val size = frameCount * blockAlign
        if (dataBytes + size > 0xffff_ffffL - WAV_HEADER_BYTES + 8) {
            throw AudioExtractionException("WAV 将超过 4 GiB，请改用 M4A 或缩短视频。")
        }
        if (bytes.size < size) bytes = ByteArray(size)
        when {
            format.channels == outputChannels && format.encoding == AudioFormat.ENCODING_PCM_16BIT &&
                pcm.order() == ByteOrder.LITTLE_ENDIAN -> pcm.get(bytes, 0, size)
            else -> {
                val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                repeat(frameCount) {
                    when {
                        format.channels == outputChannels -> repeat(outputChannels) {
                            samples.putShort(readSample(pcm))
                        }
                        outputChannels == 1 -> {
                            var total = 0
                            repeat(format.channels) { total += readSample(pcm).toInt() }
                            samples.putShort((total / format.channels).toShort())
                        }
                        else -> {
                            val mono = readSample(pcm)
                            samples.putShort(mono)
                            samples.putShort(mono)
                        }
                    }
                }
            }
        }
        output.write(bytes, 0, size)
        dataBytes += size
    }

    fun finish() {
        if (dataBytes == 0L) throw AudioExtractionException("这条音轨没有可导出的音频数据。")
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((dataBytes + WAV_HEADER_BYTES - 8).toInt())
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(outputChannels.toShort())
        header.putInt(format.sampleRate)
        header.putInt(format.sampleRate * blockAlign)
        header.putShort(blockAlign.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        output.seek(0)
        output.write(header.array())
    }

    private fun readSample(pcm: ByteBuffer): Short =
        if (format.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            (pcm.float * 32768).toInt().coerceIn(-32768, 32767).toShort()
        } else {
            pcm.short
        }
}

private fun checkClearSample(sampleFlags: Int) {
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
        throw AudioExtractionException("这条音轨受到加密保护，无法提取。")
    }
}

private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

private const val WAV_HEADER_BYTES = 44
private const val CODEC_WAIT_US = 10_000L
