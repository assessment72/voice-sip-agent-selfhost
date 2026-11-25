package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull

class HostedStateUpdateQueueTest {
    private data class Fixture(
        val queue: HostedStateUpdateQueue,
        val preferences: SharedPreferences,
    )

    private fun fixture(): Fixture {
        val context = Mockito.mock(Context::class.java)
        val preferences = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java)
        val values = mutableMapOf<String, String>()
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.getSharedPreferences("hosted_state_outbox", Context.MODE_PRIVATE))
            .thenReturn(preferences)
        Mockito.`when`(preferences.edit()).thenReturn(editor)
        doAnswer { invocation ->
            values[invocation.getArgument<String>(0)]
        }.`when`(preferences).getString(anyString(), isNull())
        Mockito.`when`(editor.putString(anyString(), anyString())).thenAnswer { invocation ->
            values[invocation.getArgument<String>(0)] = invocation.getArgument(1)
            editor
        }
        Mockito.`when`(editor.commit()).thenReturn(true)
        return Fixture(HostedStateUpdateQueue(context), preferences)
    }

    @Test fun preservesInsertionOrderAndAcknowledgesOnlyTheHead() {
        val f = fixture()
        assertTrue(f.queue.enqueue("call-1", "ringing_cellular", null))
        assertTrue(f.queue.enqueue("call-1", "cellular_answered", null))
        assertEquals("ringing_cellular", f.queue.peek()?.state)
        val firstId = f.queue.peek()!!.id

        assertTrue(f.queue.remove(firstId))
        assertEquals("cellular_answered", f.queue.peek()?.state)
        assertEquals(1, f.queue.size())
    }

    @Test fun deduplicatesIdenticalPendingTransitionsButKeepsDifferentReasons() {
        val f = fixture()
        assertTrue(f.queue.enqueue("call-1", "failed", "network"))
        val firstId = f.queue.peek()!!.id
        assertTrue(f.queue.enqueue("call-1", "failed", "network"))
        assertEquals(1, f.queue.size())
        assertEquals(firstId, f.queue.peek()?.id)

        assertTrue(f.queue.enqueue("call-1", "failed", "timeout"))
        assertEquals(2, f.queue.size())
    }

    @Test fun aNewQueueInstanceRecoversPersistedEntriesAfterProcessRecreation() {
        val f = fixture()
        assertTrue(f.queue.enqueue("call-9", "ringing_cellular", null))
        assertTrue(f.queue.enqueue("call-9", "cellular_answered", null))

        val recreatedContext = Mockito.mock(Context::class.java)
        Mockito.`when`(recreatedContext.applicationContext).thenReturn(recreatedContext)
        Mockito.`when`(recreatedContext.getSharedPreferences("hosted_state_outbox", Context.MODE_PRIVATE))
            .thenReturn(f.preferences)
        val recreated = HostedStateUpdateQueue(recreatedContext)

        assertEquals(2, recreated.size())
        assertEquals("ringing_cellular", recreated.peek()?.state)
    }

    @Test fun rejectsBlankIdentifiersAndTreatsUnknownAcknowledgementAsAlreadyRemoved() {
        val f = fixture()
        assertFalse(f.queue.enqueue("", "failed", null))
        assertFalse(f.queue.enqueue("call-1", " ", null))
        assertTrue(f.queue.remove("not-present"))
        assertNull(f.queue.peek())
        assertTrue(f.queue.isEmpty())
    }

    @Test fun enforcesTheBoundedOutboxWithoutDroppingOlderTransitions() {
        val f = fixture()
        repeat(500) { index ->
            assertTrue(f.queue.enqueue("call-$index", "ringing_cellular", null))
        }
        val firstId = f.queue.peek()!!.id
        assertEquals(500, f.queue.size())
        assertFalse(f.queue.enqueue("call-overflow", "ringing_cellular", null))
        assertEquals(500, f.queue.size())
        assertEquals(firstId, f.queue.peek()?.id)
    }

    @Test fun leavesCorruptStoredDataUntouchedAndRejectsNewUpdates() {
        val f = fixture()
        val corrupt = "not-json"
        f.preferences.edit().putString("pending_updates", corrupt).commit()
        assertNull(f.queue.peek())
        assertFalse(f.queue.enqueue("call-1", "failed", null))
        assertEquals(corrupt, f.preferences.getString("pending_updates", null))
        assertFalse(f.queue.isEmpty())
    }
}
