/* Чистый маппинг перечня сессий в строки панели — порт script.js
   L1795–1831 (sessionTimeLabel, заголовок и мета-строка записи)
   и L1811 (валидация записей). Покрыто Vitest (волна 6). */

import type { SessionSummary } from '../../api/types';
import { entriesWord } from '../../lib/format';

/** Время обновления сессии: сегодня — «ЧЧ:ММ», вчера — «вчера»,
    старше — дата «ДД.ММ». Узкая панель не вмещает большего (L1793–1805). */
export function sessionTimeLabel(updatedAt: number): string {
  const ts = Number(updatedAt);
  if (!Number.isFinite(ts)) return '';
  const stamp = new Date(ts);
  const now = new Date();
  const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((startOfDay(now) - startOfDay(stamp)) / (24 * 60 * 60 * 1000));
  if (days <= 0) return stamp.toLocaleTimeString('ru-RU', { hour12: false }).slice(0, 5);
  if (days === 1) return 'вчера';
  return stamp.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
}

/** Заголовок записи: пустой/пробельный — честное «Без сообщений» (L1820–1822). */
export function sessionTitle(summary: SessionSummary): string {
  return typeof summary.title === 'string' && summary.title.trim()
    ? summary.title
    : 'Без сообщений';
}

/** Мета-строка записи: время обновления и число записей через « · » (L1824–1831). */
export function sessionMeta(summary: SessionSummary): string {
  const bits: string[] = [];
  const time = sessionTimeLabel(summary.updatedAt);
  if (time) bits.push(time);
  const count = Number(summary.messageCount);
  if (Number.isFinite(count) && count > 0) bits.push(`${count} ${entriesWord(count)}`);
  return bits.join(' · ');
}

/** Валидные записи перечня: без строкового непустого id — мимо (L1811). */
export function validSessionSummaries(sessions: unknown): SessionSummary[] {
  if (!Array.isArray(sessions)) return [];
  return sessions.filter((s): s is SessionSummary => {
    if (s === null || typeof s !== 'object') return false;
    const id = (s as SessionSummary).id;
    return typeof id === 'string' && id !== '';
  });
}
