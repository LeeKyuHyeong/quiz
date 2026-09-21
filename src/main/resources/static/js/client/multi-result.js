/**
 * client/game/multi/result.html - 멀티게임 결과
 *
 * 주의: roomCode, isHost, myMemberId 변수는 HTML에서 Thymeleaf로 설정해야 함
 */

let pollingInterval = null;

// 페이지 로드 시 WebSocket 연결 — 방장도 연결한다. 서버는 방 토픽 연결이 모두 끊긴 참가자를 유예 뒤 내보내므로(O-018),
// 방장이 결과 화면에서 연결이 없으면 플레이 화면 연결이 끊긴 뒤 재시작한 방에서 방장이 나가게 될 수 있다.
document.addEventListener('DOMContentLoaded', function() {
    connectResultWebSocket();
});

// WebSocket 연결 (polling fallback 포함)
function connectResultWebSocket() {
    GameWebSocket.connect(roomCode, {
        RESTART: function(payload) {
            if (isHost) {
                return;  // 재시작한 방장은 restartGame() 이 직접 대기실로 이동한다
            }
            showRestartNotice();
            setTimeout(function() {
                window.location.href = '/game/multi/room/' + roomCode;
            }, 1500);
        },
        KICKED: function(payload) {
            if (payload && payload.targetMemberId === myMemberId) {
                GameWebSocket.disconnect();
                stopRestartPolling();
                window.location.href = '/game/multi';
            }
        }
    }, function() {
        // fallback: WebSocket 연결 실패 시 polling 사용
        startRestartPolling();
    });
}

// 재시작 감지 폴링 시작 (fallback)
function startRestartPolling() {
    pollingInterval = setInterval(checkRoomStatus, 2000);
}

// 폴링 중지
function stopRestartPolling() {
    if (pollingInterval) {
        clearInterval(pollingInterval);
        pollingInterval = null;
    }
}

// 방 상태 확인 (fallback polling)
async function checkRoomStatus() {
    try {
        const response = await fetch('/game/multi/room/' + roomCode + '/status');
        const result = await response.json();

        // 방이 WAITING 상태면 재시작됨 → 대기실로 이동
        if (result.status === 'WAITING') {
            stopRestartPolling();
            showRestartNotice();
            setTimeout(function() {
                window.location.href = '/game/multi/room/' + roomCode;
            }, 1500);
        }

        // 방이 없거나 강퇴됨 → 로비로 이동
        if (!result.success || result.kicked) {
            stopRestartPolling();
            window.location.href = '/game/multi';
        }
    } catch (error) {
        // console.error('상태 확인 오류:', error);
    }
}

// 재시작 알림 표시
function showRestartNotice() {
    const notice = document.getElementById('restartNotice');
    if (notice) {
        notice.style.display = 'block';
    }
}

async function restartGame() {
    if (!isHost) {
        showToast('방장만 게임을 재시작할 수 있습니다.');
        return;
    }

    if (!confirm('같은 설정으로 한번 더 게임을 진행하시겠습니까?')) {
        return;
    }

    try {
        const response = await fetch('/game/multi/room/' + roomCode + '/restart', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });

        const result = await response.json();

        if (result.success) {
            // 대기실로 이동
            window.location.href = '/game/multi/room/' + roomCode;
        } else {
            showToast(result.message || '게임 재시작에 실패했습니다.');
        }
    } catch (error) {
        // console.error('재시작 오류:', error);
        showToast('게임 재시작 중 오류가 발생했습니다.');
    }
}

async function goToLobby() {
    GameWebSocket.disconnect();
    stopRestartPolling();
    // 명시적 로비 이동 - 재시작 대상에서 제외하기 위해 별도 엔드포인트 사용
    try {
        await fetch('/game/multi/room/' + roomCode + '/leave-to-lobby', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });
    } catch (error) {
        // 나가기 실패해도 로비로 이동
        Debug.log('나가기 처리 중 오류 (무시됨):', error);
    }
    window.location.href = '/game/multi';
}
