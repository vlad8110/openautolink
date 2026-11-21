package com.carlink.logging

import com.openautolink.app.diagnostics.OalLog

/** Routes the lifted Carlink core's logging into OalLog. */
object Logger {
    object Tags {
        const val PROTO = "Carlinkit"
        const val PROTO_UNKNOWN = "CarlinkitProto"
        const val VIDEO = "CarlinkitVideo"
        const val VIDEO_USB = "CarlinkitUsb"
    }
}

fun logDebug(message: String, tag: String? = null) = OalLog.d(tag ?: Logger.Tags.PROTO, message)
fun logInfo(message: String, tag: String? = null) = OalLog.i(tag ?: Logger.Tags.PROTO, message)
fun logWarn(message: String, tag: String? = null) = OalLog.w(tag ?: Logger.Tags.PROTO, message)
fun logError(message: String, tag: String? = null, throwable: Throwable? = null) =
    OalLog.e(tag ?: Logger.Tags.PROTO, if (throwable == null) message else "$message: $throwable")
