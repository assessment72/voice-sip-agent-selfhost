package io.github.sparkcyf.voicesipagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QueuedLocalSpeechEngineTest {
    @Test fun workerProcessesFramesAndReturnsCopiedOutput() {
        val latch = CountDownLatch(1)
        val engine = QueuedLocalSpeechEngine(processor {
            latch.countDown()
            listOf(it)
        })
        try {
            val frame = ShortArray(160) { 42 }
            assertTrue(engine.offerCallerFrame(frame))
            frame.fill(0)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            var result: ShortArray? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (result == null && System.nanoTime() < deadline) {
                result = engine.pollAssistantFrame()
                Thread.sleep(5)
            }
            assertNotNull(result)
            assertEquals(42, result!![0].toInt())
        } finally {
            engine.close()
        }
    }

    @Test fun outputFramesAreBoundedAndOldestAreDroppedOnOverflow() {
        val release = CountDownLatch(1)
        val engine = QueuedLocalSpeechEngine(
            processor = processor {
                release.await(2, TimeUnit.SECONDS)
                listOf(ShortArray(160) { 7 }, ShortArray(160) { 8 }, ShortArray(160) { 9 })
            },
            inputCapacity = 2,
            outputCapacity = 2,
        )
        try {
            assertTrue(engine.offerCallerFrame(ShortArray(160)))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (engine.queueStats().producedOutputFrames < 3 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(2, engine.queueStats().pendingOutputFrames)
            assertEquals(1, engine.queueStats().droppedOutputFrames)
        } finally {
            engine.close()
        }
    }

    @Test fun closeStopsAcceptingFramesAndClosesProcessor() {
        var processorClosed = false
        val delegate = processor { emptyList() }
        val wrapped = object : LocalSpeechProcessor {
            override fun processCallerFrame(frame: ShortArray) = delegate.processCallerFrame(frame)
            override fun close() {
                processorClosed = true
                delegate.close()
            }
        }
        val engine = QueuedLocalSpeechEngine(wrapped)
        engine.close()
        assertFalse(engine.isReady)
        assertFalse(engine.offerCallerFrame(ShortArray(160)))
        assertTrue(processorClosed)
    }

    private fun processor(block: (ShortArray) -> List<ShortArray>) = object : LocalSpeechProcessor {
        override fun processCallerFrame(frame: ShortArray) = block(frame)
        override fun close() {}
    }
}
