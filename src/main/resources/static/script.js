/* ============================================================
   ШТУРМАН · логика приборной доски
   ============================================================ */

const chat = document.getElementById('chat');
const form = document.getElementById('chatForm');
const input = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const directive = document.getElementById('status');
const directiveText = document.getElementById('directiveText');
const tokenCounter = document.getElementById('tokenCounter');
const clock = document.getElementById('clock');

/* Панель параметров. Значение каждой шкалы — источник истины;
   скрытый range хранит позицию на дуге (0..100) и обслуживает клавиатуру. */
const stopInput = document.getElementById('stopSeq');
const thinkingToggle = document.getElementById('thinkingToggle');
const judgeToggle = document.getElementById('judgeToggle');
const mirrorToggle = document.getElementById('mirrorToggle');
const modelSelect = document.getElementById('modelSelect');
const resetBtn = document.getElementById('resetBtn');

/* Табло-аннунциаторы способа рассуждения */
const modeGroup = document.getElementById('reasoningMode');
const modeButtons = Array.from(modeGroup.querySelectorAll('.annunciator'));
const reasoningHint = document.getElementById('reasoningHint');
const modeRepeater = document.getElementById('modeRepeater');

/* Табло-аннунциаторы стратегии памяти + панель фактов */
const strategyGroup = document.getElementById('strategyMode');
const strategyButtons = Array.from(strategyGroup.querySelectorAll('.annunciator'));
const strategyHint = document.getElementById('strategyHint');
const factsPanel = document.getElementById('factsPanel');
const factsList = document.getElementById('factsList');

const MODE_LABELS = {
    default: 'По умолчанию',
    direct: 'Прямой ответ',
    step_by_step: 'Пошаговое решение',
    prompt_to_prompt: 'Prompt-to-Prompt',
    team: 'Команда',
    agent: 'Агент',
};

const STRATEGY_LABELS = {
    off: 'Выкл',
    window: 'Окно',
    summary: 'Сводка',
    facts: 'Факты',
    branching: 'Ветки',
};

/* Стратегии, доступные колонкам зеркала: «Ветки» там не участвует */
const MIRROR_STRATEGY_LABELS = {
    off: 'Выкл',
    window: 'Окно',
    summary: 'Сводка',
    facts: 'Факты',
};

const SETTINGS_STORAGE_KEY = 'ai-assistant.settings.v1';
const DEFAULTS = {
    reasoningMode: 'default', temperature: 0.7, maxTokens: 1024, topP: 1.0, stop: '', thinking: true, judge: false,
    contextStrategy: 'off', mirrorMode: false,
    mirrorStrategyFull: 'off', mirrorStrategyComp: 'summary',
};

/* Исходный вид бортового журнала — «Новый диалог» возвращает его на место */
const WELCOME_MARKUP = chat.innerHTML;

let currentMode = DEFAULTS.reasoningMode;
let currentStrategy = DEFAULTS.contextStrategy;
let sessionTotalTokens = 0;
/* Расход топлива по ответам: одна запись {promptTokens, completionTokens,
   totalTokens} на каждый ответ модели — сырьё для топливомера */
let sessionTokenHistory = [];
/* Идентификатор сессии диалога: выдаёт сервер (ChatResponse.sessionId),
   хранится в настройках. Одиночные режимы продолжают диалог, «Команда» и
   Prompt-to-Prompt отвечают null — сессия у них не ведётся. */
let sessionId = null;

/* ---------- Часы полосы состояния ---------- */

function tickClock() {
    clock.textContent = new Date().toLocaleTimeString('ru-RU', { hour12: false });
}

setInterval(tickClock, 1000);
tickClock();

/* ---------- Директива состояния ---------- */

function setDirective(kind, text) {
    directive.dataset.kind = kind;
    directiveText.textContent = text;
    directiveText.title = kind === 'fault' ? text : '';
}

/* ---------- Markdown и подсветка ---------- */

function renderMarkdown(text) {
    if (typeof marked !== 'undefined') {
        const html = marked.parse(text);
        return html
            .replace(/<pre><code class="language-([^"]+)">/g, '<pre><code>')
            .replace(/<code>/g, '<code>');
    }
    return text;
}

function highlightCode(container) {
    if (typeof hljs !== 'undefined') {
        container.querySelectorAll('pre code').forEach((block) => {
            hljs.highlightElement(block);
        });
    }
}

/* ---------- Записи бортового журнала ---------- */

function nowStamp() {
    return new Date().toLocaleTimeString('ru-RU', { hour12: false }).slice(0, 5);
}

/* Приборные показания: время ответа и скорость генерации (токенов в секунду) */
function fmtElapsed(ms) {
    return `${(ms / 1000).toFixed(2).replace('.', ',')} с`;
}

function fmtRate(tokenData) {
    const seconds = tokenData.elapsedMs / 1000;
    if (!seconds || tokenData.completionTokens == null) return null;
    return `${Math.round(tokenData.completionTokens / seconds)} т/с`;
}

function buildEntryHead(tag, parts) {
    const head = document.createElement('div');
    head.className = 'entry-head';

    const tagSpan = document.createElement('span');
    tagSpan.className = 'entry-tag';
    tagSpan.textContent = tag;
    head.appendChild(tagSpan);

    for (const part of parts) {
        const sep = document.createElement('span');
        sep.className = 'entry-sep';
        sep.textContent = '·';
        const val = document.createElement('span');
        if (part && part.fuel) {
            val.className = 'fuel-digits';
            val.textContent = part.text;
        } else {
            val.textContent = part ? part.text : '';
        }
        head.appendChild(sep);
        head.appendChild(val);
    }
    return head;
}

/* Бейдж «Сводка применена»: янтарная пластина — механика сжатия участвовала в обмене */
function buildSummaryBadge() {
    const badge = document.createElement('span');
    badge.className = 'summary-badge';
    badge.textContent = 'Сводка применена';
    return badge;
}

/* ---------- Клавиша разворота окон ответов ---------- */

/* Окна сырых текстов записи (вердикт судьи, payload обменов) обрезаны
    потолком и листаются в узкой полосе. Клавиша «Развернуть» поднимает
    потолок почти на весь экран, «Свернуть» возвращает узкую полосу.
    Клавиша появляется только когда контент действительно переполняет
    колодец; на коротких ответах её нет. */

const EXPAND_SLACK = 2; /* запас против дрожания на границе потолка */
let expandSeq = 0;      /* счётчик id колодцев для aria-controls */

/* Переполняет ли контент свёрнутый потолок. В развёрнутом состоянии класс
    снимается на время замера: сверяем всегда с одним и тем же потолком. */
function expandOverflow(box) {
    const wasExpanded = box.classList.contains('is-expanded');
    if (wasExpanded) box.classList.remove('is-expanded');
    const overflowing = box.scrollHeight > box.clientHeight + EXPAND_SLACK;
    if (wasExpanded) box.classList.add('is-expanded');
    return overflowing;
}

/* Пересчёт клавиши по фактическому переполнению. Пока колодец спрятан
    (details закрыт, вкладка не построена) — замер бессмыслен, ничего
    не трогаем; ResizeObserver вызовет снова при раскрытии и ресайзе. */
function syncExpandToggle(box, btn) {
    if (!box.isConnected || box.clientHeight === 0) return;
    if (!expandOverflow(box)) {
        box.classList.remove('is-expanded');
        btn.hidden = true;
        btn.setAttribute('aria-expanded', 'false');
        btn.textContent = 'Развернуть';
        return;
    }
    btn.hidden = false;
}

/* Клавиша-плашка в духе бейджа «Сводка применена»: настоящая кнопка,
    состояние — в aria-expanded, управляется с клавиатуры. Наблюдение
    за размером колодца (ResizeObserver) пересчитывает потребность в
    развороте: закрытие/раскрытие details, шрифты, ресайз окна и любые
    дописывания контента (в том числе после завершения стрима). */
function buildExpandToggle(box) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'expand-toggle';
    btn.hidden = true;
    btn.textContent = 'Развернуть';
    if (!box.id) box.id = `expandable-${++expandSeq}`;
    btn.setAttribute('aria-controls', box.id);
    btn.setAttribute('aria-expanded', 'false');
    btn.addEventListener('click', () => {
        const expanded = box.classList.toggle('is-expanded');
        btn.setAttribute('aria-expanded', String(expanded));
        btn.textContent = expanded ? 'Свернуть' : 'Развернуть';
    });

    box.classList.add('expandable');
    const observer = new ResizeObserver(() => syncExpandToggle(box, btn));
    observer.observe(box);
    return btn;
}

function addMessage(text) {
    const messageDiv = document.createElement('div');
    messageDiv.className = 'message user';

    const entry = document.createElement('div');
    entry.className = 'entry';

    entry.appendChild(buildEntryHead('Запрос', [{ text: nowStamp() }]));

    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text;

    entry.appendChild(bubble);
    messageDiv.appendChild(entry);
    chat.appendChild(messageDiv);
    chat.scrollTop = chat.scrollHeight;
    return messageDiv;
}

function addBotMessage(text, tokenData, exchanges = []) {
    const messageDiv = document.createElement('div');
    messageDiv.className = 'message bot';

    const entry = document.createElement('div');
    entry.className = 'entry';

    const parts = [{ text: MODE_LABELS[currentMode] || currentMode }, { text: nowStamp() }];
    if (tokenData && tokenData.elapsedMs != null) {
        const bits = [fmtElapsed(tokenData.elapsedMs)];
        const rate = fmtRate(tokenData);
        if (rate) bits.push(rate);
        parts.push({ fuel: true, text: bits.join(' · ') });
    }
    if (tokenData && tokenData.totalTokens != null) {
        /* Топливо с расшифровкой: токены запроса + токены ответа = сумма;
           применённая сводка сжатия добавляет свою статью расхода */
        let fuelText = `Топливо: запрос ${tokenData.promptTokens ?? 0} + ответ ${tokenData.completionTokens ?? 0} = ${tokenData.totalTokens}`;
        if (tokenData.summaryApplied && tokenData.summaryTokens != null) {
            fuelText += ` (+сводка: ${tokenData.summaryTokens})`;
        }
        parts.push({ fuel: true, text: fuelText });
    }
    entry.appendChild(buildEntryHead('Ответ', parts));

    if (tokenData && tokenData.summaryApplied) {
        entry.appendChild(buildSummaryBadge());
    }

    const bubble = document.createElement('div');
    bubble.className = 'bubble md';
    bubble.innerHTML = renderMarkdown(text);
    highlightCode(bubble);

    entry.appendChild(bubble);

    /* Протокол агентного цикла — часть ответа: идёт сразу под ним,
       до заключения судьи и сырого обмена */
    if (tokenData && Array.isArray(tokenData.events) && tokenData.events.length) {
        entry.appendChild(buildAgentTrace(tokenData.events));
    }

    if (tokenData && tokenData.judge) {
        entry.appendChild(buildJudgeReport(tokenData.judge));
    }

    if (exchanges.length) {
        entry.appendChild(buildRawExchange(exchanges));
    }

    messageDiv.appendChild(entry);
    chat.appendChild(messageDiv);
    chat.scrollTop = chat.scrollHeight;

    if (tokenData && tokenData.totalTokens != null) {
        sessionTotalTokens += tokenData.totalTokens;
        /* Запись в историю топливомера: один обмен = один столбец графика */
        sessionTokenHistory.push({
            promptTokens: tokenData.promptTokens ?? 0,
            completionTokens: tokenData.completionTokens ?? 0,
            totalTokens: tokenData.totalTokens,
        });
        updateTokenCounter();
    }
    return messageDiv;
}

/* Общий счётчик топлива за сессию */
function updateTokenCounter() {
    tokenCounter.textContent = `Топливо за сессию ${sessionTotalTokens}`;
    tokenCounter.title = 'Сумма токенов (запрос + ответ) за все ответы сессии · Нажмите — график расхода за сессию';
    tokenCounter.classList.remove('hidden');
}

/* Полный сброс топливомера: новая сессия начинается с чистого табло */
function resetSessionFuel() {
    sessionTotalTokens = 0;
    sessionTokenHistory = [];
    tokenCounter.classList.add('hidden');
}

/* Служебная запись журнала: уведомление без ответа модели — отмена и т.п. */
function addSystemMessage(text) {
    const messageDiv = document.createElement('div');
    messageDiv.className = 'message bot';

    const entry = document.createElement('div');
    entry.className = 'entry';

    entry.appendChild(buildEntryHead('Служебное', [{ text: nowStamp() }]));

    const bubble = document.createElement('div');
    bubble.className = 'bubble system';
    bubble.textContent = text;

    entry.appendChild(bubble);
    messageDiv.appendChild(entry);
    chat.appendChild(messageDiv);
    chat.scrollTop = chat.scrollHeight;
    return messageDiv;
}

