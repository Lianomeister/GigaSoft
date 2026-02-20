plugins {
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":gigasoft-api"))
    implementation(project(":gigasoft-runtime"))
    implementation("info.picocli:picocli:4.7.6")
}

application {
    mainClass.set("com.gigasoft.cli.GigaCliKt")
}

tasks.jar {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
}
