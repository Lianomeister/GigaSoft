package com.clockwork.runtime

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
    val reason: String? = null,
    val checkpointChangedPlugins: List<String> = emptyList(),
    val rollbackRecoveredPlugins: List<String> = emptyList(),
    val rollbackFailedPlugins: List<String> = emptyList(),
    val rollbackDataRestored: Boolean = false
)
