package com.kuma.bluetoothdebugassistant.project

import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothController
import com.kuma.bluetoothdebugassistant.bluetooth.BluetoothStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

const val LOGICAL_CANVAS_WIDTH = 360f
const val LOGICAL_CANVAS_HEIGHT = 640f
const val DEFAULT_GRID_SIZE = 10f
const val MIN_COMPONENT_WIDTH = 40f
const val MIN_COMPONENT_HEIGHT = 32f

@Serializable
data class ProjectLayout(
    val version: Int = 2,
    val canvasWidth: Float = LOGICAL_CANVAS_WIDTH,
    val canvasHeight: Float = LOGICAL_CANVAS_HEIGHT,
    val gridEnabled: Boolean = true,
    val gridSize: Float = DEFAULT_GRID_SIZE,
    val items: List<ProjectComponent> = emptyList(),
)

@Serializable
data class ProjectComponent(
    val id: String,
    val type: ComponentType,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float = 8f,
    val color: String = "#2F80ED",
    val name: String,
    val fontSize: Float = 14f,
    val zIndex: Int = 0,
    val commandMode: CommandMode = CommandMode.UTF8,
    val command: String = "",
    val appendNewline: Boolean = false,
    val confirmBeforeSend: Boolean = false,
    val repeatOnLongPress: Boolean = false,
    val repeatIntervalMs: Int = 250,
    val sliderMin: Float = 0f,
    val sliderMax: Float = 100f,
    val sliderInitial: Float = 0f,
    val commandTemplate: String = "{value}",
    val maxSendHz: Int = 10,
    val handleReturnToCenter: Boolean = true,
)

@Serializable
enum class ComponentType(val label: String) {
    BUTTON("按钮"),
    TEXT("文本"),
    SWITCH("开关"),
    SLIDER("滑动条"),
    HANDLE("手柄"),
}

@Serializable
enum class CommandMode {
    UTF8,
    HEX,
}

enum class EditorMode {
    EDIT,
    RUN,
}

object ProjectLayoutJson {
    val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(layout: ProjectLayout): String = format.encodeToString(layout)

    fun decode(value: String): ProjectLayout =
        format.decodeFromString<ProjectLayout>(value).normalized()
}

fun ProjectLayout.normalized(): ProjectLayout {
    val safeGrid = gridSize.coerceIn(2f, 100f)
    return copy(
        version = 2,
        canvasWidth = LOGICAL_CANVAS_WIDTH,
        canvasHeight = LOGICAL_CANVAS_HEIGHT,
        gridSize = safeGrid,
        items = items.map(ProjectComponent::constrained)
            .sortedBy(ProjectComponent::zIndex),
    )
}

fun ProjectComponent.constrained(
    canvasWidth: Float = LOGICAL_CANVAS_WIDTH,
    canvasHeight: Float = LOGICAL_CANVAS_HEIGHT,
): ProjectComponent {
    val minimum = minimumSize(type)
    val safeWidth = width.coerceIn(minimum.first, canvasWidth)
    val safeHeight = height.coerceIn(minimum.second, canvasHeight)
    return copy(
        x = x.coerceIn(0f, canvasWidth - safeWidth),
        y = y.coerceIn(0f, canvasHeight - safeHeight),
        width = safeWidth,
        height = safeHeight,
        cornerRadius = cornerRadius.coerceIn(0f, minOf(safeWidth, safeHeight) / 2f),
        fontSize = fontSize.coerceIn(8f, 48f),
        repeatIntervalMs = repeatIntervalMs.coerceIn(50, 5_000),
        maxSendHz = maxSendHz.coerceIn(1, 30),
    )
}

fun ProjectComponent.movedTo(
    newX: Float,
    newY: Float,
    snapToGrid: Boolean,
    gridSize: Float = DEFAULT_GRID_SIZE,
): ProjectComponent = copy(
    x = snap(newX, snapToGrid, gridSize),
    y = snap(newY, snapToGrid, gridSize),
).constrained()

fun ProjectComponent.resizedTo(
    newWidth: Float,
    newHeight: Float,
    snapToGrid: Boolean,
    gridSize: Float = DEFAULT_GRID_SIZE,
): ProjectComponent = copy(
    width = snap(newWidth, snapToGrid, gridSize),
    height = snap(newHeight, snapToGrid, gridSize),
).constrained()

fun snap(value: Float, enabled: Boolean, gridSize: Float = DEFAULT_GRID_SIZE): Float {
    if (!enabled || gridSize <= 0f) return value
    return (value / gridSize).roundToInt() * gridSize
}

fun minimumSize(type: ComponentType): Pair<Float, Float> = when (type) {
    ComponentType.HANDLE -> 72f to 72f
    ComponentType.SLIDER -> 100f to 40f
    else -> MIN_COMPONENT_WIDTH to MIN_COMPONENT_HEIGHT
}

fun newComponent(
    type: ComponentType,
    id: String,
    zIndex: Int,
): ProjectComponent {
    val (width, height) = when (type) {
        ComponentType.BUTTON -> 140f to 48f
        ComponentType.TEXT -> 140f to 40f
        ComponentType.SWITCH -> 140f to 48f
        ComponentType.SLIDER -> 240f to 56f
        ComponentType.HANDLE -> 140f to 140f
    }
    return ProjectComponent(
        id = id,
        type = type,
        x = (LOGICAL_CANVAS_WIDTH - width) / 2f,
        y = 30f,
        width = width,
        height = height,
        name = type.label,
        zIndex = zIndex,
    )
}

object CommandEncoder {
    fun encode(
        mode: CommandMode,
        command: String,
        appendNewline: Boolean,
    ): ByteArray = when (mode) {
        CommandMode.UTF8 -> (command + if (appendNewline) "\n" else "").encodeToByteArray()
        CommandMode.HEX -> parseHex(command)
    }.also { require(it.isNotEmpty()) { "发送内容不能为空" } }

    fun parseHex(value: String): ByteArray {
        val tokens = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        require(tokens.isNotEmpty()) { "HEX 不能为空" }
        val normalized = if (tokens.size == 1 && !tokens.single().startsWith("0x", true)) {
            tokens.single()
        } else {
            tokens.joinToString("") { token ->
                if (token.startsWith("0x", true)) token.drop(2) else token
            }
        }
        require(normalized.length % 2 == 0) { "HEX 必须包含偶数个字符" }
        require(normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "HEX 包含非法字符"
        }
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

class SendThrottle(maxHz: Int, private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val intervalMillis = 1_000L / maxHz.coerceIn(1, 30)
    private var lastSentAt = Long.MIN_VALUE

    fun shouldSend(): Boolean {
        val now = nowMillis()
        if (lastSentAt != Long.MIN_VALUE && now - lastSentAt < intervalMillis) return false
        lastSentAt = now
        return true
    }

    fun reset() {
        lastSentAt = Long.MIN_VALUE
    }
}

class ProjectCommandSender(private val controller: BluetoothController) {
    suspend fun send(
        mode: EditorMode,
        status: BluetoothStatus,
        component: ProjectComponent,
        commandOverride: String = component.command,
    ): Boolean {
        if (mode != EditorMode.RUN || status !is BluetoothStatus.Connected) return false
        controller.send(
            CommandEncoder.encode(
                mode = component.commandMode,
                command = commandOverride,
                appendNewline = component.appendNewline,
            ),
        )
        return true
    }
}
