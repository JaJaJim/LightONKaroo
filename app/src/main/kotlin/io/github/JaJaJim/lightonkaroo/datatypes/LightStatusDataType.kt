package io.github.JaJaJim.lightonkaroo.datatypes

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.JaJaJim.lightonkaroo.R
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
        val ext = io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension.getInstance()

        // Start rotation when data field is visible
        ext?.startDisplayRotation()

        scope.launch {
            combine(engine.activeState, engine.displayInfo) { state, info -> state to info }
                .collect { (activeState, info) ->
                    val remoteViews = RemoteViews(context.packageName, R.layout.light_status_view)

                    val globalStatusText = when (activeState) {
                        1 -> if (engine.settings.threeModeEnabled) "PRIMARY" else "ON"
                        2 -> "SECONDARY"
                        else -> "OFF"
                    }
                    remoteViews.setTextViewText(R.id.light_mode_text, globalStatusText)
                    val globalColor = if (activeState != 0) android.graphics.Color.parseColor("#f5e315") else android.graphics.Color.WHITE
                    val indicatorColor = if (activeState != 0) android.graphics.Color.parseColor("#27D9B4") else android.graphics.Color.WHITE
                    remoteViews.setTextColor(R.id.light_mode_text, globalColor)

                    // Top-left indicator icon
                    remoteViews.setInt(R.id.light_indicator_tiny, "setColorFilter", indicatorColor)

                    // Detailed rotation info
                    if (engine.settings.showDetailedStatus) {
                        remoteViews.setViewVisibility(R.id.light_device_name, android.view.View.VISIBLE)
                        remoteViews.setViewVisibility(R.id.light_device_status, android.view.View.VISIBLE)
                        remoteViews.setViewVisibility(R.id.light_battery_row, android.view.View.VISIBLE)

                        remoteViews.setTextViewText(R.id.light_device_name, info.deviceName)
                        remoteViews.setTextViewText(R.id.light_device_status, info.statusText)
                        
                        var batteryText = if (info.batteryLabel.isNotEmpty()) info.batteryLabel else ""
                        if (info.batteryLabel.isNotEmpty() && info.batteryFromRadar) {
                            batteryText += " (Radar)"
                        }
                        remoteViews.setTextViewText(R.id.light_battery_text, batteryText)
                        remoteViews.setTextColor(R.id.light_battery_text, info.batteryColor)

                        // Update battery icon based on level
                        val batteryIconRes = when (info.batteryLabel) {
                            "Good" -> R.drawable.ic_battery_good
                            "Medium" -> R.drawable.ic_battery_medium
                            else -> R.drawable.ic_battery_low
                        }
                        remoteViews.setImageViewResource(R.id.light_battery_icon, batteryIconRes)
                        remoteViews.setInt(R.id.light_battery_icon, "setColorFilter", info.batteryColor)
                    } else {
                        remoteViews.setViewVisibility(R.id.light_device_name, android.view.View.GONE)
                        remoteViews.setViewVisibility(R.id.light_device_status, android.view.View.GONE)
                        remoteViews.setViewVisibility(R.id.light_battery_row, android.view.View.GONE)
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
