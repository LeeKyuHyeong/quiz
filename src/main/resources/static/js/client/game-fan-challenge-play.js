// 아티스트 챌린지 게임 진행 JavaScript

// 난이도별 설정 (서버에서 전달받은 값으로 초기화)
let PLAY_TIME_MS = typeof initialPlayTimeMs !== 'undefined' ? initialPlayTimeMs : 5000;
let ANSWER_TIME_MS = typeof initialAnswerTimeMs !== 'undefined' ? initialAnswerTimeMs : 3000;
let TOTAL_TIME_MS = PLAY_TIME_MS + ANSWER_TIME_MS;
let INITIAL_LIVES = typeof initialLives !== 'undefined' ? initialLives : 3;
let SHOW_CHOSUNG_HINT = typeof showChosungHint !== 'undefined' ? showChosungHint : false;

let currentRound = 1;
let totalRounds = 0;
let remainingLives = INITIAL_LIVES;
let correctCount = 0;
let currentSong = null;
let timerInterval = null;
let startTime = null;
let isPlaying = false;
let currentPhase = 'playing'; // 'playing' | 'answering'
let youtubePlayer = null;
let youtubePlayerReady = false;

// YouTube API 준비 완료 콜백
function onYouTubeIframeAPIReady() {
    youtubePlayer = new YT.Player('youtubePlayer', {
        height: '1',
        width: '1',
        playerVars: {
            'autoplay': 0,
            'controls': 0,
            'disablekb': 1,
            'modestbranding': 1,
            'rel': 0
        },
        events: {
            'onReady': onPlayerReady,
            'onStateChange': onPlayerStateChange
        }
    });
}

function onPlayerReady(event) {
    youtubePlayerReady = true;
}

function onPlayerStateChange(event) {
    if (event.data === YT.PlayerState.PLAYING && !isPlaying) {
        isPlaying = true;
        startTimer();
    }
}

document.addEventListener('DOMContentLoaded', function() {
    // 엔터 키로 정답 제출
    document.getElementById('answerInput').addEventListener('keypress', function(e) {
        if (e.key === 'Enter') {
            submitAnswer();
        }
    });

    // 초기 라운드 정보 로드
    loadRoundInfo();
});

async function loadRoundInfo() {
    try {
        const response = await fetch(`/game/fan-challenge/round/${currentRound}`);
        const data = await response.json();

        if (data.success) {
            totalRounds = data.totalRounds;
            remainingLives = data.remainingLives;
            correctCount = data.correctCount;
            currentSong = data.song;

            // 서버에서 받은 난이도 설정 적용
            if (data.playTimeMs) PLAY_TIME_MS = data.playTimeMs;
            if (data.answerTimeMs) ANSWER_TIME_MS = data.answerTimeMs;
            TOTAL_TIME_MS = PLAY_TIME_MS + ANSWER_TIME_MS;
            if (data.initialLives) INITIAL_LIVES = data.initialLives;
            if (typeof data.showChosungHint !== 'undefined') SHOW_CHOSUNG_HINT = data.showChosungHint;

            // 초성 힌트 표시 (입문 모드)
            if (SHOW_CHOSUNG_HINT && data.song && data.song.chosungHint) {
                const hintEl = document.getElementById('chosungHint');
                const hintTextEl = document.getElementById('chosungText');
                if (hintEl && hintTextEl) {
                    hintTextEl.textContent = data.song.chosungHint;
                    hintEl.style.display = 'flex';
                }
            }

            document.getElementById('totalRounds').textContent = totalRounds;
            updateUI();
        } else {
            showToast(data.message || '라운드 정보를 불러올 수 없습니다');
            window.location.href = '/game/fan-challenge';
        }
    } catch (error) {
        // console.error('라운드 정보 로드 오류:', error);
        showToast('게임 정보를 불러올 수 없습니다');
        window.location.href = '/game/fan-challenge';
    }
}

function updateUI() {
    document.getElementById('currentRound').textContent = currentRound;
    document.getElementById('correctCount').textContent = correctCount;

    // 라이프 표시 동적 생성 (난이도별 라이프 수)
    const livesContainer = document.getElementById('livesContainer');
    if (livesContainer) {
        livesContainer.textContent = '';
        for (let i = 1; i <= INITIAL_LIVES; i++) {
            const life = document.createElement('span');
            life.className = 'life ' + (i <= remainingLives ? 'active' : 'lost');
            life.id = 'life' + i;
            life.textContent = '❤';
            livesContainer.appendChild(life);
        }
    }
}