/* ---------- Заключение судьи ---------- */

/* Оценка судьи: 7..10 — показание в допуске (зелёный), 4..6 — на грани (янтарь),
   1..3 — брак (красный). Роли цветов — по DESIGN.md. */
function judgeScoreClass(score) {
    if (!Number.isFinite(score)) return '';
    if (score >= 7) return ' ok';
    if (score >= 4) return ' mid';
    return ' low';
}

function buildJudgeReport(judge) {
    const wrap = document.createElement('details');
    wrap.className = 'judge-report';

    const summary = document.createElement('summary');
    summary.textContent = Number.isFinite(judge.overall)
        ? `Заключение судьи · ${judge.overall}/10`
        : 'Заключение судьи · без итоговой оценки';
    wrap.appendChild(summary);

    const body = document.createElement('div');
    body.className = 'judge-body';

    const criteria = [
        ['facts', 'Факты'],
        ['quality', 'Качество'],
        ['speed', 'Скорость'],
        ['efficiency', 'Ресурсоёмкость'],
    ];
    for (const [key, label] of criteria) {
        const criterion = judge[key];
        if (!criterion || (criterion.score == null && !criterion.comment)) continue;

        const row = document.createElement('div');
        row.className = 'judge-row';

        const name = document.createElement('span');
        name.className = 'judge-name';
        name.textContent = label;

        const score = document.createElement('span');
        score.className = 'judge-score' + judgeScoreClass(criterion.score);
        score.textContent = criterion.score != null ? `${criterion.score}/10` : '—';

        row.appendChild(name);
        row.appendChild(score);

        if (criterion.comment) {
            const comment = document.createElement('p');
            comment.className = 'judge-comment';
            comment.textContent = criterion.comment;
            row.appendChild(comment);
        }
        body.appendChild(row);
    }

    if (judge.verdict) {
        const verdict = document.createElement('p');
        verdict.className = 'judge-verdict';
        verdict.textContent = judge.verdict;
        body.appendChild(verdict);
    }

    if (judge.raw && !judge.facts && !judge.quality && !judge.speed && !judge.efficiency) {
        const raw = document.createElement('pre');
        raw.className = 'judge-raw';
        raw.textContent = judge.raw;
        body.appendChild(buildExpandToggle(raw));
        body.appendChild(raw);
    }

    wrap.appendChild(body);
    return wrap;
}

/* ---------- Ход работы агента ---------- */

/* Хвосты длинных значений обрезаются: аргументы и тексты приходят от модели
   и могут быть сколь угодно длинными. */
function truncateText(value, limit) {
    const str = String(value ?? '');
    return str.length > limit ? `${str.slice(0, limit)}…` : str;
}

function eventsWord(n) {
    const mod10 = n % 10;
    const mod100 = n % 100;
    if (mod10 === 1 && mod100 !== 11) return 'событие';
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'события';
    return 'событий';
}

/* Ссылка-чип «скачать <файл>» для tool_call инструмента write_file.
   Путь берётся из JSON-аргументов (path); если аргументы не распарсились
   или path отсутствует — чип молча не строится. Имя файла — ненадёжный
   ввод от LLM, поэтому и оно рендерится через textContent, без innerHTML. */
function buildDownloadChip(argumentsJson) {
    let path;
    try {
        const args = JSON.parse(argumentsJson);
        if (typeof args?.path !== 'string' || !args.path) return null;
        path = args.path;
    } catch {
        return null;
    }
    const link = document.createElement('a');
    link.className = 'dl-chip';
    link.href = '/api/files/' + encodeURIComponent(path);
    link.setAttribute('download', '');
    link.target = '_blank';
    link.rel = 'noopener';
    link.textContent = `скачать ${path}`;
    return link;
}

/* Строка протокола для одного события AgentLoop. Аргументы инструментов
   и промежуточные тексты — ненадёжный ввод от LLM, поэтому всё рендерится
   через textContent, без innerHTML. Общая для итоговой трассы и живой. */
function buildAgentEventRow(ev) {
    if (!ev || typeof ev !== 'object') return null;
    const step = ev.step != null ? ev.step : '—';

    const row = document.createElement('div');
    row.className = 'agent-event';

    const label = document.createElement('span');
    label.className = 'agent-step';
    label.textContent = `Шаг ${step}`;
    row.appendChild(label);

    const text = (value) => {
        const span = document.createElement('span');
        span.textContent = value;
        row.appendChild(span);
    };
    const code = (value) => {
        const el = document.createElement('code');
        el.className = 'agent-args';
        el.textContent = value;
        row.appendChild(el);
    };
    const flag = (ok) => {
        const el = document.createElement('span');
        el.className = 'agent-flag' + (ok ? ' ok' : ' fault');
        el.textContent = ok ? '✓' : '✗';
        row.appendChild(el);
    };

    switch (ev.type) {
        case 'step_started':
            text('— обдумываю задачу');
            break;
        case 'tool_call':
            text('— вызов инструмента: ');
            code(`${ev.tool ?? '?'}(${truncateText(ev.arguments ?? '', 200)})`);
            /* Файл, созданный write_file, — чип-ссылка на скачивание рядом строкой вызова. */
            if (ev.tool === 'write_file') {
                const chip = buildDownloadChip(ev.arguments);
                if (chip) row.appendChild(chip);
            }
            break;
        case 'tool_result':
            text(`— результат ${ev.tool ?? '?'}: `);
            flag(ev.ok);
            text(truncateText(ev.content ?? '', 300));
            if (ev.ok === false) row.classList.add('fault');
            break;
        case 'assistant_message':
            text(`— промежуточный ответ: ${truncateText(ev.text ?? '', 300)}`);
            break;
        case 'final':
            text(`— итог (${ev.reason ?? 'без причины'}): ${truncateText(ev.text ?? '', 300)}`);
            row.classList.add('final');
            break;
        default:
            text(`— событие ${ev.type ?? 'неизвестного типа'}`);
    }
    return row;
}

/* Протокол агентного цикла под ответом: по строке на событие AgentLoop.
   Для живой трассы во время потока строки строит та же buildAgentEventRow. */
function buildAgentTrace(events) {
    const wrap = document.createElement('details');
    wrap.className = 'agent-trace';

    const summary = document.createElement('summary');
    summary.textContent = `Ход работы агента · ${events.length} ${eventsWord(events.length)}`;
    wrap.appendChild(summary);

    const body = document.createElement('div');
    body.className = 'agent-trace-body';

    for (const ev of events) {
        const row = buildAgentEventRow(ev);
        if (row) body.appendChild(row);
    }

    wrap.appendChild(body);
    return wrap;
}

/* Живая трасса на время потока: та же структура .agent-trace, но раскрыта
   и растёт по строке на каждое приходящее событие агента. */
function createLiveTrace() {
    const wrap = document.createElement('details');
    wrap.className = 'agent-trace live';
    wrap.open = true;

    const summary = document.createElement('summary');
    summary.textContent = 'Ход работы агента · идёт работа';
    wrap.appendChild(summary);

    const body = document.createElement('div');
    body.className = 'agent-trace-body';
    wrap.appendChild(body);

    return { wrap, summary, body, count: 0 };
}

function appendLiveAgentEvent(trace, ev) {
    const row = buildAgentEventRow(ev);
    if (!row) return;
    trace.body.appendChild(row);
    trace.count += 1;
    trace.summary.textContent = `Ход работы агента · ${trace.count} ${eventsWord(trace.count)}`;
    chat.scrollTop = chat.scrollHeight;
}

/* ---------- Индикатор обработки: лампы ---------- */

function showTyping() {
    const wrapper = document.createElement('div');
    wrapper.className = 'message bot';
    wrapper.id = 'typing';
    wrapper.innerHTML = `
        <div class="entry">
            <div class="typing" role="status" aria-label="Идёт обработка">
                <span></span><span></span><span></span>
            </div>
            <p class="typing-status"></p>
        </div>
    `;
    chat.appendChild(wrapper);
    chat.scrollTop = chat.scrollHeight;
}

function hideTyping() {
    document.getElementById('typing')?.remove();
}

/* Строка состояния потока под лампами: ненавязчивое «что происходит» */
function setStreamStatus(text) {
    const el = document.querySelector('#typing .typing-status');
    if (el) el.textContent = text;
}

/* ---------- Приборы: круглые шкалы ---------- */

function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
}

/* Дуга шкалы: 225° — нижний левый упор (7:30), 270° по часовой до 4:30.
   Отсчёт углов — «часовой»: 0° = 12:00, по часовой стрелке. */
const GAUGE_START = 225;
const GAUGE_SWEEP = 270;

function fmtFuel(v) {
    if (v >= 1e6) return (v / 1e6).toFixed(2).replace(/\.?0+$/, '') + 'М';
    if (v >= 1e4) return (v / 1e3).toFixed(1).replace(/\.0$/, '') + 'К';
    return String(Math.round(v));
}

/* Топливо — логарифмическая шкала: диапазон 200..1 024 000 токенов
   линеен в лог-координате, иначе вся полезная зона сжалась бы в упор. */
const DIALS = {
    temperature: {
        min: 0, max: 2, step: 0.01, log: false, fmt: v => v.toFixed(2),
        zones: [[0, 0.25, 'red'], [0.26, 0.69, 'amber'], [0.7, 1.3, 'green'], [1.31, 1.65, 'amber'], [1.66, 2, 'red']],
    },
    maxTokens: {
        min: 200, max: 1024000, step: 1, log: true, fmt: fmtFuel,
        zones: [[200, 500, 'red'], [501, 800, 'amber'], [801, 99999, 'green'], [100000, 499999, 'amber'], [500000, 1024000, 'red']],
    },
    topP: {
        min: 0.01, max: 1, step: 0.01, log: false, fmt: v => v.toFixed(2),
        zones: [[0.01, 0.25, 'red'], [0.26, 0.5, 'amber'], [0.51, 1, 'green']],
    },
};

/* Зона значения — по ТЗ: границы включительны, соседняя зона начинается
   через шаг, поэтому попадание проверяется точным диапазоном */
function zoneLevel(cfg, value) {
    const v = clamp(value, cfg.min, cfg.max);
    for (const [from, to, level] of cfg.zones) {
        if (v >= from && v <= to) return level;
    }
    return 'green';
}

function valueToT(cfg, value) {
    const v = clamp(value, cfg.min, cfg.max);
    return cfg.log ? Math.log(v / cfg.min) / Math.log(cfg.max / cfg.min) : (v - cfg.min) / (cfg.max - cfg.min);
}

function tToValue(cfg, t) {
    const tt = clamp(t, 0, 1);
    const raw = cfg.log
        ? cfg.min * Math.pow(cfg.max / cfg.min, tt)
        : cfg.min + tt * (cfg.max - cfg.min);
    const snapped = cfg.log
        ? Math.max(cfg.min, Math.round(raw / cfg.step) * cfg.step)
        : Number((cfg.min + Math.round((raw - cfg.min) / cfg.step) * cfg.step).toFixed(4));
    return clamp(snapped, cfg.min, cfg.max);
}

function polar(angleDeg, r) {
    const a = (angleDeg * Math.PI) / 180;
    return [50 + r * Math.sin(a), 50 - r * Math.cos(a)];
}

function arcBetween(a0, a1, r) {
    const [x1, y1] = polar(a0, r);
    const [x2, y2] = polar(a1, r);
    const large = ((a1 - a0 + 360) % 360) > 180 ? 1 : 0;
    return `M ${x1.toFixed(2)} ${y1.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${x2.toFixed(2)} ${y2.toFixed(2)}`;
}

function arcPath(angleDeg) {
    return arcBetween(GAUGE_START, angleDeg, 41);
}

