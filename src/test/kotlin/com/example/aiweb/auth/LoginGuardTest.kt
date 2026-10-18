package com.example.aiweb.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки защиты от подбора ПИНа: три неудачи подряд включают кулдаун,
 * верный ПИН в кулдауне не помогает, успех сбрасывает счётчик, кулдаун
 * истекает по времени, пользователи независимы.
 */
class LoginGuardTest {

    /** Замороженное время, двигается вручную — детерминированные проверки кулдауна. */
    private var nowMs = 1_000L

    private fun newGuard() = LoginGuard(now = { nowMs })

    /** Пользователь с ПИНом «0407», построенный без файлового хранилища. */
    private fun pilot(): UserAccount {
        val salt = UserAccount.newSalt()
        return UserAccount(
            id = "u1",
            name = "Пилот",
            pinHash = UserAccount.hashPin("0407", salt),
            salt = salt,
            createdAt = 1L
        )
    }

    private fun wrong(account: UserAccount, guard: LoginGuard): LoginResult =
        guard.verify(account, "9999")

    private fun right(account: UserAccount, guard: LoginGuard): LoginResult =
        guard.verify(account, "0407")

    @Test
    fun `первые неудачи не блокируют, верный ПИН проходит`() {
        val guard = newGuard()
        val account = pilot()

        assertIs<LoginResult.InvalidCredentials>(wrong(account, guard))
        assertIs<LoginResult.InvalidCredentials>(wrong(account, guard))
        assertNull(guard.check(account.id), "Две неудачи ещё не включают кулдаун")
        assertIs<LoginResult.Success>(right(account, guard))
    }

    @Test
    fun `три неудачи — кулдаун на 30 секунд даже с верным ПИНом`() {
        val guard = newGuard()
        val account = pilot()
        repeat(3) { assertIs<LoginResult.InvalidCredentials>(wrong(account, guard)) }

        val cooldown = guard.check(account.id)

        assertNotNull(cooldown, "После трёх неудач должен включиться кулдаун")
        assertEquals(30_000L, cooldown.remainingMs, "Кулдаун — 30 секунд")
        assertTrue(
            cooldown.message.contains("Слишком много неудачных попыток"),
            "Сообщение — по-русски: ${cooldown.message}"
        )
        assertIs<LoginResult.Locked>(right(account, guard), "Верный ПИН в кулдауне не проходит")
        assertIs<LoginResult.Locked>(wrong(account, guard), "Неверный ПИН в кулдауне тоже заблокирован")
    }

    @Test
    fun `кулдаун истекает — вход снова доступен`() {
        val guard = newGuard()
        val account = pilot()
        repeat(3) { wrong(account, guard) }
        nowMs += 30_001

        assertNull(guard.check(account.id), "После истечения кулдауна вход снова открыт")
        assertIs<LoginResult.Success>(right(account, guard), "Верный ПИН проходит после кулдауна")
    }

    @Test
    fun `успешный вход сбрасывает счётчик неудач`() {
        val guard = newGuard()
        val account = pilot()

        repeat(2) { wrong(account, guard) }
        assertIs<LoginResult.Success>(right(account, guard))

        // После успеха снова две неудачи — кулдауна ещё нет
        repeat(2) { wrong(account, guard) }
        assertNull(guard.check(account.id), "Успех должен был обнулить счётчик")

        // Третья неудача после успеха — кулдаун
        wrong(account, guard)
        assertNotNull(guard.check(account.id))
    }

    @Test
    fun `счётчики пользователей независимы`() {
        val guard = newGuard()
        val salt = UserAccount.newSalt()
        val first = pilot()
        val second = first.copy(id = "u2", pinHash = UserAccount.hashPin("0407", salt), salt = salt)

        repeat(3) { wrong(first, guard) }

        assertNotNull(guard.check(first.id))
        assertNull(guard.check(second.id), "Кулдаун первого не задевает второго")
        assertIs<LoginResult.Success>(right(second, guard))
    }

    @Test
    fun `успех и неудача для неизвестного пользователя — без ошибки`() {
        val guard = newGuard()

        guard.recordFailure("призрак")
        guard.recordSuccess("призрак")
        assertNull(guard.check("призрак"))
    }
}
