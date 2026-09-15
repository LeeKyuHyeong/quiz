// 선택된 연도/아티스트
let selectedYears = [];
let selectedArtists = [];
let allYears = [];
let allArtists = [];
let maxAvailableSongs = 999;

// ========== 초기화 ==========

document.addEventListener('DOMContentLoaded', function() {
    // 데이터 로드
    loadYears();
    loadArtists();

    // 초기 노래 개수 업데이트
    updateSongCount();

    // 게임 모드 변경 이벤트
    document.querySelectorAll('input[name="gameMode"]').forEach(radio => {
        radio.addEventListener('change', handleGameModeChange);
    });

    // 장르 선택 변경 이벤트
    document.getElementById('fixedGenreId').addEventListener('change', updateSongCount);

    // 아티스트 검색 이벤트
    document.getElementById('artistSearchInput').addEventListener('input', function() {
        renderArtistChips(this.value);
    });

    // 아티스트 유형 변경 이벤트
    document.querySelectorAll('input[name="artistType"]').forEach(radio => {
        radio.addEventListener('change', function() {
            updateSongCount();
        });
    });

    // Enter 키로 방 만들기
    document.getElementById('roomName').addEventListener('keydown', function(e) {
        if (e.key === 'Enter') {
            createRoom();
        }
    });
});

// ========== 게임 모드 처리 ==========

function handleGameModeChange() {
    const gameMode = document.querySelector('input[name="gameMode"]:checked').value;

    // 모든 선택 영역 숨기기
    document.getElementById('genreSelectArea').style.display = 'none';
    document.getElementById('artistSelectArea').style.display = 'none';
    document.getElementById('yearSelectArea').style.display = 'none';

    // 선택 초기화
    selectedYears = [];
    selectedArtists = [];

    // 모드별 UI 표시
    switch (gameMode) {
        case 'FIXED_GENRE':
            document.getElementById('genreSelectArea').style.display = 'block';
            break;
        case 'FIXED_ARTIST':
            document.getElementById('artistSelectArea').style.display = 'block';
            document.getElementById('artistTypeArea').style.display = 'none'; // 아티스트 유형 숨김
            renderArtistChips();
            break;
        case 'FIXED_YEAR':
            document.getElementById('yearSelectArea').style.display = 'block';
            renderYearChips();
            break;
    }

    // 아티스트 고정 모드가 아니면 아티스트 유형 영역 표시
    if (gameMode !== 'FIXED_ARTIST') {
        document.getElementById('artistTypeArea').style.display = 'block';
    }

    updateSongCount();
}

// ========== 데이터 로드 ==========

async function loadYears() {
    try {
        const response = await fetch('/game/multi/years');
        allYears = await response.json();
    } catch (error) {
        // console.error('연도 목록 로드 오류:', error);
    }
}

async function loadArtists() {
    try {
        const response = await fetch('/game/multi/artists');
        allArtists = await response.json();
    } catch (error) {
        // console.error('아티스트 목록 로드 오류:', error);
    }
}

// ========== 연도 칩 ==========

function renderYearChips() {
    const container = document.getElementById('yearSelectChips');
    if (allYears.length === 0) {
        container.innerHTML = '<span class="chips-loading">등록된 연도가 없습니다</span>';
        return;
    }

    container.innerHTML = allYears.map(y => {
        const isSelected = selectedYears.includes(y.year);
        return `<div class="chip ${isSelected ? 'selected' : ''}" onclick="toggleYear(${y.year})">
            ${y.year}<span class="chip-count">(${y.count})</span>
        </div>`;
    }).join('');

    updateYearCountInfo();
}

function toggleYear(year) {
    const idx = selectedYears.indexOf(year);
    if (idx > -1) {
        selectedYears.splice(idx, 1);
    } else {
        selectedYears.push(year);
    }
    renderYearChips();
    updateSongCount();
}

function updateYearCountInfo() {
    const el = document.getElementById('yearSelectCountInfo');
    if (selectedYears.length > 0) {
        el.textContent = `(${selectedYears.length}개 선택)`;
    } else {
        el.textContent = '';
    }
}

// ========== 아티스트 칩 ==========

function renderArtistChips(filterKeyword = '') {
    const container = document.getElementById('artistSelectChips');
    let artistsToShow = allArtists;

    if (filterKeyword) {
        artistsToShow = allArtists.filter(a =>
            a.name.toLowerCase().includes(filterKeyword.toLowerCase())
        );
    }

    if (artistsToShow.length === 0) {
        container.innerHTML = '<span class="chips-loading">검색 결과가 없습니다</span>';
        return;
    }

    container.innerHTML = artistsToShow.map(a => {
        const isSelected = selectedArtists.includes(a.name);
        const escapedName = a.name.replace(/'/g, "\\'").replace(/"/g, '\\"');
        return `<div class="chip ${isSelected ? 'selected' : ''}" onclick="toggleArtist('${escapedName}')">
            ${a.name}<span class="chip-count">(${a.count})</span>
        </div>`;
    }).join('');

    updateArtistCountInfo();
}

function toggleArtist(name) {
    const idx = selectedArtists.indexOf(name);
    if (idx > -1) {
        selectedArtists.splice(idx, 1);
    } else {
        selectedArtists.push(name);
    }
    const keyword = document.getElementById('artistSearchInput').value;
    renderArtistChips(keyword);
    updateSongCount();
}

