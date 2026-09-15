package com.example.aiweb.auth

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Аккаунт пользователя «Штурмана»: имя и ПИН, хранящийся только в виде
 * SHA-256(salt + pin). Файл пользователя на диске — это ровно эта структура
 * (см. [UserStore]); pinHash/salt не должны попадать ни в какие ответы API.
 */
@Serializable
data class UserAccount(
    /** Идентификатор пользователя (UUID, попадает в имя файла). */
    val id: String,
    /** Имя после обрезки пробелов: 1..25 символов, без управляющих. */
    val name: String,
    /** SHA-256(salt + pin) в hex; открытый ПИН нигде не хранится. */
    val pinHash: String,
    /** Соль — 16 случайных байт от SecureRandom в hex. */
    val salt: String,
    /** Момент создания, мс с эпохи. */
    val createdAt: Long
) {
    /**
     * Проверяет ПИН: сравнение хешей через [MessageDigest.isEqual] —
     * постоянное время, устойчивое к timing-атакам.
     */
    fun pinMatches(pin: String): Boolean {
        val expected = hexToBytes(pinHash)
        val actual = hashPinBytes(pin, salt)
        return MessageDigest.isEqual(expected, actual)
    }

    companion object {
        /** Максимальная длина имени после обрезки. */
        const val NAME_MAX = 25

        /** Длина соли в байтах (в hex — 32 символа). */
        private const val SALT_BYTES = 16

        /** ПИН — ровно 4 цифры. */
        private val PIN_FORMAT = Regex("^\\d{4}$")

        /** Управляющие символы в имени запрещены (переносы, табы и пр.). */
        private val CONTROL_CHARS = Regex("\\p{Cntrl}")

        /**
         * Допустимые символы имени: буквы (кириллица/латиница), цифры и набор
         * спецсимволов. Эмодзи и прочий мусор отсекаются.
         */
        private val NAME_ALLOWED = Regex("[\\p{L}\\p{N} .,!?@#$%^&*()\\-_+=/\\\\\\[\\]{}'\";:<>~]")

        private val random = SecureRandom()

        /** SHA-256(salt + pin) в hex — используется при создании аккаунта. */
        fun hashPin(pin: String, salt: String): String =
            hashPinBytes(pin, salt).joinToString("") { "%02x".format(it) }

        /** Новая соль: 16 случайных байт от SecureRandom в hex. */
        fun newSalt(): String {
            val bytes = ByteArray(SALT_BYTES).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Валидирует и нормализует имя: обрезка пробелов по краям, длина 1..25,
         * без управляющих символов, только разрешённые знаки. Возвращает
         * обрезанное имя или бросает [IllegalArgumentException] с русским
         * сообщением, называющим поле.
         */
        fun validateName(raw: String): String {
            val name = raw.trim()
            if (name.isEmpty()) {
                throw IllegalArgumentException("Имя пользователя не может быть пустым")
            }
            if (name.length > NAME_MAX) {
                throw IllegalArgumentException("Имя пользователя длиннее $NAME_MAX символов")
            }
            val control = CONTROL_CHARS.find(name)
            if (control != null) {
                throw IllegalArgumentException(
                    "Имя пользователя не должно содержать управляющие символы (код ${control.value.first().code})"
                )
            }
            val invalid = name.firstOrNull { !NAME_ALLOWED.matches(it.toString()) }
            if (invalid != null) {
                throw IllegalArgumentException(
                    "Имя пользователя содержит недопустимый символ '${invalid}' " +
                        "(разрешены буквы, цифры и спецсимволы)"
                )
            }
            return name
        }

        /** Проверяет ПИН: ровно 4 цифры, иначе [IllegalArgumentException] с русским сообщением. */
        fun validatePin(pin: String) {
            if (!PIN_FORMAT.matches(pin)) {
                throw IllegalArgumentException("ПИН должен состоять ровно из 4 цифр")
            }
        }

        /** SHA-256(salt + pin) в байтах — общий код для хеширования и сравнения. */
        private fun hashPinBytes(pin: String, salt: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray(Charsets.UTF_8))

        /** Пакет из hex-строки (соль/хеш); битая строка даст «мусор», но не исключение. */
        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
    }
}
