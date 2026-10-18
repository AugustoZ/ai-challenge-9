package com.example.aiweb.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки менеджера токенов: выдача уникальных токенов, разрешение в
 * владельца, точечная отзыв и каскадный отзыв всех токенов пользователя.
 * Токены живут только в памяти процесса — рестарт означает повторный вход.
 */
class TokenManagerTest {

    @Test
    fun `issue выдаёт уникальные токены, resolve возвращает владельца`() {
        val manager = TokenManager()

        val issued = buildList {
            repeat(50) { i -> add(if (i % 2 == 0) "u1" to manager.issue("u1") else "u2" to manager.issue("u2")) }
        }

        assertEquals(50, issued.map { it.second }.toSet().size, "Каждый токен уникален")
        for ((userId, token) in issued) {
            assertEquals(userId, manager.resolve(token), "Токен должен разрешаться в своего владельца")
        }
    }

    @Test
    fun `токен — 32 байта Base64URL без паддинга`() {
        val manager = TokenManager()

        val token = manager.issue("u1")

        assertEquals(43, token.length, "32 байта = 43 символа Base64URL без '='")
        assertTrue(
            Regex("[A-Za-z0-9_-]{43}").matches(token),
            "Только URL-безопасные символы: $token"
        )
    }

    @Test
    fun `revoke отменяет только указанный токен`() {
        val manager = TokenManager()
        val first = manager.issue("u1")
        val second = manager.issue("u1")

        manager.revoke(first)

        assertNull(manager.resolve(first), "Отозванный токен не разрешается")
        assertEquals("u1", manager.resolve(second), "Остальные токены живут")
        // Повторный отзыв того же токена — без ошибки
        manager.revoke(first)
        assertNull(manager.resolve(first))
    }

    @Test
    fun `revokeAllFor отменяет все токены пользователя и только его`() {
        val manager = TokenManager()
        val u1a = manager.issue("u1")
        val u1b = manager.issue("u1")
        val u2 = manager.issue("u2")

        manager.revokeAllFor("u1")

        assertNull(manager.resolve(u1a))
        assertNull(manager.resolve(u1b))
        assertEquals("u2", manager.resolve(u2))
        // Неизвестный пользователь — без ошибки
        manager.revokeAllFor("никого")
    }

    @Test
    fun `resolve мусора и пустой строки — null`() {
        val manager = TokenManager()

        assertNull(manager.resolve(""))
        assertNull(manager.resolve("мусорный-токен"))
    }
}