function initDial(name) {
    const cfg = DIALS[name];
    const dial = document.querySelector(`.dial[data-setting="${name}"]`);
    const input = dial.querySelector('.dial-kbd');
    const valueEl = dial.querySelector('.dial-value');
    const ticks = dial.querySelector('.dial-ticks');
    const arc = dial.querySelector('.dial-arc');
    const needle = dial.querySelector('.dial-needle');
    const ctrl = { name, cfg, dial, input, valueEl, arc, needle, value: cfg.min };

    /* Тики шкалы: 5 крупных с промежуточными мелкими по всей дуге */
    const NS = 'http://www.w3.org/2000/svg';
    for (let i = 0; i <= 20; i++) {
        const major = i % 5 === 0;
        const angle = GAUGE_START + (GAUGE_SWEEP * i) / 20;
        const [x1, y1] = polar(angle, major ? 34.5 : 37.5);
        const [x2, y2] = polar(angle, 41.5);
        const line = document.createElementNS(NS, 'line');
        line.setAttribute('x1', x1.toFixed(2));
        line.setAttribute('y1', y1.toFixed(2));
        line.setAttribute('x2', x2.toFixed(2));
        line.setAttribute('y2', y2.toFixed(2));
        if (major) line.setAttribute('class', 'major');
        ticks.appendChild(line);
    }

    /* Зоны допуска на лимбе: разметка сплошная (стык — начало следующей зоны)
       и приглушённая — цветом зоны говорит только активное показание */
    cfg.zones.forEach(([from, , level], i) => {
        const zTo = i < cfg.zones.length - 1 ? cfg.zones[i + 1][0] : cfg.max;
        const a0 = GAUGE_START + GAUGE_SWEEP * valueToT(cfg, from);
        const a1 = GAUGE_START + GAUGE_SWEEP * valueToT(cfg, zTo);
        const zone = document.createElementNS(NS, 'path');
        zone.setAttribute('d', arcBetween(a0, a1, 31));
        zone.setAttribute('class', `dial-zone z-${level}`);
        ticks.appendChild(zone);
    });

    const update = () => {
        const t = valueToT(cfg, ctrl.value);
        needle.setAttribute('transform', `rotate(${(GAUGE_START + GAUGE_SWEEP * t).toFixed(2)} 50 50)`);
        arc.setAttribute('d', arcPath(GAUGE_START + GAUGE_SWEEP * t));
        valueEl.textContent = cfg.fmt(ctrl.value);
        input.value = String(Math.round(t * 100));
        input.setAttribute('aria-valuetext', cfg.fmt(ctrl.value));
        const level = zoneLevel(cfg, ctrl.value);
        for (const el of [arc, needle, valueEl]) {
            el.classList.toggle('z-amber', level === 'amber');
            el.classList.toggle('z-red', level === 'red');
        }
    };

    /* Позиция указателя -> значение: угол от центра, мёртвая зона в центре игнорируется */
    const pointerToValue = (e) => {
        const rect = dial.getBoundingClientRect();
        const dx = e.clientX - (rect.left + rect.width / 2);
        const dy = e.clientY - (rect.top + rect.height / 2);
        if (Math.hypot(dx, dy) < rect.width * 0.18) return null;
        const clockDeg = ((Math.atan2(dx, -dy) * 180) / Math.PI + 360) % 360;
        const raw = (clockDeg - GAUGE_START + 360) % 360;
        const t = raw > GAUGE_SWEEP ? (raw > GAUGE_SWEEP + 45 ? 0 : 1) : raw / GAUGE_SWEEP;
        return tToValue(cfg, t);
    };

    let dragging = false;

    dial.addEventListener('pointerdown', (e) => {
        e.preventDefault();
        dial.setPointerCapture(e.pointerId);
        dragging = true;
        input.focus({ preventScroll: true });
        const v = pointerToValue(e);
        if (v != null) setControlValue(ctrl, v);
    });

    dial.addEventListener('pointermove', (e) => {
        if (!dragging) return;
        const v = pointerToValue(e);
        if (v != null) setControlValue(ctrl, v);
    });

    const endDrag = () => {
        if (!dragging) return;
        dragging = false;
        saveSettings();
    };
    dial.addEventListener('pointerup', endDrag);
    dial.addEventListener('pointercancel', endDrag);

    /* Колесо над шкалой — точная подстройка (2% дуги за щелчок) */
    dial.addEventListener('wheel', (e) => {
        e.preventDefault();
        const dir = e.deltaY < 0 ? 1 : -1;
        const t = clamp(valueToT(cfg, ctrl.value) + dir * 0.02, 0, 1);
        setControlValue(ctrl, tToValue(cfg, t));
        saveSettings();
    }, { passive: false });

    /* Скрытый range: клавиатура и скринридер. Стрелки двигают позицию на 1% дуги */
    input.addEventListener('input', () => {
        const t = Number(input.value) / 100;
        if (!Number.isFinite(t)) return;
        ctrl.value = tToValue(cfg, t);
        update();
    });
    input.addEventListener('change', saveSettings);
    input.addEventListener('focus', () => dial.classList.add('engaged'));
    input.addEventListener('blur', () => dial.classList.remove('engaged'));

    ctrl.update = update;
    return ctrl;
}

const settingsControls = {
    temperature: initDial('temperature'),
    maxTokens: initDial('maxTokens'),
    topP: initDial('topP'),
};

function setControlValue(ctrl, value) {
    ctrl.value = clamp(value, ctrl.cfg.min, ctrl.cfg.max);
    ctrl.update();
}

/* «?» у названия параметра: клик — для тача, hover/focus подхватывает CSS */
document.querySelectorAll('.info-btn').forEach((btn) => {
    const holder = () => btn.closest('.gauge, .setting-inline');

    btn.addEventListener('click', () => {
        const open = btn.getAttribute('aria-expanded') === 'true';
        btn.setAttribute('aria-expanded', String(!open));
        holder().classList.toggle('tip-open', !open);
    });
    btn.addEventListener('keydown', (e) => {
        if (e.key !== 'Escape') return;
        btn.setAttribute('aria-expanded', 'false');
        holder().classList.remove('tip-open');
        btn.blur();
    });
});

/* Клик мимо подсказки закрывает закреплённую («?» жать повторно не нужно).
   Кнопка «?» исключена: её обработчик уже переключил состояние, и событие
   дойдёт сюда по всплытию — без проверки он закрыл бы только что открытую. */
document.addEventListener('click', (e) => {
    if (e.target.closest('.info-btn')) return;
    document.querySelectorAll('.info-btn[aria-expanded="true"]').forEach((btn) => {
        btn.setAttribute('aria-expanded', 'false');
        btn.closest('.gauge, .setting-inline').classList.remove('tip-open');
    });
});

function readSettings() {
    const settings = {
        reasoningMode: currentMode,
        thinking: thinkingToggle.getAttribute('aria-pressed') === 'true',
        judge: judgeToggle.getAttribute('aria-pressed') === 'true',
        contextStrategy: currentStrategy,
        mirrorMode: mirrorToggle.getAttribute('aria-pressed') === 'true',
        mirrorStrategyFull: mirrorStrategyFull.value || DEFAULTS.mirrorStrategyFull,
        mirrorStrategyComp: mirrorStrategyComp.value || DEFAULTS.mirrorStrategyComp,
        temperature: settingsControls.temperature.value,
        maxTokens: Math.round(settingsControls.maxTokens.value),
        topP: settingsControls.topP.value,
        stop: stopInput.value.trim(),
    };
    // Селект ещё не заполнен (идёт загрузка) — храним выбор только когда он есть
    if (modelSelect.value) settings.model = modelSelect.value;
    // Сессию диалога выдаёт сервер — храним, только когда она есть
    if (sessionId) settings.sessionId = sessionId;
    return settings;
}

function saveSettings() {
    try {
        localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(readSettings()));
    } catch (err) {
        // localStorage может быть недоступен (приватный режим и т.п.) — не критично
    }
}

function loadSettings() {
    let stored = null;
    try {
        stored = JSON.parse(localStorage.getItem(SETTINGS_STORAGE_KEY));
    } catch (err) {
        stored = null;
    }

    for (const [key, ctrl] of Object.entries(settingsControls)) {
        const value = stored ? Number(stored[key]) : NaN;
        setControlValue(ctrl, Number.isFinite(value) ? value : DEFAULTS[key]);
    }
    stopInput.value = stored && typeof stored.stop === 'string' ? stored.stop : '';
    sessionId = stored && typeof stored.sessionId === 'string' && stored.sessionId ? stored.sessionId : null;

    const thinking = stored && typeof stored.thinking === 'boolean' ? stored.thinking : DEFAULTS.thinking;
    setThinking(thinking, { save: false });

    const judge = stored && typeof stored.judge === 'boolean' ? stored.judge : DEFAULTS.judge;
    setJudge(judge, { save: false });

    /* Однократная миграция со старого тумблера сжатия: включённое «Сжатие
       истории» трактуется как стратегия «Сводка» и сразу фиксируется в
       хранилище (один раз — по наличию contextStrategy миграция не повторится) */
    const legacyCompression = Boolean(stored && stored.compressionEnabled === true && !stored.contextStrategy);
    const strategy = stored && typeof stored.contextStrategy === 'string' && stored.contextStrategy
        ? stored.contextStrategy
        : (legacyCompression ? 'summary' : null);
    setStrategy(strategy, { save: false });

    const mirror = stored && typeof stored.mirrorMode === 'boolean' ? stored.mirrorMode : DEFAULTS.mirrorMode;
    setMirror(mirror, { save: false });

    mirrorStrategyFull.value = stored && MIRROR_STRATEGY_LABELS[stored.mirrorStrategyFull]
        ? stored.mirrorStrategyFull
        : DEFAULTS.mirrorStrategyFull;
    mirrorStrategyComp.value = stored && MIRROR_STRATEGY_LABELS[stored.mirrorStrategyComp]
        ? stored.mirrorStrategyComp
        : DEFAULTS.mirrorStrategyComp;
    syncMirrorSides();

    setMode(stored && stored.reasoningMode, { save: false });
    loadModels(stored && typeof stored.model === 'string' ? stored.model : '');

    if (legacyCompression && stored) {
        /* В хранилище добавляется только новый ключ: остальные сохранённые
           настройки (модель, сессия, шкалы) переносятся как были */
        stored.contextStrategy = 'summary';
        try {
            localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(stored));
        } catch (err) {
            // localStorage может быть недоступен — миграция повторится при следующем запуске
        }
    }
}

/* ---------- Выбор модели LLM ---------- */

/* Модели приходят группами по провайдерам (/api/models). Значение селекта —
   составное «providerId/modelId», чтобы одинаковые id у разных провайдеров
   не конфликтовали; перед отправкой значение разбирается на два поля. */
async function loadModels(savedModel) {
    let providers = [];
    let defaultProvider = '';
    let defaultModel = '';
    try {
        const res = await fetch('/api/models');
        if (res.ok) {
            const data = await res.json();
            providers = Array.isArray(data.providers) ? data.providers : [];
            defaultProvider = typeof data.defaultProvider === 'string' ? data.defaultProvider : '';
            defaultModel = typeof data.default === 'string' ? data.default : '';
        }
    } catch (err) {
        // Список недоступен — селект останется с плейсхолдером, запрос уйдёт дефолтному провайдеру
    }

    const fallbackValue = defaultProvider && defaultModel ? `${defaultProvider}/${defaultModel}` : '';
    modelSelect.replaceChildren();

    for (const group of providers) {
        if (!group || !Array.isArray(group.models) || !group.models.length) continue;
        const optgroup = document.createElement('optgroup');
        optgroup.label = group.name || group.id;
        for (const id of group.models) {
            if (typeof id !== 'string' || !id) continue;
            optgroup.appendChild(new Option(id, `${group.id}/${id}`));
        }
        if (optgroup.children.length) modelSelect.appendChild(optgroup);
    }

    // Если ни одна группа не загрузилась — один вариант «по умолчанию» без выбора
    if (!modelSelect.options.length && fallbackValue) {
        modelSelect.appendChild(new Option(defaultModel, fallbackValue));
    }

    const values = Array.from(modelSelect.options).map((opt) => opt.value);
    const chosen = savedModel && values.includes(savedModel) ? savedModel : fallbackValue;
    if (chosen && values.includes(chosen)) modelSelect.value = chosen;
    if (directive.dataset.kind === 'ready' && !providers.length) {
        setDirective('ready', 'Список моделей недоступен — работает провайдер по умолчанию');
    }
}

modelSelect.addEventListener('change', saveSettings);

/* «providerId/modelId» → { provider, model } для ChatRequest */
function splitModelValue(value) {
    const sep = value.indexOf('/');
    if (sep <= 0) return { provider: null, model: value || null };
    return { provider: value.slice(0, sep), model: value.slice(sep + 1) || null };
}

/* ---------- Табло режимов ---------- */

/* Тумблер «Рассуждения»: ВКЛ → модели уходит {"type":"enabled"}, ВЫКЛ → {"type":"disabled"}. */
function setThinking(on, { save = true } = {}) {
    const enabled = Boolean(on);
    thinkingToggle.setAttribute('aria-pressed', String(enabled));
    thinkingToggle.textContent = enabled ? 'Рассуждения: ВКЛ' : 'Рассуждения: ВЫКЛ';
    if (save) saveSettings();
}

