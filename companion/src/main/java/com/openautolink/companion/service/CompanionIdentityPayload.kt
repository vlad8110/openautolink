package com.openautolink.companion.service

/** Builds the backward-compatible identity reply consumed by the car app. */
internal object CompanionIdentityPayload {
    fun encode(
        phoneId: String,
        friendlyName: String,
        bluetoothName: String?,
        proxyPort: Int,
    ): String {
        // No live proxy: omit wpp= so the car treats the reply as "no WPP endpoint".
        val wppField = if (proxyPort in 1..65535) "\twpp=$proxyPort" else ""
        val bluetoothField = "\tbt_name=${wireValue(bluetoothName.orEmpty())}"
        return "OAL!${wireValue(phoneId)}\t${wireValue(friendlyName)}" +
            "$wppField$bluetoothField\n"
    }

    private fun wireValue(value: String): String = value
        .replace(Regex("[\\t\\r\\n]+"), " ")
        .trim()
}
