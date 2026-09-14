/* Тесты чипа скачивания (порт script.js L447–459). */

import { describe, expect, it } from 'vitest';
import { downloadChipPath } from './chip';

describe('downloadChipPath', () => {
  it('путь берётся из JSON-аргументов', () => {
    expect(downloadChipPath('{"path":"otchet.txt"}')).toBe('otchet.txt');
  });

  it('битый JSON — чип молча не строится', () => {
    expect(downloadChipPath('не JSON')).toBeNull();
  });

  it('путь отсутствует или не строка — чипа нет', () => {
    expect(downloadChipPath('{}')).toBeNull();
    expect(downloadChipPath('{"path":42}')).toBeNull();
    expect(downloadChipPath('{"path":""}')).toBeNull();
    expect(downloadChipPath('[1,2]')).toBeNull();
  });
});
