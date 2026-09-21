package io.github.JaJaJim.lightonkaroo.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.JaJaJim.lightonkaroo.BuildConfig
import io.github.JaJaJim.lightonkaroo.DiscoveredLight
import io.github.JaJaJim.lightonkaroo.R
import io.github.JaJaJim.lightonkaroo.data.LightAssignment
import io.github.JaJaJim.lightonkaroo.data.LightControllerSettings
import io.github.JaJaJim.lightonkaroo.data.LightProtocol
import io.github.JaJaJim.lightonkaroo.data.LightRole
import io.github.JaJaJim.lightonkaroo.data.modeProviderFor

@Composable
fun SettingsScreen(
    settings: LightControllerSettings,
    discoveredLights: List<DiscoveredLight> = emptyList(),
    onSave: (LightControllerSettings) -> Unit,
    onUpdateAssignment: (String, LightAssignment?) -> Unit = { _, _ -> },
    onDeleteLight: (DiscoveredLight) -> Unit = {},
    onTestMode: (String, String) -> Unit = { _, _ -> },
) {
    var autoOn by remember(settings) { mutableStateOf(settings.autoOnWithRide) }
    var autoOff by remember(settings) { mutableStateOf(settings.autoOffWithRide) }
    var pauseBehavior by remember(settings) { mutableStateOf(settings.pauseBehavior) }
    var showDetailedStatus by remember(settings) { mutableStateOf(settings.showDetailedStatus) }
    var threeModeEnabled by remember(settings) { mutableStateOf(settings.threeModeEnabled) }
    var rotationSpeed by remember(settings) { mutableStateOf(settings.rotationSpeedSeconds.toFloat()) }
    var showLogo by remember(settings) { mutableStateOf(settings.showLogo) }
    var glowIntensity by remember(settings) { mutableStateOf(settings.glowIntensity.toFloat()) }

    fun saveSettings() {
        onSave(
            settings.copy(
                autoOnWithRide = autoOn,
                autoOffWithRide = autoOff,
                pauseBehavior = pauseBehavior,
                showDetailedStatus = showDetailedStatus,
                threeModeEnabled = threeModeEnabled,
                rotationSpeedSeconds = rotationSpeed.toInt(),
                showLogo = showLogo,
                glowIntensity = glowIntensity.toInt()
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("LightONKaroo", style = MaterialTheme.typography.headlineSmall)
            Image(
                painter = painterResource(R.drawable.ic_light),
                contentDescription = "LightONKaroo",
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            "Manual ANT+ and Bluetooth light control for Hammerhead Karoo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Connected Lights
        Text("Connected Lights", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        var selectedLight by remember { mutableStateOf<DiscoveredLight?>(null) }

        val discoveredIds = discoveredLights.map { it.id }.toSet()
        val savedButNotFound = settings.lightAssignments.filter { it.deviceId !in discoveredIds }

        val allLights = (discoveredLights + savedButNotFound.map {
            DiscoveredLight(it.deviceId, it.deviceName, null, it.protocol, connected = false)
        }).sortedWith(compareBy<DiscoveredLight> {
            val isConfigured = settings.lightAssignments.any { a -> a.deviceId == it.id }
            when {
                !isConfigured -> 0
                it.connected -> 1
                else -> 2
            }
        })

        if (allLights.isEmpty()) {
            Text(
                "No lights found. Pair lights in Karoo's sensor settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            allLights.forEachIndexed { index, light ->
                val assignment = settings.lightAssignments.find { it.deviceId == light.id }
                LightRow(
                    light = light,
                    assignment = assignment,
                    onClick = { selectedLight = light },
                )
                if (index < allLights.lastIndex) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        selectedLight?.let { light ->
            val assignment = settings.lightAssignments.find { it.deviceId == light.id }
            LightDetailDialog(
                light = light,
                assignment = assignment,
                onUpdateAssignment = { updated ->
                    onUpdateAssignment(light.id, updated)
                },
                onTestMode = { deviceId, modeName -> onTestMode(deviceId, modeName) },
                onDelete = { onDeleteLight(light) },
                onDismiss = { selectedLight = null },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable 3-mode control (Janus Mode)")
                Text(
                    "Split field: Left toggles Primary/Secondary, Right turns OFF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = threeModeEnabled, onCheckedChange = { threeModeEnabled = it; saveSettings() })
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Ride Control
        Text("Ride Control", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Auto-on when starting ride", modifier = Modifier.weight(1f))
            Switch(checked = autoOn, onCheckedChange = { autoOn = it; saveSettings() })
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Turn off lights after finishing ride", modifier = Modifier.weight(1f))
            Switch(checked = autoOff, onCheckedChange = { autoOff = it; saveSettings() })
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineDropdown(
                label = "Ride Pause Behavior",
                selectedId = pauseBehavior,
                options = listOf(
                    "NONE" to "Do nothing",
                    "OFF" to "Configured OFF Mode",
                    "PRIMARY" to "Primary ON",
                    "SECONDARY" to "Secondary ON",
                    "HARD_OFF" to "Truly turn OFF"
                ),
                onSelected = { pauseBehavior = it; saveSettings() }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show status rotation in data field")
                Text(
                    "Cycle through name, status, and battery of connected lights.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = showDetailedStatus, onCheckedChange = { showDetailedStatus = it; saveSettings() })
        }

        if (showDetailedStatus) {
            Spacer(modifier = Modifier.height(16.dp))
            Column {
                Text(
                    "Rotation Speed: ${rotationSpeed.toInt()}s",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = rotationSpeed,
                    onValueChange = { rotationSpeed = it },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = 5f..30f,
                    steps = 4 // 5, 10, 15, 20, 25, 30
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // UI Customization
        Text("Data Field Appearance", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show background logo")
            Switch(checked = showLogo, onCheckedChange = { showLogo = it; saveSettings() })
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column {
            Text(
                "Background Glow Intensity: ${glowIntensity.toInt()}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = glowIntensity,
                onValueChange = { glowIntensity = it },
                onValueChangeFinished = { saveSettings() },
                valueRange = 0f..5f,
                steps = 4 // 0, 1, 2, 3, 4, 5
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("Supported Lights", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "ANT+: Supports smart bike lights like Garmin Varia, Bontrager Ion/Flare, Magene L508. Note: Some lights (e.g. Raveman) may report incorrect status or battery levels (possibly due to incomplete ANT+ implementations).\n\nBLE: Support for Magicshine (M1/M2/M3) is inherited but currently untested. Use at your own risk.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun InlineDropdown(
    label: String,
    selectedId: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = options.find { it.first == selectedId }?.second ?: selectedId

    Row(
        modifier = modifier
            .clickable { expanded = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelected(id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: LightProtocol) {
    val badgeShape = RoundedCornerShape(4.dp)
    when (protocol) {
        LightProtocol.BLE -> {
            Row(
                modifier = Modifier
                    .background(Color(0xFF1565C0), badgeShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "BLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontSize = 9.sp,
                )
            }
        }
        LightProtocol.ANT_PLUS -> {
            Text(
                "ANT+",
                modifier = Modifier
                    .border(1.dp, Color(0xFF616161), badgeShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF616161),
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun LightRow(
    light: DiscoveredLight,
    assignment: LightAssignment?,
    onClick: () -> Unit,
) {
    val role = assignment?.role
    val isConfigured = role != null
    val alpha = if (light.connected || !isConfigured) 1f else 0.4f
    val roleLabel = when (role) {
        LightRole.FRONT -> "Front"
        LightRole.REAR -> "Rear"
        null -> "Tap to configure"
    }
    val statusColor = when {
        !isConfigured -> MaterialTheme.colorScheme.tertiary
        !light.connected -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val backgroundColor = when {
        !isConfigured -> Color(0xFFFFE082)
        !light.connected -> Color(0xFFE0E0E0)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, shape = MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).alpha(alpha)) {
            Text(light.name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProtocolBadge(light.protocol)
                val infoText = listOfNotNull(
                    light.manufacturer,
                    if (!light.connected) "Disconnected" else null,
                ).joinToString(" · ")
                if (infoText.isNotEmpty()) {
                    Text(
                        infoText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                roleLabel,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
            if (isConfigured) {
                val onMode = assignment.activeMode
                val offMode = assignment.modeOff
                Text(
                    "$onMode / $offMode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 8.sp
                )
            }
        }
    }
}
