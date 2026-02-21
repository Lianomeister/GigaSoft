plugins {
    `java-library`
}

dependencies {
    api(project(":clockwork-runtime"))
    implementation(project(":clockwork-host-api"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
}

tasks.register<Test>("performanceTest") {
    group = "verification"
    description = "Runs core performance-oriented benchmarks"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("performance")
    }
    filter {
        includeTestsMatching("*Performance*")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("performance")
    }
}
