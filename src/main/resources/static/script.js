const chat = document.getElementById('chat');
const form = document.getElementById('chatForm');
const input = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const status = document.getElementById('status');
const tokenCounter = document.getElementById('tokenCounter');

// Управление параметрами модели. Числовое значение каждой пары — источник истины,
// ползунок отображает его (браузер округляет до своего шага).
const settingsControls = {
    temperature: {
        range: document.getElementById('temperatureRange'),
        number: document.getElementById('temperature'),
        value: 0.7,
    },
    maxTokens: {
        range: document.getElementById('maxTokensRange'),
        number: document.getElementById('maxTokens'),
        value: 1024,
    },
    topP: {
        range: document.getElementById('topPRange'),
        number: document.getElementById('topP'),
        value: 1.0,
    },
};
const stopInput = document.getElementById('stopSeq');
const resetBtn = document.getElementById('resetBtn');

const reasoningModeSelect = document.getElementById('reasoningMode');
const reasoningHint = document.getElementById('reasoningHint');

// Подсказка под выпадающим списком обновляется вместе с выбранным способом рассуждения
const REASONING_HINTS = {
    direct: 'Сугубо сухой и прямолинейный ответ без лишних инструкций',
    step_by_step: 'Подробно расписывается способ решения и получения ответа',
    prompt_to_prompt: 'Вывод — готовый промт, который передаётся в LLM для решения задачи',
    team: 'Архитектор, Инженер и Исследователь решают задачу, затем — сравнение ответов и саммари',
};

const sidebar = document.getElementById('settings');
const settingsToggle = document.getElementById('settingsToggle');
const settingsClose = document.getElementById('settingsClose');
const sidebarBackdrop = document.getElementById('sidebarBackdrop');

const SETTINGS_STORAGE_KEY = 'ai-assistant.settings.v1';
const DEFAULTS = { reasoningMode: 'direct', temperature: 0.7, maxTokens: 1024, topP: 1.0, stop: '' };

let sessionTotalTokens = 0;

// Преобразует markdown в HTML и подсвечивает блоки кода
function renderMarkdown(text) {
    if (typeof marked !== 'undefined') {
        const html = marked.parse(text);
        return html
            .replace(/<pre><code class="language-([^"]+)">/g, '<pre><code>')
            .replace(/<code>/g, '<code>');
    }
    return text;
}

// Подсветка кода после вставки HTML
function highlightCode(container) {
    if (typeof hljs !== 'undefined') {
        container.querySelectorAll('pre code').forEach((block) => {
            hljs.highlightElement(block);
        });
    }
}

