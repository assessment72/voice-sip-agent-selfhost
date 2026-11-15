package io.github.sparkcyf.voicesipagent.gateway

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Performs expensive local inference away from the SIP media clock.
 *
 * The supplied processor is where VAD/ASR/LLM/TTS native runtimes are connected. Input and output
 * queues are bounded; offer/poll never wait for inference. A processor may emit zero or more
 * 20 ms PCM frames for each caller frame.
 */
interface LocalSpeechProcessor : AutoCloseable {
    fun processCallerFrame(frame: ShortArray): List<ShortArray>
    override fun close()
}

class QueuedLocalSpeechEngine(
    private val processor: LocalSpeechProcessor,
    inputCapacity: Int = 50,
    outputCapacity: Int = 100,
    threadName: String = "local-ai-inference",
) : LocalSpeechEngine {
    private val input = ArrayBlockingQueue<ShortArray>(inputCapacity.also { require(it > 0) })
    private val output = ArrayBlockingQueue<ShortArray>(outputCapacity.also { require(it > 0) })
    private val running = AtomicBoolean(true)
    private val accepted = AtomicLong()
    private val rejected = AtomicLong()
    private val produced = AtomicLong()
    private val outputDropped = AtomicLong()

    private val worker = Thread({
        try {
            while (running.get() || input.isNotEmpty()) {
                try {
                    val frame = input.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val generated = processor.processCallerFrame(frame)
                    if (!running.get()) break
                    for (pcm in generated) {
                        if (!running.get()) break
                        if (pcm.isEmpty()) continue
                        val copy = pcm.copyOf()
                        if (!output.offer(copy)) {
                            output.poll()
                            if (output.offer(copy)) outputDropped.incrementAndGet()
                        }
                        produced.incrementAndGet()
                    }
                } catch (_: InterruptedException) {
                    if (!running.get()) break
                } catch (_: Throwable) {
                    // A bad inference frame must not terminate the SIP media clock or worker.
                    rejected.incrementAndGet()
                }
            }
        } finally {
            // Release native contexts on the inference thread, never concurrently with inference.
            runCatching { processor.close() }
        }
    }, threadName).apply { isDaemon = true; start() }

    override val isReady: Boolean
        get() = running.get() && worker.isAlive

    override fun offerCallerFrame(frame: ShortArray): Boolean {
        if (!isReady) return false
        val acceptedFrame = input.offer(frame.copyOf())
        if (acceptedFrame) accepted.incrementAndGet() else rejected.incrementAndGet()
        return acceptedFrame
    }

    override fun pollAssistantFrame(): ShortArray? = output.poll()?.copyOf()

    data class QueueStats(
        val acceptedInputFrames: Long,
        val rejectedInputFrames: Long,
        val producedOutputFrames: Long,
        val droppedOutputFrames: Long,
        val pendingInputFrames: Int,
        val pendingOutputFrames: Int,
    )

    fun queueStats() = QueueStats(
        accepted.get(), rejected.get(), produced.get(), outputDropped.get(), input.size, output.size,
    )

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        input.clear()
        output.clear()
        worker.interrupt()
        runCatching { worker.join(500) }
    }
}
