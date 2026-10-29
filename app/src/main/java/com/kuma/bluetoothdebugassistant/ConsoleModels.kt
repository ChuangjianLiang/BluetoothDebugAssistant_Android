package com.kuma.bluetoothdebugassistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val MAX_CONSOLE_ENTRIES = 500

enum class ConsoleDirection {
    RX,
    TX,
}

enum class ConsoleDisplayMode {
    UTF8,
    HEX,
}

data class ConsoleEntry(
    val timestampMillis: Long,
    val direction: ConsoleDirection,
    val bytes: ByteArray,
) {
    fun display(mode: ConsoleDisplayMode): String = when (mode) {
        ConsoleDisplayMode.UTF8 -> bytes.decodeToString().escapeControls()
        ConsoleDisplayMode.HEX -> bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }
}

class ConsoleBuffer(
    private val capacity: Int = MAX_CONSOLE_ENTRIES,
) {
    private val entries = ArrayDeque<ConsoleEntry>()

    init {
        require(capacity > 0) { "控制台容量必须大于 0" }
    }

    @Synchronized
    fun append(entry: ConsoleEntry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<ConsoleEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

fun String.escapeControls(): String = buildString {
    this@escapeControls.forEach { character ->
        append(
            when (character) {
                '\r' -> "\\r"
                '\n' -> "\\n"
                '\t' -> "\\t"
                else -> if (character.isISOControl()) {
                    "\\u%04X".format(character.code)
                } else {
                    character
                }
            },
        )
    }
}

fun formatConsoleTime(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestampMillis))
