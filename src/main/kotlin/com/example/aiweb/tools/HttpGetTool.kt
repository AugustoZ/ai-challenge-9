package com.example.aiweb.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * Инструмент http_get: получает текстовое содержимое страницы по HTTP(S)
 * с защитой от SSRF (аргументы приходят от LLM — это ненадёжный ввод).
 *
 * Гарды (до любого сетевого соединения и повторно на каждом хопе перенаправления):
 *  - только схемы http/https (file://, data://, ftp:// и прочие запрещены);
 *  - запрещены имена хостов localhost, *.local, *.internal;
 *  - резолвятся ВСЕ адреса хоста: если хотя бы один попадает в закрытый диапазон
 *    (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, 0.0.0.0/8, 100.64/10,
 *    ::1, fe80::/10, fc00::/7) — доступ запрещён;
 *  - перенаправления выполняются вручную, не более 3 хопов, с полной
 *    перепроверкой каждого нового адреса (иначе редирект на 169.254.169.254
 *    обходил бы блок);
 *  - кап размера ответа 2 МБ, таймаут запроса ~10 секунд;
 *  - принимаются только текстовые типы и application/json.
 *
 * Любая ошибка возвращается как [ToolResult] с ok = false и понятным текстом —
 * исключения наружу не пробрасываются. Аргумент: {"url": "https://..."}.
 */
class HttpGetTool : Tool {

    override val name = NAME

    override val description =
        "Получает текстовое содержимое страницы по HTTP/HTTPS. Доступны только публичные адреса " +
            "(защита от SSRF), не более 3 перенаправлений, размер ответа до 2 МБ, " +
            "типы содержимого text/* и application/json."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "Полный URL страницы, например https://example.com/doc")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("url")) })
    }

    /**
     * Единственный переиспользуемый клиент (lazy): таймауты, без авто-редиректов —
     * они выполняются вручную, чтобы каждый хоп проходил полную проверку.
     */
    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val url = (args["url"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()
        if (url.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"url\" (строка с адресом страницы)")
        }
        return try {
            fetchWithRedirects(url)
        } catch (e: Exception) {
            ToolResult(false, "Не удалось получить страницу: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Результат одного HTTP-запроса внутри цепочки перенаправлений. */
    private sealed interface Hop {
        /** Ответ-перенаправление: адрес продолжения (может быть относительным). */
        data class Redirect(val location: String) : Hop

        /** Финальный ответ: прочитанное содержимое. */
        data class Done(val body: String) : Hop

        /** Отказ: человекочитаемая причина. */
        data class Failed(val message: String) : Hop
    }

    /** Цепочка запросов: ручные перенаправления с полной перепроверкой каждого хопа. */
    private suspend fun fetchWithRedirects(rawUrl: String): ToolResult {
        var url = rawUrl
        var hops = 0
        while (true) {
            // Гарды ДО соединения; на каждом хопе заново — цель редиректа проверяется целиком
            guard(url)?.let { return ToolResult(false, it) }
            when (val hop = client.prepareGet(url).execute { response -> classify(response) }) {
                is Hop.Redirect -> {
                    hops++
                    if (hops > MAX_REDIRECTS) {
                        return ToolResult(false, "Слишком много перенаправлений: больше $MAX_REDIRECTS")
                    }
                    url = resolveRedirect(url, hop.location)
                        ?: return ToolResult(false, "Некорректный адрес перенаправления: '${hop.location}'")
                }
                is Hop.Failed -> return ToolResult(false, hop.message)
                is Hop.Done -> return ToolResult(ok = true, content = hop.body)
            }
        }
    }

    /** Разбирает ответ одного запроса: перенаправление / HTTP-ошибка / тип содержимого / кап размера. */
    private suspend fun classify(response: HttpResponse): Hop {
        val status = response.status
        val location = response.headers[HttpHeaders.Location]
        if (status.value in REDIRECT_STATUSES && !location.isNullOrBlank()) {
            return Hop.Redirect(location)
        }
        if (!status.isSuccess()) {
            return Hop.Failed("Сервер вернул ошибку HTTP ${status.value}")
        }
        val contentType = response.contentType()
        if (contentType == null || !isAcceptedType(contentType)) {
            return Hop.Failed(
                "Недопустимый тип содержимого: ${contentType?.withoutParameters() ?: "не указан"} — " +
                    "принимаются только text/* и application/json"
            )
        }
        return try {
            Hop.Done(readCapped(response.bodyAsChannel()))
        } catch (e: ContentTooLargeException) {
            Hop.Failed("Ответ превышает лимит размера (${MAX_BODY_BYTES / (1024 * 1024)} МБ)")
        }
    }

    /** Принимаются только текстовые типы и application/json; остальное (binary и т.п.) — отказ. */
    private fun isAcceptedType(type: ContentType): Boolean =
        type.match(ContentType.Text.Any) || type.match(ContentType.Application.Json)

    /** Читает канал, но не больше [MAX_BODY_BYTES]; превышение — внутренний маркер-исключение. */
    private suspend fun readCapped(channel: ByteReadChannel): String {
        val output = ByteArrayOutputStream(BUFFER_SIZE)
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > MAX_BODY_BYTES) throw ContentTooLargeException()
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /** Разрешает Location относительно текущего URL (абсолютный или относительный адрес). */
    private fun resolveRedirect(current: String, location: String): String? = try {
        URL(URL(current), location).toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Проверки адреса до любого соединения. Возвращает текст ошибки или null,
     * если адрес разрешён. Порядок: схема → имя хоста → все IP-адреса хоста
     * (любой адрес в закрытом диапазоне запрещает доступ).
     */
    private fun guard(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return "Некорректный URL: '$url'"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Схема '${scheme ?: "не указана"}' не поддерживается — разрешены только http и https"
        }
        // Java возвращает IPv6-литерал в скобках ([]), резолверу они не нужны
        val host = uri.host.orEmpty().trim().removeSurrounding("[", "]").lowercase()
        if (host.isEmpty()) return "В URL не указан адрес хоста: '$url'"
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) {
            return "Доступ к хосту '$host' запрещён (защита от SSRF)"
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return "Не удалось определить адрес хоста '$host': ${e.message}"
        }
        val forbidden = addresses.firstOrNull { isForbiddenAddress(it) } ?: return null
        return "Хост '$host' запрещён: адрес ${forbidden.hostAddress} относится к закрытому диапазону " +
            "(защита от SSRF)"
    }

    /** Адрес в закрытом диапазоне: private, loopback, link-local, CGNAT, «любой-в-сети». */
    private fun isForbiddenAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> forbiddenIpv4(address.address)
        is Inet6Address -> when {
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ->
                true // ::, ::1, fe80::/10
            else -> {
                val bytes = address.address
                // fc00::/7 — unique local addresses
                (bytes[0].toInt() and 0xFE) == 0xFC ||
                    // IPv4-mapped (::ffff:a.b.c.d) — проверяем вложенный IPv4-адрес
                    (isIpv4Mapped(bytes) && forbiddenIpv4(bytes.copyOfRange(12, 16)))
            }
        }
        else -> true // неизвестный тип адреса — запрещаем по умолчанию
    }

    /** Закрытые IPv4-диапазоны: 0/8, 10/8, 127/8, 169.254/16, 172.16/12, 192.168/16, 100.64/10. */
    private fun forbiddenIpv4(bytes: ByteArray): Boolean {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return b0 == 0 ||
            b0 == 10 ||
            b0 == 127 ||
            (b0 == 169 && b1 == 254) ||
            (b0 == 172 && b1 in 16..31) ||
            (b0 == 192 && b1 == 168) ||
            (b0 == 100 && b1 in 64..127)
    }

    /** Признак IPv4-mapped IPv6 (::ffff:a.b.c.d): 80 младших нулей и 0xFFFF в байтах 10–11. */
    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()

    /** Превышение капа размера ответа — внутренний маркер, наружу не выходит. */
    private class ContentTooLargeException : Exception()

    companion object {
        const val NAME = "http_get"

        /** Таймаут одного запроса, мс. */
        private const val TIMEOUT_MS = 10_000L

        /** Кап размера ответа: 2 МБ. */
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024

        /** Максимум перенаправлений с полной перепроверкой каждого адреса. */
        private const val MAX_REDIRECTS = 3

        /** Размер буфера чтения. */
        private const val BUFFER_SIZE = 16 * 1024

        /** Статусы перенаправления. */
        private val REDIRECT_STATUSES = listOf(301, 302, 303, 307, 308)
    }
}
