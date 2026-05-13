let currentRound = 1;
let currentSong = null;
let isPlaying = false;
let audioPlayer = document.getElementById('audioPlayer');
let progressInterval = null;
let playerScores = {};
let actualTotalRounds = totalRounds; // 서버에서 업데이트될 수 있음
let isRoundEnded = false; // 라운드 종료 플래그
let isRoundReady = false; // 준비 완료 플래그
let youtubePlayerReady = false; // YouTube Player 준비 상태
let videoReady = false;      // YouTube CUED 상태
let pendingAutoPlay = false; // 자동 재생 대기 플래그

// 초기화
players.forEach(player => {
    playerScores[player] = 0;
});

// 게임 시작
document.addEventListener('DOMContentLoaded', async function() {
    // YouTube Player 초기화
    try {
        await YouTubePlayerManager.init('youtubePlayerContainer', {
            onStateChange: function(e) {
                Debug.log('YouTube 상태 변경:', e.data);

                if (e.data === 5) { // CUED - 영상 로드 완료
                    videoReady = true;
                    Debug.log('영상 로드 완료 (CUED)');

                    if (pendingAutoPlay && currentSong) {
                        Debug.log('자동 재생 시작');
                        pendingAutoPlay = false;
                        playAudio();
                    }
                } else if (e.data === 0) { // ENDED
                    pauseAudio();
                } else if (e.data === 1) { // PLAYING
                    videoReady = true;
                    // loadAndPlay 성공 시 UI 업데이트 (CUED 건너뛰는 경우 대응)
                    if (pendingAutoPlay) {
                        Debug.log('자동 재생 시작 (PLAYING 상태)');
                        pendingAutoPlay = false;
                        isPlaying = true;
                        document.getElementById('playBtn').innerHTML = '<span class="pause-icon">❚❚</span>';
                        document.getElementById('musicIcon').textContent = '🎶';
                        document.getElementById('musicIcon').classList.add('playing');
                        document.getElementById('playerStatus').textContent = '재생 중...';
                        if (!progressInterval) {
                            progressInterval = setInterval(updateProgress, 100);
                        }
                    }
                }
            },
            onError: function(e, errorInfo) {
                // console.error('YouTube 재생 오류:', e.data);
                videoReady = false;
                pendingAutoPlay = false;
                if (currentSong && currentSong.filePath) {
                    currentSong.youtubeVideoId = null;
                    loadAudioSource();
                } else {
                    // MP3 없으면 재생 불가 처리
                    handlePlaybackError(errorInfo);
                }
            }
        });
        youtubePlayerReady = true;
    } catch (error) {
        console.warn('YouTube Player 초기화 실패:', error);
    }

    // 매 라운드 선택 모드 처리
    if (gameMode === 'GENRE_PER_ROUND') {
        showGenreSelectModal(1);
    } else if (gameMode === 'ARTIST_PER_ROUND') {
        showArtistSelectModal(1);
    } else if (gameMode === 'YEAR_PER_ROUND') {
        showYearSelectModal(1);
    } else {
        loadRound(1);
    }

    // 아티스트 검색 입력 이벤트
    const artistSearchInput = document.getElementById('artistSearchInput');
    if (artistSearchInput) {
        artistSearchInput.addEventListener('input', function() {
            renderArtistList(this.value);
        });
    }

    // 대체된 곡 알림 표시
    showReplacedSongsNotice();
});

// 라운드 축소 알림 표시
function showReplacedSongsNotice() {
    const roundsReducedJson = sessionStorage.getItem('roundsReduced');
    if (roundsReducedJson) {
        sessionStorage.removeItem('roundsReduced'); // 한 번 표시 후 삭제

        const info = JSON.parse(roundsReducedJson);
        const notice = document.createElement('div');
        notice.className = 'rounds-reduced-notice';

        notice.innerHTML = `
            <div class="notice-content">
                <span class="notice-icon">&#x26A0;</span>
                <div class="notice-text">
                    <div class="main-message">재생 불가 곡으로 인해 ${info.requested}라운드 → ${info.actual}라운드로 축소되었습니다</div>
                </div>
            </div>
        `;
        document.body.appendChild(notice);

        // 3.5초 후 페이드아웃
        setTimeout(() => {
            notice.classList.add('fade-out');
            setTimeout(() => notice.remove(), 500);
        }, 3500);
    }
}

