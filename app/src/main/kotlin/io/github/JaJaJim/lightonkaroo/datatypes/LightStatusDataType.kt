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
) : DataTypeImpl("light-on-karoo", "light-status") {

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

        // Start rotation when data field is visible
        ext?.startDisplayRotation()

        scope.launch {
            val actualModesFlow = ext?.lightControl?.actualModes ?: MutableStateFlow(emptyMap())
            combine(
                engine.activeState,
                engine.displayInfo,
                actualModesFlow,
            ) { state, info, actualModes ->
                Triple(state, info, actualModes)
            }.collect { (activeState, info, actualModes) ->
                val remoteViews = RemoteViews(context.packageName, R.layout.light_status_view)

                val globalStatusText = when (activeState) {
                    1 -> if (engine.settings.threeModeEnabled) "PRIMARY" else "ON"
                    2 -> "SECONDARY"
                    else -> "OFF"
                }
                remoteViews.setTextViewText(R.id.light_mode_text, globalStatusText)
                val globalColor = if (activeState != 0) Color.parseColor("#ffe714") else Color.WHITE
                remoteViews.setTextColor(R.id.light_mode_text, globalColor)

                // Top-left indicator icon: Turquoise if ALL active lights connected, White if any not found
                val indicatorColor = if (info.allConnected) Color.parseColor("#32e09a") else Color.WHITE
                remoteViews.setInt(R.id.light_indicator_tiny, "setColorFilter", indicatorColor)

                // UI Customization: Logo and Glow
                val showLogo = engine.settings.showLogo
                remoteViews.setViewVisibility(R.id.light_icon, if (showLogo) View.VISIBLE else View.GONE)
                if (showLogo) {
                    val logoAlpha = if (activeState != 0) 1.0f else 0.35f
                    remoteViews.setFloat(R.id.light_icon, "setAlpha", logoAlpha)
                }

                val intensity = engine.settings.glowIntensity
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

                // Detailed rotation info (High-Priority instant updates for radar & active state)
                if (engine.settings.showDetailedStatus) {
                    remoteViews.setViewVisibility(R.id.light_device_name, View.VISIBLE)
                    remoteViews.setViewVisibility(R.id.light_device_status, View.VISIBLE)
                    remoteViews.setViewVisibility(R.id.light_battery_row, View.VISIBLE)

                    remoteViews.setTextViewText(R.id.light_device_name, info.deviceName)

                    if (info.isSimulatedRadar) {
                        val simText = when (info.simThreatLevel) {
                            2 -> "SIM: ${info.simVehicleCount} Cars (FAST)"
                            1 -> "SIM: ${info.simVehicleCount} Car"
                            else -> "SIM: 0 Cars (CLEAR)"
                        }
                        val simColor = when (info.simThreatLevel) {
                            2 -> Color.parseColor("#d34343")
                            1 -> Color.parseColor("#ffe714")
                            else -> Color.parseColor("#32e09a")
                        }
                        remoteViews.setTextViewText(R.id.light_device_status, simText)
                        remoteViews.setTextColor(R.id.light_device_status, simColor)
                    } else {
                        remoteViews.setTextViewText(R.id.light_device_status, info.statusText)
                    }

                    // Only show (Radar) suffix
                    val batterySuffix = if (info.batteryFromRadar) "(Radar)" else ""
                    remoteViews.setTextViewText(R.id.light_battery_text, batterySuffix)
                    remoteViews.setTextColor(R.id.light_battery_text, info.batteryColor)

                    // Radar Threat Icon Status (High Priority Verification)
                    val threatAssignments = engine.settings.lightAssignments.filter { it.enabled && it.useForThreatMode && it.protocol == LightProtocol.ANT_PLUS }
                    val hasThreatMode = threatAssignments.isNotEmpty() || info.isSimulatedRadar

                    if (hasThreatMode) {
                        remoteViews.setViewVisibility(R.id.light_radar_icon, View.VISIBLE)
                        val isThreatActive = ext?.isRadarThreatActive == true

                        val allThreatLightsConfirmed = threatAssignments.isNotEmpty() && threatAssignments.all { assignment ->
                            val actualMode = actualModes[assignment.deviceId]
                            val targetThreatMode = assignment.softwareThreatMode
                            actualMode == targetThreatMode || targetThreatMode == "DISABLED"
                        }

                        val radarColor = if (isThreatActive && allThreatLightsConfirmed) {
                            Color.parseColor("#32e09a")
                        } else {
                            Color.WHITE
                        }
                        remoteViews.setInt(R.id.light_radar_icon, "setColorFilter", radarColor)
                    } else {
                        remoteViews.setViewVisibility(R.id.light_radar_icon, View.GONE)
                    }

                    // Update battery icon based on level
                    val batteryIconRes = when (info.batteryLabel) {
                        "Good" -> R.drawable.ic_battery_good
                        "Medium" -> R.drawable.ic_battery_medium
                        else -> R.drawable.ic_battery_low
                    }
                    remoteViews.setImageViewResource(R.id.light_battery_icon, batteryIconRes)
                    remoteViews.setInt(R.id.light_battery_icon, "setColorFilter", info.batteryColor)

                    // Temperature Indicator
                    if (info.temperature != null) {
                        remoteViews.setViewVisibility(R.id.light_temp_icon, View.VISIBLE)
                        remoteViews.setViewVisibility(R.id.light_temp_text, View.VISIBLE)
                        val tempC = info.temperature
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

                // Fallback for older Karoo system/settings: keep root clickable to turn ON
                val intentRoot = Intent("io.github.JaJaJim.lightonkaroo.TOGGLE_LIGHTS_LEFT").apply {
                    setPackage(context.packageName)
                }
                val pendingRoot = PendingIntent.getBroadcast(
                    context, 2, intentRoot, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                remoteViews.setOnClickPendingIntent(R.id.light_status_root, pendingRoot)

                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable {
            // Stop rotation when data field is no longer visible
            ext?.stopDisplayRotation()
            scope.cancel()
        }
    }
}
