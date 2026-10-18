"""Карта зависимостей проекта для /deps (источник — индекс isink17/codegraph).

Два режима:
- ``module_graph`` — модульная карта Kotlin-бэкенда (пакеты com.example.aiweb.*,
  рёбра «кто кого импортирует» из таблицы file_imports);
- ``callers_graph`` — кто вызывает символ (таблица edges, edge_kind='calls').

Модуль чистый (без asyncio/телеги): рёбра+DOT строют чистые функции, PNG делает
внешний graphviz (``dot``), при его отсутствии возвращается None — бот тогда
отправляет только текст. LLM не участвует.
"""

from __future__ import annotations

import os
import re
import sqlite3
import subprocess
from collections import defaultdict
from pathlib import Path

__all__ = [
    "codegraph_db",
    "module_graph",
    "callers_graph",
    "extract_symbol",
    "find_design_term",
    "design_term_note",
    "dot_to_bytes",
]

# Индекс Штурмана (репозиторий ai-challenge-9); переопределяется env CODEGRAPH_DB.
_DEFAULT_DB = (
    Path.home()
    / "AI Advent Challenge 9/Projects/ai-challenge-9/.codegraph/codegraph.sqlite"
)
SRC_ROOT = "src/main/kotlin/"
PREFIX = "com.example.aiweb"

_DOT_STYLE = (
    "digraph deps {\n"
    '  rankdir=LR; bgcolor="transparent";\n'
    '  node [shape=box, style="rounded,filled", fillcolor="#2b3040", '
    'fontcolor="#e8e8e8", color="#5fd68f", fontname="Helvetica"]; \n'
    '  edge [color="#ffb347", arrowsize=0.7];\n'
)


def codegraph_db() -> Path:
    """Путь к codegraph.sqlite: env CODEGRAPH_DB, иначе дефолт репозитория."""
    env = os.environ.get("CODEGRAPH_DB", "").strip()
    return Path(env) if env else _DEFAULT_DB


def _connect(db_path: Path) -> sqlite3.Connection:
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    return con


def _pkg_of(path: str) -> str:
    """src/main/kotlin/com/example/aiweb/routes/Routes.kt → com.example.aiweb.routes."""
    rel = path[len(SRC_ROOT):] if path.startswith(SRC_ROOT) else path
    return rel.rsplit("/", 1)[0].replace("/", ".")


def _short(name: str) -> str:
    return name.replace(PREFIX + ".", "")


def _pkg_deps(db_path: Path) -> dict[str, set[str]]:
    """Межмодульные зависимости Kotlin: {пакет: пакеты, которые он вызывает}."""
    con = _connect(db_path)
    rows = con.execute(
        "SELECT sp.path AS spath, dp.path AS dpath "
        "FROM edges e "
        "JOIN symbols ss ON ss.id = e.src_symbol_id "
        "JOIN files sp ON sp.id = ss.file_id "
        "JOIN symbols ds ON ds.id = e.dst_symbol_id "
        "JOIN files dp ON dp.id = ds.file_id "
        "WHERE e.edge_kind = 'calls' AND e.dst_symbol_id IS NOT NULL "
        "AND sp.path LIKE ? AND dp.path LIKE ?",
        (SRC_ROOT + "%", SRC_ROOT + "%"),
    ).fetchall()
    mod_deps: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        s, t = _pkg_of(row["spath"]), _pkg_of(row["dpath"])
        if s != t:
            mod_deps[s].add(t)
    return mod_deps


