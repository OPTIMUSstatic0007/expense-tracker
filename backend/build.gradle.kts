plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.household.ledger"
version = "1.0.0"

application {
    mainClass.set("com.household.ledger.ApplicationKt")
}

dependencies {
    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    
    // Database (Exposed ORM)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)

    // Serialization for backup metadata
    implementation(libs.kotlinx.serialization.json)

    // Excel Export (Apache POI)
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    
    // Logging
    implementation(libs.logback)
    
    // Testing
    testImplementation(libs.ktor.server.tests)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    jvmToolchain(21)
}