async function showGenreSelectModal(roundNumber) {
    const modal = document.getElementById('genreSelectModal');
    const genreList = document.getElementById('genreList');

    // 장르별 남은 노래 수 업데이트
    try {
        const response = await fetch('/game/solo/host/genres-with-count');
        let genres = await response.json();

        // 남은 곡 개수 순으로 정렬 (내림차순)
        genres.sort((a, b) => b.availableCount - a.availableCount);

        genreList.innerHTML = '';

        genres.forEach(genre => {
            const item = document.createElement('div');
            item.className = 'genre-item';

            if (genre.availableCount === 0) {
                item.classList.add('disabled');
                // hideEmptyGenres 설정에 따라 숨김 처리
                if (hideEmptyGenres) {
                    item.classList.add('hidden');
                }
            }

            item.dataset.genreId = genre.id;
            item.dataset.genreName = genre.name;
            item.innerHTML = `
                <span class="genre-name">${genre.name}</span>
                <span class="genre-count">${genre.availableCount}곡</span>
            `;

            if (genre.availableCount > 0) {
                item.addEventListener('click', () => selectGenre(genre.id, roundNumber));
            }

            genreList.appendChild(item);
        });

    } catch (error) {
        // console.error('장르 목록 로딩 오류:', error);
    }

    modal.classList.add('show');
}

async function selectGenre(genreId, roundNumber) {
    try {
        const response = await fetch('/game/solo/host/select-genre', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                genreId: genreId,
                roundNumber: roundNumber
            })
        });

        const result = await response.json();

        if (result.success) {
            document.getElementById('genreSelectModal').classList.remove('show');

            // ★ select-genre에서 반환된 데이터로 바로 라운드 설정 (loadRound 호출 X)
            currentRound = roundNumber;
            currentSong = result.song;

            // 서버의 totalRounds로 업데이트
            if (result.totalRounds) {
                actualTotalRounds = result.totalRounds;
                const totalRoundDisplay = document.querySelector('.round-info span:last-child');
                if (totalRoundDisplay) {
                    totalRoundDisplay.textContent = actualTotalRounds;
                }
            }

            document.getElementById('currentRound').textContent = roundNumber;

            // UI 리셋 (오디오 소스 로드 전에 먼저 실행)
            resetPlayerUI();

            // 노래 정보 표시
            displaySongInfo();

            // 오디오 소스 로드 (자동 재생 포함)
            loadAudioSource();

        } else {
            showToast(result.message || '장르 선택에 실패했습니다.');
        }
    } catch (error) {
        // console.error('장르 선택 오류:', error);
        showToast('장르 선택 중 오류가 발생했습니다.');
    }
}

// ========== 아티스트 선택 모달 ==========

let allArtistsForModal = [];
let currentArtistRound = 1;

async function showArtistSelectModal(roundNumber) {
    const modal = document.getElementById('artistSelectModal');
    currentArtistRound = roundNumber;

    try {
        const response = await fetch('/game/solo/host/artists-with-count');
        allArtistsForModal = await response.json();

        // 남은 곡 개수 순으로 정렬 (내림차순)
        allArtistsForModal.sort((a, b) => b.count - a.count);

        renderArtistList();

    } catch (error) {
        // console.error('아티스트 목록 로딩 오류:', error);
    }

    // 검색 입력 초기화
    document.getElementById('artistSearchInput').value = '';
    modal.classList.add('show');
}

