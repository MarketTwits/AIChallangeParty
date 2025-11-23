console.log('✅ script.js loading started...');

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

console.log('✅ script.js DOM elements initialized');
const chatBackdrop = document.getElementById('chat-backdrop');
const expandIndicator = document.querySelector('.expand-indicator');
const contextLimitSlider = document.getElementById('context-limit-slider');
const contextLimitValue = document.getElementById('context-limit-value');
const contextProgress = document.getElementById('context-progress');
const contextUsed = document.getElementById('context-used');
const contextMax = document.getElementById('context-max');
const contextBar = document.getElementById('context-bar');

let remainingMessages = 10;
let currentCoachStyle = 'default';
let maxContextTokens = null;

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

contextLimitSlider.addEventListener('input', (e) => {
    const value = parseInt(e.target.value);
    if (value === 0) {
        contextLimitValue.textContent = 'Без лимита';
        maxContextTokens = null;
        contextProgress.classList.add('hidden');
    } else {
        contextLimitValue.textContent = value;
        maxContextTokens = value;
        contextProgress.classList.remove('hidden');
        contextMax.textContent = value;
    }
});

function updateContextProgress(totalInputTokens, contextLimit) {
    if (contextLimit && contextLimit > 0) {
        contextProgress.classList.remove('hidden');
        contextUsed.textContent = totalInputTokens;
        contextMax.textContent = contextLimit;
        const percentage = Math.min((totalInputTokens / contextLimit) * 100, 100);
        contextBar.style.width = percentage + '%';

        if (percentage >= 90) {
            contextBar.classList.remove('bg-blue-500');
            contextBar.classList.add('bg-red-500');
        } else if (percentage >= 70) {
            contextBar.classList.remove('bg-blue-500', 'bg-red-500');
            contextBar.classList.add('bg-orange-500');
        } else {
            contextBar.classList.remove('bg-orange-500', 'bg-red-500');
            contextBar.classList.add('bg-blue-500');
        }
    }
}

function getOrCreateSessionId() {
    let sessionId = localStorage.getItem('currentSessionId');
    if (!sessionId) {
        sessionId = 'session_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
        localStorage.setItem('currentSessionId', sessionId);
        addSessionToList(sessionId);
    }
    return sessionId;
}

function addSessionToList(sessionId) {
    let sessions = JSON.parse(localStorage.getItem('sessions') || '[]');
    if (!sessions.includes(sessionId)) {
        sessions.push(sessionId);
        localStorage.setItem('sessions', JSON.stringify(sessions));
    }
}

function getAllSessions() {
    return JSON.parse(localStorage.getItem('sessions') || '[]');
}

function switchSession(sessionId) {
    localStorage.setItem('currentSessionId', sessionId);
    location.reload();
}

function createNewSession() {
    const newSessionId = 'session_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    localStorage.setItem('currentSessionId', newSessionId);
    addSessionToList(newSessionId);
    location.reload();
}

function deleteSession(sessionId) {
    let sessions = getAllSessions();
    sessions = sessions.filter(s => s !== sessionId);
    localStorage.setItem('sessions', JSON.stringify(sessions));

    if (localStorage.getItem('currentSessionId') === sessionId) {
        if (sessions.length > 0) {
            switchSession(sessions[0]);
        } else {
            createNewSession();
        }
    }
}

