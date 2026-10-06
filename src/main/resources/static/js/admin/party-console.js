/**
 * 파티 콘솔(MC) — /admin/party/console
 *
 * 서버 계약(docs/party-status.md "서버 계약"):
 * - 1초마다 GET /admin/party/console/state. 버전이 같으면 다시 그리지 않는다(본게임은 타이머를 안 그린다).
 * - 조작은 POST /admin/party/<동작> + version. 응답이 올 때까지 버튼을 잠근다.
 *   공통 fetch 래퍼가 {success:false} 오류를 200 으로 바꾸므로 상태 코드가 아니라 본문으로 판단한다.
 *   실패하면(낡은 화면 stale 포함) 상태를 다시 받는다.
 * - 남은 문제 수(/remaining)는 DB 를 읽으므로 폴링하지 않는다 — 화면을 열 때, 조작 응답,
 *   다른 창이 상태를 바꾼 것을 폴링이 알아챘을 때만 받는다.
 * - 응답이 JSON 이 아니면(로그인 화면으로 넘어감) "로그아웃됨".
 * 사용자 입력·문제 내용은 전부 textContent 로만 넣는다.
 */
(function () {
    'use strict';

    const POLL_MS = 1000;
    const BASE = '/admin/party/';
    const ALL = '전체';
    const PLAYER_MODE_KEY = 'party-player-mode';

    const CATEGORIES = [
        ['SONG', '노래'], ['SCREEN', '드라마·영화'], ['ANIME', '애니'], ['GAME', '게임'],
        ['PERSON', '인물'], ['QUIZ', '초성·이모지'], ['SOUND', '시그널·CM']
    ];
    const CATEGORY_LABEL = Object.fromEntries(CATEGORIES);

    // docs/party-content/README.md "MC 판정 규칙"
    const RULES = {
        SONG: '제목만 맞히면 정답. 가수 불필요. 인정 답안(음역 등) 인정',
        SCREEN: '작품명. 시즌·부제 없어도 인정. 줄임말은 인정 답안',
        ANIME: '한국판·일본판 제목 모두 인정. 극장판·TV판 구분 안 함',
        GAME: '중분류가 게임명이면 캐릭터·유닛·맵 이름, "게임 맞히기"면 게임 이름. 줄임말 인정',
        PERSON: '예명·본명·별명 모두 인정',
        QUIZ: '초성은 정확한 단어. 속담은 뜻이 같으면 어미 차이 인정',
        SOUND: '프로그램명·브랜드명. 줄임말 인정. CM송은 제품명·회사명 모두 인정'
    };
    const COMMON_RULE = '모르는 줄임말이면 "정식 이름?" 한 번 묻고, 카드의 정답을 대면 정답';

    const PHASE_LABEL = {
        WAIT: '대기', READY: '뽑음 · 아직 안 띄움', SHOW: '문제 표시 중',
        REVEAL: '정답 공개', SCORES: '점수판', END: '종료'
    };
    const BOARD_LABEL = {
        WAIT: '대기 화면', SHOW: '문제 표시', REVEAL: '정답 공개', SCORES: '점수판', END: '종료 화면'
    };
    const DIFFICULTY = { 1: '난이도 하', 2: '난이도 중', 3: '난이도 상' };
    const PICKABLE = ['WAIT', 'READY', 'SCORES'];

    let state = null;
    let remaining = null;
    let selected = { category: null, sub: ALL };
    let busy = false;
    let loggedOut = false;
    let lastPlayerSeq = null;
    let playerMode = loadPlayerMode();

    // YouTube 플레이어 — 처음 필요할 때 만든다
    let ytStarted = false;
    let ytReady = false;
    let cuedKey = null;

    const $ = (id) => document.getElementById(id);
    const root = $('partyConsole');

    // ---------- 통신 ----------

    async function request(method, path, params) {
        const init = { method, headers: { Accept: 'application/json' } };
        if (params) {
            init.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
            init.body = new URLSearchParams(params);
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
        if (!loggedOut) hideBanner();
        return response.json();
    }

    async function refreshState() {
        const data = await request('GET', BASE + 'console/state');
        if (!data || data.success === false) return;
        const changedElsewhere = state && data.board.version !== state.board.version;
        applyState(data);
        if (changedElsewhere) loadRemaining();
    }

    async function loadRemaining() {
        const data = await request('GET', BASE + 'remaining');
        if (!data || data.success === false) return;
        remaining = data;
        renderPick();
    }

    async function act(action, params) {
        if (busy || !state || loggedOut) return;
        setBusy(true);
        try {
            const data = await request('POST', BASE + action,
                Object.assign({ version: state.board.version }, params || {}));
            if (!data) return;
            if (data.success) {
                hideNotice();
                if (data.remaining) remaining = data.remaining;
                applyState(data.state);
                renderPick();
            } else {
                showNotice(data.stale
                    ? '다른 창에서 먼저 바뀌었습니다 — 최신 상태로 다시 그렸습니다. 필요하면 다시 누르세요.'
                    : (data.message || '처리하지 못했습니다'));
                await refreshState();
            }
        } finally {
            setBusy(false);
        }
    }

    // ---------- 상태 반영 ----------

    function applyState(next) {
        const previous = state;
        state = next;
        if (previous && previous.board.version === next.board.version) return;
        render();
        followPlayer();
    }

    function render() {
        const phase = state.phase;
        const board = state.board;

        $('pcRound').textContent = '라운드 ' + board.round;
        $('pcPhase').textContent = PHASE_LABEL[phase] || phase;
        ['A', 'B'].forEach((team) => {
            $('pcTeamName' + team).textContent = teamName(team);
            $('pcScore' + team).textContent = board.scores[team];
            $('pcCorrect' + team).textContent = teamName(team) + ' 정답';
            $('pcWrong' + team).textContent = teamName(team) + ' 오답';
        });

        $('pcPick').hidden = !PICKABLE.includes(phase);
        renderPick();
        renderCard(phase);
        renderPresent(phase);
        renderJudge(phase);
        renderReveal(phase);
        renderEnd(phase);
        renderSide(phase);
        setBusy(busy);
    }

    function renderPick() {
        if (!state) return;
        const categoryBox = $('pcCategories');
        categoryBox.replaceChildren();
        CATEGORIES.forEach(([key, label]) => {
            const count = countOf(key, ALL);
            const button = el('button', 'pc-cat' + (selected.category === key ? ' on' : ''));
            button.type = 'button';
            button.disabled = count === 0;
            button.append(el('b', '', key), el('small', '', label + ' · ' + formatCount(count)));
            button.addEventListener('click', () => {
                selected = { category: key, sub: ALL };
                renderPick();
            });
            categoryBox.append(button);
        });

        const subBox = $('pcSubCategories');
        subBox.replaceChildren();
        if (selected.category && remaining && remaining[selected.category]) {
            Object.entries(remaining[selected.category]).forEach(([sub, count]) => {
                const chip = el('button', 'pc-chip' + (selected.sub === sub ? ' on' : ''), sub + ' ' + formatCount(count));
                chip.type = 'button';
                chip.disabled = count === 0;
                chip.addEventListener('click', () => {
                    selected.sub = sub;
                    renderPick();
                });
                subBox.append(chip);
            });
        }

        const pickButton = $('pcPickBtn');
        const available = selected.category ? countOf(selected.category, selected.sub) : 0;
        pickButton.textContent = state.phase === 'READY' ? '다시 뽑기' : '랜덤 뽑기';
        setEnabled(pickButton, PICKABLE.includes(state.phase) && available > 0);
        $('pcPickHint').textContent = !remaining ? '남은 문제 수를 불러오는 중'
            : !selected.category ? '대분류를 고르세요'
                : CATEGORY_LABEL[selected.category] + ' · ' + selected.sub + ' · 남은 ' + formatCount(available)
                + (available === 0 ? ' — 다른 중분류를 고르세요' : '');
    }

    function renderCard(phase) {
        const card = state.card;
        const visible = !!card && ['READY', 'SHOW', 'REVEAL'].includes(phase);
        $('pcCard').hidden = !visible;
        if (!visible) return;

        const chips = $('pcCardChips');
        chips.replaceChildren(
            el('span', 'pc-chip on', card.category),
            el('span', 'pc-chip', card.subCategory || ALL),
            el('span', 'pc-chip', card.presentation + ' · ' + patternOf(card)));
        if (DIFFICULTY[card.difficulty]) chips.append(el('span', 'pc-chip', DIFFICULTY[card.difficulty]));

        $('pcAnswer').textContent = card.answer;
        $('pcAliases').textContent = card.aliases || '—';
        $('pcDetail').textContent = card.detail || '—';
        $('pcSource').textContent = card.source || '—';
        const item = state.item;
        const isAudio = item && item.presentation === 'AUDIO';
        $('pcClipLabel').hidden = !isAudio;
        $('pcClip').hidden = !isAudio;
        if (isAudio) {
            $('pcClip').textContent = formatSeconds(item.startTime || 0) + ' 부터'
                + (item.duration ? ' ' + item.duration + '초' : '');
        }
        $('pcRule').textContent = '판정 규칙 · ' + card.category + ': ' + (RULES[card.category] || '')
            + ' / 공통: ' + COMMON_RULE;
        $('pcReadyActions').hidden = phase !== 'READY';
    }

    function renderPresent(phase) {
        const item = state.item;
        const visible = !!item && ['READY', 'SHOW', 'REVEAL'].includes(phase);
        $('pcPresent').hidden = !visible;
        if (!visible) {
            stopPlayer();
            return;
        }
        $('pcPresentTitle').textContent = phase === 'READY' ? '미리보기 — 참가자에겐 아직 안 보임' : '제시';

        const isAudio = item.presentation === 'AUDIO';
        $('pcAudio').hidden = !isAudio;
        $('pcImage').hidden = item.presentation !== 'IMAGE';
        $('pcText').hidden = item.presentation !== 'TEXT';

        if (isAudio) {
            $('pcPreviewControls').hidden = phase !== 'READY';
            $('pcPlayControls').hidden = phase === 'READY';
            if (!item.videoId) {
                showPlayerWarn('영상 정보가 없습니다 — 띄우기 전이면 다시 뽑으세요');
            } else {
                cueCurrent();
            }
        } else {
            stopPlayer();
        }

        if (item.presentation === 'IMAGE') {
            const img = $('pcImg');
            if (img.getAttribute('src') !== item.imageUrl) {
                $('pcImageWarn').hidden = true;
                img.src = item.imageUrl;
            }
        }
        if (item.presentation === 'TEXT') {
            $('pcText').textContent = item.questionText || '';
        }
        renderHints(phase);
    }

    function renderHints(phase) {
        const card = state.card;
        const box = $('pcHints');
        const hints = card && card.hints ? card.hints : [];
        box.hidden = hints.length === 0;
        box.replaceChildren();
        if (hints.length === 0) return;
        hints.forEach((hint, index) => {
            const opened = index < card.hintsOpened;
            const row = el('div', 'pc-hint' + (opened ? ' open' : ''));
            row.append(el('span', 'pc-hint-k', '힌트 ' + (index + 1)), el('span', 'pc-hint-v', hint),
                el('span', 'pc-hint-s', opened ? '보드에 보임' : '안 열림'));
            box.append(row);
        });
        const openButton = el('button', 'pc-btn', '다음 힌트 열기 (' + card.hintsOpened + '/' + hints.length + ')');
        openButton.type = 'button';
        openButton.dataset.act = 'hint';
        setEnabled(openButton, phase === 'SHOW' && card.hintsOpened < hints.length);
        box.append(openButton);
    }

    function renderJudge(phase) {
        $('pcJudge').hidden = phase !== 'SHOW';
        if (phase !== 'SHOW') return;
        const board = state.board;
        const status = $('pcJudgeStatus');
        status.className = 'pc-status';
        if (board.freeChallenge) {
            status.classList.add('free');
            status.textContent = '양 팀 모두 오답 → 자유 도전 (보드에 표시됨) · 먼저 맞히는 팀이 득점, 끝까지 안 나오면 [못 맞힘]';
        } else if (board.wrongTeam) {
            status.textContent = teamName(board.wrongTeam) + ' 오답 → ' + teamName(otherTeam(board.wrongTeam))
                + ' 기회 (보드에 표시됨 · 점수 무관)';
        } else {
            status.textContent = state.item && state.item.presentation === 'AUDIO'
                ? '재생 중 "정답!" 외치면 [일시정지] → 답을 듣고 판정'
                : '먼저 손 든 사람의 답을 듣고 판정';
        }
    }

    function renderReveal(phase) {
        $('pcReveal').hidden = phase !== 'REVEAL';
        if (phase !== 'REVEAL' || !state.board.reveal) return;
        const reveal = state.board.reveal;
        $('pcRevealStatus').textContent = '보드: ' + reveal.answer + ' · '
            + (reveal.scoringTeam ? teamName(reveal.scoringTeam) + ' +1' : '못 맞힘');
    }

    function renderEnd(phase) {
        $('pcEnd').hidden = phase !== 'END';
        if (phase !== 'END') return;
        const scores = state.board.scores;
        $('pcEndStatus').textContent = '최종 ' + teamName('A') + ' ' + scores.A + ' : ' + scores.B + ' ' + teamName('B')
            + ' — 다음 판은 설정의 [새 게임]';
    }

    function renderSide(phase) {
        const boardPhase = state.board.phase;
        $('pcBoardStatus').textContent = (BOARD_LABEL[boardPhase] || boardPhase)
            + (phase === 'READY' ? ' (뽑은 문제는 아직 TV 에 안 보임)' : '');
        setEnabled($('pcScoresBtn'), phase !== 'SHOW' && phase !== 'END' && phase !== 'SCORES');
        setEnabled($('pcWaitBtn'), phase === 'SCORES' || phase === 'END');

        const list = $('pcHistory');
        list.replaceChildren();
        const history = state.history || [];
        if (history.length === 0) {
            list.append(el('li', 'pc-muted', '아직 없음'));
        }
        history.slice().reverse().forEach((entry) => {
            const li = el('li');
            li.append(el('span', '', entry.round + ' · ' + (CATEGORY_LABEL[entry.category] || entry.category)
                + ' · ' + (entry.subCategory || ALL) + ' · ' + entry.answer));
            li.append(entry.scoringTeam
                ? el('span', 'pc-chip team-' + entry.scoringTeam.toLowerCase(), teamName(entry.scoringTeam))
                : el('span', 'pc-chip', '못 맞힘'));
            list.append(li);
        });
    }

    // ---------- 재생 ----------

    function ensurePlayer() {
        if (ytStarted) return;
        ytStarted = true;
        YouTubePlayerManager.init('pcYoutube', {
            width: '480',
            height: '270',
            onError: (event, error) => {
                showPlayerWarn('재생 불가: ' + error.message + ' — 띄우기 전이면 다시 뽑고, 띄운 뒤면 [거두기]');
            }
        }).then(() => {
            ytReady = true;
            cuedKey = null;
            if (state) cueCurrent();
        });
    }

    /** 문제가 바뀌거나 띄운 순간(라운드가 오름) 시작 위치로 다시 건다 — 미리 듣기 흔적을 지운다. */
    function cueCurrent() {
        ensurePlayer();
        const item = state.item;
        if (!ytReady || !item || item.presentation !== 'AUDIO' || !item.videoId) return;
        const key = item.videoId + ':' + (item.startTime || 0) + ':' + state.board.round + ':' + state.phase;
        if (key === cuedKey) return;
        // REVEAL 로 넘어갈 때는 다시 걸지 않는다(정답 공개 뒤에도 이어 듣는다)
        if (cuedKey && state.phase === 'REVEAL' && cuedKey.startsWith(item.videoId + ':' + (item.startTime || 0) + ':' + state.board.round + ':')) {
            cuedKey = key;
            return;
        }
        cuedKey = key;
        hidePlayerWarn();
        YouTubePlayerManager.loadVideo(item.videoId, item.startTime || 0);
    }

    function stopPlayer() {
        if (ytReady) YouTubePlayerManager.stop();
        cuedKey = null;
        hidePlayerWarn();
    }

    /** 서버의 재생 명령(seq 가 바뀔 때 한 번)을 이 창이 실행한다. 처음 받은 명령은 실행하지 않는다(새로고침). */
    function followPlayer() {
        const player = state.board.player;
        if (lastPlayerSeq === null) {
            lastPlayerSeq = player.seq;
            return;
        }
        if (player.seq === lastPlayerSeq) return;
        lastPlayerSeq = player.seq;
        if (playerMode !== 'here' || !player.cmd || !state.item || state.item.presentation !== 'AUDIO') return;
        if (!ytReady) {
            showPlayerWarn('플레이어가 아직 준비되지 않았습니다 — 잠시 뒤 다시 누르세요');
            return;
        }
        const start = state.item.startTime || 0;
        if (player.cmd === 'PLAY') {
            YouTubePlayerManager.play();
        } else if (player.cmd === 'PAUSE') {
            YouTubePlayerManager.pause();
        } else if (player.cmd === 'RESTART') {
            YouTubePlayerManager.seekTo(start);
            YouTubePlayerManager.play();
        }
    }

    function previewPlay() {
        if (!ytReady || !state || !state.item) return;
        YouTubePlayerManager.seekTo(state.item.startTime || 0);
        YouTubePlayerManager.play();
    }

    function previewStop() {
        if (!ytReady || !state || !state.item) return;
        YouTubePlayerManager.pause();
        YouTubePlayerManager.seekTo(state.item.startTime || 0);
    }

    function showPlayerWarn(message) {
        $('pcPlayerWarn').textContent = message;
        $('pcPlayerWarn').hidden = false;
    }

    function hidePlayerWarn() {
        $('pcPlayerWarn').hidden = true;
    }

    function loadPlayerMode() {
        try {
            return localStorage.getItem(PLAYER_MODE_KEY) === 'window' ? 'window' : 'here';
        } catch (e) {
            return 'here';
        }
    }

    // ---------- 화면 도우미 ----------

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function setEnabled(button, enabled) {
        button.dataset.off = enabled ? '' : '1';
        button.disabled = busy || !enabled;
    }

    function setBusy(value) {
        busy = value;
        root.classList.toggle('is-busy', value);
        root.querySelectorAll('[data-act]').forEach((button) => {
            button.disabled = busy || loggedOut || button.dataset.off === '1';
        });
    }

    function countOf(category, sub) {
        if (!remaining || !remaining[category]) return 0;
        return remaining[category][sub] || 0;
    }

    function formatCount(count) {
        return Number(count).toLocaleString('ko-KR');
    }

    function formatSeconds(total) {
        const minutes = Math.floor(total / 60);
        const seconds = String(total % 60).padStart(2, '0');
        return minutes + ':' + seconds;
    }

    function patternOf(card) {
        if (card.presentation !== 'AUDIO') return '정지형';
        return card.hints && card.hints.length > 0 ? '재생형+힌트' : '재생형';
    }

    function teamName(team) {
        return (state && state.board.teamNames[team]) || team;
    }

    function otherTeam(team) {
        return team === 'A' ? 'B' : 'A';
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

    function hideBanner() {
        $('pcBanner').hidden = true;
    }

    function showLoggedOut() {
        if (loggedOut) return;
        loggedOut = true;
        const banner = $('pcBanner');
        banner.replaceChildren(el('span', '', '로그아웃됨 (세션 만료 또는 앱 재시작) — '));
        const link = el('a', '', '다시 로그인');
        link.href = '/auth/login';
        banner.append(link);
        banner.hidden = false;
        setBusy(false);
    }

    // ---------- 시작 ----------

    root.addEventListener('click', (event) => {
        const button = event.target.closest('[data-act]');
        if (!button || button.disabled) return;
        if (button.dataset.confirm && !window.confirm(button.dataset.confirm)) return;
        const action = button.dataset.act;
        if (action === 'pick') {
            act('pick', { category: selected.category, subCategory: selected.sub });
            return;
        }
        const params = {};
        if (button.dataset.team) params.team = button.dataset.team;
        if (button.dataset.delta) params.delta = button.dataset.delta;
        if (button.dataset.keepUsed) params.keepUsed = button.dataset.keepUsed;
        act(action, params);
    });

    $('pcPreviewPlay').addEventListener('click', previewPlay);
    $('pcPreviewStop').addEventListener('click', previewStop);
    $('pcImg').addEventListener('error', () => {
        $('pcImageWarn').hidden = false;
    });

    const modeSelect = $('pcPlayerMode');
    modeSelect.value = playerMode;
    modeSelect.addEventListener('change', () => {
        playerMode = modeSelect.value;
        try {
            localStorage.setItem(PLAYER_MODE_KEY, playerMode);
        } catch (e) {
            // 저장 못 해도 이번 화면에서는 적용된다
        }
        if (playerMode !== 'here' && ytReady) YouTubePlayerManager.pause();
    });

    refreshState().then(loadRemaining);
    setInterval(() => {
        if (!busy && !loggedOut) refreshState();
    }, POLL_MS);
})();