function renderArtistList(filterKeyword = '') {
    const artistList = document.getElementById('artistList');
    let artistsToShow = allArtistsForModal;

    if (filterKeyword) {
        artistsToShow = allArtistsForModal.filter(a =>
            a.name.toLowerCase().includes(filterKeyword.toLowerCase())
        );
    }

    artistList.innerHTML = '';

    artistsToShow.forEach(artist => {
        const item = document.createElement('div');
        item.className = 'genre-item';

        if (artist.count === 0) {
            item.classList.add('disabled');
            if (hideEmptyGenres) {
                item.classList.add('hidden');
            }
        }

        item.innerHTML = `
            <span class="genre-name">${artist.name}</span>
            <span class="genre-count">${artist.count}곡</span>
        `;

        if (artist.count > 0) {
            item.addEventListener('click', () => selectArtist(artist.name, currentArtistRound));
        }

        artistList.appendChild(item);
    });
}

async function selectArtist(artistName, roundNumber) {
    try {
        const response = await fetch('/game/solo/host/select-artist', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                artist: artistName,
                roundNumber: roundNumber
            })
        });

        const result = await response.json();

        if (result.success) {
            document.getElementById('artistSelectModal').classList.remove('show');

            // select-artist에서 반환된 데이터로 바로 라운드 설정
            currentRound = roundNumber;
            currentSong = result.song;

            // 서버의 totalRounds로 업데이트
            if (result.totalRounds) {
                actualTotalRounds = result.totalRounds;
                const totalRoundDisplay = document.querySelector('.round-info span:last-child');
                if (totalRoundDisplay) {
                    totalRoundDisplay.textContent = actualTotalRounds;
                }
            }

            document.getElementById('currentRound').textContent = roundNumber;

            // UI 리셋 (오디오 소스 로드 전에 먼저 실행)
            resetPlayerUI();

            // 노래 정보 표시
            displaySongInfo();

            // 오디오 소스 로드 (자동 재생 포함)
            loadAudioSource();

        } else {
            showToast(result.message || '아티스트 선택에 실패했습니다.');
        }
    } catch (error) {
        // console.error('아티스트 선택 오류:', error);
        showToast('아티스트 선택 중 오류가 발생했습니다.');
    }
}

// ========== 연도 선택 모달 ==========

async function showYearSelectModal(roundNumber) {
    const modal = document.getElementById('yearSelectModal');
    const yearList = document.getElementById('yearList');

    try {
        const response = await fetch('/game/solo/host/years-with-count');
        let years = await response.json();

        // 이미 최신순 정렬되어있음

        yearList.innerHTML = '';

        years.forEach(year => {
            const item = document.createElement('div');
            item.className = 'genre-item';

            if (year.count === 0) {
                item.classList.add('disabled');
                if (hideEmptyGenres) {
                    item.classList.add('hidden');
                }
            }

            item.innerHTML = `
                <span class="genre-name">${year.year}년</span>
                <span class="genre-count">${year.count}곡</span>
            `;

            if (year.count > 0) {
                item.addEventListener('click', () => selectYear(year.year, roundNumber));
            }

            yearList.appendChild(item);
        });

    } catch (error) {
        // console.error('연도 목록 로딩 오류:', error);
    }

    modal.classList.add('show');
}

async function selectYear(year, roundNumber) {
    try {
        const response = await fetch('/game/solo/host/select-year', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                year: year,
                roundNumber: roundNumber
            })
        });

        const result = await response.json();

        if (result.success) {
            document.getElementById('yearSelectModal').classList.remove('show');

            // select-year에서 반환된 데이터로 바로 라운드 설정
            currentRound = roundNumber;
            currentSong = result.song;

            // 서버의 totalRounds로 업데이트
            if (result.totalRounds) {
                actualTotalRounds = result.totalRounds;
                const totalRoundDisplay = document.querySelector('.round-info span:last-child');
                if (totalRoundDisplay) {
                    totalRoundDisplay.textContent = actualTotalRounds;
                }
            }

            document.getElementById('currentRound').textContent = roundNumber;

            // UI 리셋 (오디오 소스 로드 전에 먼저 실행)
            resetPlayerUI();

            // 노래 정보 표시
            displaySongInfo();

            // 오디오 소스 로드 (자동 재생 포함)
            loadAudioSource();

        } else {
            showToast(result.message || '연도 선택에 실패했습니다.');
        }
    } catch (error) {
        // console.error('연도 선택 오류:', error);
        showToast('연도 선택 중 오류가 발생했습니다.');
    }
}