let sessionId = getOrCreateSessionId();

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
                coachStyle: currentCoachStyle,
                maxContextTokens: maxContextTokens
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

        if (data.response === 'CONTEXT_LIMIT_EXCEEDED') {
            addMessage('assistant', `⚠️ Достигнут лимит контекста в ${data.totalInputTokens} токенов! Начните новый диалог или увеличьте лимит.`);
            userInput.disabled = true;
            return;
        }

        addMessage('assistant', data.response, data.structuredResponse, data.inputTokens, data.outputTokens);

        if (data.remainingMessages !== undefined && data.remainingMessages !== null) {
            remainingMessages = data.remainingMessages;
            updateMessageCounter();
        }

        if (data.totalInputTokens !== undefined && data.contextLimit !== undefined) {
            updateContextProgress(data.totalInputTokens, data.contextLimit);
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

function addMessage(role, content, structuredResponse = null, inputTokens = null, outputTokens = null) {
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

        if (inputTokens !== null && outputTokens !== null) {
            const tokenInfo = document.createElement('div');
            tokenInfo.className = 'mt-3 pt-3 border-t border-gray-200 flex items-center gap-4 text-xs text-gray-500';
            const totalTokens = inputTokens + outputTokens;
            const costPer1M = 0.80;
            const cost = ((inputTokens / 1000000) * costPer1M + (outputTokens / 1000000) * (costPer1M * 5)).toFixed(6);
            tokenInfo.innerHTML = `
                <span class="flex items-center gap-1">
                    <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"></path>
                    </svg>
                    Вход: <strong>${inputTokens}</strong>
                </span>
                <span class="flex items-center gap-1">
                    <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"></path>
                    </svg>
                    Выход: <strong>${outputTokens}</strong>
                </span>
                <span>Всего: <strong>${totalTokens}</strong></span>
                <span>Стоимость: <strong>$${cost}</strong></span>
            `;
            messageDiv.appendChild(tokenInfo);
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
    experts: 'reasoning_experts_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9),
    tokenizer: 'reasoning_tokenizer_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9)
};

let reasoningMessageHistory = {
    direct: [],
    stepByStep: [],
    aiPrompt: [],
    experts: [],
    tokenizer: []
};

function initReasoningTab() {
    console.log('Initializing reasoning tab...');
    const tabTraining = document.getElementById('tab-training');
    const tabReasoning = document.getElementById('tab-reasoning');
    const tabModels = document.getElementById('tab-models');
    const tabMcp = document.getElementById('tab-mcp');
    const trainingContent = document.getElementById('training-content');
    const reasoningContent = document.getElementById('reasoning-content');
    const modelsContent = document.getElementById('models-content');
    const mcpContent = document.getElementById('mcp-content');

    console.log('Elements:', {
        tabTraining: !!tabTraining,
        tabReasoning: !!tabReasoning,
        tabModels: !!tabModels,
        tabMcp: !!tabMcp,
        trainingContent: !!trainingContent,
        reasoningContent: !!reasoningContent,
        modelsContent: !!modelsContent,
        mcpContent: !!mcpContent
    });

    if (!tabTraining || !tabReasoning || !tabModels || !tabMcp || !trainingContent || !reasoningContent || !modelsContent || !mcpContent) {
        console.error('Tab elements not found!', {
            tabTraining, tabReasoning, tabModels, tabMcp, trainingContent, reasoningContent, modelsContent, mcpContent
        });
        return;
    }

    function switchTab(tab) {
        console.log('Switching to tab:', tab);

        tabTraining.classList.remove('active-tab');
        tabReasoning.classList.remove('active-tab');
        tabModels.classList.remove('active-tab');
        tabMcp.classList.remove('active-tab');
        trainingContent.classList.remove('active');
        reasoningContent.classList.remove('active');
        modelsContent.classList.remove('active');
        mcpContent.classList.remove('active');

        if (tab === 'training') {
            tabTraining.classList.add('active-tab');
            trainingContent.classList.add('active');
        } else if (tab === 'reasoning') {
            tabReasoning.classList.add('active-tab');
            reasoningContent.classList.add('active');
        } else if (tab === 'models') {
            tabModels.classList.add('active-tab');
            modelsContent.classList.add('active');
        } else if (tab === 'mcp') {
            tabMcp.classList.add('active-tab');
            mcpContent.classList.add('active');
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
    tabModels.addEventListener('click', () => {
        console.log('Models tab clicked');
        switchTab('models');
    });
    tabMcp.addEventListener('click', () => {
        console.log('MCP tab clicked');
        switchTab('mcp');
    });

    console.log('Event listeners attached successfully');
}

document.addEventListener('DOMContentLoaded', initReasoningTab);

function initReasoningChat() {
    const reasoningModeBtns = document.querySelectorAll('.reasoning-mode-btn');
    const temperatureSlider = document.getElementById('temperature-slider');
    const temperatureValue = document.getElementById('temperature-value');
    const clearReasoningBtn = document.getElementById('clear-reasoning-btn');
    const compressionThresholdSlider = document.getElementById('compression-threshold-slider');
    const compressionThresholdValue = document.getElementById('compression-threshold-value');
    const summariesContainer = document.getElementById('summaries-container');

    let currentTemperature = 1.0;
    let currentCompressionThreshold = 0;

    if (temperatureSlider && temperatureValue) {
        temperatureSlider.addEventListener('input', (e) => {
            currentTemperature = parseFloat(e.target.value);
            temperatureValue.textContent = currentTemperature.toFixed(1);
        });
    }

    if (compressionThresholdSlider && compressionThresholdValue) {
        compressionThresholdSlider.addEventListener('input', (e) => {
            const value = parseInt(e.target.value);
            currentCompressionThreshold = value;
            if (value === 0) {
                compressionThresholdValue.textContent = 'Выключена';
            } else {
                compressionThresholdValue.textContent = `Каждые ${value} сообщений`;
            }
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
    const reasoningContextLimitSlider = document.getElementById('reasoning-context-limit-slider');
    const reasoningContextLimitValue = document.getElementById('reasoning-context-limit-value');
    const reasoningContextProgress = document.getElementById('reasoning-context-progress');
    const reasoningContextUsed = document.getElementById('reasoning-context-used');
    const reasoningContextMax = document.getElementById('reasoning-context-max');
    const reasoningContextBar = document.getElementById('reasoning-context-bar');

    if (!reasoningForm || !reasoningInput || !reasoningMessagesContainer || !reasoningLoadingIndicator) {
        console.error('Reasoning chat elements not found');
        return;
    }

    let reasoningMaxContextTokens = null;

    reasoningContextLimitSlider.addEventListener('input', (e) => {
        const value = parseInt(e.target.value);
        if (value === 0) {
            reasoningContextLimitValue.textContent = 'Без лимита';
            reasoningMaxContextTokens = null;
            reasoningContextProgress.classList.add('hidden');
        } else {
            reasoningContextLimitValue.textContent = value;
            reasoningMaxContextTokens = value;
            reasoningContextProgress.classList.remove('hidden');
            reasoningContextMax.textContent = value;
        }
    });

    function updateReasoningContextProgress(totalInputTokens, contextLimit) {
        if (contextLimit && contextLimit > 0) {
            reasoningContextProgress.classList.remove('hidden');
            reasoningContextUsed.textContent = totalInputTokens;
            reasoningContextMax.textContent = contextLimit;
            const percentage = Math.min((totalInputTokens / contextLimit) * 100, 100);
            reasoningContextBar.style.width = percentage + '%';

            if (percentage >= 90) {
                reasoningContextBar.classList.remove('bg-blue-500');
                reasoningContextBar.classList.add('bg-red-500');
            } else if (percentage >= 70) {
                reasoningContextBar.classList.remove('bg-blue-500', 'bg-red-500');
                reasoningContextBar.classList.add('bg-orange-500');
            } else {
                reasoningContextBar.classList.remove('bg-orange-500', 'bg-red-500');
                reasoningContextBar.classList.add('bg-blue-500');
            }
        }
    }

    function addReasoningMessage(role, content, saveToHistory = true, inputTokens = null, outputTokens = null) {
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

            let tokenInfoHtml = '';
            if (inputTokens !== null && outputTokens !== null) {
                const totalTokens = inputTokens + outputTokens;
                const costPer1M = 0.80;
                const cost = ((inputTokens / 1000000) * costPer1M + (outputTokens / 1000000) * (costPer1M * 5)).toFixed(6);
                tokenInfoHtml = `
                    <div class="mt-3 pt-3 border-t border-gray-200 flex items-center gap-4 text-xs text-gray-500">
                        <span class="flex items-center gap-1">
                            <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"></path>
                            </svg>
                            Вход: <strong>${inputTokens}</strong>
                        </span>
                        <span class="flex items-center gap-1">
                            <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"></path>
                            </svg>
                            Выход: <strong>${outputTokens}</strong>
                        </span>
                        <span>Всего: <strong>${totalTokens}</strong></span>
                        <span>Стоимость: <strong>$${cost}</strong></span>
                    </div>
                `;
            }

            messageEl.innerHTML = `
                <div class="avatar rounded-full w-10 h-10 flex items-center justify-center flex-shrink-0 font-bold text-sm text-white">
                    AI
                </div>
                <div class="flex-1">
                    <p class="font-bold text-sm mb-2" style="color: var(--primary-color);">AI Ассистент</p>
                    <div class="assistant-message p-5 rounded-2xl rounded-tl-none">
                        <div class="markdown-content text-gray-700 leading-relaxed">${formattedContent}</div>
                        ${tokenInfoHtml}
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
                reasoningMode: currentReasoningMode,
                maxContextTokens: reasoningMaxContextTokens,
                compressionThreshold: currentCompressionThreshold > 0 ? currentCompressionThreshold : null
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

            if (data.compressionOccurred) {
                displayCompressionNotification();
            }

            if (data.summaries && data.summaries.length > 0) {
                updateSummariesDisplay(data.summaries);
            }

            addReasoningMessage('assistant', data.response, true, data.inputTokens, data.outputTokens);

            if (data.totalInputTokens !== undefined && data.contextLimit !== undefined) {
                updateReasoningContextProgress(data.totalInputTokens, data.contextLimit);
            }
        } catch (error) {
            reasoningLoadingIndicator.classList.add('hidden');
            addReasoningMessage('assistant', 'Произошла ошибка при обработке запроса');
            console.error('Error:', error);
        }
    });
}

function escapeHtmlForSummary(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function displayCompressionNotification() {
    const reasoningMessagesContainer = document.getElementById('reasoning-messages');

    const notificationEl = document.createElement('div');
    notificationEl.className = 'p-4 bg-blue-50 border-2 border-blue-300 rounded-xl flex items-center gap-3 message animate-pulse';
    notificationEl.innerHTML = `
        <svg class="w-6 h-6 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
        </svg>
        <div class="flex-1">
            <p class="font-bold text-blue-700 text-sm">🗜️ Компрессия выполнена!</p>
            <p class="text-xs text-blue-600">История диалога была сжата для экономии токенов. Проверьте раздел "История компрессий" ниже.</p>
        </div>
    `;

    reasoningMessagesContainer.appendChild(notificationEl);
    reasoningMessagesContainer.scrollTop = reasoningMessagesContainer.scrollHeight;

    setTimeout(() => {
        notificationEl.classList.remove('animate-pulse');
    }, 3000);
}

function updateSummariesDisplay(summaries) {
    const summariesContainer = document.getElementById('summaries-container');
    if (!summariesContainer) return;

    summariesContainer.innerHTML = '<p class="text-xs font-semibold text-gray-600 mb-2">📝 История компрессий:</p>';

    summaries.forEach((summary, index) => {
        const summaryEl = document.createElement('div');
        summaryEl.className = 'p-3 bg-blue-50 border border-blue-200 rounded-lg cursor-pointer hover:bg-blue-100 transition-all';

        const tokensSaved = summary.tokensBeforeCompression - summary.tokensAfterCompression;
        const percentage = ((tokensSaved / summary.tokensBeforeCompression) * 100).toFixed(1);

        const timestamp = new Date(summary.timestamp);
        const timeStr = timestamp.toLocaleTimeString('ru-RU', {hour: '2-digit', minute: '2-digit'});

        summaryEl.innerHTML = `
            <div class="flex items-center justify-between mb-2">
                <span class="text-xs font-bold text-blue-700">Компрессия #${index + 1}</span>
                <span class="text-xs text-gray-500">${timeStr}</span>
            </div>
            <div class="text-xs text-gray-600 mb-2">
                <span class="font-semibold">Сообщений:</span> ${summary.originalMessageCount} → 1 summary
            </div>
            <div class="text-xs text-gray-600 mb-2">
                <span class="font-semibold">Токены:</span> ${summary.tokensBeforeCompression} → ${summary.tokensAfterCompression}
                <span class="text-green-600 font-bold ml-1">(сэкономлено ${percentage}%)</span>
            </div>
            <div class="text-xs text-gray-700 p-2 bg-white rounded border border-gray-200 mt-2 hidden summary-text">
                ${escapeHtmlForSummary(summary.summary)}
            </div>
        `;

        summaryEl.addEventListener('click', () => {
            const textEl = summaryEl.querySelector('.summary-text');
            textEl.classList.toggle('hidden');
        });

        summariesContainer.appendChild(summaryEl);
    });

    summariesContainer.classList.remove('hidden');
}

document.addEventListener('DOMContentLoaded', initReasoningChat);

if (document.getElementById('tab-models')) {
    const tabModels = document.getElementById('tab-models');
    const modelsContent = document.getElementById('models-content');
    const comparisonForm = document.getElementById('comparison-form');
    const comparisonInput = document.getElementById('comparison-input');
    const comparisonResults = document.getElementById('comparison-results');
    const comparisonLoading = document.getElementById('comparison-loading');
    const comparisonQuickPrompts = document.querySelectorAll('#models-content .quick-prompt');

    function escapeHtmlComparison(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    comparisonQuickPrompts.forEach(button => {
        button.addEventListener('click', () => {
            const promptText = button.textContent.trim().substring(2);
            comparisonInput.value = promptText;
        });
    });

    function displayComparisonResults(data) {
        comparisonResults.innerHTML = '';

        const summaryDiv = document.createElement('div');
        summaryDiv.className = 'bg-gray-50 rounded-xl p-4 border border-gray-200 mb-4';
        summaryDiv.innerHTML = `
            <div class="flex items-center justify-between mb-2">
                <h3 class="font-bold text-lg" style="color: var(--primary-color);">📊 Результаты сравнения</h3>
                <span class="text-sm text-gray-600">Общее время: ${data.totalTimeMs}ms</span>
            </div>
        `;
        comparisonResults.appendChild(summaryDiv);

        data.results.forEach((result, index) => {
            const resultDiv = document.createElement('div');
            resultDiv.className = 'border-2 border-gray-200 rounded-xl p-5 bg-white hover:shadow-lg transition-shadow';

            let statusBadge = '';
            if (result.error) {
                statusBadge = '<span class="bg-red-500 text-white px-3 py-1 rounded-full text-xs font-bold">❌ Ошибка</span>';
            } else {
                statusBadge = '<span class="bg-green-500 text-white px-3 py-1 rounded-full text-xs font-bold">✅ Успешно</span>';
            }

            let costDisplay = result.estimatedCost > 0
                ? `<div class="text-sm text-gray-600">💰 Стоимость: $${result.estimatedCost.toFixed(6)}</div>`
                : '<div class="text-sm text-green-600">💚 Бесплатно</div>';

            resultDiv.innerHTML = `
                <div class="flex items-center justify-between mb-3">
                    <div class="flex items-center gap-3">
                        <h3 class="font-bold text-lg" style="color: var(--primary-color);">${result.modelName}</h3>
                        ${statusBadge}
                    </div>
                </div>
                <div class="text-xs text-gray-500 mb-3">${result.modelId}</div>

                <div class="grid grid-cols-3 gap-3 mb-4 p-3 bg-gray-50 rounded-lg">
                    <div>
                        <div class="text-xs text-gray-500 mb-1">⏱️ Время ответа</div>
                        <div class="font-bold text-sm">${result.responseTimeMs}ms</div>
                    </div>
                    <div>
                        <div class="text-xs text-gray-500 mb-1">📥 Входные токены</div>
                        <div class="font-bold text-sm">${result.inputTokens}</div>
                    </div>
                    <div>
                        <div class="text-xs text-gray-500 mb-1">📤 Выходные токены</div>
                        <div class="font-bold text-sm">${result.outputTokens}</div>
                    </div>
                </div>

                ${costDisplay}

                ${result.error ?
                `<div class="mt-4 p-4 bg-red-50 border border-red-200 rounded-lg">
                        <div class="font-semibold text-red-700 mb-2">Ошибка:</div>
                        <div class="text-sm text-red-600">${escapeHtmlComparison(result.error)}</div>
                    </div>`
                :
                `<div class="mt-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
                        <div class="font-semibold mb-2" style="color: var(--primary-color);">Ответ модели:</div>
                        <div class="markdown-content text-sm text-gray-700">${marked.parse(result.response)}</div>
                    </div>`
            }
            `;

            comparisonResults.appendChild(resultDiv);
        });
    }

    comparisonForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const query = comparisonInput.value.trim();
        if (!query) return;

        comparisonResults.innerHTML = '';
        comparisonLoading.classList.remove('hidden');

        try {
            console.log('Sending comparison request with query:', query);

            const response = await fetch('/model-comparison', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({query})
            });

            console.log('Response status:', response.status);

            if (!response.ok) {
                const errorText = await response.text();
                console.error('Error response:', errorText);
                throw new Error(`HTTP ${response.status}: ${errorText}`);
            }

            const data = await response.json();
            console.log('Comparison results:', data);

            comparisonLoading.classList.add('hidden');
            displayComparisonResults(data);
        } catch (error) {
            console.error('Comparison error:', error);
            comparisonLoading.classList.add('hidden');
            comparisonResults.innerHTML = `
                <div class="bg-red-50 border border-red-200 rounded-xl p-4">
                    <div class="font-semibold text-red-700 mb-2">Произошла ошибка при сравнении моделей</div>
                    <div class="text-sm text-red-600 mb-2"><strong>Сообщение:</strong> ${escapeHtmlComparison(error.message)}</div>
                    <div class="text-xs text-gray-600">Проверьте консоль браузера (F12) для подробностей</div>
                </div>
            `;
        }
    });
}

const clearChatBtn = document.getElementById('clear-chat-btn');
if (clearChatBtn) {
    clearChatBtn.addEventListener('click', async () => {
        const confirmed = confirm('Вы уверены, что хотите очистить историю диалога? Это действие нельзя отменить.');
        if (!confirmed) return;

        try {
            const response = await fetch('/chat/clear', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    sessionId: sessionId
                })
            });

            if (!response.ok) {
                throw new Error('Ошибка при очистке диалога');
            }

            deleteSession(sessionId);
        } catch (error) {
            console.error('Error clearing chat:', error);
            alert('Произошла ошибка при очистке диалога: ' + error.message);
        }
    });
}

const newChatBtn = document.getElementById('new-chat-btn');
if (newChatBtn) {
    newChatBtn.addEventListener('click', () => {
        createNewSession();
    });
}

const sessionsListBtn = document.getElementById('sessions-list-btn');
const sessionsModal = document.getElementById('sessions-modal');
const closeSessionsModal = document.getElementById('close-sessions-modal');

if (sessionsListBtn && sessionsModal) {
    sessionsListBtn.addEventListener('click', () => {
        renderSessionsList();
        sessionsModal.classList.remove('hidden');
    });
}

if (closeSessionsModal && sessionsModal) {
    closeSessionsModal.addEventListener('click', () => {
        sessionsModal.classList.add('hidden');
    });
}

function renderSessionsList() {
    const container = document.getElementById('sessions-list');
    if (!container) return;

    const sessions = getAllSessions();
    const currentSessionId = localStorage.getItem('currentSessionId');

    if (sessions.length === 0) {
        container.innerHTML = '<p class="text-gray-500 text-center py-4">Нет сохранённых диалогов</p>';
        return;
    }

    container.innerHTML = sessions.map((sid, index) => {
        const isCurrent = sid === currentSessionId;
        const date = new Date(parseInt(sid.split('_')[1]));
        const dateStr = date.toLocaleString('ru-RU');

        return `
            <div class="flex items-center justify-between p-3 border rounded-lg ${
            isCurrent ? 'border-blue-500 bg-blue-50' : 'border-gray-200'
        }">
                <div class="flex-1">
                    <div class="font-semibold">${isCurrent ? '🟢 ' : ''}Диалог ${index + 1}</div>
                    <div class="text-xs text-gray-500">${dateStr}</div>
                </div>
                <div class="flex space-x-2">
                    ${!isCurrent ? `
                        <button onclick="switchSession('${sid}')"
                                class="px-3 py-1 bg-blue-500 text-white rounded hover:bg-blue-600 text-sm">
                            Открыть
                        </button>
                    ` : ''}
                    <button onclick="confirmDeleteSession('${sid}')"
                            class="px-3 py-1 bg-red-500 text-white rounded hover:bg-red-600 text-sm">
                        Удалить
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

function confirmDeleteSession(sid) {
    if (confirm('Удалить этот диалог?')) {
        deleteSession(sid);
    }
}

// Load chat history from server
async function loadChatHistory(sessionId) {
    try {
        const response = await fetch(`/chat/messages/${sessionId}`);
        if (!response.ok) {
            console.warn('Failed to load chat history');
            return;
        }

        const data = await response.json();
        const messages = data.messages || [];

        // Clear current messages except the welcome message
        while (messagesContainer.children.length > 1) {
            messagesContainer.removeChild(messagesContainer.lastChild);
        }

        // Add messages from history
        messages.forEach(msg => {
            if (msg.role === 'user') {
                // Extract text from content blocks
                const text = msg.content
                    .filter(block => block.type === 'text')
                    .map(block => block.text)
                    .join(' ');
                if (text) {
                    addMessage('user', text);
                }
            } else if (msg.role === 'assistant') {
                // Extract text from content blocks
                const text = msg.content
                    .filter(block => block.type === 'text')
                    .map(block => block.text)
                    .join(' ');
                if (text) {
                    // Try to parse as structured response
                    let structuredResponse = null;
                    try {
                        const cleanedText = text
                            .replace(/```json/g, '')
                            .replace(/```/g, '')
                            .trim();
                        structuredResponse = JSON.parse(cleanedText);
                    } catch (e) {
                        // Not a structured response, use plain text
                    }

                    addMessage('assistant', text, structuredResponse);
                }
            }
        });

        console.log(`Loaded ${messages.length} messages from history`);
    } catch (error) {
        console.error('Error loading chat history:', error);
    }
}

// MCP Agent functionality
let mcpSessionId = null;
let mcpIsLoading = false;

// Generate MCP session ID
function generateMcpSessionId() {
    return 'mcp_' + Math.random().toString(36).substr(2, 9);
}

// Initialize MCP session
function initializeMcpSession() {
    mcpSessionId = generateMcpSessionId();
    const sessionIdElement = document.getElementById('mcp-session-id');
    if (sessionIdElement) {
        sessionIdElement.textContent = mcpSessionId;
    }
    loadMcpStatus();
    loadMcpTools();
}

// Load MCP status
async function loadMcpStatus() {
    const statusElement = document.getElementById('mcp-github-status');
    if (!statusElement) return; // Element doesn't exist, skip

    try {
        const response = await fetch('/mcp/status');
        const data = await response.json();

        if (response.ok) {
            statusElement.textContent = data.githubTokenConfigured ? '✅ Configured' : '❌ Not set';
        } else {
            statusElement.textContent = '❌ Error';
        }
    } catch (error) {
        statusElement.textContent = '❌ Offline';
    }
}

// Load available MCP tools
async function loadMcpTools() {
    const toolsContainer = document.getElementById('mcp-available-tools');
    const toolsCount = document.getElementById('mcp-tools-count');

    // If elements don't exist, skip
    if (!toolsContainer || !toolsCount) return;

    try {
        const response = await fetch('/github/tools');
        const data = await response.json();

        if (response.ok && data.status === 'connected') {
            const tools = data.tools || [];
            toolsCount.textContent = tools.length;

            if (tools.length > 0) {
                toolsContainer.innerHTML = tools.map(tool => `
                    <div class="bg-gray-50 p-3 rounded-lg border border-gray-200">
                        <div class="font-semibold text-gray-800">${tool.name}</div>
                        <div class="text-sm text-gray-600 mt-1">${tool.description}</div>
                    </div>
                `).join('');
            } else {
                toolsContainer.innerHTML = '<div class="text-center text-gray-500 col-span-2">Инструменты не найдены</div>';
            }
        } else {
            toolsContainer.innerHTML = '<div class="text-center text-red-500 col-span-2">Ошибка загрузки инструментов</div>';
            toolsCount.textContent = 'Error';
        }
    } catch (error) {
        toolsContainer.innerHTML = '<div class="text-center text-red-500 col-span-2">Ошибка сети</div>';
        toolsCount.textContent = 'Offline';
    }
}

// Add message to MCP chat
function addMcpMessage(content, type, toolResults = null) {
    const messagesContainer = document.getElementById('mcp-chat-messages');
    const messageDiv = document.createElement('div');
    messageDiv.className = `mb-4 p-4 rounded-lg ${type === 'user' ? 'bg-blue-500 text-white ml-auto max-w-2xl' : 'bg-white text-gray-800 mr-auto max-w-4xl'}`;
    messageDiv.style.marginLeft = type === 'user' ? 'auto' : '0';
    messageDiv.style.marginRight = type === 'user' ? '0' : 'auto';

    let messageHTML = `<div class="font-semibold mb-1">${type === 'user' ? '👤 Вы' : '🤖 MCP Agent'}</div>`;
    messageHTML += `<div>${content}</div>`;

    if (toolResults && toolResults.length > 0) {
        messageHTML += '<div class="mt-3">';
        messageHTML += '<div class="text-sm font-semibold text-gray-700 mb-2">🔧 Использованные инструменты:</div>';
        toolResults.forEach(tool => {
            messageHTML += `
                <div class="bg-green-50 border-l-4 border-green-500 p-3 mb-2 text-sm">
                    <div class="font-semibold">${tool.tool}</div>
                    <div class="text-xs text-gray-600 mt-1">${tool.result.substring(0, 200)}${tool.result.length > 200 ? '...' : ''}</div>
                </div>
            `;
        });
        messageHTML += '</div>';
    }

    messageDiv.innerHTML = messageHTML;
    messagesContainer.appendChild(messageDiv);
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

// Show MCP loading
function showMcpLoading() {
    const messagesContainer = document.getElementById('mcp-chat-messages');
    const loadingDiv = document.createElement('div');
    loadingDiv.id = 'mcp-loading-message';
    loadingDiv.className = 'mb-4 p-4 rounded-lg bg-white text-gray-800 mr-auto max-w-4xl';
    loadingDiv.style.marginLeft = '0';
    loadingDiv.style.marginRight = 'auto';

    loadingDiv.innerHTML = `
        <div class="font-semibold mb-1">🤖 MCP Agent</div>
        <div class="inline-flex items-center">
            <div class="animate-spin rounded-full h-4 w-4 border-b-2 border-blue-500 mr-2"></div>
            Думаю<span class="loading-dots"></span>
        </div>
    `;

    messagesContainer.appendChild(loadingDiv);
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

// Hide MCP loading
function hideMcpLoading() {
    const loadingMessage = document.getElementById('mcp-loading-message');
    if (loadingMessage) {
        loadingMessage.remove();
    }
}

// Send MCP message
async function sendMcpMessage() {
    const input = document.getElementById('mcp-message-input');
    const message = input.value.trim();

    if (!message || mcpIsLoading) return;

    mcpIsLoading = true;
    const sendBtn = document.getElementById('mcp-send-btn');
    sendBtn.disabled = true;

    addMcpMessage(message, 'user');
    input.value = '';
    showMcpLoading();

    try {
        const response = await fetch('/mcp/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                message: message,
                sessionId: mcpSessionId
            })
        });

        const data = await response.json();

        hideMcpLoading();

        if (response.ok) {
            addMcpMessage(data.response, 'assistant', data.mcpResults);
        } else {
            addMcpMessage(`Ошибка: ${data.response || 'Произошла ошибка'}`, 'assistant');
        }
    } catch (error) {
        hideMcpLoading();
        addMcpMessage(`Ошибка сети: ${error.message}`, 'assistant');
    } finally {
        mcpIsLoading = false;
        sendBtn.disabled = false;
    }
}

// Clear MCP chat
async function clearMcpChat() {
    if (!confirm('Вы уверены, что хотите очистить чат?')) return;

    try {
        await fetch('/mcp/clear', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                sessionId: mcpSessionId
            })
        });

        // Reset session
        mcpSessionId = generateMcpSessionId();
        document.getElementById('mcp-session-id').textContent = mcpSessionId;

        // Clear messages
        const messagesContainer = document.getElementById('mcp-chat-messages');
        messagesContainer.innerHTML = `
            <div class="text-center text-gray-500 py-8">
                <p class="text-lg mb-2">👋 Привет! Я MCP агент для работы с GitHub.</p>
                <p class="text-sm">Спросите меня о репозиториях, пользователях или поиске на GitHub!</p>
            </div>
        `;
    } catch (error) {
        console.error('Error clearing MCP chat:', error);
    }
}

// Insert MCP example
function insertMcpExample(text) {
    document.getElementById('mcp-message-input').value = text;
    document.getElementById('mcp-message-input').focus();
}

// MCP event listeners
document.getElementById('mcp-send-btn')?.addEventListener('click', sendMcpMessage);
document.getElementById('mcp-message-input')?.addEventListener('keypress', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMcpMessage();
    }
});
document.getElementById('mcp-clear-btn')?.addEventListener('click', clearMcpChat);

// Reminders functionality
let currentRemindersFilter = 'all';
let allReminders = [];

// Create reminder function
async function createReminder() {
    const title = document.getElementById('reminder-title').value.trim();
    const description = document.getElementById('reminder-description').value.trim();
    const priority = document.getElementById('reminder-priority').value;
    const dueDate = document.getElementById('reminder-due-date').value;
    const reminderTime = document.getElementById('reminder-time').value;
    const periodicityMinutes = document.getElementById('reminder-periodicity').value;
    const recurringType = document.getElementById('reminder-recurring-type').value;

    if (!title || !description) {
        showNotification('Пожалуйста, введите название и описание задачи', 'error');
        return;
    }

    try {
        const requestBody = {
            title,
            description,
            priority,
            dueDate: dueDate || null,
            reminderTime: reminderTime || null
        };

        // Add periodicity if selected
        if (periodicityMinutes) {
            requestBody.periodicityMinutes = parseInt(periodicityMinutes);
        }

        // Add recurring type if selected
        if (recurringType) {
            requestBody.recurringType = recurringType;
        }

        const response = await fetch('/reminder/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(requestBody)
        });

        const result = await response.json();

        if (result.success) {
            // Clear form
            document.getElementById('reminder-title').value = '';
            document.getElementById('reminder-description').value = '';
            document.getElementById('reminder-priority').value = 'medium';
            document.getElementById('reminder-due-date').value = '';
            document.getElementById('reminder-time').value = '';
            document.getElementById('reminder-periodicity').value = '';
            document.getElementById('reminder-recurring-type').value = '';

            // Show success message
            const periodText = periodicityMinutes ? ` с периодичностью ${periodicityMinutes} мин` : '';
            showNotification(`✅ Задача успешно создана${periodText}!`, 'success');

            // Refresh tasks
            await loadReminders();
        } else {
            showNotification('❌ Ошибка: ' + (result.error || 'Неизвестная ошибка'), 'error');
        }
    } catch (error) {
        showNotification('❌ Ошибка при создании задачи: ' + error.message, 'error');
    }
}

// Create reminder with AI assistance
async function createReminderWithAI() {
    const title = document.getElementById('reminder-title').value.trim();
    const description = document.getElementById('reminder-description').value.trim();
    const priority = document.getElementById('reminder-priority').value;
    const dueDate = document.getElementById('reminder-due-date').value;
    const reminderTime = document.getElementById('reminder-time').value;

    if (!title || !description) {
        alert('Пожалуйста, введите название и описание задачи');
        return;
    }

    try {
        // First create the reminder
        const reminderResponse = await fetch('/reminder/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                title,
                description,
                priority,
                dueDate,
                reminderTime
            })
        });

        const reminderResult = await reminderResponse.json();

        if (!reminderResult.success) {
            throw new Error(reminderResult.error || 'Не удалось создать задачу');
        }

        // Then get AI enhancement
        const aiResponse = await fetch('/mcp/demo/execute', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                tool: 'reminder_creation',
                parameters: {
                    title,
                    description,
                    priority
                }
            })
        });

        const aiResult = await aiResponse.json();

        // Show AI response in a nice modal
        showAIEnhancementModal(aiResult.result);

        // Clear form
        document.getElementById('reminder-title').value = '';
        document.getElementById('reminder-description').value = '';
        document.getElementById('reminder-priority').value = 'medium';
        document.getElementById('reminder-due-date').value = '';
        document.getElementById('reminder-time').value = '';

        // Refresh tasks
        await loadReminders();

    } catch (error) {
        showNotification('❌ Ошибка: ' + error.message, 'error');
    }
}

