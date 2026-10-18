package io.github.JaJaJim.lightonkaroo.protocol

/**
 * Detects auto light dimming from run-time jumps (observed on a VS1200S): in AUTO the light never reports
 * its brightness, but its spontaneous run-time reports alternate between roughly two levels as it dims itself
 * (e.g. on HIGH at ~87% battery, ~150-155 min full vs ~230-240 min dimmed). Tracks the shortest remaining time
 * seen for the current mode while auto is on as the "full" reference, and calls the light dimmed once a report
 * comes in well above that reference.
 */
class AutoDimTracker {
    private var referenceMode: Int? = null
    private var referenceMinutes: Int? = null

    /**
     * Feeds one report and returns whether the light currently looks dimmed. False whenever auto is off, or
     * [mode] or [remainingMinutes] is unknown; the reference is forgotten in that case too, since it no longer
     * means anything (the mode or auto light may have changed under it).
     */
    fun update(mode: Int?, autoOn: Boolean, remainingMinutes: Int?): Boolean {
        if (!autoOn || mode == null || remainingMinutes == null) {
            referenceMode = null
            referenceMinutes = null
            return false
        }
        if (mode != referenceMode) {
            // A new mode (or the very first reading): it becomes the new "full" reference, not dimmed yet.
            referenceMode = mode
            referenceMinutes = remainingMinutes
            return false
        }
        val reference = referenceMinutes
        if (reference == null || remainingMinutes < reference) {
            referenceMinutes = remainingMinutes
            return false
        }
        return remainingMinutes > reference * DIM_RATIO
    }

    /** Forgets the reference, e.g. when a session ends: a later reading starts a fresh one. */
    fun reset() {
        referenceMode = null
        referenceMinutes = null
    }

    private companion object {
        /** How far above the shortest seen run time counts as "dimmed" (tuned against the 150-155 vs 230-240 gap). */
        const val DIM_RATIO = 1.25
    }
}


