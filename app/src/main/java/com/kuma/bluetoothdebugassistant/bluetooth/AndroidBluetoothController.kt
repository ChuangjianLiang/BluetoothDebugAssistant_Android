package com.kuma.bluetoothdebugassistant.bluetooth

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

private const val UUID_DISCOVERY_TIMEOUT_MS = 6_000L
private const val GATT_CONNECTION_TIMEOUT_MS = 10_000L
private const val CLASSIC_CONNECTION_TIMEOUT_MS = 8_000L
private const val GATT_WRITE_TIMEOUT_MS = 8_000L
private const val DEFAULT_GATT_PAYLOAD_SIZE = 20

class AndroidBluetoothController(
    context: Context,
) : BluetoothController {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val uuidDiscoveryMutex = Mutex()
    private val writeQueue = Channel<WriteRequest>(Channel.UNLIMITED)

    private val _devices = MutableStateFlow<List<BluetoothDeviceModel>>(emptyList())
    override val devices: StateFlow<List<BluetoothDeviceModel>> = _devices.asStateFlow()

    private val _status = MutableStateFlow<BluetoothStatus>(initialStatus())
    override val status: StateFlow<BluetoothStatus> = _status.asStateFlow()

    private val _receivedBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val receivedBytes: SharedFlow<ByteArray> = _receivedBytes.asSharedFlow()

    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    private var readerJob: Job? = null
    private var receiverRegistered = false
    private var bleScanning = false
    private var activeAddress: String? = null
    private var activeUuid: UUID? = null
    private var activeMode: BluetoothConnectionMode? = null
    private var activeGattProfile: BleGattProfile? = null
    private var activeWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingGattConnection: CompletableDeferred<BluetoothStatus>? = null
    private var pendingGattWrite: CompletableDeferred<Result<Unit>>? = null
    private var pendingGattProfile: BleGattProfile? = null
    @Volatile
    private var pendingUuidDiscovery: PendingUuidDiscovery? = null

    private data class WriteRequest(
        val bytes: ByteArray,
        val completed: CompletableDeferred<Result<Unit>>,
    )

    private data class PendingUuidDiscovery(
        val address: String,
        val result: CompletableDeferred<List<UUID>>,
    )

    init {
        scope.launch {
            for (request in writeQueue) {
                val result = runCatching {
                    when (activeMode) {
                        BluetoothConnectionMode.CLASSIC_SPP -> {
                            val currentSocket = socket ?: error("尚未连接 Classic SPP")
                            currentSocket.outputStream.write(request.bytes)
                            currentSocket.outputStream.flush()
                        }
                        BluetoothConnectionMode.BLE_GATT -> writeGattPackets(request.bytes)
                        null -> error("尚未连接设备")
                    }
                }
                if (result.isFailure) {
                    _status.value = BluetoothStatus.Failed(
                        BluetoothError.SendFailed(
                            result.exceptionOrNull()?.message ?: "未知错误",
                        ),
                    )
                }
                request.completed.complete(result)
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            mergeDevice(
                device = result.device,
                rssi = result.rssi.toShort(),
                advertisedServiceUuids = result.scanRecord?.serviceUuids
                    ?.map(ParcelUuid::getUuid)
                    .orEmpty(),
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            bleScanning = false
            fail(BluetoothError.DiscoveryFailed("BLE 扫描错误码 $errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (bluetoothGatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishGattFailure(gatt, "GATT 状态码 $status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.discoverServices()) {
                        finishGattFailure(gatt, "无法启动 GATT 服务发现")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnecting = pendingGattConnection != null
                    val detail = if (wasConnecting) "GATT 连接已断开" else "BLE 设备已断开"
                    finishGattFailure(gatt, detail, disconnected = !wasConnecting)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (bluetoothGatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishGattFailure(gatt, "服务发现失败，状态码 $status")
                return
            }
            val services = gatt.services.map { service ->
                BleGattServiceModel(
                    uuid = service.uuid,
                    characteristics = service.characteristics.map { characteristic ->
                        characteristic.toModel()
                    },
                )
            }
            val profile = selectBleGattProfile(services)
            if (profile == null) {
                finishGattFailure(gatt, "未发现可写的 BLE GATT 特征")
                return
            }
            val service = gatt.getService(profile.serviceUuid)
            val write = service?.getCharacteristic(profile.writeCharacteristicUuid)
            if (write == null) {
                finishGattFailure(gatt, "写特征不可用")
                return
            }
            activeGattProfile = profile
            activeWriteCharacteristic = write
            pendingGattProfile = profile
            updateGattProfile(gatt.device.address, services, profile)

            val notify = profile.notifyCharacteristicUuid
                ?.let(service::getCharacteristic)
            if (notify != null) {
                if (enableGattNotifications(gatt, notify)) return
                finishGattFailure(gatt, "无法启用 BLE 接收通知")
                return
            }
            finishGattReady(gatt, profile)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val profile = pendingGattProfile ?: return
            if (bluetoothGatt !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finishGattReady(gatt, profile)
            } else {
                finishGattFailure(gatt, "启用 BLE 通知失败，状态码 $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (bluetoothGatt !== gatt) return
            val result = if (status == BluetoothGatt.GATT_SUCCESS) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("BLE 写入失败，状态码 $status"))
            }
            pendingGattWrite?.complete(result)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (bluetoothGatt === gatt) _receivedBytes.tryEmit(value.copyOf())
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (bluetoothGatt === gatt) {
                characteristic.value?.let { _receivedBytes.tryEmit(it.copyOf()) }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _status.value = BluetoothStatus.Scanning
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_status.value is BluetoothStatus.Scanning && !bleScanning) {
                        _status.value = connectedOrIdleStatus()
                    }
                }

                BluetoothDevice.ACTION_FOUND -> {
                    intent.bluetoothDevice()?.let { device ->
                        mergeDevice(device, intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0))
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    intent.bluetoothDevice()?.let { device ->
                        mergeDevice(device)
                        val previousState = intent.getIntExtra(
                            BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                            BluetoothDevice.BOND_NONE,
                        )
                        _status.value = when {
                            device.bondState == BluetoothDevice.BOND_BONDING ->
                                BluetoothStatus.Pairing(device.address)
                            device.bondState == BluetoothDevice.BOND_NONE &&
                                previousState == BluetoothDevice.BOND_BONDING ->
                                BluetoothStatus.Failed(
                                    BluetoothError.PairingFailed("配对被拒绝或已取消"),
                                )
                            else -> connectedOrIdleStatus()
                        }
                    }
                }

                BluetoothDevice.ACTION_UUID -> {
                    intent.bluetoothDevice()?.let { device ->
                        mergeDevice(device)
                        val discovered = intent.bluetoothUuids()
                            .ifEmpty { device.uuids?.map { it.uuid }.orEmpty() }
                            .ifEmpty {
                                _devices.value.firstOrNull { it.address == device.address }
                                    ?.serviceUuids
                                    .orEmpty()
                            }
                        if (discovered.isNotEmpty()) {
                            updateServiceUuids(device.address, discovered)
                        }
                        pendingUuidDiscovery
                            ?.takeIf { it.address == device.address }
                            ?.result
                            ?.complete(discovered)
                        _status.value = connectedOrIdleStatus()
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val address = intent.bluetoothDevice()?.address
                    if (address != null && address == activeAddress) {
                        scope.launch { handleRemoteDisconnect(address) }
                    }
                }
            }
        }
    }

    override fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_UUID)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }
        refreshState()
        loadBondedDevices()
    }

    override fun stop() {
        pendingUuidDiscovery?.result?.complete(emptyList())
        pendingUuidDiscovery = null
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        stopScan()
        scope.launch { disconnect() }
    }

    override fun refreshState() {
        _status.value = initialStatus()
        if (_status.value == BluetoothStatus.Idle) {
            loadBondedDevices()
        }
    }

    override fun requiredRuntimePermissions(): Set<String> {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.filterTo(mutableSetOf()) {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    override fun startScan() {
        if (!ensureReady()) return
        val currentAdapter = adapter ?: return
        try {
            if (currentAdapter.isDiscovering) currentAdapter.cancelDiscovery()
            val classicStarted = currentAdapter.startDiscovery()
            val scanner = currentAdapter.bluetoothLeScanner
            if (scanner != null) {
                scanner.startScan(bleScanCallback)
                bleScanning = true
            }
            if (!classicStarted && !bleScanning) {
                fail(BluetoothError.DiscoveryFailed("系统拒绝启动 Classic 与 BLE 扫描"))
            } else {
                _status.value = BluetoothStatus.Scanning
            }
        } catch (error: SecurityException) {
            permissionFailure(error)
        } catch (error: Exception) {
            fail(BluetoothError.DiscoveryFailed(error.message ?: "未知错误"))
        }
    }

    override fun stopScan() {
        val currentAdapter = adapter ?: return
        if (requiredRuntimePermissions().isNotEmpty()) return
        try {
            if (currentAdapter.isDiscovering) currentAdapter.cancelDiscovery()
            if (bleScanning) {
                currentAdapter.bluetoothLeScanner?.stopScan(bleScanCallback)
                bleScanning = false
            }
            if (_status.value is BluetoothStatus.Scanning) {
                _status.value = connectedOrIdleStatus()
            }
        } catch (error: SecurityException) {
            permissionFailure(error)
        }
    }

    @SuppressLint("MissingPermission")
    override fun pair(address: String) {
        if (!ensureReady()) return
        try {
            _status.value = BluetoothStatus.Pairing(address)
            if (adapter?.getRemoteDevice(address)?.createBond() != true) {
                fail(BluetoothError.PairingFailed("系统拒绝发起配对"))
            }
        } catch (error: Exception) {
            fail(BluetoothError.PairingFailed(error.message ?: "未知错误"))
        }
    }

    @SuppressLint("MissingPermission")
    override fun refreshUuids(address: String) {
        if (!ensureReady()) return
        try {
            _status.value = BluetoothStatus.RefreshingUuids(address)
            if (adapter?.getRemoteDevice(address)?.fetchUuidsWithSdp() != true) {
                fail(BluetoothError.UuidRefreshFailed("系统拒绝 SDP 查询"))
            }
        } catch (error: Exception) {
            fail(BluetoothError.UuidRefreshFailed(error.message ?: "未知错误"))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun discoverServiceUuids(address: String): List<UUID> =
        withContext(Dispatchers.IO) {
            uuidDiscoveryMutex.withLock {
                val cached = _devices.value.firstOrNull { it.address == address }
                    ?.serviceUuids
                    .orEmpty()
                if (!ensureReady()) return@withLock cached

                val result = CompletableDeferred<List<UUID>>()
                pendingUuidDiscovery = PendingUuidDiscovery(address, result)
                try {
                    _status.value = BluetoothStatus.RefreshingUuids(address)
                    val started = adapter?.getRemoteDevice(address)?.fetchUuidsWithSdp() == true
                    if (!started) return@withLock cached

                    withTimeoutOrNull(UUID_DISCOVERY_TIMEOUT_MS) {
                        result.await()
                    }.orEmpty().ifEmpty {
                        _devices.value.firstOrNull { it.address == address }
                            ?.serviceUuids
                            .orEmpty()
                    }
                } catch (error: SecurityException) {
                    permissionFailure(error)
                    cached
                } catch (_: Exception) {
                    cached
                } finally {
                    pendingUuidDiscovery = null
                    if (_status.value is BluetoothStatus.RefreshingUuids) {
                        _status.value = connectedOrIdleStatus()
                    }
                }
            }
        }

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String, uuid: UUID) = withContext(Dispatchers.IO) {
        if (!ensureReady()) return@withContext
        if (_devices.value.firstOrNull { it.address == address }?.transport == BluetoothTransport.LE) {
            fail(BluetoothError.ConnectionFailed("仅支持 Bluetooth Classic RFCOMM，不能连接纯 LE 设备"))
            return@withContext
        }

        connectionMutex.withLock {
            closeConnections()
            try {
                adapter?.cancelDiscovery()
                _status.value = BluetoothStatus.Connecting(
                    address,
                    uuid,
                    BluetoothConnectionMode.CLASSIC_SPP,
                )
                updateConnection(address, DeviceConnectionStatus.CONNECTING)
                val device = adapter?.getRemoteDevice(address)
                    ?: error("蓝牙适配器不可用")
                val newSocket = device.createRfcommSocketToServiceRecord(uuid)
                socket = newSocket
                val timeoutJob = scope.launch {
                    delay(CLASSIC_CONNECTION_TIMEOUT_MS)
                    if (socket === newSocket && !newSocket.isConnected) {
                        runCatching { newSocket.close() }
                    }
                }
                try {
                    newSocket.connect()
                } finally {
                    timeoutJob.cancel()
                }
                activeAddress = address
                activeUuid = uuid
                activeMode = BluetoothConnectionMode.CLASSIC_SPP
                updateConnection(address, DeviceConnectionStatus.CONNECTED)
                _status.value = BluetoothStatus.Connected(
                    address,
                    uuid,
                    BluetoothConnectionMode.CLASSIC_SPP,
                )
                startReader(newSocket, address)
            } catch (error: Exception) {
                closeConnections()
                updateConnection(address, DeviceConnectionStatus.DISCONNECTED)
                fail(BluetoothError.ConnectionFailed(error.message ?: "未知错误"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connectGatt(address: String) = withContext(Dispatchers.IO) {
        if (!ensureReady()) return@withContext
        if (_devices.value.firstOrNull { it.address == address }?.transport ==
            BluetoothTransport.CLASSIC
        ) {
            fail(BluetoothError.ConnectionFailed("此设备仅支持 Bluetooth Classic"))
            return@withContext
        }

        connectionMutex.withLock {
            closeConnections()
            adapter?.cancelDiscovery()
            if (bleScanning) {
                adapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
                bleScanning = false
            }
            _status.value = BluetoothStatus.Connecting(
                address = address,
                mode = BluetoothConnectionMode.BLE_GATT,
            )
            updateConnection(address, DeviceConnectionStatus.CONNECTING)
            activeAddress = address
            activeMode = BluetoothConnectionMode.BLE_GATT
            val result = CompletableDeferred<BluetoothStatus>()
            pendingGattConnection = result
            try {
                val device = adapter?.getRemoteDevice(address)
                    ?: error("蓝牙适配器不可用")
                bluetoothGatt = device.connectGatt(
                    appContext,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                )
                val completed = withTimeoutOrNull(GATT_CONNECTION_TIMEOUT_MS) {
                    result.await()
                }
                if (completed !is BluetoothStatus.Connected) {
                    if (completed == null) {
                        finishGattFailure(bluetoothGatt, "GATT 连接或服务发现超时")
                    }
                }
            } catch (error: Exception) {
                finishGattFailure(bluetoothGatt, error.message ?: "未知错误")
            } finally {
                pendingGattConnection = null
            }
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            val address = activeAddress ?: return@withLock
            _status.value = BluetoothStatus.Disconnecting(address)
            closeConnections()
            updateConnection(address, DeviceConnectionStatus.DISCONNECTED)
            _status.value = BluetoothStatus.Idle
        }
    }

    override suspend fun send(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "发送内容不能为空" }
        val completed = CompletableDeferred<Result<Unit>>()
        writeQueue.send(WriteRequest(bytes.copyOf(), completed))
        completed.await().getOrThrow()
    }

    private fun startReader(connectedSocket: BluetoothSocket, address: String) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            try {
                while (true) {
                    val count = connectedSocket.inputStream.read(buffer)
                    if (count < 0) throw IOException("输入流已结束")
                    if (count > 0) _receivedBytes.emit(buffer.copyOf(count))
                }
            } catch (error: Exception) {
                if (socket === connectedSocket) {
                    handleRemoteDisconnect(address, error.message ?: "读取失败")
                }
            }
        }
    }

    private suspend fun handleRemoteDisconnect(address: String, detail: String = "远端断开") {
        connectionMutex.withLock {
            if (activeAddress != address) return
            closeConnections()
            updateConnection(address, DeviceConnectionStatus.DISCONNECTED)
            fail(BluetoothError.Disconnected(detail))
        }
    }

    private suspend fun writeGattPackets(bytes: ByteArray) {
        bytes.asList()
            .chunked(DEFAULT_GATT_PAYLOAD_SIZE)
            .forEach { chunk ->
                writeGattChunk(chunk.toByteArray())
            }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeGattChunk(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: error("BLE GATT 未连接")
        val characteristic = activeWriteCharacteristic ?: error("BLE 写特征不可用")
        val profile = activeGattProfile ?: error("BLE GATT 配置不可用")
        val writeType = if (profile.writeWithoutResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val completed = if (profile.writeWithoutResponse) {
            null
        } else {
            CompletableDeferred<Result<Unit>>().also { pendingGattWrite = it }
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, bytes, writeType) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = bytes
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            pendingGattWrite = null
            error("系统拒绝 BLE 写入")
        }
        if (completed == null) {
            delay(20)
            return
        }
        try {
            val result = withTimeoutOrNull(GATT_WRITE_TIMEOUT_MS) {
                completed.await()
            } ?: Result.failure(IOException("BLE 写入超时"))
            result.getOrThrow()
        } finally {
            pendingGattWrite = null
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enableGattNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return false
        val value = if (
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        ) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun finishGattReady(gatt: BluetoothGatt, profile: BleGattProfile) {
        if (bluetoothGatt !== gatt) return
        pendingGattProfile = null
        activeAddress = gatt.device.address
        activeUuid = profile.writeCharacteristicUuid
        activeMode = BluetoothConnectionMode.BLE_GATT
        updateConnection(gatt.device.address, DeviceConnectionStatus.CONNECTED)
        val connected = BluetoothStatus.Connected(
            address = gatt.device.address,
            uuid = profile.writeCharacteristicUuid,
            mode = BluetoothConnectionMode.BLE_GATT,
            gattProfile = profile,
        )
        _status.value = connected
        pendingGattConnection?.complete(connected)
    }

    @SuppressLint("MissingPermission")
    private fun finishGattFailure(
        gatt: BluetoothGatt?,
        detail: String,
        disconnected: Boolean = false,
    ) {
        if (gatt != null && bluetoothGatt !== gatt) return
        val address = activeAddress
        val failed = BluetoothStatus.Failed(
            if (disconnected) {
                BluetoothError.Disconnected(detail)
            } else {
                BluetoothError.ConnectionFailed(detail)
            },
        )
        _status.value = failed
        pendingGattConnection?.complete(failed)
        pendingGattWrite?.complete(Result.failure(IOException(detail)))
        if (address != null) {
            updateConnection(address, DeviceConnectionStatus.DISCONNECTED)
        }
        closeGatt()
    }

    private fun BluetoothGattCharacteristic.toModel(): BleGattCharacteristicModel {
        val flags = properties
        return BleGattCharacteristicModel(
            uuid = uuid,
            canWrite = flags and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
            canWriteWithoutResponse =
                flags and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
            canNotify = flags and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
            canIndicate = flags and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
            canRead = flags and BluetoothGattCharacteristic.PROPERTY_READ != 0,
        )
    }

    private fun closeConnections() {
        readerJob?.cancel()
        readerJob = null
        runCatching { socket?.close() }
        socket = null
        closeGatt()
        activeAddress = null
        activeUuid = null
        activeMode = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val current = bluetoothGatt
        bluetoothGatt = null
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
        activeGattProfile = null
        activeWriteCharacteristic = null
        pendingGattProfile = null
        pendingGattWrite = null
        if (activeMode == BluetoothConnectionMode.BLE_GATT) {
            activeAddress = null
            activeUuid = null
            activeMode = null
        }
    }

    private fun loadBondedDevices() {
        if (!ensureReady()) return
        try {
            adapter?.bondedDevices.orEmpty().forEach { mergeDevice(it) }
        } catch (error: SecurityException) {
            permissionFailure(error)
        }
    }

    private fun mergeDevice(
        device: BluetoothDevice,
        rssi: Short? = null,
        advertisedServiceUuids: List<UUID> = emptyList(),
    ) {
        try {
            val candidate = BluetoothDeviceModel(
                name = device.name?.takeIf(String::isNotBlank) ?: "未知设备",
                address = device.address,
                rssi = rssi?.takeUnless { it.toInt() == 0 }?.toInt(),
                transport = device.type.toTransport(),
                bondStatus = device.bondState.toBondStatus(),
                serviceUuids = (
                    device.uuids?.map { it.uuid }.orEmpty() + advertisedServiceUuids
                ).distinct(),
            )
            _devices.update { current ->
                val existing = current.firstOrNull { it.address == candidate.address }
                val merged = candidate.copy(
                    rssi = candidate.rssi ?: existing?.rssi,
                    serviceUuids = (
                        existing?.serviceUuids.orEmpty() + candidate.serviceUuids
                    ).distinct(),
                    connectionStatus = existing?.connectionStatus
                        ?: DeviceConnectionStatus.DISCONNECTED,
                    gattServices = existing?.gattServices.orEmpty(),
                    gattProfile = existing?.gattProfile,
                )
                (current.filterNot { it.address == candidate.address } + merged)
                    .sortedWith(
                        compareByDescending<BluetoothDeviceModel> {
                            it.bondStatus == BondStatus.BONDED
                        }.thenByDescending { it.rssi ?: Int.MIN_VALUE },
                    )
            }
        } catch (error: SecurityException) {
            permissionFailure(error)
        }
    }

    private fun updateConnection(address: String, state: DeviceConnectionStatus) {
        _devices.update { current ->
            current.map {
                if (it.address == address) it.copy(connectionStatus = state)
                else if (state == DeviceConnectionStatus.CONNECTED) {
                    it.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED)
                } else {
                    it
                }
            }
        }
    }

    private fun updateServiceUuids(address: String, uuids: List<UUID>) {
        _devices.update { current ->
            current.map {
                if (it.address == address) it.copy(serviceUuids = uuids.distinct()) else it
            }
        }
    }

    private fun updateGattProfile(
        address: String,
        services: List<BleGattServiceModel>,
        profile: BleGattProfile,
    ) {
        _devices.update { current ->
            current.map {
                if (it.address == address) {
                    it.copy(gattServices = services, gattProfile = profile)
                } else {
                    it
                }
            }
        }
    }

    private fun ensureReady(): Boolean {
        val newStatus = initialStatus()
        if (newStatus != BluetoothStatus.Idle) {
            _status.value = newStatus
            return false
        }
        return true
    }

    private fun initialStatus(): BluetoothStatus {
        val currentAdapter = adapter ?: return BluetoothStatus.BluetoothUnavailable
        val missing = requiredRuntimePermissions()
        if (missing.isNotEmpty()) return BluetoothStatus.PermissionMissing(missing)
        return try {
            if (currentAdapter.isEnabled) BluetoothStatus.Idle else BluetoothStatus.BluetoothOff
        } catch (_: SecurityException) {
            BluetoothStatus.PermissionMissing(requiredRuntimePermissions())
        }
    }

    private fun connectedOrIdleStatus(): BluetoothStatus {
        val address = activeAddress
        val uuid = activeUuid
        val profile = activeGattProfile
        return when {
            address != null &&
                activeMode == BluetoothConnectionMode.BLE_GATT &&
                bluetoothGatt != null &&
                profile != null ->
                BluetoothStatus.Connected(
                    address = address,
                    uuid = profile.writeCharacteristicUuid,
                    mode = BluetoothConnectionMode.BLE_GATT,
                    gattProfile = profile,
                )

            address != null && uuid != null && socket?.isConnected == true ->
                BluetoothStatus.Connected(
                    address = address,
                    uuid = uuid,
                    mode = BluetoothConnectionMode.CLASSIC_SPP,
                )

            else -> BluetoothStatus.Idle
        }
    }

    private fun permissionFailure(error: SecurityException) {
        val missing = requiredRuntimePermissions()
        _status.value = if (missing.isNotEmpty()) {
            BluetoothStatus.PermissionMissing(missing)
        } else {
            BluetoothStatus.Failed(
                BluetoothError.MissingPermission(setOf(error.message ?: "蓝牙权限")),
            )
        }
    }

    private fun fail(error: BluetoothError) {
        _status.value = BluetoothStatus.Failed(error)
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothUuids(): List<UUID> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                ?.map(ParcelUuid::getUuid)
                .orEmpty()
        } else {
            getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                ?.filterIsInstance<ParcelUuid>()
                ?.map(ParcelUuid::getUuid)
                .orEmpty()
        }

    private fun Int.toBondStatus(): BondStatus = when (this) {
        BluetoothDevice.BOND_BONDED -> BondStatus.BONDED
        BluetoothDevice.BOND_BONDING -> BondStatus.BONDING
        else -> BondStatus.NONE
    }

    private fun Int.toTransport(): BluetoothTransport = when (this) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothTransport.CLASSIC
        BluetoothDevice.DEVICE_TYPE_LE -> BluetoothTransport.LE
        BluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothTransport.DUAL
        else -> BluetoothTransport.UNKNOWN
    }
}
