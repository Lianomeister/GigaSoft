plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":gigasoft-core"))
    implementation(project(":gigasoft-net"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation(project(":gigasoft-demo-standalone"))
}

tasks.jar {
    enabled = false
}

tasks.named<Test>("test") {
    dependsOn(":gigasoft-demo-standalone:shadowJar")
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
    dependsOn(":gigasoft-demo-standalone:shadowJar")
    filter {
        includeTestsMatching("*Performance*")
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.gigasoft.standalone.MainKt"
    }
}
