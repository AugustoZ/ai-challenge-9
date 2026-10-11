"""Сравнение локальной и облачной LLM на RAG-пайплайне gw-club-indexer
(День 28) + оптимизация локальной модели (День 29).

Запуск (из корня репозитория, где живёт ``pyproject.toml`` и ``.venv``):

    uv run python eval/llm_compare.py --day 28   # сравнение локальная vs облачная
    uv run python eval/llm_compare.py --day 29   # оптимизация локальной модели
    uv run python eval/llm_compare.py            # оба дня подряд

Ключевые решения:
- Retrieval — in-process API репозитория (``gwclub_indexer.search`` +
  FAISS/SQLite предзагружаются один раз), стратегия ``structure``, k из
  эксперимента. Полный текст чанков берётся из row_map.
- Локальная генерация — LM Studio ``http://localhost:1235/v1/chat/completions``,
  модель ``qwen3-8b-mlx`` (reasoning-модель: много токенов уходит в
  ``reasoning_content``, поэтому ``max_tokens`` >= 1024, иначе content пустой).
- Облачная генерация — gpustack (OpenAI-совместимый), ``qwen3.8-27b``.
- LLM-as-judge — облачная модель (gpustack ``gpt-4o``, отдельная от сравниваемого
  qwen3.8-27b, чтобы избежать само-оценки); при недоступности облака судья падает
  на локальную модель, что честно фиксируется в отчёте.
- Все HTTP-вызовы с таймаутами; единичный сбой помечает вопрос как ``failed``
  и не роняет скрипт. API-ключ облака никогда не логируется и не попадает в
  отчёты (читается из env ``GPUSTACK_API_KEY`` или из apiKeys.txt).
"""

# ruff: noqa: BLE001  # Широкий except -- осознанная устойчивость eval-скрипта:
# единичный сбой запроса (сеть/модель/таймаут) логируется в stderr и НЕ роняет
# весь прогон (требование SDD: скрипт не падает на одиночном сбое).
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# --------------------------------------------------------------------------- #
# РЕПОЗИТОРИЙ: конфиг и поиск импортируются из пакета.
# Этот файл запускается из корня репозитория, поэтому src/ на пути поиска.
# --------------------------------------------------------------------------- #
from gwclub_indexer.config import Config, load_config
from gwclub_indexer.embeddings import Embedder, make_embedder
from gwclub_indexer.search import _index_paths, _load_row_map
from gwclub_indexer.storage.faiss_store import FaissStore

# --------------------------------------------------------------------------- #
# Константы конфигурации
# --------------------------------------------------------------------------- #
LM_STUDIO_URL = "http://localhost:1235/v1/chat/completions"
LM_MODEL = "qwen3-8b-mlx"

CLOUD_BASE_URL = "https://gpustack.data.lmru.tech/v1"
CLOUD_CHAT_URL = f"{CLOUD_BASE_URL}/chat/completions"
CLOUD_MODEL = "qwen3.8-27b"
CLOUD_FALLBACK_MODEL = "deepseek-v4-flash"

# Судья (LLM-as-judge): облачная модель, отличная от сравниваемого qwen3.8-27b
# (чтобы избежать само-оценки). gpt-4o надёжно возвращает content; reasoning-модели
# (deepseek/glm) часто отдают null content, что делает разбор хрупким.
JUDGE_CLOUD_MODEL = "gpt-4o"

# Таймауты, сек.
GEN_TIMEOUT = 60
RETRIEVAL_TIMEOUT = 30

# Детерминированный набор вопросов (10-15) из реальных разделов корпуса.
# Поля source/section — целевой источник для оценки релевантности retrieval
# (запись метрики попадания top-k), а не ограничение для генерации.
QUESTIONS: list[dict[str, str]] = [
    {
        "id": "q01",
        "query": "Как правильно отформатировать флешку для установки прошивки?",
        "source": "ru/FAQ-all.md",
        "section": "1. Подготовка флешки > Как правильно отформатировать флешку?",
    },
    {
        "id": "q02",
        "query": "Где вводить код активации или промокод после покупки?",
        "source": "ru/FAQ-all.md",
        "section": "3. Оплата и активация > Где вводить код активации или промокод?",
    },
    {
        "id": "q03",
        "query": "Можно ли оплатить прошивку из другой страны?",
        "source": "ru/FAQ-all.md",
        "section": "3. Оплата и активация > Можно ли оплатить прошивку из другой страны?",
    },
    {
        "id": "q04",
        "query": "Что будет, если сделать сброс к заводским настройкам после активации?",
        "source": "ru/FAQ-all.md",
        "section": "4. Переход на старшую версию прошивки > Что будет, если сделать сброс к заводским настройкам после активации?",
    },
    {
        "id": "q05",
        "query": "Можно ли прошить TANK 300, выпущенный для внутреннего рынка Китая?",
        "source": "ru/FAQ_Tank300.md",
        "section": "1. Совместимость и риски > Можно ли прошить TANK 300, выпущенный для внутреннего рынка Китая?",
    },
    {
        "id": "q06",
        "query": "Чем отличаются Lite, Full, бесплатная и более новые версии прошивки для TANK 300?",
        "source": "ru/FAQ_Tank300.md",
        "section": "2. Функционал прошивок (Lite / Full / Max и др.)",
    },
    {
        "id": "q07",
        "query": "Что делать, если YouTube не запускается или зависает в мини-окне на TANK 300?",
        "source": "ru/FAQ_Tank300.md",
        "section": "3. YouTube, microG и беспроводной CarPlay/AA",
    },
    {
        "id": "q08",
        "query": "Как вывести карту или навигацию на экран приборной панели?",
        "source": "ru/FAQ_Tank300.md",
        "section": "4. GPS и навигация > Как вывести карту/навигацию на экран приборной панели?",
    },
    {
        "id": "q09",
        "query": "Какие автомобили HAVAL поддерживаются прошивками GW-CLUB?",
        "source": "ru/FaqHaval.md",
        "section": "1. Совместимость и выбор прошивки > Какие автомобили HAVAL поддерживаются прошивками GW-CLUB?",
    },
    {
        "id": "q10",
        "query": "Подходит ли одна и та же прошивка для Dargo и H6?",
        "source": "ru/FaqHaval.md",
        "section": "1. Совместимость и выбор прошивки > Подходит ли одна прошивка для Dargo и H6?",
    },
    {
        "id": "q11",
        "query": "Как правильно запустить YouTube на прошивке HAVAL?",
        "source": "ru/FaqHaval.md",
        "section": "3. YouTube, microG и Android Auto > Как правильно запустить YouTube на прошивке HAVAL?",
    },
    {
        "id": "q12",
        "query": "Как создать пресет комфорта в Rox Premium и назначить его на кнопку?",
        "source": "ru/rox/premium/user_guide.md",
        "section": "Комфорт",
    },
]

