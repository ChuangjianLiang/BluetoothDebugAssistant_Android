package com.kuma.bluetoothdebugassistant

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothDeviceModel
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothConnectionMode
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothStatus
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothTransport
import com.kuma.bluetoothdebugassistant.bluetooth.BondStatus
import com.kuma.bluetoothdebugassistant.bluetooth.DeviceConnectionStatus
import com.kuma.bluetoothdebugassistant.bluetooth.FakeBluetoothController
import com.kuma.bluetoothdebugassistant.bluetooth.NUS_NOTIFY_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.NUS_SERVICE_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.NUS_WRITE_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.SPP_UUID
import com.kuma.bluetoothdebugassistant.bluetooth.rfcommConnectionCandidates
import com.kuma.bluetoothdebugassistant.project.ProjectEntity
import com.kuma.bluetoothdebugassistant.ui.theme.AppBackground
import com.kuma.bluetoothdebugassistant.ui.theme.CardBackground
import com.kuma.bluetoothdebugassistant.ui.theme.PrimaryBlue
import com.kuma.bluetoothdebugassistant.ui.theme.SecondaryColor
import com.kuma.bluetoothdebugassistant.ui.theme.TitleColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val DETAIL_ROUTE = "device"
private const val PROJECT_RUN_ROUTE = "project-run"
private const val PROJECT_EDIT_ROUTE = "project-edit"

@Composable
fun BluetoothDebugApp(appViewModel: AppViewModel) {
    val selectedTab by appViewModel.selectedTab.collectAsStateWithLifecycle()
    val status by appViewModel.status.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val showBottomBar = currentBackStackEntry?.destination?.route in
        setOf(AppTab.Devices.route, AppTab.Projects.route)
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        appViewModel.refreshPermissionsAndState()
    }

    LaunchedEffect(status) {
        val message = (status as? BluetoothStatus.Failed)?.error?.message
        if (message != null) snackbarHostState.showSnackbar(message)
    }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect(snackbarHostState::showSnackbar)
    }

    Scaffold(
        containerColor = AppBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (appViewModel.controller is FakeBluetoothController) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(0.dp),
                ) {
                    Text(
                        "离线演示 · 使用模拟蓝牙数据",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        bottomBar = {
            if (showBottomBar) NavigationBar(containerColor = CardBackground) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.Devices,
                    onClick = {
                        appViewModel.selectTab(AppTab.Devices)
                        navController.navigate(AppTab.Devices.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Outlined.Bluetooth, contentDescription = null) },
                    label = { Text("设备") },
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.Projects,
                    onClick = {
                        appViewModel.selectTab(AppTab.Projects)
                        navController.navigate(AppTab.Projects.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    label = { Text("项目") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppTab.Devices.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(AppTab.Devices.route) {
                DeviceListScreen(
                    viewModel = appViewModel,
                    requestPermissions = {
                        permissionLauncher.launch(appViewModel.requiredPermissions())
                    },
                    openDevice = { address ->
                        navController.navigate("$DETAIL_ROUTE/$address")
                    },
                )
            }
            composable(AppTab.Projects.route) {
                ProjectListScreen(
                    viewModel = appViewModel,
                    runProject = { id ->
                        navController.navigate("$PROJECT_RUN_ROUTE/$id")
                    },
                    editProject = { id ->
                        navController.navigate("$PROJECT_EDIT_ROUTE/$id")
                    },
                )
            }
            composable("$DETAIL_ROUTE/{address}") { entry ->
                DeviceDetailScreen(
                    address = entry.arguments?.getString("address").orEmpty(),
                    viewModel = appViewModel,
                    onBack = navController::popBackStack,
                )
            }
            composable("$PROJECT_RUN_ROUTE/{id}") { entry ->
                ProjectRuntimeScreen(
                    id = entry.arguments?.getString("id").orEmpty(),
                    viewModel = appViewModel,
                    onBack = navController::popBackStack,
                )
            }
            composable("$PROJECT_EDIT_ROUTE/{id}") { entry ->
                ProjectEditorScreen(
                    id = entry.arguments?.getString("id").orEmpty(),
                    viewModel = appViewModel,
                    onBack = navController::popBackStack,
                )
            }
        }
    }
}

@Composable
private fun DeviceListScreen(
    viewModel: AppViewModel,
    requestPermissions: () -> Unit,
    openDevice: (String) -> Unit,
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val missingPermissions = status as? BluetoothStatus.PermissionMissing
    val scanning = status is BluetoothStatus.Scanning

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "蓝牙设备",
            modifier = Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TitleColor,
        )
        StatusBanner(status)
        if (missingPermissions != null) {
            Button(
                onClick = requestPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("授予蓝牙权限")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text("按名称或 MAC 实时过滤") },
        )
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = if (scanning) viewModel::stopScan else viewModel::startScan,
                enabled = missingPermissions == null,
            ) {
                Text(if (scanning) "停止扫描" else "开始扫描")
            }
            Text(
                "已配对与扫描结果已合并 · ${devices.size} 台",
                modifier = Modifier.align(Alignment.CenterVertically),
                color = SecondaryColor,
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(devices, key = { it.address }) { device ->
                DeviceCard(device = device, onClick = { openDevice(device.address) })
            }
        }
    }
}

