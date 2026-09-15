let pollingInterval;
let chatPollingInterval;
let lastStatus = null;
let lastChatId = 0;
let lastHostId = null;  // 방장 변경 감지용
let isReloading = false;  // 새로고침 중 플래그 (leave 방지)
let usingWebSocket = false;  // WebSocket 사용 중 여부

// 페이지 로드 시 WebSocket 연결 시도 (초기 데이터는 fetch로 가져옴)
document.addEventListener('DOMContentLoaded', function() {
    // 초기 데이터 로드 (첫 렌더링용)
    fetchRoomStatus();
    fetchChats();

    // WebSocket 연결 시도
    if (typeof GameWebSocket !== 'undefined') {
        GameWebSocket.connect(roomCode, {
            ROOM_UPDATE: handleRoomUpdate,
            CHAT: handleChatMessage,
            GAME_START: handleGameStart,
            KICKED: handleKicked
        }, startPollingFallback);
        usingWebSocket = true;
    } else {
        // ws-client.js 로드 실패 시 폴링으로 폴백
        startPollingFallback();
    }
});

// WebSocket 실패 시 기존 폴링으로 폴백
function startPollingFallback() {
    usingWebSocket = false;
    startPolling();
    startChatPolling();
}

// 페이지 떠날 때 정리 및 방 나가기
window.addEventListener('beforeunload', function() {
    cleanup();
    // 새로고침 중이면 leave 요청 안 보냄 (방장 위임 후 새로고침 시)
    if (!isReloading) {
        navigator.sendBeacon(`/game/multi/room/${roomCode}/unload`);
    }
});

// 뒤로가기/앞으로가기 시에도 나가기 처리
window.addEventListener('pagehide', function() {
    cleanup();
    if (!isReloading) {
        navigator.sendBeacon(`/game/multi/room/${roomCode}/unload`);
    }
});

// 리소스 정리 (WebSocket + 폴링)
function cleanup() {
    stopPolling();
    stopChatPolling();
    if (usingWebSocket && typeof GameWebSocket !== 'undefined') {
        GameWebSocket.disconnect();
    }
}

// === WebSocket 메시지 핸들러 ===

// ROOM_UPDATE 핸들러 — 서버가 보내는 payload는 fetchRoomStatus 응답과 같은 구조
// 단, push payload(buildRoomStatus)에는 success 키가 없다. 명시적 false 만 "방 종료"로 본다.
function handleRoomUpdate(payload) {
    if (payload.success === false) {
        showToast('방이 종료되었습니다.');
        window.location.href = '/game/multi';
        return;
    }

    if (payload.status === 'PLAYING') {
        cleanup();
        window.location.href = `/game/multi/room/${roomCode}/play`;
        return;
    }

    // 방장 변경 감지
    if (lastHostId !== null && lastHostId !== payload.hostId) {
        const newHost = payload.participants.find(p => p.memberId === payload.hostId);
        const newHostName = newHost ? newHost.nickname : '알 수 없음';

        if (payload.hostId === myMemberId) {
            showToast(`🎉 ${newHostName}님이 새 방장이 되었습니다!`, 'success');
            isReloading = true;
            setTimeout(() => {
                window.location.reload();
            }, 1500);
            return;
        } else {
            showToast(`👑 ${newHostName}님이 새 방장이 되었습니다`, 'info');
        }
    }
    lastHostId = payload.hostId;

    updateParticipantsList(payload.participants, payload.hostId);

    if (isHost) {
        updateStartButton(payload.allReady, payload.participants.length);
    }
}

// CHAT 핸들러 — 단일 채팅 메시지 수신 (payload 는 fetchChats 의 항목과 같은 형태)
function handleChatMessage(payload) {
    const container = document.getElementById('chatMessages');
    if (!container) return;

    // 폴링으로 폴백해도 이미 받은 메시지를 다시 붙이지 않도록 id 를 따라간다
    if (payload.id) {
        lastChatId = Math.max(lastChatId, payload.id);
    }

    const msgDiv = document.createElement('div');
    msgDiv.className = 'chat-message';

    if (payload.messageType === 'SYSTEM') {
        msgDiv.classList.add('system');
        msgDiv.innerHTML = `<span class="system-text">${escapeHtml(payload.message)}</span>`;
    } else {
        const isMe = payload.memberId === myMemberId;
        if (isMe) msgDiv.classList.add('mine');

        msgDiv.innerHTML = `
            <span class="chat-nickname ${isMe ? 'me' : ''}">${escapeHtml(payload.nickname)}</span>
            <span class="chat-text">${escapeHtml(payload.message)}</span>
        `;
    }

    container.appendChild(msgDiv);
    container.scrollTop = container.scrollHeight;
}

// GAME_START 핸들러 — 게임 시작, 플레이 페이지로 이동
function handleGameStart(payload) {
    cleanup();
    window.location.href = `/game/multi/room/${roomCode}/play`;
}

// KICKED 핸들러 — 자신이 강퇴당한 경우 로비로 이동
function handleKicked(payload) {
    if (payload.targetMemberId === myMemberId) {
        cleanup();
        showToast('방에서 강퇴되었습니다.');
        window.location.href = '/game/multi';
    }
}

