package com.gigasoft.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.writeText

@Command(
    name = "giga",
    mixinStandardHelpOptions = true,
    subcommands = [ScaffoldCommand::class, DoctorCommand::class],
    description = ["GigaSoft developer CLI"]
)
class RootCommand : Callable<Int> {
    override fun call(): Int {
        println("Use a subcommand. Try: giga --help")
        return 0
    }
}

@Command(name = "scaffold", description = ["Create a starter GigaPlugin module"])
class ScaffoldCommand : Callable<Int> {
    @Option(names = ["--id"], required = true)
    lateinit var id: String

    @Option(names = ["--dir"], defaultValue = ".")
    lateinit var dir: Path

    override fun call(): Int {
        val root = dir.resolve(id)
        val src = root.resolve("src/main/kotlin/com/example")
        val resources = root.resolve("src/main/resources")
        Files.createDirectories(src)
        Files.createDirectories(resources)

        val className = id.replaceFirstChar { it.uppercase() } + "Plugin"
        src.resolve("$className.kt").writeText(
            """
            package com.example

            import com.gigasoft.api.GigaPlugin
            import com.gigasoft.api.PluginContext

            class $className : GigaPlugin {
                override fun onEnable(ctx: PluginContext) {
                    ctx.logger.info("Plugin $id enabled")
                }

                override fun onDisable(ctx: PluginContext) {
                    ctx.logger.info("Plugin $id disabled")
                }
            }
            """.trimIndent()
        )

        resources.resolve("gigaplugin.yml").writeText(
            """
            id: $id
            name: $id
            version: 0.1.0
            main: com.example.$className
            apiVersion: 1
            dependencies: []
            permissions: []
            """.trimIndent()
        )

        println("Scaffolded plugin at $root")
        return 0
    }
}

@Command(name = "doctor", description = ["Check local runtime layout"])
class DoctorCommand : Callable<Int> {
    @Option(names = ["--runtime"], defaultValue = "dev-runtime")
    lateinit var runtimeDir: Path

    override fun call(): Int {
        val plugins = runtimeDir.resolve("plugins")
        val gigaPlugins = runtimeDir.resolve("giga-plugins")
        val world = runtimeDir.resolve("world")

        println("Runtime doctor")
        println("- runtime: $runtimeDir (${Files.exists(runtimeDir)})")
        println("- plugins: $plugins (${Files.exists(plugins)})")
        println("- giga-plugins: $gigaPlugins (${Files.exists(gigaPlugins)})")
        println("- world: $world (${Files.exists(world)})")
        return 0
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(RootCommand()).execute(*args)
    kotlin.system.exitProcess(exitCode)
}
