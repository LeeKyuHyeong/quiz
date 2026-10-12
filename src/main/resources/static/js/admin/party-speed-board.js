/**
 * 스피드퀴즈 보드(TV) — /admin/party/speed/board
 *
 * 1초마다 GET /admin/party/speed/state 를 읽고 버전과 무관하게 매번 다시 그린다
 * (시간이 흐르면 조작 없이도 phase·remainingSeconds·word 가 바뀐다). 타이머는 서버의 remainingSeconds 로만.
 * 제시어는 "보드 표시" 설정일 때만 서버가 싣는다 — 맞히는 사람은 TV 에 등을 돌린다.
 * 팀 이름은 본게임 상태(/admin/party/state)에서 — 열 때와 10초마다. 연결 끊김은 구석에 작게. 전부 textContent.
 */
(function () {
    'use strict';

    const POLL_MS = 1000;
    const NAMES_EVERY = 10;
    const MODE_LABEL = { TALK: '말로 설명', BODY: '몸으로 설명' };

    let names = { A: '남팀', B: '여팀' };
    let loggedOut = false;
    let pollCount = 0;

    const $ = (id) => document.getElementById(id);
    const root = $('partySpeedBoard');

    async function getJson(path) {
        let response;
        try {
            response = await fetch(path, { headers: { Accept: 'application/json' } });
        } catch (e) {
            showConn('연결 끊김 · 다시 연결 중');
            return null;
        }
        const type = response.headers.get('content-type') || '';
        if (response.redirected || !type.includes('application/json')) {
            loggedOut = true;
            showConn('로그아웃됨 · 노트북에서 다시 로그인');
            return null;
        }
        $('sbConn').hidden = true;
        return response.json();
    }

    async function poll() {
        if (loggedOut) return;
        if (pollCount++ % NAMES_EVERY === 0) {
            const game = await getJson('/admin/party/state');
            if (game && game.teamNames) names = game.teamNames;
        }
        const state = await getJson('/admin/party/speed/state');
        if (!state || state.success === false) return;
        render(state);
    }

    function render(state) {
        const phase = state.phase;
        root.dataset.phase = phase;
        const team = state.turnTeam;
        const showRun = phase === 'RUN' || phase === 'OVER';

        $('sbWait').hidden = phase !== 'WAIT';
        $('sbRun').hidden = !showRun;
        $('sbResult').hidden = phase !== 'RESULT';
        $('sbScore').hidden = !showRun;

        $('sbCat').textContent = showRun
            ? '스피드퀴즈 · ' + name(team) + ' · ' + (MODE_LABEL[state.mode] || '')
            : phase === 'RESULT' ? '스피드퀴즈 결과' : '스피드퀴즈';

        if (phase === 'WAIT') {
            $('sbWaitSub').textContent = name('A') + ' ' + state.limits.A + '초 · ' + name('B') + ' ' + state.limits.B
                + '초 · 정답 수로 승부 · 이긴 팀이 본게임 첫 대분류 선택';
        }

        if (showRun) {
            const result = state.results[team];
            const remaining = state.remainingSeconds || 0;
            $('sbTime').textContent = Math.floor(remaining / 60) + ':' + String(remaining % 60).padStart(2, '0');
            $('sbTime').classList.toggle('warn', phase === 'OVER' || remaining <= 10);
            const word = $('sbWord');
            word.classList.toggle('muted', phase === 'OVER' || !state.word);
            word.textContent = phase === 'OVER' ? '시간 종료' : (state.word || '제시어는 출제자만');
            $('sbCorrect').textContent = result.correct;
            $('sbPass').textContent = result.pass;
            $('sbScoreCorrect').textContent = result.correct;
            $('sbScoreTeam').className = 'pb-team ' + (team === 'A' ? 'a' : 'b');
        }

        if (phase === 'RESULT') {
            ['A', 'B'].forEach((t) => {
                $('sbBigName' + t).textContent = name(t) + ' · ' + state.limits[t] + '초';
                $('sbBigCorrect' + t).textContent = state.results[t].correct;
                $('sbBig' + t).classList.toggle('win', state.firstPick === t);
            });
            $('sbFirstPick').textContent = state.firstPick
                ? '첫 대분류 선택권 → ' + name(state.firstPick)
                : '동점 — MC 재량';
        }

        $('sbFooter').textContent = showRun
            ? (state.word ? '맞히는 사람은 TV 에 등 돌리기 · 패스 무제한' : '패스 무제한')
            : '';
    }

    function name(team) {
        return (team && names[team]) || team || '';
    }

    function showConn(message) {
        $('sbConn').textContent = message;
        $('sbConn').hidden = false;
    }

    $('sbFullscreen').addEventListener('click', () => {
        if (document.documentElement.requestFullscreen) {
            document.documentElement.requestFullscreen().catch(() => {
                // 브라우저가 막으면 F11 로
            });
        }
    });
    document.addEventListener('fullscreenchange', () => {
        $('sbFullscreen').hidden = !!document.fullscreenElement;
    });

    poll();
    setInterval(poll, POLL_MS);
})();
