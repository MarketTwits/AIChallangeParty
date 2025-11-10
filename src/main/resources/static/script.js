const messagesContainer = document.getElementById('messages');
const chatForm = document.getElementById('chat-form');
const userInput = document.getElementById('user-input');
const loadingIndicator = document.getElementById('loading');
const quickPrompts = document.querySelectorAll('.quick-prompt');
const limitModal = document.getElementById('limit-modal');
const closeModalBtn = document.getElementById('close-modal');
const remainingCountEl = document.getElementById('remaining-count');
const messageCounterEl = document.getElementById('message-counter');
const chatContainer = document.getElementById('chat-container');
const chatBackdrop = document.getElementById('chat-backdrop');
const expandIndicator = document.querySelector('.expand-indicator');

let remainingMessages = 10;
let currentCoachStyle = 'default';

function loadCoachStyle() {
    const saved = localStorage.getItem('coachStyle');
    if (saved) {
        currentCoachStyle = saved;
        const buttons = document.querySelectorAll('.coach-style-btn');
        buttons.forEach(btn => {
            if (btn.dataset.style === currentCoachStyle) {
                btn.classList.add('active');
            } else {
                btn.classList.remove('active');
            }
        });
    }
}

function setCoachStyle(style) {
    if (currentCoachStyle !== style && messagesContainer.children.length > 1) {
        const confirmed = confirm('При смене стиля тренера чат будет очищен. Продолжить?');
        if (!confirmed) {
            return;
        }
        while (messagesContainer.children.length > 1) {
            messagesContainer.removeChild(messagesContainer.lastChild);
        }
    }

    currentCoachStyle = style;
    localStorage.setItem('coachStyle', style);
    const buttons = document.querySelectorAll('.coach-style-btn');
    buttons.forEach(btn => {
        if (btn.dataset.style === style) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });
}

loadCoachStyle();

function getOrCreateSessionId() {
    let sessionId = sessionStorage.getItem('sessionId');
    if (!sessionId) {
        sessionId = 'session_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
        sessionStorage.setItem('sessionId', sessionId);
    }
    return sessionId;
}

const sessionId = getOrCreateSessionId();

let isExpanded = false;

function toggleChatExpand(expand) {
    isExpanded = expand;

    if (expand) {
        chatContainer.classList.add('expanded');
        chatBackdrop.classList.add('active');
        document.body.style.overflow = 'hidden';

        messagesContainer.style.maxHeight = 'calc(100vh - 320px)';
        expandIndicator.innerHTML = `
            <svg class="w-4 h-4 inline-block mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
            </svg>
            Свернуть
        `;
    } else {
        chatContainer.classList.remove('expanded');
        chatBackdrop.classList.remove('active');
        document.body.style.overflow = '';

        messagesContainer.style.maxHeight = '400px';
        expandIndicator.innerHTML = `
            <svg class="w-4 h-4 inline-block mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4"></path>
            </svg>
            Развернуть
        `;
    }

    setTimeout(scrollToBottom, 100);
}

expandIndicator.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleChatExpand(!isExpanded);
});

chatBackdrop.addEventListener('click', () => {
    toggleChatExpand(false);
});

chatContainer.addEventListener('click', (e) => {
    if (!isExpanded && e.target === chatContainer) {
        toggleChatExpand(true);
    }
});

document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && isExpanded) {
        toggleChatExpand(false);
    }
});

marked.setOptions({
    breaks: true,
    gfm: true
});

chatForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const message = userInput.value.trim();
    if (!message) return;

    addMessage('user', message);
    userInput.value = '';
    userInput.disabled = true;
    showLoading();

    try {
        const response = await fetch('/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                message,
                sessionId,
                coachStyle: currentCoachStyle
            })
        });

        if (!response.ok) {
            throw new Error('Ошибка сервера');
        }

        const data = await response.json();
        hideLoading();

        if (data.response === 'LIMIT_EXCEEDED') {
            showLimitModal();
            userInput.disabled = true;
            return;
        }

        addMessage('assistant', data.response, data.structuredResponse);

        if (data.remainingMessages !== undefined && data.remainingMessages !== null) {
            remainingMessages = data.remainingMessages;
            updateMessageCounter();
        }

        if (remainingMessages <= 0) {
            showLimitModal();
            userInput.disabled = true;
        }

    } catch (error) {
        hideLoading();
        addMessage('assistant', 'Извините, произошла ошибка. Попробуйте снова.');
        console.error('Error:', error);
    } finally {
        if (remainingMessages > 0) {
            userInput.disabled = false;
            userInput.focus();
        }
    }
});

