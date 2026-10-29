package com.kuma.bluetoothdebugassistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothDeviceModel
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothStatus
import com.kuma.bluetoothdebugassistant.project.CommandEncoder
import com.kuma.bluetoothdebugassistant.project.CommandMode
import com.kuma.bluetoothdebugassistant.project.ComponentType
import com.kuma.bluetoothdebugassistant.project.DEFAULT_GRID_SIZE
import com.kuma.bluetoothdebugassistant.project.EditorMode
import com.kuma.bluetoothdebugassistant.project.LOGICAL_CANVAS_HEIGHT
import com.kuma.bluetoothdebugassistant.project.LOGICAL_CANVAS_WIDTH
import com.kuma.bluetoothdebugassistant.project.ProjectComponent
import com.kuma.bluetoothdebugassistant.project.ProjectLayout
import com.kuma.bluetoothdebugassistant.project.ProjectLayoutJson
import com.kuma.bluetoothdebugassistant.project.SendThrottle
import com.kuma.bluetoothdebugassistant.project.constrained
import com.kuma.bluetoothdebugassistant.project.movedTo
import com.kuma.bluetoothdebugassistant.project.newComponent
import com.kuma.bluetoothdebugassistant.project.resizedTo
import com.kuma.bluetoothdebugassistant.ui.theme.AppBackground
import com.kuma.bluetoothdebugassistant.ui.theme.BorderColor
import com.kuma.bluetoothdebugassistant.ui.theme.CardBackground
import com.kuma.bluetoothdebugassistant.ui.theme.PrimaryBlue
import com.kuma.bluetoothdebugassistant.ui.theme.SecondaryColor
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