async function loadRound(roundNumber) {
    try {
        const response = await fetch(`/game/solo/host/round/${roundNumber}`);
        const result = await response.json();

        if (!result.success) {
            showToast(result.message);
            return;
        }

        currentRound = roundNumber;
        currentSong = result.song;

        // 서버의 totalRounds로 업데이트 (노래 부족 시 변경될 수 있음)
        if (result.totalRounds) {
            actualTotalRounds = result.totalRounds;
            // 화면의 총 라운드 수도 업데이트
            const totalRoundDisplay = document.querySelector('.round-info span:last-child');
            if (totalRoundDisplay) {
                totalRoundDisplay.textContent = actualTotalRounds;
            }
        }

        document.getElementById('currentRound').textContent = roundNumber;

        // UI 리셋 (오디오 소스 로드 전에 먼저 실행)
        resetPlayerUI();

        // 노래 정보 표시
        displaySongInfo();

        // 오디오 소스 로드 (자동 재생 포함)
        loadAudioSource();

    } catch (error) {
        // console.error('라운드 로딩 오류:', error);
        showToast('라운드를 불러오는 중 오류가 발생했습니다.');
    }
}

/**
 * Display song information on screen
 * Shows artist, title, and release year during the round
 */
function displaySongInfo() {
    if (!currentSong) {
        hideSongInfo();
        return;
    }

    const songInfoDisplay = document.getElementById('songInfoDisplay');
    const titleElement = document.getElementById('songInfoTitle');
    const artistElement = document.getElementById('songInfoArtist');
    const metaElement = document.getElementById('songInfoMeta');

    // 제목 & 아티스트 업데이트
    if (titleElement) {
        titleElement.textContent = currentSong.title || '제목 없음';
    }
    if (artistElement) {
        artistElement.textContent = currentSong.artist || '아티스트 정보 없음';
    }

    // 메타 정보 업데이트 (발매년도 + 장르)
    if (metaElement) {
        let metaParts = [];
        if (currentSong.releaseYear) {
            metaParts.push(currentSong.releaseYear + '년');
        }
        if (currentSong.genre) {
            metaParts.push(currentSong.genre);
        }
        metaElement.textContent = metaParts.length > 0 ? metaParts.join(' · ') : '정보 없음';
    }

    // 표시
    if (songInfoDisplay) {
        songInfoDisplay.style.display = 'block';
    }
}

/**
 * Hide song information display
 */
function hideSongInfo() {
    const songInfoDisplay = document.getElementById('songInfoDisplay');
    if (songInfoDisplay) {
        songInfoDisplay.style.display = 'none';
    }
}

// 오디오 소스 로드 (YouTube 또는 MP3)
function loadAudioSource() {
    if (!currentSong) return;

    const shouldAutoPlay = currentRound > 1;
    videoReady = false;
    pendingAutoPlay = false;

    if (currentSong.youtubeVideoId && youtubePlayerReady) {
        if (shouldAutoPlay) {
            // 자동 재생: pendingAutoPlay 설정 후 loadAndPlay
            // CUED 또는 PLAYING 상태에서 UI 업데이트 (네트워크 지연 대응)
            pendingAutoPlay = true;
            Debug.log('자동 재생 대기 (라운드:', currentRound, ')');
            YouTubePlayerManager.loadAndPlay(currentSong.youtubeVideoId, currentSong.startTime || 0);
        } else {
            // 수동 재생: loadVideo 사용 (cueVideoById)
            YouTubePlayerManager.loadVideo(currentSong.youtubeVideoId, currentSong.startTime || 0);
        }
        updateTimeDisplay();
    } else if (currentSong.filePath) {
        audioPlayer.src = `/uploads/songs/${currentSong.filePath}`;
        audioPlayer.currentTime = 0;
        audioPlayer.onloadedmetadata = function() {
            updateTimeDisplay();
            if (shouldAutoPlay) {
                playAudio();
            }
        };
    }
}

