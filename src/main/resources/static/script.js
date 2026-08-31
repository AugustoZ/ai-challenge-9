const chat = document.getElementById('chat');
const form = document.getElementById('chatForm');
const input = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const status = document.getElementById('status');

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
        const res = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message })
        });

        const data = await res.json();

        if (!res.ok) {
            showStatus(data.reply || 'Произошла ошибка');
        } else {
            hideTyping();
            addMessage('bot', data.reply);
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
