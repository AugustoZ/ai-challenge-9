/* Клавиша разворота окон ответов — чистая решающая логика.
   Порт 1:1 из static/script.js L170–204 (EXPAND_SLACK, expandOverflow). */

/* Запас против дрожания на границе потолка */
export const EXPAND_SLACK = 2;

/** Переполняет ли контент свёрнутый потолок. В развёрнутом состоянии класс
    снимается на время замера: сверяем всегда с одним и тем же потолком. */
export function isOverflowing(clientHeight: number, scrollHeight: number): boolean {
  return scrollHeight > clientHeight + EXPAND_SLACK;
}