thinkingToggle.addEventListener('click', () => {
    setThinking(thinkingToggle.getAttribute('aria-pressed') !== 'true');
});

/* Тумблер «Судья»: ВКЛ → каждый ответ перепроверяется отдельным вызовом LLM. */
function setJudge(on, { save = true } = {}) {
    const enabled = Boolean(on);
    judgeToggle.setAttribute('aria-pressed', String(enabled));
    judgeToggle.textContent = enabled ? 'Судья: ВКЛ' : 'Судья: ВЫКЛ';
    if (save) saveSettings();
}

judgeToggle.addEventListener('click', () => {
    setJudge(judgeToggle.getAttribute('aria-pressed') !== 'true');
});

/* ---------- Радиогруппа «Стратегия памяти» + панель фактов ---------- */

/* Панель фактов — показания стратегии «Факты»: читаемая сводка того, что
   сервер извлёк из диалога. Только чтение: ввода в ней нет по определению. */
function renderFacts(facts) {
    factsList.replaceChildren();
    for (const fact of Array.isArray(facts) ? facts : []) {
        const key = fact && typeof fact.key === 'string' ? fact.key : '';
        if (!key) continue;
        const value = fact.value == null ? '' : String(fact.value);
        const li = document.createElement('li');
        const keySpan = document.createElement('span');
        keySpan.className = 'facts-key';
        keySpan.textContent = key;
        li.appendChild(keySpan);
        li.appendChild(document.createTextNode(': ' + value));
        factsList.appendChild(li);
    }
    if (!factsList.children.length) {
        const empty = document.createElement('li');
        empty.className = 'facts-empty';
        empty.textContent = 'Фактов пока нет';
        factsList.appendChild(empty);
    }
}

/* Видимость панели следует за активной стратегией: «Факты» — панель на виду
   (с присланными фактами или честным «пока нет»), остальные — панель скрыта */
function updateFactsPanel(data) {
    if (currentStrategy !== 'facts') {
        factsPanel.hidden = true;
        factsList.replaceChildren();
        return;
    }
    factsPanel.hidden = false;
    const facts = data && Array.isArray(data.facts) ? data.facts : [];
    renderFacts(facts);
}

function setStrategy(rawStrategy, { save = true } = {}) {
    currentStrategy = rawStrategy && Object.prototype.hasOwnProperty.call(STRATEGY_LABELS, rawStrategy)
        ? rawStrategy
        : DEFAULTS.contextStrategy;

    for (const btn of strategyButtons) {
        btn.setAttribute('aria-checked', String(btn.dataset.strategy === currentStrategy));
        if (btn.dataset.strategy === currentStrategy) {
            strategyHint.textContent = btn.dataset.caption;
        }
    }
    // APG radio: в группе tabbable только выбранная стратегия
    strategyButtons.forEach((btn) => {
        btn.tabIndex = btn.dataset.strategy === currentStrategy ? 0 : -1;
    });

    updateFactsPanel();
    // «Ветки»: чипы и клавиша развилки появляются сразу, не дожидаясь
    // первого ответа. Смена стратегии на другую убирает бар целиком —
    // чипы и развилка живут только под «Ветками».
    if (currentStrategy === 'branching') loadBranches(sessionId);
    else hideBranches();
    if (save) saveSettings();
}

strategyButtons.forEach((btn) => {
    btn.addEventListener('click', () => setStrategy(btn.dataset.strategy));
});

strategyGroup.addEventListener('keydown', (e) => {
    const keys = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'];
    if (!keys.includes(e.key)) return;
    e.preventDefault();

    const idx = strategyButtons.findIndex((b) => b.dataset.strategy === currentStrategy);
    let next = idx;
    if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = strategyButtons.length - 1;
    else {
        const dir = (e.key === 'ArrowDown' || e.key === 'ArrowRight') ? 1 : -1;
        next = (idx + dir + strategyButtons.length) % strategyButtons.length;
    }
    setStrategy(strategyButtons[next].dataset.strategy);
    strategyButtons[next].focus();
});

/* Тумблер «Режим сравнения»: ВКЛ → каждый запрос уходит параллельно в пару
   twin-сессий, каждой со своей стратегией памяти (селект в шапке колонки),
   ответы сравниваются под журналом. Выключение стирает накопленные показания
   зеркала; twin-сессии остаются на сервере как обычные, основной диалог
   не затрагивается. */
function setMirror(on, { save = true } = {}) {
    const enabled = Boolean(on);
    mirrorToggle.setAttribute('aria-pressed', String(enabled));
    mirrorToggle.textContent = enabled ? 'Режим сравнения: ВКЛ' : 'Режим сравнения: ВЫКЛ';
    if (enabled) {
        /* Зеркало ветки не показывает: twin-сессии живут по своим
           колонкам-стратегиям, у них ветвление подавлено */
        hideBranches();
        mirrorBlock.hidden = false;
        for (const side of MIRROR_SIDES) {
            if (!mirrorState[side.key].exchanges) {
                mirrorBodies[side.key].replaceChildren(mirrorEmptyNote());
            }
        }
        renderMirrorTotals();
    } else {
        finishMirror();
        loadBranches(sessionId);
    }
    if (save) saveSettings();
}

mirrorToggle.addEventListener('click', () => {
    setMirror(mirrorToggle.getAttribute('aria-pressed') !== 'true');
});

function setMode(rawMode, { save = true } = {}) {
    currentMode = rawMode && Object.prototype.hasOwnProperty.call(MODE_LABELS, rawMode)
        ? rawMode
        : DEFAULTS.reasoningMode;

    for (const btn of modeButtons) {
        btn.setAttribute('aria-checked', String(btn.dataset.mode === currentMode));
        if (btn.dataset.mode === currentMode) {
            reasoningHint.textContent = btn.dataset.caption;
        }
    }
    // APG radio: в группе tabbable только выбранный режим
    modeButtons.forEach((btn) => {
        btn.tabIndex = btn.dataset.mode === currentMode ? 0 : -1;
    });
    modeRepeater.textContent = MODE_LABELS[currentMode].toUpperCase();
    modeRepeater.title = MODE_LABELS[currentMode];

    if (directive.dataset.kind === 'ready') {
        setDirective('ready', 'Готов к передаче');
    }
    if (save) saveSettings();
}

modeButtons.forEach((btn) => {
    btn.addEventListener('click', () => setMode(btn.dataset.mode));
});

modeGroup.addEventListener('keydown', (e) => {
    const keys = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'];
    if (!keys.includes(e.key)) return;
    e.preventDefault();

    const idx = modeButtons.findIndex((b) => b.dataset.mode === currentMode);
    let next = idx;
    if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = modeButtons.length - 1;
    else {
        const dir = (e.key === 'ArrowDown' || e.key === 'ArrowRight') ? 1 : -1;
        next = (idx + dir + modeButtons.length) % modeButtons.length;
    }
    setMode(modeButtons[next].dataset.mode);
    modeButtons[next].focus();
});

stopInput.addEventListener('change', saveSettings);

resetBtn.addEventListener('click', () => {
    for (const [key, ctrl] of Object.entries(settingsControls)) {
        setControlValue(ctrl, DEFAULTS[key]);
    }
    stopInput.value = '';
    setThinking(DEFAULTS.thinking);
    setJudge(DEFAULTS.judge);
    setStrategy(DEFAULTS.contextStrategy);
    setMirror(DEFAULTS.mirrorMode);
    setMode(DEFAULTS.reasoningMode);
    if (modelSelect.options.length) modelSelect.selectedIndex = 0;
    saveSettings();
});

/* ---------- Мобильное меню (drawer) ---------- */

const sidebar = document.getElementById('settings');
const settingsToggle = document.getElementById('settingsToggle');
const settingsClose = document.getElementById('settingsClose');
const sidebarBackdrop = document.getElementById('sidebarBackdrop');

function openSettings() {
    sidebar.classList.add('open');
    sidebarBackdrop.classList.add('visible');
    settingsToggle.setAttribute('aria-expanded', 'true');
}

function closeSettings() {
    sidebar.classList.remove('open');
    sidebarBackdrop.classList.remove('visible');
    settingsToggle.setAttribute('aria-expanded', 'false');
    // Возвращаем фокус на триггер, если он остался внутри выдвижной панели
    if (document.activeElement && sidebar.contains(document.activeElement)) {
        settingsToggle.focus();
    }
}

settingsToggle.addEventListener('click', () => {
    if (sidebar.classList.contains('open')) closeSettings();
    else openSettings();
});

settingsClose.addEventListener('click', closeSettings);
sidebarBackdrop.addEventListener('click', closeSettings);

document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (recorderOpen) {
        closeRecorder();
        return;
    }
    if (fuelOpen) {
        closeFuelModal();
        return;
    }
    if (sidebar.classList.contains('open')) closeSettings();
});

/* ---------- Обслуживание: Режим ТО и Самописец ---------- */

const maintToggle = document.getElementById('maintToggle');
const recorderBtn = document.getElementById('recorderBtn');
const recorderOverlay = document.getElementById('recorderOverlay');
const recorderBody = document.getElementById('recorderBody');
const recorderClose = document.getElementById('recorderClose');
const recorderRefresh = document.getElementById('recorderRefresh');

let recorderOpen = false;

/* Режим ТО — линза обзора: видимость сырого обмена в журнале управляется
   классом на body, поэтому включать его можно до и после получения ответов. */
function setMaint(on) {
    document.body.classList.toggle('maint-on', on);
    maintToggle.setAttribute('aria-pressed', String(on));
    if (directive.dataset.kind === 'ready') {
        setDirective('ready', on ? 'Режим ТО: сырой обмен виден в журнале' : 'Готов к передаче');
    }
}

function exchangeTime(ts) {
    return new Date(ts).toLocaleTimeString('ru-RU', { hour12: false });
}

function fmtSize(payload) {
    const kb = payload.length / 1024;
    return kb >= 1 ? `${kb.toFixed(1)} КБ` : `${payload.length} Б`;
}

/* Тела обменов приходят компактными; для чтения разворачиваем в многострочный JSON. */
function prettyJson(payload) {
    try {
        return JSON.stringify(JSON.parse(payload), null, 2);
    } catch (err) {
        return payload;
    }
}

function entriesWord(n) {
    const mod10 = n % 10;
    const mod100 = n % 100;
    if (mod10 === 1 && mod100 !== 11) return 'запись';
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'записи';
    return 'записей';
}

function buildPayloadBlock(payload) {
    const pre = document.createElement('pre');
    pre.className = 'json-view';

    const code = document.createElement('code');
    code.className = 'language-json';
    code.textContent = prettyJson(payload);
    pre.appendChild(code);

    if (typeof hljs !== 'undefined') {
        hljs.highlightElement(code);
    }
    return pre;
}

/* Блок «Сырой обмен» в записи журнала: раскрывается только в Режиме ТО (CSS). */
function buildRawExchange(exchanges) {
    const wrap = document.createElement('details');
    wrap.className = 'raw-exchange';

    const summary = document.createElement('summary');
    summary.textContent = `Сырой обмен · ${exchanges.length} ${entriesWord(exchanges.length)}`;
    wrap.appendChild(summary);

    for (const ex of exchanges) {
        const item = document.createElement('details');
        item.className = 'raw-item' + (ex.ok === false ? ' fault' : '');

        const head = document.createElement('summary');
        const dir = document.createElement('span');
        dir.className = 'raw-dir';
        dir.textContent = ex.dir === 'req' ? `→ запрос к модели · ${exchangeTime(ex.ts)}` : `← ответ модели · ${exchangeTime(ex.ts)}`;
        const meta = document.createElement('span');
        meta.className = 'raw-meta';
        const bits = [fmtSize(ex.payload)];
        if (ex.ms != null) bits.push(`${ex.ms} мс`);
        meta.textContent = bits.join(' · ');
        head.appendChild(dir);
        head.appendChild(meta);

        item.appendChild(head);
        const payload = buildPayloadBlock(ex.payload);
        item.appendChild(buildExpandToggle(payload));
        item.appendChild(payload);
        wrap.appendChild(item);
    }
    return wrap;
}

