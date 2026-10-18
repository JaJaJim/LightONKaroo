package io.github.JaJaJim.lightonkaroo.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
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
import io.github.JaJaJim.lightonkaroo.data.LightRole
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFA252CC).copy(alpha = 0.25f))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        HeaderSection()

        Spacer(modifier = Modifier.height(16.dp))

        SetupNoticeSection()

        Spacer(modifier = Modifier.height(20.dp))

        ConnectedLightsSection(
            settings = settings,
            discoveredLights = discoveredLights,
            onUpdateAssignment = onUpdateAssignment,
            onTestMode = onTestMode,
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        JanusModeSection(settings = settings, onSave = onSave)

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        SoftwareThreatModeSection(
            settings = settings,
            onSave = onSave,
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        RideControlSection(settings = settings, onSave = onSave)

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        DataFieldAppearanceSection(settings = settings, onSave = onSave)

        Spacer(modifier = Modifier.height(16.dp))
        FooterSection()
    }
}

@Composable
private fun HeaderSection() {
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
}

@Composable
private fun SetupNoticeSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = Color(0xFF32E09A), shape = RoundedCornerShape(8.dp))
            .background(Color(0xFF32E09A).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            "💡 IMPORTANT",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF1B5E20),
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Karoo Sensor Setup & Light Modes",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "• In Karoo Sensor Settings, set Auto Light Control to 'OFF' to prevent ANT+ conflicts.\n• Power on your lights BEFORE booting Karoo to discover all advanced light modes. If lights connect after boot, standard fallback modes will be used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConnectedLightsSection(
    settings: LightControllerSettings,
    discoveredLights: List<DiscoveredLight>,
    onUpdateAssignment: (String, LightAssignment?) -> Unit,
    onTestMode: (String, String) -> Unit,
) {
    Text("Connected Lights", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(8.dp))

    val discoveredIds = discoveredLights.map { it.id }.toSet()
    val savedButNotFound = settings.lightAssignments.filter { it.deviceId !in discoveredIds }

    // STABLE ORDER: Discovered lights first, followed by saved devices. No jumping on toggle!
    val allLights = (discoveredLights + savedButNotFound.map {
        DiscoveredLight(it.deviceId, it.deviceName, null, it.protocol, connected = false)
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
            LightCard(
                light = light,
                assignment = assignment,
                onUpdateAssignment = { updated ->
                    onUpdateAssignment(light.id, updated)
                },
                onTestMode = onTestMode,
            )
            if (index < allLights.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                onUpdateAssignment("", null) // Reset light assignments
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset All Light Settings to Default", color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun JanusModeSection(
    settings: LightControllerSettings,
    onSave: (LightControllerSettings) -> Unit,
) {
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
        Switch(
            checked = settings.threeModeEnabled,
            onCheckedChange = { onSave(settings.copy(threeModeEnabled = it)) },
        )
    }
}

@Composable
private fun SoftwareThreatModeSection(
    settings: LightControllerSettings,
    onSave: (LightControllerSettings) -> Unit,
) {
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
            checked = settings.softwareThreatModeEnabled,
            onCheckedChange = { onSave(settings.copy(softwareThreatModeEnabled = it)) },
        )
    }

    if (settings.softwareThreatModeEnabled) {
        Spacer(modifier = Modifier.height(8.dp))

        // Warning Box (25% Red Background, Black Text)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = Color(0xFFD34343), shape = RoundedCornerShape(8.dp))
                .background(Color(0xFFD34343).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Text(
                "⚠️ WARNING & DISCLAIMER",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB71C1C),
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Relies on wireless ANT+ streaming (~1s wake latency) and third-party device firmware. Subject to radio interference, packet loss, and potential software bugs in LightONKaroo or connected devices. Provided \"AS-IS\" without warranty of any kind. Not guaranteed to be error-free or safety-critical. Use entirely at your own risk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Radar Status Legend Box (25% Turquoise Background)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = Color(0xFF32E09A), shape = RoundedCornerShape(8.dp))
                .background(Color(0xFF32E09A).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Radar Status Icon Indicator",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "• White: Waiting for vehicle or light confirmation\n• Turquoise: Vehicle detected & light mode matches threat configuration",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        val threatLights = settings.lightAssignments.filter { it.enabled && it.useForThreatMode && it.protocol == LightProtocol.ANT_PLUS }
        if (threatLights.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = Color(0xFFD34343), shape = RoundedCornerShape(8.dp))
                    .background(Color(0xFFD34343).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    "⚠️ CONFIGURATION WARNING",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFB71C1C),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Software Threat Mode is enabled, but no connected lights are configured for threat mode. Please enable 'Use for Software Threat Mode' on at least one ANT+ light in Connected Lights.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
                checked = settings.overrideActiveModes,
                onCheckedChange = { onSave(settings.copy(overrideActiveModes = it)) },
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
                checked = settings.simulateRadar,
                onCheckedChange = { onSave(settings.copy(simulateRadar = it)) },
            )
        }

        if (settings.simulateRadar) {
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

        StepperSlider(
            label = "Threat Hold Time",
            subtitle = "Keeps threat light active for X seconds after vehicles pass to extend warning visibility and prevent flickering in traffic.",
            value = settings.threatHoldTimeSeconds,
            range = 0..5,
            step = 1,
            unit = "s",
            onSaveValue = { onSave(settings.copy(threatHoldTimeSeconds = it)) },
        )
    }
}

@Composable
private fun RideControlSection(
    settings: LightControllerSettings,
    onSave: (LightControllerSettings) -> Unit,
) {
    Text("Ride Control", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Turn ON in Primary mode on ride start", modifier = Modifier.weight(1f))
        Switch(
            checked = settings.autoOnWithRide,
            onCheckedChange = { onSave(settings.copy(autoOnWithRide = it)) },
        )
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
        Switch(
            checked = settings.autoOffWithRide,
            onCheckedChange = { onSave(settings.copy(autoOffWithRide = it)) },
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineDropdown(
            label = "Ride Pause Behavior",
            selectedId = settings.pauseBehavior,
            options = listOf(
                "NONE" to "Do nothing",
                "OFF" to "Configured OFF Mode",
                "PRIMARY" to "Primary ON",
                "SECONDARY" to "Secondary ON",
                "HARD_OFF" to "Truly turn OFF",
            ),
            onSelected = { onSave(settings.copy(pauseBehavior = it)) },
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
        Switch(
            checked = settings.showDetailedStatus,
            onCheckedChange = { onSave(settings.copy(showDetailedStatus = it)) },
        )
    }

    if (settings.showDetailedStatus) {
        Spacer(modifier = Modifier.height(16.dp))
        StepperSlider(
            label = "Rotation Speed",
            value = settings.rotationSpeedSeconds,
            range = 5..30,
            step = 5,
            unit = "s",
            onSaveValue = { onSave(settings.copy(rotationSpeedSeconds = it)) },
        )
    }
}

@Composable
private fun DataFieldAppearanceSection(
    settings: LightControllerSettings,
    onSave: (LightControllerSettings) -> Unit,
) {
    Text("Data Field Appearance", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Show Janus Sun background logo", modifier = Modifier.weight(1f))
        Switch(
            checked = settings.showLogo,
            onCheckedChange = { onSave(settings.copy(showLogo = it)) },
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    StepperSlider(
        label = "Ambient Side-Glow Intensity",
        value = settings.glowIntensity,
        range = 0..5,
        step = 1,
        unit = "",
        onSaveValue = { onSave(settings.copy(glowIntensity = it)) },
    )
}

@Composable
private fun StepperSlider(
    label: String,
    subtitle: String? = null,
    value: Int,
    range: IntRange,
    step: Int = 1,
    unit: String = "",
    onSaveValue: (Int) -> Unit,
) {
    var currentValue by remember(value) { mutableStateOf(value.coerceIn(range.first, range.last)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val next = (currentValue - step).coerceIn(range.first, range.last)
                        currentValue = next
                        onSaveValue(next)
                    },
                    modifier = Modifier.size(25.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("-", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }

                Text(
                    if (unit.isNotEmpty()) "${currentValue}$unit" else "$currentValue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedButton(
                    onClick = {
                        val next = (currentValue + step).coerceIn(range.first, range.last)
                        currentValue = next
                        onSaveValue(next)
                    },
                    modifier = Modifier.size(25.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("+", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        Slider(
            value = currentValue.toFloat(),
            onValueChange = { currentValue = it.toInt() },
            onValueChangeFinished = { onSaveValue(currentValue) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (((range.last - range.first) / step) > 1) ((range.last - range.first) / step) - 1 else 0,
        )
    }
}

@Composable
private fun FooterSection() {
    Text("Supported Lights & Credits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "ANT+: Supports all ANT+ smart bike lights recognized by Karoo. Tested with Magene AT1200, Magicshine Hori 1300Pro, Ravemen FR300 ANT+, Coospo TR70, and Cycplus L7 radar.\n\n" +
        "iGPSPORT BLE: Full support for iGPSPORT VS-series smart lights. Tested with VS1800S (Dual Beam). Support for VS1200S, VS800S, and VS500 (Single Beam) implemented based on igpsport-karoo by Tiago Fontes Dias (tiagodias00).\n\n" +
        "BLE: Tested with Magicshine Hori 1300S and 1300Pro.",
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

@Composable
private fun LightCard(
    light: DiscoveredLight,
    assignment: LightAssignment?,
    onUpdateAssignment: (LightAssignment?) -> Unit,
    onTestMode: (deviceId: String, modeId: String) -> Unit,
) {
    val enabled = assignment?.enabled ?: false
    val nickname = assignment?.nickname ?: ""
    val activeMode = assignment?.activeMode ?: "OFF"
    val secondaryMode = assignment?.secondaryMode ?: "OFF"
    val modeOff = assignment?.modeOff ?: "OFF"
    val useForThreatMode = assignment?.useForThreatMode ?: false
    val softwareThreatMode = assignment?.softwareThreatMode ?: "FAST_FLASH"

    val modes = remember(light.id, light.protocol) {
        modeProviderFor(light.protocol, light.id).availableModes()
    }

    fun save(
        newEnabled: Boolean = enabled,
        newNickname: String = nickname,
        newActiveMode: String = activeMode,
        newSecondaryMode: String = secondaryMode,
        newModeOff: String = modeOff,
        newUseForThreatMode: Boolean = useForThreatMode,
        newSoftwareThreatMode: String = softwareThreatMode,
    ) {
        onUpdateAssignment(
            LightAssignment(
                deviceId = light.id,
                deviceName = light.name,
                role = assignment?.role ?: LightRole.REAR,
                protocol = light.protocol,
                enabled = newEnabled,
                useForThreatMode = if (light.protocol == LightProtocol.ANT_PLUS) newUseForThreatMode else false,
                activeMode = newActiveMode,
                secondaryMode = newSecondaryMode,
                modeOff = newModeOff,
                softwareThreatMode = newSoftwareThreatMode,
                nickname = newNickname,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (light.connected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .background(
                if (enabled) Color(0xFF32E09A).copy(alpha = 0.25f) else Color(0xFFD34343).copy(alpha = 0.25f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: Line 1: Light Name full width; Line 2: Status Subtitle
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = light.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            val protocolText = when (light.protocol) {
                LightProtocol.ANT_PLUS -> "ANT+"
                LightProtocol.IGPSPORT_BLE -> "iGPSPORT BLE"
                LightProtocol.BLE -> "BLE"
            }
            val statusText = if (light.connected) "Connected" else "Not found"
            val batteryText = light.batteryPercent?.let { " · Battery Level $it%" } ?: ""
            Text(
                text = "$protocolText · $statusText$batteryText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Line 3: Prominent "Slide to activate" + Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Slide to activate",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            Switch(
                checked = enabled,
                onCheckedChange = { save(newEnabled = it) },
            )
        }

        if (enabled) {
            HorizontalDivider()

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
                    save(newNickname = if (selected == "Standard") "" else selected)
                },
            )

            val refresh: () -> Unit = { io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension.getInstance()?.lightControl?.forceRefreshLightParameters(light.id) }

            ModeRow(
                label = "Primary ON Mode",
                selectedMode = activeMode,
                modes = modes,
                onSelected = { save(newActiveMode = it) },
                onTest = { onTestMode(light.id, activeMode) },
                onRequestRefresh = refresh,
            )

            ModeRow(
                label = "Secondary ON Mode",
                selectedMode = secondaryMode,
                modes = modes,
                onSelected = { save(newSecondaryMode = it) },
                onTest = { onTestMode(light.id, secondaryMode) },
                onRequestRefresh = refresh,
            )

            ModeRow(
                label = "OFF Mode",
                selectedMode = modeOff,
                modes = modes,
                onSelected = { save(newModeOff = it) },
                onTest = { onTestMode(light.id, modeOff) },
                onRequestRefresh = refresh,
            )

            if (light.protocol == LightProtocol.ANT_PLUS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use for Software Threat Mode", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Include in radar threat response",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useForThreatMode,
                        onCheckedChange = { save(newUseForThreatMode = it) },
                    )
                }

                if (useForThreatMode) {
                    val threatOptions = buildList {
                        add(LightModeOption("DISABLED", "Disabled"))
                        add(LightModeOption("FAST_FLASH", "Fast Flash"))
                        modes.forEach { mode ->
                            if (mode.id != "OFF" && mode.id != "FAST_FLASH") {
                                add(mode)
                            }
                        }
                    }
                    ModeRow(
                        label = "Threat Warning Mode",
                        selectedMode = softwareThreatMode,
                        modes = threatOptions,
                        onSelected = { save(newSoftwareThreatMode = it) },
                        onTest = { onTestMode(light.id, softwareThreatMode) },
                        onRequestRefresh = refresh,
                    )
                }
            }

            val isIgpsport = light.protocol == LightProtocol.IGPSPORT_BLE ||
                             light.manufacturer?.uppercase() == "IGPSPORT" ||
                             light.name.uppercase().contains("VS") ||
                             light.name.uppercase().contains("IGP") ||
                             assignment?.deviceName?.uppercase()?.contains("VS") == true ||
                             assignment?.deviceName?.uppercase()?.contains("IGP") == true ||
                             assignment?.protocol == LightProtocol.IGPSPORT_BLE

            if (isIgpsport) {
                val igpController = io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension.getInstance()?.igpsportController
                val igpConfig = igpController?.getDeviceConfig(light.id)
                var lowBatterySave by remember(light.id) { mutableStateOf(igpConfig?.lowBatterySaveEnabled ?: false) }

                val nameUpper = ((assignment?.deviceName ?: "") + " " + light.name).uppercase()
                val isExplicitSingleBeam = nameUpper.contains("1200") || nameUpper.contains("800") || nameUpper.contains("500")
                val isDualBeam = !isExplicitSingleBeam
                val isVs500 = nameUpper.contains("500")
                val hasLightSensor = !isVs500

                if (hasLightSensor) {
                    HorizontalDivider()
                    Text("iGPSPORT Smart Features", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Low-Battery Power Saver", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Automatically switches to low-power flash when battery is critical",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = lowBatterySave,
                            onCheckedChange = { active ->
                                lowBatterySave = active
                                igpController?.setLowBatterySave(light.id, active)
                            },
                        )
                    }
                }

                HorizontalDivider()
                var showCustomProfiles by remember(light.id) { mutableStateOf(true) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Custom Light Profiles", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Configure custom light slots and beam levels",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showCustomProfiles,
                        onCheckedChange = { showCustomProfiles = it },
                    )
                }

                if (showCustomProfiles) {
                    Spacer(modifier = Modifier.height(4.dp))
                    for (slot in 1..3) {
                        var highBeamPct by remember(light.id, slot) { mutableStateOf(if (slot == 1) 100 else if (slot == 2) 50 else 25) }
                        var lowBeamPct by remember(light.id, slot) { mutableStateOf(if (slot == 1) 100 else if (slot == 2) 50 else 25) }
                        var mainPct by remember(light.id, slot) { mutableStateOf(if (slot == 1) 100 else if (slot == 2) 50 else 25) }
                        var patternType by remember(light.id, slot) { mutableStateOf(0) } // 0: Steady, 1: Flash, 2: Breath
                        var slotAutoDim by remember(light.id, slot) { mutableStateOf(false) }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Custom Slot $slot", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)

                            if (hasLightSensor) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Enable Auto-Dimming", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    Switch(
                                        checked = slotAutoDim,
                                        onCheckedChange = { active ->
                                            slotAutoDim = active
                                            igpController?.setAutoDimmingForSlot(light.id, slot, active)
                                        },
                                    )
                                }
                            }

                            InlineDropdown(
                                label = "Pattern Type",
                                selectedId = patternType.toString(),
                                options = listOf(
                                    "0" to "Steady",
                                    "1" to "Flash",
                                    "2" to "Breath",
                                ),
                                onSelected = { selected ->
                                    patternType = selected.toIntOrNull() ?: 0
                                    if (isDualBeam) {
                                        igpController?.setCustomModeConfig(light.id, slot, patternType, highBeamPct, lowBeamPct)
                                    } else {
                                        igpController?.setCustomModeConfigSingleBeam(light.id, slot, patternType, mainPct)
                                    }
                                },
                            )

                            if (isDualBeam) {
                                StepperSlider(
                                    label = "High Beam",
                                    value = highBeamPct,
                                    range = 0..100,
                                    step = 1,
                                    unit = "%",
                                    onSaveValue = { pct ->
                                        highBeamPct = pct
                                        igpController?.setCustomModeConfig(light.id, slot, patternType, highBeamPct, lowBeamPct)
                                    },
                                )

                                StepperSlider(
                                    label = "Low Beam",
                                    value = lowBeamPct,
                                    range = 0..100,
                                    step = 1,
                                    unit = "%",
                                    onSaveValue = { pct ->
                                        lowBeamPct = pct
                                        igpController?.setCustomModeConfig(light.id, slot, patternType, highBeamPct, lowBeamPct)
                                    },
                                )
                            } else {
                                StepperSlider(
                                    label = "Brightness",
                                    value = mainPct,
                                    range = 0..100,
                                    step = 1,
                                    unit = "%",
                                    onSaveValue = { pct ->
                                        mainPct = pct
                                        igpController?.setCustomModeConfigSingleBeam(light.id, slot, patternType, mainPct)
                                    },
                                )
                            }
                        }
                        if (slot < 3) Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    label: String,
    selectedMode: String,
    modes: List<LightModeOption>,
    onSelected: (String) -> Unit,
    onTest: () -> Unit,
    onRequestRefresh: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

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
                IconButton(onClick = {
                    onRequestRefresh?.invoke()
                    expanded = true
                }) {
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
