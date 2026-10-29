package com.kuma.bluetoothdebugassistant.bluetooth

import java.util.UUID

val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
val NUS_WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
val NUS_NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

enum class BluetoothConnectionMode(val label: String) {
    CLASSIC_SPP("Classic SPP"),
    BLE_GATT("BLE GATT"),
}

enum class BluetoothTransport(val label: String) {
    CLASSIC("Classic"),
    LE("LE"),
    DUAL("Dual"),
    UNKNOWN("未知"),
}

enum class BondStatus(val label: String) {
    NONE("未配对"),
    BONDING("配对中"),
    BONDED("已配对"),
}

enum class DeviceConnectionStatus(val label: String) {
    DISCONNECTED("未连接"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
}

data class BleGattCharacteristicModel(
    val uuid: UUID,
    val canWrite: Boolean = false,
    val canWriteWithoutResponse: Boolean = false,
    val canNotify: Boolean = false,
    val canIndicate: Boolean = false,
    val canRead: Boolean = false,
)

data class BleGattServiceModel(
    val uuid: UUID,
    val characteristics: List<BleGattCharacteristicModel>,
)

data class BleGattProfile(
    val serviceUuid: UUID,
    val writeCharacteristicUuid: UUID,
    val notifyCharacteristicUuid: UUID?,
    val writeWithoutResponse: Boolean,
)

data class BluetoothDeviceModel(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val transport: BluetoothTransport = BluetoothTransport.UNKNOWN,
    val bondStatus: BondStatus = BondStatus.NONE,
    val connectionStatus: DeviceConnectionStatus = DeviceConnectionStatus.DISCONNECTED,
    val serviceUuids: List<UUID> = emptyList(),
    val gattServices: List<BleGattServiceModel> = emptyList(),
    val gattProfile: BleGattProfile? = null,
)

sealed interface BluetoothStatus {
    data object BluetoothUnavailable : BluetoothStatus
    data class PermissionMissing(val permissions: Set<String>) : BluetoothStatus
    data object BluetoothOff : BluetoothStatus
    data object Idle : BluetoothStatus
    data object Scanning : BluetoothStatus
    data class Pairing(val address: String) : BluetoothStatus
    data class RefreshingUuids(val address: String) : BluetoothStatus
    data class Connecting(
        val address: String,
        val uuid: UUID? = null,
        val mode: BluetoothConnectionMode = BluetoothConnectionMode.CLASSIC_SPP,
    ) : BluetoothStatus
    data class Connected(
        val address: String,
        val uuid: UUID,
        val mode: BluetoothConnectionMode = BluetoothConnectionMode.CLASSIC_SPP,
        val gattProfile: BleGattProfile? = null,
    ) : BluetoothStatus
    data class Disconnecting(val address: String) : BluetoothStatus
    data class Failed(val error: BluetoothError) : BluetoothStatus
}

sealed interface BluetoothError {
    val message: String

    data class MissingPermission(val permissions: Set<String>) : BluetoothError {
        override val message = "缺少蓝牙权限：${permissions.joinToString()}"
    }

    data object AdapterUnavailable : BluetoothError {
        override val message = "此设备不支持蓝牙"
    }

    data object AdapterDisabled : BluetoothError {
        override val message = "蓝牙未开启"
    }

    data class DiscoveryFailed(val detail: String) : BluetoothError {
        override val message = "扫描失败：$detail"
    }

    data class PairingFailed(val detail: String) : BluetoothError {
        override val message = "配对失败：$detail"
    }

    data class UuidRefreshFailed(val detail: String) : BluetoothError {
        override val message = "刷新 UUID 失败：$detail"
    }

    data class ConnectionFailed(val detail: String) : BluetoothError {
        override val message = "连接失败：$detail"
    }

    data class Disconnected(val detail: String) : BluetoothError {
        override val message = "连接已断开：$detail"
    }

    data class SendFailed(val detail: String) : BluetoothError {
        override val message = "发送失败：$detail"
    }
}

fun filterDevices(
    devices: List<BluetoothDeviceModel>,
    query: String,
): List<BluetoothDeviceModel> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return devices
    return devices.filter {
        it.name.contains(keyword, ignoreCase = true) ||
            it.address.contains(keyword, ignoreCase = true)
    }
}

fun rfcommConnectionCandidates(discovered: List<UUID>): List<UUID> {
    val bluetoothBaseSuffix = "-0000-1000-8000-00805f9b34fb"
    val customServices = discovered.filter { uuid ->
        uuid != NUS_SERVICE_UUID &&
            (uuid == SPP_UUID ||
                !uuid.toString().endsWith(bluetoothBaseSuffix, ignoreCase = true))
    }
    return (listOf(SPP_UUID) + customServices).distinct()
}

fun selectBleGattProfile(services: List<BleGattServiceModel>): BleGattProfile? {
    val nus = services.firstOrNull { it.uuid == NUS_SERVICE_UUID }
    val nusWrite = nus?.characteristics?.firstOrNull {
        it.uuid == NUS_WRITE_UUID && (it.canWrite || it.canWriteWithoutResponse)
    }
    if (nus != null && nusWrite != null) {
        val notify = nus.characteristics.firstOrNull {
            it.uuid == NUS_NOTIFY_UUID && (it.canNotify || it.canIndicate)
        }
        return BleGattProfile(
            serviceUuid = nus.uuid,
            writeCharacteristicUuid = nusWrite.uuid,
            notifyCharacteristicUuid = notify?.uuid,
            writeWithoutResponse = !nusWrite.canWrite && nusWrite.canWriteWithoutResponse,
        )
    }

    services.forEach { service ->
        val write = service.characteristics.firstOrNull { it.canWrite }
            ?: service.characteristics.firstOrNull { it.canWriteWithoutResponse }
            ?: return@forEach
        val notify = service.characteristics.firstOrNull { it.canNotify || it.canIndicate }
        return BleGattProfile(
            serviceUuid = service.uuid,
            writeCharacteristicUuid = write.uuid,
            notifyCharacteristicUuid = notify?.uuid,
            writeWithoutResponse = !write.canWrite && write.canWriteWithoutResponse,
        )
    }
    return null
}

fun ByteArray.toHexString(): String =
    joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }

fun String.hexToByteArray(): ByteArray {
    val normalized = filterNot(Char::isWhitespace)
    require(normalized.length % 2 == 0) { "HEX 必须包含偶数个字符" }
    require(normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        "HEX 包含非法字符"
    }
    return ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