/* Самописец: модальный протокол всех обменов, свежие сверху. */
function buildRecorderEntry(ex) {
    const item = document.createElement('details');
    item.className = 'rec-entry ' + (ex.dir === 'req' ? 'req' : 'res') + (ex.ok === false ? ' fault' : '');

    const head = document.createElement('summary');
    head.className = 'rec-head';

    const time = document.createElement('span');
    time.className = 'rec-time';
    time.textContent = exchangeTime(ex.ts);

    const dir = document.createElement('span');
    dir.className = 'rec-dir';
    dir.textContent = ex.dir === 'req' ? '→ запрос' : '← ответ';

    const meta = document.createElement('span');
    meta.className = 'rec-meta';
    const bits = [fmtSize(ex.payload)];
    if (ex.ms != null) bits.push(`${ex.ms} мс`);
    meta.textContent = bits.join(' · ');

    head.appendChild(time);
    head.appendChild(dir);
    head.appendChild(meta);

    item.appendChild(head);
    item.appendChild(buildPayloadBlock(ex.payload));
    return item;
}

function recorderMessage(text, isFault = false) {
    const p = document.createElement('p');
    p.className = 'recorder-empty' + (isFault ? ' fault' : '');
    p.textContent = text;
    return p;
}

async function loadRecorder() {
    recorderBody.replaceChildren(recorderMessage('Читаю протокол…'));
    try {
        const res = await fetch('/api/log');
        const data = await res.json();
        if (!recorderOpen) return;

        const entries = Array.isArray(data.entries) ? data.entries : [];
        if (!entries.length) {
            recorderBody.replaceChildren(recorderMessage('Протокол пуст — обмен с моделью ещё не выполнялся'));
            return;
        }
        recorderBody.replaceChildren(...entries.slice().reverse().map(buildRecorderEntry));
    } catch (err) {
        if (!recorderOpen) return;
        recorderBody.replaceChildren(recorderMessage('Не удалось прочитать протокол: ' + err.message, true));
    }
}

function openRecorder() {
    recorderOpen = true;
    recorderOverlay.hidden = false;
    recorderClose.focus();
    loadRecorder();
}

function closeRecorder() {
    recorderOpen = false;
    recorderOverlay.hidden = true;
    recorderBtn.focus();
}

maintToggle.addEventListener('click', () => {
    setMaint(maintToggle.getAttribute('aria-pressed') !== 'true');
});

recorderBtn.addEventListener('click', openRecorder);
recorderClose.addEventListener('click', closeRecorder);
recorderRefresh.addEventListener('click', loadRecorder);

recorderOverlay.addEventListener('click', (e) => {
    if (e.target === recorderOverlay) closeRecorder();
});

/* ---------- Топливомер: расход топлива за сессию ---------- */

/* Тариф: ровно $0,00000036 за один токен — запрос и ответ по одной цене */
const TOKEN_TARIFF = 0.00000036;

const fuelOverlay = document.getElementById('fuelOverlay');
const fuelBody = document.getElementById('fuelBody');
const fuelClose = document.getElementById('fuelClose');

let fuelOpen = false;

const SVG_NS = 'http://www.w3.org/2000/svg';

/* Элемент SVG с атрибутами: оба графика чертятся вручную, без библиотек */
function svgEl(name, attrs = {}) {
    const el = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attrs)) {
        el.setAttribute(key, String(value));
    }
    return el;
}

/* Деньги: значения копеечные — sig значащих цифр без экспоненты, запятая
   по-русски (например, $0,00212); очень малые значения не уводим в e-нотацию */
function fmtMoney(value, sig = 3) {
    if (!Number.isFinite(value) || value <= 0) return '$0';
    let str;
    if (value >= 1) {
        str = value.toFixed(2);
    } else {
        const digits = Math.min(12, Math.max(0, sig - 1 - Math.floor(Math.log10(value))));
        str = value.toFixed(digits);
    }
    if (str.includes('.')) str = str.replace(/0+$/, '').replace(/\.$/, '');
    return '$' + str.replace('.', ',');
}

/* Ось Y: круглая верхняя граница и 3–4 линии сетки. Пары «шаг/линии»
   подобраны так, чтобы промежуточные подписи тоже были круглыми
   (5542 → 6000 по 2000; 1200 → 2000 по 500) */
function niceScale(value) {
    if (!(value > 0)) return { max: 1, lines: 3 };
    const pow = Math.pow(10, Math.floor(Math.log10(value)));
    const norm = value / pow;
    const steps = [[1, 4], [2, 4], [3, 3], [4, 4], [6, 3], [8, 4], [12, 4]];
    for (const [step, lines] of steps) {
        if (norm <= step) return { max: step * pow, lines };
    }
    return { max: 10 * pow, lines: 5 };
}

/* Сетка и подписи оси Y — общая часть обоих графиков */
function buildFuelAxis(svg, left, right, bottom, plotH, max, lines, fmt) {
    for (let i = 1; i <= lines; i++) {
        const y = bottom - (plotH * i) / lines;
        svg.appendChild(svgEl('line', { class: 'fuel-grid', x1: left, y1: y.toFixed(1), x2: right, y2: y.toFixed(1) }));
        const label = svgEl('text', {
            class: 'fuel-axis-label',
            x: left - 6,
            y: (y + 3).toFixed(1),
            'text-anchor': 'end',
        });
        label.textContent = fmt((max * i) / lines);
        svg.appendChild(label);
    }
    svg.appendChild(svgEl('line', { class: 'fuel-axis-line', x1: left, y1: bottom, x2: right, y2: bottom }));
}

/* График расхода: составной столбец на каждый обмен — запрос (зелёный)
   снизу и ответ (янтарный) сверху; цвета направлений из самописца */
function buildTokenChart(history) {
    const W = 560, H = 210, L = 44, R = 6, T = 10, B = 22;
    const plotW = W - L - R;
    const plotH = H - T - B;
    const bottom = T + plotH;

    const totals = history.map((rec) => rec.totalTokens || 0);
    const { max, lines } = niceScale(Math.max(...totals));

    const svg = svgEl('svg', {
        class: 'fuel-chart-svg',
        viewBox: `0 0 ${W} ${H}`,
        role: 'img',
        'aria-label': 'Расход токенов по обменам за сессию',
    });

    buildFuelAxis(svg, L, W - R, bottom, plotH, max, lines, fmtFuel);

    /* Ширина паза на столбец: при многих обменах столбцы жмутся и мелеют,
       но не вылезают за ось; подписи номеров прореживаются */
    const slot = plotW / history.length;
    const barW = Math.max(1.5, Math.min(34, slot * 0.62));
    const showEvery = slot >= 16 ? 1 : Math.ceil(16 / slot);

    history.forEach((rec, i) => {
        const prompt = rec.promptTokens || 0;
        const completion = rec.completionTokens || 0;
        const cx = L + slot * i + slot / 2;
        const x = cx - barW / 2;
        const promptH = prompt ? Math.max(1, (plotH * prompt) / max) : 0;
        const completionH = completion ? Math.max(1, (plotH * completion) / max) : 0;

        const bar = svgEl('g', { class: 'fuel-bar' });
        const title = svgEl('title');
        title.textContent = `Запрос ${i + 1}: +${prompt} / ответ ${completion}`;
        bar.appendChild(title);

        if (completionH) {
            bar.appendChild(svgEl('rect', {
                class: 'fuel-bar-completion',
                x: x.toFixed(1),
                y: (bottom - promptH - completionH).toFixed(1),
                width: barW.toFixed(1),
                height: completionH.toFixed(1),
            }));
        }
        if (promptH) {
            bar.appendChild(svgEl('rect', {
                class: 'fuel-bar-prompt',
                x: x.toFixed(1),
                y: (bottom - promptH).toFixed(1),
                width: barW.toFixed(1),
                height: promptH.toFixed(1),
            }));
        }
        svg.appendChild(bar);

        if (i % showEvery === 0) {
            const tick = svgEl('text', {
                class: 'fuel-axis-label',
                x: cx.toFixed(1),
                y: bottom + 14,
                'text-anchor': 'middle',
            });
            tick.textContent = String(i + 1);
            svg.appendChild(tick);
        }
    });

    return svg;
}

/* График цены: накопленная стоимость сессии по обменам */
function buildPriceChart(history) {
    const W = 560, H = 210, L = 56, R = 6, T = 10, B = 22;
    const plotW = W - L - R;
    const plotH = H - T - B;
    const bottom = T + plotH;

    let running = 0;
    const costs = history.map((rec) => {
        running += (rec.totalTokens || 0) * TOKEN_TARIFF;
        return running;
    });
    const { max, lines } = niceScale(costs[costs.length - 1]);

    const svg = svgEl('svg', {
        class: 'fuel-chart-svg',
        viewBox: `0 0 ${W} ${H}`,
        role: 'img',
        'aria-label': 'Стоимость сессии по обменам',
    });

    buildFuelAxis(svg, L, W - R, bottom, plotH, max, lines, (v) => fmtMoney(v, 2));

    /* Один обмен — точка в центре; несколько — плечи по всей ширине оси */
    const step = plotW / Math.max(1, history.length - 1);
    const points = costs.map((cost, i) => ({
        x: history.length === 1 ? L + plotW / 2 : L + step * i,
        y: bottom - (plotH * cost) / max,
        cost,
    }));

    svg.appendChild(svgEl('polyline', {
        class: 'fuel-line',
        points: points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' '),
    }));

    points.forEach((p, i) => {
        const dot = svgEl('circle', { class: 'fuel-dot', cx: p.x.toFixed(1), cy: p.y.toFixed(1), r: 3 });
        const title = svgEl('title');
        title.textContent = `Обмен ${i + 1}: ${fmtMoney(p.cost)} за сессию`;
        dot.appendChild(title);
        svg.appendChild(dot);
    });

    return svg;
}

function buildFuelCell(nameText, valueText) {
    const cell = document.createElement('div');
    cell.className = 'fuel-cell';

    const name = document.createElement('span');
    name.className = 'fuel-cell-name';
    name.textContent = nameText;

    const value = document.createElement('span');
    value.className = 'fuel-cell-value';
    value.textContent = valueText;

    cell.appendChild(name);
    cell.appendChild(value);
    return cell;
}

/* Легенда составного столбца: цвета — как направления обмена в самописце */
function buildFuelLegend() {
    const legend = document.createElement('div');
    legend.className = 'fuel-legend';
    const items = [['fuel-swatch-prompt', 'Запрос'], ['fuel-swatch-completion', 'Ответ']];
    for (const [cls, text] of items) {
        const item = document.createElement('span');
        item.className = 'fuel-legend-item';

        const swatch = document.createElement('span');
        swatch.className = `fuel-swatch ${cls}`;

        const name = document.createElement('span');
        name.textContent = text;

        item.appendChild(swatch);
        item.appendChild(name);
        legend.appendChild(item);
    }
    return legend;
}

function buildFuelChartBlock(captionText, svg, legend) {
    const block = document.createElement('div');
    block.className = 'fuel-chart';

    const head = document.createElement('div');
    head.className = 'fuel-chart-head';
    const caption = document.createElement('span');
    caption.className = 'fuel-chart-caption';
    caption.textContent = captionText;
    head.appendChild(caption);
    if (legend) head.appendChild(legend);

    block.appendChild(head);
    block.appendChild(svg);
    return block;
}

/* Содержимое топливомера: сводка, оба графика и тариф. Перестраивается при
   каждом открытии — данные берутся из живой истории текущей сессии */
function renderFuelModal() {
    const history = sessionTokenHistory;
    const wrap = document.createDocumentFragment();

    /* Сводка: расход запроса и ответа, сумма и цена; без обменов — нули */
    const promptSum = history.reduce((acc, rec) => acc + (rec.promptTokens || 0), 0);
    const completionSum = history.reduce((acc, rec) => acc + (rec.completionTokens || 0), 0);
    const totalSum = promptSum + completionSum;

    const summary = document.createElement('div');
    summary.className = 'fuel-summary';
    summary.appendChild(buildFuelCell('Запрос', promptSum));
    summary.appendChild(buildFuelCell('Ответ', completionSum));
    summary.appendChild(buildFuelCell('Сумма', totalSum));
    summary.appendChild(buildFuelCell('Цена за сессию', fmtMoney(totalSum * TOKEN_TARIFF)));
    wrap.appendChild(summary);

    if (!history.length) {
        const empty = document.createElement('p');
        empty.className = 'recorder-empty';
        empty.textContent = 'Топливо ещё не расходовалось — задайте вопрос, и здесь появится график расхода';
        wrap.appendChild(empty);
    } else {
        wrap.appendChild(buildFuelChartBlock('Расход по обменам', buildTokenChart(history), buildFuelLegend()));
        wrap.appendChild(buildFuelChartBlock('Стоимость за сессию', buildPriceChart(history), null));
    }

    const tariff = document.createElement('p');
    tariff.className = 'fuel-tariff';
    tariff.textContent = 'Тариф: $0,00000036 за 1 токен';
    wrap.appendChild(tariff);

    fuelBody.replaceChildren(wrap);
}

