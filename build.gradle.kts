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
    version = "0.1.0-rc.1"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
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
    dependsOn(":gigasoft-bridge-paper:shadowJar", ":gigasoft-demo:shadowJar")
}

tasks.register("runServer") {
    dependsOn("buildPlugin")
    doLast {
        println("Artifacts built. Copy jars into dev-runtime/plugins and dev-runtime/giga-plugins as needed.")
    }
}

tasks.register("reloadPlugin") {
    doLast {
        println("In a running Paper server: /giga reload all")
    }
}

tasks.register("integrationSmoke") {
    dependsOn(":gigasoft-integration:integrationTest")
}

tasks.register<Copy>("releaseCandidateArtifacts") {
    val bridgeShadow = project(":gigasoft-bridge-paper")
        .tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    val demoShadow = project(":gigasoft-demo")
        .tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    val cliShadow = project(":gigasoft-cli")
        .tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")

    dependsOn(
        bridgeShadow,
        demoShadow,
        cliShadow
    )
    val releaseDir = layout.buildDirectory.dir("release/${project.version}")
    from(bridgeShadow.flatMap { it.archiveFile })
    from(demoShadow.flatMap { it.archiveFile })
    from(cliShadow.flatMap { it.archiveFile })
    into(releaseDir)
}

tasks.register("releaseCandidate") {
    group = "release"
    description = "Builds and verifies release candidate artifacts"
    dependsOn(
        ":gigasoft-api:test",
        ":gigasoft-runtime:test",
        ":gigasoft-bridge-paper:test",
        ":gigasoft-cli:test",
        ":gigasoft-demo:test",
        "integrationSmoke",
        "releaseCandidateArtifacts"
    )
}
