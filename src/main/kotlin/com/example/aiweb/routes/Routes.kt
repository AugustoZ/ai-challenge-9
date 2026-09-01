package com.example.aiweb.routes

import com.example.aiweb.client.AIClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.session.SessionManager
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
    val sessions = SessionManager()

    // POST /api/chat — принимает сообщение и возвращает ответ AI с учётом контекста сессии
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

        // Сессия может отсутствовать (одиночный запрос без истории) — тогда используем дефолтную
        val sessionId = body.sessionId ?: "default"

        // Строгая последовательность ролей: повторный user подряд отвергаем
        if (!sessions.addUserMessage(sessionId, body.message)) {
            call.respond(
                HttpStatusCode.Conflict,
                ChatResponse("Некорректный порядок: получено подряд два сообщения пользователя.")
            )
            return@post
        }

        try {
            // Суммаризация «выпавших» из окна старых сообщений через LLM (отдельный запрос)
            val toSummarize = sessions.messagesToSummarize(sessionId)
            if (toSummarize.isNotEmpty()) {
                val summary = aiClient.summarize(toSummarize)
                if (summary != null && summary.isNotBlank()) {
                    sessions.setSummary(sessionId, summary)
                }
            }

            // Собираем полный контекст: инструкция формата + резюме + скользящее окно последних сообщений
            val context = buildList {
                add(OpenAIMessage(role = "system", content = AIClient.formatInstruction()))
                addAll(sessions.messagesForRequest(sessionId))
            }

            val result = aiClient.ask(messages = context, maxTokens = body.maxTokens)

            // Запоминаем ответ ассистента в историю сессии
            sessions.addAssistantMessage(sessionId, result.reply)

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
