/* Тесты решающей логики клавиши разворота (порт script.js L170–204). */

import { describe, expect, it } from 'vitest';
import { EXPAND_SLACK, isOverflowing } from './expand';

describe('isOverflowing', () => {
  it('запас EXPAND_SLACK гасит дрожание на границе потолка', () => {
    expect(EXPAND_SLACK).toBe(2);
    expect(isOverflowing(240, 240)).toBe(false);
    expect(isOverflowing(240, 242)).toBe(false);
    expect(isOverflowing(240, 243)).toBe(true);
  });

  it('короткий контент клавиши не требует', () => {
    expect(isOverflowing(240, 20)).toBe(false);
  });

  it('длинный контент требует разворота', () => {
    expect(isOverflowing(240, 900)).toBe(true);
  });
});