function togglePlay() {
    if (!currentSong || (!currentSong.youtubeVideoId && !currentSong.filePath)) {
        showToast('재생할 노래가 없습니다.');
        return;
    }

    if (isPlaying) {
        pauseAudio();
    } else {
        playAudio();
    }
}

function playAudio() {
    if (currentSong.youtubeVideoId && youtubePlayerReady) {
        YouTubePlayerManager.play();
    } else {
        audioPlayer.play();
    }
    isPlaying = true;

    document.getElementById('playBtn').innerHTML = '<span class="pause-icon">❚❚</span>';
    document.getElementById('musicIcon').textContent = '🎶';
    document.getElementById('musicIcon').classList.add('playing');
    document.getElementById('playerStatus').textContent = '재생 중...';

    // 프로그레스 바 업데이트
    progressInterval = setInterval(updateProgress, 100);
}

function pauseAudio() {
    if (currentSong && currentSong.youtubeVideoId && youtubePlayerReady) {
        YouTubePlayerManager.pause();
    } else {
        audioPlayer.pause();
    }
    isPlaying = false;

    document.getElementById('playBtn').innerHTML = '<span class="play-icon">▶</span>';
    document.getElementById('musicIcon').textContent = '🎵';
    document.getElementById('musicIcon').classList.remove('playing');
    document.getElementById('playerStatus').textContent = '일시정지';

    clearInterval(progressInterval);
}

function stopAudio() {
    if (currentSong && currentSong.youtubeVideoId && youtubePlayerReady) {
        YouTubePlayerManager.stop();
    } else {
        audioPlayer.pause();
        audioPlayer.currentTime = 0;
    }
    isPlaying = false;

    document.getElementById('playBtn').innerHTML = '<span class="play-icon">▶</span>';
    document.getElementById('musicIcon').textContent = '🎵';
    document.getElementById('musicIcon').classList.remove('playing');
    document.getElementById('playerStatus').textContent = '재생 대기중';
    document.getElementById('progressBar').style.width = '0%';

    clearInterval(progressInterval);
}

function updateProgress() {
    if (!currentSong) return;

    const duration = currentSong.playDuration || 10;
    let currentTime;

    if (currentSong.youtubeVideoId && youtubePlayerReady) {
        const startTime = currentSong.startTime || 0;
        currentTime = YouTubePlayerManager.getCurrentTime() - startTime;
    } else {
        currentTime = audioPlayer.currentTime;
    }

    currentTime = Math.max(0, currentTime);
    const progress = Math.min((currentTime / duration) * 100, 100);

    document.getElementById('progressBar').style.width = progress + '%';
    updateTimeDisplay();

    // 재생 시간 초과 시 자동 정지 및 버튼 비활성화
    if (currentTime >= duration) {
        pauseAudio();
        disablePlayButton();
    }
}

// 재생 시간 종료 후 버튼 비활성화
function disablePlayButton() {
    const playBtn = document.getElementById('playBtn');
    if (playBtn) {
        playBtn.disabled = true;
        playBtn.innerHTML = '<span class="play-icon">▶</span>';
    }
    document.getElementById('playerStatus').textContent = '재생 완료';
}