quickPrompts.forEach(button => {
    button.addEventListener('click', () => {
        const text = button.textContent.trim().replace(/^[🏃🎯🏅⚡]\s*/, '');
        userInput.value = text;
        userInput.focus();
    });
});

closeModalBtn.addEventListener('click', () => {
    limitModal.classList.add('hidden');
});

limitModal.addEventListener('click', (e) => {
    if (e.target === limitModal) {
        limitModal.classList.add('hidden');
    }
});

document.querySelectorAll('.coach-style-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        setCoachStyle(btn.dataset.style);
    });
});

function updateMessageCounter() {
    remainingCountEl.textContent = remainingMessages;

    if (remainingMessages <= 3) {
        messageCounterEl.style.background = 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)';
        messageCounterEl.style.boxShadow = '0 4px 15px rgba(239, 68, 68, 0.4), inset 0 1px 1px rgba(255, 255, 255, 0.2)';
    } else if (remainingMessages <= 5) {
        messageCounterEl.style.background = 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)';
        messageCounterEl.style.boxShadow = '0 4px 15px rgba(245, 158, 11, 0.4), inset 0 1px 1px rgba(255, 255, 255, 0.2)';
    }
}

function showLimitModal() {
    limitModal.classList.remove('hidden');
}

function addMessage(role, content, structuredResponse = null) {
    const messageWrapper = document.createElement('div');
    messageWrapper.className = `message ${role === 'user' ? 'flex justify-end' : ''}`;

    if (role === 'user') {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'user-message p-4 rounded-2xl rounded-tr-none max-w-3xl text-white font-medium';
        messageDiv.textContent = content;
        messageWrapper.appendChild(messageDiv);
    } else {
        const flexContainer = document.createElement('div');
        flexContainer.className = 'flex items-start space-x-4';

        const avatar = document.createElement('div');
        avatar.className = 'avatar rounded-full w-10 h-10 flex items-center justify-center flex-shrink-0 font-bold text-sm text-white';
        avatar.textContent = 'AI';

        const contentWrapper = document.createElement('div');
        contentWrapper.className = 'flex-1 max-w-4xl';

        const label = document.createElement('p');
        label.className = 'font-bold text-sm text-blue-400 mb-2';
        label.textContent = 'Виртуальный тренер';

        const messageDiv = document.createElement('div');
        messageDiv.className = 'assistant-message p-5 rounded-2xl rounded-tl-none';

        if (structuredResponse) {
            messageDiv.innerHTML = formatStructuredResponse(structuredResponse);
        } else {
            try {
                const jsonContent = JSON.parse(content);
                if (jsonContent.answer) {
                    messageDiv.innerHTML = formatStructuredResponse(jsonContent);
                } else {
                    messageDiv.innerHTML = formatJSON(jsonContent);
                }
            } catch {
                const markdownDiv = document.createElement('div');
                markdownDiv.className = 'markdown-content';
                markdownDiv.innerHTML = marked.parse(content);
                messageDiv.appendChild(markdownDiv);
            }
        }

        contentWrapper.appendChild(label);
        contentWrapper.appendChild(messageDiv);
        flexContainer.appendChild(avatar);
        flexContainer.appendChild(contentWrapper);
        messageWrapper.appendChild(flexContainer);
    }

    messagesContainer.appendChild(messageWrapper);
    scrollToBottom();
}

function formatStructuredResponse(response) {
    let html = '';

    if (response.tag) {
        const tagEmojis = {
            'greeting': '👋',
            'assessment': '📊',
            'plan': '📋',
            'recovery': '💚',
            'motivation': '🔥',
            'error': '⚠️'
        };
        const tagColors = {
            'greeting': 'bg-blue-500',
            'assessment': 'bg-green-500',
            'plan': 'bg-purple-500',
            'recovery': 'bg-pink-500',
            'motivation': 'bg-orange-500',
            'error': 'bg-red-500'
        };
        const emoji = tagEmojis[response.tag] || '💬';
        const tagColor = tagColors[response.tag] || 'bg-gray-500';
        html += `<div class="mb-3 flex items-center justify-between">`;
        html += `<span class="${tagColor} text-white px-3 py-1 rounded-full text-xs font-bold flex items-center gap-1">${emoji} ${response.tag}</span>`;
        if (response.answerTimestamp) {
            const date = new Date(response.answerTimestamp);
            const timeStr = date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
            html += `<span class="text-xs text-gray-400">${timeStr}</span>`;
        }
        html += `</div>`;
    }

    const answerText = response.answer
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0)
        .join('\n\n');

    const markdownDiv = document.createElement('div');
    markdownDiv.className = 'markdown-content';
    markdownDiv.innerHTML = marked.parse(answerText);
    html += markdownDiv.outerHTML;

    if (response.nextAction) {
        html += `<div class="mt-4 inline-block">`;
        html += `<div style="background: #E5F4FE; color: #2E3F6E;" class="px-4 py-2 rounded-lg shadow-md inline-flex items-center gap-2">`;
        html += `<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7l5 5m0 0l-5 5m5-5H6"></path></svg>`;
        html += `<span class="text-sm font-semibold">${response.nextAction}</span>`;
        html += `</div>`;
        html += `</div>`;
    }

    return html;
}

