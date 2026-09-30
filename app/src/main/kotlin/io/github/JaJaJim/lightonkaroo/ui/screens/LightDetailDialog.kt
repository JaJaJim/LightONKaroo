package io.github.JaJaJim.lightonkaroo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.JaJaJim.lightonkaroo.DiscoveredLight
import io.github.JaJaJim.lightonkaroo.data.LightAssignment
import io.github.JaJaJim.lightonkaroo.data.LightModeOption
import io.github.JaJaJim.lightonkaroo.data.LightProtocol
import io.github.JaJaJim.lightonkaroo.data.LightRole
import io.github.JaJaJim.lightonkaroo.data.modeProviderFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LightDetailDialog(
    light: DiscoveredLight,
    assignment: LightAssignment?,
    onUpdateAssignment: (LightAssignment?) -> Unit,
    onDelete: (() -> Unit)? = null,
    onTestMode: ((deviceId: String, modeId: String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var enabled by remember(assignment) { mutableStateOf(assignment?.enabled ?: true) }
    var useForThreatMode by remember(assignment) { mutableStateOf(if (light.protocol == LightProtocol.ANT_PLUS) (assignment?.useForThreatMode ?: false) else false) }
    var activeMode by remember(assignment) { mutableStateOf(assignment?.activeMode ?: "OFF") }
    var secondaryMode by remember(assignment) { mutableStateOf(assignment?.secondaryMode ?: "OFF") }
    var modeOff by remember(assignment) { mutableStateOf(assignment?.modeOff ?: "OFF") }
    var nickname by remember(assignment) { mutableStateOf(assignment?.nickname ?: "") }

    val modes = remember(light.id, light.protocol) {
        modeProviderFor(light.protocol, light.id).availableModes()
    }

    fun save() {
        onUpdateAssignment(
            LightAssignment(
                deviceId = light.id,
                deviceName = light.name,
                role = assignment?.role ?: LightRole.REAR,
                protocol = light.protocol,
                enabled = enabled,
                useForThreatMode = if (light.protocol == LightProtocol.ANT_PLUS) useForThreatMode else false,
                activeMode = activeMode,
                secondaryMode = secondaryMode,
                modeOff = modeOff,
                nickname = nickname,
            ),
        )
    }

    val protocolLabel = when (light.protocol) {
        LightProtocol.ANT_PLUS -> "ANT+"
        LightProtocol.BLE -> "BLE"
    }
    val connectionLabel = if (light.connected) "Connected" else "Not found"

    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Light") },
            text = { Text("Remove ${light.name}? Settings will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete?.invoke()
                    onDismiss()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = if (onDelete != null && assignment != null) {
            {
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        } else null,
        title = { Text(light.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val statusParts = listOfNotNull(
                    light.manufacturer,
                    protocolLabel,
                    connectionLabel,
                )
                Text(
                    statusParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val telemetryParts = mutableListOf<String>()
                light.batteryPercent?.let {
                    val text = if (light.batteryFromRadar) "Radar Battery" else "Battery"
                    telemetryParts.add(text)
                }
                light.temperature?.let { telemetryParts.add("Temp: ${it}°C") }
                if (telemetryParts.isNotEmpty()) {
                    Text(
                        telemetryParts.joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active Light", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Enable light control and status monitoring",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            save()
                        },
                    )
                }

                if (enabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    InlineDropdown(
                        label = "Display Name",
                        selectedId = if (nickname.isEmpty()) "Standard" else nickname,
                        options = listOf(
                            "Standard" to "Default",
                            "Front Light" to "Front Light",
                            "Rear Light" to "Rear Light",
                            "Helmet Light" to "Helmet Light",
                            "Pedal Light" to "Pedal Light",
                            "Trailer Light" to "Trailer Light",
                            "Radar Light" to "Radar Light",
                        ),
                        onSelected = { selected ->
                            nickname = if (selected == "Standard") "" else selected
                            save()
                        },
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    ModeRow(
                        label = "Primary ON Mode",
                        selectedMode = activeMode,
                        modes = modes,
                        onSelected = { activeMode = it; save() },
                        onTest = if (onTestMode != null) {
                            { onTestMode(light.id, activeMode) }
                        } else null,
                    )

                    ModeRow(
                        label = "Secondary ON Mode",
                        selectedMode = secondaryMode,
                        modes = modes,
                        onSelected = { secondaryMode = it; save() },
                        onTest = if (onTestMode != null) {
                            { onTestMode(light.id, secondaryMode) }
                        } else null,
                    )

                    ModeRow(
                        label = "OFF Mode",
                        selectedMode = modeOff,
                        modes = modes,
                        onSelected = { modeOff = it; save() },
                        onTest = if (onTestMode != null) {
                            { onTestMode(light.id, modeOff) }
                        } else null,
                    )

                    if (light.protocol == LightProtocol.ANT_PLUS) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Use for Software Threat Mode", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Include this light in radar threat response",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = useForThreatMode,
                                onCheckedChange = {
                                    useForThreatMode = it
                                    save()
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ModeRow(
    label: String,
    selectedMode: String,
    modes: List<LightModeOption>,
    onSelected: (String) -> Unit,
    onTest: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            val currentOption = modes.find { it.id == selectedMode }
            Text(
                currentOption?.displayName ?: selectedMode,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        var expanded by remember { mutableStateOf(false) }

        IconButton(onClick = { expanded = true }) {
            Text("▼", style = MaterialTheme.typography.bodyMedium)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName) },
                    onClick = {
                        expanded = false
                        onSelected(mode.id)
                    },
                )
            }
        }

        var countdownSeconds by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()

        if (onTest != null) {
            IconButton(
                onClick = {
                    if (countdownSeconds == 0) {
                        onTest()
                        scope.launch {
                            for (sec in 3 downTo 1) {
                                countdownSeconds = sec
                                delay(1000L)
                            }
                            countdownSeconds = 0
                        }
                    }
                },
                enabled = countdownSeconds == 0,
            ) {
                if (countdownSeconds > 0) {
                    Text(
                        "${countdownSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        "▶",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineDropdown(
    label: String,
    selectedId: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val displayName = options.find { it.first == selectedId }?.second ?: selectedId
            Text(
                displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        var expanded by remember { mutableStateOf(false) }

        IconButton(onClick = { expanded = true }) {
            Text("▼", style = MaterialTheme.typography.bodyMedium)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onSelected(id)
                    },
                )
            }
        }
    }
}