// Добавление сообщения в чат
function addMessage(sender, text) {
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${sender}`;

    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text;

    messageDiv.appendChild(bubble);
    chat.appendChild(messageDiv);
    chat.scrollTop = chat.scrollHeight;
    return messageDiv;
}

// Добавление ответа бота с подписью о потраченных токенах
function addBotMessage(text, tokenData) {
    const messageDiv = addMessage('bot');

    const bubble = messageDiv.querySelector('.bubble');
    bubble.classList.add('md');
    bubble.innerHTML = renderMarkdown(text);
    highlightCode(bubble);

    if (tokenData && tokenData.totalTokens != null) {
        const meta = document.createElement('div');
        meta.className = 'token-meta';
        meta.textContent =
            `Токены: промпт ${tokenData.promptTokens ?? 0} · ответ ${tokenData.completionTokens ?? 0} · всего ${tokenData.totalTokens}`;
        messageDiv.appendChild(meta);

        sessionTotalTokens += tokenData.totalTokens;
        updateTokenCounter();
    }

    return messageDiv;
}

// Обновление общего счётчика токенов за сессию
function updateTokenCounter() {
    tokenCounter.textContent = `Токены за сессию: ${sessionTotalTokens}`;
    tokenCounter.classList.remove('hidden');
}

// Индикатор "печатает..."
function showTyping() {
    const wrapper = document.createElement('div');
    wrapper.className = 'message bot';
    wrapper.id = 'typing';
    wrapper.innerHTML = `
        <div class="typing">
            <span></span><span></span><span></span>
        </div>
    `;
    chat.appendChild(wrapper);
    chat.scrollTop = chat.scrollHeight;
}

function hideTyping() {
    document.getElementById('typing')?.remove();
}

function showStatus(msg) {
    status.textContent = msg;
    status.classList.remove('hidden');
}

function hideStatus() {
    status.classList.add('hidden');
}

/* ---------- Настройки модели ---------- */

function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
}

// Заполненная часть дорожки ползунка (WebKit; в Firefox работает ::-moz-range-progress)
function updateFill(ctrl) {
    const min = Number(ctrl.range.min);
    const max = Number(ctrl.range.max);
    const pct = max > min ? ((ctrl.value - min) / (max - min)) * 100 : 0;
    ctrl.range.style.setProperty('--fill', pct.toFixed(2) + '%');
}

function setControlValue(ctrl, value) {
    ctrl.value = clamp(value, Number(ctrl.range.min), Number(ctrl.range.max));
    ctrl.range.value = ctrl.value;
    ctrl.number.value = ctrl.value;
    updateFill(ctrl);
}

// Ползунок -> числовое поле (значение ползунка всегда валидно)
function syncFromRange(ctrl) {
    setControlValue(ctrl, Number(ctrl.range.value));
}

// Числовое поле -> ползунок. Пока пользователь печатает, текст поля не трогаем —
// нормализация (clamp/откат) произойдёт по blur или Enter.
function syncFromNumber(ctrl) {
    const raw = ctrl.number.value.trim();
    const value = Number(raw);
    if (raw === '' || !Number.isFinite(value)) return;
    setControlValue(ctrl, value);
}

function normalizeNumber(ctrl) {
    const raw = ctrl.number.value.trim();
    const value = Number(raw);
    if (raw === '' || !Number.isFinite(value)) {
        ctrl.number.value = ctrl.value;
    } else {
        setControlValue(ctrl, value);
    }
}

function readSettings() {
    return {
        reasoningMode: reasoningModeSelect.value,
        temperature: settingsControls.temperature.value,
        maxTokens: settingsControls.maxTokens.value,
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

    const storedMode = stored && stored.reasoningMode;
    reasoningModeSelect.value =
        storedMode && Object.prototype.hasOwnProperty.call(REASONING_HINTS, storedMode)
            ? storedMode
            : DEFAULTS.reasoningMode;
    reasoningHint.textContent = REASONING_HINTS[reasoningModeSelect.value];
}

for (const ctrl of Object.values(settingsControls)) {
    ctrl.range.addEventListener('input', () => syncFromRange(ctrl));
    ctrl.range.addEventListener('change', saveSettings);

    ctrl.number.addEventListener('input', () => syncFromNumber(ctrl));
    ctrl.number.addEventListener('change', () => {
        normalizeNumber(ctrl);
        saveSettings();
    });
}

stopInput.addEventListener('change', saveSettings);

reasoningModeSelect.addEventListener('change', () => {
    reasoningHint.textContent = REASONING_HINTS[reasoningModeSelect.value];
    saveSettings();
});

resetBtn.addEventListener('click', () => {
    for (const [key, ctrl] of Object.entries(settingsControls)) {
        setControlValue(ctrl, DEFAULTS[key]);
    }
    stopInput.value = '';
    reasoningModeSelect.value = DEFAULTS.reasoningMode;
    reasoningHint.textContent = REASONING_HINTS[reasoningModeSelect.value];
    saveSettings();
});

/* ---------- Мобильное меню (drawer) ---------- */

function openSettings() {
    sidebar.classList.add('open');
    sidebarBackdrop.classList.add('visible');
    settingsToggle.setAttribute('aria-expanded', 'true');
}

function closeSettings() {
    sidebar.classList.remove('open');
    sidebarBackdrop.classList.remove('visible');
    settingsToggle.setAttribute('aria-expanded', 'false');
}

settingsToggle.addEventListener('click', () => {
    if (sidebar.classList.contains('open')) closeSettings();
    else openSettings();
});

settingsClose.addEventListener('click', closeSettings);
sidebarBackdrop.addEventListener('click', closeSettings);

document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && sidebar.classList.contains('open')) closeSettings();
});

loadSettings();

/* ---------- Отправка сообщения ---------- */

form.addEventListener('submit', async (e) => {
    e.preventDefault();

    const message = input.value.trim();
    if (!message || sendBtn.disabled) return;

    hideStatus();
    addMessage('user', message);
    input.value = '';
    sendBtn.disabled = true;
    input.disabled = true;
    showTyping();

    const { reasoningMode, temperature, maxTokens, topP, stop } = readSettings();

    try {
        const res = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message,
                reasoningMode,
                maxTokens,
                temperature,
                topP,
                stop: stop || null,
            }),
        });

        const data = await res.json();

        if (!res.ok) {
            showStatus(data.reply || 'Произошла ошибка');
        } else {
            hideTyping();
            addBotMessage(data.reply, data);
        }
    } catch (err) {
        hideTyping();
        showStatus('Не удалось связаться с сервером: ' + err.message);
    } finally {
        hideTyping();
        sendBtn.disabled = false;
        input.disabled = false;
        input.focus();
    }
});