def module_graph(db_path: Path) -> tuple[dict, str]:
    """Модульная карта: (stats, DOT). Рёбра — резолвленные вызовы между пакетами."""
    con = _connect(db_path)
    files = {
        row["id"]: row["path"]
        for row in con.execute(
            "SELECT id, path FROM files WHERE language='kotlin' AND path LIKE ?",
            (SRC_ROOT + "%",),
        )
    }
    if not files:
        raise LookupError("В индексе нет Kotlin-файлов — переиндексируйте проект.")

    mod_deps = _pkg_deps(db_path)
    call_count = con.execute(
        "SELECT count(*) FROM edges e "
        "JOIN symbols ss ON ss.id = e.src_symbol_id "
        "JOIN files sp ON sp.id = ss.file_id "
        "JOIN symbols ds ON ds.id = e.dst_symbol_id "
        "JOIN files dp ON dp.id = ds.file_id "
        "WHERE e.edge_kind = 'calls' AND e.dst_symbol_id IS NOT NULL "
        "AND sp.path LIKE ? AND dp.path LIKE ?",
        (SRC_ROOT + "%", SRC_ROOT + "%"),
    ).fetchone()[0]

    lines = [_DOT_STYLE]
    edges = 0
    for s, ts in sorted(mod_deps.items()):
        lines.append(f'  "{_short(s)}";')
        for t in sorted(ts):
            lines.append(f'  "{_short(s)}" -> "{_short(t)}";')
            edges += 1
    lines.append("}\n")
    dot = "\n".join(lines)

    fanout = {s: len(ts) for s, ts in mod_deps.items()}
    fanin: dict[str, int] = defaultdict(int)
    for s, ts in mod_deps.items():
        for t in ts:
            fanin[t] += 1
    top = lambda d: sorted(d.items(), key=lambda kv: (-kv[1], kv[0]))[:5]
    stats = {
        "kind": "module",
        "kotlin_files": len(files),
        "modules": len(set(mod_deps) | {t for ts in mod_deps.values() for t in ts}),
        "dep_pairs": edges,
        "resolved_calls": call_count,
        "top_fanout": [(_short(k), v) for k, v in top(fanout)],
        "top_fanin": [(_short(k), v) for k, v in top(fanin)],
    }
    return stats, dot


def find_module(db_path: Path, text: str) -> str | None:
    """Ищет во фразе имя Kotlin-модуля (пакета) из модульной карты.

    «routes», «auth.token», «memory» — совпадение целым словом; возвращает
    полное имя пакета или None.
    """
    low = _norm(text)
    if not low:
        return None
    best: tuple[int, str] | None = None
    for pkg in _pkg_deps(db_path):
        short = _short(pkg)
        for alias in {short, short.rsplit(".", 1)[-1]}:
            if len(alias) < 3 or not re.search(
                rf"(?<![0-9a-zа-яё.]){re.escape(alias)}(?![0-9a-zа-яё.])", low
            ):
                continue
            if best is None or len(alias) > best[0]:
                best = (len(alias), pkg)
    return best and best[1]


def module_subgraph(db_path: Path, pkg: str) -> tuple[dict, str]:
    """Точечный подграф модуля: соседи (исходящие/входящие) вокруг ``pkg``."""
    mod_deps = _pkg_deps(db_path)
    if pkg not in mod_deps and not any(pkg in ts for ts in mod_deps.values()):
        raise LookupError(f"Модуль «{_short(pkg)}» не найден в модульной карте.")
    outgoing = sorted(mod_deps.get(pkg, ()))
    incoming = sorted(s for s, ts in mod_deps.items() if pkg in ts and s != pkg)

    target = _esc(_short(pkg))
    lines = [_DOT_STYLE, f'  "{target}" [fillcolor="#20301f"];']
    for t in outgoing:
        lines.append(f'  "{target}" -> "{_esc(_short(t))}";')
    for s in incoming:
        lines.append(f'  "{_esc(_short(s))}" -> "{target}";')
    lines.append("}\n")

    stats = {
        "kind": "module_sub",
        "module": _short(pkg),
        "outgoing": [_short(t) for t in outgoing],
        "incoming": [_short(s) for s in incoming],
    }
    return stats, "\n".join(lines)


def _find_symbol(con: sqlite3.Connection, symbol: str) -> sqlite3.Row | None:
    """Точное имя (без учёта регистра); фолбэк — qualified_name по суффиксу."""
    row = con.execute(
        "SELECT * FROM symbols WHERE name = ? COLLATE NOCASE "
        "ORDER BY CASE kind WHEN 'function' THEN 0 WHEN 'method' THEN 1 ELSE 2 END, id "
        "LIMIT 1",
        (symbol,),
    ).fetchone()
    if row is None:
        row = con.execute(
            "SELECT * FROM symbols WHERE qualified_name = ? COLLATE NOCASE LIMIT 1",
            (symbol,),
        ).fetchone()
    return row