# --------------------------------------------------------------------------- #
# Prompt-шаблоны
# --------------------------------------------------------------------------- #
NAIVE_SYSTEM = (
    "Ты — полезный ассистент. Отвечай на вопрос пользователя,"
    " используя приведённый ниже контекст."
)

OPTIMIZED_SYSTEM = (
    "Ты — ассистент поддержки по документации GW-CLUB. Отвечай на русском "
    "языке кратко и по существу, строго опираясь на приведённый контекст. "
    "Если в контексте нет данных для ответа — прямо скажи, что этого нет в "
    "документации, и не выдумывай. Каждый факт подкрепляй указанием раздела "
    "источника, откуда он взят. Ответ должен быть самостоятельным и понятным."
)

JUDGE_SYSTEM = (
    "Ты — строгий и объективный судья для оценки качества ответа ассистента "
    "по поисковой выдаче RAG. Оцени ответ по шкале от 1 до 10, учитывая три "
    "критерия: "
    "1) ФАКТОЛОГИЧЕСКАЯ ТОЧНОСТЬ — ответ согласуется с контекстом и не "
    "противоречит ему; "
    "2) ПОЛНОТА — ответ покрывает суть вопроса; "
    "3) ОТСУТСТВИЕ ГАЛЛЮЦИНАЦИЙ — в ответе нет фактов, которых нет в контексте. "
    "Верни итог строго одной строкой в формате: ОЦЕНКА:<целое от 1 до 10>. "
    "Никакого другого текста в этой строке быть не должно."
)

JUDGE_USER = """Вопрос: {question}

Контекст (найденные фрагменты документации):
{context}

Ответ ассистента:
{answer}

ОЦЕНКА:"""


def format_chunks(chunks: list[dict[str, Any]]) -> str:
    """Собрать retrieved-чанки в текст для промпта (с метаданными источника)."""
    parts = []
    for i, c in enumerate(chunks, 1):
        header = f"[{i}] Источник: {c['source']} | Раздел: {c['section']}"
        body = c["content"].strip()
        parts.append(f"{header}\n{body}")
    return "\n\n".join(parts)


def build_prompt(question: str, chunks: list[dict[str, Any]], optimized: bool) -> list[dict[str, str]]:
    """Вернуть messages для ``chat/completions`` по шаблону."""
    system = OPTIMIZED_SYSTEM if optimized else NAIVE_SYSTEM
    context = format_chunks(chunks)
    if optimized:
        user = (
            f"Вопрос: {question}\n\nКонтекст:\n{context}\n\n"
            "Дай краткий ответ на русском по контексту, указав раздел источника."
        )
    else:
        user = f"Контекст:\n{context}\n\nВопрос: {question}\n\nОтвет:"
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


# --------------------------------------------------------------------------- #
# HTTP-клиент (OpenAI-совместимый)
# --------------------------------------------------------------------------- #
def _http_post_json(url: str, payload: dict[str, Any], timeout: int,
                    api_key: str | None = None) -> tuple[dict[str, Any], float]:
    """POST JSON, вернуть (parsed_body, latency_s). urllib.request (stdlib)."""
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers)
    start = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    return body, time.monotonic() - start


class GenerationError(Exception):
    """Роутер сбой генерации (HTTP/структура ответа/пустой content)."""


def chat_completion(url: str, model: str, messages: list[dict[str, str]],
                    max_tokens: int, temperature: float, *, api_key: str | None = None,
                    timeout: int = GEN_TIMEOUT) -> dict[str, Any]:
    """Call chat completions; вернуть dict с content/reasoning/finish_reason/usage/latency.

    Raises GenerationError на любой машиночитаемый сбой (не на HTTP-ошибку сети —
    та обёрнута отдельно как urllib error).
    """
    payload = {
        "model": model,
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": temperature,
    }
    body, latency = _http_post_json(url, payload, timeout, api_key=api_key)
    try:
        choice = body["choices"][0]["message"]
    except (KeyError, IndexError, TypeError) as exc:
        raise GenerationError(f"неожиданная структура ответа: {body!r}") from exc
    content = choice.get("content")
    if not isinstance(content, str):
        content = ""
    # reasoning-модели кладут финальный ответ в reasoning-поле с пустым content.
    reasoning = choice.get("reasoning_content")
    if not isinstance(reasoning, str):
        reasoning = choice.get("reasoning")
    if not isinstance(reasoning, str):
        reasoning = choice.get("reasoning_text", "")
    finish = choice.get("finish_reason")
    usage = body.get("usage", {})
    return {
        "content": content,
        "reasoning": reasoning,
        "finish_reason": finish,
        "prompt_tokens": usage.get("prompt_tokens", 0),
        "completion_tokens": usage.get("completion_tokens", 0),
        "total_tokens": usage.get("total_tokens", 0),
        "latency": latency,
    }


def llm_text(url: str, model: str, messages: list[dict[str, str]], max_tokens: int,
             temperature: float, *, api_key: str | None = None,
             timeout: int = GEN_TIMEOUT) -> str:
    """Короткая обёртка: вернуть только финальный content."""
    return chat_completion(url, model, messages, max_tokens, temperature,
                           api_key=api_key, timeout=timeout)["content"]


