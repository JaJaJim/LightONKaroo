package io.github.JaJaJim.lightonkaroo.datatypes

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension
import io.github.JaJaJim.lightonkaroo.R
import io.github.JaJaJim.lightonkaroo.data.LightProtocol
import io.github.JaJaJim.lightonkaroo.engine.LightControlEngine
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LightStatusDataType(
    private val engine: LightControlEngine,
    val bikeProfileIndex: Int = 1,
) : DataTypeImpl(
    "light-on-karoo",
    if (bikeProfileIndex == 1) "light-status" else "light-status-bike-$bikeProfileIndex"
) {

    companion object {
        const val FIELD_ACTIVE = "active"
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            engine.activeState.collect { activeState ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId = dataTypeId, values = mapOf(
                            FIELD_ACTIVE to if (activeState != 0) 1.0 else 0.0,
                        )),
                    ),
                )
            }
        }

        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ext = KarooLightControllerExtension.getInstance()

        ext?.registerActiveDataField(bikeProfileIndex)

        scope.launch {
            var rotationIndex = 0
            val actualModesFlow = ext?.lightControl?.actualModes ?: MutableStateFlow(emptyMap())

            combine(
                engine.activeState,
                ext?.discoveredLights ?: MutableStateFlow(emptyList()),
                actualModesFlow,
            ) { state, discovered, actualModes ->
                Triple(state, discovered, actualModes)
            }.collect { (activeState, discovered, actualModes) ->
                val settings = engine.settings
                val remoteViews = RemoteViews(context.packageName, R.layout.light_status_view)

                val masterProfileIndex = ext?.getMasterDataFieldProfile() ?: bikeProfileIndex
                if (bikeProfileIndex > masterProfileIndex) {
                    remoteViews.setTextViewText(R.id.light_mode_text, "CONFLICT")
                    remoteViews.setTextColor(R.id.light_mode_text, Color.parseColor("#d34343"))
                    remoteViews.setViewVisibility(R.id.light_device_name, View.VISIBLE)
                    remoteViews.setViewVisibility(R.id.light_device_status, View.VISIBLE)
                    remoteViews.setTextViewText(R.id.light_device_name, "Bike $bikeProfileIndex Conflict")
                    remoteViews.setTextViewText(R.id.light_device_status, "Bike $masterProfileIndex Has Priority")
                    remoteViews.setViewVisibility(R.id.light_battery_row, View.GONE)
                    remoteViews.setViewVisibility(R.id.light_radar_icon, View.GONE)
                    emitter.updateView(remoteViews)
                    return@collect
                }

                val profileAssignments = settings.assignmentsForProfile(bikeProfileIndex).filter { it.enabled }
                if (profileAssignments.isEmpty()) {
                    remoteViews.setTextViewText(R.id.light_mode_text, "OFF")
                    remoteViews.setTextColor(R.id.light_mode_text, Color.GRAY)
                    remoteViews.setViewVisibility(R.id.light_device_name, View.VISIBLE)
                    remoteViews.setViewVisibility(R.id.light_device_status, View.VISIBLE)
                    remoteViews.setTextViewText(R.id.light_device_name, "Bike $bikeProfileIndex Not Configured")
                    remoteViews.setTextViewText(R.id.light_device_status, "Activate lights in settings")
                    remoteViews.setViewVisibility(R.id.light_battery_row, View.GONE)
                    remoteViews.setViewVisibility(R.id.light_radar_icon, View.GONE)
                    emitter.updateView(remoteViews)
                    return@collect
                }

                // Calculate rotation specifically for THIS bike profile's assigned lights!
                val safeIndex = rotationIndex % profileAssignments.size
                val currentAssignment = profileAssignments[safeIndex]
                val currentLight = discovered.find { it.id == currentAssignment.deviceId }

                val globalStatusText = when (activeState) {
                    1 -> if (settings.threeModeEnabled) "PRIMARY" else "ON"
                    2 -> "SECONDARY"
                    else -> "OFF"
                }
                remoteViews.setTextViewText(R.id.light_mode_text, globalStatusText)
                val globalColor = if (activeState != 0) Color.parseColor("#ffe714") else Color.WHITE
                remoteViews.setTextColor(R.id.light_mode_text, globalColor)

                val allConnected = profileAssignments.all { assignment ->
                    discovered.find { it.id == assignment.deviceId }?.connected == true
                }
                val indicatorColor = if (allConnected) Color.parseColor("#32e09a") else Color.WHITE
                remoteViews.setInt(R.id.light_indicator_tiny, "setColorFilter", indicatorColor)

                // Logo and Glow
                val showLogo = settings.showLogo
                remoteViews.setViewVisibility(R.id.light_icon, if (showLogo) View.VISIBLE else View.GONE)
                if (showLogo) {
                    val logoAlpha = if (activeState != 0) 1.0f else 0.35f
                    remoteViews.setFloat(R.id.light_icon, "setAlpha", logoAlpha)
                }

                val intensity = settings.glowIntensity
                if (intensity > 0) {
                    remoteViews.setViewVisibility(R.id.light_glow_layer, View.VISIBLE)
                    val alphaValue = intensity / 5.0f
                    remoteViews.setFloat(R.id.light_glow_layer, "setAlpha", alphaValue)
                    if (activeState != 0) {
                        remoteViews.setImageViewResource(R.id.light_glow_layer, R.drawable.glow_left)
                    } else {
                        remoteViews.setImageViewResource(R.id.light_glow_layer, R.drawable.glow_right)
                    }
                } else {
                    remoteViews.setViewVisibility(R.id.light_glow_layer, View.GONE)
                }

                if (settings.showDetailedStatus) {
                    remoteViews.setViewVisibility(R.id.light_device_name, View.VISIBLE)
                    remoteViews.setViewVisibility(R.id.light_device_status, View.VISIBLE)
                    remoteViews.setViewVisibility(R.id.light_battery_row, View.VISIBLE)

                    remoteViews.setTextViewText(R.id.light_device_name, currentAssignment.displayName)

                    val statusText = when {
                        currentLight == null -> "OFFLINE"
                        !currentLight.connected -> "SEARCHING"
                        else -> currentLight.currentMode?.replace("_", " ") ?: "CONNECTED"
                    }
                    remoteViews.setTextViewText(R.id.light_device_status, statusText)

                    val battery = currentLight?.batteryPercent
                    val (batteryLabel, batteryColor) = when {
                        battery == null -> "Unknown" to Color.GRAY
                        battery > 50 -> "Good" to Color.parseColor("#32e09a")
                        battery >= 25 -> "Medium" to Color.parseColor("#ffe714")
                        else -> "Low" to Color.parseColor("#d34343")
                    }

                    val batterySuffix = if (currentLight?.batteryFromRadar == true) "(Radar)" else ""
                    remoteViews.setTextViewText(R.id.light_battery_text, batterySuffix)
                    remoteViews.setTextColor(R.id.light_battery_text, batteryColor)

                    // Radar Threat Icon
                    val threatAssignments = profileAssignments.filter { it.useForThreatMode && it.protocol == LightProtocol.ANT_PLUS }
                    if (threatAssignments.isNotEmpty()) {
                        remoteViews.setViewVisibility(R.id.light_radar_icon, View.VISIBLE)
                        val isThreatActive = ext?.isRadarThreatActive == true
                        val allThreatLightsConfirmed = threatAssignments.all { a ->
                            val actualMode = actualModes[a.deviceId]
                            actualMode == a.softwareThreatMode || a.softwareThreatMode == "DISABLED"
                        }
                        val radarColor = if (isThreatActive && allThreatLightsConfirmed) Color.parseColor("#32e09a") else Color.WHITE
                        remoteViews.setInt(R.id.light_radar_icon, "setColorFilter", radarColor)
                    } else {
                        remoteViews.setViewVisibility(R.id.light_radar_icon, View.GONE)
                    }

                    // Battery Icon
                    val batteryIconRes = when (batteryLabel) {
                        "Good" -> R.drawable.ic_battery_good
                        "Medium" -> R.drawable.ic_battery_medium
                        else -> R.drawable.ic_battery_low
                    }
                    remoteViews.setImageViewResource(R.id.light_battery_icon, batteryIconRes)
                    remoteViews.setInt(R.id.light_battery_icon, "setColorFilter", batteryColor)

                    // Temperature
                    if (currentLight?.temperature != null) {
                        remoteViews.setViewVisibility(R.id.light_temp_icon, View.VISIBLE)
                        remoteViews.setViewVisibility(R.id.light_temp_text, View.VISIBLE)
                        val tempC = currentLight.temperature
                        remoteViews.setTextViewText(R.id.light_temp_text, "${tempC}°C")
                        val tempColor = when {
                            tempC >= 60 -> Color.parseColor("#d34343")
                            tempC >= 50 -> Color.parseColor("#ffe714")
                            else -> Color.parseColor("#32e09a")
                        }
                        remoteViews.setInt(R.id.light_temp_icon, "setColorFilter", tempColor)
                        remoteViews.setTextColor(R.id.light_temp_text, tempColor)
                    } else {
                        remoteViews.setViewVisibility(R.id.light_temp_icon, View.GONE)
                        remoteViews.setViewVisibility(R.id.light_temp_text, View.GONE)
                    }
                } else {
                    remoteViews.setViewVisibility(R.id.light_device_name, View.GONE)
                    remoteViews.setViewVisibility(R.id.light_device_status, View.GONE)
                    remoteViews.setViewVisibility(R.id.light_battery_row, View.GONE)
                }

                // Only attach tap pending intents during active ride recording so Karoo's Data Pages Profile Editor receives 100% of touch gestures (long-press drag, double-tap edit/delete)
                if (ext?.isRideActive == true) {
                    val intentLeft = Intent("io.github.JaJaJim.lightonkaroo.TOGGLE_LIGHTS_LEFT").apply {
                        setPackage(context.packageName)
                    }
                    val pendingLeft = PendingIntent.getBroadcast(
                        context, 0, intentLeft, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    remoteViews.setOnClickPendingIntent(R.id.light_status_left, pendingLeft)

                    val intentRight = Intent("io.github.JaJaJim.lightonkaroo.TOGGLE_LIGHTS_RIGHT").apply {
                        setPackage(context.packageName)
                    }
                    val pendingRight = PendingIntent.getBroadcast(
                        context, 1, intentRight, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    remoteViews.setOnClickPendingIntent(R.id.light_status_right, pendingRight)
                }

                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable {
            ext?.unregisterActiveDataField(bikeProfileIndex)
            scope.cancel()
        }
    }
}