@Composable
fun ProjectEditorScreen(
    id: String,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val project by viewModel.observeProject(id).collectAsStateWithLifecycle(initialValue = null)
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    var layout by remember(id) { mutableStateOf<ProjectLayout?>(null) }
    var revision by remember(id) { mutableIntStateOf(0) }
    var selectedId by remember(id) { mutableStateOf<String?>(null) }
    var showProperties by remember { mutableStateOf(false) }
    var addExpanded by remember { mutableStateOf(false) }
    var deviceExpanded by remember { mutableStateOf(false) }
    val repeatRegistry = remember { RepeatSendRegistry() }

    LaunchedEffect(project?.id) {
        if (layout == null && project != null) {
            layout = runCatching { ProjectLayoutJson.decode(project!!.layoutJson) }
                .getOrElse { ProjectLayout() }
        }
    }
    LaunchedEffect(revision) {
        if (revision > 0) {
            delay(700)
            layout?.let { viewModel.updateLayout(id, it) }
        }
    }
    DisposableEffect(Unit) {
        onDispose { repeatRegistry.cancelAll() }
    }

    fun updateLayout(transform: (ProjectLayout) -> ProjectLayout) {
        layout = layout?.let(transform)
        revision += 1
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "返回",
                                tint = PrimaryBlue,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            project?.name ?: "项目编辑器",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "组件编辑 · 360 × 640 逻辑画布",
                            color = SecondaryColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { layout?.let { viewModel.updateLayout(id, it, true) } },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("保存")
                    }
                }
            }

            EditorConnectionRow(
                projectDeviceName = project?.boundDeviceName,
                projectDeviceAddress = project?.boundDeviceAddress,
                status = status,
                devices = devices,
                expanded = deviceExpanded,
                onExpandedChange = { deviceExpanded = it },
                onBind = { viewModel.bindProjectDevice(id, it) },
                onScan = viewModel::startScan,
                onConnect = { address -> viewModel.discoverAndConnect(address) },
                onDisconnect = viewModel::disconnect,
            )

            if (layout != null) {
                EditorToolbar(
                    layout = layout!!,
                    selected = layout!!.items.firstOrNull { it.id == selectedId },
                    onGridChanged = { enabled ->
                        updateLayout { it.copy(gridEnabled = enabled) }
                    },
                    onEdit = { showProperties = selectedId != null },
                    onDelete = {
                        updateLayout { current ->
                            current.copy(items = current.items.filterNot { it.id == selectedId })
                        }
                        selectedId = null
                    },
                )
            }

            val currentLayout = layout
            if (project == null || currentLayout == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (project == null) "正在加载项目…" else "正在加载布局…")
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("画布", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        layout!!.items.firstOrNull { it.id == selectedId }?.name
                            ?.let { "已选择：$it" }
                            ?: "点击组件编辑属性",
                        color = SecondaryColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ProjectCanvas(
                    modifier = Modifier.weight(1f),
                    layout = currentLayout,
                    mode = EditorMode.EDIT,
                    selectedId = selectedId,
                    connected = false,
                    repeatRegistry = repeatRegistry,
                    onSelected = { componentId ->
                        selectedId = componentId
                        showProperties = true
                    },
                    onComponentChanged = { changed ->
                        updateLayout { current ->
                            current.copy(
                                items = current.items.map {
                                    if (it.id == changed.id) changed else it
                                },
                            )
                        }
                    },
                    onSend = { _, _ -> },
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            FloatingActionButton(
                onClick = { addExpanded = true },
                containerColor = PrimaryBlue,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加组件", tint = Color.White)
            }
            DropdownMenu(
                expanded = addExpanded,
                onDismissRequest = { addExpanded = false },
            ) {
                ComponentType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        onClick = {
                            addExpanded = false
                            val current = layout ?: return@DropdownMenuItem
                            val component = newComponent(
                                type = type,
                                id = UUID.randomUUID().toString(),
                                zIndex = (current.items.maxOfOrNull { it.zIndex } ?: -1) + 1,
                            )
                            updateLayout { it.copy(items = it.items + component) }
                            selectedId = component.id
                            showProperties = true
                        },
                    )
                }
            }
        }
    }

    val selected = layout?.items?.firstOrNull { it.id == selectedId }
    if (showProperties && selected != null) {
        ComponentPropertiesSheet(
            component = selected,
            onDismiss = { showProperties = false },
            onSave = { changed ->
                updateLayout { current ->
                    current.copy(
                        items = current.items.map {
                            if (it.id == changed.id) changed.constrained() else it
                        },
                    )
                }
                showProperties = false
            },
        )
    }

}

