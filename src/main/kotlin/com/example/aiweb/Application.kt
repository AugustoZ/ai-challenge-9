package com.example.aiweb

import com.example.aiweb.config.AppConfig
import com.example.aiweb.routes.aiRoutes
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun main() {
    val config = AppConfig.fromEnv()

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }

    routing {
        staticResources("/static", "static")
        aiRoutes(config)
        get("/") {
            call.respondRedirect("/static/index.html")
        }
    }
}
