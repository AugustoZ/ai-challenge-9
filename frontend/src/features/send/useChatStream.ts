/* ============================================================
   Конвейер отправки — порт static/script.js L2495–2792:
   makeRequestId (api/sse.ts), sendChatStream (L2620–2731),
   sendChatFallback (L2565–2589), stopChat (L2521–2536),
   applyChatResponse (L2541–2563) + addBotMessage (L252–317)
   и обработчик submit (L2735–2792). Волна 6: зеркальная ветка
   передачи (runMirrorExchange L2471–2493, sendTwinStream
   L2384–2465, sendTwinBlocking L2363–2379) и обновления
   loadBranches/loadSessions/фактов после final (L2549–2562).

   Правила (spec «SSE-конвейер», «Правила эффектов», tasks.md
   «Подводные камни»):
   - AbortController и requestId живут в useRef — это identity
     запроса, а не UI-состояние (ванильный activeRequest L2512);
   - стримы открываются только из обработчика клавиши «Передать» —
     mount-эффектов в хуке нет вовсе (StrictMode удваивает эффекты);
   - все записи в сторы гвардятся requestId: событие устаревшего
     запроса (после «Стоп» или новой передачи) не пишет ничего;
     twin-обмены гвардятся своим twinId+номером обмена (TwinPane);
   - AbortError оседает как «Передача остановлена», а не как ошибка;
   - фолбэк: поток недоступен или ноль кадров → блокирующий
     POST /api/chat (L2565–2589) — и для основного, и для twin-запроса.
   ============================================================ */

import { useCallback, useRef, useState } from 'react';
import { ApiError, postJson } from '../../api/client';
import { makeRequestId, streamChat } from '../../api/sse';
import type { ChatRequest, ChatResponse, StreamEvent } from '../../api/types';
import { nowStamp } from '../../lib/format';
import { loadBranches } from '../branches/branchesApi';
import {
  buildTwinRequest,
  MIRROR_SIDES,
  MIRROR_STRATEGY_LABELS,
  twinErrorDecision,
  twinStreamDecision,
  type TwinOutcome,
  type TwinSide,
} from '../mirror/twin';
import { getTwin, type TwinHandle } from '../mirror/twinRegistry';
import { loadSessions } from '../sessions/loadSessions';
import { useBranchesStore } from '../../stores/branches';
import { useMemoryStore } from '../../stores/memory';
import { useSessionStore } from '../../stores/session';
import { useSettingsStore, type MirrorStrategy } from '../../stores/settings';
import {
  buildChatRequest,
  busyDirective,
  mirrorBusyDirective,
  mirrorResultDirective,
  needsFallback,
  streamAbortDecision,
  type Directive,
} from './request';

/** Ванильный activeRequest (script.js L2510–2512): identity полёта. */
interface ActiveRequest {
  controller: AbortController;
  requestId: string;
  /** Запись-заглушка с лампами (showTyping) — hideTyping убирает её по id. */
  placeholderId: string;
}

const IDLE_DIRECTIVE: Directive = { kind: 'ready', text: 'Готов к передаче' };

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

function isAbortError(err: unknown): boolean {
  return err instanceof Error && err.name === 'AbortError';
}

