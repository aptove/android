package com.acp.chat.domain.acp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ACP data models — ACPConnectionState and ACPMessage.
 */
class ACPModelsTest {

    // ----- ACPConnectionState -----

    @Test
    fun `Disconnected is a singleton`() {
        assertSame(ACPConnectionState.Disconnected, ACPConnectionState.Disconnected)
    }

    @Test
    fun `Connecting is a singleton`() {
        assertSame(ACPConnectionState.Connecting, ACPConnectionState.Connecting)
    }

    @Test
    fun `Reconnecting is a singleton`() {
        assertSame(ACPConnectionState.Reconnecting, ACPConnectionState.Reconnecting)
    }

    @Test
    fun `Error state carries message`() {
        val state = ACPConnectionState.Error("connection lost")
        assertEquals("connection lost", state.message)
        assertNull(state.cause)
    }

    @Test
    fun `Error state carries cause`() {
        val exception = RuntimeException("timeout")
        val state = ACPConnectionState.Error("failed", exception)
        assertEquals("failed", state.message)
        assertSame(exception, state.cause)
    }

    // ----- ACPMessage -----

    @Test
    fun `TextChunk carries text and isComplete flag`() {
        val chunk = ACPMessage.TextChunk("Hello", isComplete = false)
        assertEquals("Hello", chunk.text)
        assertFalse(chunk.isComplete)

        val complete = ACPMessage.TextChunk("Done", isComplete = true)
        assertTrue(complete.isComplete)
    }

    @Test
    fun `TextChunk isComplete defaults to false`() {
        val chunk = ACPMessage.TextChunk("test")
        assertFalse(chunk.isComplete)
    }

    @Test
    fun `Error message carries text`() {
        val error = ACPMessage.Error("something broke")
        assertEquals("something broke", error.message)
    }

    @Test
    fun `Complete is a singleton`() {
        assertSame(ACPMessage.Complete, ACPMessage.Complete)
    }

    @Test
    fun `ACPMessage sealed class when expression is exhaustive`() {
        val messages: List<ACPMessage> = listOf(
            ACPMessage.TextChunk("hi"),
            ACPMessage.Error("fail"),
            ACPMessage.Complete
        )

        messages.forEach { msg ->
            when (msg) {
                is ACPMessage.TextChunk -> assertNotNull(msg.text)
                is ACPMessage.Error -> assertNotNull(msg.message)
                is ACPMessage.Complete -> { /* singleton */ }
            }
        }
    }
}
