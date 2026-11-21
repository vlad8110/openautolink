package com.openautolink.app.diagnostics

enum class DiagnosticLevel {
    DEBUG, INFO, WARN, ERROR;

    fun toWire(): String = name

    companion object {
        fun fromWire(value: String): DiagnosticLevel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INFO
    }
}