function formatJSON(obj) {
    if (typeof obj === 'string') {
        return obj;
    }

    let html = '';

    if (obj.week_1 || obj.week_2) {
        html += '<div class="space-y-4">';
        for (let week = 1; week <= 4; week++) {
            const weekKey = `week_${week}`;
            if (obj[weekKey]) {
                html += `<div class="bg-slate-900 bg-opacity-60 rounded-xl p-5 border border-blue-500 border-opacity-30 transition-all hover:border-opacity-50">`;
                html += `<h3 class="font-bold text-blue-400 mb-4 text-lg flex items-center">
                    <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"></path>
                    </svg>
                    Неделя ${week}
                </h3>`;
                html += '<div class="space-y-3 text-sm">';

                const weekData = typeof obj[weekKey] === 'string' ? JSON.parse(obj[weekKey]) : obj[weekKey];
                const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
                const daysRu = ['Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница', 'Суббота', 'Воскресенье'];
                const dayEmojis = ['💪', '🏃', '⚡', '🎯', '🔥', '🌟', '🏅'];

                days.forEach((day, index) => {
                    if (weekData[day]) {
                        html += `<div class="flex items-start p-3 bg-slate-800 bg-opacity-40 rounded-lg hover:bg-opacity-60 transition-all">`;
                        html += `<span class="text-xl mr-3">${dayEmojis[index]}</span>`;
                        html += `<div class="flex-1">
                            <div class="font-semibold text-blue-300 mb-1">${daysRu[index]}</div>
                            <div class="text-slate-300 leading-relaxed">${weekData[day]}</div>
                        </div>`;
                        html += `</div>`;
                    }
                });

                html += '</div></div>';
            }
        }
        if (obj.tips) {
            html += `<div class="mt-4 p-4 bg-blue-500 bg-opacity-10 rounded-xl border-l-4 border-blue-500">`;
            html += `<div class="flex items-start">
                <svg class="w-5 h-5 text-blue-400 mr-2 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"></path>
                </svg>
                <div>
                    <div class="font-semibold text-blue-400 mb-1">Совет</div>
                    <div class="text-slate-300">${obj.tips}</div>
                </div>
            </div>`;
            html += `</div>`;
        }
        html += '</div>';
    } else if (obj.level) {
        html += '<div class="space-y-3">';
        const levelEmoji = obj.level === 'beginner' ? '🌱' : obj.level === 'intermediate' ? '🏃' : '🏆';
        const levelText = obj.level === 'beginner' ? 'Новичок' : obj.level === 'intermediate' ? 'Средний' : 'Продвинутый';
        const levelColor = obj.level === 'beginner' ? 'text-green-400' : obj.level === 'intermediate' ? 'text-blue-400' : 'text-yellow-400';

        html += `<div class="bg-slate-900 bg-opacity-60 p-4 rounded-xl border border-blue-500 border-opacity-30">
            <div class="flex items-center">
                <span class="text-2xl mr-3">${levelEmoji}</span>
                <div>
                    <div class="text-sm text-slate-400">Уровень подготовки</div>
                    <div class="font-bold ${levelColor} text-lg">${levelText}</div>
                </div>
            </div>
        </div>`;

        if (obj.max_weekly_km) {
            html += `<div class="bg-slate-900 bg-opacity-60 p-4 rounded-xl border border-blue-500 border-opacity-30">
                <div class="flex items-center">
                    <svg class="w-6 h-6 text-blue-400 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"></path>
                    </svg>
                    <div>
                        <div class="text-sm text-slate-400">Максимальная нагрузка</div>
                        <div class="font-bold text-blue-300">${obj.max_weekly_km} км в неделю</div>
                    </div>
                </div>
            </div>`;
        }

        if (obj.recommended_days) {
            html += `<div class="bg-slate-900 bg-opacity-60 p-4 rounded-xl border border-blue-500 border-opacity-30">
                <div class="flex items-center">
                    <svg class="w-6 h-6 text-blue-400 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"></path>
                    </svg>
                    <div>
                        <div class="text-sm text-slate-400">Рекомендуемая частота</div>
                        <div class="font-bold text-blue-300">${obj.recommended_days} дней в неделю</div>
                    </div>
                </div>
            </div>`;
        }

        if (obj.advice) {
            html += `<div class="p-4 bg-blue-500 bg-opacity-10 rounded-xl border-l-4 border-blue-500">
                <div class="text-slate-300 leading-relaxed">${obj.advice}</div>
            </div>`;
        }
        html += '</div>';
    } else if (obj.nutrition) {
        html += '<div class="space-y-4">';

        const sections = [
            { key: 'nutrition', icon: '🥗', title: 'Питание', color: 'green' },
            { key: 'sleep', icon: '😴', title: 'Сон', color: 'purple' },
            { key: 'stretching', icon: '🧘', title: 'Растяжка', color: 'blue' },
            { key: 'injury_prevention', icon: '🛡️', title: 'Профилактика травм', color: 'red' }
        ];

        sections.forEach(section => {
            if (obj[section.key]) {
                html += `<div class="bg-slate-900 bg-opacity-60 rounded-xl p-5 border border-blue-500 border-opacity-30">
                    <div class="flex items-center mb-3">
                        <span class="text-2xl mr-3">${section.icon}</span>
                        <h4 class="font-bold text-blue-400 text-lg">${section.title}</h4>
                    </div>
                    <div class="text-slate-300 leading-relaxed">${obj[section.key]}</div>
                </div>`;
            }
        });

        html += '</div>';
    } else {
        html = `<pre class="text-sm bg-slate-900 bg-opacity-60 p-4 rounded-lg overflow-x-auto"><code>${JSON.stringify(obj, null, 2)}</code></pre>`;
    }

    return html;
}

