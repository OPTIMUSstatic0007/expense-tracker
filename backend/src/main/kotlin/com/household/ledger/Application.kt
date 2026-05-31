package com.household.ledger

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.http.content.*
import com.household.ledger.database.*
import com.household.ledger.routes.*
import com.household.ledger.service.BackupService

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    DatabaseFactory.init()

    // 1. Trigger Automatic Backup on Startup
    val backupService = BackupService()
    backupService.createAutoBackup()
    
    routing {
        transactionRoutes()

        static("/") {
            resources("static")
            defaultResource("index.html", "static")
        }
    }
}
