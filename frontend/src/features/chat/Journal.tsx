/* ============================================================
   Бортовой журнал — список записей. Порт static/index.html
   L271–281 (#chat.flight-log и приветственная запись — она же
   состояние пустого журнала: «Новый диалог» возвращает её на
   место) и static/style.css L826–845 (.flight-log).
   Презентационная: записи приходят через props.
   ============================================================ */

import { useEffect, useRef } from 'react';
import Entry from './Entry';
import type { JournalEntry } from './Entry';
import entryStyles from './Entry.module.css';
import styles from './Journal.module.css';

export type { JournalEntry };

/* Приветственная запись — из index.html L272–280 (WELCOME_MARKUP, сырой HTML,
   не markdown); после flip первый абзац переписан под единственный агентный
   режим — перечень старых режимов рассуждения больше не существует. */
function WelcomeEntry() {
  return (
    <div className={`${entryStyles.message} ${entryStyles.bot}`}>
      <div className={entryStyles.entry}>
        <div className={entryStyles.entryHead}>
          <span className={entryStyles.entryTag}>Бортовой журнал</span>
          <span className={entryStyles.entrySep}>·</span>
          <span>готов к работе</span>
        </div>
        <div className={`${entryStyles.bubble} ${entryStyles.md}`}>
          <p>
            Обработка запроса — агентная: <strong>Агент</strong> сам разбивает
            задачу на шаги, вызывает инструменты и докладывает ход работы —
            ход цикла виден в трассе под ответом.
          </p>
          <p>
            Модель LLM выбирайте в «Параметрах модели». Запрос — в строку передачи
            внизу: покажу время и расход топлива на каждом ответе, а режим{' '}
            <strong>Судья</strong> добавит под ответом заключение о фактах, качестве,
            скорости и ресурсоёмкости.
          </p>
        </div>
      </div>
    </div>
  );
}

export default function Journal({ entries }: { entries: JournalEntry[] }) {
  const logRef = useRef<HTMLDivElement>(null);
  /* Автоскролл: свежий ответ держим в кадре. Стрим дописывает хвостовую
     запись — ссылка entries меняется на каждом событии SSE, эффект гонит
     ползунок вниз. Пользователь, отмотавший вверх (>120px от низа),
     автоскролл не ловит: читаем, а не догоняем; вернулся к краю — снова. */
  const stickRef = useRef(true);

  useEffect(() => {
    const el = logRef.current;
    if (!el) return;
    const onScroll = () => {
      stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const el = logRef.current;
    if (el && stickRef.current) el.scrollTop = el.scrollHeight;
  }, [entries]);

  return (
    <div className={styles.flightLog} ref={logRef} aria-label="Бортовой журнал">
      {entries.length ? entries.map((entry) => <Entry key={entry.id} entry={entry} />) : <WelcomeEntry />}
    </div>
  );
}
