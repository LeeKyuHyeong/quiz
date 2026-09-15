// 페이지 로드 시 방 목록 갱신
document.addEventListener('DOMContentLoaded', function() {
    // 코드 입력란 자동 대문자 변환
    const codeInput = document.getElementById('joinCode');
    codeInput.addEventListener('input', function() {
        this.value = this.value.toUpperCase().replace(/[^A-Z0-9]/g, '');
    });

    // Enter 키로 참가
    codeInput.addEventListener('keydown', function(e) {
        if (e.key === 'Enter') {
            joinByCode();
        }
    });
});

// 코드로 참가
async function joinByCode() {
    const code = document.getElementById('joinCode').value.trim();

    if (!code) {
        showToast('참가 코드를 입력해주세요.');
        return;
    }

    if (code.length !== 6) {
        showToast('참가 코드는 6자리입니다.');
        return;
    }

    await joinRoom(code);
}

// 방 참가
async function joinRoom(roomCode) {
    try {
        const response = await fetch(`/game/multi/join/${roomCode}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({})
        });

        const result = await response.json();

        if (result.success) {
            window.location.href = `/game/multi/room/${result.roomCode}`;
        } else {
            showToast(result.message || '방 참가에 실패했습니다.');
        }
    } catch (error) {
        // console.error('참가 오류:', error);
        showToast('방 참가 중 오류가 발생했습니다.');
    }
}

// 방 목록 로드
async function loadRooms() {
    const keyword = document.getElementById('searchKeyword').value.trim();

    try {
        let url = '/game/multi/rooms';
        if (keyword) {
            url += `?keyword=${encodeURIComponent(keyword)}`;
        }

        const response = await fetch(url);
        const rooms = await response.json();

        renderRoomList(rooms);
    } catch (error) {
        // console.error('방 목록 로드 오류:', error);
    }
}

// 방 검색
let searchTimeout;
function searchRooms() {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(loadRooms, 300);
}

// 방 목록 렌더링
function renderRoomList(rooms) {
    const container = document.getElementById('roomList');

    if (rooms.length === 0) {
        container.innerHTML = `
            <div class="empty-rooms">
                <div class="empty-icon">🎵</div>
                <p>참가 가능한 방이 없습니다.</p>
                <p class="empty-sub">새로운 방을 만들어보세요!</p>
            </div>
        `;
        return;
    }

    // 비공개 방은 서버가 roomCode 를 내려주지 않는다. 카드는 보이되 입장 버튼은 안내만 한다.
    container.innerHTML = rooms.map(room => `
        <div class="room-card"${room.isPrivate ? '' : ` data-room-code="${room.roomCode}"`}>
            <div class="room-info">
                <div class="room-name">${room.isPrivate ? '<span class="room-private-icon" title="비공개 방">🔒</span> ' : ''}${escapeHtml(room.roomName)}</div>
                <div class="room-host">
                    <span class="host-icon">👑</span>
                    <span>${escapeHtml(room.hostNickname)}</span>
                </div>
            </div>
            <div class="room-meta">
                <div class="player-count">
                    <span class="count-icon">👥</span>
                    <span>${room.currentPlayers}/${room.maxPlayers}</span>
                </div>
                <div class="round-count">
                    <span class="round-icon">🎯</span>
                    <span>${room.totalRounds}라운드</span>
                </div>
            </div>
            ${room.isPrivate
                ? `<button type="button" class="btn btn-enter btn-enter-private" onclick="notifyPrivateRoom()">🔒 비공개</button>`
                : `<button type="button" class="btn btn-enter" onclick="joinRoom('${room.roomCode}')">입장</button>`}
        </div>
    `).join('');
}

// 비공개 방 카드 클릭 — 참가하지 않고 코드 입력으로 안내
function notifyPrivateRoom() {
    showToast('비공개 방입니다. 방장에게 받은 참가 코드를 입력해주세요.');
    const codeInput = document.getElementById('joinCode');
    if (codeInput) codeInput.focus();
}

// HTML 이스케이프
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 방 참가 정보 초기화
async function resetParticipation() {
    if (!confirm('모든 방 참가 정보를 초기화하시겠습니까?\n\n현재 참가중인 방에서 나가게 됩니다.')) {
        return;
    }

    try {
        const response = await fetch('/game/multi/reset-participation', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });

        const result = await response.json();

        if (result.success) {
            showToast(result.message);
            // 페이지 새로고침
            window.location.reload();
        } else {
            showToast(result.message || '초기화에 실패했습니다.');
        }
    } catch (error) {
        // console.error('초기화 오류:', error);
        showToast('초기화 중 오류가 발생했습니다.');
    }
}