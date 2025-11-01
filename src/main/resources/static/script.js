const messagesContainer = document.getElementById('messages');
const chatForm = document.getElementById('chat-form');
const userInput = document.getElementById('user-input');
const loadingIndicator = document.getElementById('loading');
const quickPrompts = document.querySelectorAll('.quick-prompt');
const limitModal = document.getElementById('limit-modal');
const closeModalBtn = document.getElementById('close-modal');
const remainingCountEl = document.getElementById('remaining-count');
const messageCounterEl = document.getElementById('message-counter');

let remainingMessages = 10;

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
            body: JSON.stringify({ message })
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

        addMessage('assistant', data.response);

        remainingMessages--;
        updateMessageCounter();

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

function addMessage(role, content) {
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
    scrollToBottom();
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
