"""Запись бота в файлы проекта «Штурман» (ai-challenge-9) — гейт правок.

Зеркало ``project_files.py`` (чтение) для агентного режима: чистые функции
без LLM, только stdlib. Корень дерева — тот же ``PROJECT_FILES_ROOT``
(env, дефолт — корень ai-challenge-9 рядом с ботом); логика containment
НЕ дублируется — импортируется из ``project_files``.

Безопасность: путь резолвится ТОЛЬКО внутри корня — нормализация
``os.path.normpath`` + проверка вхождения без ``resolve()`` (в дереве
проекта есть симлинки корпуса ``docs/ru/<repo>`` — resolve() ломает
сравнение родителей, тот же питфолл, что в ``project_files._contained``).
Поверх containment — список запрещённых путей: секреты (config.json,
.env), артефакты сборки (build/, node_modules/, frontend/dist/) и
служебные каталоги (.git/, data/, backups/, openspec/) — запись туда
отклоняется на валидации, до бэкапа и диска.

Запись на диск — только ``write_file`` и только ПОСЛЕ подтверждения
пользователя (гейт ``AWAIT_CONFIRM`` — ответственность вызывающего,
модуль подтверждение не проверяет). Чистые функции ``apply_write`` /
``apply_patch`` / ``compute_diff`` работают в памяти — на них строится
показ диффов до подтверждения.
"""

from __future__ import annotations

import difflib
import os
import shutil
import tempfile

from project_files import _contained, BINARY_EXT, MAX_FILE_CHARS, project_root

# Потолок содержимого одной правки (символов).
MAX_WRITE_CHARS = 300_000

# Потолок файла, для которого делается бэкап (байт): большие копировать не
# пытаемся — запись разрешается без бэкапа.
MAX_BACKUP_BYTES = 1024 * 1024

# Потолок длины относительного пути (символов) — защита от патологических
# путей из ответа модели.
MAX_REL_LEN = 512

# Пути, куда запись запрещена даже внутри корня: точные имена файлов и
# каталоги (с завершающим «/»). Проверка — по нормализованному
# относительному пути, регистронезависимо.
FORBIDDEN_PREFIXES = [
    "config.json",
    "config.docker.json",
    "config.example.json",
    ".env",
    ".gitignore",
    ".git/",
    "node_modules/",
    "data/",
    "frontend/dist/",
    "build/",
    ".gradle/",
    "backups/",
]


def is_forbidden(rel: str) -> bool:
    """True — путь попадает в запрещённый список (имя или каталог)."""
    low = os.path.normpath((rel or "").strip().lstrip("/"))
    low = low.replace(os.sep, "/").lower()
    if not low or low == ".":
        return False
    for pref in FORBIDDEN_PREFIXES:
        if low == pref or low.startswith(pref):
            return True
    return False


def validate_path(rel: str) -> str | None:
    """Безопасный относительный путь для записи или None.

    Отсекает: пустые пути, выход за корень (включая ``..`` и абсолютные —
    через ``project_files._contained``, без resolve()), бинарные
    расширения, запрещённые пути и пути длиннее 512 символов.
    """
    safe = _contained(rel or "")
    if safe is None:
        return None
    if len(safe) > MAX_REL_LEN:
        return None
    if os.path.splitext(safe)[1].lower() in BINARY_EXT:
        return None
    if is_forbidden(safe):
        return None
    return safe


def backup_file(rel: str) -> str | None:
    """Копия файла в ``<root>/backups/agent/<rel>`` или None.

    None — файла ещё не было (создание), путь снаружи корня, файл больше
    1 МБ (копия отменяется, запись разрешается) или ошибка копирования
    (запись всё равно выполняется — бэкап best-effort).
    """
    safe = _contained(rel or "")
    if safe is None:
        return None
    full = os.path.join(project_root(), safe)
    try:
        if not os.path.isfile(full):
            return None
        if os.path.getsize(full) > MAX_BACKUP_BYTES:
            return None
        dst = os.path.join(project_root(), "backups", "agent", safe)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(full, dst)
        return dst
    except OSError:
        return None


# -------------------------------------------------------------------------
# Чистая часть (без записи на диск) — подготовка правок и диффов
# -------------------------------------------------------------------------

def apply_write(original: str | None, new_text: str) -> str:
    """Полная замена содержимого файла (``<write>``)."""
    return new_text


def apply_patch(original: str | None, old_text: str, new_text: str) -> tuple[str, bool]:
    """Замена ПЕРВОГО вхождения ``old_text`` (``<patch>``).

    ``original=None`` трактуется как пустой файл (новый). Пустой
    ``old_text`` правкой не считается. Возвращает (новый текст,
    применён): если вхождение не найдено — (исходный текст, False);
    вызывающий решает, что делать с неприменённым патчем.
    """
    base = original or ""
    if not old_text:
        return base, False
    idx = base.find(old_text)
    if idx < 0:
        return base, False
    return base[:idx] + new_text + base[idx + len(old_text):], True


def compute_diff(old: str | None, new: str, rel: str) -> str:
    """Unified-дифф old -> new с путями ``a/<rel>`` / ``b/<rel>``.

    Для нового файла old='' (весь текст — добавленные строки).
    """
    label_a = f"a/{rel}"
    label_b = f"b/{rel}"
    diff = "".join(difflib.unified_diff(
        (old or "").splitlines(keepends=True),
        (new or "").splitlines(keepends=True),
        fromfile=label_a,
        tofile=label_b,
    ))
    return diff


# -------------------------------------------------------------------------
# Запись на диск — только после подтверждения пользователем
# -------------------------------------------------------------------------

def write_file(rel: str, content: str) -> dict:
    """Записать ``content`` в файл ``rel`` внутри корня проекта.

    Порядок: валидация пути → лимит размера → бэкап оригинала →
    атомарная запись (tmp-файл в том же каталоге + os.replace, utf-8).
    Подтверждение пользователя проверяет вызывающий (гейт AWAIT_CONFIRM).

    Возвращает {path, ok, backup, error}: path — безопасный относительный
    путь, backup — путь бэкапа или None (файла ещё не было), error —
    причина отказа (ok=False): путь запрещён, контент больше лимита или
    ошибка записи.
    """
    result: dict = {"path": rel, "ok": False, "backup": None, "error": None}
    if len(content) > MAX_WRITE_CHARS:
        result["error"] = (
            f"Контент больше лимита ({len(content)} > {MAX_WRITE_CHARS} символов)."
        )
        return result
    safe = validate_path(rel)
    if safe is None:
        result["error"] = "Путь вне корня проекта, запрещён или бинарный."
        return result
    result["path"] = safe
    full = os.path.join(project_root(), safe)
    backup = backup_file(safe)
    try:
        os.makedirs(os.path.dirname(full) or ".", exist_ok=True)
        fd, tmp = tempfile.mkstemp(
            dir=os.path.dirname(full) or ".",
            prefix=".agent-tmp-",
            suffix=os.path.basename(safe),
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write(content)
            os.replace(tmp, full)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
    except OSError as exc:
        result["error"] = f"Ошибка записи: {exc}"
        return result
    result["ok"] = True
    result["backup"] = backup
    return result
