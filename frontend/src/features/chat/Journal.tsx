/* ============================================================
   Бортовой журнал — список записей. Порт static/index.html
   L271–281 (#chat.flight-log и приветственная запись — она же
   состояние пустого журнала: «Новый диалог» возвращает её на
   место) и static/style.css L826–845 (.flight-log).
   Презентационная: записи приходят через props.
   ============================================================ */

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
  return (
    <div className={styles.flightLog} aria-label="Бортовой журнал">
      {entries.length ? entries.map((entry) => <Entry key={entry.id} entry={entry} />) : <WelcomeEntry />}
    </div>
  );
}