def callers_graph(db_path: Path, symbol: str) -> tuple[dict, str]:
    """Кто вызывает ``symbol``: (stats, DOT) по resolved-рёбам и dst_name."""
    con = _connect(db_path)
    sym = _find_symbol(con, symbol)
    if sym is None:
        raise LookupError(f"Символ «{symbol}» не найден в индексе.")

    rows = con.execute(
        "SELECT s.name AS name, s.container_name AS container, s.kind AS kind, "
        "f.path AS path, e.line AS line "
        "FROM edges e "
        "JOIN symbols s ON s.id = e.src_symbol_id "
        "JOIN files f ON f.id = s.file_id "
        "WHERE e.edge_kind = 'calls' AND ("
        "  e.dst_symbol_id = ? OR (e.dst_symbol_id IS NULL AND "
        "  (e.dst_name = ? OR e.dst_name LIKE '%.' || ?))) "
        "ORDER BY f.path, e.line",
        (sym["id"], sym["name"], sym["name"]),
    ).fetchall()

    callers: list[dict] = []
    seen: set[tuple] = set()
    for r in rows:
        key = (r["name"], r["container"], r["path"])
        if key in seen:
            continue
        seen.add(key)
        callers.append(
            {
                "name": r["name"],
                "container": r["container"],
                "kind": r["kind"],
                "file": r["path"],
                "line": r["line"],
                "test": "/test/" in r["path"].replace("\\", "/"),
            }
        )

    target = f'{_clean(sym["container_name"]) + "." if sym["container_name"] else ""}{_clean(sym["name"])}'
    lines = [
        _DOT_STYLE,
        f'  "{_esc(target)}" [fillcolor="#20301f"];',
    ]
    for c in callers[:40]:  # потолок рёбер: граф должен читаться
        label = f'{_clean(c["container"]) + "." if c["container"] else ""}{_clean(c["name"])}'
        short_file = c["file"].rsplit("/", 1)[-1]
        node = f'{_esc(label)}\\n{_esc(short_file)}'
        lines.append(
            f'  "{node_id(label, c["file"])}" [label="{node}", '
            f'color="{"#8a8f98" if c["test"] else "#ffb347"}"];'
        )
        lines.append(
            f'  "{node_id(label, c["file"])}" -> "{_esc(target)}";'
        )
    lines.append("}\n")
    stats = {
        "kind": "callers",
        "symbol": sym["name"],
        "qualified": sym["qualified_name"],
        "callers": callers,
        "n_production": sum(1 for c in callers if not c["test"]),
        "n_tests": sum(1 for c in callers if c["test"]),
    }
    return stats, "\n".join(lines)


def _clean(name: str) -> str:
    """Снимает бэктики с имён Kotlin-тестов (``дефолтный блок…``)."""
    return name.strip("`")


def _esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def node_id(label: str, file: str) -> str:  # noqa: A002 - имя из signature
    return _esc(f"{label}@{file}")


def extract_symbol(db_path: Path, text: str) -> str | None:
    """Ищет имя код-символа в свободной фразе («кто вызывает renderPromptBlock»).

    Возвращает самое длинное совпавшее имя символа (без бэктиков) или None.
    """
    low = text.lower()
    names: list[str] = []
    try:
        con = _connect(db_path)
    except sqlite3.Error:
        return None
    for (name,) in con.execute("SELECT DISTINCT name FROM symbols"):
        clean = name.strip("`")
        if len(clean) < 4:
            continue
        if re.search(rf"(?<![\w.]){re.escape(clean.lower())}(?![\w.])", low):
            names.append(clean)
    if not names:
        return None
    return max(set(names), key=lambda n: (len(n), n))


# --- Дизайн-термины (токены tokens.css и правила DESIGN.md) -----------------
#
# codegraph индексирует только код (функции/классы), поэтому «карта
# зависимостей Amber Soft» или «правила Красный» символа не находят и бот
# падал в модульную карту. Эти функции распознают дизайн-термин во фразе и
# дают короткую справку вместо бесполезной модульной карты.

_DESIGN_TOKENS_REL = Path("frontend") / "src" / "styles" / "tokens.css"
_DESIGN_DOC_REL = Path("DESIGN.md")
_TOKEN_DECL_RE = re.compile(r"^\s*(--[\w-]+)\s*:\s*([^;]+);")
_RULE_RE = re.compile(r"\*\*Правило\s*«([^»]+)»")
_COLOR_RE = re.compile(r"^- \*\*([^*]+)\*\*\s*\((#[0-9a-fA-F]{3,8}|rgba\([^)]*\))\)")


def _norm(text: str) -> str:
    """«--Amber-Soft» → «amber soft»: регистр и пунктуация не важны."""
    return re.sub(r"[^0-9a-zа-яё]+", " ", text.lower()).strip()


def project_root(db_path: Path) -> Path:
    """<репо>/.codegraph/codegraph.sqlite → корень репозитория."""
    return db_path.parent.parent


def _read_lines(path: Path) -> list[str]:
    try:
        return path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []


