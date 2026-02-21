plugins {
    application
    id("com.gradleup.shadow")
}

import java.io.File
import java.nio.file.Path

fun hasAnyToolOnPath(vararg toolNames: String): Boolean {
    val path = System.getenv("PATH") ?: return false
    val segments = path.split(File.pathSeparatorChar)
    return segments.any { segment ->
        toolNames.any { tool ->
            File(segment, tool).exists()
        }
    }
}

dependencies {
    implementation(project(":clockwork-api"))
    implementation(project(":clockwork-runtime"))
    implementation("info.picocli:picocli:4.7.6")
}

application {
    mainClass.set("com.clockwork.cli.GigaCliKt")
}

tasks.jar {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
}

tasks.register("windowsCliImage") {
    group = "distribution"
    description = "Builds a Windows app-image with ClockworkCli.exe (bundled runtime, no manual jar launch)"
    dependsOn("shadowJar")
    notCompatibleWithConfigurationCache("Runs external jpackage process and file-system packaging steps")
    val moduleDir = layout.projectDirectory.asFile
    val buildRoot = layout.buildDirectory.asFile.get()
    val jarProvider = layout.buildDirectory.file("libs/clockwork-cli-${project.version}.jar")
    val inputDirProvider = layout.buildDirectory.dir("windows-jpackage-input")
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        require(osName.contains("windows")) { "windowsCliImage can only run on Windows hosts" }

        val jpackage = Path.of(System.getProperty("java.home"), "bin", "jpackage.exe").toFile()
        require(jpackage.exists()) { "jpackage.exe not found in current JDK (${jpackage.absolutePath})" }

        val jar = jarProvider.get().asFile
        require(jar.exists()) { "Missing CLI jar: ${jar.absolutePath}" }

        val inputDir = inputDirProvider.get().asFile
        val outputDir = buildRoot.resolve("windows-image-${System.currentTimeMillis()}")
        if (inputDir.exists()) inputDir.deleteRecursively()
        if (outputDir.exists()) outputDir.deleteRecursively()
        inputDir.mkdirs()
        val appTargetDir = outputDir.resolve("ClockworkCli")
        jar.copyTo(inputDir.resolve(jar.name), overwrite = true)

        val command = listOf(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", "ClockworkCli",
            "--dest", outputDir.absolutePath,
            "--input", inputDir.absolutePath,
            "--main-jar", jar.name,
            "--main-class", "com.clockwork.cli.GigaCliKt",
            "--vendor", "Clockwork",
            "--description", "Clockwork CLI",
            "--java-options", "-Xms256m",
            "--java-options", "-Xmx1G"
        )
        val process = ProcessBuilder(command)
            .directory(moduleDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::println) }
        val exit = process.waitFor()
        check(exit == 0) { "Command failed (exit=$exit): ${command.joinToString(" ")}" }

        val exe = appTargetDir.resolve("ClockworkCli.exe")
        require(exe.exists()) { "Expected launcher not found: ${exe.absolutePath}" }
        println("Built Windows CLI image: ${appTargetDir.absolutePath}")
        println("Start via: ${exe.absolutePath}")
    }
}

tasks.register("windowsCliExeInstaller") {
    group = "distribution"
    description = "Builds a Windows installer .exe for ClockworkCli (requires WiX Toolset)"
    dependsOn("windowsCliImage")
    notCompatibleWithConfigurationCache("Runs external jpackage process for installer creation")
    val moduleDir = layout.projectDirectory.asFile
    val buildRoot = layout.buildDirectory.asFile.get()
    val outputDirProvider = layout.buildDirectory.dir("windows-installer")
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        require(osName.contains("windows")) { "windowsCliExeInstaller can only run on Windows hosts" }
        if (!hasAnyToolOnPath("wix.exe", "light.exe", "candle.exe")) {
            println("Skipping installer build: WiX Toolset not found in PATH.")
            println("Portable EXE is already available from windowsCliImage output.")
            return@doLast
        }

        val jpackage = Path.of(System.getProperty("java.home"), "bin", "jpackage.exe").toFile()
        require(jpackage.exists()) { "jpackage.exe not found in current JDK (${jpackage.absolutePath})" }

        val appImageDir = buildRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("windows-image-") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.resolve("ClockworkCli") }
            ?.firstOrNull { it.exists() }
            ?: throw IllegalStateException("No built app-image found. Run windowsCliImage first.")
        require(appImageDir.exists()) { "Missing app-image directory: ${appImageDir.absolutePath}" }
        val wixExtensionDll = sequenceOf(
            moduleDir.resolve(".wix/extensions/WixToolset.Util.wixext/6.0.2/wixext6/WixToolset.Util.wixext.dll"),
            moduleDir.parentFile.resolve(".wix/extensions/WixToolset.Util.wixext/6.0.2/wixext6/WixToolset.Util.wixext.dll")
        ).firstOrNull { it.exists() }
            ?: throw IllegalStateException("Missing WiX extension DLL for jpackage: WixToolset.Util.wixext.dll")
        wixExtensionDll.copyTo(appImageDir.resolve("WixToolset.Util.wixext"), overwrite = true)

        val outputDir = outputDirProvider.get().asFile
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        val command = listOf(
            jpackage.absolutePath,
            "--type", "exe",
            "--name", "ClockworkCli",
            "--dest", outputDir.absolutePath,
            "--app-image", appImageDir.absolutePath,
            "--vendor", "Clockwork",
            "--description", "Clockwork CLI"
        )
        val process = ProcessBuilder(command)
            .directory(moduleDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::println) }
        val exit = process.waitFor()
        check(exit == 0) { "Command failed (exit=$exit): ${command.joinToString(" ")}" }

        println("Built Windows CLI installer in: ${outputDir.absolutePath}")
    }
}