function updateTimeDisplay() {
    const duration = currentSong ? (currentSong.playDuration || 10) : 0;
    let currentTime;

    if (currentSong && currentSong.youtubeVideoId && youtubePlayerReady) {
        const startTime = currentSong.startTime || 0;
        currentTime = Math.max(0, YouTubePlayerManager.getCurrentTime() - startTime);
    } else {
        currentTime = Math.max(0, audioPlayer.currentTime);
    }

    document.getElementById('currentTime').textContent = formatTime(Math.min(currentTime, duration));
    document.getElementById('totalTime').textContent = formatTime(duration);
}

function formatTime(seconds) {
    if (isNaN(seconds) || seconds === null || seconds === undefined) {
        return '0:00';
    }
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
}

function resetPlayerUI() {
    stopAudio();
    isRoundEnded = false; // 라운드 종료 플래그 리셋
    isRoundReady = false; // 준비 완료 플래그 리셋
    videoReady = false;      // YouTube 영상 상태 리셋
    pendingAutoPlay = false; // 자동 재생 대기 리셋
    // 재생 버튼 다시 활성화
    const playBtn = document.getElementById('playBtn');
    if (playBtn) {
        playBtn.disabled = false;
    }
    document.querySelectorAll('.player-btn').forEach(btn => {
        btn.classList.remove('selected');
        btn.disabled = false; // 버튼 활성화
    });
}

// 준비 완료 프롬프트 표시
function showReadyPrompt() {
    isRoundReady = false;
    const playBtn = document.getElementById('playBtn');
    const readyPrompt = document.getElementById('readyPrompt');

    if (playBtn) {
        playBtn.disabled = true;
    }
    if (readyPrompt) {
        readyPrompt.style.display = 'block';
    }
}

// 준비 완료 처리
function confirmReady() {
    isRoundReady = true;
    const playBtn = document.getElementById('playBtn');
    const readyPrompt = document.getElementById('readyPrompt');

    if (playBtn) {
        playBtn.disabled = false;
    }
    if (readyPrompt) {
        readyPrompt.style.display = 'none';
    }
}

async function selectWinner(playerName) {
    if (!currentSong) return;
    if (isRoundEnded) return; // 이미 라운드 종료된 경우 무시

    isRoundEnded = true; // 라운드 종료 플래그 설정

    // 모든 버튼 비활성화 및 선택된 버튼 하이라이트
    document.querySelectorAll('.player-btn').forEach(btn => {
        btn.disabled = true;
        if (btn.textContent === playerName) {
            btn.classList.add('selected');
        }
    });

    stopAudio();

    // 서버에 결과 전송
    try {
        const response = await fetch('/game/solo/host/answer', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                roundNumber: currentRound,
                winner: playerName,
                isSkip: false
            })
        });

        const result = await response.json();

        if (result.success) {
            // 점수 업데이트
            playerScores[playerName] = (playerScores[playerName] || 0) + 100;
            updateScoreboard();

            // 정답 모달 표시
            showAnswerModal(playerName, result.isGameOver);
        } else {
            // 실패 시 플래그 및 버튼 복원
            isRoundEnded = false;
            document.querySelectorAll('.player-btn').forEach(btn => {
                btn.disabled = false;
                btn.classList.remove('selected');
            });
            showToast(result.message);
        }
    } catch (error) {
        // 오류 시 플래그 및 버튼 복원
        isRoundEnded = false;
        document.querySelectorAll('.player-btn').forEach(btn => {
            btn.disabled = false;
            btn.classList.remove('selected');
        });
        // console.error('답변 제출 오류:', error);
    }
}