@Composable
fun ProjectRuntimeScreen(
    id: String,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val project by viewModel.observeProject(id).collectAsStateWithLifecycle(initialValue = null)
    val status by viewModel.status.collectAsStateWithLifecycle()
    var layout by remember(id) { mutableStateOf<ProjectLayout?>(null) }
    var connectionStarted by remember(id) { mutableStateOf(false) }
    var wasConnected by remember(id) { mutableStateOf(false) }
    var errorMessage by remember(id) { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var pendingConfirmation by remember { mutableStateOf<PendingSend?>(null) }
    val repeatRegistry = remember { RepeatSendRegistry() }
    val connectedStatus = status as? BluetoothStatus.Connected
    val connected = connectedStatus?.address == project?.boundDeviceAddress

    fun disconnectAndExit() {
        repeatRegistry.cancelAll()
        viewModel.disconnect()
        onBack()
    }

    BackHandler(onBack = ::disconnectAndExit)

    LaunchedEffect(project?.id) {
        val currentProject = project ?: return@LaunchedEffect
        layout = runCatching { ProjectLayoutJson.decode(currentProject.layoutJson) }
            .getOrElse { ProjectLayout() }
        if (connectionStarted) return@LaunchedEffect
        val address = currentProject.boundDeviceAddress
        if (address == null) {
            errorMessage = "项目尚未绑定设备，请先点击铅笔图标进入编辑页面并绑定设备。"
            return@LaunchedEffect
        }
        if ((status as? BluetoothStatus.Connected)?.address == address) {
            connectionStarted = true
            wasConnected = true
            return@LaunchedEffect
        }
        connectionStarted = true
        viewModel.discoverAndConnect(address) { result ->
            when (result) {
                is BluetoothStatus.Connected -> wasConnected = true
                is BluetoothStatus.Failed -> errorMessage = result.error.message
                else -> errorMessage = "自动连接未完成，请检查设备的 BLE GATT 或 Classic SPP 服务。"
            }
        }
    }

    LaunchedEffect(status, wasConnected) {
        if (wasConnected && status is BluetoothStatus.Failed) {
            errorMessage = (status as BluetoothStatus.Failed).error.message
            repeatRegistry.cancelAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose { repeatRegistry.cancelAll() }
    }

    fun requestSend(component: ProjectComponent, command: String = component.command) {
        if (!connected) {
            sendError = "蓝牙未连接，命令未发送。"
            return
        }
        if (component.confirmBeforeSend) {
            pendingConfirmation = PendingSend(component, command)
        } else {
            viewModel.sendProjectCommand(EditorMode.RUN, component, command) {
                sendError = it
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = if (connected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    when (status) {
                        is BluetoothStatus.Connecting -> "正在连接…"
                        is BluetoothStatus.Connected -> "已连接"
                        else -> "未连接"
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                project?.name ?: "项目运行",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            TextButton(onClick = ::disconnectAndExit) {
                Text("断开连接")
            }
        }

        val currentLayout = layout
        if (project == null || currentLayout == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载项目…")
            }
        } else {
            ProjectCanvas(
                modifier = Modifier.weight(1f),
                layout = currentLayout,
                mode = EditorMode.RUN,
                selectedId = null,
                connected = connected,
                repeatRegistry = repeatRegistry,
                onSelected = {},
                onComponentChanged = {},
                onSend = ::requestSend,
            )
        }

        sendError?.let { error ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                onClick = { sendError = null },
            ) {
                Text(error, modifier = Modifier.padding(10.dp))
            }
        }
    }

    pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text("确认发送？") },
            text = { Text("${pending.component.name}\n${pending.command}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirmation = null
                        viewModel.sendProjectCommand(
                            EditorMode.RUN,
                            pending.component,
                            pending.command,
                        ) { sendError = it }
                    },
                ) { Text("发送") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) { Text("取消") }
            },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("连接失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        errorMessage = null
                        viewModel.disconnect()
                        onBack()
                    },
                ) { Text("确定") }
            },
        )
    }
}

private data class PendingSend(
    val component: ProjectComponent,
    val command: String,
)

@Composable
private fun EditorConnectionRow(
    projectDeviceName: String?,
    projectDeviceAddress: String?,
    status: BluetoothStatus,
    devices: List<BluetoothDeviceModel>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onBind: (BluetoothDeviceModel?) -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = status as? BluetoothStatus.Connected
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Bluetooth,
                    contentDescription = null,
                    tint = PrimaryBlue,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("绑定设备", fontWeight = FontWeight.SemiBold)
                    Text(
                        projectDeviceAddress ?: "选择运行项目时自动连接的设备",
                        color = SecondaryColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Surface(
                    color = if (connected != null) {
                        Color(0xFFE8F8EE)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        if (connected != null) "已连接" else "未连接",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        color = if (connected != null) Color(0xFF15803D) else SecondaryColor,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { onExpandedChange(true) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            projectDeviceName ?: "选择绑定设备",
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { onExpandedChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text("不绑定设备") },
                            onClick = {
                                onExpandedChange(false)
                                onBind(null)
                            },
                        )
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text("${device.name}\n${device.address}") },
                                onClick = {
                                    onExpandedChange(false)
                                    onBind(device)
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onScan,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("扫描")
                }
                if (connected != null) {
                    TextButton(onClick = onDisconnect) { Text("断开") }
                } else if (projectDeviceAddress != null) {
                    TextButton(onClick = { onConnect(projectDeviceAddress) }) { Text("连接") }
                }
            }
        }
    }
}

