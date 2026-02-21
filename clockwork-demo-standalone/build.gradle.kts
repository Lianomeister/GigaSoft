plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":clockwork-api"))
    implementation(project(":clockwork-runtime"))
}

tasks.jar {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
}