// This file builds the JDBC/Kafka outbox infrastructure without depending on the API module.
plugins {
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.kafka)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.micrometer.registry.prometheus)
    testImplementation(libs.junit.jupiter)
}
