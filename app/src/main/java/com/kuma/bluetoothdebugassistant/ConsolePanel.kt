package com.kuma.bluetoothdebugassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kuma.bluetoothdebugassistant.ui.theme.CardBackground
import com.kuma.bluetoothdebugassistant.ui.theme.SecondaryColor

private val TerminalBackground = Color(0xFF1E293B)
private val TerminalText = Color(0xFFE2E8F0)
private val TerminalRx = Color(0xFF60A5FA)
private val TerminalTx = Color(0xFF34D399)

@Composable
fun RealtimeConsolePanel(
    entries: List<ConsoleEntry>,
    mode: ConsoleDisplayMode,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = CardBackground,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "实时日志",
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { onPausedChange(!paused) }) {
                    Icon(
                        if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = null,
                    )
                    Text(if (paused) "继续" else "暂停")
                }
                TextButton(onClick = onClear) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                    Text("清空")
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = TerminalBackground,
                shape = RoundedCornerShape(12.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(
                        if (paused) "日志已暂停" else "等待接收或发送数据…",
                        modifier = Modifier.padding(14.dp),
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        reverseLayout = true,
                    ) {
                        itemsIndexed(
                            entries.asReversed(),
                            key = { index, item ->
                                "${item.timestampMillis}-${item.direction}-$index"
                            },
                        ) { _, entry ->
                            Text(
                                "[${formatConsoleTime(entry.timestampMillis)}] " +
                                    "${if (entry.direction == ConsoleDirection.RX) "↓" else "↑"} " +
                                    entry.display(mode),
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = if (entry.direction == ConsoleDirection.RX) {
                                    TerminalRx
                                } else {
                                    TerminalTx
                                },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RxTxConsole(
    entries: List<ConsoleEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(ConsoleDisplayMode.UTF8) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CardBackground,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "RX/TX 控制台（${entries.size}/$MAX_CONSOLE_ENTRIES）",
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (expanded) {
                    FilterChip(
                        selected = mode == ConsoleDisplayMode.UTF8,
                        onClick = { mode = ConsoleDisplayMode.UTF8 },
                        label = { Text("UTF-8") },
                    )
                    FilterChip(
                        selected = mode == ConsoleDisplayMode.HEX,
                        onClick = { mode = ConsoleDisplayMode.HEX },
                        label = { Text("HEX") },
                    )
                    TextButton(onClick = onClear) { Text("清空") }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowDown
                        else Icons.Outlined.KeyboardArrowUp,
                        contentDescription = if (expanded) "折叠控制台" else "展开控制台",
                    )
                }
            }
            if (expanded) {
                if (entries.isEmpty()) {
                    Text(
                        "暂无收发记录",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        color = SecondaryColor,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .padding(horizontal = 12.dp),
                        reverseLayout = true,
                    ) {
                        itemsIndexed(
                            entries.asReversed(),
                            key = { index, item -> "${item.timestampMillis}-${item.direction}-$index" },
                        ) { _, entry ->
                            Text(
                                "${formatConsoleTime(entry.timestampMillis)} " +
                                    "${entry.direction.name}  ${entry.display(mode)}",
                                modifier = Modifier.padding(vertical = 2.dp),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
