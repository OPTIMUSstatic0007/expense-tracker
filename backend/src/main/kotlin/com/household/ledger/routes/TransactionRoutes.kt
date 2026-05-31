package com.household.ledger.routes

import com.household.ledger.database.TransactionService
import com.household.ledger.models.Transaction
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.transactionRoutes() {
    val service = TransactionService()

    route("/transactions") {
        get {
            call.respond(service.getAllTransactions())
        }

        post {
            val transaction = call.receive<Transaction>()
            val created = service.addTransaction(transaction)
            if (created != null) {
                call.respond(HttpStatusCode.Created, created)
            } else {
                call.respond(HttpStatusCode.InternalServerError, "Failed to create transaction")
            }
        }

        put("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid ID")
            val transaction = call.receive<Transaction>()
            if (service.updateTransaction(id, transaction)) {
                call.respond(HttpStatusCode.OK, "Transaction updated")
            } else {
                call.respond(HttpStatusCode.NotFound, "Transaction not found")
            }
        }

        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ID")
            if (service.deleteTransaction(id)) {
                call.respond(HttpStatusCode.OK, "Transaction deleted")
            } else {
                call.respond(HttpStatusCode.NotFound, "Transaction not found")
            }
        }
    }
}