@Composable
private fun EditorToolbar(
    layout: ProjectLayout,
    selected: ProjectComponent?,
    onGridChanged: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = layout.gridEnabled,
                onCheckedChange = onGridChanged,
            )
            Column {
                Text("网格吸附", fontWeight = FontWeight.Medium)
                Text(
                    "${layout.gridSize.roundToInt()} × ${layout.gridSize.roundToInt()}",
                    color = SecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            if (selected != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        selected.type.label,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        color = PrimaryBlue,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            IconButton(onClick = onEdit, enabled = selected != null) {
                Icon(Icons.Outlined.Settings, contentDescription = "属性")
            }
            IconButton(onClick = onDelete, enabled = selected != null) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = if (selected != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        SecondaryColor.copy(alpha = 0.4f)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProjectCanvas(
    modifier: Modifier = Modifier,
    layout: ProjectLayout,
    mode: EditorMode,
    selectedId: String?,
    connected: Boolean,
    repeatRegistry: RepeatSendRegistry,
    onSelected: (String) -> Unit,
    onComponentChanged: (ProjectComponent) -> Unit,
    onSend: (ProjectComponent, String) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFEEF2F7))
            .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val scale = min(
            maxWidth.value / LOGICAL_CANVAS_WIDTH,
            maxHeight.value / LOGICAL_CANVAS_HEIGHT,
        ).coerceAtLeast(0.1f)
        val width = (LOGICAL_CANVAS_WIDTH * scale).dp
        val height = (LOGICAL_CANVAS_HEIGHT * scale).dp
        Box(
            modifier = Modifier
                .size(width, height)
                .clip(RoundedCornerShape(14.dp))
                .background(CardBackground)
                .border(1.dp, BorderColor, RoundedCornerShape(14.dp)),
        ) {
            if (mode == EditorMode.EDIT && layout.gridEnabled) {
                Canvas(Modifier.fillMaxSize()) {
                    val pixelScale = size.width / LOGICAL_CANVAS_WIDTH
                    var x = layout.gridSize
                    while (x < LOGICAL_CANVAS_WIDTH) {
                        drawLine(
                            BorderColor.copy(alpha = 0.55f),
                            Offset(x * pixelScale, 0f),
                            Offset(x * pixelScale, size.height),
                            strokeWidth = 1f,
                        )
                        x += layout.gridSize
                    }
                    var y = layout.gridSize
                    while (y < LOGICAL_CANVAS_HEIGHT) {
                        drawLine(
                            BorderColor.copy(alpha = 0.55f),
                            Offset(0f, y * pixelScale),
                            Offset(size.width, y * pixelScale),
                            strokeWidth = 1f,
                        )
                        y += layout.gridSize
                    }
                }
            }
            layout.items.sortedBy { it.zIndex }.forEach { component ->
                LayoutComponent(
                    component = component,
                    scale = scale,
                    mode = mode,
                    selected = selectedId == component.id,
                    connected = connected,
                    gridEnabled = layout.gridEnabled,
                    gridSize = layout.gridSize,
                    repeatRegistry = repeatRegistry,
                    onSelected = { onSelected(component.id) },
                    onChanged = onComponentChanged,
                    onSend = onSend,
                )
            }
        }
    }
}

@Composable
private fun LayoutComponent(
    component: ProjectComponent,
    scale: Float,
    mode: EditorMode,
    selected: Boolean,
    connected: Boolean,
    gridEnabled: Boolean,
    gridSize: Float,
    repeatRegistry: RepeatSendRegistry,
    onSelected: () -> Unit,
    onChanged: (ProjectComponent) -> Unit,
    onSend: (ProjectComponent, String) -> Unit,
) {
    val density = LocalDensity.current.density
    val currentComponent by rememberUpdatedState(component)
    val shape = RoundedCornerShape((component.cornerRadius * scale).dp)
    var modifier = Modifier
        .offset(
            x = (component.x * scale).dp,
            y = (component.y * scale).dp,
        )
        .size(
            width = (component.width * scale).dp,
            height = (component.height * scale).dp,
        )
        .clip(shape)

    if (mode == EditorMode.EDIT) {
        modifier = modifier.pointerInput(component.id, scale, gridEnabled, gridSize, selected) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val resizeHandlePixels = 22f * density
                if (
                    selected &&
                    down.position.x >= size.width - resizeHandlePixels &&
                    down.position.y >= size.height - resizeHandlePixels
                ) {
                    return@awaitEachGesture
                }
                val startComponent = currentComponent
                val pointerId = down.id
                var total = Offset.Zero
                var moved = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!change.pressed) break
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) {
                        moved = moved || total.getDistance() > 3f || delta.getDistance() > 3f
                        total += delta
                        onChanged(
                            startComponent.movedTo(
                                startComponent.x + total.x / density / scale,
                                startComponent.y + total.y / density / scale,
                                gridEnabled,
                                gridSize,
                            ),
                        )
                        change.consume()
                    }
                }
                if (!moved) onSelected()
            }
        }
    }

    Box(modifier) {
        ComponentContent(
            component = component,
            mode = mode,
            connected = connected,
            repeatRegistry = repeatRegistry,
            onSend = onSend,
        )
        if (mode == EditorMode.EDIT && selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(2.dp, PrimaryBlue, shape),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .background(PrimaryBlue, RoundedCornerShape(topStart = 6.dp))
                    .pointerInput(component.id, scale, gridEnabled, gridSize) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startComponent = currentComponent
                            val pointerId = down.id
                            var total = Offset.Zero
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) break
                                total += change.positionChange()
                                onChanged(
                                    startComponent.resizedTo(
                                        startComponent.width + total.x / density / scale,
                                        startComponent.height + total.y / density / scale,
                                        gridEnabled,
                                        gridSize,
                                    ),
                                )
                                change.consume()
                            }
                        }
                    },
            )
        }
    }
}

