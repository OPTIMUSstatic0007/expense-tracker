plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}
application {
    mainClass.set("com.household.ledger.ApplicationKt")
}

group = "com.household.ledger"
version = "1.0.0"

application {
    mainClass.set("com.household.ledger.ApplicationKt")
}
dependencies {
    implementation("io.ktor:ktor-server-core-jvm:2.3.5")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.5")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.5")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.5")
    implementation("io.ktor:ktor-server-cors-jvm:2.3.5")
    
    // Database (Exposed ORM)
    implementation("org.jetbrains.exposed:exposed-core:0.44.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.44.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.44.1")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.11")
    
    testImplementation("io.ktor:ktor-server-tests-jvm:2.3.5")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
}

kotlin {
    jvmToolchain(21)
}