// Show AI enhancement modal
function showAIEnhancementModal(aiResponse) {
    // Create modal if it doesn't exist
    let modal = document.getElementById('ai-enhancement-modal');
    if (!modal) {
        modal = document.createElement('div');
        modal.id = 'ai-enhancement-modal';
        modal.className = 'hidden fixed inset-0 modal-backdrop flex items-center justify-center p-4 z-50';
        modal.innerHTML = `
            <div class="modal-content rounded-2xl max-w-2xl w-full p-6 max-h-[80vh] overflow-y-auto">
                <div class="flex justify-between items-center mb-4">
                    <h3 class="text-xl font-bold text-purple-800">🤖 AI Улучшение задачи</h3>
                    <button onclick="closeAIEnhancementModal()" class="text-gray-500 hover:text-gray-700">
                        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
                        </svg>
                    </button>
                </div>
                <div id="ai-enhancement-content" class="prose prose-sm max-w-none">
                    Loading AI response...
                </div>
            </div>
        `;
        document.body.appendChild(modal);
    }

    // Set content and show modal
    document.getElementById('ai-enhancement-content').innerHTML = aiResponse.replace(/\n/g, '<br>');
    modal.classList.remove('hidden');
}

// Close AI enhancement modal
function closeAIEnhancementModal() {
    const modal = document.getElementById('ai-enhancement-modal');
    if (modal) {
        modal.classList.add('hidden');
    }
}

