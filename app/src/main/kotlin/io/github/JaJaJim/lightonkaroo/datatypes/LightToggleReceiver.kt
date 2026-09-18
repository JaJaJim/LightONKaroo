package io.github.JaJaJim.lightonkaroo.datatypes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension
import timber.log.Timber

class LightToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("LightToggleReceiver: Received toggle intent")
        KarooLightControllerExtension.getInstance()?.engine?.onToggleLights()
    }
}
