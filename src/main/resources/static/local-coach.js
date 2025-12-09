// Local Coach - JavaScript for Local LLM Chat
let sessionId = generateSessionId();
let messageCount = 0;
let isLoading = false;
let availableModels = [];
let selectedModel = null;

// DOM Elements
const chatForm = document.getElementById('chat-form');
const userInput = document.getElementById('user-input');
const sendButton = document.getElementById('send-button');
const clearButton = document.getElementById('clear-button');
const messagesContainer = document.getElementById('messages');
const loadingIndicator = document.getElementById('loading');
const messageCountEl = document.getElementById('message-count');
const statusText = document.getElementById('status-text');
const statusIndicator = document.getElementById('status-indicator');
const modelSelect = document.getElementById('model-select');

// Generate unique session ID
function generateSessionId() {
    return 'local-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9);
}

// Load available models
async function loadModels() {
    try {
        const response = await fetch('/local-coach/models');
        const data = await response.json();

        if (data.models && data.models.length > 0) {
            availableModels = data.models;

            // Clear and populate select
            modelSelect.innerHTML = '';
            data.models.forEach((model, index) => {
                const option = document.createElement('option');
                option.value = model;
                option.textContent = model;
                modelSelect.appendChild(option);
            });

            // Select first model by default
            selectedModel = data.models[0];
            modelSelect.value = selectedModel;

            console.log(`Loaded ${data.models.length} models, selected: ${selectedModel}`);
        } else {
            modelSelect.innerHTML = '<option value="">Нет доступных моделей</option>';
            displayMessage('assistant', '⚠️ Модели не найдены. Убедитесь, что LM Studio запущен и модель загружена.');
        }
    } catch (error) {
        console.error('Error loading models:', error);
        modelSelect.innerHTML = '<option value="">Ошибка загрузки моделей</option>';
    }
}

// Check if Local Coach is available
async function checkStatus() {
    try {
        const response = await fetch('/local-coach/status');
        const data = await response.json();

        if (data.available) {
            statusText.textContent = 'Готов';
            statusIndicator.style.background = '#10B981';

            // Load models
            await loadModels();
        } else {
            statusText.textContent = 'Недоступен';
            statusIndicator.style.background = '#EF4444';
            statusIndicator.style.animation = 'none';

            // Show error message
            displayMessage('assistant', data.message || 'Локальная LLM не настроена. Проверьте настройки LOCAL_LLM_URL в .env');
            sendButton.disabled = true;
            modelSelect.disabled = true;
        }
    } catch (error) {
        console.error('Error checking status:', error);
        statusText.textContent = 'Ошибка';
        statusIndicator.style.background = '#EF4444';
        statusIndicator.style.animation = 'none';
        displayMessage('assistant', 'Ошибка подключения к серверу');
        sendButton.disabled = true;
        modelSelect.disabled = true;
    }
}

// Display message in chat
function displayMessage(role, content) {
    const messageDiv = document.createElement('div');
    messageDiv.className = 'flex items-start space-x-4 message';

    const isUser = role === 'user';

    messageDiv.innerHTML = `
        <div class="rounded-full w-10 h-10 flex items-center justify-center flex-shrink-0 font-bold text-sm text-white"
             style="background: linear-gradient(135deg, ${isUser ? '#10B981' : 'var(--accent-color)'} 0%, ${isUser ? '#059669' : 'var(--accent-dark)'} 100%);">
            ${isUser ? 'Вы' : '🤖'}
        </div>
        <div class="flex-1">
            <p class="font-bold text-sm mb-2" style="color: ${isUser ? '#10B981' : 'var(--accent-color)'};">
                ${isUser ? 'Вы' : 'Локальный AI-тренер'}
            </p>
            <div class="${isUser ? 'user-message' : 'assistant-message'} p-5 rounded-2xl ${isUser ? 'rounded-tr-none' : 'rounded-tl-none'}">
                <div class="markdown-content">
                    ${isUser ? escapeHtml(content) : marked.parse(content)}
                </div>
            </div>
        </div>
    `;

    messagesContainer.appendChild(messageDiv);
    scrollToBottom();
}

// Escape HTML
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Scroll to bottom
function scrollToBottom() {
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

// Show/hide loading indicator
function setLoading(loading) {
    isLoading = loading;
    loadingIndicator.classList.toggle('hidden', !loading);
    sendButton.disabled = loading;
    userInput.disabled = loading;

    if (loading) {
        scrollToBottom();
    }
}

// Send message to local coach
async function sendMessage(message) {
    if (!message.trim() || isLoading) return;

    // Get selected model
    const currentModel = modelSelect.value || selectedModel;

    // Display user message
    displayMessage('user', message);
    userInput.value = '';
    setLoading(true);

    try {
        const response = await fetch('/local-coach/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                sessionId: sessionId,
                message: message,
                temperature: 0.7,
                model: currentModel
            })
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        // Display assistant response
        displayMessage('assistant', data.response);

        // Update message count
        messageCount = data.messageCount;
        messageCountEl.textContent = messageCount;

    } catch (error) {
        console.error('Error sending message:', error);
        displayMessage('assistant', '❌ Ошибка: ' + error.message);
    } finally {
        setLoading(false);
        userInput.focus();
    }
}

// Clear chat
async function clearChat() {
    if (!confirm('Вы уверены, что хотите очистить историю диалога?')) {
        return;
    }

    try {
        const response = await fetch('/local-coach/clear', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ sessionId: sessionId })
        });

        if (response.ok) {
            // Clear UI
            messagesContainer.innerHTML = '';

            // Add welcome message
            displayMessage('assistant', 'История диалога очищена! Начнем новый разговор? 💬');

            // Reset counter
            messageCount = 0;
            messageCountEl.textContent = messageCount;

            // Generate new session ID
            sessionId = generateSessionId();
        }
    } catch (error) {
        console.error('Error clearing chat:', error);
        alert('Ошибка при очистке диалога: ' + error.message);
    }
}

// Event Listeners
chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const message = userInput.value.trim();
    if (message) {
        sendMessage(message);
    }
});

clearButton.addEventListener('click', clearChat);

// Model selection change handler
modelSelect.addEventListener('change', (e) => {
    selectedModel = e.target.value;
    console.log('Model changed to:', selectedModel);
});

// Auto-focus input
userInput.focus();

// Check status on load
checkStatus();
