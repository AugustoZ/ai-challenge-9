/* Чистые форматтеры, перенесённые из script.js (только не трогающие DOM).
   Локаль «ru-RU» — как в старом интерфейсе; запятая в числах — приборный стиль. */

/** Штамп времени записи журнала «ЧЧ:ММ». */
export function nowStamp(): string {
  return new Date().toLocaleTimeString('ru-RU', { hour12: false }).slice(0, 5);
}

/** Приборное показание времени ответа: «1,23 с». */
export function fmtElapsed(ms: number): string {
  return `${(ms / 1000).toFixed(2).replace('.', ',')} с`;
}

export interface RateSource {
  elapsedMs: number;
  completionTokens: number | null | undefined;
}

/** Скорость генерации «42 т/с»; нет данных — null. */
export function fmtRate(tokenData: RateSource): string | null {
  const seconds = tokenData.elapsedMs / 1000;
  if (!seconds || tokenData.completionTokens == null) return null;
  return `${Math.round(tokenData.completionTokens / seconds)} т/с`;
}

/** Хвосты длинных значений обрезаются: аргументы и тексты приходят от модели
    и могут быть сколь угодно длинными. */
export function truncateText(value: unknown, limit: number): string {
  const str = String(value ?? '');
  return str.length > limit ? `${str.slice(0, limit)}…` : str;
}

/** Русские множественные формы для счётчиков событий трассы. */
export function eventsWord(n: number): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return 'событие';
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'события';
  return 'событий';
}

/** Русские множественные формы для счётчика записей самописца. */
export function entriesWord(n: number): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return 'запись';
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'записи';
  return 'записей';
}

/** Время записи самописца «ЧЧ:ММ:СС». */
export function exchangeTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('ru-RU', { hour12: false });
}

/** Размер тела обмена: «1,2 КБ» или байты. */
export function fmtSize(payload: string): string {
  const kb = payload.length / 1024;
  return kb >= 1 ? `${kb.toFixed(1)} КБ` : `${payload.length} Б`;
}

/** Тела обменов приходят компактными; для чтения разворачиваем в многострочный JSON. */
export function prettyJson(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}