function openFuelModal() {
    fuelOpen = true;
    fuelOverlay.hidden = false;
    renderFuelModal();
    fuelClose.focus();
}

function closeFuelModal() {
    fuelOpen = false;
    fuelOverlay.hidden = true;
    tokenCounter.focus();
}

/* Клик по счётчику топлива в полосе открывает и закрывает топливомер */
tokenCounter.addEventListener('click', () => {
    if (fuelOpen) closeFuelModal();
    else openFuelModal();
});

fuelClose.addEventListener('click', closeFuelModal);

fuelOverlay.addEventListener('click', (e) => {
    if (e.target === fuelOverlay) closeFuelModal();
});

/* ---------- Новый диалог ---------- */

const newChatBtn = document.getElementById('newChatBtn');

/* Сброс диалога: сессия забывается локально, журнал возвращается к приветствию.
   Серверную сессию больше не удаляем — прежние диалоги остаются в панели
   «Сессии» и доступны для переключения. */
newChatBtn.addEventListener('click', () => {
    sessionId = null;
    saveSettings();
    chat.innerHTML = WELCOME_MARKUP;
    hideBranches();
    /* Топливомер обнуляется: новый диалог — новый расход */
    resetSessionFuel();
    setDirective('ready', 'Готов к передаче');
    /* Прежняя сессия остаётся в перечне, лампа активной записи гаснет */
    loadSessions();
    input.focus();
});

/* ---------- Очистка всех сессий ---------- */

const clearAllBtn = document.getElementById('clearAllBtn');
const CLEAR_ALL_LABEL = 'Очистить все';
const CLEAR_ALL_ARMED_LABEL = 'Точно стереть?';

/* Таймер автоматического взведения/снятия: одно на клавишу */
let clearAllTimer = null;

/* Возврат клавиши в исходное состояние: гаснет лампа, возвращается надпись */
function disarmClearAll() {
    clearTimeout(clearAllTimer);
    clearAllTimer = null;
    clearAllBtn.classList.remove('armed');
    clearAllBtn.setAttribute('aria-pressed', 'false');
    clearAllBtn.textContent = CLEAR_ALL_LABEL;
}

/* Клавиша требует двух нажатий — подтверждения на борту вместо диалога браузера:
   первое взводит её (красная лампа, «Точно стереть?»), второе стирает все сессии
   на сервере. Без нажатия через 4 секунды клавиша гаснет сама. */
clearAllBtn.addEventListener('click', async () => {
    if (!clearAllBtn.classList.contains('armed')) {
        clearAllBtn.classList.add('armed');
        clearAllBtn.setAttribute('aria-pressed', 'true');
        clearAllBtn.textContent = CLEAR_ALL_ARMED_LABEL;
        clearAllTimer = setTimeout(disarmClearAll, 4000);
        return;
    }

    disarmClearAll();
    try {
        const res = await fetch('/api/sessions', { method: 'DELETE' });
        if (!res.ok) throw new Error(String(res.status));
        const data = await res.json().catch(() => ({}));
        const deleted = Number(data && data.deleted) || 0;

        /* Локальный сброс как у «Новый диалог»: серверные сессии уже стёрты */
        sessionId = null;
        saveSettings();
        chat.innerHTML = WELCOME_MARKUP;
        hideBranches();
        resetSessionFuel();
        setDirective('ready', `Все сессии стёрты (${deleted})`);
        loadSessions();
        input.focus();
    } catch (err) {
        /* Состояние диалога не трогаем — только директива об отказе */
        setDirective('fault', 'Не удалось стереть сессии');
    }
});

/* ---------- Панель сессий ---------- */

const sessionsList = document.getElementById('sessionsList');

/* Кэш последнего перечня: подсветка активной записи обновляется без запроса */
let sessionsCache = [];

/* Время обновления сессии: сегодня — «ЧЧ:ММ», вчера — «вчера»,
   старше — дата «ДД.ММ». Узкая панель не вмещает большего. */
function sessionTimeLabel(updatedAt) {
    const ts = Number(updatedAt);
    if (!Number.isFinite(ts)) return '';
    const stamp = new Date(ts);
    const now = new Date();
    const startOfDay = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const days = Math.round((startOfDay(now) - startOfDay(stamp)) / (24 * 60 * 60 * 1000));
    if (days <= 0) return stamp.toLocaleTimeString('ru-RU', { hour12: false }).slice(0, 5);
    if (days === 1) return 'вчера';
    return stamp.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
}

function renderSessions() {
    const frag = document.createDocumentFragment();

    for (const session of sessionsCache) {
        if (!session || typeof session.id !== 'string' || !session.id) continue;

        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'session-item';
        if (session.id === sessionId) item.setAttribute('aria-current', 'true');

        const title = document.createElement('span');
        title.className = 'session-title';
        title.textContent = typeof session.title === 'string' && session.title.trim()
            ? session.title
            : 'Без сообщений';

        const meta = document.createElement('span');
        meta.className = 'session-meta';
        const bits = [];
        const time = sessionTimeLabel(session.updatedAt);
        if (time) bits.push(time);
        const count = Number(session.messageCount);
        if (Number.isFinite(count) && count > 0) bits.push(`${count} ${entriesWord(count)}`);
        meta.textContent = bits.join(' · ');

        item.appendChild(title);
        item.appendChild(meta);
        item.addEventListener('click', () => switchToSession(session.id));
        frag.appendChild(item);
    }

    if (!frag.children.length) {
        const empty = document.createElement('p');
        empty.className = 'sessions-empty';
        empty.textContent = 'Пока нет сессий';
        sessionsList.replaceChildren(empty);
        return;
    }
    sessionsList.replaceChildren(frag);
}

/* Перечень сессий для панели: запрос не awaited — панель обновится сама */
async function loadSessions() {
    try {
        const res = await fetch('/api/sessions');
        if (!res.ok) return;
        const data = await res.json();
        sessionsCache = Array.isArray(data && data.sessions) ? data.sessions : [];
    } catch (err) {
        return;
    }
    renderSessions();
}

/* Переключение на выбранную сессию: идентификатор запоминается, история
   отрисовывается тем же помощником, что и при восстановлении после
   перезагрузки; лампа активной записи переезжает на выбранную строку */
async function switchToSession(id) {
    if (!id || id === sessionId) return;
    const previous = sessionId;
    sessionId = id;
    saveSettings();

    let data = null;
    try {
        const res = await fetch(`/api/session/${encodeURIComponent(id)}`);
        if (!res.ok) {
            /* Сессия не дожила до переключения: возвращаем прежнюю активную
               и перечитываем перечень — исчезнувшая из него уйдёт */
            sessionId = previous;
            saveSettings();
            loadSessions();
            return;
        }
        data = await res.json();
    } catch (err) {
        // Сервер недоступен — историю не достать, остаёмся на прежней сессии
        sessionId = previous;
        saveSettings();
        return;
    }

    const messages = Array.isArray(data && data.messages) ? data.messages : [];
    if (!renderHistory(messages)) {
        // Истории нет — журнал возвращается к исходному виду
        chat.innerHTML = WELCOME_MARKUP;
    }
    renderSessions();
    loadBranches(id);
    input.focus();
}

/* ---------- Восстановление журнала и переключение сессий ---------- */

/* Общая отрисовка истории — для восстановления после перезагрузки и для
   переключения сессий из панели: правила одни. Служебный трафик агента
   (role «tool») и пустые записи не восстанавливаем — он эфемерен.
   Ответы — без приборных показаний (они остались в прошлом полёте).
   Возвращает true, если журнал был перезаполнен. */
function renderHistory(messages) {
    // Пустые записи и служебный трафик агента пропускаем молча
    const restorable = messages.filter((m) =>
        m
        && (m.role === 'user' || m.role === 'assistant')
        && typeof m.content === 'string'
        && m.content.trim()
    );

    // Показывать нечего — журнал не трогаем
    if (!restorable.length) return false;

    // Журнал расчищается под восстановленные записи — обратное WELCOME_MARKUP
    chat.innerHTML = '';

    for (const m of restorable) {
        if (m.role === 'user') {
            addMessage(m.content);
        } else {
            /* Протокол агента восстанавливается из msg.events (когда бэкенд
               начнёт его присылать): та же трасса, что и под живым ответом,
               с чипами скачивания файлов. Без events — прежний вид. */
            addBotMessage(
                m.content,
                Array.isArray(m.events) && m.events.length ? { events: m.events } : null
            );
        }
    }
    chat.scrollTop = chat.scrollHeight;
    return true;
}

/* Идентификатор сессии переживает перезагрузку в настройках, а журнал — нет.
   Запрашиваем историю у сервера (GET /api/session/{id}) и отрисовываем её
   общим помощником renderHistory. */
async function restoreSession() {
    if (!sessionId) return;

    let data = null;
    try {
        const res = await fetch(`/api/session/${encodeURIComponent(sessionId)}`);
        if (!res.ok) {
            /* 404 — сессия не дожила до перезагрузки (или прочий неуспех):
               считаем идентификатор протухшим и остаёмся на приветствии */
            sessionId = null;
            saveSettings();
            return;
        }
        data = await res.json();
    } catch (err) {
        // Сервер недоступен — истории не достать, идентификатор считаем протухшим
        sessionId = null;
        saveSettings();
        return;
    }

    const messages = Array.isArray(data && data.messages) ? data.messages : [];
    /* Показывать нечего — приветствие остаётся, сессия продолжится
       следующими запросами (идентификатор сохранён) */
    renderHistory(messages);
    loadBranches(sessionId);
}

/* ---------- Селектор веток ---------- */

/* Ряд чипов над журналом: ветки активной сессии. Под стратегией «Ветки»
   виден всегда — минимум чип корня и клавиша развилки; в зеркале скрыт. */
const branchBar = document.getElementById('branchBar');

/* Активная ветка по последнему опросу: null — активна исходная история
   (корень). requestData шлёт её в /api/chat, пока выбрана стратегия «Ветки». */
let activeBranchId = null;

/* Селектор убирается целиком: «Новый диалог», зеркало и обычные режимы
   без веток не показывают чипы. activeBranchId обнуляется — протухший
   идентификатор не должен уйти в следующий запрос. */
function hideBranches() {
    activeBranchId = null;
    branchBar.hidden = true;
    branchBar.replaceChildren();
}

/* Чипы по снимку GET /api/session/{id}/branches. Первая запись перечня —
   живое активное состояние; для исходной истории у неё branchId=null:
   такой чип отрисован как активная строка ленты, но не кликабелен —
   активировать корень POST-ом нельзя. После активации ветки содержимое
   корня сворачивается в обычную запись (непустой id) и становится
   переключаемым. */
function renderBranches(branches) {
    const frag = document.createDocumentFragment();
    let sawActive = false;

    for (const branch of branches) {
        if (!branch || typeof branch.label !== 'string' || !branch.label.trim()) continue;

        const isActive = branch.active === true;
        if (isActive) sawActive = true;

        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = 'branch-chip';
        chip.setAttribute('aria-current', String(isActive));
        chip.disabled = branch.branchId == null;
        if (branch.branchId != null) {
            chip.addEventListener('click', () => activateBranch(branch.branchId));
        }

        if (isActive) {
            const lamp = document.createElement('span');
            lamp.className = 'branch-chip-lamp';
            lamp.setAttribute('aria-hidden', 'true');
            lamp.textContent = '●';
            chip.appendChild(lamp);
        }

        const label = document.createElement('span');
        label.className = 'branch-chip-label';
        label.textContent = branch.label;
        chip.appendChild(label);

        frag.appendChild(chip);
    }

    /* Без активной записи перечень не разметить — обычным режимам чипы
       не показываем. Под «Ветками» бар всё равно остаётся: клавиша
       развилки не должна пропасть вместе с чипами. */
    if ((!frag.children.length || !sawActive) && currentStrategy !== 'branching') {
        hideBranches();
        return;
    }
    frag.appendChild(buildAddBranchButton());
    branchBar.hidden = false;
    branchBar.replaceChildren(frag);
}

/* Опрос веток сессии. Под стратегией «Ветки» бар виден всегда: даже с
   единственной записью (живой корень) в нём есть клавиша «+ Развилка» —
   иначе первый ручной форк недостижим до авто-форка. Обычные режимы
   при записях меньше двух чипов не получают. Зеркало ветки не показывает
   вовсе. При недоступности сервера прежние чипы остаются как есть. */