// Load reminders from server
async function loadReminders() {
    try {
        const response = await fetch('/reminder/list');
        const data = await response.json();
        allReminders = data.tasks || [];

        // Update summary
        updateRemindersSummary();

        // Display filtered tasks
        displayFilteredReminders();

    } catch (error) {
        console.error('Error loading reminders:', error);
        showNotification('❌ Ошибка загрузки задач: ' + error.message, 'error');
    }
}

// Update reminders summary
function updateRemindersSummary() {
    const summary = allReminders.reduce((acc, task) => {
        acc.total++;
        if (task.status === 'completed') {
            acc.completed++;
        } else {
            acc.pending++;
        }
        // Check if overdue (past due date)
        if (task.dueDate && new Date(task.dueDate) < new Date() && task.status !== 'completed') {
            acc.overdue++;
        }
        return acc;
    }, {total: 0, completed: 0, pending: 0, overdue: 0});

    document.getElementById('total-tasks').textContent = summary.total;
    document.getElementById('completed-tasks').textContent = summary.completed;
    document.getElementById('pending-tasks').textContent = summary.pending;
    document.getElementById('overdue-tasks').textContent = summary.overdue;
}

// Display filtered reminders
function displayFilteredReminders() {
    const container = document.getElementById('reminders-list');

    let filteredTasks = allReminders;
    if (currentRemindersFilter !== 'all') {
        filteredTasks = allReminders.filter(task => {
            if (currentRemindersFilter === 'completed') {
                return task.status === 'completed';
            } else if (currentRemindersFilter === 'pending') {
                return task.status !== 'completed';
            }
            return true;
        });
    }

    if (filteredTasks.length === 0) {
        container.innerHTML = `
            <div class="text-center text-gray-500 py-8">
                <i class="fas fa-inbox text-4xl mb-2"></i>
                <p>Нет задач для отображения</p>
            </div>
        `;
        return;
    }

    container.innerHTML = filteredTasks.map(task => createReminderCard(task)).join('');
}

