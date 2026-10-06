/**
 * 파티 보드(TV) — /admin/party/board
 *
 * 1초마다 GET /admin/party/state(보드 상태)만 읽는다. 콘솔 상태는 읽지 않으므로 정답은 공개(REVEAL) 뒤에만 보인다.
 * 버전이 같으면 다시 그리지 않는다(본게임은 타이머를 그리지 않는다 — 10-06 규칙).
 * 소리는 내지 않는다 — 노래 뮤직비디오가 정답을 보여 주므로 플레이어도 두지 않는다(재생은 콘솔·플레이어 창).
 * 참가자가 보는 화면이라 연결 끊김·로그아웃은 구석에 작게만 표시한다. 문제 내용은 전부 textContent.
 */
(function () {
    'use strict';

    const POLL_MS = 1000;

    const CATEGORY_LABEL = {
        SONG: '노래', SCREEN: '드라마·영화', ANIME: '애니', GAME: '게임',
        PERSON: '인물', QUIZ: '초성·이모지', SOUND: '시그널·CM'
    };
    const GAME_TITLE_SUB = '게임 맞히기';

    let version = null;
    let loggedOut = false;

    const $ = (id) => document.getElementById(id);
    const root = $('partyBoard');

    async function poll() {
        if (loggedOut) return;
        let response;
        try {
            response = await fetch('/admin/party/state', { headers: { Accept: 'application/json' } });
        } catch (e) {
            showConn('연결 끊김 · 다시 연결 중');
            return;
        }
        const type = response.headers.get('content-type') || '';
        if (response.redirected || !type.includes('application/json')) {
            loggedOut = true;
            showConn('로그아웃됨 · 노트북에서 다시 로그인');
            return;
        }
        const state = await response.json();
        if (state.success === false) return;
        hideConn();
        if (state.version === version) return;
        version = state.version;
        render(state);
    }

    function render(state) {
        const phase = state.phase;
        root.dataset.phase = phase;
        const names = state.teamNames;
        const onBoard = phase === 'SHOW' || phase === 'REVEAL';

        ['A', 'B'].forEach((team) => {
            $('pbTeamName' + team).textContent = names[team];
            $('pbScore' + team).textContent = state.scores[team];
            $('pbBigName' + team).textContent = names[team];
            $('pbBigScore' + team).textContent = state.scores[team];
            $('pbTeam' + team).classList.toggle('dim',
                phase === 'SHOW' && !state.freeChallenge && state.wrongTeam === team);
        });

        $('pbCat').textContent = headerOf(state);
        $('pbWait').hidden = phase !== 'WAIT';
        $('pbWaitSub').textContent = state.round > 0
            ? '라운드 ' + state.round + ' 끝 · 다음 문제를 기다리는 중'
            : '다음 문제를 기다리는 중';
        $('pbQuestion').hidden = phase !== 'SHOW';
        $('pbReveal').hidden = phase !== 'REVEAL';
        $('pbScoreboard').hidden = phase !== 'SCORES' && phase !== 'END';

        if (phase === 'SHOW' && state.item) renderQuestion(state);
        if (phase === 'REVEAL' && state.reveal) renderReveal(state);
        if (phase === 'SCORES' || phase === 'END') renderScoreboard(state);
        if (!onBoard) $('pbImg').removeAttribute('src');

        $('pbFooter').textContent = footerOf(state);
    }

    function renderQuestion(state) {
        const item = state.item;
        const img = $('pbImg');
        img.hidden = item.presentation !== 'IMAGE';
        if (item.presentation === 'IMAGE' && img.getAttribute('src') !== item.imageUrl) {
            img.src = item.imageUrl;
        }
        $('pbText').hidden = item.presentation !== 'TEXT';
        $('pbText').textContent = item.presentation === 'TEXT' ? (item.questionText || '') : '';
        $('pbListen').hidden = item.presentation !== 'AUDIO';
        $('pbListenSub').textContent = item.presentation === 'AUDIO' ? whatToSay(item) : '';

        const turn = $('pbTurn');
        turn.className = 'pb-turn';
        if (state.freeChallenge) {
            turn.hidden = false;
            turn.classList.add('free');
            turn.textContent = '양 팀 오답 — 자유 도전!';
        } else if (state.wrongTeam) {
            const other = state.wrongTeam === 'A' ? 'B' : 'A';
            turn.hidden = false;
            turn.classList.add(other.toLowerCase());
            turn.textContent = state.teamNames[state.wrongTeam] + ' 오답 → ' + state.teamNames[other] + ' 기회';
        } else {
            turn.hidden = true;
        }

        const hints = $('pbHints');
        hints.replaceChildren();
        hints.hidden = state.hints.length === 0;
        state.hints.forEach((hint, index) => {
            const li = document.createElement('li');
            li.textContent = '힌트 ' + (index + 1) + ' · ' + hint;
            hints.append(li);
        });
    }

    function renderReveal(state) {
        const reveal = state.reveal;
        $('pbAnswer').textContent = reveal.answer;
        const parts = [];
        if (reveal.detail) parts.push(reveal.detail);
        if (reveal.source) parts.push('출처: ' + reveal.source);
        $('pbDetail').textContent = parts.join('  |  ');
        const who = $('pbWho');
        who.className = 'pb-who';
        if (reveal.scoringTeam) {
            who.classList.add(reveal.scoringTeam.toLowerCase());
            who.textContent = state.teamNames[reveal.scoringTeam] + ' +1';
        } else {
            who.textContent = '못 맞힘';
        }
    }

    function renderScoreboard(state) {
        const a = state.scores.A;
        const b = state.scores.B;
        $('pbBigA').classList.toggle('win', state.phase === 'END' && a > b);
        $('pbBigB').classList.toggle('win', state.phase === 'END' && b > a);
        $('pbFinalTitle').textContent = state.phase !== 'END' ? ''
            : a === b ? '최종 결과 · 무승부'
                : '최종 결과 · ' + state.teamNames[a > b ? 'A' : 'B'] + ' 승리!';
    }

    function headerOf(state) {
        switch (state.phase) {
            case 'SHOW':
            case 'REVEAL': {
                const item = state.item;
                if (!item) return '라운드 ' + state.round;
                return (CATEGORY_LABEL[item.category] || item.category)
                    + (item.subCategory ? ' · ' + item.subCategory : '');
            }
            case 'SCORES':
                return '점수판';
            case 'END':
                return '최종 결과';
            default:
                return state.round > 0 ? '라운드 ' + state.round : '파티 퀴즈';
        }
    }

    function footerOf(state) {
        switch (state.phase) {
            case 'SHOW':
                return state.freeChallenge
                    ? '자유 도전 — 먼저 맞히는 팀이 득점'
                    : '먼저 손 든 사람만 · 오답이면 상대 팀 기회';
            case 'REVEAL':
            case 'SCORES':
                return '다음 대분류는 MC 가';
            case 'END':
                return '수고하셨습니다!';
            default:
                return '🔊 소리는 노트북 → HDMI → TV';
        }
    }

    /** 무엇을 말해야 정답인지 — docs/party-content/README.md "MC 판정 규칙" 의 참가자용 요약. */
    function whatToSay(item) {
        switch (item.category) {
            case 'SONG':
                return '노래 제목을 말하면 정답';
            case 'ANIME':
                return '애니 제목을 말하면 정답';
            case 'SOUND':
                return '프로그램·브랜드 이름을 말하면 정답';
            case 'GAME':
                return item.subCategory === GAME_TITLE_SUB
                    ? '게임 이름을 말하면 정답'
                    : '캐릭터·유닛·맵 이름을 말하면 정답';
            default:
                return '';
        }
    }

    function showConn(message) {
        $('pbConn').textContent = message;
        $('pbConn').hidden = false;
    }

    function hideConn() {
        $('pbConn').hidden = true;
    }

    $('pbFullscreen').addEventListener('click', () => {
        if (document.documentElement.requestFullscreen) {
            document.documentElement.requestFullscreen().catch(() => {
                // 브라우저가 막으면 F11 로
            });
        }
    });
    document.addEventListener('fullscreenchange', () => {
        $('pbFullscreen').hidden = !!document.fullscreenElement;
    });
    $('pbImg').addEventListener('error', () => {
        $('pbImg').hidden = true;
    });

    poll();
    setInterval(poll, POLL_MS);
})();