async function skipRound() {
    if (!currentSong) return;
    if (isRoundEnded) return; // 이미 라운드 종료된 경우 무시

    isRoundEnded = true; // 라운드 종료 플래그 설정

    // 모든 버튼 비활성화
    document.querySelectorAll('.player-btn').forEach(btn => {
        btn.disabled = true;
    });

    stopAudio();

    try {
        const response = await fetch('/game/solo/host/answer', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                roundNumber: currentRound,
                winner: null,
                isSkip: true
            })
        });

        const result = await response.json();

        if (result.success) {
            showAnswerModal(null, result.isGameOver);
        } else {
            // 실패 시 플래그 및 버튼 복원
            isRoundEnded = false;
            document.querySelectorAll('.player-btn').forEach(btn => {
                btn.disabled = false;
            });
            showToast(result.message);
        }
    } catch (error) {
        // 오류 시 플래그 및 버튼 복원
        isRoundEnded = false;
        document.querySelectorAll('.player-btn').forEach(btn => {
            btn.disabled = false;
        });
        // console.error('스킵 오류:', error);
    }
}

function showAnswerModal(winner, isGameOver) {
    const modal = document.getElementById('answerModal');
    const header = document.getElementById('answerHeader');
    const winnerInfo = document.getElementById('winnerInfo');
    const nextBtn = document.getElementById('nextRoundBtn');

    if (winner) {
        header.textContent = '🎉 정답!';
        header.className = 'answer-header correct';
        winnerInfo.innerHTML = `<span class="winner-name">${escapeHtml(winner)}</span> 정답! +100점`;
    } else {
        header.textContent = '⏭ 스킵';
        header.className = 'answer-header skip';
        winnerInfo.innerHTML = '아쉽게도 스킵되었습니다.';
    }

    // 노래 정보 표시
    document.getElementById('answerTitle').textContent = currentSong.title;
    document.getElementById('answerArtist').textContent = currentSong.artist;

    let meta = [];
    if (currentSong.releaseYear) meta.push(currentSong.releaseYear + '년');
    if (currentSong.genre) meta.push(currentSong.genre);
    document.getElementById('answerMeta').textContent = meta.join(' · ');

    // 버튼 텍스트
    if (isGameOver) {
        nextBtn.textContent = '결과 보기 🏆';
        nextBtn.onclick = function() { window.location.href = '/game/solo/host/result'; };
    } else {
        nextBtn.textContent = '다음 라운드 →';
        nextBtn.onclick = nextRound;
    }

    modal.classList.add('show');
}

function nextRound() {
    document.getElementById('answerModal').classList.remove('show');

    if (currentRound < actualTotalRounds) {
        const nextRoundNumber = currentRound + 1;

        // 게임 모드에 따라 적절한 모달 표시
        if (gameMode === 'GENRE_PER_ROUND') {
            showGenreSelectModal(nextRoundNumber);
        } else if (gameMode === 'ARTIST_PER_ROUND') {
            showArtistSelectModal(nextRoundNumber);
        } else if (gameMode === 'YEAR_PER_ROUND') {
            showYearSelectModal(nextRoundNumber);
        } else {
            loadRound(nextRoundNumber);
        }
    } else {
        window.location.href = '/game/solo/host/result';
    }
}

function updateScoreboard() {
    const scoreList = document.getElementById('scoreList');

    // 점수순 정렬
    const sorted = Object.entries(playerScores).sort((a, b) => b[1] - a[1]);

    sorted.forEach(([player, score], index) => {
        const item = scoreList.querySelector(`[data-player="${player}"]`);
        if (item) {
            item.querySelector('.player-score').textContent = score;
            item.style.order = index;
        }
    });
}

async function quitGame() {
    if (!confirm('정말 게임을 종료하시겠습니까?')) return;

    try {
        await fetch('/game/solo/host/end', { method: 'POST' });
        window.location.href = '/';
    } catch (error) {
        window.location.href = '/';
    }
}

// 오디오 이벤트
audioPlayer.addEventListener('ended', function() {
    pauseAudio();
});

audioPlayer.addEventListener('error', function() {
    showToast('오디오 파일을 재생할 수 없습니다.');
    pauseAudio();
});

// ========== 재생 실패 처리 ==========

