package com.gigasoft.bridge.paper

import com.gigasoft.api.GigaLogger
import com.gigasoft.host.api.HostBridgeAdapters
import com.gigasoft.runtime.LoadedPlugin
import org.bukkit.Server

object PaperBridgeAdapters {
    fun registerDefaults(plugin: LoadedPlugin, server: Server, logger: GigaLogger) {
        HostBridgeAdapters.registerDefaults(
            pluginId = plugin.manifest.id,
            registry = plugin.context.adapters,
            hostBridge = PaperHostBridge(server),
            logger = logger,
            bridgeName = "Paper"
        )
    }
}