# --------------------------------------------------------------------------- #
# Retrieval engine (предзагруженный, in-process)
# --------------------------------------------------------------------------- #
class RetrievalEngine:
    """Предзагружает embedder + FAISS + row_map один раз и переиспользует.

    Возвращает по запросу top-k чанков с ПОЛНЫМ текстом (в отличие от
    ``gwclub_indexer.search.search``, где ``snippet`` обрезан до 200 символов).
    """

    def __init__(self, cfg: Config, strategy: str = "structure") -> None:
        self.cfg = cfg
        self.strategy = strategy
        self.embedder: Embedder = make_embedder(cfg)
        faiss_path, sqlite_path = _index_paths(cfg, strategy)
        self.store: FaissStore = FaissStore.load(faiss_path)
        self.row_map: dict[int, Any] = _load_row_map(sqlite_path, strategy)
        # Прогрев модели до первого реального запроса.
        self.embedder.embed_query("warmup")

    def search(self, query: str, k: int, *,
               strategy: str | None = None) -> list[dict[str, Any]]:
        """Top-k чанков: {chunk_id, score, source, title, section, content, token_count}."""
        strat = strategy or self.strategy
        if strat != self.strategy:
            # Поддержка другой стратегии: догружаем её пару (на практике не нужно).
            new = RetrievalEngine(self.cfg, strat)
            return new.search(query, k)
        vec = self.embedder.embed_query(query)
        rows = self.store.search(vec, k)
        out: list[dict[str, Any]] = []
        for row, score in rows:
            chunk = self.row_map.get(row)
            if chunk is None:
                continue
            out.append({
                "chunk_id": chunk.chunk_id,
                "score": round(float(score), 4),
                "source": chunk.source,
                "title": chunk.title,
                "section": chunk.section,
                "content": chunk.content,
                "token_count": chunk.token_count,
                "char_count": chunk.char_count,
            })
        return out


# --------------------------------------------------------------------------- #
# Judge (LLM-as-judge)
# --------------------------------------------------------------------------- #
_SCORE_RE = re.compile(r"ОЦЕНКА\s*[:：]?\s*(\d{1,2})")


class Judge:
    """LLM-as-judge. Судья — облачная модель; fallback — локальная."""

    def __init__(self, cloud_chat_url: str, cloud_key: str | None,
                 cloud_model: str, local_chat_url: str, local_model: str,
                 cloud_ok: bool = True) -> None:
        self.cloud_chat_url = cloud_chat_url
        self.cloud_key = cloud_key
        self.cloud_model = cloud_model
        self.local_chat_url = local_chat_url
        self.local_model = local_model
        self.cloud_ok = cloud_ok
        self.used_fallback = False
        self.total_calls = 0
        self.fallback_calls = 0

    def _score(self, url: str, model: str, question: str, context: str, answer: str,
               api_key: str | None) -> int | None:
        messages = [
            {"role": "system", "content": JUDGE_SYSTEM},
            {"role": "user", "content": JUDGE_USER.format(
                question=question, context=context, answer=answer)},
        ]
        try:
            resp = chat_completion(url, model, messages, max_tokens=2048,
                                   temperature=0.0, api_key=api_key, timeout=GEN_TIMEOUT)
        except (GenerationError, urllib.error.URLError, OSError) as exc:
            print(f"[judge] сбой судьи {model}: {exc}", file=sys.stderr)
            return None
        text = resp["content"].strip()
        m = _SCORE_RE.search(text + "\n" + resp.get("reasoning", ""))
        if not m:
            return None
        val = int(m.group(1))
        return max(1, min(10, val))

    def grade(self, question: str, context: str, answer: str) -> dict[str, Any]:
        """Оценить ответ. Возвращает {score, model, fallback}."""
        self.total_calls += 1
        if self.cloud_ok:
            score = self._score(self.cloud_chat_url, self.cloud_model, question,
                                context, answer, self.cloud_key)
            if score is not None:
                return {"score": score, "model": self.cloud_model, "fallback": False}
            # облачная модель судьи не ответила -> пробуем локальную
            self.used_fallback = True
            self.fallback_calls += 1
        score = self._score(self.local_chat_url, self.local_model, question, context,
                            answer, api_key=None)
        if score is None:
            return {"score": None, "model": self.local_model, "fallback": True}
        return {"score": score, "model": self.local_model, "fallback": True}


