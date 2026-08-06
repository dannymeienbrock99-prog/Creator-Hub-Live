const chat = document.getElementById('chat');

const state = {
  maxMessages: 8,
  hideAfterMs: 12000,
  showModerators: true,
  showSubscribers: true,
  showGifts: true,
  blockedWords: [],
  fontSize: 18,
  opacity: 0.76
};

function applyAppearance() {
  document.documentElement.style.setProperty('--chat-font-size', `${state.fontSize}px`);
  document.documentElement.style.setProperty('--chat-opacity', String(state.opacity));
}

function sanitize(value) {
  return String(value ?? '').replace(/[<>]/g, '');
}

function isBlocked(text) {
  const normalized = text.toLowerCase();
  return state.blockedWords.some(word => word && normalized.includes(word.toLowerCase()));
}

function addMessage(payload = {}) {
  const text = sanitize(payload.text);
  if (!text || isBlocked(text)) return;
  if (payload.type === 'gift' && !state.showGifts) return;

  const item = document.createElement('div');
  item.className = 'message';

  const badges = [];
  if (payload.moderator && state.showModerators) badges.push('<span class="badge mod">MOD</span>');
  if (payload.subscriber && state.showSubscribers) badges.push('<span class="badge sub">SUB</span>');
  if (payload.type === 'gift') badges.push('<span class="badge gift">GESCHENK</span>');

  const contentClass = payload.type === 'gift' ? 'text gift-line' : 'text';
  item.innerHTML = `${badges.join('')}<span class="name">${sanitize(payload.username || 'Gast')}:</span><span class="${contentClass}">${text}</span>`;
  chat.appendChild(item);

  while (chat.children.length > state.maxMessages) chat.firstElementChild?.remove();

  window.setTimeout(() => {
    item.classList.add('leaving');
    window.setTimeout(() => item.remove(), 280);
  }, state.hideAfterMs);
}

function configure(options = {}) {
  Object.assign(state, options);
  state.maxMessages = Math.max(1, Math.min(30, Number(state.maxMessages) || 8));
  state.hideAfterMs = Math.max(2000, Math.min(60000, Number(state.hideAfterMs) || 12000));
  state.fontSize = Math.max(12, Math.min(42, Number(state.fontSize) || 18));
  state.opacity = Math.max(0.05, Math.min(1, Number(state.opacity) || 0.76));
  applyAppearance();
}

window.CreatorHubChat = { addMessage, configure };
window.addEventListener('message', event => {
  const data = event.data;
  if (!data || typeof data !== 'object') return;
  if (data.type === 'creatorhub-chat-message') addMessage(data.payload);
  if (data.type === 'creatorhub-chat-config') configure(data.payload);
});

applyAppearance();

const params = new URLSearchParams(location.search);
if (params.get('demo') === '1') {
  addMessage({ username: 'Crazy_Batto', text: 'Willkommen im Creator Hub Live!', moderator: true });
  setTimeout(() => addMessage({ username: 'Luna', text: 'Das Overlay sieht richtig gut aus 🔥', subscriber: true }), 700);
  setTimeout(() => addMessage({ username: 'Max', text: 'sendet eine Schatzkiste ×1', type: 'gift' }), 1400);
}