def _tokens_with_lines(path: Path) -> dict[str, tuple[str, int]]:
    """tokens.css → {«--amber-soft»: (значение, строка)}."""
    tokens: dict[str, tuple[str, int]] = {}
    for no, line in enumerate(_read_lines(path), 1):
        m = _TOKEN_DECL_RE.match(line)
        if m:
            tokens[m.group(1)] = (m.group(2).strip(), no)
    return tokens


def _rules_with_lines(path: Path) -> dict[str, tuple[str, int]]:
    """DESIGN.md → {«янтарь»: («Янтарь — только действие», строка)}."""
    rules: dict[str, tuple[str, int]] = {}
    for no, line in enumerate(_read_lines(path), 1):
        m = _RULE_RE.search(line)
        if not m:
            continue
        title = m.group(1).strip()
        key = _norm(title.split("—")[0])
        if key:
            rules.setdefault(key, (title, no))
    return rules


def _colors_with_lines(path: Path) -> dict[str, tuple[str, int]]:
    """DESIGN.md, секция Colors → {«amber soft»: («Amber Soft (rgba…)», строка)}."""
    colors: dict[str, tuple[str, int]] = {}
    for no, line in enumerate(_read_lines(path), 1):
        m = _COLOR_RE.match(line)
        if m:
            name, value = m.group(1).strip(), m.group(2)
            colors.setdefault(_norm(name), (f"{name} ({value})", no))
    return colors


def _count_word(path: Path, word: str) -> int:
    return sum(
        len(re.findall(rf"(?<![\\w.-]){re.escape(word)}(?![\\w.-])", line, re.I))
        for line in _read_lines(path)
    )


def _token_uses_detail(root: Path, token: str) -> list[tuple[str, int]]:
    """Все ``var(--token)`` в исходниках фронта: [(относительный путь, строка)]."""
    src = root / "frontend" / "src"
    found: list[tuple[str, int]] = []
    for path in sorted(src.rglob("*")):
        if path.suffix not in (".css", ".tsx", ".ts") or not path.is_file():
            continue
        rel = str(path.relative_to(root))
        for no, line in enumerate(_read_lines(path), 1):
            if f"var({token})" in line:
                found.append((rel, no))
    return found


def _count_token_uses(root: Path, token: str) -> int:
    """Сколько раз var(--token) встречается в исходниках фронта."""
    return len(_token_uses_detail(root, token))


def find_design_term(db_path: Path, text: str) -> dict | None:
    """Ищет дизайн-токен/правило/цвет DESIGN.md в свободной фразе.

    Возвращает словарь с полями kind («правило»/«токен»/«цвет»), name,
    source («файл:строка»), detail, uses/doc_mentions — либо None, если
    фраза не содержит известный дизайн-термин.
    """
    low = _norm(text)
    if not low:
        return None
    root = project_root(db_path)
    tokens_css, design_md = root / _DESIGN_TOKENS_REL, root / _DESIGN_DOC_REL

    matches: list[tuple[int, int, dict]] = []  # (длина алиаса, приоритет, info)
    for prio, table in enumerate(
        (
            _rules_with_lines(design_md),
            _tokens_with_lines(tokens_css),
            _colors_with_lines(design_md),
        )
    ):
        kind = ("правило", "токен", "цвет")[prio]
        for raw_alias, (detail, line_no) in table.items():
            alias = _norm(raw_alias)
            uses_detail: list[tuple[str, int]] = []
            if len(alias) < 4 or not re.search(
                rf"(?<![0-9a-zа-яё]){re.escape(alias)}(?![0-9a-zа-яё])", low
            ):
                continue
            if kind == "токен":
                uses_detail = _token_uses_detail(root, raw_alias)
                display, source = raw_alias, f"frontend/src/styles/tokens.css:{line_no}"
                uses, doc_mentions = (
                    len(uses_detail),
                    _count_word(design_md, raw_alias.lstrip("-")),
                )
            else:
                display, source = detail, f"DESIGN.md:{line_no}"
                uses, doc_mentions = 0, _count_word(design_md, alias.split()[0])
            matches.append(
                (
                    len(alias),
                    -prio,
                    {
                        "kind": kind,
                        "name": display,
                        "source": source,
                        "detail": detail,
                        "uses": uses,
                        "doc_mentions": doc_mentions,
                        "uses_detail": uses_detail if kind == "токен" else [],
                    },
                )
            )
    if not matches:
        return None
    matches.sort(key=lambda item: (item[0], item[1]), reverse=True)
    return matches[0][2]


# --- TS-компоненты, которых нет в codegraph ---------------------------------
#
# codegraph не индексирует `export default function X` — большинство
# фронтенд-компонентов (AuthGate, Dial, SettingsPanel…) в symbols отсутствует,
# и deps-интент по ним падал в карту Kotlin-модулей. Эти функции дают граф
# импортов компонента по его файлу (scan import-спецификаторов по frontend/src).

