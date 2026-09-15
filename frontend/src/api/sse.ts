/* SSE-конвейер /api/chat/stream. Протокол не меняется: кадры
   `data: <одна строка JSON>\n\n`, дискриминатор — поле `type`
   (agent/status/final/cancelled/error). Логика чтения перенесена
   дословно из script.js (parseStreamFrame + sendChatStream) —
   парсеры вынесены в чистые функции для Vitest. */

import {
  isTerminalStreamEvent,
  type ChatRequest,
  type StreamEvent,
} from './types';
import { useAuthStore } from '../stores/auth';

/** Wire-типы кадров; неизвестный тип — кадр пропускается (как в vanilla). */
const STREAM_EVENT_TYPES = ['agent', 'status', 'final', 'cancelled', 'error'];

/**
 * Режет накопленный буфер на кадры по разделителю «\n\n».
 * Хвост без завершающего разделителя возвращается в rest —
 * он дожидается следующего чанка (обрыв между чанками не теряет кадры).
 */
export function extractFrames(buffer: string): { frames: string[]; rest: string } {
  const frames: string[] = [];
  let rest = buffer;
  let sep = rest.indexOf('\n\n');
  while (sep !== -1) {
    frames.push(rest.slice(0, sep));
    rest = rest.slice(sep + 2);
    sep = rest.indexOf('\n\n');
  }
  return { frames, rest };
}

/**
 * Разбор одного кадра: строки «data: <JSON>»; комментарии «:…» пропускаем.
 * Возвращает первое событие известного типа; битый JSON или неизвестный
 * type — null (та же толерантность, что у vanilla-парсера). Бэкенд-фрейм
 * всегда содержит ровно одну data-строку (encodeFrame в Sse.kt).
 */
export function parseFrame(frameText: string): StreamEvent | null {
  for (const rawLine of frameText.split('\n')) {
    const line = rawLine.replace(/\r$/, '');
    if (line.startsWith(':')) continue;
    if (!line.startsWith('data:')) continue;

    const payload = line.slice(5).trim();
    if (!payload) continue;

    let event: unknown;
    try {
      event = JSON.parse(payload);
    } catch {
      continue; // битый кадр — пропускаем, ждём следующий
    }
    if (
      event !== null &&
      typeof event === 'object' &&
      'type' in event &&
      typeof (event as { type: unknown }).type === 'string' &&
      STREAM_EVENT_TYPES.includes((event as { type: string }).type)
    ) {
      return event as StreamEvent;
    }
  }
  return null;
}

export interface StreamChatOptions {
  /** Сигнал отмены чтения («Стоп» = abort() на стороне вызывающего). */
  signal?: AbortSignal;
  /** Колбэк для каждого разобранного события кадра. */
  onEvent: (event: StreamEvent) => void;
}

export interface StreamChatResult {
  /**
   * true — пришёл хотя бы один кадр (fallback на блокирующий /api/chat
   * не нужен); false — ни одного кадра до конца/обрыва (нужен fallback).
   */
  ok: boolean;
  /** Сколько кадров received (включая неформатные). */
  frames: number;
}

/**
 * Живой поток: POST /api/chat/stream, кадры разбираются по мере прихода.
 * requestId кладётся в тело вызывающим (поле ChatRequest.requestId —
 * по нему работает POST /api/cancel). Терминальный кадр
 * (final|cancelled|error) завершает чтение с reader.cancel().
 * AbortError не ловится — семантику («Передача остановлена») решает
 * вызывающий; сеть/!ok/нет кадров → { ok: false }. Авто-retry нет.
 */
export async function streamChat(
  request: ChatRequest,
  { signal, onEvent }: StreamChatOptions,
): Promise<StreamChatResult> {
  // Токен берётся в момент открытия потока (как настройки в send()):
  // без него intercept сервера вернёт 401 → ok:false → фолбэк
  const token = useAuthStore.getState().getToken();
  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'X-Auth-Token': token } : {}),
    },
    body: JSON.stringify(request),
    signal,
  });

  if (!res.ok || !res.body) {
    return { ok: false, frames: 0 };
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let frames = 0;
  let finished = false;

  const consumeFrame = (frameText: string): void => {
    frames += 1;
    const event = parseFrame(frameText);
    if (event) onEvent(event);
    if (event && isTerminalStreamEvent(event)) finished = true;
  };

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // Кадры разделены пустой строкой; терминальный — прекращаем чтение
    const { frames: ready, rest } = extractFrames(buffer);
    buffer = rest;
    for (const frameText of ready) {
      consumeFrame(frameText);
      if (finished) break;
    }
    if (finished) {
      await reader.cancel().catch(() => {});
      return { ok: true, frames };
    }
  }

  // Хвост без завершающего \n\n: по спецификации SSE доставляется в конце потока
  buffer += decoder.decode();
  if (buffer.trim()) consumeFrame(buffer);

  if (frames === 0) return { ok: false, frames: 0 };
  return { ok: true, frames };
}

/** Идентификатор запроса: по нему сервер находит и прерывает выполнение
    /api/chat/stream при нажатии «Стоп» (/api/cancel). */
export function makeRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // Запасной вариант для окружений без crypto.randomUUID (не-secure context)
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}
