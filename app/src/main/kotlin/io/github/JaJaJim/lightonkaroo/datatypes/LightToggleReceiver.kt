package io.github.JaJaJim.lightonkaroo.datatypes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension
import timber.log.Timber

class LightToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("LightToggleReceiver: Received intent action=${intent.action}")
        val engine = KarooLightControllerExtension.getInstance()?.engine ?: return
        when (intent.action) {
            "io.github.JaJaJim.lightonkaroo.TOGGLE_LIGHTS",
            "io.github.JaJaJim.lightonkaroo.TOGGLE_LIGHTS_LEFT" -> engine.onToggleLights()
            "io.github.JaJaJim.lightonkaroo.TOGGLE_LIGHTS_RIGHT" -> engine.onRightClick()
        }
    }
}
