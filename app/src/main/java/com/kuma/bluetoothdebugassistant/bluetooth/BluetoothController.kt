package com.kuma.bluetoothdebugassistant.bluetooth

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

interface BluetoothController {
    val devices: StateFlow<List<BluetoothDeviceModel>>
    val status: StateFlow<BluetoothStatus>
    val receivedBytes: SharedFlow<ByteArray>

    fun start()
    fun stop()
    fun refreshState()
    fun requiredRuntimePermissions(): Set<String>
    fun startScan()
    fun stopScan()
    fun pair(address: String)
    fun refreshUuids(address: String)
    suspend fun discoverServiceUuids(address: String): List<UUID>
    suspend fun connect(address: String, uuid: UUID = SPP_UUID)
    suspend fun connectGatt(address: String)
    suspend fun disconnect()
    suspend fun send(bytes: ByteArray)
}
