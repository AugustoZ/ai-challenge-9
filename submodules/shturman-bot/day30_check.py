#!/usr/bin/env python3
"""Проверки Дня 30 (SDD «Локальная LLM как приватный сервис»).

Проверяет:
  1. Стабильность при нескольких запросах: 5 параллельных генераций в
     LM Studio и 5 параллельных поисков в RAG-sidecar.
  2. Базовые ограничения: поведение при длинном контексте (>>8k токенов).
  3. Rate limit бота документируется отдельно (COOLDOWN_SEC=5 в
     bot_responder.py), здесь проверяется только транспорт.

Запуск:  .venv/bin/python day30_check.py
Результат: человекочитаемая сводка в stdout + JSON в .day30-check.json
(для отчёта reports/day30-service.md).

Секретов не выводит; все запросы — только к локальным сервисам.
"""

from __future__ import annotations

import asyncio
import json
import os
import statistics
import sys
import time
from pathlib import Path

import httpx

LM_API_BASE = os.environ.get("LM_API_BASE", "http://localhost:1235/v1")
LM_MODEL = os.environ.get("LM_MODEL", "qwen3-8b-mlx")
LM_MAX_TOKENS = int(os.environ.get("LM_MAX_TOKENS", "256"))
RAG_URL = os.environ.get("RAG_SEARCH_URL", "http://127.0.0.1:18700/search")
PARALLEL = int(os.environ.get("DAY30_PARALLEL", "5"))
LM_TIMEOUT = 180.0
RAG_TIMEOUT = 30.0

REPO_ROOT = Path(__file__).resolve().parent
OUT_JSON = Path(__file__).resolve().parent / ".day30-check.json"


async def _lm_call(client: httpx.AsyncClient, prompt: str, tag: str) -> dict:
    t0 = time.monotonic()
    try:
        r = await client.post(
            f"{LM_API_BASE.rstrip('/')}/chat/completions",
            json={
                "model": LM_MODEL,
                "messages": [{"role": "user", "content": prompt}],
                "max_tokens": LM_MAX_TOKENS,
                "temperature": 0.1,
            },
            timeout=LM_TIMEOUT,
        )
        r.raise_for_status()
        data = r.json()
        choice = (data.get("choices") or [{}])[0]
        content = ((choice.get("message") or {}).get("content") or "").strip()
        usage = data.get("usage") or {}
        return {
            "tag": tag,
            "ok": True,
            "latency": round(time.monotonic() - t0, 3),
            "empty": not content,
            "finish_reason": choice.get("finish_reason") or "",
            "total_tokens": usage.get("total_tokens"),
        }
    except Exception as exc:  # noqa: BLE001 - фиксируем любой сбой как отказ
        return {
            "tag": tag,
            "ok": False,
            "latency": round(time.monotonic() - t0, 3),
            "error": f"{type(exc).__name__}: {exc}"[:200],
        }


async def _rag_call(client: httpx.AsyncClient, tag: str) -> dict:
    t0 = time.monotonic()
    try:
        r = await client.post(
            RAG_URL,
            json={"query": "как отформатировать флешку для прошивки", "k": 5},
            timeout=RAG_TIMEOUT,
        )
        r.raise_for_status()
        hits = r.json()
        return {
            "tag": tag,
            "ok": True,
            "latency": round(time.monotonic() - t0, 3),
            "hits": len(hits) if isinstance(hits, list) else -1,
        }
    except Exception as exc:  # noqa: BLE001
        return {
            "tag": tag,
            "ok": False,
            "latency": round(time.monotonic() - t0, 3),
            "error": f"{type(exc).__name__}: {exc}"[:200],
        }


