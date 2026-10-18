package com.example.aiweb.client

import com.example.aiweb.model.ExchangeInfo
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/**
 * Бортовой самописец: кольцевой буфер сырых обменов между приложением и LLM.
 * Каждый HTTP-вызов к API даёт две записи: «req» (тело запроса) и «res» (тело ответа).
 * Потокобезопасен: вызовы к API могут выполняться параллельно, поэтому буфер
 * защищён synchronized, а порядок записей задаётся сквозным номером seq.
 *
 * Каждая запись несёт userId владельца обмена (задача 6): обработчики чата
 * оборачивают прогон в [userContext], и вызовы [record] без явного userId
 * подхватывают тег из корутины. Записи без контекста (системные вызовы вне
 * обработки запроса) получают userId = null и не видны никому через [forUser].
 */
object ExchangeLog {
    private const val MAX_ENTRIES = 200

    private val seq = AtomicLong(0)
    private val buffer = ArrayDeque<ExchangeInfo>()

    /** Метка «номер последовательности до начала обмена» для последующего среза. */
    fun mark(): Long = seq.get()

    /**
     * Фиксирует одну запись обмена (запрос или ответ). [userId] не передают
     * вручную: значение по умолчанию берётся из контекста, установленного
     * [userContext] в обработчике HTTP-запроса.
     */
    fun record(dir: String, payload: String, ok: Boolean?, ms: Long?, userId: String? = currentTag()) {
        synchronized(buffer) {
            buffer.addLast(
                ExchangeInfo(
                    seq = seq.incrementAndGet(),
                    ts = System.currentTimeMillis(),
                    dir = dir,
                    ok = ok,
                    ms = ms,
                    payload = payload,
                    userId = userId
                )
            )
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
    }

    /** Записи, зафиксированные после метки mark (порядок может быть вперемешку при параллельных вызовах). */
    fun since(mark: Long): List<ExchangeInfo> = synchronized(buffer) {
        buffer.filter { it.seq > mark }
    }

    /** Полный снимок буфера от старых записей к свежим. */
    fun all(): List<ExchangeInfo> = synchronized(buffer) { buffer.toList() }

    /** Записи одного пользователя (фильтр GET /api/log); чужие и безтеговые не отдаются. */
    fun forUser(userId: String?): List<ExchangeInfo> = synchronized(buffer) {
        buffer.filter { it.userId == userId }
    }

    /** Текущий тег пользователя для новых записей (null — вне обработки запроса). */
    internal fun currentTag(): String? = currentUser.get()

    /**
     * Контекст корутины с тегом пользователя: устанавливается обработчиками чата
     * через withContext и наследуется дочерними корутинами (стриминг, вызовы
     * LLM-клиента) — ThreadContextElement переносит ThreadLocal через смены
     * потоков при возобновлении.
     */
    fun userContext(userId: String?): CoroutineContext =
        if (userId == null) kotlin.coroutines.EmptyCoroutineContext else UserTagElement(userId)

    private val currentUser = ThreadLocal<String?>()

    private class UserTagElement(private val userId: String) : ThreadContextElement<String?> {
        companion object Key : CoroutineContext.Key<UserTagElement>

        override val key: CoroutineContext.Key<*> get() = Key

        override fun updateThreadContext(context: CoroutineContext): String? {
            val previous = currentUser.get()
            currentUser.set(userId)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
            currentUser.set(oldState)
        }
    }
}