// Create reminder card HTML
function createReminderCard(task) {
    const priorityColors = {
        high: 'border-red-500',
        medium: 'border-yellow-500',
        low: 'border-green-500'
    };

    const priorityIcons = {
        high: '🔴',
        medium: '🟡',
        low: '🟢'
    };

    const statusClass = task.status === 'completed' ? 'opacity-60' : '';
    const isOverdue = task.dueDate && new Date(task.dueDate) < new Date() && task.status !== 'completed';

    return `
        <div class="border-l-4 ${priorityColors[task.priority]} bg-white p-4 rounded-lg shadow-sm hover:shadow-md transition-shadow ${statusClass}">
            <div class="flex items-start justify-between">
                <div class="flex-1">
                    <div class="flex items-center mb-2">
                        <span class="text-lg">${priorityIcons[task.priority]}</span>
                        <h4 class="font-semibold ml-2 ${task.status === 'completed' ? 'line-through' : ''}">
                            ${task.title}
                        </h4>
                        ${isOverdue ? '<span class="ml-2 text-xs bg-red-100 text-red-700 px-2 py-1 rounded">Просрочена</span>' : ''}
                    </div>
                    <p class="text-gray-600 text-sm mb-2">${task.description}</p>
                    <div class="flex flex-wrap gap-2 text-xs">
                        ${task.dueDate ? `<span class="bg-gray-100 text-gray-700 px-2 py-1 rounded">
                            <i class="fas fa-calendar mr-1"></i>${new Date(task.dueDate).toLocaleDateString()}
                        </span>` : ''}
                        ${task.reminderTime ? `<span class="bg-blue-100 text-blue-700 px-2 py-1 rounded">
                            <i class="fas fa-bell mr-1"></i>${new Date(task.reminderTime).toLocaleTimeString()}
                        </span>` : ''}
                    </div>
                </div>
                <div class="flex space-x-2">
                    ${task.status !== 'completed' ? `
                        <button onclick="completeReminder('${task.id}')"
                                class="text-green-600 hover:text-green-800 transition-colors"
                                title="Отметить как выполнена">
                            <i class="fas fa-check-circle"></i>
                        </button>
                    ` : ''}
                    <button onclick="deleteReminder('${task.id}')"
                            class="text-red-600 hover:text-red-800 transition-colors"
                            title="Удалить задачу">
                        <i class="fas fa-trash"></i>
                    </button>
                </div>
            </div>
        </div>
    `;
}

