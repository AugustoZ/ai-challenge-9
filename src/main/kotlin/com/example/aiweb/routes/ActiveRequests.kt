package com.example.aiweb.routes

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Реестр активных запросов стриминга для POST /api/cancel: клиентский requestId →
 * Job обработчика /api/chat/stream + флаг «сохранить контекст при отмене».
 * Потокобезопасен.
 *
 * Отмена снимает регистрацию (повторный cancel по тому же идентификатору вернёт
 * false — запрос больше не активен); нормальное завершение стрима снимает
 * регистрацию в finally обработчика. Флаг [Entry.saveContext] — общая мутабельная
 * ячейка между местом register и callback'ом onCancelled агента: cancel с
 * saveContext=true выставляет её ДО job.cancel(), поэтому checkpoint паузы
 * успевает сохраниться в onCancelled (он зовётся уже при отменённой корутине).
 */
class ActiveRequests {

    /** Запись реестра: Job запроса и флаг сохранения контекста при отмене. */
    private class Entry(val job: Job, val saveContext: AtomicBoolean)

    private val jobs = ConcurrentHashMap<String, Entry>()

    /**
     * Регистрирует задание активного запроса; пустой идентификатор игнорируется.
     * [saveContext] — флаг, который /api/cancel выставляет перед отменой, когда
     * отмена вызвана паузой (контрольная точка должна сохраниться).
     */
    fun register(requestId: String, job: Job, saveContext: AtomicBoolean) {
        if (requestId.isBlank()) return
        jobs[requestId.trim()] = Entry(job, saveContext)
    }

    /** Снимает регистрацию запроса (завершение или отмена); идемпотентно. */
    fun unregister(requestId: String) {
        if (requestId.isBlank()) return
        jobs.remove(requestId.trim())
    }

    /**
     * Отменяет задание активного запроса; true — запрос был активен и отмена
     * выполнена, false — для пустого, неизвестного или уже завершённого запроса.
     * [saveContext] = true выставляет флаг записи ДО job.cancel() — callback
     * onCancelled агента увидит его и сохранит дельту прогона (checkpoint паузы).
     */
    fun cancel(requestId: String, saveContext: Boolean = false): Boolean {
        if (requestId.isBlank()) return false
        val entry = jobs.remove(requestId.trim()) ?: return false
        if (saveContext) entry.saveContext.set(true)
        entry.job.cancel()
        return true
    }
}
