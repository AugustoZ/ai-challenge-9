/* ============================================================
   Топливомер: расход топлива за сессию — порт 1:1 из
   static/script.js L1596–1687 (buildFuelCell, buildFuelLegend,
   buildFuelChartBlock, renderFuelModal) и static/style.css
   L2222–2402 (.fuel-*). Рисуется внутри Overlay; сводка, оба
   графика и тариф. Данные — живая история текущей сессии.
   ============================================================ */

import type { ReactNode } from 'react';
import { fmtMoney, TOKEN_TARIFF } from './scale';
import {
  axisTicks,
  PRICE_CHART_BOX,
  priceChartModel,
  tokenAxisLabel,
  TOKEN_CHART_BOX,
  tokenChartModel,
} from './chartMath';
import type { TokenRecord } from './chartMath';
import styles from './FuelMeter.module.css';

function FuelCell({ name, value }: { name: string; value: string | number }) {
  return (
    <div className={styles.cell}>
      <span className={styles.cellName}>{name}</span>
      <span className={styles.cellValue}>{value}</span>
    </div>
  );
}

/* Легенда составного столбца: цвета — как направления обмена в самописце */
function FuelLegend() {
  const items = [
    { swatch: styles.swatchPrompt, text: 'Запрос' },
    { swatch: styles.swatchCompletion, text: 'Ответ' },
  ];
  return (
    <div className={styles.legend}>
      {items.map((item) => (
        <span key={item.text} className={styles.legendItem}>
          <span className={`${styles.swatch} ${item.swatch}`} />
          <span>{item.text}</span>
        </span>
      ))}
    </div>
  );
}

function FuelChartBlock({ caption, legend, children }: {
  caption: string;
  legend?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className={styles.chart}>
      <div className={styles.chartHead}>
        <span className={styles.chartCaption}>{caption}</span>
        {legend}
      </div>
      {children}
    </div>
  );
}

/* График расхода: составной столбец на каждый обмен */
function TokenChart({ history }: { history: TokenRecord[] }) {
  const { W, L, R } = TOKEN_CHART_BOX;
  const model = tokenChartModel(history);

  return (
    <svg
      className={styles.chartSvg}
      viewBox={`0 0 ${W} 210`}
      role="img"
      aria-label="Расход токенов по обменам за сессию"
    >
      {axisTicks(model.bottom, model.plotH, model.max, model.lines).map((tick) => (
        <g key={tick.value}>
          <line
            className={styles.grid}
            x1={L}
            y1={tick.y.toFixed(1)}
            x2={W - R}
            y2={tick.y.toFixed(1)}
          />
          <text className={styles.axisLabel} x={L - 6} y={(tick.y + 3).toFixed(1)} textAnchor="end">
            {tokenAxisLabel(tick.value)}
          </text>
        </g>
      ))}
      <line
        className={styles.axisLine}
        x1={L}
        y1={model.bottom}
        x2={W - R}
        y2={model.bottom}
      />

      {model.bars.map((bar, i) => {
        const rec = history[i];
        return (
          <g key={i} className={styles.bar}>
            <title>{`Запрос ${i + 1}: +${rec.promptTokens || 0} / ответ ${rec.completionTokens || 0}`}</title>
            {bar.completionH ? (
              <rect
                className={styles.barCompletion}
                x={bar.x.toFixed(1)}
                y={(model.bottom - bar.promptH - bar.completionH).toFixed(1)}
                width={bar.barW.toFixed(1)}
                height={bar.completionH.toFixed(1)}
              />
            ) : null}
            {bar.promptH ? (
              <rect
                className={styles.barPrompt}
                x={bar.x.toFixed(1)}
                y={(model.bottom - bar.promptH).toFixed(1)}
                width={bar.barW.toFixed(1)}
                height={bar.promptH.toFixed(1)}
              />
            ) : null}
            {bar.tick ? (
              <text
                className={styles.axisLabel}
                x={bar.cx.toFixed(1)}
                y={model.bottom + 14}
                textAnchor="middle"
              >
                {String(bar.label)}
              </text>
            ) : null}
          </g>
        );
      })}
    </svg>
  );
}

/* График цены: накопленная стоимость сессии по обменам */
function PriceChart({ history }: { history: TokenRecord[] }) {
  const { W, L, R } = PRICE_CHART_BOX;
  const model = priceChartModel(history, TOKEN_TARIFF);

  return (
    <svg
      className={styles.chartSvg}
      viewBox={`0 0 ${W} 210`}
      role="img"
      aria-label="Стоимость сессии по обменам"
    >
      {axisTicks(model.bottom, model.plotH, model.max, model.lines).map((tick) => (
        <g key={tick.value}>
          <line
            className={styles.grid}
            x1={L}
            y1={tick.y.toFixed(1)}
            x2={W - R}
            y2={tick.y.toFixed(1)}
          />
          <text className={styles.axisLabel} x={L - 6} y={(tick.y + 3).toFixed(1)} textAnchor="end">
            {fmtMoney(tick.value, 2)}
          </text>
        </g>
      ))}
      <line
        className={styles.axisLine}
        x1={L}
        y1={model.bottom}
        x2={W - R}
        y2={model.bottom}
      />

      <polyline
        className={styles.line}
        points={model.points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')}
      />

      {model.points.map((p, i) => (
        <circle key={i} className={styles.dot} cx={p.x.toFixed(1)} cy={p.y.toFixed(1)} r={3}>
          <title>{`Обмен ${i + 1}: ${fmtMoney(p.cost)} за сессию`}</title>
        </circle>
      ))}
    </svg>
  );
}

/* Содержимое топливомера: сводка, оба графика и тариф */
export default function FuelMeter({ history }: { history: TokenRecord[] }) {
  /* Сводка: расход запроса и ответа, сумма и цена; без обменов — нули */
  const promptSum = history.reduce((acc, rec) => acc + (rec.promptTokens || 0), 0);
  const completionSum = history.reduce((acc, rec) => acc + (rec.completionTokens || 0), 0);
  const totalSum = promptSum + completionSum;

  return (
    <div className={styles.fuelBody}>
      <div className={styles.summary}>
        <FuelCell name="Запрос" value={promptSum} />
        <FuelCell name="Ответ" value={completionSum} />
        <FuelCell name="Сумма" value={totalSum} />
        <FuelCell name="Цена за сессию" value={fmtMoney(totalSum * TOKEN_TARIFF)} />
      </div>

      {!history.length ? (
        <p className={styles.empty}>
          Топливо ещё не расходовалось — задайте вопрос, и здесь появится график расхода
        </p>
      ) : (
        <>
          <FuelChartBlock caption="Расход по обменам" legend={<FuelLegend />}>
            <TokenChart history={history} />
          </FuelChartBlock>
          <FuelChartBlock caption="Стоимость за сессию">
            <PriceChart history={history} />
          </FuelChartBlock>
        </>
      )}

      <p className={styles.tariff}>Тариф: $0,00000036 за 1 токен</p>
    </div>
  );
}