async function loadBranches(id) {
    if (!id || mirrorToggle.getAttribute('aria-pressed') === 'true') {
        hideBranches();
        return;
    }

    let data = null;
    try {
        const res = await fetch(`/api/session/${encodeURIComponent(id)}/branches`);
        if (!res.ok) {
            hideBranches();
            return;
        }
        data = await res.json();
    } catch (err) {
        return;
    }

    const branches = Array.isArray(data && data.branches) ? data.branches : [];
    activeBranchId = null;
    for (const branch of branches) {
        if (branch && branch.active === true) {
            activeBranchId = branch.branchId == null ? null : String(branch.branchId);
            break;
        }
    }

    /* Обычным режимам бар не нужен, пока веток меньше двух. «Ветки» —
       бар и на одной записи: чип корня (не кликабелен) плюс клавиша
       развилки; пустой перечень даёт бар только с клавишей. */
    if (branches.length < 2 && currentStrategy !== 'branching') {
        hideBranches();
        return;
    }
    renderBranches(branches);
}

/* Клавиша ручной развилки в конце ряда чипов: пользовательский чекпоинт
   активного состояния в новую ветку-близнец — поверх авто-форка, по клику.
   Та же формула клавиши, что у чипов; видна, только когда бар виден
   (стратегия «Ветки», зеркала нет). */
function buildAddBranchButton() {
    const add = document.createElement('button');
    add.type = 'button';
    add.className = 'branch-chip branch-add';
    add.title = 'Создать развилку — снимок текущего диалога в новую ветку';

    const label = document.createElement('span');
    label.className = 'branch-chip-label';
    label.textContent = '+ Развилка';
    add.appendChild(label);
    add.addEventListener('click', addBranch);
    return add;
}

/* Ручная развилка: POST /api/session/{id}/branches создаёт близнец-ветку
   со снимком активного состояния и НЕ переключает её — активной остаётся
   текущая ветка, как и после авто-форка. Один клик — одна ветка; веток
   можно завести несколько и жить в них параллельно. */
async function addBranch() {
    if (!sessionId) return;

    let res = null;
    try {
        res = await fetch(`/api/session/${encodeURIComponent(sessionId)}/branches`, {
            method: 'POST',
        });
    } catch (err) {
        setDirective('fault', 'Не удалось создать развилку: ' + err.message);
        return;
    }

    if (res.status === 409) {
        addSystemMessage('Достигнут лимит веток (8)');
        return;
    }
    if (res.status === 404) {
        addSystemMessage('Сессия не найдена');
        return;
    }
    if (!res.ok) {
        setDirective('fault', `Развилка не создана (${res.status})`);
        return;
    }

    const data = await res.json().catch(() => null);
    addSystemMessage('─ Чекпоинт: создана '
        + (data && typeof data.label === 'string' && data.label ? data.label : 'ветка') + ' ─');
    loadBranches(sessionId);
}

/* Переключение на ветку: снимок цели становится журналом, sessionId не
   меняется — ветки живут внутри одной сессии. Топливо сессии при
   переключении НЕ сбрасывается: счёт общий за сессию (sessionTotalTokens),
   ветки — это та же сессия, а не новый диалог. */
async function activateBranch(id) {
    if (!sessionId || id == null || id === activeBranchId) return;

    try {
        const res = await fetch(`/api/session/${encodeURIComponent(sessionId)}/branches/activate`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ branchId: id }),
        });

        if (!res.ok) {
            if (res.status === 404) {
                addSystemMessage('Ветка не найдена');
            } else {
                setDirective('fault', `Ветка не переключена (${res.status})`);
            }
            loadBranches(sessionId);
            return;
        }

        const data = await res.json().catch(() => null);
        const messages = Array.isArray(data && data.messages) ? data.messages : [];
        if (!renderHistory(messages)) {
            // Пустой снимок — журнал к исходному виду
            chat.innerHTML = WELCOME_MARKUP;
        }
        loadBranches(sessionId);
        input.focus();
    } catch (err) {
        setDirective('fault', 'Не удалось переключить ветку: ' + err.message);
    }
}

/* ---------- Зеркальный режим: пара twin-сессий ---------- */

const mirrorBlock = document.getElementById('mirrorBlock');
const mirrorBodies = {
    full: document.getElementById('mirrorBodyFull'),
    compressed: document.getElementById('mirrorBodyComp'),
};
const mirrorTotals = {
    full: document.getElementById('mirrorTotalFull'),
    compressed: document.getElementById('mirrorTotalComp'),
};
const mirrorVerdict = document.getElementById('mirrorVerdict');

/* Селекты стратегий в шапках колонок: источник истины по каждой стороне */
const mirrorStrategyFull = document.getElementById('mirrorStrategyFull');
const mirrorStrategyComp = document.getElementById('mirrorStrategyComp');
const mirrorTitles = {
    full: document.getElementById('mirrorTitleFull'),
    compressed: document.getElementById('mirrorTitleComp'),
};

/* Две стороны сравнения. Стратегия памяти читается из селекта колонки перед
   каждым обменом — кэшировать нельзя: выбор может измениться между передачами. */
const MIRROR_SIDES = [
    { key: 'full', select: mirrorStrategyFull },
    { key: 'compressed', select: mirrorStrategyComp },
];

/* Стратегия стороны на текущий обмен; «Ветки» в зеркале не участвует —
   неизвестное значение селекта трактуются как «Выкл» */
function sideStrategy(side) {
    return MIRROR_STRATEGY_LABELS[side.select.value] ? side.select.value : 'off';
}

/* Заголовок колонки всегда показывает действующую стратегию своей колонки */
function syncMirrorSide(side) {
    mirrorTitles[side.key].textContent = MIRROR_STRATEGY_LABELS[sideStrategy(side)];
}

function syncMirrorSides() {
    for (const side of MIRROR_SIDES) syncMirrorSide(side);
}

for (const side of MIRROR_SIDES) {
    side.select.addEventListener('change', () => {
        syncMirrorSide(side);
        saveSettings();
    });
}
syncMirrorSides();

/* Состояние зеркала живёт в JS-памяти страницы и не переживает перезагрузку.
    По стороне: sessionId twin-сессии (выдаёт сервер первым обменом),
    total — накопленные токены (null, когда сервер прислал не число),
    summaryTotal — суммарная статья «сводка», exchanges — число передач. */
const mirrorState = {};
for (const side of MIRROR_SIDES) {
    mirrorState[side.key] = { sessionId: null, total: 0, summaryTotal: 0, exchanges: 0 };
}

/* Токен, которого сервер не прислал, — не ноль: показываем честное «н/д» */
function mirrorTokensOrNd(value) {
    return value == null ? 'н/д' : String(value);
}

function mirrorEmptyNote() {
    const p = document.createElement('p');
    p.className = 'mirror-empty';
    p.textContent = 'Передач ещё не было';
    return p;
}

/* Выключение сравнения: панель убирается, накопленные показания стираются.
    Сами twin-сессии остаются на сервере и видны в перечне сессий. */
function finishMirror() {
    mirrorBlock.hidden = true;
    for (const side of MIRROR_SIDES) {
        mirrorState[side.key] = { sessionId: null, total: 0, summaryTotal: 0, exchanges: 0 };
        mirrorBodies[side.key].replaceChildren();
    }
    mirrorVerdict.textContent = 'Сравнение появится после первой передачи';
}

/* Карточка одной передачи в колонке: ответ + строка топлива,
    либо карточка отказа — ошибка не выходит за свою сторону */
function buildMirrorCard(result, side) {
    const card = document.createElement('article');
    card.className = 'mirror-card';

    if (result.fault) {
        card.classList.add('fault');
        card.appendChild(buildEntryHead('Отказ', [{ text: nowStamp() }]));
        const text = document.createElement('p');
        text.className = 'mirror-error';
        text.textContent = result.message;
        card.appendChild(text);
        return card;
    }

    const parts = [{ text: nowStamp() }];
    if (result.elapsedMs != null) {
        const bits = [fmtElapsed(result.elapsedMs)];
        const rate = fmtRate(result);
        if (rate) bits.push(rate);
        parts.push({ fuel: true, text: bits.join(' · ') });
    }
    let fuelText = `Топливо: запрос ${mirrorTokensOrNd(result.promptTokens)}`
        + ` + ответ ${mirrorTokensOrNd(result.completionTokens)}`
        + ` = ${mirrorTokensOrNd(result.totalTokens)}`;
    if (result.summaryApplied && result.summaryTokens != null) {
        fuelText += ` (+сводка: ${result.summaryTokens})`;
    } else if (side && sideStrategy(side) === 'summary' && !result.summaryApplied) {
        fuelText += ' · сводка включена, сводки пока не было';
    }
    parts.push({ fuel: true, text: fuelText });
    card.appendChild(buildEntryHead('Ответ', parts));

    if (result.summaryApplied) {
        card.appendChild(buildSummaryBadge());
    }

    const bubble = document.createElement('div');
    bubble.className = 'bubble md';
    bubble.innerHTML = renderMarkdown(result.reply || '');
    highlightCode(bubble);
    card.appendChild(bubble);
    return card;
}

/* Накопленные показания сторон и итоговая строка сравнения */
function renderMirrorTotals() {
    for (const side of MIRROR_SIDES) {
        const st = mirrorState[side.key];
        mirrorTotals[side.key].textContent = st.exchanges
            ? `Итог: ${mirrorTokensOrNd(st.total)} ток`
            : 'Итог: —';
    }

    const full = mirrorState.full;
    const comp = mirrorState.compressed;
    if (!full.exchanges && !comp.exchanges) {
        mirrorVerdict.textContent = 'Сравнение появится после первой передачи';
        return;
    }
    const saving = full.total != null && comp.total != null && full.total > 0
        ? `экономия: ${Math.round(((full.total - comp.total) / full.total) * 100)}%`
        : 'экономия: н/д';
    const summaryPart = !comp.exchanges
        ? '(из них сводка: н/д)'
        : (comp.summaryTotal > 0
            ? `(из них сводка: ${comp.summaryTotal})`
            : '(сводка ещё не применялась)');
    mirrorVerdict.textContent =
        `${MIRROR_STRATEGY_LABELS[sideStrategy(MIRROR_SIDES[0])]}: ${mirrorTokensOrNd(full.total)} ток`
        + ` · ${MIRROR_STRATEGY_LABELS[sideStrategy(MIRROR_SIDES[1])]}: ${mirrorTokensOrNd(comp.total)} ток`
        + ` ${summaryPart}`
        + ` · ${saving}`;
}

/* Отказ стороны оседает карточкой в её колонке: обмен без токенов
    накопленные показания не меняет и обменом не считается */
function settleTwin(side, result) {
    if (result.fault) {
        const body = mirrorBodies[side.key];
        body.appendChild(buildMirrorCard({ fault: true, message: result.message }, side));
        body.scrollTop = body.scrollHeight;
    }
    return result;
}

function applyTwinResponse(side, data) {
    const st = mirrorState[side.key];
    if (data.sessionId) st.sessionId = data.sessionId;
    if (data.totalTokens != null) {
        if (st.total != null) st.total += data.totalTokens;
    } else {
        st.total = null;
    }
    if (data.summaryTokens != null) st.summaryTotal += data.summaryTokens;
    st.exchanges += 1;
    mirrorBodies[side.key].appendChild(buildMirrorCard(data, side));
    mirrorBodies[side.key].scrollTop = mirrorBodies[side.key].scrollHeight;
    renderMirrorTotals();
}

/* Резервный путь twin-стороны: блокирующий /api/chat, когда поток недоступен */
async function sendTwinBlocking(side, requestData) {
    try {
        const res = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(requestData),
        });
        const data = await res.json().catch(() => null);
        if (!res.ok || !data) {
            return settleTwin(side, { fault: true, message: (data && data.reply) || `Сервер ответил ${res.status}` });
        }
        applyTwinResponse(side, data);
        return { fault: false };
    } catch (err) {
        return settleTwin(side, { fault: true, message: 'Нет связи с сервером: ' + err.message });
    }
}

/* Один twin-запрос: тот же /api/chat/stream, итог — в свою колонку зеркала.
    Любой сбой стороны (сеть, не-2xx, кадр error) оседает карточкой отказа
    в её колонке и наружу не выходит: вторая сторона рендерится нормально. */