function updateArtistCountInfo() {
    const el = document.getElementById('artistSelectCountInfo');
    if (selectedArtists.length > 0) {
        el.textContent = `(${selectedArtists.length}명 선택)`;
    } else {
        el.textContent = '';
    }
}

// ========== 노래 개수 조회 ==========

async function updateSongCount() {
    const gameMode = document.querySelector('input[name="gameMode"]:checked').value;

    const artistType = document.querySelector('input[name="artistType"]:checked').value;
    let soloOnly = null;
    let groupOnly = null;
    if (artistType === 'solo') soloOnly = true;
    if (artistType === 'group') groupOnly = true;

    try {
        const body = {};
        if (soloOnly) body.soloOnly = soloOnly;
        if (groupOnly) body.groupOnly = groupOnly;

        // 모드별 파라미터
        if (gameMode === 'FIXED_GENRE') {
            const genreId = document.getElementById('fixedGenreId').value;
            if (genreId) body.genreId = parseInt(genreId);
        } else if (gameMode === 'FIXED_ARTIST') {
            if (selectedArtists.length > 0) body.artists = selectedArtists;
        } else if (gameMode === 'FIXED_YEAR') {
            if (selectedYears.length > 0) body.years = selectedYears;
        }

        const response = await fetch('/game/multi/song-count', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const result = await response.json();

        maxAvailableSongs = result.count;

        const infoEl = document.getElementById('songCountInfo');
        if (maxAvailableSongs === 0) {
            infoEl.textContent = '(사용 가능한 노래 없음)';
            infoEl.style.color = '#ef4444';
        } else {
            infoEl.textContent = `(최대 ${maxAvailableSongs}곡)`;
            infoEl.style.color = '#22c55e';
        }

    } catch (error) {
        // console.error('노래 개수 조회 오류:', error);
    }
}

// ========== 방 만들기 ==========

async function createRoom() {
    const roomName = document.getElementById('roomName').value.trim();

    if (!roomName) {
        showToast('방 이름을 입력해주세요.');
        document.getElementById('roomName').focus();
        return;
    }

    if (roomName.length < 2) {
        showToast('방 이름은 2자 이상 입력해주세요.');
        document.getElementById('roomName').focus();
        return;
    }

    const gameMode = document.querySelector('input[name="gameMode"]:checked').value;

    // 모드별 유효성 검사
    if (gameMode === 'FIXED_GENRE') {
        const genreId = document.getElementById('fixedGenreId').value;
        if (!genreId) {
            showToast('장르를 선택해주세요.');
            return;
        }
    }

    if (gameMode === 'FIXED_ARTIST') {
        if (selectedArtists.length === 0) {
            showToast('아티스트를 선택해주세요.');
            return;
        }
    }

    if (gameMode === 'FIXED_YEAR') {
        if (selectedYears.length === 0) {
            showToast('연도를 선택해주세요.');
            return;
        }
    }

    // 노래 개수 확인
    if (maxAvailableSongs === 0) {
        showToast('현재 조건에 맞는 노래가 없습니다. 조건을 변경해주세요.');
        return;
    }

    const maxPlayers = parseInt(document.getElementById('maxPlayers').value);
    const totalRounds = parseInt(document.getElementById('totalRounds').value);
    const isPrivate = document.getElementById('isPrivate').checked;

    // 라운드 수가 사용 가능한 노래 수를 초과하는지 확인
    if (totalRounds > maxAvailableSongs) {
        showToast(`현재 조건에서 최대 ${maxAvailableSongs}라운드까지 가능합니다.`);
        return;
    }

    // 설정 구성
    const settings = {
        gameMode: gameMode
    };

    // 아티스트 유형
    const artistType = document.querySelector('input[name="artistType"]:checked').value;
    if (artistType === 'solo') settings.soloOnly = true;
    if (artistType === 'group') settings.groupOnly = true;

    if (gameMode === 'FIXED_GENRE') {
        settings.fixedGenreId = parseInt(document.getElementById('fixedGenreId').value);
    }

    if (gameMode === 'FIXED_ARTIST') {
        settings.selectedArtists = selectedArtists;
    }

    if (gameMode === 'FIXED_YEAR') {
        settings.selectedYears = selectedYears;
    }

    try {
        const response = await fetch('/game/multi/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            // 서버는 본문 전체를 GameSettings 로 바인딩한다. 키 이름은 GameSettings 필드와 같아야 하고
            // (privateRoom), 게임 모드 설정도 중첩 객체가 아니라 최상위에 펼쳐서 보낸다.
            body: JSON.stringify({
                roomName: roomName,
                maxPlayers: maxPlayers,
                totalRounds: totalRounds,
                privateRoom: isPrivate,
                ...settings
            })
        });

        const result = await response.json();

        if (result.success) {
            window.location.href = `/game/multi/room/${result.roomCode}`;
        } else {
            showToast(result.message || '방 생성에 실패했습니다.');
        }
    } catch (error) {
        // console.error('방 생성 오류:', error);
        showToast('방 생성 중 오류가 발생했습니다.');
    }
}
