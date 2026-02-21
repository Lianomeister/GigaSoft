plugins {
    `java-library`
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

dependencies {
    api(kotlin("stdlib"))
}

tasks.register<Test>("contractTest") {
    group = "verification"
    description = "Runs API contract-focused tests for clockwork-api"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("*Contract*")
        includeTestsMatching("*Contracts*")
    }
}
