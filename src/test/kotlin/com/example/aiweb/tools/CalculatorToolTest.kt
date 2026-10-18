package com.example.aiweb.tools

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверки калькулятора: приоритеты, скобки, унарный минус, степень, ошибки. */
class CalculatorToolTest {

    private val tool = CalculatorTool()

    private suspend fun eval(expression: String): ToolResult =
        tool.execute("{\"expression\": \"$expression\"}")

    @Test
    fun `приоритет операций умножение раньше сложения`() = runBlocking {
        val result = eval("2+3*4")
        assertTrue(result.ok)
        assertEquals("14", result.content)
    }

    @Test
    fun `скобки меняют порядок вычисления`() = runBlocking {
        val result = eval("(2+3)*4")
        assertTrue(result.ok)
        assertEquals("20", result.content)
    }

    @Test
    fun `унарный минус в начале и внутри выражения`() = runBlocking {
        val first = eval("-3+5")
        assertTrue(first.ok)
        assertEquals("2", first.content)

        val second = eval("2*-3")
        assertTrue(second.ok)
        assertEquals("-6", second.content)
    }

    @Test
    fun `степень правоассоциативна`() = runBlocking {
        val result = eval("2^3^2")
        assertTrue(result.ok)
        assertEquals("512", result.content)
    }

    @Test
    fun `деление на ноль даёт ошибку без исключения`() = runBlocking {
        val result = eval("1/0")
        assertFalse(result.ok)
        assertTrue(result.content.isNotBlank())
    }

    @Test
    fun `некорректное выражение даёт ошибку без исключения`() = runBlocking {
        val result = eval("два плюс два")
        assertFalse(result.ok)
        assertTrue(result.content.isNotBlank())
    }

    @Test
    fun `дробный результат без лишних нулей`() = runBlocking {
        val result = eval("7/2")
        assertTrue(result.ok)
        assertEquals("3.5", result.content)
    }

    @Test
    fun `аргументы не json объект дают ошибку`() = runBlocking {
        val result = tool.execute("[1,2]")
        assertFalse(result.ok)
        assertEquals(ARGS_NOT_OBJECT_ERROR, result.content)
    }
}
