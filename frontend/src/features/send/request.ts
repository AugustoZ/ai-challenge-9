/* ============================================================
   Чистая логика конвейера отправки — порт точек принятия решений
   из static/script.js. Пишет в сторы/DOM, только считается —
   покрыто Vitest (task «Волна 5»: сборка запроса, вид директивы,
   решение о фолбэке).
   ============================================================ */

import type { ChatRequest } from '../../api/types';
import type { StatusKind } from '../../components/StatusLine';
import { splitModelValue } from '../settings/models';
import type { SettingsValues } from '../../stores/settings';

/* ---------- Директива состояния (script.js setDirective L93–97) ---------- */

export interface Directive {
  kind: StatusKind;
  text: string;
}

/* Директива на время передачи: строка busy из обработчика submit
   (script.js L2750–2752). Режимов рассуждения нет с волны 1. */
export function busyDirective(judge: boolean): Directive {
  return {
    kind: 'busy',
    text: `Идёт обработка${judge ? ' · проверка судьёй' : ''}`,
  };
}

/* Директива зеркала на время пары передач (script.js L2750–2752). */
export function mirrorBusyDirective(): Directive {
  return {
    kind: 'busy',
    text: 'Идёт сравнение · пара параллельных передач',
  };
}

/* Директива по итогам пары (runMirrorExchange, script.js L2490). */
export function mirrorResultDirective(anyOk: boolean): Directive {
  return anyOk
    ? { kind: 'ready', text: 'Сравнение завершено' }
    : { kind: 'fault', text: 'Обе стороны зеркала отказали' };
}

/* Отображение на StatusLine: поток в полёте — всегда busy, в покое —
   локальное состояние (fault держится до следующей передачи, ready —
   исходное). */
export function statusKindFor(inFlight: boolean, directive: Directive): StatusKind {
  return inFlight ? 'busy' : directive.kind;
}

/* ---------- Сборка ChatRequest (script.js L2748–2781) ---------- */

/** Серверный контекст запроса: сессия и активная ветка. */
export interface SendContext {
  sessionId: string | null;
  activeBranchId: string | null;
}

/** Поля настроек, уходящие в запрос; зеркальные не участвуют (волна 6). */
export type SendSettings = Pick<
  SettingsValues,
  'model' | 'temperature' | 'maxTokens' | 'topP' | 'stop' | 'thinking' | 'judge' | 'contextStrategy' | 'memoryTools' | 'ragEnabled'
>;

/**
 * ChatRequest по ванильному обработчику submit: составное значение селекта
 * «providerId/modelId» разбирается splitModelValue, стоп-фраза обрезается
 * при чтении (readSettings L852) и пустая уходит null, «Ветки» добавляют
 * branchId только при сессии и активной ветке (script.js L2777–2781).
 * Имена полей — зеркало Models.kt:ChatRequest.
 */
export function buildChatRequest(
  settings: SendSettings,
  ctx: SendContext,
  message: string,
): ChatRequest {
  const selected = splitModelValue(settings.model || '');
  /* Память агента всегда идёт явным boolean: сервер трактует
     отсутствие поля как «включено» (spec «Память»). */
  const request: ChatRequest = {
    message,
    provider: selected.provider,
    model: selected.model,
    thinkingEnabled: settings.thinking,
    maxTokens: settings.maxTokens,
    temperature: settings.temperature,
    topP: settings.topP,
    stop: settings.stop.trim() || null,
    sessionId: ctx.sessionId || null,
    judgeEnabled: settings.judge,
    contextStrategy: settings.contextStrategy,
    memoryTools: settings.memoryTools,
    /* RAG всегда идёт явным boolean: сервер включает поиск только при true. */
    ragEnabled: settings.ragEnabled,
  } as ChatRequest & { memoryTools: boolean };
  if (settings.contextStrategy === 'branching' && ctx.sessionId && ctx.activeBranchId) {
    request.branchId = ctx.activeBranchId;
  }
  return request;
}

/* ---------- Решение о блокирующем фолбэке (script.js L2565–2589) ---------- */

/** Результат streamChat: { ok: false } — поток недоступен, тот же запрос
    уходит по старому блокирующему пути (script.js L2677–2680, L2712–2715). */
export function needsFallback(result: { ok: boolean; frames: number }): boolean {
  return !result.ok;
}

/**
 * Обрыв сети во время чтения потока (script.js L2720–2730): если события уже
 * — честный отказ («Связь с сервером прервалась во время передачи»),
 * если ни одного — запрос повторяется блокирующим фолбэком.
 */
export function streamAbortDecision(gotEvents: boolean): 'fault' | 'fallback' {
  return gotEvents ? 'fault' : 'fallback';
}
