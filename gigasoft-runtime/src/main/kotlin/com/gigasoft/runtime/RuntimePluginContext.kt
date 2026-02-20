package com.gigasoft.runtime

import com.gigasoft.api.*

class RuntimePluginContext(
    override val manifest: PluginManifest,
    override val logger: GigaLogger,
    override val scheduler: Scheduler,
    override val registry: RegistryFacade,
    override val storage: StorageProvider,
    override val commands: CommandRegistry,
    override val events: EventBus
) : PluginContext
