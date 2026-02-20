plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":gigasoft-api"))
    implementation(project(":gigasoft-runtime"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    compileOnly("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
}

tasks.jar {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
}