function startRound() {
    document.getElementById('readyScreen').style.display = 'none';
    document.getElementById('gameScreen').style.display = 'flex';
    document.getElementById('answerInput').value = '';
    document.getElementById('answerInput').focus();

    // 상태 초기화
    isPlaying = false;
    currentPhase = 'playing';

    // 타이머 바 초기화 (동적 시간)
    document.getElementById('timerBar').style.width = '100%';
    document.getElementById('timerBar').classList.remove('warning', 'critical', 'answering');
    document.getElementById('timerValue').textContent = (TOTAL_TIME_MS / 1000).toFixed(1);
    updatePhaseDisplay();

    // 음악 재생
    playSong();
}

function playSong() {
    if (!currentSong) return;

    if (currentSong.youtubeVideoId) {
        playYouTube(currentSong.youtubeVideoId, currentSong.startTime || 0);
    } else if (currentSong.filePath) {
        playMP3(currentSong.filePath, currentSong.startTime || 0);
    }
}

function playYouTube(videoId, startTime) {
    if (youtubePlayer && youtubePlayerReady) {
        youtubePlayer.loadVideoById({
            videoId: videoId,
            startSeconds: startTime
        });
        // onPlayerStateChange에서 타이머 시작
    } else {
        // YouTube 플레이어가 준비되지 않은 경우 직접 시작
        setTimeout(() => {
            isPlaying = true;
            startTimer();
        }, 500);
    }
}

function playMP3(filePath, startTime) {
    const audio = document.getElementById('audioPlayer');
    audio.src = '/uploads/songs/' + filePath;
    audio.currentTime = startTime;
    audio.play().then(() => {
        isPlaying = true;
        startTimer();
    }).catch(error => {
        // console.error('MP3 재생 오류:', error);
        isPlaying = true;
        startTimer();
    });
}

function startTimer() {
    startTime = Date.now();

    timerInterval = setInterval(() => {
        const elapsed = Date.now() - startTime;
        const remaining = Math.max(0, TOTAL_TIME_MS - elapsed);
        const seconds = (remaining / 1000).toFixed(1);

        document.getElementById('timerValue').textContent = seconds;
        document.getElementById('timerBar').style.width = (remaining / TOTAL_TIME_MS * 100) + '%';

        const timerBar = document.getElementById('timerBar');

        // Phase 전환: 5초 경과 시 음악 정지 및 answering 단계로 전환
        if (elapsed >= PLAY_TIME_MS && currentPhase === 'playing') {
            currentPhase = 'answering';
            stopMusicOnly();
            updatePhaseDisplay();
        }

        // 타이머 색상 변경
        if (currentPhase === 'answering') {
            timerBar.classList.remove('warning');
            timerBar.classList.add('answering');
            if (remaining <= 1000) {
                timerBar.classList.add('critical');
            } else {
                timerBar.classList.remove('critical');
            }
        } else {
            // playing 단계
            timerBar.classList.remove('answering');
            if (remaining <= ANSWER_TIME_MS + 1000) {
                timerBar.classList.add('warning');
            } else {
                timerBar.classList.remove('warning');
            }
        }

        if (remaining <= 0) {
            if (isPlaying) {
                handleTimeout();
            }
            return;
        }
    }, 100);
}

function stopMusicOnly() {
    // YouTube 중지
    if (youtubePlayer && youtubePlayerReady) {
        try {
            youtubePlayer.pauseVideo();
        } catch (e) {}
    }

    // MP3 중지
    const audio = document.getElementById('audioPlayer');
    if (audio) {
        audio.pause();
    }
}

function updatePhaseDisplay() {
    const phaseEl = document.getElementById('phaseText');
    if (phaseEl) {
        if (currentPhase === 'playing') {
            phaseEl.textContent = '🎵 듣는 중...';
            phaseEl.className = 'phase-text playing';
        } else {
            phaseEl.textContent = '✏️ 입력 시간!';
            phaseEl.className = 'phase-text answering';
        }
    }
}

function stopTimer() {
    if (timerInterval) {
        clearInterval(timerInterval);
        timerInterval = null;
    }
}

function stopMusic() {
    // YouTube 중지
    if (youtubePlayer && youtubePlayerReady) {
        try {
            youtubePlayer.stopVideo();
        } catch (e) {}
    }

    // MP3 중지
    const audio = document.getElementById('audioPlayer');
    audio.pause();
    audio.currentTime = 0;

    isPlaying = false;
}

async function submitAnswer() {
    if (!isPlaying) return;

    const answerInput = document.getElementById('answerInput');
    const answer = answerInput.value.trim();

    if (!answer) {
        answerInput.focus();
        return;
    }

    isPlaying = false; // 먼저 상태 변경 (중복 제출/타임아웃 방지)
    const answerTimeMs = Date.now() - startTime;

    stopTimer();
    stopMusic();

    try {
        const response = await fetch('/game/fan-challenge/answer', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                roundNumber: currentRound,
                answer: answer,
                answerTimeMs: answerTimeMs
            })
        });

        const result = await response.json();

        if (result.success) {
            showAnswerResult(result);
        } else {
            showToast(result.message || '오류가 발생했습니다');
        }
    } catch (error) {
        // console.error('정답 제출 오류:', error);
        showToast('정답 제출 중 오류가 발생했습니다');
    }
}

