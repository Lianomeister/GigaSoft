plugins {
    `java-library`
}

dependencies {
    api(project(":gigasoft-api"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
}
