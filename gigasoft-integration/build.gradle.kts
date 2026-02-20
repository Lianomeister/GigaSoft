import org.gradle.api.tasks.testing.Test

plugins {
    `java-library`
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

tasks.test {
    exclude("**/PaperSmokeIntegrationTest.class")
    failOnNoDiscoveredTests = false
}

val bridgeJar = project(":gigasoft-bridge-paper").tasks.named("shadowJar")
val demoJar = project(":gigasoft-demo").tasks.named("shadowJar")

val integrationTest by tasks.registering(Test::class) {
    description = "Runs a real Paper smoke test with bridge and demo plugin"
    group = "verification"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    dependsOn(bridgeJar, demoJar)

    val bridgeArchive = project(":gigasoft-bridge-paper")
        .tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
        .flatMap { it.archiveFile }
    val demoArchive = project(":gigasoft-demo")
        .tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
        .flatMap { it.archiveFile }

    doFirst {
        systemProperty("gigasoft.bridgeJar", bridgeArchive.get().asFile.absolutePath)
        systemProperty("gigasoft.demoJar", demoArchive.get().asFile.absolutePath)
    }

    shouldRunAfter(tasks.test)
}
