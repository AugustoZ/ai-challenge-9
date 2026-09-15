package com.example.aiweb.auth

import java.util.concurrent.ConcurrentHashMap

/** Активный кулдаун подбора ПИНа: остаток времени и русское сообщение. */
data class Cooldown(val remainingMs: Long, val message: String)

/** Исход попытки входа. */
sealed interface LoginResult {
    /** Успех: токен выдается маршрутом (задача роутов). */
    data class Success(val userId: String) : LoginResult

    /** Неверный ПИН или пользователь; после 3-й неудачи начинается кулдаун. */
    object InvalidCredentials : LoginResult

    /** Перебор заблокирован: даже верный ПИН не проходит до конца кулдауна. */
    data class Locked(val cooldown: Cooldown) : LoginResult
}

/**
 * Защита от подбора ПИНа: счётчик неудач на пользователя в памяти процесса.
 * Три неудачных попытки подряд → кулдаун 30 секунд, в течение которого не
 * проходит даже верный ПИН. Успешный вход сбрасывает счётчик. Отсиженный
 * кулдаун тоже обнуляет счётчик — снова 3 попытки на окно.
 *
 * Счётчики в памяти процесса: рестарт сервера сам является паузой, а
 * персистентный «штраф» карал бы не виноватого.
 */
class LoginGuard(
    /** Источник времени — шов для детерминированных тестов. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Длительность кулдауна, мс. */
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    /** Число неудач до кулдауна. */
    private val maxFailures: Int = DEFAULT_MAX_FAILURES
) {

    /** Состояние неудач пользователя: счётчик и момент окончания кулдауна. */
    private class Failures(var count: Int, var cooldownUntil: Long)

    private val states = ConcurrentHashMap<String, Failures>()

    /**
     * Активный кулдаун пользователя или null. Просроченный кулдаун снимается,
     * счётчик неудач обнуляется.
     */
    fun check(userId: String): Cooldown? {
        val state = states[userId] ?: return null
        if (state.cooldownUntil <= 0L) return null
        val remaining = state.cooldownUntil - now()
        if (remaining <= 0) {
            states.remove(userId)
            return null
        }
        return Cooldown(remaining, cooldownMessage(remaining))
    }

    /**
     * Полная попытка входа: кулдаун → блокировка; верный ПИН → успех (счётчик
     * сброшен); неверный → неудача в счётчике. На третьей неудаче кулдаун
     * начинает действовать со СЛЕДУЮЩЕЙ попытки — сама третья отвечает
     * «неверные данные», как и первые две.
     */
    fun verify(account: UserAccount, pin: String): LoginResult {
        check(account.id)?.let { return LoginResult.Locked(it) }
        return if (account.pinMatches(pin)) {
            recordSuccess(account.id)
            LoginResult.Success(account.id)
        } else {
            recordFailure(account.id)
            LoginResult.InvalidCredentials
        }
    }

    /** Регистрирует неудачу; на [maxFailures]-й включает кулдаун. */
    fun recordFailure(userId: String) {
        states.compute(userId) { _, existing ->
            val current = existing ?: Failures(0, 0L)
            if (current.cooldownUntil > now()) {
                // Кулдаун уже идёт: не продлеваем и не пересчитываем
                current
            } else {
                val count = current.count + 1
                if (count >= maxFailures) Failures(0, now() + cooldownMs) else Failures(count, 0L)
            }
        }
    }

    /** Успешный вход: счётчик неудач пользователя обнуляется. */
    fun recordSuccess(userId: String) {
        states.remove(userId)
    }

    /** Русское сообщение с остатком времени в целых секундах (с округлением вверх). */
    private fun cooldownMessage(remainingMs: Long): String {
        val seconds = (remainingMs + 999) / 1000
        return "Слишком много неудачных попыток входа. Повторите через $seconds сек."
    }

    companion object {
        /** Кулдаун после 3 неудачных попыток: 30 секунд. */
        const val DEFAULT_COOLDOWN_MS = 30_000L

        /** Число неудачных попыток до кулдауна. */
        const val DEFAULT_MAX_FAILURES = 3
    }
}
