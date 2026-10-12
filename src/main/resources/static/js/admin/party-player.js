/**
 * 파티 플레이어 창 — /admin/party/player
 *
 * 휴대폰을 콘솔로 쓸 때 노트북에서 소리를 낸다. 1초마다 GET /admin/party/state(보드 상태 — 정답 없음)를 읽고
 * player.seq 가 바뀌면 cmd(PLAY·PAUSE·RESTART)를 한 번 실행한다. 콘솔(party-console.js)의 "이 창" 재생과 같은 규칙:
 * - 창을 열기 전·[플레이어 시작] 전에 온 명령은 실행하지 않는다(열자마자 노래가 나오지 않게).
 * - 문제가 바뀌면(영상·시작초·라운드) 시작 위치로 다시 걸고, 정답 공개로 넘어가도 이어 듣는다.
 * - 문제가 내려가거나 소리 없는 문제면 멈춘다.
 * 자동 재생 정책 때문에 [플레이어 시작] 클릭 한 번이 필요하다.
 */
(function () {
    'use strict';

    const POLL_MS = 1000;
    const CATEGORY_LABEL = {
        SONG: '노래', SCREEN: '드라마·영화', ANIME: '애니', GAME: '게임',
        PERSON: '인물', QUIZ: '초성·이모지', SOUND: '시그널·CM'
    };

    let state = null;
    let lastSeq = null;
    let started = false;
    let ready = false;
    let cuedKey = null;
    let loggedOut = false;

    const $ = (id) => document.getElementById(id);

    async function poll() {
        if (loggedOut) return;
        let response;
        try {
            response = await fetch('/admin/party/state', { headers: { Accept: 'application/json' } });
        } catch (e) {
            showBanner('서버 응답 없음 — 앱이 켜져 있는지 확인하세요. 자동으로 다시 연결합니다.');
            return;
        }
        const type = response.headers.get('content-type') || '';
        if (response.redirected || !type.includes('application/json')) {
            loggedOut = true;
            showBanner('로그아웃됨 (세션 만료 또는 앱 재시작) — 이 창을 새로고침해 다시 로그인하세요.');
            return;
        }
        const next = await response.json();
        if (next.success === false) return;
        $('pcBanner').hidden = true;
        const changed = !state || state.version !== next.version;
        state = next;
        if (changed) {
            syncItem();
            follow();
        }
        renderStatus();
    }

    /** 지금 떠 있는 소리 문제. 없으면 null. */
    function audioItem() {
        const onBoard = state && (state.phase === 'SHOW' || state.phase === 'REVEAL');
        const item = onBoard ? state.item : null;
        return item && item.presentation === 'AUDIO' && item.videoId ? item : null;
    }

    function syncItem() {
        const item = audioItem();
        if (!item) {
            if (ready && cuedKey) YouTubePlayerManager.stop();
            cuedKey = null;
            return;
        }
        if (!ready) return;
        const key = item.videoId + ':' + (item.startTime || 0) + ':' + state.round;
        if (key === cuedKey) return;
        cuedKey = key;
        $('ppWarn').hidden = true;
        YouTubePlayerManager.loadVideo(item.videoId, item.startTime || 0);
    }

    function follow() {
        const player = state.player;
        if (lastSeq === null || !started) {
            lastSeq = player.seq;  // 시작 전 명령은 건너뛴다
            return;
        }
        if (player.seq === lastSeq) return;
        lastSeq = player.seq;
        const item = audioItem();
        if (!player.cmd || !item) return;
        if (!ready) {
            showWarn('플레이어가 아직 준비되지 않았습니다 — 콘솔에서 잠시 뒤 다시 누르세요');
            return;
        }
        if (player.cmd === 'PLAY') {
            YouTubePlayerManager.play();
        } else if (player.cmd === 'PAUSE') {
            YouTubePlayerManager.pause();
        } else if (player.cmd === 'RESTART') {
            YouTubePlayerManager.seekTo(item.startTime || 0);
            YouTubePlayerManager.play();
        }
    }

    function renderStatus() {
        const item = audioItem();
        const head = !started ? '시작 전' : !ready ? '플레이어 준비 중' : '명령 대기';
        const cmd = state.player.cmd && item ? ' · 마지막 명령 #' + state.player.seq + ' ' + state.player.cmd : '';
        $('ppStatus').textContent = head + cmd;

        const onBoard = state.phase === 'SHOW' || state.phase === 'REVEAL';
        if (!onBoard || !state.item) {
            $('ppItem').textContent = '라운드 ' + state.round + ' · 문제가 떠 있지 않음';
        } else if (!item) {
            $('ppItem').textContent = (CATEGORY_LABEL[state.item.category] || state.item.category)
                + ' · 소리 없는 문제(' + state.item.presentation + ')';
        } else {
            $('ppItem').textContent = (CATEGORY_LABEL[item.category] || item.category)
                + (item.subCategory ? ' · ' + item.subCategory : '')
                + ' · ' + formatSeconds(item.startTime || 0) + ' 부터';
        }
    }

    function start() {
        if (started) return;
        started = true;
        $('ppStart').hidden = true;
        if (state) lastSeq = state.player.seq;
        YouTubePlayerManager.init('ppYoutube', {
            width: '480',
            height: '270',
            onError: (event, error) => {
                showWarn('재생 불가: ' + error.message + ' — 콘솔에서 [거두기]');
            }
        }).then(() => {
            ready = true;
            if (state) {
                syncItem();
                renderStatus();
            }
        });
        if (state) renderStatus();
    }

    function formatSeconds(total) {
        return Math.floor(total / 60) + ':' + String(total % 60).padStart(2, '0');
    }

    function showWarn(message) {
        $('ppWarn').textContent = message;
        $('ppWarn').hidden = false;
    }

    function showBanner(message) {
        $('pcBanner').textContent = message;
        $('pcBanner').hidden = false;
    }

    $('ppStartBtn').addEventListener('click', start);
    poll();
    setInterval(poll, POLL_MS);
})();
