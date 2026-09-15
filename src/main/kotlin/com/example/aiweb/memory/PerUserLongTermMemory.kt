package com.example.aiweb.memory

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-user долговременная память (изоляция пользователей): каждый пользователь
 * работает со своим файлом `dataDir/memory/{userId}/longterm.json`. Внутри —
 * ленивые [LongTermMemoryStore] по userId (хранятся в памяти процесса);
 * таксономия видов, лимиты [MemoryLimits] и формат записей не меняются,
 * поэтому кап 200 записей и любая эвикция действуют в границах одного
 * пользователя (per-user), не затрагивая чужие записи.
 *
 * Резолв владельца зафиксирован на уровне роутов: userId приходит из
 * атрибута вызова (intercept), роуты вызывают [forUser] и работают с портом
 * [MemoryRepository] конкретного пользователя. Legacy-файл
 * `dataDir/memory/longterm.json` обёртка не читает и не пишет — общая
 * раскладка остаётся только у дефолтного конструктора [LongTermMemoryStore];
 * миграция legacy-файла — задача 10.
 */
class PerUserLongTermMemory(
    /** Каталог данных (AI_DATA_DIR); подкаталог memory/{userId} создаётся по требованию. */
    private val baseDir: String,
    /** Источник времени — шов для детерминированных тестов. */
    private val now: () -> Long = System::currentTimeMillis
) {

    private val stores = ConcurrentHashMap<String, LongTermMemoryStore>()

    /**
     * Стор долговременной памяти пользователя [userId]; один экземпляр
     * на пользователя на время жизни процесса. Идентификатор попадает
     * в путь файла — traversal недопустим ([IllegalArgumentException]).
     */
    fun forUser(userId: String): LongTermMemoryStore {
        require(isSafeId(userId)) { "Недопустимый идентификатор пользователя" }
        return stores.computeIfAbsent(userId) { id ->
            LongTermMemoryStore(baseDir, now, memorySubPath = File(MEMORY_DIR, id).path)
        }
    }

    /** Идентификатор пользователя попадает в имя каталога — traversal недопустим. */
    private fun isSafeId(id: String): Boolean =
        id.isNotBlank() &&
            !id.contains('/') &&
            !id.contains('\\') &&
            !id.contains("..") &&
            !id.contains('\u0000')

    companion object {
        /** Подкаталог внутри каталога данных с каталогами пользователей. */
        private const val MEMORY_DIR = "memory"
    }
}