@Composable
private fun ComponentContent(
    component: ProjectComponent,
    mode: EditorMode,
    connected: Boolean,
    repeatRegistry: RepeatSendRegistry,
    onSend: (ProjectComponent, String) -> Unit,
) {
    val color = parseColor(component.color)
    when (component.type) {
        ComponentType.BUTTON -> RuntimeButton(
            component = component,
            enabled = mode == EditorMode.RUN && connected,
            repeatRegistry = repeatRegistry,
            onSend = { onSend(component, component.command) },
        )
        ComponentType.TEXT -> Box(
            Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                component.name,
                color = color,
                fontSize = component.fontSize.sp,
                textAlign = TextAlign.Center,
            )
        }
        ComponentType.SWITCH -> {
            var checked by remember(component.id) { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(component.name, modifier = Modifier.weight(1f), fontSize = component.fontSize.sp)
                Switch(
                    checked = checked,
                    onCheckedChange = if (mode == EditorMode.RUN && connected) {
                        {
                            checked = it
                            onSend(
                                component,
                                component.commandTemplate.replace("{value}", if (it) "1" else "0"),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        ComponentType.SLIDER -> RuntimeSlider(component, mode, connected, onSend)
        ComponentType.HANDLE -> RuntimeHandle(component, mode, connected, onSend)
    }
}

@Composable
private fun RuntimeButton(
    component: ProjectComponent,
    enabled: Boolean,
    repeatRegistry: RepeatSendRegistry,
    onSend: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val background = parseColor(component.color)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (enabled) background else background.copy(alpha = 0.45f))
            .then(
                if (enabled) {
                    Modifier.pointerInput(component) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pointerId = down.id
                            var repeated = false
                            var inside = true
                            val repeatJob = if (
                                component.repeatOnLongPress && !component.confirmBeforeSend
                            ) {
                                scope.launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    repeated = true
                                    while (isActive) {
                                        onSend()
                                        delay(component.repeatIntervalMs.toLong())
                                    }
                                }.also { repeatRegistry.register(component.id, it) }
                            } else {
                                null
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes
                                        .firstOrNull { it.id == pointerId } ?: break
                                    inside = change.position.isInside(size.width, size.height)
                                    if (!inside || !change.pressed) break
                                    change.consume()
                                }
                                if (inside && !repeated) onSend()
                            } finally {
                                repeatJob?.cancel()
                                repeatRegistry.cancel(component.id)
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            component.name,
            color = Color.White,
            fontSize = component.fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RuntimeSlider(
    component: ProjectComponent,
    mode: EditorMode,
    connected: Boolean,
    onSend: (ProjectComponent, String) -> Unit,
) {
    val safeMin = minOf(component.sliderMin, component.sliderMax)
    val safeMax = maxOf(component.sliderMin, component.sliderMax).let {
        if (it == safeMin) safeMin + 1f else it
    }
    var value by remember(component.id) {
        mutableFloatStateOf(component.sliderInitial.coerceIn(safeMin, safeMax))
    }
    val throttle = remember(component.id, component.maxSendHz) {
        SendThrottle(component.maxSendHz)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${component.name} ${formatControlValue(value)}", fontSize = component.fontSize.sp)
        Slider(
            value = value,
            onValueChange = { changed ->
                value = changed
                if (throttle.shouldSend()) {
                    onSend(
                        component,
                        component.commandTemplate.replace(
                            "{value}",
                            formatControlValue(changed),
                        ),
                    )
                }
            },
            enabled = mode == EditorMode.RUN && connected,
            valueRange = safeMin..safeMax,
        )
    }
}

@Composable
private fun RuntimeHandle(
    component: ProjectComponent,
    mode: EditorMode,
    connected: Boolean,
    onSend: (ProjectComponent, String) -> Unit,
) {
    var handle by remember(component.id) { mutableStateOf(Offset.Zero) }
    val throttle = remember(component.id, component.maxSendHz) {
        SendThrottle(component.maxSendHz)
    }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(parseColor(component.color).copy(alpha = 0.12f))
            .then(
                if (mode == EditorMode.RUN && connected) {
                    Modifier.pointerInput(component) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val pointerId = down.id
                            fun update(position: Offset, force: Boolean = false) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val radius = min(size.width, size.height) / 2f
                                val raw = (position - center) / radius
                                val length = raw.getDistance()
                                handle = if (length > 1f) raw / length else raw
                                if (force || throttle.shouldSend()) {
                                    onSend(component, component.handleCommand(handle))
                                }
                            }
                            update(down.position, force = true)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes
                                        .firstOrNull { it.id == pointerId } ?: break
                                    if (
                                        !change.pressed ||
                                        !change.position.isInside(size.width, size.height)
                                    ) break
                                    update(change.position)
                                    change.consume()
                                }
                            } finally {
                                if (component.handleReturnToCenter) {
                                    handle = Offset.Zero
                                    onSend(component, component.handleCommand(handle))
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.42f
        drawCircle(parseColor(component.color), radius, center, style = Stroke(3f))
        drawLine(
            parseColor(component.color).copy(alpha = 0.35f),
            Offset(center.x - radius, center.y),
            Offset(center.x + radius, center.y),
        )
        drawLine(
            parseColor(component.color).copy(alpha = 0.35f),
            Offset(center.x, center.y - radius),
            Offset(center.x, center.y + radius),
        )
        drawCircle(
            parseColor(component.color),
            radius * 0.23f,
            center + Offset(handle.x * radius, handle.y * radius),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentPropertiesSheet(
    component: ProjectComponent,
    onDismiss: () -> Unit,
    onSave: (ProjectComponent) -> Unit,
) {
    var draft by remember(component.id) { mutableStateOf(component) }
    var colorText by remember(component.id) { mutableStateOf(component.color) }
    var colorExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CardBackground,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(parseColor(colorText), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            component.type.label.take(1),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "${component.type.label}属性",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "调整布局、外观与发送行为",
                            color = SecondaryColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("布局", fontWeight = FontWeight.Bold)
            PropertyNumberRow(
                firstLabel = "X",
                firstValue = draft.x,
                onFirst = { draft = draft.copy(x = it) },
                secondLabel = "Y",
                secondValue = draft.y,
                onSecond = { draft = draft.copy(y = it) },
            )
            PropertyNumberRow(
                firstLabel = "宽度",
                firstValue = draft.width,
                onFirst = { draft = draft.copy(width = it) },
                secondLabel = "高度",
                secondValue = draft.height,
                onSecond = { draft = draft.copy(height = it) },
            )
            PropertyNumberRow(
                firstLabel = "圆角",
                firstValue = draft.cornerRadius,
                onFirst = { draft = draft.copy(cornerRadius = it) },
                secondLabel = "字号",
                secondValue = draft.fontSize,
                onSecond = { draft = draft.copy(fontSize = it) },
            )
            Text("外观", fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(
                    onClick = { colorExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(parseColor(colorText), CircleShape)
                            .border(1.dp, BorderColor, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        DEFAULT_COMPONENT_COLORS
                            .firstOrNull { it.hex.equals(colorText, ignoreCase = true) }
                            ?.name
                            ?: "自定义颜色",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                    )
                    Text(colorText.uppercase(), color = SecondaryColor)
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "选择颜色")
                }
                DropdownMenu(
                    expanded = colorExpanded,
                    onDismissRequest = { colorExpanded = false },
                ) {
                    DEFAULT_COMPONENT_COLORS.forEach { option ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(parseColor(option.hex), CircleShape),
                                )
                            },
                            text = {
                                Column {
                                    Text(option.name)
                                    Text(
                                        option.hex,
                                        color = SecondaryColor,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            onClick = {
                                colorText = option.hex
                                colorExpanded = false
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = colorText,
                    onValueChange = { colorText = it },
                    label = { Text("颜色 HEX") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.zIndex.toString(),
                    onValueChange = { it.toIntOrNull()?.let { value -> draft = draft.copy(zIndex = value) } },
                    label = { Text("层级") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            if (component.type != ComponentType.TEXT) {
                HorizontalDivider()
                Text("命令", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommandMode.entries.forEach { commandMode ->
                        FilterChip(
                            selected = draft.commandMode == commandMode,
                            onClick = { draft = draft.copy(commandMode = commandMode) },
                            label = { Text(commandMode.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.command,
                    onValueChange = { draft = draft.copy(command = it) },
                    label = { Text("命令文本") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                PropertyCheck(
                    "UTF8 追加换行",
                    draft.appendNewline,
                ) { draft = draft.copy(appendNewline = it) }
                PropertyCheck(
                    "发送前确认",
                    draft.confirmBeforeSend,
                ) { draft = draft.copy(confirmBeforeSend = it) }
            }

            if (component.type == ComponentType.BUTTON) {
                PropertyCheck(
                    "长按重复发送",
                    draft.repeatOnLongPress,
                ) { draft = draft.copy(repeatOnLongPress = it) }
                OutlinedTextField(
                    value = draft.repeatIntervalMs.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { value ->
                            draft = draft.copy(repeatIntervalMs = value)
                        }
                    },
                    label = { Text("重复间隔 ms（50..5000）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            if (component.type == ComponentType.SLIDER || component.type == ComponentType.HANDLE ||
                component.type == ComponentType.SWITCH
            ) {
                OutlinedTextField(
                    value = draft.commandTemplate,
                    onValueChange = { draft = draft.copy(commandTemplate = it) },
                    label = {
                        Text(
                            if (component.type == ComponentType.HANDLE) {
                                "模板（{x}、{y}）"
                            } else {
                                "模板（{value}）"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (component.type == ComponentType.SLIDER) {
                PropertyNumberRow(
                    firstLabel = "最小值",
                    firstValue = draft.sliderMin,
                    onFirst = { draft = draft.copy(sliderMin = it) },
                    secondLabel = "最大值",
                    secondValue = draft.sliderMax,
                    onSecond = { draft = draft.copy(sliderMax = it) },
                )
                OutlinedTextField(
                    value = draft.sliderInitial.toString(),
                    onValueChange = {
                        it.toFloatOrNull()?.let { value -> draft = draft.copy(sliderInitial = value) }
                    },
                    label = { Text("初始值") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (component.type == ComponentType.SLIDER || component.type == ComponentType.HANDLE) {
                OutlinedTextField(
                    value = draft.maxSendHz.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { value -> draft = draft.copy(maxSendHz = value) }
                    },
                    label = { Text("最大发送频率 Hz（1..30）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            if (component.type == ComponentType.HANDLE) {
                PropertyCheck(
                    "抬起回中并发送",
                    draft.handleReturnToCenter,
                ) { draft = draft.copy(handleReturnToCenter = it) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    onClick = {
                        val normalizedColor = colorText.trim().uppercase()
                        if (!normalizedColor.matches(Regex("^#[0-9A-F]{6}([0-9A-F]{2})?$"))) {
                            error = "颜色格式应为 #RRGGBB 或 #AARRGGBB"
                            return@Button
                        }
                        if (draft.commandMode == CommandMode.HEX && draft.command.isNotBlank()) {
                            val hexError = runCatching {
                                CommandEncoder.parseHex(draft.command)
                            }.exceptionOrNull()
                            if (hexError != null) {
                                error = hexError.message
                                return@Button
                            }
                        }
                        onSave(draft.copy(color = normalizedColor))
                    },
                ) { Text("应用") }
            }
        }
    }
}

private data class ComponentColorOption(
    val name: String,
    val hex: String,
)

private val DEFAULT_COMPONENT_COLORS = listOf(
    ComponentColorOption("蓝色", "#2F80ED"),
    ComponentColorOption("绿色", "#22C55E"),
    ComponentColorOption("橙色", "#F59E0B"),
    ComponentColorOption("红色", "#EF4444"),
    ComponentColorOption("紫色", "#8B5CF6"),
    ComponentColorOption("青色", "#06B6D4"),
    ComponentColorOption("深灰", "#1F2937"),
)

@Composable
private fun PropertyNumberRow(
    firstLabel: String,
    firstValue: Float,
    onFirst: (Float) -> Unit,
    secondLabel: String,
    secondValue: Float,
    onSecond: (Float) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = formatControlValue(firstValue),
            onValueChange = { it.toFloatOrNull()?.let(onFirst) },
            label = { Text(firstLabel) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        OutlinedTextField(
            value = formatControlValue(secondValue),
            onValueChange = { it.toFloatOrNull()?.let(onSecond) },
            label = { Text(secondLabel) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
    }
}

@Composable
private fun PropertyCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

private class RepeatSendRegistry {
    private val jobs = mutableMapOf<String, Job>()

    fun register(id: String, job: Job) {
        jobs.remove(id)?.cancel()
        jobs[id] = job
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }

    fun cancelAll() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }
}

private fun ProjectComponent.handleCommand(value: Offset): String =
    commandTemplate
        .replace("{x}", formatControlValue(value.x))
        .replace("{y}", formatControlValue(-value.y))

private fun formatControlValue(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString()
    else "%.2f".format(value)

private fun parseColor(value: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(PrimaryBlue)

private fun Offset.isInside(width: Int, height: Int): Boolean =
    x in 0f..width.toFloat() && y in 0f..height.toFloat()
