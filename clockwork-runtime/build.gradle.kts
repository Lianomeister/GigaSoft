plugins {
    `java-library`
}

dependencies {
    api(project(":clockwork-api"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("org.yaml:snakeyaml:2.3")
}

tasks.register<Test>("performanceTest") {
    group = "verification"
    description = "Runs runtime performance-oriented benchmarks"
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

tasks.register<Test>("soakTest") {
    group = "verification"
    description = "Runs runtime soak tests"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("soak")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("performance")
    }
}
