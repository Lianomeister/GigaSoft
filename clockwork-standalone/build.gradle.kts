plugins {
    `java-library`
    id("com.gradleup.shadow")
}

import java.nio.file.Path
import java.io.File

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
    implementation(project(":clockwork-core"))
    implementation(project(":clockwork-net"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation(project(":clockwork-demo-standalone"))
}

tasks.jar {
    enabled = false
}

tasks.named<Test>("test") {
    dependsOn(":clockwork-demo-standalone:shadowJar")
    useJUnitPlatform {
        excludeTags("performance")
    }
}

tasks.register<Test>("performanceTest") {
    group = "verification"
    description = "Runs standalone performance-oriented benchmarks"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("performance")
    }
    dependsOn(":clockwork-demo-standalone:shadowJar")
    filter {
        includeTestsMatching("*Performance*")
    }
}

tasks.register<Test>("smokeTest") {
    group = "verification"
    description = "Runs standalone smoke tests"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("smoke")
    }
    dependsOn(":clockwork-demo-standalone:shadowJar")
}

tasks.register<Test>("soakTest") {
    group = "verification"
    description = "Runs standalone soak tests"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("soak")
    }
    dependsOn(":clockwork-demo-standalone:shadowJar")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(
        ":clockwork-plugin-browser:shadowJar",
        ":clockwork-plugin-bridged:shadowJar"
    )
    val browserJar = project(":clockwork-plugin-browser")
        .layout
        .buildDirectory
        .file("libs/clockwork-plugin-browser-${project.version}.jar")
    val bridgedJar = project(":clockwork-plugin-bridged")
        .layout
        .buildDirectory
        .file("libs/clockwork-plugin-bridged-${project.version}.jar")
    from(browserJar) {
        into("default-plugins")
        rename { "clockwork-plugin-browser.jar" }
    }
    from(bridgedJar) {
        into("default-plugins")
        rename { "clockwork-plugin-bridged.jar" }
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.clockwork.standalone.MainKt"
    }
}

tasks.register("windowsServerImage") {
    group = "distribution"
    description = "Builds a Windows app-image with ClockworkServer.exe (bundled runtime, no manual jar launch)"
    dependsOn("shadowJar")
    notCompatibleWithConfigurationCache("Runs external jpackage process and file-system packaging steps")
    val moduleDir = layout.projectDirectory.asFile
    val buildRoot = layout.buildDirectory.asFile.get()
    val jarProvider = layout.buildDirectory.file("libs/clockwork-standalone-${project.version}.jar")
    val inputDirProvider = layout.buildDirectory.dir("windows-jpackage-input")
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        require(osName.contains("windows")) { "windowsServerImage can only run on Windows hosts" }

        val jpackage = Path.of(System.getProperty("java.home"), "bin", "jpackage.exe").toFile()
        require(jpackage.exists()) { "jpackage.exe not found in current JDK (${jpackage.absolutePath})" }

        val jar = jarProvider.get().asFile
        require(jar.exists()) { "Missing standalone jar: ${jar.absolutePath}" }

        val inputDir = inputDirProvider.get().asFile
        val outputDir = buildRoot.resolve("windows-image-${System.currentTimeMillis()}")
        if (inputDir.exists()) inputDir.deleteRecursively()
        if (outputDir.exists()) outputDir.deleteRecursively()
        inputDir.mkdirs()
        val appTargetDir = outputDir.resolve("ClockworkServer")
        jar.copyTo(inputDir.resolve(jar.name), overwrite = true)

        val command = listOf(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", "ClockworkServer",
            "--dest", outputDir.absolutePath,
            "--input", inputDir.absolutePath,
            "--main-jar", jar.name,
            "--main-class", "com.clockwork.standalone.MainKt",
            "--vendor", "Clockwork",
            "--description", "Clockwork Standalone Server",
            "--java-options", "-Xms1G",
            "--java-options", "-Xmx2G"
        )
        val process = ProcessBuilder(command)
            .directory(moduleDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::println) }
        val exit = process.waitFor()
        check(exit == 0) { "Command failed (exit=$exit): ${command.joinToString(" ")}" }

        val exe = appTargetDir.resolve("ClockworkServer.exe")
        require(exe.exists()) { "Expected launcher not found: ${exe.absolutePath}" }
        println("Built Windows server image: ${appTargetDir.absolutePath}")
        println("Start via: ${exe.absolutePath}")
    }
}

tasks.register("windowsServerExeInstaller") {
    group = "distribution"
    description = "Builds a Windows installer .exe for ClockworkServer (requires jpackage installer tooling)"
    dependsOn("windowsServerImage")
    notCompatibleWithConfigurationCache("Runs external jpackage process for installer creation")
    val moduleDir = layout.projectDirectory.asFile
    val buildRoot = layout.buildDirectory.asFile.get()
    val outputDirProvider = layout.buildDirectory.dir("windows-installer")
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        require(osName.contains("windows")) { "windowsServerExeInstaller can only run on Windows hosts" }
        if (!hasAnyToolOnPath("wix.exe", "light.exe", "candle.exe")) {
            println("Skipping installer build: WiX Toolset not found in PATH.")
            println("Portable EXE is already available from windowsServerImage output.")
            return@doLast
        }

        val jpackage = Path.of(System.getProperty("java.home"), "bin", "jpackage.exe").toFile()
        require(jpackage.exists()) { "jpackage.exe not found in current JDK (${jpackage.absolutePath})" }

        val appImageDir = buildRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("windows-image-") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.resolve("ClockworkServer") }
            ?.firstOrNull { it.exists() }
            ?: throw IllegalStateException("No built app-image found. Run windowsServerImage first.")
        require(appImageDir.exists()) { "Missing app-image directory: ${appImageDir.absolutePath}" }

        val outputDir = outputDirProvider.get().asFile
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        val command = listOf(
            jpackage.absolutePath,
            "--type", "exe",
            "--name", "ClockworkServer",
            "--dest", outputDir.absolutePath,
            "--app-image", appImageDir.absolutePath,
            "--vendor", "Clockwork",
            "--description", "Clockwork Standalone Server"
        )
        val process = ProcessBuilder(command)
            .directory(moduleDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::println) }
        val exit = process.waitFor()
        check(exit == 0) { "Command failed (exit=$exit): ${command.joinToString(" ")}" }

        println("Built Windows installer in: ${outputDir.absolutePath}")
    }
}
