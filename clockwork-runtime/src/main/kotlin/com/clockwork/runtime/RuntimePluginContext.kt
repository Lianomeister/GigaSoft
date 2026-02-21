package com.clockwork.runtime

import com.clockwork.api.*

class RuntimePluginContext(
    override val manifest: PluginManifest,
    override val logger: GigaLogger,
    override val scheduler: Scheduler,
    override val registry: RegistryFacade,
    override val adapters: ModAdapterRegistry,
    override val storage: StorageProvider,
    override val commands: CommandRegistry,
    override val events: EventBus,
    override val network: PluginNetwork = PluginNetwork.unavailable(),
    override val ui: PluginUi = PluginUi.unavailable(),
    override val host: HostAccess = HostAccess.unavailable()
) : PluginContext
