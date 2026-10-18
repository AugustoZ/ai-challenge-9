package com.example.aiweb.auth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Выдача и учёт сессионных токенов. Токены живут ТОЛЬКО в памяти процесса:
 * рестарт сервера сбрасывает все входы — пользователи входят заново.
 * Диска и TTL нет по дизайну (без JWT/refresh — guardrail проекта).
 *
 * Токен — 32 случайных байта от [SecureRandom] в Base64URL без паддинга
 * (256 бит энтропии ≥ 128 требуемых). Формат токена никогда не уходит в
 * логи и не сериализуется в JSON хранилищ.
 */
class TokenManager {

    /** Отображение token → userId; выдача и отзыв — атомарные операции карты. */
    private val tokens = ConcurrentHashMap<String, String>()

    private val random = SecureRandom()

    /** Выдаёт новый уникальный токен для пользователя и регистрирует его. */
    fun issue(userId: String): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tokens[token] = userId
        return token
    }

    /** Пользователь по токену; неизвестный, отозванный или мусорный токен — null. */
    fun resolve(token: String): String? = tokens[token]

    /** Отзывает один токен; повторный отзыв — без ошибки. */
    fun revoke(token: String) {
        tokens.remove(token)
    }

    /** Каскадный отзыв: отменяет ВСЕ токены пользователя (выход, удаление аккаунта). */
    fun revokeAllFor(userId: String) {
        tokens.entries.removeIf { it.value == userId }
    }

    companion object {
        /** Длина токена в байтах: 32 байта → 43 символа Base64URL. */
        const val TOKEN_BYTES = 32
    }
}
