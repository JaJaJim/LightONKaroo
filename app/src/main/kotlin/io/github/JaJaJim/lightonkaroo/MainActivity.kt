package io.github.JaJaJim.lightonkaroo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.JaJaJim.lightonkaroo.data.LightControllerSettings
import io.github.JaJaJim.lightonkaroo.data.PreferencesRepository
import kotlinx.coroutines.flow.flow
import io.github.JaJaJim.lightonkaroo.ui.screens.SettingsScreen
import io.github.JaJaJim.lightonkaroo.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: PreferencesRepository

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            KarooLightControllerExtension.getInstance()?.setSettingsUiActive(true)
        }
    }

    private fun ensureBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(perms)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureBlePermissions()

        repository = PreferencesRepository(applicationContext)

        setContent {
            AppTheme {
                val settings by repository.settingsFlow.collectAsState(initial = LightControllerSettings())

                val lights = KarooLightControllerExtension.getInstance()
                    ?.discoveredLights?.collectAsState(initial = emptyList())?.value ?: emptyList()

                SettingsScreen(
                    settings = settings,
                    discoveredLights = lights,
                    onSave = { newSettings ->
                        lifecycleScope.launch {
                            repository.updateSettings(newSettings)
                            KarooLightControllerExtension.getInstance()?.let { ext ->
                                ext.engine.settings = newSettings
                            }
                        }
                    },
                    onUpdateAssignment = { deviceId, updated ->
                        lifecycleScope.launch {
                            val newAssignments = settings.lightAssignments
                                .filter { it.deviceId != deviceId }
                                .let { list -> if (updated != null) list + updated else list }
                            val newSettings = settings.copy(lightAssignments = newAssignments)
                            repository.updateSettings(newSettings)
                            KarooLightControllerExtension.getInstance()?.let { ext ->
                                ext.engine.settings = newSettings
                                ext.onAssignmentChanged()
                            }
                        }
                    },
                    onDeleteLight = { light ->
                        if (light.protocol == io.github.JaJaJim.lightonkaroo.data.LightProtocol.BLE) {
                            KarooLightControllerExtension.getInstance()?.magicshineController?.disconnect(light.id)
                        }
                        lifecycleScope.launch {
                            val newAssignments = settings.lightAssignments.filter { it.deviceId != light.id }
                            val newSettings = settings.copy(lightAssignments = newAssignments)
                            repository.updateSettings(newSettings)
                            KarooLightControllerExtension.getInstance()?.let { ext ->
                                ext.engine.settings = newSettings
                                ext.onAssignmentChanged()
                            }
                        }
                    },
                    onTestMode = { deviceId, modeName ->
                        KarooLightControllerExtension.getInstance()?.testMode(deviceId, modeName)
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        KarooLightControllerExtension.getInstance()?.setSettingsUiActive(true)
    }

    override fun onStop() {
        super.onStop()
        KarooLightControllerExtension.getInstance()?.setSettingsUiActive(false)
    }

    override fun onDestroy() {
        KarooLightControllerExtension.getInstance()?.setSettingsUiActive(false)
        super.onDestroy()
    }
}
