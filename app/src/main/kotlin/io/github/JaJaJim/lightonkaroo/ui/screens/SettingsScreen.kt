package io.github.JaJaJim.lightonkaroo.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.JaJaJim.lightonkaroo.BuildConfig
import io.github.JaJaJim.lightonkaroo.DiscoveredLight
import io.github.JaJaJim.lightonkaroo.R
import io.github.JaJaJim.lightonkaroo.data.LightAssignment
import io.github.JaJaJim.lightonkaroo.data.LightControllerSettings
import io.github.JaJaJim.lightonkaroo.data.LightModeOption
import io.github.JaJaJim.lightonkaroo.data.LightProtocol
import io.github.JaJaJim.lightonkaroo.data.modeProviderFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var threatHoldTime by remember(settings) { mutableStateOf(settings.threatHoldTimeSeconds.toFloat()) }
    var overrideActiveModes by remember(settings) { mutableStateOf(settings.overrideActiveModes) }
    var simulateRadar by remember(settings) { mutableStateOf(settings.simulateRadar) }
    var softwareThreatModeEnabled by remember(settings) { mutableStateOf(settings.softwareThreatModeEnabled) }

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
                glowIntensity = glowIntensity.toInt(),
                threatHoldTimeSeconds = threatHoldTime.toInt(),
                overrideActiveModes = overrideActiveModes,
                simulateRadar = simulateRadar,
                softwareThreatModeEnabled = softwareThreatModeEnabled,
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

        Spacer(modifier = Modifier.height(16.dp))

        // Prominent Important Setup Notice at the Very Top
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Text(
                "💡 IMPORTANT",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Karoo Sensor Setup",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "In Karoo Sensor Settings, set Auto Light Control to 'OFF' so Karoo OS does not conflict with LightONKaroo over ANT+.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

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
                Text("Enable Janus Mode (3-Way Control)")
                Text(
                    "When ON: Left tap toggles Primary/Secondary Light Mode, Right tap turns OFF. When OFF (Classic Mode): Full tap toggles Primary ON and OFF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = threeModeEnabled, onCheckedChange = { threeModeEnabled = it; saveSettings() })
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Software Threat Mode Master Switch Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable Software Threat Mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Triggers warning lighting when radar detects approaching vehicles. Choose Steady Light for StVZO / DACH compliance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = softwareThreatModeEnabled,
                onCheckedChange = {
                    softwareThreatModeEnabled = it
                    saveSettings()
                },
            )
        }

        if (softwareThreatModeEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "⚠️ WARNING: Relies on wireless ANT+ streaming (~1s wake latency) and third-party device firmware. Subject to radio interference, packet loss, and potential software bugs in LightONKaroo or connected devices. Provided \"AS-IS\" without warranty of any kind. Not guaranteed to be error-free or safety-critical. Use entirely at your own risk.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Radar Status Legend (Text Only)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Radar Status Icon Indicator",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "• White: Waiting for vehicle or light confirmation\n• Turquoise: Vehicle detected & light mode matches threat configuration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Override Active ON Modes")
                    Text(
                        "Threat mode ALWAYS activates assigned threat lights when OFF. Enable this to ALSO temporarily override active ON lights (Primary/Secondary).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = overrideActiveModes,
                    onCheckedChange = {
                        overrideActiveModes = it
                        saveSettings()
                    },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Simulate Radar Threats (Testing)")
                    Text(
                        "Generates artificial vehicle approach events based on real radar data for indoor testing without a physical radar sensor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = simulateRadar,
                    onCheckedChange = {
                        simulateRadar = it
                        saveSettings()
                    },
                )
            }

            if (simulateRadar) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        "🚨 RADAR SIMULATOR ACTIVE",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Real radar inputs are disabled. Generating realistic vehicle approach events for indoor testing. Turn OFF before riding outdoors!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column {
                Text(
                    "Threat Hold Time: ${threatHoldTime.toInt()}s",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Keeps threat light active for X seconds after vehicles pass to extend warning visibility and prevent flickering in traffic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = threatHoldTime,
                    onValueChange = { threatHoldTime = it },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = 0f..5f,
                    steps = 4, // 0, 1, 2, 3, 4, 5
                )
            }

            val threatLights = settings.lightAssignments.filter { it.enabled && it.useForThreatMode && it.protocol == LightProtocol.ANT_PLUS }
            if (threatLights.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        "⚠️ CONFIGURATION WARNING",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Software Threat Mode is enabled, but no connected lights are configured for threat mode. Please enable 'Use for Software Threat Mode' on at least one ANT+ light in Connected Lights.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                threatLights.forEach { assignment ->
                    Spacer(modifier = Modifier.height(12.dp))
                    val modes = modeProviderFor(assignment.protocol, assignment.deviceId).availableModes()
                    val threatOptions = buildList {
                        add(LightModeOption("DISABLED", "Disabled"))
                        add(LightModeOption("FAST_FLASH", "Fast Flash"))
                        modes.forEach { mode ->
                            if (mode.id != "OFF" && mode.id != "FAST_FLASH") {
                                add(mode)
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThreatModeRow(
                            displayName = assignment.displayName,
                            selectedMode = assignment.softwareThreatMode,
                            modes = threatOptions,
                            onSelected = { newMode ->
                                val updatedAssignments = settings.lightAssignments.map {
                                    if (it.deviceId == assignment.deviceId) it.copy(softwareThreatMode = newMode) else it
                                }
                                onSave(settings.copy(lightAssignments = updatedAssignments))
                            },
                            onTest = {
                                onTestMode(assignment.deviceId, assignment.softwareThreatMode)
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
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
            Text("Turn ON in Primary mode on ride start", modifier = Modifier.weight(1f))
            Switch(checked = autoOn, onCheckedChange = { autoOn = it; saveSettings() })
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Truly turn OFF lights on ride end")
                Text(
                    "Sends a hardware OFF command to fully power down lights and save battery when ride ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    "HARD_OFF" to "Truly turn OFF",
                ),
                onSelected = { pauseBehavior = it; saveSettings() },
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
                    "Cycles through light name, status, and battery level in the data field UI.",
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
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = rotationSpeed,
                    onValueChange = { rotationSpeed = it },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = 5f..30f,
                    steps = 4, // 5, 10, 15, 20, 25, 30
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
            Text("Show Janus Sun background logo", modifier = Modifier.weight(1f))
            Switch(checked = showLogo, onCheckedChange = { showLogo = it; saveSettings() })
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column {
            Text(
                "Ambient Side-Glow Intensity: ${glowIntensity.toInt()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = glowIntensity,
                onValueChange = { glowIntensity = it },
                onValueChangeFinished = { saveSettings() },
                valueRange = 0f..5f,
                steps = 4, // 0, 1, 2, 3, 4, 5
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("Supported Lights", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "ANT+: Supports ANT+ smart bike lights. Tested with Magene AT1200, Magicshine Hori 1300Pro, Ravemen FR300 ANT+, Coospo TR70 and Cycplus L7 radar.\n\nBLE: Tested with Magicshine Hori 1300S and 1300Pro. Partially supported (no high/low beam switching capability).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ThreatModeRow(
    displayName: String,
    selectedMode: String,
    modes: List<LightModeOption>,
    onSelected: (String) -> Unit,
    onTest: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Line 1: Light Name (e.g. "Radar Light") in primary color
        Text(
            displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Line 2: Subtitle "Software Threat Mode"
        Text(
            "Software Threat Mode",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Line 3: Selected Mode + Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val currentOption = modes.find { it.id == selectedMode }
            val modeName = currentOption?.displayName ?: selectedMode

            Text(
                modeName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            var expanded by remember { mutableStateOf(false) }

            Row(verticalAlignment = Alignment.CenterVertically) {
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
}

@Composable
private fun LightRow(
    light: DiscoveredLight,
    assignment: LightAssignment?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (light.connected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = assignment?.displayName ?: light.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val protocolText = when (light.protocol) {
                LightProtocol.ANT_PLUS -> "ANT+"
                LightProtocol.BLE -> "BLE"
            }
            val statusText = if (light.connected) "Connected" else "Not found"
            Text(
                text = "$protocolText · $statusText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedButton(onClick = onClick) {
            Text("Edit")
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
                style = MaterialTheme.typography.bodyLarge,
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
