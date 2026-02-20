package com.gigasoft.runtime

object RuntimeVersion {
    const val API_VERSION = "1"

    fun apiMajor(): Int = major(API_VERSION)

    fun isApiCompatible(pluginApiVersion: String): Boolean {
        return major(pluginApiVersion) == apiMajor()
    }

    private fun major(version: String): Int {
        return version.trim().substringBefore('.').toIntOrNull()
            ?: error("Invalid version '$version'")
    }
}
