/**
 * 멀티플레이어 게임용 WebSocket 클라이언트 (STOMP over SockJS)
 * - 서버 → 클라이언트 push 수신
 * - 연결 실패 시 polling fallback 지원
 */
const GameWebSocket = {
    stompClient: null,
    subscription: null,
    connected: false,
    roomCode: null,
    handlers: {},
    reconnectAttempts: 0,
    maxReconnectAttempts: 5,
    reconnectTimer: null,
    stableTimer: null,          // 연결이 이 시간 동안 살아 있어야 재연결 시도 횟수를 초기화한다
    stableAfterMs: 5000,
    keepAliveTimer: null,       // WebSocket 트래픽은 HTTP 세션을 연장하지 않는다. 연결 중에는 폴링도 멈추므로 직접 연장한다
    keepAliveMs: 5 * 60 * 1000, // 서버 세션 유휴 한도(60분)보다 충분히 짧게
    fallbackCallback: null,
    fallbackActivated: false,
    closing: false,             // disconnect() 로 닫는 중 — onclose 가 재연결을 걸지 않게

    /**
     * WebSocket 연결 및 방 토픽 구독
     * @param {string} roomCode - 방 코드
     * @param {Object} messageHandlers - 타입별 핸들러 {ROOM_UPDATE: fn, CHAT: fn, ...}
     * @param {Function} [fallbackFn] - WS 연결 실패 시 호출할 polling 시작 함수
     */
    connect(roomCode, messageHandlers, fallbackFn) {
        this.roomCode = roomCode;
        this.handlers = messageHandlers || {};
        this.fallbackCallback = fallbackFn || null;
        this.reconnectAttempts = 0;
        this.fallbackActivated = false;
        this.closing = false;
        this._clearTimers();

        this._doConnect();
    },

    _clearTimers() {
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.stableTimer) {
            clearTimeout(this.stableTimer);
            this.stableTimer = null;
        }
        this._stopKeepAlive();
    },

    _startKeepAlive() {
        this._stopKeepAlive();
        this.keepAliveTimer = setInterval(() => {
            fetch('/auth/status').catch(() => { /* 일시적 네트워크 오류는 다음 주기에 다시 */ });
        }, this.keepAliveMs);
    },

    _stopKeepAlive() {
        if (this.keepAliveTimer) {
            clearInterval(this.keepAliveTimer);
            this.keepAliveTimer = null;
        }
    },

    _doConnect() {
        try {
            const socket = new SockJS('/ws');
            this.stompClient = Stomp.over(socket);

            // STOMP 디버그 로그 비활성화 (프로덕션)
            this.stompClient.debug = null;

            // CSRF 토큰 헤더
            const headers = {};
            const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
            if (csrfToken && csrfHeader) {
                headers[csrfHeader] = csrfToken;
            }

            this.stompClient.connect(headers, () => {
                this.connected = true;
                console.log('[WS] Connected to /topic/room/' + this.roomCode);
                this._startKeepAlive();

                // CONNECTED 직후 SUBSCRIBE 가 거부되면(참가자 아님) ERROR 로 바로 끊긴다.
                // 여기서 시도 횟수를 0 으로 되돌리면 그 경우 폴백 없이 1초마다 영원히 재접속하므로,
                // 연결이 일정 시간 살아 있을 때만 초기화한다.
                this.stableTimer = setTimeout(() => {
                    this.stableTimer = null;
                    this.reconnectAttempts = 0;
                }, this.stableAfterMs);

                // 방 토픽 구독
                this.subscription = this.stompClient.subscribe(
                    '/topic/room/' + this.roomCode,
                    (message) => {
                        try {
                            const data = JSON.parse(message.body);
                            this._onMessage(data);
                        } catch (e) {
                            console.error('[WS] Message parse error:', e);
                        }
                    }
                );
            }, (error) => {
                console.warn('[WS] Connection error:', error);
                this.connected = false;
                this._handleDisconnect();
            });

            // SockJS 연결 종료 감지. 여기서 stomp.js 가 걸어 둔 onclose 를 덮어쓰므로 그쪽 오류 콜백은 닫힘으로는 오지 않는다 —
            // 연결된 뒤 끊김과 CONNECTED 전 실패(서버가 내려가 있는 동안의 재시도, 배포 전환) 둘 다 여기서 처리해야 한다.
            // 전에는 connected 일 때만 처리해 재시도 1회가 실패하면 재연결도 폴링 폴백도 없이 멈췄다 (O-021, 2026-09-22).
            // _handleDisconnect 는 재연결이 예약돼 있거나 폴백으로 넘어갔으면 무시하므로 오류 콜백과 겹쳐도 한 번만 센다.
            socket.onclose = () => {
                if (this.closing) {
                    return;  // disconnect() 로 우리가 닫은 것 — 재연결하지 않는다
                }
                const wasConnected = this.connected;
                this.connected = false;
                console.warn(wasConnected ? '[WS] Connection closed' : '[WS] Connection attempt failed');
                this._handleDisconnect();
            };
        } catch (e) {
            console.error('[WS] Failed to create connection:', e);
            this._activateFallback();
        }
    },

    _onMessage(data) {
        const type = data.type;
        const payload = data.payload;

        if (this.handlers[type]) {
            this.handlers[type](payload);
        }
    },

    _handleDisconnect() {
        this._stopKeepAlive();
        if (this.stableTimer) {
            clearTimeout(this.stableTimer);
            this.stableTimer = null;
        }
        if (this.reconnectTimer || this.fallbackActivated) {
            return;  // 이미 재연결이 예약됐거나 폴링으로 넘어갔다
        }

        this.reconnectAttempts++;

        if (this.reconnectAttempts > this.maxReconnectAttempts) {
            console.warn('[WS] Max reconnect attempts reached, activating fallback');
            this._activateFallback();
            return;
        }

        // 지수 백오프 재연결 (1s, 2s, 4s, 8s, 16s)
        const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts - 1), 16000);
        console.log(`[WS] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts})`);

        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this._doConnect();
        }, delay);
    },

    _activateFallback() {
        if (this.fallbackActivated) {
            return;
        }
        this.fallbackActivated = true;
        if (this.fallbackCallback) {
            console.log('[WS] Activating polling fallback');
            this.fallbackCallback();
        }
    },

    disconnect() {
        this.closing = true;
        this._clearTimers();
        if (this.subscription) {
            this.subscription.unsubscribe();
            this.subscription = null;
        }
        if (this.stompClient && this.connected) {
            this.stompClient.disconnect();
        }
        this.connected = false;
        this.stompClient = null;
    },

    isConnected() {
        return this.connected;
    }
};
