package com.kuma.bluetoothdebugassistant

import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothStatus
import com.kuma.bluetoothdebugassistant.bluetooth.FakeBluetoothController
import com.kuma.bluetoothdebugassistant.bluetooth.SPP_UUID
import com.kuma.bluetoothdebugassistant.project.CommandEncoder
import com.kuma.bluetoothdebugassistant.project.CommandMode
import com.kuma.bluetoothdebugassistant.project.ComponentType
import com.kuma.bluetoothdebugassistant.project.EditorMode
import com.kuma.bluetoothdebugassistant.project.ProjectCommandSender
import com.kuma.bluetoothdebugassistant.project.ProjectComponent
import com.kuma.bluetoothdebugassistant.project.ProjectLayout
import com.kuma.bluetoothdebugassistant.project.ProjectLayoutJson
import com.kuma.bluetoothdebugassistant.project.SendThrottle
import com.kuma.bluetoothdebugassistant.project.movedTo
import com.kuma.bluetoothdebugassistant.project.resizedTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectLayoutTest {
    private val component = ProjectComponent(
        id = "one",
        type = ComponentType.BUTTON,
        x = 0f,
        y = 0f,
        width = 100f,
        height = 50f,
        name = "Test",
    )

    @Test
    fun `move resize constraints and grid snapping stay inside canvas`() {
        val moved = component.movedTo(357f, 639f, snapToGrid = true, gridSize = 10f)
        assertEquals(260f, moved.x)
        assertEquals(590f, moved.y)

        val resized = component.resizedTo(13f, 700f, snapToGrid = true, gridSize = 10f)
        assertEquals(40f, resized.width)
        assertEquals(640f, resized.height)

        val unsnapped = component.movedTo(13.5f, 19.5f, snapToGrid = false)
        assertEquals(13.5f, unsnapped.x)
        assertEquals(19.5f, unsnapped.y)
    }

    @Test
    fun `layout serialization round trips every component type`() {
        val layout = ProjectLayout(
            items = ComponentType.entries.mapIndexed { index, type ->
                component.copy(
                    id = "item-$index",
                    type = type,
                    name = type.label,
                    x = index * 10f,
                    y = index * 20f,
                    width = 120f,
                    height = 100f,
                    commandMode = if (index % 2 == 0) CommandMode.UTF8 else CommandMode.HEX,
                    commandTemplate = "{x},{y},{value}",
                    maxSendHz = index + 1,
                )
            },
        )

        assertEquals(layout, ProjectLayoutJson.decode(ProjectLayoutJson.encode(layout)))
    }

    @Test
    fun `UTF8 appends one configured newline`() {
        assertArrayEquals(
            "ping\n".encodeToByteArray(),
            CommandEncoder.encode(CommandMode.UTF8, "ping", appendNewline = true),
        )
        assertArrayEquals(
            "ping".encodeToByteArray(),
            CommandEncoder.encode(CommandMode.UTF8, "ping", appendNewline = false),
        )
    }

    @Test
    fun `HEX accepts spaced and prefixed formats and rejects invalid input`() {
        val expected = byteArrayOf(0xAA.toByte(), 0x55, 0x0D, 0x0A)
        assertArrayEquals(expected, CommandEncoder.parseHex("AA 55 0D 0A"))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x55), CommandEncoder.parseHex("0xAA 0x55"))
        assertFails<IllegalArgumentException> { CommandEncoder.parseHex("AA5") }
        assertFails<IllegalArgumentException> { CommandEncoder.parseHex("AA GG") }
    }

    @Test
    fun `throttle permits first send and enforces configured interval`() {
        var now = 1_000L
        val throttle = SendThrottle(maxHz = 10) { now }

        assertTrue(throttle.shouldSend())
        now += 99
        assertFalse(throttle.shouldSend())
        now += 1
        assertTrue(throttle.shouldSend())
    }

    @Test
    fun `edit mode sender guard never calls controller send`() = runTest {
        val fake = FakeBluetoothController(scope = backgroundScope)
        val address = FakeBluetoothController.sampleDevices.first().address
        fake.connect(address)
        val sender = ProjectCommandSender(fake)

        val sent = sender.send(
            mode = EditorMode.EDIT,
            status = BluetoothStatus.Connected(address, SPP_UUID),
            component = component.copy(command = "forbidden"),
        )
        advanceUntilIdle()

        assertFalse(sent)
        assertTrue(fake.sentPackets.value.isEmpty())
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
