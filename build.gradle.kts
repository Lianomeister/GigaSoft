import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.10" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
}

allprojects {
    group = "com.gigasoft"
    version = "0.1.0-rc.2"

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
        ":gigasoft-demo-standalone:shadowJar",
        ":gigasoft-standalone:shadowJar"
    )
}

tasks.register("runServer") {
    dependsOn("buildPlugin")
    doLast {
        println("Artifacts built. Use gigasoft-standalone for runtime execution.")
    }
}

tasks.register("reloadPlugin") {
    doLast {
        println("Use standalone console command: reload all")
    }
}

tasks.register<Copy>("standaloneReleaseCandidateArtifacts") {
    val demoShadow = project(":gigasoft-demo-standalone")
        .tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    val cliShadow = project(":gigasoft-cli")
        .tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    val standaloneShadow = project(":gigasoft-standalone")
        .tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")

    dependsOn(demoShadow, cliShadow, standaloneShadow)
    val releaseDir = layout.buildDirectory.dir("release-standalone/${project.version}")
    from(demoShadow.flatMap { it.archiveFile })
    from(cliShadow.flatMap { it.archiveFile })
    from(standaloneShadow.flatMap { it.archiveFile })
    into(releaseDir)
}

tasks.register("standaloneReleaseCandidate") {
    group = "release"
    description = "Builds and verifies standalone release candidate artifacts"
    dependsOn(
        ":gigasoft-api:test",
        ":gigasoft-host-api:test",
        ":gigasoft-net:test",
        ":gigasoft-runtime:test",
        ":gigasoft-core:test",
        ":gigasoft-standalone:test",
        ":gigasoft-cli:test",
        ":gigasoft-demo-standalone:test",
        "standaloneReleaseCandidateArtifacts"
    )
}

tasks.register("performanceBaseline") {
    group = "verification"
    description = "Runs lightweight performance baselines for runtime/core/standalone"
    dependsOn(
        ":gigasoft-runtime:performanceTest",
        ":gigasoft-core:performanceTest",
        ":gigasoft-standalone:performanceTest"
    )
}