async function sendTwinStream(side, requestData) {
    const requestId = makeRequestId();
    let gotFrame = false;

    try {
        const res = await fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ...requestData, requestId }),
        });

        if (!res.ok || !res.body) {
            return await sendTwinBlocking(side, requestData);
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let finished = false;
        let outcome = null;

        const handleEvent = (event) => {
            switch (event.type) {
                case 'status':
                    setStreamStatus(`${MIRROR_STRATEGY_LABELS[sideStrategy(side)]}: ${typeof event.text === 'string' ? event.text : ''}`);
                    return false;
                case 'checkpoint':
                    /* В зеркале ветвление подавлено — кадр не должен прийти.
                       Явный кейс для читаемости: молча игнорируем, маркер в
                       колонки не рендерим, поток не завершаем */
                    return false;
                case 'final':
                    if (event.response && typeof event.response === 'object') {
                        applyTwinResponse(side, event.response);
                        outcome = { fault: false };
                    } else {
                        outcome = { fault: true, message: 'Сервер прислал некорректный итоговый ответ' };
                    }
                    return true;
                case 'cancelled':
                    outcome = { fault: true, message: 'Передача остановлена' };
                    return true;
                case 'error':
                    outcome = { fault: true, message: event.message || 'Произошла ошибка' };
                    return true;
                default:
                    return false;
            }
        };
        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            let sep;
            while (!finished && (sep = buffer.indexOf('\n\n')) !== -1) {
                gotFrame = true;
                finished = parseStreamFrame(buffer.slice(0, sep), handleEvent);
                buffer = buffer.slice(sep + 2);
            }
            if (finished) {
                await reader.cancel().catch(() => {});
                break;
            }
        }

        if (!finished) {
            buffer += decoder.decode();
            if (buffer.trim()) {
                gotFrame = true;
                finished = parseStreamFrame(buffer, handleEvent);
            }
        }

        if (outcome) return settleTwin(side, outcome);
        if (!gotFrame) return await sendTwinBlocking(side, requestData);
        return settleTwin(side, { fault: true, message: 'Сервер завершил передачу без итогового ответа' });
    } catch (err) {
        if (err && err.name === 'AbortError') {
            return settleTwin(side, { fault: true, message: 'Передача остановлена' });
        }
        if (gotFrame) {
            return settleTwin(side, { fault: true, message: 'Связь с сервером прервалась во время передачи: ' + err.message });
        }
        return await sendTwinBlocking(side, requestData);
    }
}

/* Пара параллельных передач: оба fetch отправляются одновременно —
    ожидание одной стороны ради другой лишило бы сравнение смысла.
    Каждый twin несёт свой sessionId и стратегию памяти своей колонки,
    прочитанную из селекта в момент отправки. */
async function runMirrorExchange(baseRequest) {
    for (const side of MIRROR_SIDES) {
        if (!mirrorState[side.key].exchanges) {
            mirrorBodies[side.key].replaceChildren();
        }
    }

    const attempts = MIRROR_SIDES.map((side) => {
        const requestData = {
            ...baseRequest,
            sessionId: mirrorState[side.key].sessionId,
            judgeEnabled: false,
            contextStrategy: sideStrategy(side),
        };
        return sendTwinStream(side, requestData);
    });

    const results = await Promise.all(attempts);
    const anyOk = results.some((r) => !r.fault);
    setDirective(anyOk ? 'ready' : 'fault', anyOk ? 'Сравнение завершено' : 'Обе стороны зеркала отказали');
    loadSessions();
    return results;
}

loadSettings();
restoreSession();
loadSessions();

/* ---------- Клавиша «Стоп» ---------- */

/* Идентификатор запроса: по нему сервер находит и прерывает выполнение
   /api/chat/stream при нажатии «Стоп» (/api/cancel). */
function makeRequestId() {
    if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    // Запасной вариант для окружений без crypto.randomUUID (не-secure context)
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

/* Текущий запрос: { controller, requestId }. Клавиша «Стоп» видна, пока он
   в полёте — и для потока событий, и для блокирующего запроса. */
let activeRequest = null;

const stopBtn = document.getElementById('stopBtn');

function endRequest() {
    activeRequest = null;
    stopBtn.hidden = true;
}

/* «Стоп»: обрываем чтение потока и отдельно просим сервер убить вызов LLM.
   Ответ /api/cancel не важен — ошибки игнорируем. */
stopBtn.addEventListener('click', () => {
    if (!activeRequest) return;
    const { controller, requestId } = activeRequest;
    activeRequest = null;
    controller.abort();
    fetch('/api/cancel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requestId }),
    }).catch(() => {});
    hideTyping();
    addSystemMessage('Остановлено пользователем');
    setDirective('fault', 'Передача остановлена');
});

/* ---------- Передача запроса ---------- */

/* Общая часть обоих путей: готовый ChatResponse — в журнал, сессия в настройки */
function applyChatResponse(data) {
    hideTyping();
    addBotMessage(data.reply, data, data.exchanges || []);
    // Сервер подтвердил сессию диалога — запоминаем для следующих запросов
    if (data.sessionId) {
        sessionId = data.sessionId;
        saveSettings();
    }
    // Факты из ответа (стратегия «Факты») — в панель, если она на виду
    updateFactsPanel(data);
    // Ветки: авто-форк мог произойти (кадр checkpoint) или смениться активная
    // ветка. Отзыв по стратегии, не только по branchId: после первого форка
    // корень остаётся активным и branchId в ответе держится null
    if (data.branchId != null) {
        activeBranchId = data.branchId;
    }
    if (data.branchId != null || currentStrategy === 'branching') {
        loadBranches(sessionId);
    }
    setDirective('ready', 'Передача завершена');
    /* Перечень сессий устарел — время и число записей изменились.
       Итог потока приходит сюда же, отдельный вызов не нужен. */
    loadSessions();
}

/* Резервный путь: старый блокирующий запрос, когда поток событий недоступен */
async function sendChatFallback(requestData) {
    const signal = activeRequest ? activeRequest.controller.signal : null;
    try {
        const res = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(requestData),
            ...(signal ? { signal } : {}),
        });

        const data = await res.json();

        if (!res.ok) {
            setDirective('fault', data.reply || 'Произошла ошибка');
        } else {
            applyChatResponse(data);
        }
    } catch (err) {
        // «Стоп» уже показал «Остановлено пользователем» — тихо выходим
        if (err && err.name === 'AbortError') return;
        hideTyping();
        setDirective('fault', 'Не удалось связаться с сервером: ' + err.message);
    }
}

/* Разбор кадра SSE: строки «data: <JSON>»; комментарии «:…» пропускаем.
   Возвращает true, если кадр оказался завершающим (final/cancelled/error). */
function parseStreamFrame(frame, handleEvent) {
    let finished = false;
    for (const rawLine of frame.split('\n')) {
        const line = rawLine.replace(/\r$/, '');
        if (line.startsWith(':')) continue;
        if (!line.startsWith('data:')) continue;

        const payload = line.slice(5).trim();
        if (!payload) continue;

        let event;
        try {
            event = JSON.parse(payload);
        } catch (err) {
            continue; // битый кадр — пропускаем, ждём следующий
        }
        if (event && typeof event === 'object' && handleEvent(event)) {
            finished = true;
        }
    }
    return finished;
}

/* Живой поток: POST /api/chat/stream, кадры SSE разбираются по мере прихода.
   Ход агента растёт в живой трассе под лампами; итоговый ChatResponse
   рендерится обычной записью журнала. Если до первого кадра поток недоступен
   (не 2xx, сеть, обрыв) — тот же запрос уходит по старому блокирующему пути. */
async function sendChatStream(requestData, requestId) {
    const controller = new AbortController();
    activeRequest = { controller, requestId };
    stopBtn.hidden = false;

    let live = null;
    let gotFrame = false;

    /* Живая трасса заводится лениво — по первому событию агента */
    const ensureLiveTrace = () => {
        if (!live) {
            live = createLiveTrace();
            document.getElementById('typing')?.querySelector('.entry')?.appendChild(live.wrap);
        }
        return live;
    };

    const handleEvent = (event) => {
        switch (event.type) {
            case 'status':
                setStreamStatus(typeof event.text === 'string' ? event.text : '');
                return false;
            case 'agent':
                if (event.event && typeof event.event === 'object') {
                    appendLiveAgentEvent(ensureLiveTrace(), event.event);
                }
                return false;
            case 'checkpoint':
                /* Развилка: служебная запись в журнал и перечитывание чипов.
                   Активной строкой остаётся корень — активная ветка меняется
                   только явной активацией. Кадр НЕ завершает поток: за ним
                   придёт final (семантика status-кадра) */
                addSystemMessage('─ Развилка: создана '
                    + (typeof event.label === 'string' && event.label ? event.label : 'ветка') + ' ─');
                loadBranches(sessionId);
                return false;
            case 'final':
                hideTyping();
                if (event.response && typeof event.response === 'object') {
                    applyChatResponse(event.response);
                } else {
                    setDirective('fault', 'Сервер прислал некорректный итоговый ответ');
                }
                return true;
            case 'cancelled':
                hideTyping();
                addSystemMessage('Остановлено пользователем');
                setDirective('fault', 'Передача остановлена');
                return true;
            case 'error':
                hideTyping();
                setDirective('fault', event.message || 'Произошла ошибка');
                return true;
            default:
                return false;
        }
    };

    try {
        const res = await fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ...requestData, requestId }),
            signal: controller.signal,
        });

        if (!res.ok || !res.body) {
            await sendChatFallback(requestData);
            return;
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let finished = false;

        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            // Кадры разделены пустой строкой; обрыв между чанками держим в буфере
            let sep;
            while (!finished && (sep = buffer.indexOf('\n\n')) !== -1) {
                gotFrame = true;
                finished = parseStreamFrame(buffer.slice(0, sep), handleEvent);
                buffer = buffer.slice(sep + 2);
            }
            if (finished) {
                await reader.cancel().catch(() => {});
                return;
            }
        }

        // Хвост без завершающего \n\n: по спецификации SSE доставляется в конце потока
        buffer += decoder.decode();
        if (buffer.trim()) {
            gotFrame = true;
            finished = parseStreamFrame(buffer, handleEvent);
        }

        if (!gotFrame) {
            await sendChatFallback(requestData);
            return;
        }
        if (!finished) {
            hideTyping();
            setDirective('fault', 'Сервер завершил передачу без итогового ответа');
        }
    } catch (err) {
        // «Стоп» уже показал «Остановлено пользователем» — тихо выходим
        if (err && err.name === 'AbortError') return;
        if (gotFrame) {
            hideTyping();
            setDirective('fault', 'Связь с сервером прервалась во время передачи: ' + err.message);
        } else {
            // Ни одного кадра не пришло — уходим в блокирующий запрос
            await sendChatFallback(requestData);
        }
    }
}

/* ---------- Клавиша «Передать» ---------- */

form.addEventListener('submit', async (e) => {
    e.preventDefault();

    const message = input.value.trim();
    if (!message || sendBtn.disabled) return;

    const mirrorOn = mirrorToggle.getAttribute('aria-pressed') === 'true';

    addMessage(message);
    input.value = '';
    sendBtn.disabled = true;
    input.disabled = true;
    showTyping();
    const { reasoningMode, thinking, judge, contextStrategy, temperature, maxTokens, topP, stop, model } = readSettings();
    const selected = splitModelValue(model || '');
    setDirective('busy', mirrorOn
        ? 'Идёт сравнение · пара параллельных передач'
        : `Идёт обработка · ${MODE_LABELS[currentMode]}${judge ? ' · проверка судьёй' : ''}`);

    const commonRequest = {
        message,
        reasoningMode,
        provider: selected.provider,
        model: selected.model,
        thinkingEnabled: thinking,
        maxTokens,
        temperature,
        topP,
        stop: stop || null,
    };

    try {
        if (mirrorOn) {
            // Зеркало: основной диалог и его счётчики не затрагиваются
            await runMirrorExchange(commonRequest);
        } else {
            const requestData = {
                ...commonRequest,
                sessionId: sessionId || null,
                judgeEnabled: judge,
                contextStrategy,
            };
            // «Ветки»: активная ветка уходит в запрос, чтобы модель продолжала
            // её контекст. При null (активна исходная история) поле не шлём
            if (contextStrategy === 'branching' && sessionId && activeBranchId) {
                requestData.branchId = activeBranchId;
            }
            // Живой поток хода агента; при его недоступности — блокирующий запрос
            await sendChatStream(requestData, makeRequestId());
        }
    } finally {
        hideTyping();
        endRequest();
        sendBtn.disabled = false;
        input.disabled = false;
        input.focus();
    }
});
