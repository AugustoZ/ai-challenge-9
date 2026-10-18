/* ============================================================
   Markdown и подсветка кода — порт 1:1 из static/script.js
   L101–117 (renderMarkdown + highlightCode).
   В CDN-версии marked/hljs могли не догрузиться (typeof-проверки);
   при статическом импорте пакеты доступны всегда — проверки сняты.
   Чистая функция без DOM + отдельная пост-подсветка колодцев.
   ============================================================ */

import { marked } from 'marked';
import hljs from 'highlight.js';

/**
 * Ванильный конвейер: marked.parse + срез class="language-…"
 * с открывающего тега — hljs.highlightElement расставит свои классы сам
 * (script.js L101–109). Вторая замена в ваниле дословно меняет
 * `<code>` на `<code>` — исторический no-op, сохранён для паритета.
 */
export function renderMarkdown(text: string): string {
  const html = marked.parse(text, { async: false });
  return html
    .replace(/<pre><code class="language-([^"]+)">/g, '<pre><code>')
    .replace(/<code>/g, '<code>');
}

/**
 * Подсветка кодовых колодцев уже отрисованного HTML (script.js L111–117):
 * трогает только переданный контейнер — вызывать из useEffect.
 */
export function highlightCodeBlocks(container: HTMLElement): void {
  container.querySelectorAll<HTMLElement>('pre code').forEach((block) => {
    hljs.highlightElement(block);
  });
}
