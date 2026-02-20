plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":gigasoft-api"))
    implementation(project(":gigasoft-runtime"))
}

tasks.jar {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
}