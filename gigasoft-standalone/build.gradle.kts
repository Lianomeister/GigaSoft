plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":gigasoft-core"))
}

tasks.jar {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.gigasoft.standalone.MainKt"
    }
}
