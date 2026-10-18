"""Автопоиск файлов проекта для issue (/create).

Из описания задачи/бага извлекаются упоминания функций/модулей
(латинские идентификаторы, имена файлов, русские слова-подсказки) и
отыскиваются файлы проекта «Штурман» (ai-challenge-9), где они встречаются.
Результат — список относительных путей, которые бот автоматически
прикладывает к issue (в тело, кодовыми блоками — так же, как файлы,
присланные пользователем).

Чистые функции без LLM, в стиле deps_map.py / project_files.py:
модуль не «мнит» за модель — только детерминированный поиск по дереву.
Корень дерева — project_files.project_root() (env ``PROJECT_FILES_ROOT``).

Ограничения:
- содержимое корпуса-зеркала ``docs/`` и планов ``openspec/`` не ищем
  (``docs/ru/<repo>`` — симлинки на те же файлы, дубли совпадений);
- ищем только в текстовых исходниках (``_SEARCH_EXT``);
- результат — максимум ``limit`` файлов, отсортированных по релевантности.
"""

from __future__ import annotations

import os
import re

import project_files

# Служебные каталоги поверх project_files.SKIP_DIRS: в docs — симлинки-зеркала
# (тот же текст, что в живых репо), в openspec/backups — планы и бэкапы.
_EXTRA_SKIP = {"docs", "openspec", "backups"}

# Расширения, в которых имеет смысл искать упоминания функций/модулей.
_SEARCH_EXT = {
    ".kt", ".kts", ".ts", ".tsx", ".js", ".jsx", ".py", ".md", ".css",
    ".html", ".json", ".yml", ".yaml", ".toml", ".sql", ".sh",
}

# Потолок чтения одного файла при поиске (символов).
_SEARCH_FILE_CAP = 200_000

# Максимум кандидатов в выдаче.
DEFAULT_LIMIT = 5

# Латинский идентификатор: слово >= 4 символов (renderPromptBlock, TokenManager).
_IDENT_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]{3,}")

# Акроним из 3+ заглавных (PIN, SSE, RAG): короче минимума _IDENT_RE,
# но осмыслен; фильтруется общим стоп-листом.
_ACRO_RE = re.compile(r"(?<![A-Za-z0-9_])[A-Z][A-Z0-9]{2,}(?![a-z0-9_])")

# Явное имя файла с расширением в тексте описания: Routes.kt, useChatStream.ts.
_FILENAME_RE = re.compile(
    r"\b[A-Za-z0-9_.\-]+\.(kt|kts|ts|tsx|js|jsx|py|md|css|json)\b"
)

# Границы слова для счёта вхождений в файле (без учёта регистра:
# «PIN» в описании должно находить pin в исходниках).
def _word_re(token: str) -> re.Pattern:
    return re.compile(
        r"(?<![A-Za-z0-9_])" + re.escape(token) + r"(?![A-Za-z0-9_])",
        re.IGNORECASE,
    )

# Русские слова-подсказки → токены проекта (модули/пакеты/файлы).
# Русские слова в описаниях задач — основной случай; без словаря
# «добавь судье поле» не найдёт JudgePrompts.kt.
_RU_HINTS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("судь", ("judge",)),
    ("предпочтен", ("preferences", "preference")),
    ("памят", ("memory", "memoryretriever")),
    ("авторизац", ("auth", "tokenmanager")),
    ("логин", ("auth", "loginguard")),
    ("токен", ("tokenmanager", "token")),
    # «ПИН» латиницей короче 4 символов и идентификатором не ловится.
    ("пин", ("pin", "useraccount")),
    ("вход", ("auth", "loginguard", "authgate")),
    ("роут", ("routes",)),
    ("маршрут", ("routes",)),
    ("сесси", ("session", "sessionstore")),
    ("ветк", ("branches", "branch")),
    ("миграц", ("migration", "shturmanmigration")),
    ("настройк", ("settings", "appconfig")),
    ("самописц", ("exchangelog",)),
    ("провайдер", ("provider", "aiclient")),
    ("инструмент", ("tools", "toolregistry")),
    ("контекст", ("contextplanner", "contextstrategy")),
    ("сжатие", ("historycompressor",)),
    ("база знаний", ("rag",)),
)

# Общие английские слова, которые не являются символами проекта.
_STOP_TOKENS = {
    "the", "and", "for", "with", "this", "that", "from", "into", "issue",
    "create", "bug", "error", "file", "files", "need", "must", "should",
    "would", "when", "then", "there", "where", "what", "which", "please",
    "thanks", "check", "make", "have", "does", "some", "more", "than",
    "also", "after", "before", "only", "over", "under", "line", "text",
    "data", "list", "user", "name", "type", "true", "false", "none",
    "http", "https", "json", "config", "self", "class", "return", "print",
    "import", "body", "title", "test", "code", "todo", "github",
    # частые акронимы HTTP/UI, не указывающие на конкретный файл
    "api", "get", "post", "put", "url", "ui", "id",
}


