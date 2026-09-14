/* ============================================================
   Топливомер: чистая математика осей и денег.
   Порт 1:1 из static/script.js L1407–1456 (TOKEN_TARIFF,
   fmtMoney, niceScale).
   ============================================================ */

/* Тариф: ровно $0,00000036 за один токен — запрос и ответ по одной цене */
export const TOKEN_TARIFF = 0.00000036;

/* Деньги: значения копеечные — sig значащих цифр без экспоненты, запятая
   по-русски (например, $0,00212); очень малые значения не уводим в e-нотацию */
export function fmtMoney(value: number, sig = 3): string {
  if (!Number.isFinite(value) || value <= 0) return '$0';
  let str: string;
  if (value >= 1) {
    str = value.toFixed(2);
  } else {
    const digits = Math.min(12, Math.max(0, sig - 1 - Math.floor(Math.log10(value))));
    str = value.toFixed(digits);
  }
  if (str.includes('.')) str = str.replace(/0+$/, '').replace(/\.$/, '');
  return `$${str.replace('.', ',')}`;
}

export interface NiceScale {
  max: number;
  lines: number;
}

/* Ось Y: круглая верхняя граница и 3–4 линии сетки. Пары «шаг/линии»
   подобраны так, чтобы промежуточные подписи тоже были круглыми
   (5542 → 6000 по 2000; 1200 → 2000 по 500) */
export function niceScale(value: number): NiceScale {
  if (!(value > 0)) return { max: 1, lines: 3 };
  const pow = Math.pow(10, Math.floor(Math.log10(value)));
  const norm = value / pow;
  const steps: ReadonlyArray<readonly [number, number]> = [[1, 4], [2, 4], [3, 3], [4, 4], [6, 3], [8, 4], [12, 4]];
  for (const [step, lines] of steps) {
    if (norm <= step) return { max: step * pow, lines };
  }
  return { max: 10 * pow, lines: 5 };
}