function showLoading() {
    loadingIndicator.classList.remove('hidden');
    scrollToBottom();
}

function hideLoading() {
    loadingIndicator.classList.add('hidden');
}

function scrollToBottom() {
    setTimeout(() => {
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }, 100);
}

userInput.focus();

function addTypingEffect(element, text, speed = 20) {
    return new Promise((resolve) => {
        let i = 0;
        element.textContent = '';

        const interval = setInterval(() => {
            if (i < text.length) {
                element.textContent += text.charAt(i);
                i++;
            } else {
                clearInterval(interval);
                resolve();
            }
        }, speed);
    });
}

function addButtonClickEffect(button) {
    button.style.transform = 'scale(0.95)';
    setTimeout(() => {
        button.style.transform = '';
    }, 150);
}

quickPrompts.forEach(button => {
    button.addEventListener('mousedown', () => {
        addButtonClickEffect(button);
    });
});

const sendButton = document.querySelector('.send-button');
sendButton.addEventListener('mousedown', () => {
    addButtonClickEffect(sendButton);
});

userInput.addEventListener('input', () => {
    userInput.style.height = 'auto';
    userInput.style.height = userInput.scrollHeight + 'px';
});

let isTyping = false;
let typingTimeout;

userInput.addEventListener('keydown', (e) => {
    if (!isTyping) {
        isTyping = true;
        userInput.style.borderColor = 'var(--primary-color)';
    }

    clearTimeout(typingTimeout);
    typingTimeout = setTimeout(() => {
        isTyping = false;
        userInput.style.borderColor = '';
    }, 1000);

    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        chatForm.dispatchEvent(new Event('submit'));
    }
});

