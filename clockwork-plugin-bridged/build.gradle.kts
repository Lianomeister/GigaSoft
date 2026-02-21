plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":clockwork-api"))
}

tasks.jar {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
}
