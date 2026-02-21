import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.1.10" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3" apply false
}

allprojects {
    group = "com.clockwork"
    version = "1.5.0-rc.2"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(kotlin("test"))
    }
}

tasks.register("buildPlugin") {
    dependsOn(
        ":clockwork-demo-standalone:shadowJar",
        ":clockwork-standalone:shadowJar"
    )
}

tasks.register("runServer") {
    dependsOn("buildPlugin")
    doLast {
        println("Artifacts built. Use clockwork-standalone for runtime execution.")
    }
}

tasks.register("reloadPlugin") {
    doLast {
        println("Use standalone console command: reload all")
    }
}

abstract class StandaloneReleaseBundleTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val versionLabel: Property<String>

    @TaskAction
    fun buildBundle() {
        val destination = outputDir.get().asFile
        if (destination.exists()) {
            destination.deleteRecursively()
        }
        destination.mkdirs()

        val jarFiles = jars.files.sortedBy { it.name }
        require(jarFiles.size == 3) {
            "Expected exactly 3 release jars (standalone, cli, demo), found ${jarFiles.size}"
        }
        require(jarFiles.any { it.name.startsWith("clockwork-standalone-") }) {
            "Release bundle missing standalone jar"
        }
        require(jarFiles.any { it.name.startsWith("clockwork-cli-") }) {
            "Release bundle missing cli jar"
        }
        require(jarFiles.any { it.name.startsWith("clockwork-demo-standalone-") }) {
            "Release bundle missing demo jar"
        }

        jarFiles.forEach { file ->
            require(file.exists()) { "Missing release artifact: ${file.absolutePath}" }
            java.nio.file.Files.copy(
                file.toPath(),
                destination.resolve(file.name).toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        val copiedJars = destination.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

        val checksums = mutableListOf<String>()
        val manifestEntries = mutableListOf<String>()
        copiedJars.forEach { jar ->
            val sha = sha256(jar)
            checksums += "$sha  ${jar.name}"
            manifestEntries += """  {"name":"${jar.name}","sizeBytes":${jar.length()},"sha256":"$sha"}"""
        }

        destination.resolve("SHA256SUMS.txt").writeText(checksums.joinToString("\n") + "\n")
        destination.resolve("ARTIFACTS.txt").writeText(copiedJars.joinToString("\n") { it.name } + "\n")
        destination.resolve("ARTIFACTS.json").writeText(
            "{\n" +
                "  \"version\": \"${versionLabel.get()}\",\n" +
                "  \"artifacts\": [\n" +
                manifestEntries.joinToString(",\n") +
                "\n  ]\n" +
                "}\n"
        )
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

tasks.register<StandaloneReleaseBundleTask>("standaloneReleaseCandidateArtifacts") {
    dependsOn(
        ":clockwork-demo-standalone:shadowJar",
        ":clockwork-cli:shadowJar",
        ":clockwork-standalone:shadowJar"
    )
    val bundleVersion = project.version.toString()
    val demoJar = project(":clockwork-demo-standalone").layout.buildDirectory.file("libs/clockwork-demo-standalone-$bundleVersion.jar")
    val cliJar = project(":clockwork-cli").layout.buildDirectory.file("libs/clockwork-cli-$bundleVersion.jar")
    val standaloneJar = project(":clockwork-standalone").layout.buildDirectory.file("libs/clockwork-standalone-$bundleVersion.jar")
    jars.from(demoJar, cliJar, standaloneJar)
    outputDir.set(layout.buildDirectory.dir("release-standalone/$bundleVersion"))
    versionLabel.set(bundleVersion)
}

tasks.register("standaloneReleaseCandidate") {
    group = "release"
    description = "Builds and verifies standalone release candidate artifacts"
    dependsOn(
        ":clockwork-api:apiCheck",
        ":clockwork-api:test",
        ":clockwork-host-api:test",
        ":clockwork-net:test",
        ":clockwork-runtime:test",
        ":clockwork-core:test",
        ":clockwork-standalone:test",
        ":clockwork-cli:test",
        ":clockwork-demo-standalone:test",
        "standaloneReleaseCandidateArtifacts"
    )
}


tasks.register("performanceBaseline") {
    group = "verification"
    description = "Runs lightweight performance baselines for runtime/core/standalone"
    dependsOn(
        ":clockwork-runtime:performanceTest",
        ":clockwork-core:performanceTest",
        ":clockwork-standalone:performanceTest"
    )
}

tasks.register("smokeTest") {
    group = "verification"
    description = "Runs smoke test pipeline for standalone operations"
    dependsOn(":clockwork-standalone:smokeTest")
}

tasks.register("integrationSmoke") {
    group = "verification"
    description = "Runs integration smoke suite for standalone parity and deterministic lifecycle behavior"
    dependsOn("smokeTest")
}

tasks.register("soakTest") {
    group = "verification"
    description = "Runs soak test pipeline for runtime and standalone operations"
    dependsOn(
        ":clockwork-runtime:soakTest",
        ":clockwork-standalone:soakTest"
    )
}