function addMessageWithAnimation(role, content) {
    const messageWrapper = document.createElement('div');
    messageWrapper.className = `message ${role === 'user' ? 'flex justify-end' : ''}`;
    messageWrapper.style.opacity = '0';
    messageWrapper.style.transform = 'translateY(20px)';

    if (role === 'user') {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'user-message p-4 rounded-2xl rounded-tr-none max-w-3xl text-white font-medium';
        messageDiv.textContent = content;
        messageWrapper.appendChild(messageDiv);
    } else {
        const flexContainer = document.createElement('div');
        flexContainer.className = 'flex items-start space-x-4';

        const avatar = document.createElement('div');
        avatar.className = 'avatar rounded-full w-10 h-10 flex items-center justify-center flex-shrink-0 font-bold text-sm text-white';
        avatar.textContent = 'AI';

        const contentWrapper = document.createElement('div');
        contentWrapper.className = 'flex-1 max-w-4xl';

        const label = document.createElement('p');
        label.className = 'font-bold text-sm text-blue-400 mb-2';
        label.textContent = 'Виртуальный тренер';

        const messageDiv = document.createElement('div');
        messageDiv.className = 'assistant-message p-5 rounded-2xl rounded-tl-none';

        try {
            const jsonContent = JSON.parse(content);
            messageDiv.innerHTML = formatJSON(jsonContent);
        } catch {
            const markdownDiv = document.createElement('div');
            markdownDiv.className = 'markdown-content';
            markdownDiv.innerHTML = marked.parse(content);
            messageDiv.appendChild(markdownDiv);
        }

        contentWrapper.appendChild(label);
        contentWrapper.appendChild(messageDiv);
        flexContainer.appendChild(avatar);
        flexContainer.appendChild(contentWrapper);
        messageWrapper.appendChild(flexContainer);
    }

    messagesContainer.appendChild(messageWrapper);

    requestAnimationFrame(() => {
        messageWrapper.style.transition = 'all 0.4s cubic-bezier(0.4, 0, 0.2, 1)';
        messageWrapper.style.opacity = '1';
        messageWrapper.style.transform = 'translateY(0)';
    });

    scrollToBottom();
}

let messageQueue = [];
let isProcessingQueue = false;

async function processMessageQueue() {
    if (isProcessingQueue || messageQueue.length === 0) return;

    isProcessingQueue = true;

    while (messageQueue.length > 0) {
        const { role, content } = messageQueue.shift();
        addMessageWithAnimation(role, content);
        await new Promise(resolve => setTimeout(resolve, 100));
    }

    isProcessingQueue = false;
}

function queueMessage(role, content) {
    messageQueue.push({ role, content });
    processMessageQueue();
}

let currentReasoningMode = 'direct';
let reasoningSessionIds = {
    direct: 'reasoning_direct_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9),
    stepByStep: 'reasoning_step_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9),
    aiPrompt: 'reasoning_prompt_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9),
    experts: 'reasoning_experts_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9)
};

let reasoningMessageHistory = {
    direct: [],
    stepByStep: [],
    aiPrompt: [],
    experts: []
};

function initReasoningTab() {
    console.log('Initializing reasoning tab...');
    const tabTraining = document.getElementById('tab-training');
    const tabReasoning = document.getElementById('tab-reasoning');
    const trainingContent = document.getElementById('training-content');
    const reasoningContent = document.getElementById('reasoning-content');

    console.log('Elements:', {
        tabTraining: !!tabTraining,
        tabReasoning: !!tabReasoning,
        trainingContent: !!trainingContent,
        reasoningContent: !!reasoningContent
    });

    if (!tabTraining || !tabReasoning || !trainingContent || !reasoningContent) {
        console.error('Tab elements not found!', {
            tabTraining, tabReasoning, trainingContent, reasoningContent
        });
        return;
    }

    function switchTab(tab) {
        console.log('Switching to tab:', tab);
        if (tab === 'training') {
            tabTraining.classList.add('active-tab');
            tabReasoning.classList.remove('active-tab');
            trainingContent.classList.add('active');
            reasoningContent.classList.remove('active');
        } else {
            tabReasoning.classList.add('active-tab');
            tabTraining.classList.remove('active-tab');
            reasoningContent.classList.add('active');
            trainingContent.classList.remove('active');
        }
        console.log('Tab switched successfully');
    }

    tabTraining.addEventListener('click', () => {
        console.log('Training tab clicked');
        switchTab('training');
    });
    tabReasoning.addEventListener('click', () => {
        console.log('Reasoning tab clicked');
        switchTab('reasoning');
    });

    console.log('Event listeners attached successfully');
}

document.addEventListener('DOMContentLoaded', initReasoningTab);

