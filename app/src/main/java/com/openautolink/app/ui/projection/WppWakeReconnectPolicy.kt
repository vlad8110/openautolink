package com.openautolink.app.ui.projection

/** Pure admission policy for rebuilding an idle WPP projection session after wake. */
internal object WppWakeReconnectPolicy {
    fun preStartRejection(
        wppSelectedNow: Boolean,
        ignitionOff: Boolean,
        sessionIdle: Boolean,
        currentWppOwnerPresent: Boolean = false,
    ): String? = when {
        !wppSelectedNow -> "transport-changed"
        ignitionOff -> "ignition-off"
        !sessionIdle -> "session-not-idle"
        currentWppOwnerPresent -> "session-owner-active"
        else -> null
    }

    fun shouldKickWake(
        wppSelected: Boolean,
        connectInFlight: Boolean,
        sessionIdle: Boolean,
        currentWppOwnerPresent: Boolean = false,
    ): Boolean = wppSelected && !connectInFlight && sessionIdle && !currentWppOwnerPresent

    fun shouldKickIgnition(
        wppSelected: Boolean,
        connectInFlight: Boolean,
        sessionIdle: Boolean,
        currentWppOwnerPresent: Boolean = false,
    ): Boolean = shouldKickWake(wppSelected, connectInFlight, sessionIdle, currentWppOwnerPresent)
}
