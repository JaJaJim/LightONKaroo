package io.github.JaJaJim.lightonkaroo.ble

import kotlinx.coroutines.flow.Flow

/** A connection to one light. Collecting [connect] opens (and keeps re-opening) the link; cancelling closes it. */
interface LightLink {
    fun connect(address: String): Flow<LinkEvent>

    /** Queues a whole frame; the link splits it into 20-byte writes. False when not connected. */
    fun send(frame: ByteArray): Boolean
}

sealed interface LinkEvent {
    data object Connected : LinkEvent
    data object Disconnected : LinkEvent
    /** One raw notification from the light: a fragment of a frame, not necessarily a whole one. */
    class Fragment(val bytes: ByteArray) : LinkEvent
}


