"""Read-only доступ бота к файлам проекта «Штурман» (ai-challenge-9).

Тул-модуль в стиле deps_map.py: чистые функции без LLM. Корень дерева —
``PROJECT_FILES_ROOT`` (env, дефолт — корень ai-challenge-9 рядом с ботом).

Безопасность: путь резолвится ТОЛЬКО внутри корня — нормализация
``os.path.normpath`` + проверка вхождения без ``resolve()`` (в дереве проекта
есть симлинки корпуса ``docs/ru/<repo>`` — resolve() ломает сравнение
родителей, тот же питфолл, что в ``_load_source_docs``). Бинарные файлы и
служебные каталоги (.git, node_modules, build, data…) не читаются.
"""

from __future__ import annotations

import os
import re

# Дефолт — корень веб-проекта «Штурман» рядом с репо бота.
_DEFAULT_PROJECT_ROOT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "ai-challenge-9",
)

# Служебные каталоги: не ходим внутрь (и не индексируем их содержимое).
SKIP_DIRS = {
    ".git", ".github", ".gradle", ".idea", ".codegraph", ".venv", "venv",
    "__pycache__", "node_modules", "dist", "build", "data", ".data",
    "frontend-dist", ".openspec-cache",
}

# Расширения, которые как текст не читаем и документом не отправляем.
BINARY_EXT = {
    ".png", ".jpg", ".jpeg", ".gif", ".ico", ".icns", ".pdf", ".zip", ".tar",
    ".gz", ".tgz", ".jar", ".class", ".so", ".dylib", ".bin", ".exe", ".dll",
    ".db", ".sqlite", ".sqlite3", ".wasm", ".ttf", ".otf", ".woff", ".woff2",
    ".mp3", ".mp4", ".mov", ".wav", ".ogg", ".webm", ".DS_Store",
}

# Потолок чтения одного файла (символов) — файлы проекта на порядки меньше.
MAX_FILE_CHARS = 300_000

_WORD_RE = re.compile(r"[a-zA-Zа-яА-ЯёЁ0-9_.\-]+")


def project_root() -> str:
    """Корень дерева проекта: env ``PROJECT_FILES_ROOT`` или дефолт."""
    env = (os.environ.get("PROJECT_FILES_ROOT") or "").strip()
    root = os.path.abspath(env) if env else os.path.abspath(_DEFAULT_PROJECT_ROOT)
    return os.path.normpath(root)


def _contained(rel: str) -> str | None:
    """Нормализованный относительный путь внутри корня или None.

    Без resolve(): только отсечение ``..``/абсолютных путей и выхода за корень.
    """
    if not rel or "\x00" in rel:
        return None
    root = project_root()
    full = os.path.normpath(os.path.join(root, rel.lstrip("/")))
    if full == root or not full.startswith(root + os.sep):
        return None
    return os.path.relpath(full, root)


def _walk_files() -> list[str]:
    """Все файлы дерева проекта (относительные пути) без служебных каталогов."""
    root = project_root()
    out: list[str] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name == ".DS_Store":
                continue
            out.append(os.path.relpath(os.path.join(dirpath, name), root))
    return out


def find_files(query: str, limit: int = 8) -> list[str]:
    """Пути файлов проекта, подходящих под запрос (лучшие — первыми).

    Матч: все токены запроса встречаются в пути (нечувствительно к регистру);
    если таких нет — хотя бы один токен. Ранг: точное имя > имя содержит >
    подстрока пути; при равном ранге короче путь.
    """
    tokens = [t.lower().strip(".") for t in _WORD_RE.findall(query or "")]
    tokens = [t for t in tokens if t]
    if not tokens:
        return []
    all_files = _walk_files()
    strict: list[tuple[int, int, str]] = []
    loose: list[tuple[int, int, str]] = []
    for rel in all_files:
        low = rel.lower()
        base = os.path.basename(low)
        hits = [t for t in tokens if t in low]
        if not hits:
            continue
        score = 0
        for t in tokens:
            if base == t:
                score += 100
            elif t in base:
                score += 50
            elif t in low:
                score += 10
        bucket = strict if len(hits) == len(tokens) else loose
        bucket.append((-score, len(rel), rel))
    strict.sort()
    loose.sort()
    return [rel for _, _, rel in (strict + loose)[:limit]]


def read_text(rel: str) -> str | None:
    """Текст файла внутри корня проекта или None (снаружи/нет/бинарный)."""
    safe = _contained(rel)
    if safe is None:
        return None
    if os.path.splitext(safe)[1].lower() in BINARY_EXT:
        return None
    full = os.path.join(project_root(), safe)
    try:
        if not os.path.isfile(full):
            return None
        if os.path.getsize(full) > MAX_FILE_CHARS * 4:
            return None
        with open(full, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read(MAX_FILE_CHARS + 1)
    except OSError:
        return None
    if len(text) > MAX_FILE_CHARS:
        text = text[:MAX_FILE_CHARS] + "\n… (обрезано)"
    if "\x00" in text[:8000]:
        return None
    return text


# Префиксы корпуса RAG (docs/ru/<repo>/...): срезаем до пути внутри проекта.
_CORPUS_PREFIX_RE = re.compile(r"^ru/(ai-challenge-9|shturman-bot)/")


def resolve_request(request: str) -> str | None:
    """Путь файла в проекте из запроса модели (может быть размытым).

    Порядок: точный путь → срез префикса корпуса → поиск по имени файла.
    """
    req = (request or "").strip().strip("`\"'").strip()
    if not req:
        return None
    safe = _contained(req)
    if safe and os.path.isfile(os.path.join(project_root(), safe)):
        return safe
    stripped = _CORPUS_PREFIX_RE.sub("", req)
    if stripped != req:
        safe = _contained(stripped)
        if safe and os.path.isfile(os.path.join(project_root(), safe)):
            return safe
    base = os.path.basename(stripped)
    if base:
        exact = [rel for rel in find_files(base, limit=4)
                 if os.path.basename(rel).lower() == base.lower()]
        if len(exact) == 1:
            return exact[0]
    return None
