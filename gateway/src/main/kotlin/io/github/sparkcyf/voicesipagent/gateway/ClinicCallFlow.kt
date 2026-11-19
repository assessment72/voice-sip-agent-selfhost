package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import java.io.File
import java.io.RandomAccessFile

/** Side-effect-free IVR state machine; playback and dialing are wired by [CallBridge]. */
class ClinicIvrSession {
    enum class State { ANNOUNCEMENT, MENU, CONNECTING, COMPLETE }
    sealed interface Action {
        data class ConnectEmployee(val extension: String) : Action
        data class InvalidDigit(val digit: Char) : Action
    }

    var state: State = State.ANNOUNCEMENT
        private set

    fun announcementFinished() {
        if (state == State.ANNOUNCEMENT) state = State.MENU
    }

    /** Accept digits while the prompt is playing too, in case the caller presses early. */
    fun digitReceived(digit: Char): Action {
        if (state != State.ANNOUNCEMENT && state != State.MENU) return Action.InvalidDigit(digit)
        return if (digit == '1' || digit == '2') {
            state = State.CONNECTING
            Action.ConnectEmployee(digit.toString())
        } else Action.InvalidDigit(digit)
    }

    fun retry() {
        if (state != State.COMPLETE) state = State.ANNOUNCEMENT
    }

    fun complete() { state = State.COMPLETE }
}

/** Writes signed little-endian PCM16 frames to a standard mono WAV file. */
class PcmWavRecorder(file: File, private val sampleRate: Int = 8000) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false

    init {
        require(sampleRate > 0)
        output.setLength(0)
        output.write(ByteArray(44))
    }

    @Synchronized fun writeFrame(frame: ShortArray) {
        check(!closed) { "recorder is closed" }
        val bytes = ByteArray(frame.size * 2)
        frame.forEachIndexed { i, sample ->
            bytes[i * 2] = (sample.toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8 and 0xff).toByte()
        }
        output.write(bytes)
        dataBytes += bytes.size
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        output.seek(0)
        output.writeBytes("RIFF")
        writeLe32(36L + dataBytes)
        output.writeBytes("WAVEfmt ")
        writeLe32(16)
        writeLe16(1)
        writeLe16(1)
        writeLe32(sampleRate.toLong())
        writeLe32((sampleRate * 2).toLong())
        writeLe16(2)
        writeLe16(16)
        output.writeBytes("data")
        writeLe32(dataBytes)
        output.close()
    }

    private fun writeLe16(value: Int) {
        output.write(value and 0xff)
        output.write(value shr 8 and 0xff)
    }

    private fun writeLe32(value: Long) {
        output.write((value and 0xff).toInt())
        output.write((value shr 8 and 0xff).toInt())
        output.write((value shr 16 and 0xff).toInt())
        output.write((value shr 24 and 0xff).toInt())
    }
}

/** Decorates a media port and records one direction without changing the audio path. */
class RecordingMediaPort(
    private val delegate: MediaPort,
    private val recorder: PcmWavRecorder,
) : MediaPort {
    override val format: PcmFormat get() = delegate.format
    override val isClockSource: Boolean get() = delegate.isClockSource
    override fun setClockListener(listener: FrameClockListener?) = delegate.setClockListener(listener)
    override fun readFrame(): ShortArray = delegate.readFrame().also(recorder::writeFrame)
    override fun writeFrame(frame: ShortArray) {
        recorder.writeFrame(frame)
        delegate.writeFrame(frame)
    }
}
