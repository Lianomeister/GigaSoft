plugins {
    `java-library`
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

dependencies {
    api(kotlin("stdlib"))
}