// === 폴링 (초기 로드 + 폴백용) ===

// 폴링 시작
function startPolling() {
    pollingInterval = setInterval(fetchRoomStatus, 2000);
}

// 폴링 중지
function stopPolling() {
    if (pollingInterval) {
        clearInterval(pollingInterval);
        pollingInterval = null;
    }
}

// 채팅 폴링 시작
function startChatPolling() {
    chatPollingInterval = setInterval(fetchChats, 1000);
}

// 채팅 폴링 중지
function stopChatPolling() {
    if (chatPollingInterval) {
        clearInterval(chatPollingInterval);
        chatPollingInterval = null;
    }
}

// 방 상태 조회
async function fetchRoomStatus() {
    try {
        const response = await fetch(`/game/multi/room/${roomCode}/status`);
        const result = await response.json();

        if (!result.success) {
            showToast('방이 종료되었습니다.');
            window.location.href = '/game/multi';
            return;
        }

        // 강퇴 여부 확인
        if (result.kicked) {
            cleanup();
            showToast('방에서 강퇴되었습니다.');
            window.location.href = '/game/multi';
            return;
        }

        if (result.status === 'PLAYING') {
            cleanup();
            window.location.href = `/game/multi/room/${roomCode}/play`;
            return;
        }

        // 방장 변경 감지
        if (lastHostId !== null && lastHostId !== result.hostId) {
            const newHost = result.participants.find(p => p.memberId === result.hostId);
            const newHostName = newHost ? newHost.nickname : '알 수 없음';

            if (result.hostId === myMemberId) {
                // 내가 새 방장이 됨 → 페이지 새로고침으로 UI 갱신
                showToast(`🎉 ${newHostName}님이 새 방장이 되었습니다!`, 'success');
                isReloading = true;  // leave 요청 방지
                setTimeout(() => {
                    window.location.reload();
                }, 1500);
                return;
            } else {
                // 다른 사람이 새 방장이 됨
                showToast(`👑 ${newHostName}님이 새 방장이 되었습니다`, 'info');
            }
        }
        lastHostId = result.hostId;

        updateParticipantsList(result.participants, result.hostId);

        if (isHost) {
            updateStartButton(result.allReady, result.participants.length);
        }

    } catch (error) {
        // console.error('상태 조회 오류:', error);
    }
}

// 채팅 목록 조회
async function fetchChats() {
    try {
        const response = await fetch(`/game/multi/room/${roomCode}/chats?lastId=${lastChatId}`);
        const result = await response.json();

        if (result.success && result.chats && result.chats.length > 0) {
            appendChats(result.chats);
        }
    } catch (error) {
        // console.error('채팅 조회 오류:', error);
    }
}

// 채팅 메시지 추가 (배열 — 초기 로드 및 폴링용)
function appendChats(chats) {
    const container = document.getElementById('chatMessages');
    if (!container) return;

    chats.forEach(chat => {
        if (chat.id > lastChatId) {
            lastChatId = chat.id;

            const msgDiv = document.createElement('div');
            msgDiv.className = 'chat-message';

            if (chat.messageType === 'SYSTEM') {
                msgDiv.classList.add('system');
                msgDiv.innerHTML = `<span class="system-text">${escapeHtml(chat.message)}</span>`;
            } else {
                const isMe = chat.memberId === myMemberId;
                if (isMe) msgDiv.classList.add('mine');

                msgDiv.innerHTML = `
                    <span class="chat-nickname ${isMe ? 'me' : ''}">${escapeHtml(chat.nickname)}</span>
                    <span class="chat-text">${escapeHtml(chat.message)}</span>
                `;
            }

            container.appendChild(msgDiv);
        }
    });

    container.scrollTop = container.scrollHeight;
}

// === POST 요청 (변경 없음) ===

