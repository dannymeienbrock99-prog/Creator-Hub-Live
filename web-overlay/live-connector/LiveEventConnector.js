export class LiveEventConnector {
  constructor(options = {}) {
    this.url = options.url || '';
    this.apiKey = options.apiKey || '';
    this.room = options.room || '';
    this.reconnectDelay = options.reconnectDelay || 3000;
    this.socket = null;
    this.closedByUser = false;
    this.listeners = new Map();
  }

  on(type, callback) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(callback);
    return () => this.listeners.get(type)?.delete(callback);
  }

  emit(type, payload) {
    this.listeners.get(type)?.forEach((callback) => callback(payload));
    this.listeners.get('*')?.forEach((callback) => callback({ type, payload }));
  }

  connect() {
    if (!this.url) throw new Error('Live-Event-URL fehlt');
    if (this.socket && this.socket.readyState <= WebSocket.OPEN) return;

    this.closedByUser = false;
    const url = new URL(this.url);
    if (this.room) url.searchParams.set('room', this.room);
    if (this.apiKey) url.searchParams.set('apiKey', this.apiKey);

    this.socket = new WebSocket(url.toString());
    this.socket.addEventListener('open', () => this.emit('connection', { connected: true }));
    this.socket.addEventListener('message', (event) => this.handleMessage(event.data));
    this.socket.addEventListener('error', () => this.emit('error', { message: 'WebSocket-Fehler' }));
    this.socket.addEventListener('close', () => {
      this.emit('connection', { connected: false });
      if (!this.closedByUser) setTimeout(() => this.connect(), this.reconnectDelay);
    });
  }

  disconnect() {
    this.closedByUser = true;
    this.socket?.close();
    this.socket = null;
  }

  handleMessage(raw) {
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      this.emit('error', { message: 'Ungültige Live-Nachricht empfangen' });
      return;
    }

    const normalized = this.normalize(message);
    if (!normalized) return;
    this.emit(normalized.type, normalized.data);
  }

  normalize(message) {
    const type = message.type || message.event || message.eventType;
    const data = message.data || message.payload || message;

    switch (type) {
      case 'chat':
      case 'comment':
        return {
          type: 'chat',
          data: {
            username: data.username || data.user?.nickname || data.user?.uniqueId || 'Zuschauer',
            text: data.comment || data.text || data.message || '',
            moderator: Boolean(data.moderator || data.user?.isModerator),
            subscriber: Boolean(data.subscriber || data.user?.isSubscriber)
          }
        };
      case 'gift':
        return {
          type: 'gift',
          data: {
            username: data.username || data.user?.nickname || data.user?.uniqueId || 'Zuschauer',
            giftName: data.giftName || data.gift?.name || 'Geschenk',
            coinValue: Number(data.coinValue || data.diamondCount || data.gift?.diamondCount || 0),
            repeatCount: Number(data.repeatCount || data.combo || 1)
          }
        };
      case 'like':
        return { type: 'like', data };
      case 'follow':
        return { type: 'follow', data };
      case 'share':
        return { type: 'share', data };
      case 'member':
        return { type: 'member', data };
      case 'subscribe':
        return { type: 'subscribe', data };
      case 'roomUserSeq':
      case 'viewerCount':
        return {
          type: 'viewerCount',
          data: { viewerCount: Number(data.viewerCount || data.count || 0) }
        };
      default:
        return { type: 'unknown', data: message };
    }
  }
}
