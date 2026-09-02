package com.example.aiweb.client

import com.example.aiweb.model.ExchangeInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * Бортовой самописец: кольцевой буфер сырых обменов между приложением и LLM.
 * Каждый HTTP-вызов к API даёт две записи: «req» (тело запроса) и «res» (тело ответа).
 * Потокобезопасен: режим «Команда» выполняет вызовы параллельно, поэтому буфер
 * защищён synchronized, а порядок записей задаётся сквозным номером seq.
 */
object ExchangeLog {
    private const val MAX_ENTRIES = 200

    private val seq = AtomicLong(0)
    private val buffer = ArrayDeque<ExchangeInfo>()

    /** Метка «номер последовательности до начала обмена» для последующего среза. */
    fun mark(): Long = seq.get()

    /** Фиксирует одну запись обмена (запрос или ответ). */
    fun record(dir: String, payload: String, ok: Boolean?, ms: Long?) {
        synchronized(buffer) {
            buffer.addLast(
                ExchangeInfo(
                    seq = seq.incrementAndGet(),
                    ts = System.currentTimeMillis(),
                    dir = dir,
                    ok = ok,
                    ms = ms,
                    payload = payload
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
}
