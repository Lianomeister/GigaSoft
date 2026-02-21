plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":clockwork-core"))
    implementation(project(":clockwork-net"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation(project(":clockwork-demo-standalone"))
}

tasks.jar {
    enabled = false
}

tasks.named<Test>("test") {
    dependsOn(":clockwork-demo-standalone:shadowJar")
    useJUnitPlatform {
        excludeTags("performance")
    }
}

tasks.register<Test>("performanceTest") {
    group = "verification"
    description = "Runs standalone performance-oriented benchmarks"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("performance")
    }
    dependsOn(":clockwork-demo-standalone:shadowJar")
    filter {
        includeTestsMatching("*Performance*")
    }
}

tasks.register<Test>("smokeTest") {
    group = "verification"
    description = "Runs standalone smoke tests"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("smoke")
    }
    dependsOn(":clockwork-demo-standalone:shadowJar")
}

tasks.register<Test>("soakTest") {
    group = "verification"
    description = "Runs standalone soak tests"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("soak")
    }
    dependsOn(":clockwork-demo-standalone:shadowJar")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(
        ":clockwork-plugin-browser:shadowJar",
        ":clockwork-plugin-bridged:shadowJar"
    )
    val browserJar = project(":clockwork-plugin-browser")
        .layout
        .buildDirectory
        .file("libs/clockwork-plugin-browser-${project.version}.jar")
    val bridgedJar = project(":clockwork-plugin-bridged")
        .layout
        .buildDirectory
        .file("libs/clockwork-plugin-bridged-${project.version}.jar")
    from(browserJar) {
        into("default-plugins")
        rename { "clockwork-plugin-browser.jar" }
    }
    from(bridgedJar) {
        into("default-plugins")
        rename { "clockwork-plugin-bridged.jar" }
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.clockwork.standalone.MainKt"
    }
}
