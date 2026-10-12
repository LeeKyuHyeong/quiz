/**
 * 스피드퀴즈 콘솔(MC) — /admin/party/speed
 *
 * 서버 계약(docs/party-status.md "서버 계약"):
 * - 1초마다 GET /admin/party/speed/console/state. 스피드퀴즈는 시간이 흐르면 버전이 그대로여도
 *   phase·remainingSeconds 가 바뀌므로 버전과 무관하게 매번 다시 그린다.
 * - 타이머는 서버의 remainingSeconds 로만 그린다. 화면 시계로 0 을 계산해 /finish 를 보내지 않는다(서버가 끝낸다).
 * - 조작은 POST /admin/party/speed/<동작> + version, 응답까지 잠금. 공통 fetch 래퍼가 {success:false} 를
 *   200 으로 바꾸므로 본문으로 판단하고, 실패하면 상태를 다시 받는다.
 * - 주제별 남은 수(/topics)는 DB 를 읽으므로 열 때와 조작 응답에서만 받는다.
 * - 팀 이름은 본게임 상태(/admin/party/state)에 있다 — 열 때와 10초마다.
 * 제시어·로그는 전부 textContent.
 */
(function () {
    'use strict';

    const POLL_MS = 1000;
    const NAMES_EVERY = 10;
    const BASE = '/admin/party/speed/';
    const ALL = '전체';
    const MODE_LABEL = { TALK: '말로', BODY: '몸으로' };
    const PHASE_LABEL = { WAIT: '대기', RUN: '진행 중', OVER: '시간 종료', RESULT: '두 팀 결과' };

    let state = null;
    let topics = null;
    let names = { A: '남팀', B: '여팀' };
    let busy = false;
    let loggedOut = false;
    let pollCount = 0;
    // 설정 초안 — 고치는 중이면 폴링이 덮어쓰지 않는다
    let draft = null;
    let dirty = false;

    const $ = (id) => document.getElementById(id);
    const root = $('partySpeedConsole');

    // ---------- 통신 ----------

    async function request(method, path, body) {
        const init = { method, headers: { Accept: 'application/json' } };
        if (body) {
            init.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
            init.body = body;
        }
        let response;
        try {
            response = await fetch(path, init);
        } catch (e) {
            showBanner('서버 응답 없음 — 앱이 켜져 있는지 확인하세요. 자동으로 다시 연결합니다.');
            return null;
        }
        const type = response.headers.get('content-type') || '';
        if (response.redirected || !type.includes('application/json')) {
            showLoggedOut();
            return null;
        }
        if (!loggedOut) $('pcBanner').hidden = true;
        return response.json();
    }

    async function refreshState() {
        const data = await request('GET', BASE + 'console/state');
        if (!data || data.success === false) return;
        state = data;
        render();
    }

    async function loadTopics() {
        const data = await request('GET', BASE + 'topics');
        if (!data || data.success === false) return;
        topics = data;
        renderSetup();
    }

    async function loadNames() {
        const data = await request('GET', '/admin/party/state');
        if (!data || !data.teamNames) return;
        names = data.teamNames;
        if (state) render();
    }

    /** @returns 적용됐으면 true */
    async function act(action, params) {
        if (busy || !state || loggedOut) return false;
        setBusy(true);
        try {
            const body = params instanceof URLSearchParams ? params : new URLSearchParams(params || {});
            body.set('version', state.board.version);
            const data = await request('POST', BASE + action, body);
            if (!data) return false;
            if (data.success) {
                hideNotice();
                state = data.state;
                if (data.topics) topics = data.topics;
                render();
                return true;
            }
            showNotice(data.stale
                ? '다른 창에서 먼저 바뀌었습니다 — 최신 상태로 다시 그렸습니다. 필요하면 다시 누르세요.'
                : (data.message || '처리하지 못했습니다'));
            await refreshState();
            return false;
        } finally {
            setBusy(false);
        }
    }

    // ---------- 그리기 ----------

    function render() {
        const board = state.board;
        const phase = board.phase;
        $('spPhase').textContent = PHASE_LABEL[phase] || phase;

        renderTurn(board, phase);
        renderResult(board, phase);
        if (!dirty) draft = draftFrom(state.setup);
        renderSetup();
        setBusy(busy);
    }

    function renderTurn(board, phase) {
        const running = phase === 'RUN';
        const turnTeam = board.turnTeam;
        $('spTurnCard').hidden = phase === 'RESULT';

        $('spTurnTitle').textContent = phase === 'WAIT' ? '턴 — 시작할 팀을 고르세요'
            : running ? name(turnTeam) + ' 진행 중 · ' + MODE_LABEL[board.mode] + ' 설명'
                : phase === 'OVER' ? name(turnTeam) + ' 턴 끝' : '턴';

        ['A', 'B'].forEach((team) => {
            const button = $('spStart' + team);
            const done = board.results[team].done;
            button.textContent = done ? name(team) + ' 끝남' : '▶ ' + name(team) + ' 시작 (' + board.limits[team] + '초)';
            setEnabled(button, !running && !done);
        });
        $('spStartRow').hidden = running;

        const showRun = running || phase === 'OVER';
        $('spRun').hidden = !showRun;
        if (!showRun) return;

        const remaining = board.remainingSeconds || 0;
        $('spTimer').textContent = formatSeconds(remaining);
        $('spTimer').classList.toggle('warn', remaining <= 10);
        $('spWord').textContent = running ? (state.word || '—') : '시간 종료';
        $('spWord').classList.toggle('over', !running);
        $('spOverLine').hidden = running;

        setEnabled($('spCorrect'), running);
        setEnabled($('spPass'), running);
        setEnabled($('spUndo'), state.canUndo);
        setEnabled($('spFinish'), running);

        const result = board.results[turnTeam];
        $('spCountCorrect').textContent = result.correct;
        $('spCountPass').textContent = result.pass;
        $('spCountTotal').textContent = state.log.length;

        const list = $('spLog');
        list.replaceChildren();
        state.log.forEach((entry) => {
            const li = document.createElement('li');
            li.textContent = entry.word;
            if (!entry.correct) li.className = 'pass';
            list.append(li);
        });
    }

    function renderResult(board, phase) {
        $('spResultCard').hidden = phase !== 'RESULT';
        if (phase !== 'RESULT') return;
        ['A', 'B'].forEach((team) => {
            $('spResName' + team).textContent = name(team) + ' · ' + board.limits[team] + '초';
            $('spResCorrect' + team).textContent = board.results[team].correct;
            $('spResPass' + team).textContent = '패스 ' + board.results[team].pass;
        });
        $('spFirstPick').textContent = board.firstPick
            ? '첫 대분류 선택권: ' + name(board.firstPick)
            : '동점 — MC 재량(재대결 가능)';
    }

    function renderSetup() {
        if (!state || !draft) return;
        const locked = state.board.phase === 'RUN';
        $('spSetupLock').textContent = locked ? '(턴 진행 중엔 못 바꿈)' : '';

        const box = $('spTopics');
        box.replaceChildren();
        const entries = topics ? Object.entries(topics) : [];
        entries.forEach(([topic, count]) => {
            const isAll = topic === ALL;
            const on = isAll ? draft.topics.size === 0 : draft.topics.has(topic);
            const chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'pc-chip' + (on ? ' on' : '');
            chip.textContent = topic + ' ' + count;
            chip.disabled = locked || (!isAll && count === 0 && !on);
            chip.addEventListener('click', () => {
                if (isAll) {
                    draft.topics.clear();
                } else if (draft.topics.has(topic)) {
                    draft.topics.delete(topic);
                } else {
                    draft.topics.add(topic);
                }
                markDirty();
            });
            box.append(chip);
        });

        ['A', 'B'].forEach((team) => {
            $('spLimitName' + team).textContent = name(team);
            const input = $('spLimit' + team);
            if (document.activeElement !== input) input.value = draft.limits[team];
            input.disabled = locked;
        });
        root.querySelectorAll('[data-mode]').forEach((chip) => {
            chip.classList.toggle('on', chip.dataset.mode === draft.mode);
            chip.disabled = locked;
        });
        root.querySelectorAll('[data-board]').forEach((chip) => {
            chip.classList.toggle('on', chip.dataset.board === String(draft.wordOnBoard));
            chip.disabled = locked;
        });
        $('spSave').disabled = locked || busy;
        $('spDirty').textContent = dirty ? '저장 안 됨' : '';
    }

    function draftFrom(setup) {
        return {
            topics: new Set(setup.topics || []),
            limits: { A: setup.limitSeconds.A, B: setup.limitSeconds.B },
            mode: setup.mode,
            wordOnBoard: setup.wordOnBoard
        };
    }

    function markDirty() {
        dirty = true;
        renderSetup();
    }

    async function saveSetup() {
        const body = new URLSearchParams();
        draft.topics.forEach((topic) => body.append('topic', topic));
        body.set('limitA', $('spLimitA').value);
        body.set('limitB', $('spLimitB').value);
        body.set('mode', draft.mode);
        body.set('wordOnBoard', String(draft.wordOnBoard));
        if (await act('setup', body)) {
            dirty = false;
            draft = draftFrom(state.setup);
            renderSetup();
            showNotice('설정을 저장했습니다');
        }
    }

    // ---------- 도우미 ----------

    function name(team) {
        return (team && names[team]) || team || '';
    }

    function formatSeconds(total) {
        return Math.floor(total / 60) + ':' + String(total % 60).padStart(2, '0');
    }

    function setEnabled(button, enabled) {
        button.dataset.off = enabled ? '' : '1';
        button.disabled = busy || loggedOut || !enabled;
    }

    function setBusy(value) {
        busy = value;
        root.querySelectorAll('[data-act]').forEach((button) => {
            button.disabled = busy || loggedOut || button.dataset.off === '1';
        });
        if (state) $('spSave').disabled = busy || state.board.phase === 'RUN';
    }

    function showNotice(message) {
        $('pcNotice').textContent = message;
        $('pcNotice').hidden = false;
    }

    function hideNotice() {
        $('pcNotice').hidden = true;
    }

    function showBanner(message) {
        $('pcBanner').textContent = message;
        $('pcBanner').hidden = false;
    }

    function showLoggedOut() {
        if (loggedOut) return;
        loggedOut = true;
        const banner = $('pcBanner');
        const text = document.createElement('span');
        text.textContent = '로그아웃됨 (세션 만료 또는 앱 재시작) — ';
        const link = document.createElement('a');
        link.textContent = '다시 로그인';
        link.href = '/auth/login';
        banner.replaceChildren(text, link);
        banner.hidden = false;
        setBusy(false);
    }

    // ---------- 시작 ----------

    root.addEventListener('click', (event) => {
        const button = event.target.closest('[data-act]');
        if (!button || button.disabled) return;
        if (button.dataset.confirm && !window.confirm(button.dataset.confirm)) return;
        const params = {};
        if (button.dataset.team) params.team = button.dataset.team;
        act(button.dataset.act, params).then((applied) => {
            if (applied && button.dataset.act === 'reset') {
                dirty = false;
                draft = draftFrom(state.setup);
                loadTopics();
            }
        });
    });
    root.querySelectorAll('[data-mode]').forEach((chip) => {
        chip.addEventListener('click', () => {
            draft.mode = chip.dataset.mode;
            markDirty();
        });
    });
    root.querySelectorAll('[data-board]').forEach((chip) => {
        chip.addEventListener('click', () => {
            draft.wordOnBoard = chip.dataset.board === 'true';
            markDirty();
        });
    });
    ['A', 'B'].forEach((team) => {
        $('spLimit' + team).addEventListener('input', () => {
            draft.limits[team] = $('spLimit' + team).value;
            markDirty();
        });
    });
    $('spSave').addEventListener('click', saveSetup);

    loadNames();
    refreshState().then(loadTopics);
    setInterval(() => {
        if (busy || loggedOut) return;
        pollCount++;
        if (pollCount % NAMES_EVERY === 0) loadNames();
        refreshState();
    }, POLL_MS);
})();