export function useChatStream() {
  const activeRef = useRef<ActiveRequest | null>(null);
  /** Зеркало sendBtn.disabled из ванили — синхронный доступ для guard'ов. */
  const inFlightRef = useRef(false);
  const [inFlight, setInFlight] = useState(false);
  /** Клавиша «Стоп» видна, только пока основной запрос в полёте
      (ваниль L2510–2519: активный activeRequest; в зеркале его нет). */
  const [stoppable, setStoppable] = useState(false);
  const [directive, setDirective] = useState<Directive>(IDLE_DIRECTIVE);

  /* ---------- Клавиша «Стоп» (script.js L2521–2536) ---------- */

  const stop = useCallback(() => {
    const active = activeRef.current;
    if (!active) return;
    activeRef.current = null;
    /* Обрываем чтение потока и отдельно просим сервер убить вызов LLM.
       Ответ /api/cancel не важен — ошибки игнорируем (L2527–2532). */
    active.controller.abort();
    postJson('cancel', { requestId: active.requestId }).catch(() => {});
    /* hideTyping (L2533) + «Остановлено пользователем» (L2534) +
       директива отказа (L2535). inFlight не трогаем: клавиша «Стоп»
       и блокировка ввода снимутся в finally передачи, как в ваниле. */
    const session = useSessionStore.getState();
    session.removeEntry(active.placeholderId);
    session.appendEntry({
      kind: 'system',
      text: 'Остановлено пользователем',
      meta: { time: nowStamp() },
    });
    setDirective({ kind: 'fault', text: 'Передача остановлена' });
  }, []);

  /* ---------- Клавиша «Передать» (script.js L2735–2792) ---------- */

  const send = useCallback(async (rawText: string) => {
    const message = rawText.trim();
    /* Ваниль L2739: if (!message || sendBtn.disabled) return; */
    if (!message || inFlightRef.current) return;

    /* Настройки читаются в момент отправки (readSettings, L2748) */
    const settings = useSettingsStore.getState();
    const { sessionId } = useSessionStore.getState();
    const { activeBranchId } = useBranchesStore.getState();

    const requestId = makeRequestId();
    const controller = new AbortController();

    /* Запрос в журнал (addMessage, L2743) и заглушка с лампами
       (showTyping, L2747): три мигающие лампы + строка состояния,
       живая трасса агента растёт в ней по событиям. */
    const store = useSessionStore.getState();
    store.appendEntry({
      kind: 'user',
      text: message,
      meta: { time: nowStamp() },
    });
    const placeholderId = store.appendEntry({
      kind: 'bot',
      text: '',
      meta: { time: nowStamp() },
      typing: { statusText: '' },
      events: [],
    });

    activeRef.current = { controller, requestId, placeholderId };
    inFlightRef.current = true;
    setInFlight(true);
    setDirective(settings.mirrorMode ? mirrorBusyDirective() : busyDirective(settings.judge)); // L2750–2752

    const request = buildChatRequest(
      settings,
      { sessionId, activeBranchId },
      message,
    );

    /* Гвардия записей: устаревший запрос (стоп или новая передача)
       не пишет ни в сторы, ни в директиву. */
    const isCurrent = () => activeRef.current?.requestId === requestId;
    const guarded = (fn: () => void) => {
      if (isCurrent()) fn();
    };

    /* Строка состояния потока под лампами (setStreamStatus, L607–610) */
    const setStreamStatus = (text: string) =>
      guarded(() => {
        useSessionStore.getState().patchEntry(placeholderId, {
          typing: { statusText: text },
        });
      });

    /* ---------- Зеркальный обмен (script.js L2363–2493) ----------
       Состояние близнецов — в TwinPane (реестр по twinId); отсюда
       только обмен и оседание результата в свою колонку. Отказ
       стороны наружу не выходит: вторая рендерится нормально. */

    const settleTwin = (twin: TwinHandle, seq: number, outcome: TwinOutcome): TwinOutcome => {
      if (outcome.fault) twin.applyFault(seq, outcome.message ?? 'Произошла ошибка');
      return outcome;
    };

    /* Резервный путь twin-стороны: блокирующий /api/chat (L2363–2379) */
    const sendTwinBlocking = async (
      twin: TwinHandle,
      seq: number,
      requestData: ChatRequest,
      strategy: MirrorStrategy,
    ): Promise<TwinOutcome> => {
      try {
        const data = await postJson<ChatResponse>('chat', requestData);
        twin.applyResponse(seq, data, strategy);
        return { fault: false };
      } catch (err) {
        if (err instanceof ApiError) {
          /* Ванилла L2370–2372: тело ответа разобрано — отказ показывает reply */
          let reply: string | null = null;
          try {
            const parsed: unknown = JSON.parse(err.bodyText);
            if (
              parsed !== null &&
              typeof parsed === 'object' &&
              typeof (parsed as { reply?: unknown }).reply === 'string'
            ) {
              reply = (parsed as { reply: string }).reply;
            }
          } catch {
            /* тело не JSON — текста reply нет */
          }
          return settleTwin(twin, seq, { fault: true, message: reply || `Сервер ответил ${err.status}` });
        }
        return settleTwin(twin, seq, { fault: true, message: 'Нет связи с сервером: ' + errorMessage(err) });
      }
    };

    /* Один twin-запрос: тот же /api/chat/stream, итог — в свою колонку
       (sendTwinStream L2384–2465). AbortController'а нет: «Стоп» в зеркале
       относится к основному столбцу, которого во время сравнения нет. */
    const sendTwinStream = async (
      twin: TwinHandle,
      seq: number,
      requestData: ChatRequest,
      strategy: MirrorStrategy,
    ): Promise<TwinOutcome> => {
      const strategyLabel = MIRROR_STRATEGY_LABELS[strategy];
      let gotEvents = false;
      let outcome: TwinOutcome | null = null;

      const handleEvent = (event: StreamEvent): void => {
        gotEvents = true;
        if (event.type === 'status') {
          /* Статус twin-потока — в строку состояния заглушки (L2407–2409) */
          setStreamStatus(`${strategyLabel}: ${typeof event.text === 'string' ? event.text : ''}`);
          return;
        }
        if (event.type === 'final') {
          if (event.response && typeof event.response === 'object') {
            /* Итог оседает в колонку немедленно (applyTwinResponse, L2412) */
            twin.applyResponse(seq, event.response, strategy);
            outcome = { fault: false };
          } else {
            outcome = { fault: true, message: 'Сервер прислал некорректный итоговый ответ' };
          }
          return;
        }
        if (event.type === 'cancelled') {
          outcome = { fault: true, message: 'Передача остановлена' };
          return;
        }
        if (event.type === 'error') {
          outcome = { fault: true, message: event.message || 'Произошла ошибка' };
        }
      };

      try {
        const result = await streamChat(requestData, { onEvent: handleEvent });
        if (outcome) return settleTwin(twin, seq, outcome); // итог оседает (L2453)
        const decision = twinStreamDecision(result, outcome !== null, gotEvents);
        if (decision === 'blocking') return await sendTwinBlocking(twin, seq, requestData, strategy);
        return settleTwin(twin, seq, {
          fault: true,
          message: 'Сервер завершил передачу без итогового ответа', // L2455
        });
      } catch (err) {
        const decision = twinErrorDecision(isAbortError(err), gotEvents);
        if (decision === 'stop') {
          return settleTwin(twin, seq, { fault: true, message: 'Передача остановлена' }); // L2457–2458
        }
        if (decision === 'fault') {
          return settleTwin(twin, seq, {
            fault: true,
            message: 'Связь с сервером прервалась во время передачи: ' + errorMessage(err), // L2460–2461
          });
        }
        return await sendTwinBlocking(twin, seq, requestData, strategy); // L2462–2463
      }
    };

    /* Пара параллельных передач (runMirrorExchange L2471–2493): оба fetch
       уходят одновременно; каждый twin несёт свой sessionId и стратегию
       своей колонки, прочитанную в момент отправки. */
    const runMirrorExchange = async (baseRequest: ChatRequest): Promise<void> => {
      const attempts = MIRROR_SIDES.map(async (side: TwinSide): Promise<TwinOutcome> => {
        const twin = getTwin(side);
        if (!twin) return { fault: false }; // колонка демонтирована (зеркало выключили в полёте) — обмен в никуда
        const strategy = side === 'full'
          ? settings.mirrorStrategyFull
          : settings.mirrorStrategyComp;
        const seq = twin.beginExchange();
        const twinRequest = buildTwinRequest(baseRequest, twin.getSessionId(), strategy);
        return sendTwinStream(
          twin,
          seq,
          { ...twinRequest, requestId: makeRequestId() },
          strategy,
        );
      });

      const results = await Promise.all(attempts);
      const anyOk = results.some((r) => !r.fault);
      setDirective(mirrorResultDirective(anyOk)); // L2490
      void loadSessions(); // L2491
    };

    /* Готовый ChatResponse — в журнал (applyChatResponse, L2541–2563).
       hideTyping убирает заглушку, addBotMessage дописывает свежую
       запись ответа с показаниями, трассой и судьёй (L252–317). */
    const applyChatResponse = (data: ChatResponse) =>
      guarded(() => {
        const session = useSessionStore.getState();
        session.removeEntry(placeholderId); // hideTyping, L2542
        session.appendEntry({
          kind: 'bot',
          text: data.reply,
          meta: {
            time: nowStamp(),
            tokenData: {
              elapsedMs: data.elapsedMs,
              promptTokens: data.promptTokens,
              completionTokens: data.completionTokens,
              totalTokens: data.totalTokens,
              summaryApplied: data.summaryApplied,
              summaryTokens: data.summaryTokens,
            },
          },
          events:
            Array.isArray(data.events) && data.events.length ? data.events : undefined,
          judge: data.judge ?? undefined,
        });
        /* Учёт топлива (addBotMessage, L306–315): totalTokens != null —
           плюс к счётчику сессии и столбец в историю топливомера */
        session.applyUsage(data);
        /* Сервер подтвердил сессию — запоминаем для следующих запросов
           (L2545–2548); персист настроек выполняет persist-middleware. */
        if (data.sessionId) useSessionStore.getState().setSessionId(data.sessionId);
        /* Факты из ответа (стратегия «Факты») — в панель, если она на виду
           (updateFactsPanel, L2549–2550); видимость решает компонент Facts */
        useSessionStore.getState().setFacts(Array.isArray(data.facts) ? data.facts : []);
        /* Ветки: могла смениться активная. Отзыв по стратегии, а не только
           по branchId — под «Ветками» счётчики чипов пересчитываются каждым
           ходом (L2551–2558); панель сессий устарела (L2560–2562). */
        if (data.branchId != null) {
          useBranchesStore.getState().setActiveBranchId(data.branchId);
        }
        const effectiveSessionId = useSessionStore.getState().sessionId;
        if (data.branchId != null || useSettingsStore.getState().contextStrategy === 'branching') {
          void loadBranches(effectiveSessionId);
        }
        void loadSessions();
        /* Память: агентные инструменты могли читать/писать рабочие заметки —
           кэш стора обновляется тихо (fire-and-forget), стрим не блокируется */
        if (effectiveSessionId) {
          void useMemoryStore.getState().loadWorking(effectiveSessionId);
          void useMemoryStore.getState().loadOverview(effectiveSessionId);
        }
        setDirective({ kind: 'ready', text: 'Передача завершена' }); // L2559
      });

    /* Разбор кадров потока — ванильный handleEvent (L2637–2667).
       gotEvents/sawTerminal — решения об обрыве из хвоста sendChatStream
       (L2712–2730): любые разобранные события и терминальный кадр. */
    let gotEvents = false;
    let sawTerminal = false;
    const handleEvent = (event: StreamEvent) => {
      gotEvents = true;
      if (event.type === 'final' || event.type === 'cancelled' || event.type === 'error') {
        sawTerminal = true;
      }
      guarded(() => {
        switch (event.type) {
          case 'status':
            setStreamStatus(typeof event.text === 'string' ? event.text : '');
            break;
          case 'agent':
            if (event.event) {
              useSessionStore.getState().appendTraceEvent(placeholderId, event.event);
            }
            break;
          case 'final':
            if (event.response && typeof event.response === 'object') {
              applyChatResponse(event.response);
            } else {
              useSessionStore.getState().removeEntry(placeholderId);
              setDirective({ kind: 'fault', text: 'Сервер прислал некорректный итоговый ответ' });
            }
            break;
          case 'cancelled':
            useSessionStore.getState().removeEntry(placeholderId);
            useSessionStore.getState().appendEntry({
              kind: 'system',
              text: 'Остановлено пользователем',
              meta: { time: nowStamp() },
            });
            setDirective({ kind: 'fault', text: 'Передача остановлена' });
            break;
          case 'error':
            useSessionStore.getState().removeEntry(placeholderId);
            setDirective({ kind: 'fault', text: event.message || 'Произошла ошибка' });
            break;
        }
      });
    };

    /* Резервный путь: старый блокирующий запрос (L2565–2589).
       Тело — request без requestId: его в ваниле получает только стрим. */
    const runFallback = async () => {
      try {
        const data = await postJson<ChatResponse>('chat', request, controller.signal);
        applyChatResponse(data);
      } catch (err) {
        if (!isCurrent()) return;
        /* «Стоп» уже показал «Остановлено пользователем» — тихо выходим (L2584–2585) */
        if (isAbortError(err)) return;
        if (err instanceof ApiError) {
          /* Ванилла L2576–2579: тело ответа разобрано, отказ показывает
             reply; без reply — «Произошла ошибка» */
          let reply: string | null = null;
          try {
            const parsed: unknown = JSON.parse(err.bodyText);
            if (
              parsed !== null &&
              typeof parsed === 'object' &&
              typeof (parsed as { reply?: unknown }).reply === 'string'
            ) {
              reply = (parsed as { reply: string }).reply;
            }
          } catch {
            /* тело не JSON — текста reply нет */
          }
          setDirective({ kind: 'fault', text: reply || 'Произошла ошибка' });
        } else {
          useSessionStore.getState().removeEntry(placeholderId); // hideTyping, L2586
          setDirective({
            kind: 'fault',
            text: 'Не удалось связаться с сервером: ' + errorMessage(err),
          });
        }
      }
    };

    try {
      if (settings.mirrorMode) {
        /* Зеркало: основной диалог и его счётчики не затрагиваются
           (L2767–2769); клавиши «Стоп» в это время нет (запрос не открыт) */
        await runMirrorExchange(request);
        return;
      }

      /* Живой поток: requestId кладётся в тело (L2670–2675); клавиша
         «Стоп» видна до конца полёта — и в потоке, и в фолбэке (L2510) */
      setStoppable(true);
      const result = await streamChat(
        { ...request, requestId },
        { signal: controller.signal, onEvent: handleEvent },
      );

      /* Запрос устарел (стоп/новая передача) — ничего не решаем и не пишем */
      if (!isCurrent()) return;

      /* Поток недоступен (!ok — не 2xx, сеть, ноль кадров) — тот же
         запрос уходит по блокирующему пути (L2677–2680, L2712–2715) */
      if (needsFallback(result)) {
        await runFallback();
        return;
      }

      /* Стрим кончился без терминального кадра (L2716–2719): события
         разбирались, но final/cancelled/error не пришёл */
      if (!sawTerminal) {
        useSessionStore.getState().removeEntry(placeholderId);
        setDirective({ kind: 'fault', text: 'Сервер завершил передачу без итогового ответа' });
      }
    } catch (err) {
      /* «Стоп» уже показал «Остановлено пользователем» — тихо выходим
         (L2721–2722); AbortError оседает как «Передача остановлена» */
      if (!isCurrent() || isAbortError(err)) return;

      if (streamAbortDecision(gotEvents) === 'fallback') {
        /* Ни одного события не пришло — уходим в блокирующий запрос (L2726–2729) */
        await runFallback();
        return;
      }
      useSessionStore.getState().removeEntry(placeholderId);
      setDirective({
        kind: 'fault',
        text: 'Связь с сервером прервалась во время передачи: ' + errorMessage(err),
      });
    } finally {
      /* hideTyping (L2786) — идемпотентно: после терминальных кадров
         заглушка уже убрана */
      useSessionStore.getState().removeEntry(placeholderId);
      /* endRequest (L2516–2519, L2787): клавиша «Стоп» скрыта, ввод
          разблокирован; фокус в поле возвращает SendBar */
      activeRef.current = null;
      inFlightRef.current = false;
      setInFlight(false);
      setStoppable(false);
    }
  }, []);

  return { send, stop, inFlight, stoppable, directive, setDirective };
}
