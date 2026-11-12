// This file builds the only runnable Spring Boot application in Phase 1.
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ledger-core"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.junit.jupiter)
}
