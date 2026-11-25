package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Records a short AAC/M4A voice note for Telegram's sendVoice endpoint. */
class TelegramVoiceRecorder(context: Context) {
    private val app = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(): File {
        check(recorder == null) { "A voice recording is already active" }
        val directory = File(app.cacheDir, "telegram/outbox")
        check(directory.exists() || directory.mkdirs()) { "Could not create recording directory" }
        val file = File(directory, "voice_${System.currentTimeMillis()}.m4a")
        val active = MediaRecorder()
        try {
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioSamplingRate(16_000)
            active.setAudioEncodingBitRate(24_000)
            active.setOutputFile(file.absolutePath)
            active.prepare()
            active.start()
            recorder = active
            output = file
            return file
        } catch (failure: Throwable) {
            runCatching { active.release() }
            file.delete()
            throw failure
        }
    }

    fun stop(): File {
        val active = recorder ?: error("No voice recording is active")
        val file = output
        recorder = null
        try {
            active.stop()
        } catch (failure: Throwable) {
            file?.delete()
            output = null
            throw failure
        } finally {
            active.release()
        }
        output = null
        check(file != null && file.isFile && file.length() > 0L) { "The recording is empty" }
        return file
    }

    fun cancel() {
        val active = recorder
        recorder = null
        runCatching { active?.stop() }
        active?.release()
        output?.delete()
        output = null
    }
}