async function handleTimeout() {
    if (!isPlaying) return; // 중복 호출 방지

    isPlaying = false; // 먼저 상태 변경
    stopTimer();
    stopMusic();

    try {
        const response = await fetch('/game/fan-challenge/timeout', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                roundNumber: currentRound
            })
        });

        const result = await response.json();

        if (result.success) {
            showAnswerResult(result);
        }
    } catch (error) {
        // console.error('시간 초과 처리 오류:', error);
    }
}

function showAnswerResult(result) {
    remainingLives = result.remainingLives;
    correctCount = result.correctCount;
    updateUI();

    const resultEl = document.getElementById('answerResult');
    const correctAnswerEl = document.getElementById('correctAnswer');

    if (result.isTimeout) {
        resultEl.innerHTML = '<span class="timeout">시간 초과!</span>';
        resultEl.className = 'answer-result timeout';
    } else if (result.isCorrect) {
        resultEl.innerHTML = '<span class="correct">정답!</span>';
        resultEl.className = 'answer-result correct';
    } else {
        resultEl.innerHTML = '<span class="wrong">오답!</span>';
        resultEl.className = 'answer-result wrong';
    }

    correctAnswerEl.textContent = result.correctAnswer;

    // 라이프 표시 (난이도별 동적)
    const modalLives = document.getElementById('modalLives');
    modalLives.textContent = '';
    for (let i = 0; i < INITIAL_LIVES; i++) {
        const life = document.createElement('span');
        life.className = 'life ' + (i < result.remainingLives ? 'active' : 'lost');
        life.textContent = '❤';
        modalLives.appendChild(life);
    }
    document.getElementById('modalCorrect').textContent = result.correctCount;

    // 게임 오버 체크
    if (result.isGameOver) {
        document.getElementById('nextBtn').style.display = 'none';

        // 결과 데이터를 sessionStorage에 백업 저장
        if (result.resultData) {
            const backupData = {
                ...result.resultData,
                correctCount: result.correctCount,
                totalRounds: result.totalRounds,
                gameOverReason: result.gameOverReason
            };
            sessionStorage.setItem('fanChallengeResult', JSON.stringify(backupData));
        }

        setTimeout(() => {
            document.getElementById('answerModal').style.display = 'none';
            showGameOver(result);
        }, 1500);
    } else {
        document.getElementById('nextBtn').style.display = 'block';
        document.getElementById('nextBtn').textContent =
            `다음 곡 (${result.completedRounds}/${result.totalRounds})`;
    }

    document.getElementById('answerModal').style.display = 'flex';
}

function showGameOver(result) {
    const titleEl = document.getElementById('gameOverTitle');
    const messageEl = document.getElementById('gameOverMessage');

    if (result.gameOverReason === 'PERFECT_CLEAR') {
        titleEl.innerHTML = '&#127942; PERFECT CLEAR! &#127942;';
        titleEl.className = 'gameover-title perfect';
        messageEl.textContent = `${artist}의 모든 곡을 맞췄습니다!`;
    } else if (result.gameOverReason === 'ALL_ROUNDS_COMPLETED') {
        // 모든 라운드 완료했지만 퍼펙트는 아님
        titleEl.innerHTML = '&#127881; CHALLENGE COMPLETE!';
        titleEl.className = 'gameover-title completed';
        messageEl.textContent = `${result.correctCount}/${result.totalRounds}곡 정답 (라이프 ${result.remainingLives}개 남음)`;
    } else {
        // LIFE_EXHAUSTED
        titleEl.innerHTML = '&#128148; GAME OVER';
        titleEl.className = 'gameover-title failed';
        messageEl.textContent = `${result.correctCount}/${result.totalRounds}곡 정답`;
    }

    document.getElementById('gameOverModal').style.display = 'flex';
}

async function nextRound() {
    document.getElementById('answerModal').style.display = 'none';
    document.getElementById('gameScreen').style.display = 'none';
    document.getElementById('readyScreen').style.display = 'flex';

    currentRound++;
    await loadRoundInfo();
}

function goToResult() {
    window.location.href = '/game/fan-challenge/result';
}

async function quitGame() {
    if (!confirm('정말 포기하시겠습니까?')) return;

    stopTimer();
    stopMusic();

    try {
        await fetch('/game/fan-challenge/end', { method: 'POST' });
    } catch (e) {}

    window.location.href = '/game/fan-challenge';
}
