package com.kuma.bluetoothdebugassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothController
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothDeviceModel
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothStatus
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothTransport
import com.kuma.bluetoothdebugassistant.bluetooth.NUS_SERVICE_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.SPP_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.filterDevices
import com.kuma.bluetoothdebugassistant.bluetooth.hexToByteArray
import com.kuma.bluetoothdebugassistant.bluetooth.rfcommConnectionCandidates
import com.kuma.bluetoothdebugassistant.project.ProjectEntity
import com.kuma.bluetoothdebugassistant.project.CommandEncoder
import com.kuma.bluetoothdebugassistant.project.ProjectComponent
import com.kuma.bluetoothdebugassistant.project.ProjectLayout
import com.kuma.bluetoothdebugassistant.project.EditorMode
import com.kuma.bluetoothdebugassistant.project.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppTab(val route: String) {
    Devices("devices"),
    Projects("projects"),
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    val controller: BluetoothController,
    private val projectRepository: ProjectRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val consoleBuffer = ConsoleBuffer()
    private val _selectedTab = MutableStateFlow(AppTab.Devices)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _deviceQuery = MutableStateFlow("")
    val query: StateFlow<String> = _deviceQuery.asStateFlow()

    val devices: StateFlow<List<BluetoothDeviceModel>> =
        combine(controller.devices, _deviceQuery, ::filterDevices)
            .stateIn(viewModelScope, SharingStarted.Eagerly, controller.devices.value)

    val status = controller.status

    private val _projectQuery = MutableStateFlow("")
    val projectQuery: StateFlow<String> = _projectQuery.asStateFlow()
    val projects: StateFlow<List<ProjectEntity>> = _projectQuery
        .flatMapLatest(projectRepository::observeProjects)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages

    private val _selectedUuids = MutableStateFlow<Map<String, UUID>>(emptyMap())
    val selectedUuids: StateFlow<Map<String, UUID>> = _selectedUuids.asStateFlow()

    private val _consoleEntries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val consoleEntries: StateFlow<List<ConsoleEntry>> = _consoleEntries.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { projectRepository.ensureSampleProject() }
                .onFailure { _messages.emit(it.userMessage("默认项目创建失败")) }
        }
        viewModelScope.launch {
            controller.receivedBytes.collect { bytes ->
                consoleBuffer.append(ConsoleEntry(nowMillis(), ConsoleDirection.RX, bytes))
                _consoleEntries.value = consoleBuffer.snapshot()
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
    }

    fun setQuery(value: String) {
        _deviceQuery.value = value
    }

    fun setProjectQuery(value: String) {
        _projectQuery.value = value
    }

    fun observeProject(id: String) = projectRepository.observeProject(id)

    fun createProject(name: String) = launchProjectMutation("新建项目失败") {
        projectRepository.create(name)
    }

    fun renameProject(id: String, name: String) = launchProjectMutation("重命名失败") {
        projectRepository.rename(id, name)
    }

    fun duplicateProject(id: String) = launchProjectMutation("复制项目失败") {
        projectRepository.duplicate(id)
    }

    fun deleteProject(id: String) = launchProjectMutation("删除项目失败") {
        projectRepository.delete(id)
    }

    fun updateLayout(id: String, layout: ProjectLayout, showConfirmation: Boolean = false) {
        viewModelScope.launch {
            runCatching { projectRepository.updateLayout(id, layout) }
                .onSuccess {
                    if (showConfirmation) _messages.emit("布局已保存")
                }
                .onFailure { _messages.emit(it.userMessage("布局保存失败")) }
        }
    }

    fun bindProjectDevice(id: String, device: BluetoothDeviceModel?) =
        launchProjectMutation("设备绑定失败") {
            projectRepository.bindDevice(id, device?.name, device?.address)
        }

    fun sendProjectCommand(
        mode: EditorMode,
        component: ProjectComponent,
        commandOverride: String = component.command,
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                if (mode != EditorMode.RUN || status.value !is BluetoothStatus.Connected) return@runCatching
                sendBytes(
                    CommandEncoder.encode(
                        component.commandMode,
                        commandOverride,
                        component.appendNewline,
                    ),
                )
            }.onFailure { onError(it.userMessage("发送失败")) }
        }
    }

    fun refreshPermissionsAndState() = controller.refreshState()

    fun requiredPermissions(): Array<String> =
        controller.requiredRuntimePermissions().toTypedArray()

    fun startScan() = controller.startScan()

    fun stopScan() = controller.stopScan()

    fun pair(address: String) = controller.pair(address)

    fun refreshUuids(address: String) = controller.refreshUuids(address)

    fun selectedUuid(address: String): UUID = _selectedUuids.value[address] ?: SPP_UUID

    fun selectUuid(address: String, uuid: UUID) {
        _selectedUuids.update { it + (address to uuid) }
    }

    fun connect(
        address: String,
        uuid: UUID = selectedUuid(address),
        onComplete: (BluetoothStatus) -> Unit = {},
    ) {
        viewModelScope.launch {
            controller.connect(address, uuid)
            onComplete(status.value)
        }
    }

    fun discoverAndConnect(
        address: String,
        onComplete: (BluetoothStatus) -> Unit = {},
    ) {
        viewModelScope.launch {
            val device = controller.devices.value
                .firstOrNull { it.address == address }
            val transport = device?.transport ?: BluetoothTransport.UNKNOWN
            var result: BluetoothStatus = status.value
            val attemptedClassicUuids = mutableSetOf<UUID>()

            suspend fun tryGatt(): Boolean {
                controller.connectGatt(address)
                val current = status.value
                result = current
                return current is BluetoothStatus.Connected && current.address == address
            }

            suspend fun tryClassic(uuids: List<UUID>): Boolean {
                for (uuid in rfcommConnectionCandidates(uuids)) {
                    if (!attemptedClassicUuids.add(uuid)) continue
                    selectUuid(address, uuid)
                    controller.connect(address, uuid)
                    val current = status.value
                    result = current
                    if (current is BluetoothStatus.Connected && current.address == address) {
                        return true
                    }
                }
                return false
            }

            val preferGatt = transport == BluetoothTransport.LE ||
                device?.gattProfile != null ||
                NUS_SERVICE_UUID in device?.serviceUuids.orEmpty() ||
                (transport != BluetoothTransport.CLASSIC &&
                    SPP_UUID !in device?.serviceUuids.orEmpty())

            if (preferGatt && tryGatt()) {
                onComplete(result)
                return@launch
            }

            if (transport == BluetoothTransport.LE) {
                onComplete(result)
                return@launch
            }

            if (tryClassic(device?.serviceUuids.orEmpty())) {
                onComplete(result)
                return@launch
            }

            val discovered = runCatching { controller.discoverServiceUuids(address) }
                .getOrDefault(emptyList())
            if (tryClassic(discovered)) {
                onComplete(result)
                return@launch
            }

            if (!preferGatt && transport != BluetoothTransport.CLASSIC) {
                tryGatt()
            }
            onComplete(result)
        }
    }

    fun disconnect() {
        viewModelScope.launch { controller.disconnect() }
    }

    fun sendText(value: String, onError: (String) -> Unit = {}) {
        if (value.isEmpty()) return
        viewModelScope.launch {
            runCatching { sendBytes(value.encodeToByteArray()) }
                .onFailure { onError(it.userMessage("发送失败")) }
        }
    }

    fun sendHex(value: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            val bytes = runCatching { value.hexToByteArray() }
                .getOrElse {
                    onError(it.message ?: "HEX 格式错误")
                    return@launch
                }
            runCatching { sendBytes(bytes) }
                .onFailure { onError(it.message ?: "发送失败") }
        }
    }

    fun clearConsole() {
        consoleBuffer.clear()
        _consoleEntries.value = emptyList()
    }

    private suspend fun sendBytes(bytes: ByteArray) {
        controller.send(bytes)
        consoleBuffer.append(ConsoleEntry(nowMillis(), ConsoleDirection.TX, bytes))
        _consoleEntries.value = consoleBuffer.snapshot()
    }

    private fun launchProjectMutation(
        fallbackMessage: String,
        operation: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { operation() }
                .onFailure { _messages.emit(it.userMessage(fallbackMessage)) }
        }
    }

    class Factory(
        private val controller: BluetoothController,
        private val projectRepository: ProjectRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return AppViewModel(controller, projectRepository) as T
            }
            error("未知 ViewModel：${modelClass.name}")
        }
    }
}

private fun Throwable.userMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback
