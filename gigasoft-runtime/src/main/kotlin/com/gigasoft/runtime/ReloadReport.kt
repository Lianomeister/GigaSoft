package com.gigasoft.runtime

enum class ReloadStatus {
    SUCCESS,
    ROLLED_BACK,
    FAILED
}

data class ReloadReport(
    val target: String,
    val affectedPlugins: List<String>,
    val reloadedPlugins: List<String>,
    val status: ReloadStatus,
    val reason: String? = null
)

