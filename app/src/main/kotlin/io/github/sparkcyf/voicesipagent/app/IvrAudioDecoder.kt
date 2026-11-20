package io.github.sparkcyf.voicesipagent.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.math.floor

/** Decodes bounded uncompressed PCM16 WAV input and normalizes it to the call path (8 kHz mono). */
internal object IvrAudioDecoder {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val TARGET_RATE = 8000

    fun decodeWav(file: File): ShortArray? {
        if (!file.isFile || file.length() !in 44..MAX_BYTES.toLong()) return null
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val bytes = ByteArray(file.length().toInt())
                input.readFully(bytes)
                require(bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WAVE")
                var offset = 12
                var channels = 0
                var sampleRate = 0
                var bits = 0
                var pcm = false
                var dataOffset = -1
                var dataSize = 0
                while (offset + 8 <= bytes.size) {
                    val id = bytes.ascii(offset, 4)
                    val size = bytes.u32(offset + 4)
                    val content = offset + 8
                    require(size <= bytes.size - content)
                    when (id) {
                        "fmt " -> if (size >= 16) {
                            pcm = bytes.u16(content) == 1
                            channels = bytes.u16(content + 2)
                            sampleRate = bytes.u32(content + 4)
                            bits = bytes.u16(content + 14)
                        }
                        "data" -> { dataOffset = content; dataSize = size }
                    }
                    offset = content + size + (size and 1)
                }
                require(pcm && channels in 1..2 && sampleRate in 8000..48000 && bits == 16)
                require(dataOffset >= 0 && dataSize >= channels * 2 && dataSize % (channels * 2) == 0)
                val frames = dataSize / (channels * 2)
                require(frames <= sampleRate * 180) { "IVR WAV duration exceeds 180 seconds" }
                val mono = ShortArray(frames) { frame ->
                    var sum = 0
                    repeat(channels) { c ->
                        val p = dataOffset + (frame * channels + c) * 2
                        sum += bytes.s16(p)
                    }
                    (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                resample(mono, sampleRate)
            }
        }.getOrNull()
    }

    /** Decode supported OGG audio on-device and normalize decoder PCM into the 8 kHz call format. */
    fun decodeOgg(file: File): ShortArray? {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
        return runCatching {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("OGG has no audio track")
                val source = extractor.getTrackFormat(track)
                val mime = source.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")
                extractor.selectTrack(track)
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(source, null, null, 0)
                codec.start()
                var inputDone = false
                var outputDone = false
                var sampleRate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                val output = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index) ?: error("Missing decoder input")
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (index >= 0) {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(index) ?: error("Missing decoder output")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                buffer.get(chunk)
                                require(output.size().toLong() + chunk.size <= MAX_BYTES.toLong())
                                output.write(chunk)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
                require(sampleRate in 8000..48000 && channels in 1..2)
                val bytes = output.toByteArray()
                val decoded = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    require(bytes.size % 4 == 0)
                    val floats = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    ShortArray(floats.remaining()) { (floats.get() * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                } else {
                    require(bytes.size % 2 == 0)
                    ShortArray(bytes.size / 2) { i ->
                        ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                    }
                }
                val frames = decoded.size / channels
                require(frames in 1..sampleRate * 180)
                val mono = ShortArray(frames) { frame ->
                    var sum = 0
                    repeat(channels) { c -> sum += decoded[frame * channels + c].toInt() }
                    (sum / channels).toShort()
                }
                resample(mono, sampleRate)
            } finally {
                runCatching { codec?.stop() }
                codec?.release()
                extractor.release()
            }
        }.getOrNull()
    }

    private fun resample(input: ShortArray, sourceRate: Int): ShortArray {
        if (sourceRate == TARGET_RATE) return input
        val outSize = ((input.size.toLong() * TARGET_RATE) / sourceRate).toInt().coerceAtLeast(1)
        return ShortArray(outSize) { i ->
            val sourcePosition = i.toDouble() * sourceRate / TARGET_RATE
            val left = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = sourcePosition - left
            (input[left] * (1.0 - fraction) + input[right] * fraction).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun ByteArray.ascii(offset: Int, length: Int) = copyOfRange(offset, offset + length).decodeToString()
    private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(offset: Int) = u16(offset) or (u16(offset + 2) shl 16)
    private fun ByteArray.s16(offset: Int) = u16(offset).toShort().toInt()
}
