package com.kuma.bluetoothdebugassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleBufferTest {
    @Test
    fun `console formats UTF8 controls and HEX bytes`() {
        val entry = ConsoleEntry(
            timestampMillis = 0L,
            direction = ConsoleDirection.RX,
            bytes = byteArrayOf(0x41, 0x0A, 0x09),
        )

        assertEquals("A\\n\\t", entry.display(ConsoleDisplayMode.UTF8))
        assertEquals("41 0A 09", entry.display(ConsoleDisplayMode.HEX))
    }

    @Test
    fun `console retains only the latest 500 entries`() {
        val buffer = ConsoleBuffer()
        repeat(MAX_CONSOLE_ENTRIES + 5) { index ->
            buffer.append(
                ConsoleEntry(index.toLong(), ConsoleDirection.TX, byteArrayOf(index.toByte())),
            )
        }

        val snapshot = buffer.snapshot()
        assertEquals(MAX_CONSOLE_ENTRIES, snapshot.size)
        assertEquals(5L, snapshot.first().timestampMillis)
        assertEquals((MAX_CONSOLE_ENTRIES + 4).toLong(), snapshot.last().timestampMillis)
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
    }
}
