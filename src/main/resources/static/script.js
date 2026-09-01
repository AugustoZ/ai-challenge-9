const chat = document.getElementById('chat');
const form = document.getElementById('chatForm');
const input = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const status = document.getElementById('status');
const tokenCounter = document.getElementById('tokenCounter');
const maxTokensSelect = document.getElementById('maxTokens');

// Идентификатор сессии: постоянный для одной вкладки (localStorage), чтобы
// сервер сохранял историю диалога между запросами.
function getSessionId() {
    let id = localStorage.getItem('aiweb_session_id');
    if (!id) {
        id = crypto.randomUUID ? crypto.randomUUID() : ('s-' + Date.now() + '-' + Math.random().toString(36).slice(2));
        localStorage.setItem('aiweb_session_id', id);
    }
    return id;
}

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

    try {
        const maxTokens = maxTokensSelect.value ? Number(maxTokensSelect.value) : null;

        const res = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message, maxTokens, sessionId: getSessionId() })
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
