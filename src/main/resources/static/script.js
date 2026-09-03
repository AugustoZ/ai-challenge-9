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
const resetBtn = document.getElementById('resetBtn');

/* Табло-аннунциаторы способа рассуждения */
const modeGroup = document.getElementById('reasoningMode');
const modeButtons = Array.from(modeGroup.querySelectorAll('.annunciator'));
const reasoningHint = document.getElementById('reasoningHint');
const modeRepeater = document.getElementById('modeRepeater');

const MODE_LABELS = {
    default: 'По умолчанию',
    direct: 'Прямой ответ',
    step_by_step: 'Пошаговое решение',
    prompt_to_prompt: 'Prompt-to-Prompt',
    team: 'Команда',
};

const SETTINGS_STORAGE_KEY = 'ai-assistant.settings.v1';
const DEFAULTS = {
    reasoningMode: 'default', temperature: 0.7, maxTokens: 1024, topP: 1.0, stop: '', thinking: true,
};

let currentMode = DEFAULTS.reasoningMode;
let sessionTotalTokens = 0;

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
    if (tokenData && tokenData.totalTokens != null) {
        parts.push({ fuel: true, text: `Топливо ${tokenData.promptTokens ?? 0}/${tokenData.completionTokens ?? 0}/${tokenData.totalTokens}` });
    }
    entry.appendChild(buildEntryHead('Ответ', parts));

    const bubble = document.createElement('div');
    bubble.className = 'bubble md';
    bubble.innerHTML = renderMarkdown(text);
    highlightCode(bubble);

    entry.appendChild(bubble);

    if (exchanges.length) {
        entry.appendChild(buildRawExchange(exchanges));
    }

    messageDiv.appendChild(entry);
    chat.appendChild(messageDiv);
    chat.scrollTop = chat.scrollHeight;

    if (tokenData && tokenData.totalTokens != null) {
        sessionTotalTokens += tokenData.totalTokens;
        updateTokenCounter();
    }
    return messageDiv;
}

/* Общий счётчик топлива за сессию */
function updateTokenCounter() {
    tokenCounter.textContent = `Топливо ${sessionTotalTokens}`;
    tokenCounter.classList.remove('hidden');
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
        </div>
    `;
    chat.appendChild(wrapper);
    chat.scrollTop = chat.scrollHeight;
}

function hideTyping() {
    document.getElementById('typing')?.remove();
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
    return {
        reasoningMode: currentMode,
        thinking: thinkingToggle.getAttribute('aria-pressed') === 'true',
        temperature: settingsControls.temperature.value,
        maxTokens: Math.round(settingsControls.maxTokens.value),
        topP: settingsControls.topP.value,
        stop: stopInput.value.trim(),
    };
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

    const thinking = stored && typeof stored.thinking === 'boolean' ? stored.thinking : DEFAULTS.thinking;
    setThinking(thinking, { save: false });

    setMode(stored && stored.reasoningMode, { save: false });
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
    setMode(DEFAULTS.reasoningMode);
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
        item.appendChild(buildPayloadBlock(ex.payload));
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

loadSettings();

/* ---------- Передача запроса ---------- */

form.addEventListener('submit', async (e) => {
    e.preventDefault();

    const message = input.value.trim();
    if (!message || sendBtn.disabled) return;

    addMessage(message);
    input.value = '';
    sendBtn.disabled = true;
    input.disabled = true;
    showTyping();
    setDirective('busy', `Идёт обработка · ${MODE_LABELS[currentMode]}`);

    const { reasoningMode, thinking, temperature, maxTokens, topP, stop } = readSettings();

    try {
        const res = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message,
                reasoningMode,
                thinkingEnabled: thinking,
                maxTokens,
                temperature,
                topP,
                stop: stop || null,
            }),
        });

        const data = await res.json();

        if (!res.ok) {
            setDirective('fault', data.reply || 'Произошла ошибка');
        } else {
            hideTyping();
            addBotMessage(data.reply, data, data.exchanges || []);
            setDirective('ready', 'Передача завершена');
        }
    } catch (err) {
        hideTyping();
        setDirective('fault', 'Не удалось связаться с сервером: ' + err.message);
    } finally {
        hideTyping();
        sendBtn.disabled = false;
        input.disabled = false;
        input.focus();
    }
});
