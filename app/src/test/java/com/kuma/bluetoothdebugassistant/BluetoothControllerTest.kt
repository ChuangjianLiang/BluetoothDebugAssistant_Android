package com.kuma.bluetoothdebugassistant

import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothDeviceModel
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothConnectionMode
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothStatus
import com.kuma.bluetoothdebugassistant.bluetooth.BleGattCharacteristicModel
import com.kuma.bluetoothdebugassistant.bluetooth.BleGattServiceModel
import com.kuma.bluetoothdebugassistant.bluetooth.BondStatus
import com.kuma.bluetoothdebugassistant.bluetooth.DeviceConnectionStatus
import com.kuma.bluetoothdebugassistant.bluetooth.FakeBluetoothController
import com.kuma.bluetoothdebugassistant.bluetooth.NUS_NOTIFY_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.NUS_SERVICE_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.NUS_WRITE_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.SPP_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.filterDevices
import com.kuma.bluetoothdebugassistant.bluetooth.hexToByteArray
import com.kuma.bluetoothdebugassistant.bluetooth.rfcommConnectionCandidates
import com.kuma.bluetoothdebugassistant.bluetooth.selectBleGattProfile
import com.kuma.bluetoothdebugassistant.bluetooth.toHexString
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothControllerTest {
    @Test
    fun `filter matches name and mac case insensitively`() {
        val devices = listOf(
            BluetoothDeviceModel("Serial Device", "AA:BB:CC:00:00:01"),
            BluetoothDeviceModel("Other", "11:22:33:44:55:66"),
        )

        assertEquals(listOf(devices[0]), filterDevices(devices, "sErIaL"))
        assertEquals(listOf(devices[1]), filterDevices(devices, "44:55"))
        assertEquals(devices, filterDevices(devices, "  "))
    }

    @Test
    fun `hex helpers round trip unsigned bytes`() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())

        val text = bytes.toHexString()

        assertEquals("00 7F 80 FF", text)
        assertArrayEquals(bytes, text.hexToByteArray())
    }

    @Test
    fun `discovered UUIDs are tried before the SPP compatibility fallback`() {
        val custom = UUID.fromString("12345678-1234-5678-1234-567812345678")

        assertEquals(listOf(custom, SPP_UUID), rfcommConnectionCandidates(listOf(custom)))
        assertEquals(listOf(SPP_UUID), rfcommConnectionCandidates(listOf(SPP_UUID)))
    }

    @Test
    fun `fake SDP discovery returns advertised service UUIDs`() = runTest {
        val fake = FakeBluetoothController(scope = backgroundScope)
        val device = FakeBluetoothController.sampleDevices.first()

        assertEquals(device.serviceUuids, fake.discoverServiceUuids(device.address))
    }

    @Test
    fun `Nordic UART GATT profile is selected by its known characteristics`() {
        val profile = selectBleGattProfile(FakeBluetoothController.nordicUartServices)

        assertEquals(NUS_SERVICE_UUID, profile?.serviceUuid)
        assertEquals(NUS_WRITE_UUID, profile?.writeCharacteristicUuid)
        assertEquals(NUS_NOTIFY_UUID, profile?.notifyCharacteristicUuid)
        assertTrue(profile?.writeWithoutResponse == true)
    }

    @Test
    fun `generic GATT profile uses writable and notifiable characteristics`() {
        val serviceUuid = UUID.fromString("12345678-1234-5678-1234-567812345678")
        val writeUuid = UUID.fromString("12345678-1234-5678-1234-567812345679")
        val notifyUuid = UUID.fromString("12345678-1234-5678-1234-567812345680")
        val profile = selectBleGattProfile(
            listOf(
                BleGattServiceModel(
                    serviceUuid,
                    listOf(
                        BleGattCharacteristicModel(writeUuid, canWrite = true),
                        BleGattCharacteristicModel(notifyUuid, canNotify = true),
                    ),
                ),
            ),
        )

        assertEquals(writeUuid, profile?.writeCharacteristicUuid)
        assertEquals(notifyUuid, profile?.notifyCharacteristicUuid)
    }

    @Test
    fun `GATT profile requires a writable characteristic`() {
        val services = listOf(
            BleGattServiceModel(
                NUS_SERVICE_UUID,
                listOf(BleGattCharacteristicModel(NUS_NOTIFY_UUID, canNotify = true)),
            ),
        )

        assertEquals(null, selectBleGattProfile(services))
    }

    @Test
    fun `fake BLE connection reports GATT mode`() = runTest {
        val fake = FakeBluetoothController(scope = backgroundScope)
        val device = FakeBluetoothController.sampleDevices.first {
            it.gattServices.isNotEmpty()
        }

        fake.connectGatt(device.address)

        val connected = fake.status.value as BluetoothStatus.Connected
        assertEquals(BluetoothConnectionMode.BLE_GATT, connected.mode)
        assertEquals(NUS_WRITE_UUID, connected.uuid)
    }

    @Test
    fun `fake drives pair connect and disconnect states`() = runTest {
        val fake = FakeBluetoothController(scope = backgroundScope)
        val address = FakeBluetoothController.sampleDevices[1].address

        fake.startScan()
        assertEquals(BluetoothStatus.Scanning, fake.status.value)
        fake.stopScan()
        fake.pair(address)
        assertEquals(BondStatus.BONDED, fake.devices.value.first { it.address == address }.bondStatus)
        fake.connect(address, SPP_UUID)
        assertTrue(fake.status.value is BluetoothStatus.Connected)
        assertEquals(
            DeviceConnectionStatus.CONNECTED,
            fake.devices.value.first { it.address == address }.connectionStatus,
        )
        fake.disconnect()
        assertEquals(BluetoothStatus.Idle, fake.status.value)
    }

    @Test
    fun `fake serializes all sends through one queue`() = runTest {
        val fake = FakeBluetoothController(scope = backgroundScope)
        val address = FakeBluetoothController.sampleDevices.first().address
        fake.connect(address)

        launch { fake.send(byteArrayOf(1)) }
        launch { fake.send(byteArrayOf(2)) }
        launch { fake.send(byteArrayOf(3)) }
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), fake.sentPackets.value.map { it.single().toInt() })
    }
}
