/* Тесты markdown-конвейера (порт script.js L101–109). */

import { describe, expect, it } from 'vitest';
import { renderMarkdown } from './markdown';

describe('renderMarkdown', () => {
  it('заголовки и жирный переходят в HTML', () => {
    expect(renderMarkdown('# Дискрет')).toContain('<h1>Дискрет</h1>');
    expect(renderMarkdown('**важно**')).toContain('<strong>важно</strong>');
    expect(renderMarkdown('просто текст')).toContain('<p>просто текст</p>');
  });

  it('ограждённый код: язык срезается — расставит hljs', () => {
    const html = renderMarkdown('```js\nconst a = 1;\n```');
    expect(html).toContain('<pre><code>');
    expect(html).not.toContain('language-js');
    expect(html).toContain('const a = 1;');
  });

  it('спецсимволы текста экранируются', () => {
    expect(renderMarkdown('5 < 6')).toContain('&lt;');
  });

  it('переводы строк абзацев не ломают разметку', () => {
    const html = renderMarkdown('первая\n\nвторая');
    expect(html).toContain('<p>первая</p>');
    expect(html).toContain('<p>вторая</p>');
  });
});
