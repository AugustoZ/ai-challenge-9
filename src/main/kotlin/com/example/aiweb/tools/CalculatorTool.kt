package com.example.aiweb.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Инструмент calculator: вычисляет арифметическое выражение без сторонних зависимостей
 * (рекурсивный спуск). Поддерживаются + - * / ^ (правоассоциативная степень), скобки,
 * унарный минус и десятичная точка. Деление на ноль и нефинитный результат — ошибка,
 * а не значение. Аргумент: {"expression": "строка с выражением"}.
 */
class CalculatorTool : Tool {

    override val name = NAME

    override val description =
        "Вычисляет арифметическое выражение. Поддерживаются операции +, -, *, /, " +
            "^ (возведение в степень, правоассоциативное), скобки, унарный минус " +
            "и десятичная точка."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("expression", buildJsonObject {
                put("type", "string")
                put("description", "Арифметическое выражение, например \"2+3*4\" или \"(1+2)^2/3\"")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("expression")) })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val expression = (args["expression"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()
        if (expression.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"expression\" (строка с выражением)")
        }
        return try {
            ToolResult(ok = true, content = format(ExpressionParser(expression).parse()))
        } catch (e: ExpressionException) {
            ToolResult(false, e.message ?: "Не удалось вычислить выражение")
        }
    }

    /** Форматирует результат: целые значения без дробной части (14, а не 14.0). */
    private fun format(value: Double): String =
        if (abs(value) < 1e15 && value == value.roundToLong().toDouble()) {
            value.roundToLong().toString()
        } else {
            value.toString()
        }

    /** Ошибка разбора или вычисления выражения — попадает в ToolResult(false, message). */
    private class ExpressionException(message: String) : Exception(message)

    /**
     * Рекурсивный спуск по грамматике:
     *  expression := term (('+' | '-') term)*
     *  term       := unary (('*' | '/') unary)*
     *  unary      := '-' unary | power            (унарный минус слабее степени: -2^2 = -4)
     *  power      := primary ('^' unary)?         (правоассоциативно: 2^3^2 = 2^(3^2) = 512)
     *  primary    := число | '(' expression ')'
     */
    private class ExpressionParser(private val src: String) {
        private var pos = 0

        /** Разбирает всё выражение целиком; лишние символы после конца — ошибка. */
        fun parse(): Double {
            val value = expression()
            skipSpaces()
            if (pos < src.length) fail("Лишние символы после выражения: '${src.substring(pos)}'")
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                skipSpaces()
                when {
                    peek('+') -> { pos++; value = checked(value + term()) }
                    peek('-') -> { pos++; value = checked(value - term()) }
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = unary()
            while (true) {
                skipSpaces()
                when {
                    peek('*') -> { pos++; value = checked(value * unary()) }
                    peek('/') -> {
                        pos++
                        val divisor = unary()
                        if (divisor == 0.0) fail("Деление на ноль")
                        value = checked(value / divisor)
                    }
                    else -> return value
                }
            }
        }

        /** Унарный минус; рекурсия допускает любую вложенность и минус перед степенью. */
        private fun unary(): Double {
            skipSpaces()
            if (peek('-')) {
                pos++
                return checked(-unary())
            }
            return power()
        }

        /** Степень правоассоциативна; показатель степени может иметь унарный минус (2^-3). */
        private fun power(): Double {
            val base = primary()
            skipSpaces()
            if (peek('^')) {
                pos++
                return checked(base.pow(unary()))
            }
            return base
        }

        private fun primary(): Double {
            skipSpaces()
            if (pos >= src.length) fail("Неожиданный конец выражения")
            if (peek('(')) {
                pos++
                val value = expression()
                skipSpaces()
                if (!peek(')')) fail("Ожидалась закрывающая скобка")
                pos++
                return value
            }
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (pos == start) fail("Ожидалось число или скобка, найдено: '${src[pos]}'")
            return src.substring(start, pos).toDoubleOrNull()
                ?: fail("Некорректное число: '${src.substring(start, pos)}'")
        }

        /** Проверяет текущий символ без сдвига позиции (пробелы пропускает вызывающий). */
        private fun peek(c: Char): Boolean = pos < src.length && src[pos] == c

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        /** Отбрасывает нефинитные результаты (переполнение, NaN) как ошибки вычисления. */
        private fun checked(value: Double): Double {
            if (!value.isFinite()) fail("Результат вычисления вне допустимого диапазона")
            return value
        }

        private fun fail(message: String): Nothing = throw ExpressionException(message)
    }

    companion object {
        const val NAME = "calculator"
    }
}
