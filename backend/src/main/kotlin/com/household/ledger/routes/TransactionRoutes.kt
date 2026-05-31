package com.household.ledger.routes

import com.household.ledger.database.TransactionService
import com.household.ledger.models.Transaction
import com.household.ledger.service.BackupService
import com.household.ledger.service.ExportService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

// Background scope for non-blocking backup operations
private val backupScope = CoroutineScope(Dispatchers.IO)

fun Route.transactionRoutes() {
    val service = TransactionService()
    val exportService = ExportService()
    val backupService = BackupService()

    route("/transactions") {
        get {
            call.respond(service.getAllTransactions())
        }

        post {
            val transaction = call.receive<Transaction>()
            val created = service.addTransaction(transaction)
            if (created != null) {
                // BACKUP FIX: Run in background to prevent DB locking
                backupScope.launch {
                    try {
                        backupService.createAutoBackup()
                    } catch (e: Exception) {
                        println("Background auto-backup failed: ${e.message}")
                    }
                }
                call.respond(HttpStatusCode.Created, created)
            } else {
                call.respond(HttpStatusCode.InternalServerError, "Failed to create transaction")
            }
        }

        put("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid ID")
            val transaction = call.receive<Transaction>()
            if (service.updateTransaction(id, transaction)) {
                backupScope.launch {
                    try {
                        backupService.createAutoBackup()
                    } catch (e: Exception) {
                        println("Background auto-backup failed: ${e.message}")
                    }
                }
                call.respond(HttpStatusCode.OK, "Transaction updated")
            } else {
                call.respond(HttpStatusCode.NotFound, "Transaction not found")
            }
        }

        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ID")
            if (service.deleteTransaction(id)) {
                backupScope.launch {
                    try {
                        backupService.createAutoBackup()
                    } catch (e: Exception) {
                        println("Background auto-backup failed: ${e.message}")
                    }
                }
                call.respond(HttpStatusCode.OK, "Transaction deleted")
            } else {
                call.respond(HttpStatusCode.NotFound, "Transaction not found")
            }
        }
    }

    route("/export") {
        get("/excel") {
            val month = call.request.queryParameters["month"]
            val year = call.request.queryParameters["year"]
            val category = call.request.queryParameters["category"]
            val search = call.request.queryParameters["search"]?.lowercase()

            val all = service.getAllTransactions()
            val filtered = all.filter { t ->
                val dateParts = t.date.split("-")
                val tYear = if (dateParts.isNotEmpty()) dateParts[0] else ""
                val tMonth = if (dateParts.size > 1) dateParts[1] else ""
                
                val matchesMonth = month.isNullOrEmpty() || tMonth == month
                val matchesYear = year.isNullOrEmpty() || tYear == year
                val matchesCategory = category.isNullOrEmpty() || t.category == category
                
                val s = search
                val matchesSearch = s.isNullOrEmpty() || 
                    t.category.lowercase().contains(s) ||
                    t.paidTo.lowercase().contains(s) ||
                    t.notes.lowercase().contains(s)

                matchesMonth && matchesYear && matchesCategory && matchesSearch
            }

            val fileName = "ledger_${month ?: "all"}_${year ?: "all"}.xlsx"
            val bytes = exportService.generateExcel(filtered, month, year)
            
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
            )
            call.respondBytes(bytes, ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        }

        get("/csv") {
            val month = call.request.queryParameters["month"]
            val year = call.request.queryParameters["year"]
            val category = call.request.queryParameters["category"]
            val search = call.request.queryParameters["search"]?.lowercase()

            val all = service.getAllTransactions()
            val filtered = all.filter { t ->
                val dateParts = t.date.split("-")
                val tYear = if (dateParts.isNotEmpty()) dateParts[0] else ""
                val tMonth = if (dateParts.size > 1) dateParts[1] else ""
                
                val matchesMonth = month.isNullOrEmpty() || tMonth == month
                val matchesYear = year.isNullOrEmpty() || tYear == year
                val matchesCategory = category.isNullOrEmpty() || t.category == category
                
                val s = search
                val matchesSearch = s.isNullOrEmpty() || 
                    t.category.lowercase().contains(s) ||
                    t.paidTo.lowercase().contains(s) ||
                    t.notes.lowercase().contains(s)

                matchesMonth && matchesYear && matchesCategory && matchesSearch
            }

            val fileName = "ledger_${month ?: "all"}_${year ?: "all"}.csv"
            val csvString = exportService.generateCsv(filtered, month, year)
            
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
            )
            call.respondText(csvString, ContentType.Text.CSV)
        }
    }

    route("/backup") {
        get("/database") {
            val file = backupService.getDatabaseFile()
            if (file.exists()) {
                val fileName = backupService.generateBackupFileName()
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
                )
                call.respondFile(file)
            } else {
                call.respond(HttpStatusCode.NotFound, "Database file not found")
            }
        }

        get("/status") {
            call.respond(backupService.getBackupStatus())
        }
    }

    route("/restore") {
        post("/database") {
            val multipart = call.receiveMultipart()
            var restored = false
            
            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val tempFile = File.createTempFile("restore", ".db")
                    part.streamProvider().use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    
                    if (backupService.restoreDatabase(tempFile)) {
                        restored = true
                    }
                    tempFile.delete()
                }
                part.dispose()
            }

            if (restored) {
                call.respond(HttpStatusCode.OK, "Database restored successfully")
            } else {
                call.respond(HttpStatusCode.BadRequest, "Failed to restore database. Ensure it is a valid SQLite file.")
            }
        }
    }
}
