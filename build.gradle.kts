import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

plugins {
    kotlin("jvm") version "2.1.10" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3" apply false
}

allprojects {
    group = "com.clockwork"
    version = "0.18.3"

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
        ":clockwork-plugin-browser:shadowJar",
        ":clockwork-plugin-bridged:shadowJar",
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

abstract class ApiCompatibilityReportTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun writeReport() {
        val files = apiFiles.files.sortedBy { it.name }
        require(files.isNotEmpty()) { "No API baseline files found for compatibility report" }
        val lines = mutableListOf<String>()
        lines += "# API Compatibility Report"
        lines += ""
        lines += "Generated by task `apiCompatibilityReport`."
        lines += ""
        lines += "| Module | Baseline File | SHA-256 |"
        lines += "|---|---|---|"
        files.forEach { file ->
            val module = file.parentFile?.parentFile?.name ?: "unknown"
            lines += "| $module | ${file.name} | ${sha256(file)} |"
        }
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(lines.joinToString("\n") + "\n")
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

abstract class ApiContractFreezeGateTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val currentApiFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineV16Files: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineV1xFiles: ConfigurableFileCollection

    @get:Input
    abstract val allowBreaking: Property<Boolean>

    @get:InputFile
    @get:Optional
    abstract val exceptionsFile: org.gradle.api.file.RegularFileProperty

    @get:InputFile
    abstract val contractDocFile: org.gradle.api.file.RegularFileProperty

    @get:OutputFile
    abstract val reportFile: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun verifyFreeze() {
        val current = currentApiFiles.files.associateBy { it.name }
        val base16 = baselineV16Files.files.associateBy { it.name }
        val base1x = baselineV1xFiles.files.associateBy { it.name }
        val docFile = contractDocFile.get().asFile
        require(docFile.exists()) { "Missing API contract doc: ${docFile.path}" }

        val allow = allowBreaking.getOrElse(false)
        val docText = docFile.readText().lowercase()
        val errors = mutableListOf<String>()
        val removedDetails = linkedMapOf<String, List<String>>()
        val rows = mutableListOf<String>()
        rows += "| Module | Current SHA | Removed vs v1.6 | Removed vs v1.x | Added vs v1.6 | Added vs v1.x |"
        rows += "|---|---|---:|---:|---:|---:|"

        val exceptions = loadExceptions(exceptionsFile.orNull?.asFile)

        current.toSortedMap().forEach { (name, currentFile) ->
            val currentSymbols = symbols(currentFile)
            val v16Symbols = symbols(base16[name])
            val v1xSymbols = symbols(base1x[name])
            if (!base16.containsKey(name)) errors += "Missing v1.6 baseline for $name"
            if (!base1x.containsKey(name)) errors += "Missing v1.x baseline for $name"

            val removedV16 = (v16Symbols - currentSymbols).sorted()
            val removedV1x = (v1xSymbols - currentSymbols).sorted()
            val addedV16 = currentSymbols - v16Symbols
            val addedV1x = currentSymbols - v1xSymbols

            if (removedV16.isNotEmpty() || removedV1x.isNotEmpty()) {
                val combinedRemoved = (removedV16 + removedV1x).distinct().sorted()
                removedDetails[name] = combinedRemoved
                if (!allow) {
                    errors += "Breaking change detected in $name (use -PapiBreakingApproved=true with explicit exception metadata)"
                } else {
                    val allowed = exceptions.allowedRemoved[name] ?: emptySet()
                    if (!exceptions.enabled) {
                        errors += "apiBreakingApproved=true but exceptions are disabled in ${exceptions.path}"
                    }
                    if (exceptions.ticket.isBlank() || exceptions.approvedBy.isBlank()) {
                        errors += "apiBreakingApproved=true requires non-empty ticket and approvedBy in ${exceptions.path}"
                    }
                    val unapproved = combinedRemoved.filter { it !in allowed }
                    if (unapproved.isNotEmpty()) {
                        errors += "Unapproved breaking symbols in $name: ${unapproved.take(5).joinToString("; ")}"
                    }
                }
            }

            val hash = sha256(currentFile)
            if (!docText.contains("$name sha256: ${hash.lowercase()}")) {
                errors += "Contract doc not aligned for $name sha256 ($hash) in ${docFile.path}"
            }

            rows += "| $name | $hash | ${removedV16.size} | ${removedV1x.size} | ${addedV16.size} | ${addedV1x.size} |"
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        val lines = mutableListOf<String>()
        lines += "# API Contract Freeze Report v1.7"
        lines += ""
        lines += "- allowBreaking: `$allow`"
        lines += "- exceptions: `${exceptions.path}`"
        lines += "- contractDoc: `${docFile.path}`"
        lines += ""
        lines += "## Baseline Matrix"
        lines += ""
        lines += rows
        lines += ""
        if (removedDetails.isNotEmpty()) {
            lines += "## Removed Symbols"
            lines += ""
            removedDetails.forEach { (module, removed) ->
                lines += "### $module"
                removed.forEach { symbol -> lines += "- `$symbol`" }
                lines += ""
            }
        }
        if (errors.isEmpty()) {
            lines += "## Result: PASSED"
        } else {
            lines += "## Result: FAILED"
            lines += ""
            lines += "## Errors"
            lines += ""
            errors.forEach { lines += "- $it" }
        }
        report.writeText(lines.joinToString("\n") + "\n")

        if (errors.isNotEmpty()) {
            throw org.gradle.api.GradleException("API contract freeze gate failed. See ${report.path}")
        }
    }

    private data class BreakingExceptions(
        val enabled: Boolean,
        val ticket: String,
        val approvedBy: String,
        val allowedRemoved: Map<String, Set<String>>,
        val path: String
    )

    private fun loadExceptions(file: java.io.File?): BreakingExceptions {
        if (file == null || !file.exists()) {
            return BreakingExceptions(
                enabled = false,
                ticket = "",
                approvedBy = "",
                allowedRemoved = emptyMap(),
                path = file?.path ?: "<missing>"
            )
        }
        val props = Properties()
        file.inputStream().use { props.load(it) }
        val allowed = linkedMapOf<String, Set<String>>()
        props.stringPropertyNames()
            .filter { it.endsWith(".allowedRemoved") }
            .forEach { key ->
                val module = key.removeSuffix(".allowedRemoved")
                val values = props.getProperty(key, "")
                    .split("|")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                allowed[module] = values
            }
        return BreakingExceptions(
            enabled = props.getProperty("enabled", "false").trim().equals("true", ignoreCase = true),
            ticket = props.getProperty("ticket", "").trim(),
            approvedBy = props.getProperty("approvedBy", "").trim(),
            allowedRemoved = allowed,
            path = file.path
        )
    }

    private fun symbols(file: java.io.File?): Set<String> {
        if (file == null || !file.exists()) return emptySet()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("//") }
            .toSet()
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
        ":clockwork-host-api:apiCheck",
        "apiContractsFreezeGate",
        ":clockwork-api:test",
        ":clockwork-host-api:test",
        ":clockwork-net:test",
        ":clockwork-runtime:test",
        ":clockwork-core:test",
        ":clockwork-standalone:test",
        ":clockwork-cli:test",
        ":clockwork-demo-standalone:test",
        ":clockwork-plugin-browser:test",
        ":clockwork-plugin-bridged:test",
        "standaloneReleaseCandidateArtifacts"
    )
}

tasks.register<ApiCompatibilityReportTask>("apiCompatibilityReport") {
    group = "verification"
    description = "Generates deterministic API compatibility baseline report for public modules"
    dependsOn(
        ":clockwork-api:apiCheck",
        ":clockwork-host-api:apiCheck"
    )
    apiFiles.from(
        project(":clockwork-api").layout.projectDirectory.file("api/clockwork-api.api"),
        project(":clockwork-host-api").layout.projectDirectory.file("api/clockwork-host-api.api")
    )
    reportFile.set(layout.buildDirectory.file("reports/api-compatibility/report.md"))
}

tasks.register<ApiContractFreezeGateTask>("apiContractsFreezeGate") {
    group = "verification"
    description = "Enforces v1.7 API freeze against v1.6/v1.x baselines with explicit exception metadata"
    dependsOn(
        ":clockwork-api:apiCheck",
        ":clockwork-host-api:apiCheck",
        ":clockwork-api:contractTest",
        ":clockwork-host-api:contractTest"
    )
    currentApiFiles.from(
        project(":clockwork-api").layout.projectDirectory.file("api/clockwork-api.api"),
        project(":clockwork-host-api").layout.projectDirectory.file("api/clockwork-host-api.api")
    )
    baselineV16Files.from(
        layout.projectDirectory.file("docs/api/baselines/v1.6/clockwork-api.api"),
        layout.projectDirectory.file("docs/api/baselines/v1.6/clockwork-host-api.api")
    )
    baselineV1xFiles.from(
        layout.projectDirectory.file("docs/api/baselines/v1.x/clockwork-api.api"),
        layout.projectDirectory.file("docs/api/baselines/v1.x/clockwork-host-api.api")
    )
    allowBreaking.set(findProperty("apiBreakingApproved")?.toString()?.toBooleanStrictOrNull() ?: false)
    exceptionsFile.set(layout.projectDirectory.file("docs/api/breaking-exceptions.properties"))
    contractDocFile.set(layout.projectDirectory.file("docs/api/v1.7.0.md"))
    reportFile.set(layout.buildDirectory.file("reports/api-compatibility/v1.7-freeze-report.md"))
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



