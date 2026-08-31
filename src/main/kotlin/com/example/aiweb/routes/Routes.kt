package com.example.aiweb.routes

import com.example.aiweb.client.AIClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Определяет HTTP-маршруты приложения.
 */
fun Route.aiRoutes(config: AppConfig) {
    val aiClient = AIClient(config)

    // POST /api/chat — принимает сообщение и возвращает ответ AI
    post("/api/chat") {
        val body = try {
            call.receive<ChatRequest>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse("Некорректный запрос.")
            )
            return@post
        }

        if (body.message.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse("Сообщение не может быть пустым.")
            )
            return@post
        }

        try {
            val result = aiClient.ask(body.message)
            call.respond(
                ChatResponse(
                    reply = result.reply,
                    promptTokens = result.promptTokens,
                    completionTokens = result.completionTokens,
                    totalTokens = result.totalTokens
                )
            )
        } catch (e: Exception) {
            println("Ошибка при обращении к API: ${e.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ChatResponse("Ошибка при обращении к API: ${e.message}")
            )
        }
    }
}