// 채팅 전송
async function sendChat() {
    const input = document.getElementById('chatInput');
    const message = input.value.trim();

    if (!message) return;

    input.value = '';
    input.focus();

    try {
        const response = await fetch(`/game/multi/room/${roomCode}/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: message })
        });

        const result = await response.json();

        if (!result.success) {
            // console.error('채팅 전송 실패:', result.message);
        }
    } catch (error) {
        // console.error('채팅 전송 오류:', error);
    }
}

// 참가자 목록 갱신
function updateParticipantsList(participants, hostId) {
    const container = document.getElementById('participantsList');
    if (!container) return;

    container.innerHTML = participants.map(p => {
        const isHostMember = p.memberId === hostId;
        const isMe = p.memberId === myMemberId;

        return `
            <div class="participant-card ${p.isReady ? 'ready' : ''}" data-member-id="${p.memberId}">
                <div class="participant-info">
                    <span class="participant-icon">${isHostMember ? '👑' : '👤'}</span>
                    <span class="participant-name">${escapeHtml(p.nickname)}${isMe ? ' (나)' : ''}</span>
                </div>
                <div class="participant-status">
                    ${isHostMember
                        ? '<span class="status-badge host">방장</span>'
                        : `<span class="status-badge ${p.isReady ? 'ready' : 'waiting'}">${p.isReady ? '준비완료' : '대기중'}</span>`
                    }
                </div>
                ${isHost && !isHostMember ? `<button type="button" class="btn btn-kick" onclick="kickPlayer(${p.memberId})">강퇴</button>` : ''}
            </div>
        `;
    }).join('');

    if (!isHost) {
        const myInfo = participants.find(p => p.memberId === myMemberId);
        if (myInfo) {
            const readyBtn = document.getElementById('readyBtn');
            if (readyBtn) {
                if (myInfo.isReady) {
                    readyBtn.classList.add('is-ready');
                    readyBtn.innerHTML = '✅ 준비 완료';
                } else {
                    readyBtn.classList.remove('is-ready');
                    readyBtn.innerHTML = '🎮 준비하기';
                }
            }
        }
    }
}

// 시작 버튼 상태 갱신
function updateStartButton(allReady, participantCount) {
    const startBtn = document.getElementById('startGameBtn');
    if (!startBtn) return;

    if (participantCount < 2) {
        startBtn.disabled = true;
        startBtn.textContent = '👥 2명 이상 필요';
    } else if (!allReady) {
        startBtn.disabled = true;
        startBtn.textContent = '⏳ 모두 준비 대기중';
    } else {
        startBtn.disabled = false;
        startBtn.textContent = '🚀 게임 시작';
    }
}

// 준비 상태 토글
async function toggleReady() {
    try {
        const response = await fetch(`/game/multi/room/${roomCode}/ready`, {
            method: 'POST'
        });

        const result = await response.json();

        if (!result.success) {
            showToast(result.message || '준비 상태 변경에 실패했습니다.');
        }
    } catch (error) {
        // console.error('준비 상태 변경 오류:', error);
    }
}

// 방 나가기
async function leaveRoom() {
    if (!confirm('정말 방을 나가시겠습니까?')) return;

    try {
        const response = await fetch(`/game/multi/room/${roomCode}/leave`, {
            method: 'POST'
        });

        const result = await response.json();

        if (result.success) {
            cleanup();
            window.location.href = '/game/multi';
        } else {
            showToast(result.message || '나가기에 실패했습니다.');
        }
    } catch (error) {
        // console.error('나가기 오류:', error);
        window.location.href = '/game/multi';
    }
}

// 강퇴
async function kickPlayer(memberId) {
    if (!confirm('정말 이 플레이어를 강퇴하시겠습니까?')) return;

    try {
        const response = await fetch(`/game/multi/room/${roomCode}/kick/${memberId}`, {
            method: 'POST'
        });

        const result = await response.json();

        if (!result.success) {
            showToast(result.message || '강퇴에 실패했습니다.');
        }
    } catch (error) {
        // console.error('강퇴 오류:', error);
    }
}

// 게임 시작 (방장용)
async function startGame() {
    try {
        const response = await fetch(`/game/multi/room/${roomCode}/start`, {
            method: 'POST'
        });

        const result = await response.json();

        if (!result.success) {
            showToast(result.message || '게임 시작에 실패했습니다.');
        }
    } catch (error) {
        // console.error('게임 시작 오류:', error);
        showToast('게임 시작 중 오류가 발생했습니다.');
    }
}

// 참가 코드 복사
function copyRoomCode() {
    // Clipboard API가 지원되는지 확인 (HTTPS 또는 localhost에서만 사용 가능)
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(roomCode).then(() => {
            showCopySuccess();
        }).catch(() => {
            fallbackCopy();
        });
    } else {
        fallbackCopy();
    }
}

// 폴백 복사 (구형 브라우저 또는 HTTP 환경)
function fallbackCopy() {
    const tempInput = document.createElement('textarea');
    tempInput.value = roomCode;
    tempInput.style.position = 'fixed';
    tempInput.style.left = '-9999px';
    tempInput.style.top = '0';
    document.body.appendChild(tempInput);
    tempInput.focus();
    tempInput.select();

    try {
        document.execCommand('copy');
        showCopySuccess();
    } catch (err) {
        // 복사 실패 시 직접 코드 표시
        prompt('참가 코드를 복사하세요:', roomCode);
    }

    document.body.removeChild(tempInput);
}

// 복사 성공 메시지
function showCopySuccess() {
    const copyBtn = document.querySelector('.btn-copy');
    if (copyBtn) {
        const originalText = copyBtn.textContent;
        copyBtn.textContent = '✅';
        copyBtn.disabled = true;
        setTimeout(() => {
            copyBtn.textContent = originalText;
            copyBtn.disabled = false;
        }, 1500);
    }
}

// HTML 이스케이프
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 토스트 알림 표시
function showToast(message, type = 'info') {
    // 기존 토스트 제거
    const existingToast = document.querySelector('.toast-notification');
    if (existingToast) {
        existingToast.remove();
    }

    const toast = document.createElement('div');
    toast.className = `toast-notification toast-${type}`;
    toast.textContent = message;
    document.body.appendChild(toast);

    // 애니메이션을 위해 약간의 딜레이 후 show 클래스 추가
    requestAnimationFrame(() => {
        toast.classList.add('show');
    });

    // 3초 후 자동 제거
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}