function initReasoningChat() {
    const reasoningModeBtns = document.querySelectorAll('.reasoning-mode-btn');
    const temperatureSlider = document.getElementById('temperature-slider');
    const temperatureValue = document.getElementById('temperature-value');
    const clearReasoningBtn = document.getElementById('clear-reasoning-btn');

    let currentTemperature = 1.0;

    if (temperatureSlider && temperatureValue) {
        temperatureSlider.addEventListener('input', (e) => {
            currentTemperature = parseFloat(e.target.value);
            temperatureValue.textContent = currentTemperature.toFixed(1);
        });
    }

    if (clearReasoningBtn) {
        clearReasoningBtn.addEventListener('click', async () => {
            const confirmed = confirm('Вы уверены, что хотите очистить диалог?');
            if (!confirmed) return;

            try {
                await fetch('/reasoning-chat/clear', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({
                        sessionId: reasoningSessionIds[currentReasoningMode]
                    })
                });

                const reasoningMessagesContainer = document.getElementById('reasoning-messages');
                while (reasoningMessagesContainer.children.length > 1) {
                    reasoningMessagesContainer.removeChild(reasoningMessagesContainer.lastChild);
                }

                reasoningMessageHistory[currentReasoningMode] = [];

                alert('Диалог успешно очищен!');
            } catch (error) {
                console.error('Error clearing reasoning chat:', error);
                alert('Ошибка при очистке диалога');
            }
        });
    }

    reasoningModeBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            reasoningModeBtns.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');

            const newMode = btn.dataset.mode;
            if (newMode !== currentReasoningMode) {
                currentReasoningMode = newMode;
                restoreReasoningHistory(newMode);
            }
        });
    });

    const reasoningForm = document.getElementById('reasoning-form');
    const reasoningInput = document.getElementById('reasoning-input');
    const reasoningMessagesContainer = document.getElementById('reasoning-messages');
    const reasoningLoadingIndicator = document.getElementById('reasoning-loading');

    if (!reasoningForm || !reasoningInput || !reasoningMessagesContainer || !reasoningLoadingIndicator) {
        console.error('Reasoning chat elements not found');
        return;
    }

    function addReasoningMessage(role, content, saveToHistory = true) {
        const messageEl = document.createElement('div');
        messageEl.className = 'flex items-start space-x-4 message';

        if (role === 'user') {
            messageEl.innerHTML = `
                <div class="flex-1"></div>
                <div class="user-message p-4 rounded-2xl rounded-tr-none max-w-2xl">
                    <p class="text-white leading-relaxed">${escapeHtml(content)}</p>
                </div>
                <div class="avatar rounded-full w-10 h-10 flex items-center justify-center flex-shrink-0 font-bold text-sm text-white">
                    U
                </div>
            `;
        } else {
            const formattedContent = typeof marked !== 'undefined' ? marked.parse(content) : content.replace(/\n/g, '<br>');
            messageEl.innerHTML = `
                <div class="avatar rounded-full w-10 h-10 flex items-center justify-center flex-shrink-0 font-bold text-sm text-white">
                    AI
                </div>
                <div class="flex-1">
                    <p class="font-bold text-sm mb-2" style="color: var(--primary-color);">AI Ассистент</p>
                    <div class="assistant-message p-5 rounded-2xl rounded-tl-none">
                        <div class="markdown-content text-gray-700 leading-relaxed">${formattedContent}</div>
                    </div>
                </div>
            `;
        }

        reasoningMessagesContainer.appendChild(messageEl);
        reasoningMessagesContainer.scrollTop = reasoningMessagesContainer.scrollHeight;

        if (saveToHistory) {
            reasoningMessageHistory[currentReasoningMode].push({role, content});
        }
    }

    function restoreReasoningHistory(mode) {
        while (reasoningMessagesContainer.children.length > 1) {
            reasoningMessagesContainer.removeChild(reasoningMessagesContainer.lastChild);
        }

        const history = reasoningMessageHistory[mode];
        if (history && history.length > 0) {
            history.forEach(msg => {
                addReasoningMessage(msg.role, msg.content, false);
            });
        }
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    reasoningForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const message = reasoningInput.value.trim();
        if (!message) return;

        addReasoningMessage('user', message);
        reasoningInput.value = '';
        reasoningLoadingIndicator.classList.remove('hidden');

        try {
            const requestBody = {
                message: message,
                sessionId: reasoningSessionIds[currentReasoningMode],
                reasoningMode: currentReasoningMode
            };

            if (currentReasoningMode === 'direct') {
                requestBody.temperature = currentTemperature;
            }

            const response = await fetch('/reasoning-chat', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(requestBody)
            });

            const data = await response.json();
            reasoningLoadingIndicator.classList.add('hidden');

            addReasoningMessage('assistant', data.response);
        } catch (error) {
            reasoningLoadingIndicator.classList.add('hidden');
            addReasoningMessage('assistant', 'Произошла ошибка при обработке запроса');
            console.error('Error:', error);
        }
    });
}

document.addEventListener('DOMContentLoaded', initReasoningChat);