// Complete reminder
async function completeReminder(taskId) {
    try {
        const response = await fetch(`/reminder/${taskId}`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({status: 'completed'})
        });

        const result = await response.json();

        if (result.success) {
            showNotification('✅ Задача отмечена как выполнена!', 'success');
            await loadReminders();
        } else {
            showNotification('❌ Ошибка: ' + (result.error || 'Не удалось обновить задачу'), 'error');
        }
    } catch (error) {
        showNotification('❌ Ошибка при обновлении задачи: ' + error.message, 'error');
    }
}

// Delete reminder
async function deleteReminder(taskId) {
    if (!confirm('Вы уверены, что хотите удалить эту задачу?')) {
        return;
    }

    try {
        const response = await fetch(`/reminder/${taskId}`, {
            method: 'DELETE'
        });

        const result = await response.json();

        if (result.success) {
            showNotification('✅ Задача успешно удалена!', 'success');
            await loadReminders();
        } else {
            showNotification('❌ Ошибка: ' + (result.error || 'Не удалось удалить задачу'), 'error');
        }
    } catch (error) {
        showNotification('❌ Ошибка при удалении задачи: ' + error.message, 'error');
    }
}

// Filter reminders
function filterReminders(filter) {
    currentRemindersFilter = filter;

    // Update button styles
    document.querySelectorAll('.filter-reminders-btn').forEach(btn => {
        if (btn.dataset.filter === filter) {
            btn.className = 'filter-reminders-btn px-3 py-1 rounded-lg text-sm font-medium bg-blue-600 text-white';
        } else {
            btn.className = 'filter-reminders-btn px-3 py-1 rounded-lg text-sm font-medium bg-gray-200 text-gray-700 hover:bg-gray-300';
        }
    });

    displayFilteredReminders();
}

// Refresh tasks
async function refreshTasks() {
    const refreshBtn = event.target;
    const originalText = refreshBtn.innerHTML;

    refreshBtn.innerHTML = '<i class="fas fa-spinner fa-spin mr-2"></i>Обновление...';
    refreshBtn.disabled = true;

    try {
        await loadReminders();
        showNotification('🔄 Задачи обновлены!', 'success');
    } catch (error) {
        showNotification('❌ Ошибка обновления: ' + error.message, 'error');
    } finally {
        refreshBtn.innerHTML = originalText;
        refreshBtn.disabled = false;
    }
}

// Show notification (reuse existing function if available)
function showNotification(message, type) {
    // Create notification element if it doesn't exist
    let notification = document.getElementById('notification');
    if (!notification) {
        notification = document.createElement('div');
        notification.id = 'notification';
        notification.className = 'fixed bottom-4 right-4 max-w-md z-50';
        notification.innerHTML = `
            <div class="bg-white rounded-lg shadow-xl p-4 border-l-4">
                <div class="flex items-center">
                    <div class="flex-shrink-0">
                        <div class="notification-icon text-xl"></div>
                    </div>
                    <div class="ml-3">
                        <div class="notification-message text-sm font-medium text-gray-900"></div>
                    </div>
                    <button onclick="hideNotification()" class="ml-auto text-gray-400 hover:text-gray-500">
                        <i class="fas fa-times"></i>
                    </button>
                </div>
            </div>
        `;
        document.body.appendChild(notification);
    }

    const messageEl = notification.querySelector('.notification-message');
    const iconEl = notification.querySelector('.notification-icon');
    const borderEl = notification.firstElementChild;

    messageEl.textContent = message;

    if (type === 'success') {
        iconEl.className = 'notification-icon fas fa-check-circle text-green-500';
        borderEl.className = 'bg-white rounded-lg shadow-xl p-4 border-l-4 border-green-500';
    } else {
        iconEl.className = 'notification-icon fas fa-exclamation-circle text-red-500';
        borderEl.className = 'bg-white rounded-lg shadow-xl p-4 border-l-4 border-red-500';
    }

    notification.classList.remove('hidden');
    notification.classList.add('fade-in');

    // Auto-hide after 5 seconds
    setTimeout(() => {
        hideNotification();
    }, 5000);
}