_TS_SRC_REL = Path("frontend") / "src"


def find_ts_component(db_path: Path, text: str) -> str | None:
    """Ищет во фразе PascalCase-слово, совпадающее с именем файла фронта.

    «auth.AuthGate» → «AuthGate» (точечные имена берутся по хвосту).
    """
    root = project_root(db_path)
    src = root / _TS_SRC_REL
    stems = {p.stem for p in src.rglob("*.tsx")} | {p.stem for p in src.rglob("*.ts")}
    for word in re.findall(r"\b[A-Z][A-Za-z0-9]{2,}\b", text):
        if word in stems:
            return word
    return None


def ts_import_graph(db_path: Path, name: str) -> tuple[dict, str] | None:
    """Граф импортов фронтенд-модуля ``name``: (stats, DOT) или None.

    Рёбра — import-спецификаторы, заканчивающиеся на «/Name» (в т.ч.
    './Name'); тестовые файлы помечены отдельно.
    """
    root = project_root(db_path)
    src = root / _TS_SRC_REL
    candidates = list(src.rglob(f"{name}.tsx")) + list(src.rglob(f"{name}.ts"))
    candidates = [p for p in candidates if "node_modules" not in p.parts]
    if not candidates:
        return None
    target = candidates[0]
    rel = str(target.relative_to(root))
    pattern = re.compile(rf"[\"'][^\"']*/{re.escape(name)}[\"']")

    importers: list[dict] = []
    for path in sorted(src.rglob("*")):
        if path.suffix not in (".ts", ".tsx") or not path.is_file() or path == target:
            continue
        hits = [
            no
            for no, line in enumerate(_read_lines(path), 1)
            if pattern.search(line)
        ]
        if hits:
            importers.append(
                {
                    "file": str(path.relative_to(root)),
                    "line": hits[0],
                    "test": path.name.endswith(".test.ts") or path.name.endswith(".test.tsx"),
                }
            )

    esc_target = _esc(f"{name}\\n{rel.rsplit('/', 1)[-1]}")
    lines = [
        _DOT_STYLE,
        f'  "{esc_target}" [fillcolor="#20301f"];',
    ]
    for imp in importers[:40]:
        short_file = imp["file"].rsplit("/", 1)[-1]
        node = _esc(f"{short_file}\\nстрока {imp['line']}")
        lines.append(
            f'  "{node}" [label="{node}", '
            f'color="{"#8a8f98" if imp["test"] else "#ffb347"}"];'
        )
        lines.append(f'  "{node}" -> "{esc_target}";')
    lines.append("}\n")
    stats = {
        "kind": "imports",
        "symbol": name,
        "file": rel,
        "importers": importers,
        "n_production": sum(1 for i in importers if not i["test"]),
        "n_tests": sum(1 for i in importers if i["test"]),
    }
    return stats, "\n".join(lines)


def design_term_note(term: dict) -> str:
    """Короткая справка вместо модульной карты (для _send_text)."""
    lines = [f"🧭 «{term['name']}» — {term['kind']} дизайн-системы ({term['source']})."]
    if term["kind"] == "токен":
        lines[0] = (
            f"🧭 «{term['name']}» — дизайн-токен ({term['source']}, "
            f"значение `{term['detail']}`)."
        )
        lines.append(
            f"Применений в стилях фронта: {term['uses']}, "
            f"упоминаний в DESIGN.md: {term['doc_mentions']}."
        )
        detail = term.get("uses_detail") or []
        if detail:
            lines.append("Где применяется:")
            shown = detail[:8]
            lines += [f"• {rel}:{no}" for rel, no in shown]
            if len(detail) > len(shown):
                lines.append(f"• …и ещё {len(detail) - len(shown)} мест")
    elif term["kind"] == "правило":
        lines.append(f"Упоминаний в DESIGN.md: {term['doc_mentions']}.")
    lines.append(
        "Карта зависимостей строится только для код-символов "
        "(функции/классы); про роль — в DESIGN.md."
    )
    return "\n".join(lines)


def dot_to_bytes(dot: str) -> bytes | None:
    """DOT → PNG через graphviz; None, если ``dot`` не установлен/упал."""
    try:
        proc = subprocess.run(
            ["dot", "-Tpng"],
            input=dot.encode("utf-8"),
            capture_output=True,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0 or not proc.stdout:
        return None
    return proc.stdout

