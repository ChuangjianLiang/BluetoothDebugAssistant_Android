package com.kuma.bluetoothdebugassistant.bluetooth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class FakeBluetoothController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    initialDevices: List<BluetoothDeviceModel> = sampleDevices,
    private val autoDemo: Boolean = false,
) : BluetoothController {
    private val _devices = MutableStateFlow(initialDevices)
    override val devices: StateFlow<List<BluetoothDeviceModel>> = _devices.asStateFlow()

    private val _status = MutableStateFlow<BluetoothStatus>(BluetoothStatus.Idle)
    override val status: StateFlow<BluetoothStatus> = _status.asStateFlow()

    private val _receivedBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val receivedBytes: SharedFlow<ByteArray> = _receivedBytes.asSharedFlow()

    private val sendQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val _sentPackets = MutableStateFlow<List<ByteArray>>(emptyList())
    val sentPackets: StateFlow<List<ByteArray>> = _sentPackets.asStateFlow()

    private var connectedAddress: String? = null
    private var demoJob: Job? = null

    init {
        scope.launch {
            for (packet in sendQueue) {
                _sentPackets.update { it + packet.copyOf() }
            }
        }
    }

    override fun start() {
        refreshState()
        if (!autoDemo || demoJob != null) return
        val device = sampleDevices.first()
        connectedAddress = device.address
        _devices.update { list ->
            list.map {
                it.copy(
                    connectionStatus = if (it.address == device.address) {
                        DeviceConnectionStatus.CONNECTED
                    } else {
                        DeviceConnectionStatus.DISCONNECTED
                    },
                )
            }
        }
        _status.value = BluetoothStatus.Connected(device.address, SPP_UUID)
        demoJob = scope.launch {
            var messageNumber = 1
            while (true) {
                _receivedBytes.emit("Demo RX #$messageNumber\r\n".encodeToByteArray())
                messageNumber++
                delay(1_000)
            }
        }
    }

    override fun stop() {
        demoJob?.cancel()
        demoJob = null
    }

    override fun refreshState() {
        if (_status.value is BluetoothStatus.Failed) {
            _status.value = BluetoothStatus.Idle
        }
    }

    override fun requiredRuntimePermissions(): Set<String> = emptySet()

    override fun startScan() {
        _status.value = BluetoothStatus.Scanning
    }

    override fun stopScan() {
        _status.value = BluetoothStatus.Idle
    }

    override fun pair(address: String) {
        _status.value = BluetoothStatus.Pairing(address)
        _devices.update { list ->
            list.map {
                if (it.address == address) it.copy(bondStatus = BondStatus.BONDED) else it
            }
        }
        _status.value = BluetoothStatus.Idle
    }

    override fun refreshUuids(address: String) {
        _status.value = BluetoothStatus.RefreshingUuids(address)
        _devices.update { list ->
            list.map {
                if (it.address == address) {
                    it.copy(serviceUuids = (it.serviceUuids + SPP_UUID).distinct())
                } else {
                    it
                }
            }
        }
        _status.value = BluetoothStatus.Idle
    }

    override suspend fun discoverServiceUuids(address: String): List<UUID> {
        _status.value = BluetoothStatus.RefreshingUuids(address)
        delay(10)
        val discovered = _devices.value.firstOrNull { it.address == address }
            ?.serviceUuids
            .orEmpty()
        _status.value = BluetoothStatus.Idle
        return discovered
    }

    override suspend fun connect(address: String, uuid: UUID) {
        _status.value = BluetoothStatus.Connecting(address, uuid)
        delay(10)
        connectedAddress = address
        _devices.update { list ->
            list.map {
                it.copy(
                    connectionStatus = when (it.address) {
                        address -> DeviceConnectionStatus.CONNECTED
                        else -> DeviceConnectionStatus.DISCONNECTED
                    },
                )
            }
        }
        _status.value = BluetoothStatus.Connected(address, uuid)
    }

    override suspend fun connectGatt(address: String) {
        _status.value = BluetoothStatus.Connecting(
            address = address,
            mode = BluetoothConnectionMode.BLE_GATT,
        )
        delay(10)
        val device = _devices.value.firstOrNull { it.address == address }
        val profile = device?.let { selectBleGattProfile(it.gattServices) }
        if (profile == null) {
            _status.value = BluetoothStatus.Failed(
                BluetoothError.ConnectionFailed("未发现可写的 BLE GATT 特征"),
            )
            return
        }
        connectedAddress = address
        _devices.update { list ->
            list.map {
                it.copy(
                    connectionStatus = if (it.address == address) {
                        DeviceConnectionStatus.CONNECTED
                    } else {
                        DeviceConnectionStatus.DISCONNECTED
                    },
                    gattProfile = if (it.address == address) profile else it.gattProfile,
                )
            }
        }
        _status.value = BluetoothStatus.Connected(
            address = address,
            uuid = profile.writeCharacteristicUuid,
            mode = BluetoothConnectionMode.BLE_GATT,
            gattProfile = profile,
        )
    }

    override suspend fun disconnect() {
        val address = connectedAddress ?: return
        _status.value = BluetoothStatus.Disconnecting(address)
        connectedAddress = null
        _devices.update { list ->
            list.map { it.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED) }
        }
        _status.value = BluetoothStatus.Idle
    }

    override suspend fun send(bytes: ByteArray) {
        check(connectedAddress != null) { "尚未连接设备" }
        sendQueue.send(bytes.copyOf())
    }

    fun emitReceived(bytes: ByteArray) {
        _receivedBytes.tryEmit(bytes.copyOf())
    }

    fun fail(error: BluetoothError) {
        _status.value = BluetoothStatus.Failed(error)
    }

    companion object {
        val nordicUartServices = listOf(
            BleGattServiceModel(
                uuid = NUS_SERVICE_UUID,
                characteristics = listOf(
                    BleGattCharacteristicModel(
                        uuid = NUS_WRITE_UUID,
                        canWriteWithoutResponse = true,
                    ),
                    BleGattCharacteristicModel(
                        uuid = NUS_NOTIFY_UUID,
                        canNotify = true,
                    ),
                ),
            ),
        )

        val sampleDevices = listOf(
            BluetoothDeviceModel(
                name = "SPP Demo Device",
                address = "10:20:30:40:50:60",
                rssi = -42,
                transport = BluetoothTransport.CLASSIC,
                bondStatus = BondStatus.BONDED,
                serviceUuids = listOf(SPP_UUID),
            ),
            BluetoothDeviceModel(
                name = "Dual Mode Sensor",
                address = "AA:BB:CC:DD:EE:01",
                rssi = -67,
                transport = BluetoothTransport.DUAL,
                gattServices = nordicUartServices,
            ),
            BluetoothDeviceModel(
                name = "BLE UART Device",
                address = "AA:BB:CC:DD:EE:02",
                rssi = -81,
                transport = BluetoothTransport.LE,
                gattServices = nordicUartServices,
            ),
        )
    }
}
