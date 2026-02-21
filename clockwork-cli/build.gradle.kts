plugins {
    application
    id("com.gradleup.shadow")
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