function hideNotification() {
    const notification = document.getElementById('notification');
    if (notification) {
        notification.classList.add('hidden');
    }
}

// MCP Composition Functions
function setCompositionExample(request) {
    document.getElementById('composition-request-input').value = request;
}

function showCompositionLoading() {
    document.getElementById('composition-loading').classList.remove('hidden');
    document.getElementById('composition-results').classList.add('hidden');
    document.getElementById('composition-error').classList.add('hidden');

    const executeBtn = document.getElementById('composition-execute-btn');
    const executeText = document.getElementById('composition-execute-text');
    executeBtn.disabled = true;
    executeText.textContent = 'Выполнение...';
}

function hideCompositionLoading() {
    document.getElementById('composition-loading').classList.add('hidden');

    const executeBtn = document.getElementById('composition-execute-btn');
    const executeText = document.getElementById('composition-execute-text');
    executeBtn.disabled = false;
    executeText.textContent = 'Выполнить';
}

function showCompositionResults(result) {
    document.getElementById('composition-results').classList.remove('hidden');
    document.getElementById('composition-error').classList.add('hidden');

    // Display plan
    const planDescription = document.getElementById('composition-plan-description');
    const planSteps = document.getElementById('composition-plan-steps');

    planDescription.textContent = result.plan.description;
    planSteps.innerHTML = '';

    result.plan.steps.forEach((step, index) => {
        const stepElement = document.createElement('div');
        stepElement.className = 'flex items-center text-gray-700 text-sm p-3 bg-white rounded-lg border border-gray-200';
        stepElement.innerHTML = `
            <span class="bg-blue-500 text-white rounded-full w-6 h-6 flex items-center justify-center mr-3 text-xs font-semibold">
                ${index + 1}
            </span>
            <div class="flex-1">
                <div class="font-medium text-gray-800">${step.toolName}</div>
                <div class="text-gray-600 text-xs">${step.description}</div>
                ${step.outputVariable ? `<div class="text-blue-600 text-xs mt-1">Переменная: ${step.outputVariable}</div>` : ''}
            </div>
        `;
        planSteps.appendChild(stepElement);
    });

    // Display execution results
    const executionResults = document.getElementById('composition-execution-results');
    executionResults.innerHTML = '';

    result.executionResults.forEach((result, index) => {
        const resultElement = document.createElement('div');
        const isSuccess = result.success;

        resultElement.className = `p-4 rounded-lg border ${
            isSuccess
                ? 'bg-green-50 border-green-200'
                : 'bg-red-50 border-red-200'
        }`;

        resultElement.innerHTML = `
            <div class="flex items-start">
                <div class="mr-3">
                    <i class="fas ${isSuccess ? 'fa-check-circle text-green-600' : 'fa-exclamation-circle text-red-600'} text-lg"></i>
                </div>
                <div class="flex-1">
                    <div class="font-semibold mb-1 ${isSuccess ? 'text-green-800' : 'text-red-800'}">
                        ${result.step.description}
                    </div>
                    <div class="text-sm ${isSuccess ? 'text-green-700' : 'text-red-700'}">
                        <strong>Инструмент:</strong> ${result.step.toolName}
                        <span class="ml-2">(${result.executionTimeMs}ms)</span>
                    </div>
                    ${result.step.outputVariable ? `<div class="text-xs ${isSuccess ? 'text-green-600' : 'text-red-600'}"><strong>Переменная:</strong> ${result.step.outputVariable}</div>` : ''}
                    ${!isSuccess ? `<div class="text-sm text-red-600 mt-1"><strong>Ошибка:</strong> ${result.error}</div>` : ''}
                </div>
            </div>
        `;

        executionResults.appendChild(resultElement);
    });

    // Display final output
    const finalOutput = document.getElementById('composition-final-output');
    finalOutput.textContent = result.finalOutput;
}

function showCompositionError(errorMessage) {
    document.getElementById('composition-results').classList.add('hidden');
    document.getElementById('composition-error').classList.remove('hidden');
    document.getElementById('composition-error-message').textContent = errorMessage;
}

function clearCompositionResults() {
    document.getElementById('composition-results').classList.add('hidden');
    document.getElementById('composition-error').classList.add('hidden');
    document.getElementById('composition-loading').classList.add('hidden');
    document.getElementById('composition-request-input').value = '';
}

async function executeComposition() {
    const request = document.getElementById('composition-request-input').value.trim();
    if (!request) {
        alert('Пожалуйста, введите запрос для выполнения композиции');
        return;
    }

    showCompositionLoading();

    try {
        const response = await fetch('/mcp/composition', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({request})
        });

        if (!response.ok) {
            const errorData = await response.json();
            throw new Error(errorData.error || 'Ошибка выполнения запроса');
        }

        const result = await response.json();
        showCompositionResults(result);

    } catch (error) {
        console.error('Error executing composition:', error);
        showCompositionError(error.message);
    } finally {
        hideCompositionLoading();
    }
}

// ============= MCP ORCHESTRATION FUNCTIONS =============

// Load orchestration servers status
async function loadOrchestrationServers() {
    console.log('Loading orchestration servers...');
    try {
        const response = await fetch('/api/orchestration/servers');
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = await response.json();
        console.log('Servers data:', data);

        const container = document.getElementById('orch-servers-status');
        if (!container) {
            console.error('Container #orch-servers-status not found!');
            return;
        }

        container.innerHTML = data.servers.map(server => `
            <div class="p-3 bg-gradient-to-r from-gray-50 to-gray-100 rounded-lg border border-gray-200 hover:shadow-md transition-all">
                <div class="flex items-center justify-between mb-2">
                    <span class="font-semibold text-gray-800 text-sm">${server.serverName}</span>
                    <span class="badge badge-${server.serverId} text-xs">${server.serverId}</span>
                </div>
                <div class="text-xs text-gray-600 space-y-1">
                    <div>v${server.version}</div>
                    <div>Tools: <span class="font-semibold">${server.toolCount}</span></div>
                    <div>State: <span class="font-semibold ${server.state === 'READY' ? 'text-green-600' : 'text-red-600'}">${server.state}</span></div>
                </div>
            </div>
        `).join('');
        console.log('Servers loaded successfully!');
    } catch (error) {
        console.error('Error loading orchestration servers:', error);
        const container = document.getElementById('orch-servers-status');
        if (container) {
            container.innerHTML = `<div class="text-center text-red-500 text-sm py-4">Ошибка загрузки: ${error.message}</div>`;
        }
    }
}

// Load available orchestration tools
async function loadOrchestrationTools() {
    console.log('Loading orchestration tools...');
    try {
        const response = await fetch('/api/orchestration/tools');
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = await response.json();
        console.log('Tools data:', data);

        const container = document.getElementById('orch-tools-list');
        if (!container) {
            console.error('Container #orch-tools-list not found!');
            return;
        }

        container.innerHTML = data.tools.map(tool => `
            <div class="tool-item p-2 bg-gray-50 rounded-lg hover:bg-gray-100 transition-colors border border-gray-100">
                <div class="flex items-start justify-between">
                    <div class="flex-1 min-w-0">
                        <div class="font-semibold text-gray-800 text-sm truncate">${tool.name}</div>
                        <div class="text-xs text-gray-600 mt-1 line-clamp-2">${tool.description}</div>
                    </div>
                    <span class="badge badge-${tool.serverId} ml-2 flex-shrink-0 text-xs">${tool.serverId}</span>
                </div>
            </div>
        `).join('');
        console.log(`Loaded ${data.tools.length} tools successfully!`);
    } catch (error) {
        console.error('Error loading orchestration tools:', error);
        const container = document.getElementById('orch-tools-list');
        if (container) {
            container.innerHTML = `<div class="text-center text-red-500 text-sm py-4">Ошибка загрузки: ${error.message}</div>`;
        }
    }
}