def _camel_parts(token: str) -> list[str]:
    """Части camelCase-идентификатора длиной >= 4 (renderPromptBlock → [prompt, block])."""
    parts = re.findall(r"[A-Z]+(?![a-z])|[A-Z][a-z]+|[a-z]+", token)
    return [p for p in parts if len(p) >= 4]


def candidate_tokens(text: str) -> list[str]:
    """Токены поиска из описания: идентификаторы + русские подсказки.

    Порядок: точные идентификаторы длиннее — первыми; дубли снимаются.
    """
    raw = [t.strip("_") for t in _IDENT_RE.findall(text or "")]
    acros = [t for t in _ACRO_RE.findall(text or "")]
    seen: set[str] = set()
    tokens: list[str] = []
    for t in raw:
        low = t.lower()
        if len(t) < 4 or low in seen or low in _STOP_TOKENS:
            continue
        seen.add(low)
        tokens.append(t)
        for part in _camel_parts(t):
            if part.lower() not in seen and part.lower() not in _STOP_TOKENS:
                seen.add(part.lower())
                tokens.append(part)
    # Акронимы — порог 3 символа (PIN, SSE): идентификаторный порог их режет.
    for t in acros:
        low = t.lower()
        if low in seen or low in _STOP_TOKENS:
            continue
        seen.add(low)
        tokens.append(t)
    low = (text or "").lower()
    for needle, hints in _RU_HINTS:
        if needle in low:
            for h in hints:
                if h not in seen:
                    seen.add(h)
                    tokens.append(h)
    return tokens


def _walk_sources() -> list[str]:
    """Текстовые исходники проекта (относительные пути)."""
    root = project_files.project_root()
    skip = project_files.SKIP_DIRS | _EXTRA_SKIP
    out: list[str] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in skip and not d.startswith(".")]
        for name in filenames:
            ext = os.path.splitext(name)[1].lower()
            if ext in _SEARCH_EXT:
                out.append(os.path.relpath(os.path.join(dirpath, name), root))
    return out


def _score_file(text: str, tokens: list[str]) -> int:
    """Ранг файла: сумма вхождений (вес растёт с длиной токена)."""
    score = 0
    for t in tokens:
        n = len(_word_re(t).findall(text))
        if n:
            score += n * (1 + len(t) // 6)
    return score


def locate_files(text: str, limit: int = DEFAULT_LIMIT) -> list[str]:
    """Файлы проекта, где встречаются упомянутые функции/модули.

    Ранг: явное имя файла > совпадение имени файла > вхождения в тексте;
    при равенстве — файл, покрывший больше разных токенов, затем короче путь.
    Нет токенов → [].
    """
    tokens = candidate_tokens(text)
    if not tokens:
        return []

    # 1) Явные имена файлов — резолвим напрямую (самый надёжный сигнал).
    ranked: dict[str, float] = {}
    for m in _FILENAME_RE.finditer(text or ""):
        rel = project_files.resolve_request(m.group(0))
        if rel and rel not in ranked:
            ranked[rel] = 1000.0

    # 2) Имя файла/пути совпало с токеном (только ищемые расширения —
    # без картинок/скриншотов, где совпадение имени ничего не значит).
    for t in tokens:
        for rel in project_files.find_files(t, limit=4):
            if os.path.splitext(rel)[1].lower() not in _SEARCH_EXT:
                continue
            base = os.path.basename(rel).lower()
            if base.split(".")[0].lower() == t.lower():
                ranked[rel] = max(ranked.get(rel, 0), 500.0)
            elif t.lower() in os.path.basename(rel).lower():
                ranked[rel] = max(ranked.get(rel, 0), 100.0)

    # 3) Вхождения идентификаторов в содержимом исходников.
    sources = _walk_sources()
    root = project_files.project_root()
    for rel in sources:
        full = os.path.join(root, rel)
        try:
            if os.path.getsize(full) > _SEARCH_FILE_CAP:
                continue
            with open(full, "r", encoding="utf-8", errors="replace") as fh:
                content = fh.read(_SEARCH_FILE_CAP)
        except OSError:
            continue
        score = _score_file(content, tokens)
        if score:
            ranked[rel] = max(ranked.get(rel, 0), float(score))

    return [rel for rel, _ in sorted(ranked.items(), key=lambda kv: (-kv[1], len(kv[0])))[:limit]]


def auto_note(paths: list[str]) -> str:
    """Строка ack-сообщения о найденных файлах (без файлов — пустая строка)."""
    if not paths:
        return ""
    listing = "\n".join(f"  • {p}" for p in paths)
    return f"\n🔎 Нашёл в проекте по описанию (приложу к issue):\n{listing}"
