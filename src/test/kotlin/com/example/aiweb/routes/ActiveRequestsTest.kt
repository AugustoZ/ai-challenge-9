package com.example.aiweb.routes

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки реестра активных запросов стриминга: жизненный цикл
 * register → cancel/unregister, идемпотентность и защита от неизвестных
 * идентификаторов (POST /api/cancel должен честно отвечать {"cancelled": false}).
 */
class ActiveRequestsTest {

    @Test
    fun `register и cancel — полный жизненный цикл`() {
        val registry = ActiveRequests()
        val job = Job()
        registry.register("req-1", job)

        assertTrue(registry.cancel("req-1"))
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancel неизвестного или пустого идентификатора — false`() {
        val registry = ActiveRequests()

        assertFalse(registry.cancel("нет-такого"))
        assertFalse(registry.cancel(""))
        assertFalse(registry.cancel("   "))
    }

    @Test
    fun `повторный cancel — false, запрос уже снят с регистрации`() {
        val registry = ActiveRequests()
        val job = Job()
        registry.register("req-1", job)

        assertTrue(registry.cancel("req-1"))
        assertFalse(registry.cancel("req-1"))
    }

    @Test
    fun `unregister снимает регистрацию — cancel больше не находит запрос`() {
        val registry = ActiveRequests()
        val job = Job()
        registry.register("req-2", job)

        registry.unregister("req-2")

        assertFalse(registry.cancel("req-2"))
        assertFalse(job.isCancelled)
    }
}
