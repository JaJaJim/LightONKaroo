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
            engine.activeZone.collect { activeZone ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId = dataTypeId, values = mapOf(
                            FIELD_ACTIVE to if (activeZone != null) 1.0 else 0.0,
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

        scope.launch {
            combine(engine.activeZone, engine.displayInfo) { zone, info -> zone to info }
                .collect { (activeZone, info) ->
                    val remoteViews = RemoteViews(context.packageName, R.layout.light_status_view)

                    val globalStatusText = if (activeZone != null) "ON" else "OFF"
                    remoteViews.setTextViewText(R.id.light_mode_text, globalStatusText)
                    val globalColor = if (activeZone != null) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                    remoteViews.setTextColor(R.id.light_mode_text, globalColor)

                    // Detailed rotation info
                    if (engine.settings.showDetailedStatus) {
                        remoteViews.setViewVisibility(R.id.light_device_name, android.view.View.VISIBLE)
                        remoteViews.setViewVisibility(R.id.light_battery_text, android.view.View.VISIBLE)

                        val deviceNameStatus = if (info.deviceName.isNotEmpty()) "${info.deviceName} ${info.statusText}" else info.statusText
                        remoteViews.setTextViewText(R.id.light_device_name, deviceNameStatus)
                        
                        var batteryText = if (info.batteryPercent != null) "Battery: ${info.batteryPercent}%" else ""
                        if (info.batteryPercent != null && info.batteryFromRadar) {
                            batteryText += " (Radar)"
                        }
                        remoteViews.setTextViewText(R.id.light_battery_text, batteryText)
                        remoteViews.setTextColor(R.id.light_battery_text, info.batteryColor)
                    } else {
                        remoteViews.setViewVisibility(R.id.light_device_name, android.view.View.GONE)
                        remoteViews.setViewVisibility(R.id.light_battery_text, android.view.View.GONE)
                    }

                    val intent = Intent("io.github.JaJaJim.lightonkaroo.TOGGLE_LIGHTS").apply {
                        setPackage(context.packageName)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    remoteViews.setOnClickPendingIntent(R.id.light_status_root, pendingIntent)

                    emitter.updateView(remoteViews)
                }
        }

        emitter.setCancellable { scope.cancel() }
    }
}
