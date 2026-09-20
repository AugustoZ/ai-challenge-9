package com.example.aiweb.routes

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки реестра активных запросов стриминга: жизненный цикл
 * register → cancel/unregister, идемпотентность и защита от неизвестных
 * идентификаторов (POST /api/cancel должен честно отвечать {"cancelled": false}),
 * плюс флаг saveContext: cancel с saveContext=true выставляет его ДО job.cancel(),
 * чтобы onCancelled агента успел сохранить checkpoint паузы.
 */
class ActiveRequestsTest {

    @Test
    fun `register и cancel — полный жизненный цикл`() {
        val registry = ActiveRequests()
        val job = Job()
        registry.register("req-1", job, AtomicBoolean(false))

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
        registry.register("req-1", job, AtomicBoolean(false))

        assertTrue(registry.cancel("req-1"))
        assertFalse(registry.cancel("req-1"))
    }

    @Test
    fun `unregister снимает регистрацию — cancel больше не находит запрос`() {
        val registry = ActiveRequests()
        val job = Job()
        registry.register("req-2", job, AtomicBoolean(false))

        registry.unregister("req-2")

        assertFalse(registry.cancel("req-2"))
        assertFalse(job.isCancelled)
    }

    @Test
    fun `cancel с saveContext=true выставляет флаг до отмены Job`() {
        val registry = ActiveRequests()
        val flag = AtomicBoolean(false)
        val job = Job()
        // invokeOnCompletion у простого Job срабатывает синхронно внутри cancel() —
        // снимаем показание флага в момент отмены
        var seenDuringCancel = false
        job.invokeOnCompletion { seenDuringCancel = flag.get() }
        registry.register("req-1", job, flag)

        assertTrue(registry.cancel("req-1", saveContext = true))

        assertTrue(flag.get())
        assertTrue(job.isCancelled)
        assertTrue(seenDuringCancel, "флаг должен быть выставлен ДО job.cancel()")
    }

    @Test
    fun `cancel без saveContext флаг не выставляет`() {
        val registry = ActiveRequests()
        val flag = AtomicBoolean(false)
        val job = Job()
        registry.register("req-1", job, flag)

        assertTrue(registry.cancel("req-1"))

        assertFalse(flag.get(), "«Стоп» без паузы checkpoint не сохраняет")
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancel неизвестного запроса с saveContext=true — false без побочных эффектов`() {
        val registry = ActiveRequests()
        val flag = AtomicBoolean(false)

        assertFalse(registry.cancel("нет-такого", saveContext = true))
        assertFalse(flag.get())
    }
}
