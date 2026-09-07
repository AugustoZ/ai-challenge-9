package com.example.aiweb.routes

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Реестр активных запросов стриминга для POST /api/cancel: клиентский requestId →
 * Job обработчика /api/chat/stream. Потокобезопасен.
 *
 * Отмена снимает регистрацию (повторный cancel по тому же идентификатору вернёт
 * false — запрос больше не активен); нормальное завершение стрима снимает
 * регистрацию в finally обработчика.
 */
class ActiveRequests {

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Регистрирует задание активного запроса; пустой идентификатор игнорируется. */
    fun register(requestId: String, job: Job) {
        if (requestId.isBlank()) return
        jobs[requestId.trim()] = job
    }

    /** Снимает регистрацию запроса (завершение или отмена); идемпотентно. */
    fun unregister(requestId: String) {
        if (requestId.isBlank()) return
        jobs.remove(requestId.trim())
    }

    /**
     * Отменяет задание активного запроса; true — запрос был активен и отмена
     * выполнена, false — для пустого, неизвестного или уже завершённого запроса.
     */
    fun cancel(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        val job = jobs.remove(requestId.trim()) ?: return false
        job.cancel()
        return true
    }
}
