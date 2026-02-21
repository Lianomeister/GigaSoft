package com.clockwork.runtime

import com.clockwork.api.CommandSender
import com.clockwork.api.PluginContext

interface RuntimeCommandExecution {
    fun execute(ctx: PluginContext, sender: CommandSender, commandLine: String): String
}