def _summary(rows: list[dict]) -> dict:
    lat = [r["latency"] for r in rows if r.get("ok")]
    return {
        "n": len(rows),
        "ok": sum(1 for r in rows if r.get("ok")),
        "failed": sum(1 for r in rows if not r.get("ok")),
        "empty_answers": sum(1 for r in rows if r.get("empty")),
        "latency_mean": round(statistics.fmean(lat), 3) if lat else None,
        "latency_max": round(max(lat), 3) if lat else None,
        "latency_stdev": round(statistics.pstdev(lat), 3) if len(lat) > 1 else 0.0,
    }


async def main() -> int:
    results: dict = {"lm_model": LM_MODEL, "parallel": PARALLEL}
    async with httpx.AsyncClient() as client:
        # --- 1. Параллельная генерация (LM Studio) ---
        t0 = time.monotonic()
        gen = await asyncio.gather(
            *[_lm_call(client, f"Ответь одним словом: {2 + i} + {i} = ?", f"gen{i}") for i in range(PARALLEL)]
        )
        results["lm_parallel"] = {"wall": round(time.monotonic() - t0, 3), "rows": list(gen), **_summary(list(gen))}

        # --- 2. Параллельный retrieval (sidecar) ---
        t0 = time.monotonic()
        rag = await asyncio.gather(*[_rag_call(client, f"rag{i}") for i in range(PARALLEL)])
        results["rag_parallel"] = {"wall": round(time.monotonic() - t0, 3), "rows": list(rag), **_summary(list(rag))}

        # --- 3. Длинный контекст (~8k токенов: ~48k символов русского текста) ---
        filler = ("Инструкция по установке прошивки повторяется для теста контекста. " * 560)
        long_prompt = f"{filler}\n\nВ конце этого текста задан вопрос: сколько будет 2+2? Ответь числом."
        approx_tokens = len(long_prompt) // 4
        t0 = time.monotonic()
        long_res = await _lm_call(client, long_prompt, "longctx")
        results["long_context"] = {
            "approx_input_tokens": approx_tokens,
            "limit_model": 40960,
            "within_limit": approx_tokens < 40960,
            **long_res,
        }

    # --- 4. Доступность по сети (bind-адреса слушателей) ---
    bind_info: dict[str, str] = {}
    try:
        out = __import__("subprocess").run(
            ["lsof", "-nP", "-iTCP:1235", "-sTCP:LISTEN"], capture_output=True, text=True, timeout=10
        )
        bind_info["lmstudio_listener"] = "localhost" if "localhost" in out.stdout else (
            "*:" if "*:" in out.stdout else out.stdout.strip() or "not-listening"
        )
    except Exception as exc:  # noqa: BLE001
        bind_info["lmstudio_listener"] = f"check-failed: {exc}"[:120]
    results["network_bind"] = bind_info

    OUT_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=2))

    # --- Человекочитаемая сводка ---
    lm, rg, lc = results["lm_parallel"], results["rag_parallel"], results["long_context"]
    print("=== День 30: стабильность при параллельных запросах ===")
    print(f"LM Studio  : {lm['ok']}/{lm['n']} ok, пустых {lm['empty_answers']}, "
          f"latency mean {lm['latency_mean']}s max {lm['latency_max']}s (wall {lm['wall']}s)")
    print(f"RAG sidecar: {rg['ok']}/{rg['n']} ok, latency mean {rg['latency_mean']}s (wall {rg['wall']}s)")
    print("=== Ограничения ===")
    print(f"Длинный контекст ~{lc['approx_input_tokens']} токенов (лимит 40960): "
          f"ok={lc['ok']}, finish={lc.get('finish_reason', '')!r}, latency {lc.get('latency')}s")
    print(f"LM Studio listener: {bind_info['lmstudio_listener']} "
          f"(для доступа по сети из LAN требуется bind 0.0.0.0 в LM Studio → Developer)")
    verdict = lm["failed"] == 0 and rg["failed"] == 0 and lc["ok"]
    print(f"ИТОГ: {'PASS' if verdict else 'FAIL'}  (JSON: {OUT_JSON.name})")
    return 0 if verdict else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