@Composable
private fun StatusBanner(status: BluetoothStatus) {
    val (text, isError) = when (status) {
        BluetoothStatus.BluetoothUnavailable -> "蓝牙不可用" to true
        is BluetoothStatus.PermissionMissing -> "缺少权限：${status.permissions.joinToString()}" to true
        BluetoothStatus.BluetoothOff -> "蓝牙已关闭，请在系统设置中开启" to true
        BluetoothStatus.Idle -> "空闲" to false
        BluetoothStatus.Scanning -> "正在扫描 Classic 与 BLE 设备…" to false
        is BluetoothStatus.Pairing -> "正在配对 ${status.address}" to false
        is BluetoothStatus.RefreshingUuids -> "正在通过 SDP 刷新 UUID" to false
        is BluetoothStatus.Connecting ->
            "正在通过 ${status.mode.label} 连接 ${status.address}" to false
        is BluetoothStatus.Connected ->
            "已通过 ${status.mode.label} 连接 ${status.address}" to false
        is BluetoothStatus.Disconnecting -> "正在断开 ${status.address}" to false
        is BluetoothStatus.Failed -> status.error.message to true
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        Text(text, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun DeviceCard(
    device: BluetoothDeviceModel,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Bluetooth,
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(device.address, color = SecondaryColor)
                Text(
                    "${device.transport.label} · ${device.bondStatus.label} · " +
                        device.connectionStatus.label,
                    color = SecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(device.rssi?.let { "$it dBm" } ?: "—")
        }
    }
}

@Composable
private fun DeviceDetailScreen(
    address: String,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val devices by viewModel.controller.devices.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val selectedUuids by viewModel.selectedUuids.collectAsStateWithLifecycle()
    val consoleEntries by viewModel.consoleEntries.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.address == address }
    var sendValue by remember { mutableStateOf("") }
    var displayMode by remember { mutableStateOf(ConsoleDisplayMode.UTF8) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var uuidExpanded by remember(address) { mutableStateOf(false) }
    var connectionDialog by remember(address) { mutableStateOf<ConnectionDialog?>(null) }
    var pairingStarted by remember(address) { mutableStateOf(false) }
    var autoConnectionStarted by remember(address) { mutableStateOf(false) }
    var manualDisconnectRequested by remember(address) { mutableStateOf(false) }
    var logPaused by remember { mutableStateOf(false) }
    var pausedEntries by remember { mutableStateOf<List<ConsoleEntry>>(emptyList()) }

    if (device == null) {
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("设备信息已不可用")
        }
        return
    }

    val isConnected = device.connectionStatus == DeviceConnectionStatus.CONNECTED
    val selectedUuid = selectedUuids[address] ?: SPP_UUID
    val uuidChoices = rfcommConnectionCandidates(device.serviceUuids)
    val hasNordicUart = NUS_SERVICE_UUID in device.serviceUuids ||
        device.gattProfile?.serviceUuid == NUS_SERVICE_UUID
    val connectedStatus = (status as? BluetoothStatus.Connected)
        ?.takeIf { it.address == address }
    val isBusy = status is BluetoothStatus.Connecting ||
        status is BluetoothStatus.RefreshingUuids ||
        status is BluetoothStatus.Disconnecting
    val visibleEntries = if (logPaused) pausedEntries else consoleEntries
    val isLandscape = LocalConfiguration.current.orientation ==
        Configuration.ORIENTATION_LANDSCAPE

    fun discoverAndConnect() {
        viewModel.discoverAndConnect(address) { result ->
            if (!manualDisconnectRequested) {
                connectionDialog = when (result) {
                    is BluetoothStatus.Connected -> ConnectionDialog.Success
                    is BluetoothStatus.Failed -> ConnectionDialog.Failure(
                        if (hasNordicUart) {
                            "已识别 Nordic UART 服务，但 BLE GATT 连接失败：" +
                                "${result.error.message}。请确认设备未被其他手机占用，" +
                                "且 002 支持写入、003 支持通知。"
                        } else {
                            "已自动尝试 BLE GATT 和 Classic SPP，但连接失败：" +
                                "${result.error.message}。可以留在详情页手动选择 Classic UUID。"
                        },
                    )
                    else -> ConnectionDialog.Failure(
                        "自动连接未完成，请检查权限，以及目标设备的 GATT 或 SPP 服务。",
                    )
                }
            }
        }
    }

    fun sendCurrentValue() {
        if (displayMode == ConsoleDisplayMode.HEX) {
            viewModel.sendHex(sendValue) { inputError = it }
        } else {
            viewModel.sendText(sendValue) { inputError = it }
        }
    }

    fun connectSelectedUuid() {
        manualDisconnectRequested = false
        autoConnectionStarted = true
        connectionDialog = null
        viewModel.connect(address, selectedUuid) { result ->
            if (!manualDisconnectRequested) {
                connectionDialog = when (result) {
                    is BluetoothStatus.Connected -> ConnectionDialog.Success
                    is BluetoothStatus.Failed -> ConnectionDialog.Failure(result.error.message)
                    else -> ConnectionDialog.Failure("使用所选 UUID 连接未完成")
                }
            }
        }
    }

    LaunchedEffect(logPaused) {
        if (logPaused) pausedEntries = consoleEntries
    }

    LaunchedEffect(address, device.bondStatus, isConnected) {
        if (!isConnected &&
            device.transport == BluetoothTransport.CLASSIC &&
            device.bondStatus == BondStatus.NONE &&
            !pairingStarted
        ) {
            pairingStarted = true
            viewModel.pair(address)
        } else if (!isConnected && !autoConnectionStarted && !manualDisconnectRequested) {
            autoConnectionStarted = true
            discoverAndConnect()
        }
    }

    LaunchedEffect(status, pairingStarted, autoConnectionStarted) {
        if (
            pairingStarted &&
            !autoConnectionStarted &&
            !manualDisconnectRequested &&
            status is BluetoothStatus.Failed
        ) {
            connectionDialog = ConnectionDialog.Failure(
                (status as BluetoothStatus.Failed).error.message,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${device.address} · ${device.transport.label}",
                    color = SecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isConnected) {
                TextButton(
                    onClick = {
                        manualDisconnectRequested = true
                        connectionDialog = null
                        viewModel.disconnect()
                    },
                ) {
                    Text("断开")
                }
            }
        }

        val retryConnection = {
            manualDisconnectRequested = false
            autoConnectionStarted = true
            discoverAndConnect()
        }
        val clearConsole = {
            viewModel.clearConsole()
            pausedEntries = emptyList()
        }

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.38f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ConnectionSummaryCard(
                        connectedStatus = connectedStatus,
                        isConnected = isConnected,
                        isBusy = isBusy,
                        onRetry = retryConnection,
                    )
                    DisplayModeCard(displayMode) { displayMode = it }
                    ConnectionUuidCard(
                        connectedStatus = connectedStatus,
                        transport = device.transport,
                        hasNordicUart = hasNordicUart,
                        selectedUuid = selectedUuid,
                        choices = uuidChoices,
                        expanded = uuidExpanded,
                        isBusy = isBusy,
                        onExpandedChange = { uuidExpanded = it },
                        onSelect = {
                            viewModel.selectUuid(address, it)
                            uuidExpanded = false
                        },
                        onRefresh = { viewModel.refreshUuids(address) },
                        onConnect = ::connectSelectedUuid,
                    )
                }
                Column(
                    modifier = Modifier.weight(0.62f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RealtimeConsolePanel(
                        entries = visibleEntries,
                        mode = displayMode,
                        paused = logPaused,
                        onPausedChange = { logPaused = it },
                        onClear = clearConsole,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    CommandInputRow(
                        value = sendValue,
                        mode = displayMode,
                        error = inputError,
                        enabled = isConnected,
                        onValueChange = {
                            sendValue = it
                            inputError = null
                        },
                        onSend = ::sendCurrentValue,
                    )
                }
            }
        } else {
            ConnectionSummaryCard(
                connectedStatus = connectedStatus,
                isConnected = isConnected,
                isBusy = isBusy,
                onRetry = retryConnection,
            )
            DisplayModeCard(displayMode) { displayMode = it }
            RealtimeConsolePanel(
                entries = visibleEntries,
                mode = displayMode,
                paused = logPaused,
                onPausedChange = { logPaused = it },
                onClear = clearConsole,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            ConnectionUuidCard(
                connectedStatus = connectedStatus,
                transport = device.transport,
                hasNordicUart = hasNordicUart,
                selectedUuid = selectedUuid,
                choices = uuidChoices,
                expanded = uuidExpanded,
                isBusy = isBusy,
                onExpandedChange = { uuidExpanded = it },
                onSelect = {
                    viewModel.selectUuid(address, it)
                    uuidExpanded = false
                },
                onRefresh = { viewModel.refreshUuids(address) },
                onConnect = ::connectSelectedUuid,
            )
            CommandInputRow(
                value = sendValue,
                mode = displayMode,
                error = inputError,
                enabled = isConnected,
                onValueChange = {
                    sendValue = it
                    inputError = null
                },
                onSend = ::sendCurrentValue,
            )
        }
    }

    connectionDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(if (dialog is ConnectionDialog.Success) "连接成功" else "连接失败")
            },
            text = {
                Text(
                    when (dialog) {
                        ConnectionDialog.Success -> "已连接 ${device.name}，现在可以进行收发测试。"
                        is ConnectionDialog.Failure -> dialog.message
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        connectionDialog = null
                        if (dialog is ConnectionDialog.Failure) onBack()
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = if (dialog is ConnectionDialog.Failure) {
                {
                    TextButton(onClick = { connectionDialog = null }) {
                        Text("留在详情页")
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun ConnectionSummaryCard(
    connectedStatus: BluetoothStatus.Connected?,
    isConnected: Boolean,
    isBusy: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBackground,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        connectedStatus != null -> "已连接 · ${connectedStatus.mode.label}"
                        isBusy -> "正在自动识别并连接…"
                        else -> "未连接"
                    },
                    color = if (isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        SecondaryColor
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "自动匹配协议和 UUID，无需手动选择",
                    color = SecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!isConnected) {
                Button(onClick = onRetry, enabled = !isBusy) {
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun DisplayModeCard(
    mode: ConsoleDisplayMode,
    onModeChange: (ConsoleDisplayMode) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBackground,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("显示与发送模式", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = mode == ConsoleDisplayMode.UTF8,
                    onClick = { onModeChange(ConsoleDisplayMode.UTF8) },
                    label = { Text("UTF-8") },
                )
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = mode == ConsoleDisplayMode.HEX,
                    onClick = { onModeChange(ConsoleDisplayMode.HEX) },
                    label = { Text("HEX") },
                )
            }
        }
    }
}

@Composable
private fun ConnectionUuidCard(
    connectedStatus: BluetoothStatus.Connected?,
    transport: BluetoothTransport,
    hasNordicUart: Boolean,
    selectedUuid: UUID,
    choices: List<UUID>,
    expanded: Boolean,
    isBusy: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (UUID) -> Unit,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBackground,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                if (connectedStatus?.mode == BluetoothConnectionMode.BLE_GATT ||
                    hasNordicUart
                ) {
                    "写入特征 UUID"
                } else {
                    "串口 UUID"
                },
                color = SecondaryColor,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                connectedStatus?.uuid?.toString()
                    ?: if (hasNordicUart) NUS_WRITE_UUID.toString() else "连接后自动显示",
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            val gattProfile = connectedStatus?.gattProfile
            if (gattProfile != null) {
                Text(
                    "服务 ${gattProfile.serviceUuid}",
                    color = SecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                gattProfile.notifyCharacteristicUuid?.let { notifyUuid ->
                    Text(
                        "通知 $notifyUuid",
                        color = SecondaryColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (hasNordicUart) {
                Column {
                    Text(
                        "NUS 服务 $NUS_SERVICE_UUID",
                        color = SecondaryColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "写入 $NUS_WRITE_UUID",
                        color = SecondaryColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "通知 $NUS_NOTIFY_UUID",
                        color = SecondaryColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (transport != BluetoothTransport.LE) {
                Text(
                    "手动选择 Classic UUID（仅 RFCOMM 回退）",
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    color = SecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                )
                Box {
                    OutlinedButton(
                        onClick = { onExpandedChange(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (selectedUuid == SPP_UUID) {
                                "标准 SPP · $selectedUuid"
                            } else {
                                selectedUuid.toString()
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "选择 Classic UUID",
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { onExpandedChange(false) },
                    ) {
                        choices.forEach { uuid ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            if (uuid == SPP_UUID) {
                                                "标准 SPP UUID"
                                            } else {
                                                "设备服务 UUID"
                                            },
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            uuid.toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                onClick = { onSelect(uuid) },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onRefresh,
                        enabled = !isBusy,
                    ) {
                        Text("刷新列表")
                    }
                    Button(
                        onClick = onConnect,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (connectedStatus == null) "使用所选 UUID 连接" else "切换到所选 UUID")
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandInputRow(
    value: String,
    mode: ConsoleDisplayMode,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        if (mode == ConsoleDisplayMode.HEX) {
                            "输入 HEX，例如 AA 55"
                        } else {
                            "输入指令…"
                        },
                    )
                },
                isError = error != null,
            )
            Button(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
            ) {
                Text("发送")
            }
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private sealed interface ConnectionDialog {
    data object Success : ConnectionDialog
    data class Failure(val message: String) : ConnectionDialog
}

@Composable
private fun ProjectListScreen(
    viewModel: AppViewModel,
    runProject: (String) -> Unit,
    editProject: (String) -> Unit,
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val query by viewModel.projectQuery.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectEntity?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "项目",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setProjectQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                label = { Text("按项目名或设备实时过滤") },
            )
            Text(
                "${projects.size} 个匹配项目",
                color = SecondaryColor,
                style = MaterialTheme.typography.bodySmall,
            )
            if (projects.isEmpty()) {
                Text(
                    if (query.isBlank()) "暂无项目，点击 + 新建" else "没有匹配的项目",
                    modifier = Modifier.padding(top = 24.dp),
                    color = SecondaryColor,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(top = 10.dp),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(projects, key = ProjectEntity::id) { project ->
                        ProjectCard(
                            project = project,
                            onRun = { runProject(project.id) },
                            onEdit = { editProject(project.id) },
                            onRename = { renameTarget = project },
                            onDuplicate = { viewModel.duplicateProject(project.id) },
                            onDelete = { deleteTarget = project },
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd),
            containerColor = PrimaryBlue,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "新增项目")
        }
    }

    if (showCreateDialog) {
        ProjectNameDialog(
            title = "新增项目",
            initialValue = "",
            confirmLabel = "创建",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                viewModel.createProject(name)
            },
        )
    }
    renameTarget?.let { project ->
        ProjectNameDialog(
            title = "重命名项目",
            initialValue = project.name,
            confirmLabel = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                viewModel.renameProject(project.id, name)
            },
        )
    }
    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目？") },
            text = { Text("“${project.name}”及其本地布局数据将被永久删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        viewModel.deleteProject(project.id)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectEntity,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRun),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "绑定设备：${project.boundDeviceName ?: "未绑定"}",
                    color = SecondaryColor,
                )
                Text(
                    "本地修改：${formatProjectDate(project.updatedAt)}",
                    color = SecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "编辑组件",
                    tint = PrimaryBlue,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "项目操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Edit, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复制") },
                        leadingIcon = {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectNameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("项目名称") },
                supportingText = { Text("${value.trim().length}/30") },
                isError = value.trim().isEmpty() || value.trim().length > 30,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private val projectDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatProjectDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(projectDateFormatter)