// Execute orchestration
async function executeOrchestration(query) {
    const resultsContainer = document.getElementById('orch-results-container');
    const loadingIndicator = document.getElementById('orch-loading-indicator');
    const executeBtn = document.getElementById('orch-execute-btn');

    if (!resultsContainer || !loadingIndicator || !executeBtn) return;

    resultsContainer.classList.add('hidden');
    loadingIndicator.classList.remove('hidden');
    executeBtn.disabled = true;

    try {
        const response = await fetch('/api/orchestration/execute', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({query})
        });

        const result = await response.json();

        // Display results
        displayOrchestrationResults(result);

        resultsContainer.classList.remove('hidden');

        // Scroll to results
        resultsContainer.scrollIntoView({behavior: 'smooth', block: 'nearest'});
    } catch (error) {
        console.error('Error executing orchestration:', error);
        showNotification('❌ Ошибка выполнения: ' + error.message, 'error');
    } finally {
        loadingIndicator.classList.add('hidden');
        executeBtn.disabled = false;
    }
}

// Display orchestration results
function displayOrchestrationResults(result) {
    // Execution time
    const execTimeEl = document.getElementById('orch-exec-time');
    if (execTimeEl) {
        execTimeEl.textContent = `${result.elapsedTimeMs}ms`;
    }

    // Token usage
    const tokenUsageEl = document.getElementById('orch-token-usage');
    if (tokenUsageEl) {
        const totalTokens = (result.inputTokens || 0) + (result.outputTokens || 0);
        tokenUsageEl.textContent = `${totalTokens} tokens`;
    }

    // Execution steps
    const stepsContainer = document.getElementById('orch-execution-steps');
    if (stepsContainer) {
        stepsContainer.innerHTML = result.executionSteps.map((step, idx) => `
            <div class="p-3 bg-white rounded-lg border-l-4 ${step.success ? 'border-green-500' : 'border-red-500'}">
                <div class="flex items-start">
                    <div class="flex-shrink-0 w-6 h-6 rounded-full ${step.success ? 'bg-green-100 text-green-600' : 'bg-red-100 text-red-600'} flex items-center justify-center text-xs font-bold mr-2">
                        ${step.step}
                    </div>
                    <div class="flex-1 min-w-0">
                        <div class="font-semibold text-gray-800 text-sm">${step.action}</div>
                        <div class="text-xs text-gray-600 mt-1">${step.description}</div>
                        ${step.toolsInvolved && step.toolsInvolved.length > 0 ? `
                            <div class="mt-2 flex flex-wrap gap-1">
                                ${step.toolsInvolved.map(tool => `<span class="text-xs px-2 py-0.5 bg-blue-100 text-blue-700 rounded">${tool}</span>`).join('')}
                            </div>
                        ` : ''}
                        ${step.result ? `<div class="mt-2 p-2 bg-gray-50 rounded text-xs font-mono max-h-20 overflow-y-auto">${step.result.substring(0, 200)}${step.result.length > 200 ? '...' : ''}</div>` : ''}
                    </div>
                </div>
            </div>
        `).join('');
    }

    // Servers used
    const serversContainer = document.getElementById('orch-servers-used');
    if (serversContainer) {
        serversContainer.innerHTML = result.serversUsed.map(serverId => `
            <span class="badge badge-${serverId}">${serverId}</span>
        `).join('');
    }

    // Final response
    const finalResponseEl = document.getElementById('orch-final-response');
    if (finalResponseEl) {
        finalResponseEl.textContent = result.response;
    }
}

// Initialize orchestration tab
let orchestrationTabInitialized = false;

function initializeOrchestrationTab() {
    // Load servers and tools on every tab open
    loadOrchestrationServers();
    loadOrchestrationTools();

    // Only attach event listeners once
    if (orchestrationTabInitialized) {
        return;
    }
    orchestrationTabInitialized = true;

    // Execute button
    const executeBtn = document.getElementById('orch-execute-btn');
    const queryInput = document.getElementById('orch-query-input');

    if (executeBtn && queryInput) {
        executeBtn.addEventListener('click', () => {
            const query = queryInput.value.trim();
            if (query) {
                executeOrchestration(query);
            } else {
                showNotification('⚠️ Введите запрос для выполнения', 'error');
            }
        });

        // Enter key to execute (Shift+Enter for new line)
        queryInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                const query = queryInput.value.trim();
                if (query) {
                    executeOrchestration(query);
                }
            }
        });
    }

    // Example queries
    const exampleButtons = document.querySelectorAll('.orch-example-query');
    if (exampleButtons) {
        exampleButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                const query = btn.textContent.trim();
                if (queryInput) {
                    queryInput.value = query;
                    queryInput.focus();
                }
            });
        });
    }

    // Tool search
    const toolSearchInput = document.getElementById('orch-tool-search');
    if (toolSearchInput) {
        toolSearchInput.addEventListener('input', (e) => {
            const searchTerm = e.target.value.toLowerCase();
            const tools = document.querySelectorAll('.tool-item');
            tools.forEach(tool => {
                const text = tool.textContent.toLowerCase();
                tool.style.display = text.includes(searchTerm) ? 'block' : 'none';
            });
        });
    }

    // Refresh servers every 30 seconds
    setInterval(loadOrchestrationServers, 30000);
}

// ============= END MCP ORCHESTRATION FUNCTIONS =============

// Initialize chat history when page loads
document.addEventListener('DOMContentLoaded', () => {
    loadCoachStyle();
    loadChatHistory(sessionId);
    initializeMcpSession();

    // Add tab switching for reminders
    const remindersTab = document.getElementById('tab-reminders');
    if (remindersTab) {
        remindersTab.addEventListener('click', () => {
            // Hide all tabs
            document.querySelectorAll('.tab-content').forEach(tab => {
                tab.classList.remove('active');
            });
            document.querySelectorAll('.nav-link').forEach(link => {
                link.classList.remove('active-tab');
            });

            // Show reminders tab
            document.getElementById('reminders-content').classList.add('active');
            remindersTab.classList.add('active-tab');

            // Load reminders when tab is opened
            loadReminders();
        });
    }

    // Add tab switching for composition
    const compositionTab = document.getElementById('tab-composition');
    if (compositionTab) {
        compositionTab.addEventListener('click', () => {
            // Hide all tabs
            document.querySelectorAll('.tab-content').forEach(tab => {
                tab.classList.remove('active');
            });
            document.querySelectorAll('.nav-link').forEach(link => {
                link.classList.remove('active-tab');
            });

            // Show composition tab
            document.getElementById('composition-content').classList.add('active');
            compositionTab.classList.add('active-tab');
        });
    }

    // Add tab switching for MCP orchestration
    const mcpTab = document.getElementById('tab-mcp');
    if (mcpTab) {
        console.log('MCP tab found, attaching click handler');
        mcpTab.addEventListener('click', () => {
            console.log('MCP tab clicked!');
            // Hide all tabs
            document.querySelectorAll('.tab-content').forEach(tab => {
                tab.classList.remove('active');
            });
            document.querySelectorAll('.nav-link').forEach(link => {
                link.classList.remove('active-tab');
            });

            // Show MCP orchestration tab
            document.getElementById('mcp-content').classList.add('active');
            mcpTab.classList.add('active-tab');

            // Initialize orchestration when tab is opened
            console.log('Calling initializeOrchestrationTab()');
            initializeOrchestrationTab();
        });
    } else {
        console.error('MCP tab #tab-mcp not found!');
    }

    console.log('✅ DOMContentLoaded complete - all event listeners attached');
});
