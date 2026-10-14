package org.darkan.core.net.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.installExceptions() {
    install(StatusPages) {
        exception<ConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, cause.message ?: "Resource conflict")
        }
    }
}

class ConflictException(message: String) : RuntimeException(message)