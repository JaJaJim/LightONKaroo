package io.github.JaJaJim.lightonkaroo.light

/** The active light session, shared by the extension, the ride field, bonus actions and the app page (same process). */
object LightHub {
    @Volatile
    var session: LightSession? = null
}


