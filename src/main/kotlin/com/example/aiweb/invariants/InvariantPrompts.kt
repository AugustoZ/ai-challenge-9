package com.example.aiweb.invariants

/**
 * Чистый рендер системного блока инвариантов (инъекция — задача 6 плана
 * assistant-invariants): маркер-заголовок + секции по скоупам + инструкция
 * отказа. Инъекция — 4-й хвостовой слот seed в [com.example.aiweb.routes]
 * (после trim: порядок предпочтения → состояние → инварианты → память).
 *
 * Правила:
 *  - оба скоупа пусты → null (пустой блок не инъектируется — guard пустоты);
 *  - пустая секция опускается, нумерация сквозная внутри каждой секции;
 *  - общий бюджет блока — [BUDGET] символов: переполнение обрезается
 *    с эллипсисом по образцу [com.example.aiweb.prefs.renderPromptBlock];
 *  - [INVARIANTS_MARKER] — тот же маркер, который ищет mock-режим refuse
 *    (задача 10): отказ с цитированием инварианта только при наличии блока.
 */

/** Маркер-заголовок блока инвариантов. */
const val INVARIANTS_MARKER = "НЕПРЕЛОЖНЫЕ ИНВАРИАНТЫ"

/** Инструкция отказа: как вести себя при конфликте запроса с инвариантами. */
const val REFUSAL_INSTRUCTION =
    "Перечисленные инварианты — непреодолимые ограничения. Если запрос " +
        "пользователя противоречит хотя бы одному — не выполняй его: вежливо " +
        "откажись, назови нарушенный инвариант и кратко объясни причину. " +
        "Предлагай только решения, совместимые с инвариантами."

/** Общий бюджет блока в символах (~2000 по плану). */
private const val BUDGET = 2000

/** Заголовок блока: маркер + статус запрета. */
private const val TITLE = "$INVARIANTS_MARKER (нарушать запрещено)"

/** Заголовок секции глобальных инвариантов. */
private const val GLOBAL_SECTION = "Глобальные:"

/** Заголовок секции инвариантов сессии. */
private const val SESSION_SECTION = "Сессия:"

/**
 * Строит системный блок инвариантов или null, если оба скоупа пусты.
 * [global] — инварианты глобального скоупа (файл-стор, задача 2);
 * [session] — инварианты сессии ([com.example.aiweb.session.Session.invariants],
 * задача 3). Блок не превышает [BUDGET] символов при любых валидных
 * инвариантах: переполнение → эллипсис в конце.
 */
fun renderInvariantBlock(global: List<StoredInvariant>, session: List<StoredInvariant>): String? {
    if (global.isEmpty() && session.isEmpty()) return null

    val lines = mutableListOf(TITLE)
    if (global.isNotEmpty()) {
        lines.add(GLOBAL_SECTION)
        global.forEachIndexed { index, invariant -> lines.add("${index + 1}. ${invariant.text}") }
    }
    if (session.isNotEmpty()) {
        lines.add(SESSION_SECTION)
        session.forEachIndexed { index, invariant -> lines.add("${index + 1}. ${invariant.text}") }
    }
    lines.add(REFUSAL_INSTRUCTION)

    val block = lines.joinToString("\n")
    return if (block.length <= BUDGET) block else block.take(BUDGET) + "…"
}