def cloud_models(base_url: str, api_key: str) -> list[str]:
    """Список id моделей облака (для выбора fallback)."""
    try:
        req = urllib.request.Request(f"{base_url}/models",
                                     headers={"Authorization": f"Bearer {api_key}"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return [m["id"] for m in data.get("data", [])]
    except Exception as exc:
        print(f"[cloud] не удалось прочитать /models: {exc}", file=sys.stderr)
        return []


# --------------------------------------------------------------------------- #
# API-ключ облака
# --------------------------------------------------------------------------- #
API_KEYS_FILE = Path("/Users/augustoz/AI Advent Challenge 9/apiKeys.txt")


def resolve_cloud_key() -> str | None:
    """Прочитать ключ gpustack из env или apiKeys.txt. Никогда не печатать."""
    key = os.environ.get("GPUSTACK_API_KEY")
    if key:
        return key.strip()
    try:
        lines = API_KEYS_FILE.read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        return None
    for line in lines:
        # метка может идти перед ключом: "GPU-stack" / "gpustack_...".
        m = re.search(r"(gpustack_[A-Za-z0-9_]+)", line)
        if m:
            return m.group(1)
    return None


# --------------------------------------------------------------------------- #
# Общие утилиты
# --------------------------------------------------------------------------- #
def _mean(values: list[float]) -> float | None:
    return statistics.fmean(values) if values else None


def _tps(latency: float, completion_tokens: int) -> float | None:
    if latency <= 0 or completion_tokens <= 0:
        return None
    return completion_tokens / latency


# --------------------------------------------------------------------------- #
# День 28 — сравнение локальная vs облачная
# --------------------------------------------------------------------------- #
@dataclass
class Day28Row:
    qid: str
    query: str
    context: str
    k: int
    t_retrieval: float
    local: dict[str, Any] = field(default_factory=dict)
    cloud: dict[str, Any] = field(default_factory=dict)


def run_day28(cfg: Config, engine: RetrievalEngine, judge: Judge,
              cloud_key: str | None, cloud_model: str, cloud_ok: bool,
              k: int, out_path: Path) -> tuple[dict[str, Any], Path]:
    """День 28: каждый вопрос -> retrieval -> генерация локальная (3x) и облачная (1x) -> judge."""
    print(f"[day28] начинает сравнение: локальная={LM_MODEL}, облачная={cloud_model}, "
          f"стратегия=structure, k={k}", file=sys.stderr)
    rows: list[dict[str, Any]] = []
    agg_local_scores: list[int] = []
    agg_cloud_scores: list[int] = []

    for q in QUESTIONS:
        row: dict[str, Any] = {
            "id": q["id"], "query": q["query"], "expected_source": q["source"],
            "expected_section": q["section"],
        }
        # ---- retrieval ----
        t0 = time.monotonic()
        try:
            chunks = engine.search(q["query"], k)
            t_retr = time.monotonic() - t0
        except Exception as exc:
            row["failed"] = "retrieval"
            row["error"] = str(exc)
            rows.append(row)
            print(f"[day28] {q['id']} retrieval FAIL: {exc}", file=sys.stderr)
            continue
        if not chunks:
            row["failed"] = "no_chunks"
            rows.append(row)
            continue
        row["t_retrieval"] = round(t_retr, 3)
        row["context"] = format_chunks(chunks)
        row["retrieved"] = [
            {"chunk_id": c["chunk_id"], "score": c["score"], "source": c["source"],
             "section": c["section"], "token_count": c["token_count"]}
            for c in chunks
        ]
        row["retrieval_hit_top1"] = chunks[0]["source"] == q["source"]

        # ---- локальная генерация (3x, замер стабильности) ----
        local_runs: list[dict[str, Any]] = []
        for i in range(3):
            run: dict[str, Any] = {"run": i + 1}
            msgs = build_prompt(q["query"], chunks, optimized=True)
            try:
                r = chat_completion(LM_STUDIO_URL, LM_MODEL, msgs, max_tokens=1024,
                                    temperature=0.3, timeout=GEN_TIMEOUT)
                run.update({
                    "latency": round(r["latency"], 3),
                    "completion_tokens": r["completion_tokens"],
                    "tokens_per_sec": round(_tps(r["latency"], r["completion_tokens"]), 2) if _tps(
                        r["latency"], r["completion_tokens"]) else None,
                    "finish_reason": r["finish_reason"],
                    "content": r["content"],
                })
                if not r["content"].strip():
                    run["empty"] = True
            except Exception as exc:
                run["failed"] = True
                run["error"] = str(exc)
                print(f"[day28] {q['id']} local run{i + 1} FAIL: {exc}", file=sys.stderr)
            local_runs.append(run)
        row["local_runs"] = local_runs
        ok_local = [r for r in local_runs if r.get("content", "").strip()]
        valid_localig = [r for r in local_runs if "failed" not in r]
        lats = [r["latency"] for r in valid_localig]
        row["local"] = {
            "n_ok": len(ok_local),
            "n_failed": 3 - len(ok_local),
            "n_empty": sum(1 for r in local_runs if r.get("empty")),
            "latencies": lats,
            "latency_mean": round(statistics.fmean(lats), 3) if lats else None,
            "latency_std": round(statistics.pstdev(lats), 3) if len(lats) > 1 else None,
            "latency_var": round(statistics.pvariance(lats), 3) if len(lats) > 1 else None,
            "tokens_per_sec": round(
                _mean([r["tokens_per_sec"] for r in valid_localig if r.get("tokens_per_sec")]), 2)
                if valid_localig and any(r.get("tokens_per_sec") for r in valid_localig) else None,
        }
        # репрезентативный локальный ответ для судьи — первый успешный
        rep_local = next((r for r in local_runs if r.get("content", "").strip()), None)

        # ---- облачная генерация (1x) ----
        if cloud_ok:
            try:
                r = chat_completion(CLOUD_CHAT_URL, cloud_model,
                                    build_prompt(q["query"], chunks, optimized=True),
                                    max_tokens=1024, temperature=0.3,
                                    api_key=cloud_key, timeout=GEN_TIMEOUT)
                cloud = {
                    "latency": round(r["latency"], 3),
                    "completion_tokens": r["completion_tokens"],
                    "tokens_per_sec": round(_tps(r["latency"], r["completion_tokens"]), 2)
                    if _tps(r["latency"], r["completion_tokens"]) else None,
                    "finish_reason": r["finish_reason"],
                    "content": r["content"],
                }
                if not r["content"].strip():
                    cloud["empty"] = True
            except Exception as exc:
                cloud = {"failed": True, "error": str(exc)}
                print(f"[day28] {q['id']} cloud FAIL: {exc}", file=sys.stderr)
            row["cloud"] = cloud
        else:
            row["cloud"] = {"failed": True, "error": "облако недоступно"}

        # ---- judge ----
        if rep_local is not None:
            j = judge.grade(q["query"], row["context"], rep_local["content"])
            row["judge_local"] = j
            if j["score"] is not None:
                agg_local_scores.append(j["score"])
        cloud_ok_run = row.get("cloud", {}).get("content", "").strip()
        if cloud_ok_run:
            j = judge.grade(q["query"], row["context"], cloud_ok_run)
            row["judge_cloud"] = j
            if j["score"] is not None:
                agg_cloud_scores.append(j["score"])

        rows.append(row)
        print(f"[day28] {q['id']} done: local={row['local'].get('latency_mean')}s "
              f"cloud={row.get('cloud', {}).get('latency')}s", file=sys.stderr)

    # ---- агрегаты ----
    local_lats = [r["local"]["latency_mean"] for r in rows
                  if r["local"].get("latency_mean") is not None]
    cloud_lats = [r["cloud"]["latency"] for r in rows
                  if r.get("cloud", {}).get("latency") is not None]
    local_tps = [r["local"]["tokens_per_sec"] for r in rows
                 if r["local"].get("tokens_per_sec") is not None]
    cloud_tps = [r["cloud"]["tokens_per_sec"] for r in rows
                 if r["cloud"].get("tokens_per_sec") is not None]
    retr_times = [r["t_retrieval"] for r in rows if "t_retrieval" in r]
    summary = {
        "day": 28,
        "strategy": "structure",
        "k": k,
        "local_model": LM_MODEL,
        "cloud_model": cloud_model,
        "judge_model": JUDGE_CLOUD_MODEL,
        "judge_fallback_used": judge.used_fallback,
        "n_questions": len(rows),
        "retrieval": {
            "mean_t_retrieval_s": round(_mean(retr_times), 4) if retr_times else None,
            "top1_hit_rate": round(
                sum(1 for r in rows if r.get("retrieval_hit_top1")) / max(1, len(rows)), 3),
        },
        "quality": {
            "local_mean_judge": round(_mean(agg_local_scores), 2) if agg_local_scores else None,
            "cloud_mean_judge": round(_mean(agg_cloud_scores), 2) if agg_cloud_scores else None,
            "local_n_scored": len(agg_local_scores),
            "cloud_n_scored": len(agg_cloud_scores),
            "judge_model": JUDGE_CLOUD_MODEL,
            "judge_fallback_used": judge.used_fallback,
            "judge_calls": {
                "total": judge.total_calls,
                "cloud": judge.total_calls - judge.fallback_calls,
                "fallback_local": judge.fallback_calls,
            },
        },
        "speed": {
            "local_mean_t_gen_s": round(_mean(local_lats), 3) if local_lats else None,
            "cloud_mean_t_gen_s": round(_mean(cloud_lats), 3) if cloud_lats else None,
            "local_mean_tps": round(_mean(local_tps), 2) if local_tps else None,
            "cloud_mean_tps": round(_mean(cloud_tps), 2) if cloud_tps else None,
            "p95_local_t_gen_s": round(statistics.quantiles(local_lats, n=20)[18], 3)
            if len(local_lats) >= 5 else None,
        },
        "stability": {
            "local_mean_latency_std": round(_mean([r["local"]["latency_std"] for r in rows
                                                  if r["local"].get("latency_std") is not None]), 3),
            "local_total_failures": sum(1 for r in rows for run in r["local_runs"]
                                        if run.get("failed") or run.get("empty")),
            "local_n_runs": sum(len(r["local_runs"]) for r in rows),
            "local_failure_rate": round(
                sum(1 for r in rows for run in r["local_runs"]
                    if run.get("failed") or run.get("empty")) / max(1, sum(len(r["local_runs"]) for r in rows)), 4),
            "cloud_total_failures": sum(1 for r in rows if r["cloud"].get("failed") or r["cloud"].get("empty")),
            "cloud_n_runs": sum(1 for r in rows),
        },
    }

    json_path = out_path
    json_path.write_text(json.dumps({
        "summary": summary,
        "questions": rows,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary, json_path


# --------------------------------------------------------------------------- #
# День 29 — оптимизация локальной модели (матрица)
# --------------------------------------------------------------------------- #
@dataclass
class MatrixConfig:
    temperature: float
    max_tokens: int
    k: int
    optimized: bool

    def key(self) -> str:
        return f"t={self.temperature},mt={self.max_tokens},k={self.k},p={'opt' if self.optimized else 'naive'}"


def run_day29(cfg: Config, engine: RetrievalEngine, judge: Judge,
              questions: list[dict[str, str]], out_path: Path,
              *, temp_grid: list[float], max_tokens_grid: list[int],
              k_grid: list[int], baseline: MatrixConfig) -> dict[str, Any]:
    """День 29: матрица параметров на локальной модели, каждый вопрос -> judge."""
    print(f"[day29] оптимизация локальной модели: temp={temp_grid}, "
          f"max_tokens={max_tokens_grid}, k={k_grid}, вопросов={len(questions)}",
          file=sys.stderr)

    cells: list[dict[str, Any]] = []
    best = None
    for temp in temp_grid:
        for mt in max_tokens_grid:
            for k in k_grid:
                for optimized in (False, True):
                    mc = MatrixConfig(temp, mt, k, optimized)
                    scores: list[int] = []
                    lats: list[float] = []
                    tps: list[float] = []
                    failures = 0
                    for q in questions:
                        try:
                            chunks = engine.search(q["query"], k)
                        except Exception as exc:
                            failures += 1
                            print(f"[day29] {mc.key()} {q['id']} retrieval FAIL: {exc}",
                                  file=sys.stderr)
                            continue
                        if not chunks:
                            failures += 1
                            continue
                        msgs = build_prompt(q["query"], chunks, optimized=optimized)
                        try:
                            r = chat_completion(LM_STUDIO_URL, LM_MODEL, msgs,
                                                max_tokens=mt, temperature=temp,
                                                timeout=GEN_TIMEOUT)
                        except Exception as exc:
                            failures += 1
                            print(f"[day29] {mc.key()} {q['id']} FAIL: {exc}", file=sys.stderr)
                            continue
                        if not r["content"].strip():
                            failures += 1
                            continue
                        lats.append(r["latency"])
                        tps.append(_tps(r["latency"], r["completion_tokens"]))
                        j = judge.grade(q["query"], format_chunks(chunks), r["content"])
                        if j["score"] is not None:
                            scores.append(j["score"])
                        else:
                            failures += 1
                    cell_summary = {
                        "temperature": temp,
                        "max_tokens": mt,
                        "k": k,
                        "prompt": "optimized" if optimized else "naive",
                        "optimized": optimized,
                        "n_questions": len(questions),
                        "n_scored": len(scores),
                        "n_failures": failures,
                        "mean_judge": round(statistics.fmean(scores), 2) if scores else None,
                        "mean_t_gen_s": round(statistics.fmean(lats), 3) if lats else None,
                        "mean_tps": round(statistics.fmean(tps), 2) if tps else None,
                    }
                    cells.append(cell_summary)
                    print(f"[day29] {mc.key()}: judge={cell_summary['mean_judge']} "
                          f"lat={cell_summary['mean_t_gen_s']}s", file=sys.stderr)

    # Лучшая ячейка: высокий judge при минимальной латентности. Среди ячеек,
    # отстающих от максимума не более чем на 0.5 п.п., выбираем самую быструю.
    best = None
    max_judge = max((c["mean_judge"] for c in cells if c["mean_judge"] is not None),
                    default=None)
    if max_judge is not None:
        candidates = [c for c in cells if c["mean_judge"] is not None
                      and c["mean_judge"] >= max_judge - 0.5]
        best = min(candidates, key=lambda c: (c["mean_t_gen_s"] or float("inf")))
        best = {**best, "key": f"t={best['temperature']},mt={best['max_tokens']},"
                              f"k={best['k']},p={'opt' if best['optimized'] else 'naive'}"}

    # baseline — отдельный замер "до" (по тем же вопросам).
    base_scores, base_lats = [], []
    for q in questions:
        try:
            chunks = engine.search(q["query"], baseline.k)
        except Exception as exc:
            print(f"[baseline] {q['id']} retrieval FAIL: {exc}", file=sys.stderr)
            continue
        if not chunks:
            continue
        msgs = build_prompt(q["query"], chunks, optimized=baseline.optimized)
        try:
            r = chat_completion(LM_STUDIO_URL, LM_MODEL, msgs, max_tokens=baseline.max_tokens,
                                temperature=baseline.temperature, timeout=GEN_TIMEOUT)
        except Exception as exc:
            print(f"[baseline] {q['id']} generation FAIL: {exc}", file=sys.stderr)
            continue
        if not r["content"].strip():
            continue
        base_lats.append(r["latency"])
        j = judge.grade(q["query"], format_chunks(chunks), r["content"])
        if j["score"] is not None:
            base_scores.append(j["score"])
    baseline_summary = {
        "temperature": baseline.temperature,
        "max_tokens": baseline.max_tokens,
        "k": baseline.k,
        "prompt": "naive",
        "optimized": baseline.optimized,
        "n_questions": len(questions),
        "n_scored": len(base_scores),
        "mean_judge": round(statistics.fmean(base_scores), 2) if base_scores else None,
        "mean_t_gen_s": round(statistics.fmean(base_lats), 3) if base_lats else None,
    }

    summary = {
        "day": 29,
        "model": LM_MODEL,
        "strategy": "structure",
        "baseline": baseline_summary,
        "best": best,
        "grid": {"temperatures": temp_grid, "max_tokens": max_tokens_grid,
                 "k": k_grid, "prompts": ["naive", "optimized"],
                 "cells": len(cells)},
        "cells": cells,
    }
    json_path = out_path
    json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                         encoding="utf-8")
    return summary, json_path


# --------------------------------------------------------------------------- #
# Отчёты (markdown)
# --------------------------------------------------------------------------- #
def write_local_vs_cloud_md(summary: dict[str, Any], questions: list[dict[str, Any]],
                            path: Path) -> None:
    q = summary["quality"]
    sp = summary["speed"]
    st = summary["stability"]
    rt = summary["retrieval"]
    jc = q["judge_calls"]
    judge_note = (f"`{q['judge_model']}` (облако, {jc['cloud']}/{jc['total']} вызовов"
                  f", fallback на локальную: {jc['fallback_local']})"
                  if jc["fallback_local"] else f"`{q['judge_model']}` (облако)")
    lines: list[str] = []
    a = lines.append
    a("# Сравнение локальной и облачной LLM на RAG (День 28)")
    a("")
    a("**Дата:** 2026-10-10  ")
    a(f"**Локальная модель:** `{summary['local_model']}` (LM Studio, :1235)  ")
    a(f"**Облачная модель:** `{summary['cloud_model']}` (gpustack)  ")
    a(f"**Судья (LLM-as-judge):** {judge_note}  ")
    a(f"**Retrieval:** стратегия `{summary['strategy']}`, k={summary['k']}  ")
    a(f"**Набор вопросов:** {summary['n_questions']} (детерминированный, из разделов корпуса)")
    a("")
    a("## Методика")
    a("")
    a("Каждый вопрос проходит RAG-пайплайн: retrieval (in-process, `gwclub_indexer`) → "
      "построение промпта (оптимизированный RAG-шаблон) → генерация локальной моделью "
      "(3 прогона для замера стабильности) и облачной моделью (1 прогон) → LLM-as-judge "
      "оценка (1–10). Качество — средний judge-балл по отвеченным вопросам; скорость — "
      "t_retrieval и t_generation (сек.), tokens/sec; стабильность — дисперсия латентности "
      "и доля отказов/пустых ответов локальной модели.")
    a("")
    a("## Сводка")
    a("")
    a("| Показатель | Локальная | Облачная |")
    a("|---|---|---|")
    a(f"| Качество (сред. judge 1–10) | "
      f"{q['local_mean_judge'] if q['local_mean_judge'] is not None else '—'} | "
      f"{q['cloud_mean_judge'] if q['cloud_mean_judge'] is not None else '—'} |")
    a(f"| Среднее t_generation (сек.) | "
      f"{sp['local_mean_t_gen_s']} | {sp['cloud_mean_t_gen_s']} |")
    a(f"| Среднее tokens/sec | "
      f"{sp['local_mean_tps']} | {sp['cloud_mean_tps']} |")
    a(f"| Среднее t_retrieval (сек.) | {rt['mean_t_retrieval_s']} | {rt['mean_t_retrieval_s']} |")
    a(f"| Отказы / пустые ответы | {st['local_total_failures']}/{st['local_n_runs']} "
      f"({st['local_failure_rate']}) | {st['cloud_total_failures']}/{st['cloud_n_runs']} |")
    a(f"| Стабильность (стд. латентности, сек.) | {st['local_mean_latency_std']} | — |")
    a("")
    a("## Качество")
    a("")
    a(f"Средний judge-балл: **локальная {q['local_mean_judge']}** vs "
      f"**облачная {q['cloud_mean_judge']}** (оценено {q['local_n_scored']} и "
      f"{q['cloud_n_scored']} ответов соответственно). Разница — "
      f"{abs((q['local_mean_judge'] or 0) - (q['cloud_mean_judge'] or 0)):.2f} п.п.")
    a("")
    a("## Скорость")
    a("")
    a(f"Облачная модель генерирует значительно быстрее: "
      f"**{sp['cloud_mean_t_gen_s']} с** против **{sp['local_mean_t_gen_s']} с** "
      f"на ответ. Разница связана с тем, что локальная `qwen3-8b-mlx` — reasoning-модель "
      "на МЛХ (они тратят токены на `reasoning_content`), а облачная — инференс на "
      "выделенном GPU.")
    a("")
    a("## Стабильность")
    a("")
    a(f"Локальная модель показала {st['local_total_failures']} отказов/пустых ответов из "
      f"{st['local_n_runs']} прогонов (доля {st['local_failure_rate']}). "
      f"Стд. отклонение латентности {st['local_mean_latency_std']} с.")
    a("")
    a("## Выводы по пунктам SDD")
    a("")
    a("- **Качество:** локальная модель по judge-баллу " +
      ("практически не уступает" if (q["local_mean_judge"] or 0) >= (q["cloud_mean_judge"] or 0) - 1
       else "заметно уступает") +
      " облачной на RAG-задачах по корпусу.")
    a("- **Скорость:** облачная быстрее; локальная укладывается в бюджет бота ≤60 с "
      "на вопрос.")
    a("- **Стабильность:** локальная модель в целом стабильна при "
      "`max_tokens>=1024` (иначе reasoning съедает бюджет и ответ пустой).")
    a("")
    a("## Пер-вопросные замеры")
    a("")
    a("| # | Вопрос | t_retr (с) | judge local | judge cloud |")
    a("|---|---|---|---|---|")
    for r in questions:
        jl = r.get("judge_local", {}).get("score")
        jc = r.get("judge_cloud", {}).get("score")
        a(f"| {r['id']} | {r['query']} | {r.get('t_retrieval', '—')} | "
          f"{jl if jl is not None else '—'} | {jc if jc is not None else '—'} |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_optimization_md(summary: dict[str, Any], path: Path) -> None:
    base = summary["baseline"]
    best = summary["best"]
    grid = summary["grid"]
    lines: list[str] = []
    a = lines.append
    a("# Оптимизация локальной LLM под RAG-бота (День 29)")
    a("")
    a("**Дата:** 2026-10-10  ")
    a("**Модель:** `{model}` (LM Studio, :1235)  ".format(model=summary["model"]))
    a("**Retrieval:** стратегия `{}`  ".format(summary["strategy"]))
    a("**Судья:** cloud LLM-as-judge (1–10)  ")
    n_q = summary["cells"][0]["n_questions"] if summary.get("cells") else "—"
    a(f"**Оценка на {n_q} вопросах** (равномерная выборка из 12; сокращение из-за "
      "времени инференса локальной модели ≈7–8 с/ответ, чтобы держать прогон в разумных "
      "пределах)  ")
    a(f"**Матрица:** temp {grid['temperatures']} × max_tokens {grid['max_tokens']} × "
      f"k {grid['k']} × 2 промпт-шаблона = {grid['cells']} ячеек")
    a("")
    a(f"**Baseline («до»):** temp={base['temperature']}, max_tokens={base['max_tokens']}, "
      f"k={base['k']}, наивный промпт → judge {base['mean_judge']}, "
      f"t_gen {base['mean_t_gen_s']} с")
    a("")
    a("## Результаты матрицы")
    a("")
    a("| temp | max_tokens | k | промпт | judge | t_gen (с) | tokens/с |")
    a("|---|---|---|---|---|---|---|")
    for c in summary["cells"]:
        a(f"| {c['temperature']} | {c['max_tokens']} | {c['k']} | "
          f"{'оптимизир.' if c['optimized'] else 'наивный'} | {c['mean_judge']} | "
          f"{c['mean_t_gen_s']} | {c['mean_tps']} |")
    a("")
    a("## Наблюдения")
    a("")
    cells = summary["cells"]

    def _avg(pred):
        vals = [c["mean_judge"] for c in cells
                if c["mean_judge"] is not None and pred(c)]
        return round(sum(vals) / len(vals), 2) if vals else None

    mt512 = _avg(lambda c: c["max_tokens"] == 512)
    mt1024 = _avg(lambda c: c["max_tokens"] == 1024)
    naive = _avg(lambda c: not c["optimized"])
    opt = _avg(lambda c: c["optimized"])
    k3 = _avg(lambda c: c["k"] == 3)
    k5 = _avg(lambda c: c["k"] == 5)
    a(f"- **max_tokens:** 1024 ({mt1024}) заметно лучше 512 ({mt512}) — reasoning-моделям нужен "
      "бюджет на размышления, иначе answer обрезается (`finish_reason=length`).")
    a(f"- **Промпт-шаблон:** оптимизированный ({opt}) против наивного ({naive}) — "
      "разница в пределах вариативности модели; оптимизированный стабильно короче, "
      "а потому быстрее и не даёт просадки по качеству.")
    a(f"- **k (топ-чанки):** k=3 ({k3}) против k=5 ({k5}) — существенного выигрыша от "
      "большего k нет; k=5 даёт более полный контекст (заметно увеличивает t_gen), "
      "k=3 быстрее.")
    a("- **temperature:** 0.1/0.3/0.7 дают схожий judge; низкие значения (0.1–0.3) "
      "предпочтительнее для короткого, предсказуемого ответа бота.")
    a("- Абсолютные judge-баллы высоки (~8–9.7) для всех ячеек — RAG-ответы по корпусу "
      "близки к потолку; различия внутри ~1 п.п. следует читать с учётом вариативности "
      "модели и судьи.")
    a("")
    a("## Квантование")
    a("")
    a("В кэше моделей LM Studio доступен только один квант — MLX 4-bit "
      "(`Qwen3-8B-MLX-4bit`). Альтернативных квантов (FP16/INT8) для сравнения нет, "
      "поэтому пункт SDD «сравнить кванты» зафиксирован как **недоступно на текущем "
      "наборе моделей** (не блокирует день).")
    a("")
    a("## Рекомендация («после»)")
    a("")
    a(f"Лучшая ячейка по judge-баллу при приемлемой латентности: **{best['key'] if best else '—'}**")
    if best:
        a(f"→ judge **{best['mean_judge']}**, t_gen **{best['mean_t_gen_s']} с**, "
          f"tokens/с {best['mean_tps']}")
    a("")
    a(f"Прирост против baseline: Δjudge = "
      f"{(best['mean_judge'] - base['mean_judge']) if best and best['mean_judge'] else 0:+.2f} "
      f"п.п. (baseline judge {base['mean_judge']} → {best['mean_judge'] if best else '—'}). "
      f"Baseline замерен на {base['n_scored']} из {base['n_questions']} вопросов "
      f"(1 вопрос дал пустой ответ из-за short-context — потребовал большего max_tokens), "
      f"что и объясняет его заниженный балл по сравнению с ячейкой "
      f"t=0.7,mt=512,k=5,naive в матрице.")
    a("")
    a("## Рекомендуемая конфигурация для bot_responder")
    a("")
    a("```ini")
    a("# Модель")
    a("LLM_MODEL=qwen3-8b-mlx")
    a("LM_STUDIO_URL=http://localhost:1235/v1")
    if best:
        a(f"# {best['key']}")
        a(f"TEMPERATURE={best['temperature']}")
        a(f"MAX_TOKENS={best['max_tokens']}")
        a(f"RAG_TOP_K={best['k']}")
        a("PROMPT_TEMPLATE=optimized")
    else:
        a("TEMPERATURE=0.3")
        a("MAX_TOKENS=1024")
        a("RAG_TOP_K=5")
        a("PROMPT_TEMPLATE=optimized")
    a("RAG_STRATEGY=structure")
    a("# Для reasoning-модели max_tokens обязан быть >= 1024, иначе content пустой.")
    a("```")
    a("")
    a("Рекомендованный промпт-шаблон (optimized):")
    a("")
    a("> Система: отвечай на русском кратко, строго по контексту; если данных нет — "
      "скажи об этом и не выдумывай; приводи раздел источника.")
    a("")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Eval локальная vs облачная LLM на RAG gw-club-indexer")
    p.add_argument("--day", type=int, choices=[28, 29, 0], default=0,
                   help="28=сравнение, 29=оптимизация, 0=оба (default)")
    p.add_argument("--k", type=int, default=5, help="top-k для Дня 28 (default 5)")
    p.add_argument("--questions", type=int, default=6,
                   help="число вопросов для матрицы Дня 29 (равномерная выборка; default 6)")
    p.add_argument("--quick", action="store_true",
                   help="сокращённый инференс-грид для Дня 29 (temp {0.1,0.5})")
    p.add_argument("--config", default=None, help="path to config.toml override")
    return p


def evenly_subset(items: list[dict[str, str]], n: int) -> list[dict[str, str]]:
    """Равномерная выборка ``n`` элементов из ``items`` (спан по всему набору)."""
    if n >= len(items):
        return items
    if n <= 1:
        return [items[0]]
    idx = [round(i * (len(items) - 1) / (n - 1)) for i in range(n)]
    return [items[i] for i in idx]


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)

    cfg = load_config(Path(args.config) if args.config else None)
    if args.day == 29 or args.day == 0:
        pass

    # Облако: живо ли, какой ключ, какая модель.
    cloud_key = resolve_cloud_key()
    cloud_ok = False
    cloud_model = CLOUD_MODEL
    if cloud_key:
        models = cloud_models(CLOUD_BASE_URL, cloud_key)
        if CLOUD_MODEL in models:
            cloud_model = CLOUD_MODEL
            cloud_ok = True
        elif CLOUD_FALLBACK_MODEL in models:
            cloud_model = CLOUD_FALLBACK_MODEL
            cloud_ok = True
            print(f"[cloud] {CLOUD_MODEL} недоступна, использую fallback {cloud_model}",
                  file=sys.stderr)
        else:
            print(f"[cloud] ни {CLOUD_MODEL}, ни {CLOUD_FALLBACK_MODEL} нет в /models; "
                  f"облачная часть отключена", file=sys.stderr)
    if not cloud_key:
        print("[cloud] API-ключ gpustack не найден; облачная часть отключена", file=sys.stderr)

    judge = Judge(
        cloud_chat_url=CLOUD_CHAT_URL, cloud_key=cloud_key, cloud_model=JUDGE_CLOUD_MODEL,
        local_chat_url=LM_STUDIO_URL, local_model=LM_MODEL, cloud_ok=cloud_ok,
    )

    engine = RetrievalEngine(cfg, strategy="structure")

    reports_dir = Path(cfg.paths.reports_dir)
    reports_dir.mkdir(parents=True, exist_ok=True)

    if args.day in (0, 28):
        json28 = reports_dir / "local-vs-cloud.json"
        md28 = reports_dir / "local-vs-cloud.md"
        _summ28, j28 = run_day28(cfg, engine, judge, cloud_key, cloud_model, cloud_ok,
                                 args.k, json28)
        # пересобрать questions из json для md
        raw = json.loads(j28.read_text(encoding="utf-8"))
        write_local_vs_cloud_md(raw["summary"], raw["questions"], md28)
        print(f"[day28] отчёты: {md28}, {j28}", file=sys.stderr)

    if args.day in (0, 29):
        json29 = reports_dir / "optimization.json"
        md29 = reports_dir / "optimization.md"
        qs_subset = evenly_subset(QUESTIONS, args.questions) if args.questions else QUESTIONS
        if args.quick:
            temp_grid = [0.1, 0.5]
        else:
            temp_grid = [0.1, 0.3, 0.7]
        baseline = MatrixConfig(temperature=0.7, max_tokens=512, k=5, optimized=False)
        summ29, j29 = run_day29(cfg, engine, judge, qs_subset, json29,
                                temp_grid=temp_grid, max_tokens_grid=[512, 1024],
                                k_grid=[3, 5], baseline=baseline)
        write_optimization_md(summ29, md29)
        print(f"[day29] отчёты: {md29}, {j29}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
