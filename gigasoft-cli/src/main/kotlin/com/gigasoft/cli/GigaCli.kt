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
    subcommands = [ScaffoldCommand::class, DoctorCommand::class, ServerCommand::class],
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

@Command(
    name = "server",
    description = ["Simple local server control via docker compose"],
    subcommands = [ServerStartCommand::class, ServerStopCommand::class, ServerStatusCommand::class, ServerLogsCommand::class]
)
class ServerCommand : Callable<Int> {
    override fun call(): Int {
        println("Use a subcommand: start, stop, status, logs")
        return 0
    }
}

abstract class BaseServerCommand : Callable<Int> {
    @Option(names = ["--project-dir"], defaultValue = ".")
    lateinit var projectDir: Path

    protected fun dockerCompose(vararg args: String): Int {
        val composeFile = projectDir.resolve("docker-compose.yml")
        if (!Files.exists(composeFile)) {
            System.err.println("Missing docker-compose.yml at $composeFile")
            return 1
        }

        val command = mutableListOf("docker", "compose", "-f", composeFile.toAbsolutePath().toString())
        command.addAll(args)

        return try {
            val process = ProcessBuilder(command)
                .directory(projectDir.toFile())
                .inheritIO()
                .start()
            process.waitFor()
        } catch (t: Throwable) {
            System.err.println("Failed to run docker compose: ${t.message}")
            1
        }
    }
}

@Command(name = "start", description = ["Start local server stack"])
class ServerStartCommand : BaseServerCommand() {
    override fun call(): Int = dockerCompose("up", "-d")
}

@Command(name = "stop", description = ["Stop local server stack"])
class ServerStopCommand : BaseServerCommand() {
    override fun call(): Int = dockerCompose("down")
}

@Command(name = "status", description = ["Show local server status"])
class ServerStatusCommand : BaseServerCommand() {
    override fun call(): Int = dockerCompose("ps")
}

@Command(name = "logs", description = ["Show local server logs"])
class ServerLogsCommand : BaseServerCommand() {
    @Option(names = ["--follow"], defaultValue = "false")
    var follow: Boolean = false

    override fun call(): Int {
        return if (follow) dockerCompose("logs", "-f") else dockerCompose("logs")
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(RootCommand()).execute(*args)
    kotlin.system.exitProcess(exitCode)
}