/**
 * YouTube 재생 실패 시 처리
 * @param {object} errorInfo - 에러 정보 (code, message, isPlaybackError)
 */
function handlePlaybackError(errorInfo) {
    if (!currentSong) return;

    Debug.log('재생 실패 처리:', errorInfo);

    // 재생 불가 에러인 경우에만 처리
    if (errorInfo && errorInfo.isPlaybackError) {
        // 1. 자동 신고 (서버에 재생 불가 보고)
        reportUnplayableSong(currentSong.id, errorInfo.code);

        // 2. 에러 모달 표시
        showPlaybackErrorModal(errorInfo);
    }
}

/**
 * 재생 불가 곡 자동 신고
 */
async function reportUnplayableSong(songId, errorCode) {
    try {
        await fetch('/api/song-report', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                songId: songId,
                reportType: 'UNPLAYABLE',
                description: '자동 신고: YouTube 에러 코드 ' + errorCode
            })
        });
        Debug.log('재생 불가 곡 자동 신고 완료');
    } catch (error) {
        // console.error('자동 신고 실패:', error);
    }
}

/**
 * 재생 실패 모달 표시
 */
function showPlaybackErrorModal(errorInfo) {
    // 기존 모달이 있으면 제거
    let modal = document.getElementById('playbackErrorModal');
    if (modal) {
        modal.remove();
    }

    // 모달 생성
    modal = document.createElement('div');
    modal.id = 'playbackErrorModal';
    modal.className = 'modal show';
    modal.innerHTML = `
        <div class="modal-content playback-error-modal">
            <div class="error-icon">⚠️</div>
            <h3>재생할 수 없는 곡입니다</h3>
            <p class="error-message">${errorInfo ? errorInfo.message : '알 수 없는 오류'}</p>
            <div class="auto-report-notice">
                <span class="auto-report-badge">✓ 자동 신고 완료</span>
                <p>관리자가 확인 후 조치합니다</p>
            </div>
            <div class="error-actions">
                <button class="btn-skip" onclick="skipUnplayableRound()">다음 곡으로 넘어가기</button>
            </div>
        </div>
    `;

    document.body.appendChild(modal);
}

/**
 * 재생 불가로 인한 라운드 스킵 (정답 모달 없이 바로 다음으로)
 */
async function skipUnplayableRound() {
    // 에러 모달 닫기
    const modal = document.getElementById('playbackErrorModal');
    if (modal) {
        modal.remove();
    }

    if (!currentSong) return;
    if (isRoundEnded) return;

    isRoundEnded = true;
    stopAudio();

    // 모든 버튼 비활성화
    document.querySelectorAll('.player-btn').forEach(btn => {
        btn.disabled = true;
    });

    try {
        const response = await fetch('/game/solo/host/answer', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                roundNumber: currentRound,
                winner: null,
                isSkip: true
            })
        });

        const result = await response.json();

        if (result.success) {
            // 정답 모달 없이 바로 다음 라운드로
            if (result.isGameOver) {
                window.location.href = '/game/solo/host/result';
            } else {
                const nextRoundNumber = currentRound + 1;
                if (gameMode === 'GENRE_PER_ROUND') {
                    showGenreSelectModal(nextRoundNumber);
                } else if (gameMode === 'ARTIST_PER_ROUND') {
                    showArtistSelectModal(nextRoundNumber);
                } else if (gameMode === 'YEAR_PER_ROUND') {
                    showYearSelectModal(nextRoundNumber);
                } else {
                    loadRound(nextRoundNumber);
                }
            }
        } else {
            isRoundEnded = false;
            document.querySelectorAll('.player-btn').forEach(btn => {
                btn.disabled = false;
            });
            showToast(result.message);
        }
    } catch (error) {
        isRoundEnded = false;
        document.querySelectorAll('.player-btn').forEach(btn => {
            btn.disabled = false;
        });
        // console.error('스킵 오류:', error);
    }
}

