package com.example.aiweb.tools

import io.ktor.http.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки приёма типов содержимого и извлечения текста из HTML у http_get.
 * Функции чистые (без сети) — тестируются напрямую, как гарды в GuardsTest.
 */
class HttpGetToolContentTest {

    private val tool = HttpGetTool()

    @Test
    fun `xml-производные типы принимаются`() {
        listOf(
            "application/xml",
            "text/xml",
            "application/rss+xml",
            "application/atom+xml",
            "application/xhtml+xml",
            "application/json",
            "text/plain",
            "text/html"
        ).forEach { type ->
            assertTrue(tool.isAcceptedType(ContentType.parse(type)), "Ожидался приём: $type")
        }
    }

    @Test
    fun `двоичные типы отклоняются`() {
        listOf(
            "application/octet-stream",
            "image/png",
            "application/pdf",
            "video/mp4",
            "application/zip"
        ).forEach { type ->
            assertFalse(tool.isAcceptedType(ContentType.parse(type)), "Ожидался отказ: $type")
        }
    }

    @Test
    fun `html распознаётся по типу содержимого`() {
        assertTrue(tool.isHtmlContent(ContentType.Text.Html))
        assertTrue(tool.isHtmlContent(ContentType.parse("application/xhtml+xml; charset=utf-8")))
        assertFalse(tool.isHtmlContent(ContentType.Application.Json))
        assertFalse(tool.isHtmlContent(ContentType.Text.Plain))
    }

    @Test
    fun `из html извлекается читаемый текст`() {
        val html = """
            <html><head><title>Пробки СПб</title>
            <style>body { color: red; }</style>
            <script>var evil = 42;</script></head>
            <body>
            <h1>Балл&nbsp;пробок</h1>
            <p>Сейчас 5 &amp; идёт дождь</p>
            <script>alert(1)</script>
            <!-- скрытый комментарий -->
            <div>Второй   блок</div>
            <table><tr><td>ячейка</td></tr></table>
            </body></html>
        """.trimIndent()

        val text = tool.htmlToText(html)

        assertTrue(text.contains("Пробки СПб"), "заголовок страницы сохранён: $text")
        assertTrue(text.contains("Балл пробок"), "nbsp раскрыт в пробел: $text")
        assertTrue(text.contains("Сейчас 5 & идёт дождь"), "&amp; раскрыт: $text")
        assertTrue(text.contains("ячейка"), "таблица превращена в текст: $text")
        assertFalse(text.contains("evil"), "содержимое script выкинуто: $text")
        assertFalse(text.contains("alert"), "второй script выкинут: $text")
        assertFalse(text.contains("color: red"), "содержимое style выкинуто: $text")
        assertFalse(text.contains("скрытый комментарий"), "комментарии выкинуты: $text")
        assertFalse(text.contains("<"), "теги сняты: $text")
        assertTrue(text.contains("Второй блок"), "пробелы схлопнуты: $text")
    }

    @Test
    fun `числовые сущности раскрываются`() {
        assertEquals("a<бA", tool.htmlToText("a&#60;б&#x41;"))
    }

    @Test
    fun `текст без тегов проходит без потерь`() {
        assertEquals("просто текст", tool.htmlToText("просто текст"))
    }
}
