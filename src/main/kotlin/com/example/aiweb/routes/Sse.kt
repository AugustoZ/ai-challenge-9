package com.example.aiweb.routes

import com.example.aiweb.model.StreamEvent
import kotlinx.serialization.json.Json

private val sseJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * Кодирует кадр SSE-потока /api/chat/stream: `data: <одна строка JSON>\n\n`
 * (стандартный SSE — пустая строка завершает кадр). kotlinx.serialization пишет
 * JSON без переносов и экранирует `\n` внутри строк, поэтому каждый кадр
 * гарантированно занимает ровно одну строку данных.
 */
fun encodeFrame(event: StreamEvent): String =
    "data: " + sseJson.encodeToString(StreamEvent.serializer(), event) + "\n\n"
