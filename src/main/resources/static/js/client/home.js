// Client home page scripts - Bento Grid Version
let isUserLoggedIn = false;

document.addEventListener('DOMContentLoaded', function() {
    checkLoginStatus();
    loadActiveStagesAndRanking();
    loadGenreChallengeRanking();
    loadRankingPreview();
});

// 디바운스 유틸리티
function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

async function checkLoginStatus() {
    try {
        const response = await fetch('/auth/status');
        const result = await response.json();

        const userInfoDesktop = document.getElementById('userInfoDesktop');
        const userInfoMobile = document.getElementById('userInfoMobile');
        const bentoMyRank = document.getElementById('bentoMyRank');
        const bentoRanking = document.getElementById('bentoRanking');

        if (result.isLoggedIn) {
            isUserLoggedIn = true;
            const adminBtn = result.role === 'ADMIN' ? '<a href="/admin/login" class="btn btn-admin">관리자</a>' : '';
            const adminBtnMobile = result.role === 'ADMIN' ? '<a href="/admin/login" class="mobile-menu-link admin">🛠️ 관리자</a>' : '';

            const safeNickname = escapeHtml(result.nickname);

            // 데스크탑 UI
            userInfoDesktop.innerHTML = `
                <span class="user-greeting">안녕하세요, <strong>${safeNickname}</strong>님!</span>
                <a href="/mypage" class="btn btn-mypage">마이페이지</a>
                <button class="btn btn-logout" onclick="logout()">로그아웃</button>
                ${adminBtn}
            `;

            // 모바일 UI
            userInfoMobile.innerHTML = `
                <div class="mobile-user-greeting">안녕하세요, <strong>${safeNickname}</strong>님!</div>
                <a href="/mypage" class="mobile-menu-link">👤 마이페이지</a>
                <button class="mobile-menu-link" onclick="logout()">🚪 로그아웃</button>
                ${adminBtnMobile}
            `;

            // 벤토 카드: 로그인 시 내 순위 표시, 전체 랭킹 숨김
            if (bentoMyRank) bentoMyRank.classList.remove('hidden');
            if (bentoRanking) bentoRanking.classList.add('hidden');

            // 내 순위 로딩
            loadMyRanking();
        } else {
            isUserLoggedIn = false;

            // 데스크탑 UI
            userInfoDesktop.innerHTML = `
                <a href="/auth/login" class="btn btn-login">로그인</a>
                <a href="/auth/register" class="btn btn-register">회원가입</a>
            `;

            // 모바일 UI
            userInfoMobile.innerHTML = `
                <a href="/auth/login" class="mobile-menu-link">🔑 로그인</a>
                <a href="/auth/register" class="mobile-menu-link primary">✨ 회원가입</a>
            `;

            // 벤토 카드: 비로그인 시 내 순위 숨김
            if (bentoMyRank) bentoMyRank.classList.add('hidden');
        }
    } catch (error) {
        // console.error('로그인 상태 확인 오류:', error);
    }
}

// 모바일 메뉴 토글
function toggleMobileMenu() {
    const menu = document.getElementById('mobileMenu');
    const btn = document.getElementById('hamburgerBtn');
    const isOpen = menu.classList.toggle('open');

    btn.setAttribute('aria-expanded', isOpen);
    btn.setAttribute('aria-label', isOpen ? '메뉴 닫기' : '메뉴 열기');
}

// 메뉴 외부 클릭 시 닫기
document.addEventListener('click', function(e) {
    const menu = document.getElementById('mobileMenu');
    const btn = document.getElementById('hamburgerBtn');

    if (menu && btn && !menu.contains(e.target) && !btn.contains(e.target)) {
        menu.classList.remove('open');
        btn.setAttribute('aria-expanded', 'false');
        btn.setAttribute('aria-label', '메뉴 열기');
    }
});

async function logout() {
    try {
        await fetch('/auth/logout', { method: 'POST' });
        window.location.reload();
    } catch (error) {
        // console.error('로그아웃 오류:', error);
    }
}

async function loadMyRanking() {
    try {
        const response = await fetch('/api/ranking/my');
        const data = await response.json();

        const content = document.getElementById('myRankContent');

        if (!data.loggedIn || !content) {
            return;
        }

        if (data.guessGames > 0) {
            content.innerHTML = `
                <span class="myrank-tier tier-${data.tierName?.toLowerCase() || 'bronze'}">${data.tierDisplayName}</span>
                <div class="myrank-stats">
                    <span class="myrank-rank">#${data.guessRank}</span>
                    <span class="myrank-score">${data.guessScore.toLocaleString()}점</span>
                </div>
            `;
        } else {
            content.innerHTML = `
                <span class="myrank-tier tier-${data.tierName?.toLowerCase() || 'bronze'}">${data.tierDisplayName}</span>
                <div class="myrank-stats">
                    <span class="myrank-score">게임 기록 없음</span>
                </div>
            `;
        }
    } catch (error) {
        // console.error('내 순위 로딩 오류:', error);
    }
}

// 챌린지 데이터 로드 상태
let artistDataLoaded = false;
let genreDataLoaded = false;
let activeStages = [];
let currentStageLevel = 1;

// 활성화된 단계 목록 로드 후 랭킹 로드
async function loadActiveStagesAndRanking() {
    try {
        const response = await fetch('/game/fan-challenge/active-stages');
        activeStages = await response.json();

        // 단계 탭 렌더링 (2개 이상일 때만 표시)
        renderStageTabs();

        // 첫 번째 단계의 랭킹 로드
        if (activeStages.length > 0) {
            currentStageLevel = activeStages[0].level;
            loadArtistChallengeRanking(currentStageLevel);
        } else {
            // 활성화된 단계가 없으면 기본값으로 로드
            loadArtistChallengeRanking(1);
        }
    } catch (error) {
        // 에러 시 기본값으로 로드
        loadArtistChallengeRanking(1);
    }
}

// 단계 탭 렌더링
function renderStageTabs() {
    const tabsContainer = document.getElementById('stageTabs');
    if (!tabsContainer) return;

    // 단계가 1개 이하면 탭 숨김
    if (activeStages.length <= 1) {
        tabsContainer.style.display = 'none';
        return;
    }

    tabsContainer.style.display = 'flex';
    let html = '';
    activeStages.forEach((stage, index) => {
        const activeClass = index === 0 ? 'active' : '';
        html += `<button class="stage-tab ${activeClass}" data-level="${stage.level}" onclick="switchStageTab(${stage.level})">
            ${stage.emoji || ''} ${stage.name || stage.level + '단계'}
        </button>`;
    });

    tabsContainer.innerHTML = html;
}

// 단계 탭 전환
function switchStageTab(stageLevel) {
    currentStageLevel = stageLevel;

    // 탭 버튼 활성화 상태 변경
    const tabs = document.querySelectorAll('#stageTabs .stage-tab');
    tabs.forEach(tab => {
        if (parseInt(tab.dataset.level) === stageLevel) {
            tab.classList.add('active');
        } else {
            tab.classList.remove('active');
        }
    });

    // 해당 단계 랭킹 로드
    loadArtistChallengeRanking(stageLevel);
}

async function loadArtistChallengeRanking(stageLevel = 1) {
    try {
        const response = await fetch(`/game/fan-challenge/top-artists?stageLevel=${stageLevel}`);
        const data = await response.json();

        const section = document.getElementById('bentoChallengeTop');
        const scroll = document.getElementById('artistRankingScroll');

        if (!section || !scroll) {
            return;
        }

        // 데이터가 없어도 섹션은 표시 (탭은 보여야 함)
        if (!data || data.length === 0) {
            scroll.innerHTML = '<div class="artist-scroll-empty">아직 기록이 없습니다</div>';
            section.classList.remove('hidden');
            artistDataLoaded = true;
            return;
        }

        section.classList.remove('hidden');
        artistDataLoaded = true;

        // 서버에서 이미 정렬됨: correctCount DESC → bestTimeMs ASC

        let html = '';
        data.forEach(item => {
            const scoreText = `${item.correctCount}/${item.totalSongs}`;
            const badgeHtml = item.isPerfectClear
                ? '<span class="artist-card-badge">PERFECT</span>'
                : '';
            const timeHtml = item.bestTimeMs
                ? `<div class="artist-card-time">${(item.bestTimeMs / 1000).toFixed(1)}s</div>`
                : '';

            const safeArtist = escapeHtml(item.artist);
            const safeNickname = escapeHtml(item.nickname);
            html += `
                <div class="artist-card">
                    <div class="artist-card-icon">🎵</div>
                    <div class="artist-card-name" title="${safeArtist}">${safeArtist}</div>
                    <div class="artist-card-user">${safeNickname}</div>
                    <div class="artist-card-score">${scoreText}</div>
                    ${timeHtml}
                    ${badgeHtml}
                </div>
            `;
        });

        scroll.innerHTML = html;

        // PC 드래그 스크롤 활성화
        if (typeof enableDragScroll === 'function') {
            enableDragScroll(scroll);
        }
    } catch (error) {
        // console.error('아티스트 챌린지 랭킹 로딩 오류:', error);
    }
}

async function loadGenreChallengeRanking() {
    try {
        const response = await fetch('/game/genre-challenge/top-genres');
        const data = await response.json();

        const section = document.getElementById('bentoChallengeTop');
        const scroll = document.getElementById('genreRankingScroll');

        if (!data || data.length === 0 || !section || !scroll) {
            return;
        }

        // 아티스트 데이터가 없으면 장르 데이터로 섹션 표시
        if (!artistDataLoaded) {
            section.classList.remove('hidden');
        }
        genreDataLoaded = true;

        // 서버에서 이미 정렬됨: correctCount DESC → bestTimeMs ASC

        let html = '';
        data.forEach(item => {
            const scoreText = `${item.correctCount}/${item.totalSongs}`;
            const timeHtml = item.bestTimeMs
                ? `<div class="artist-card-time">${(item.bestTimeMs / 1000).toFixed(1)}s</div>`
                : '';

            const safeGenre = escapeHtml(item.genreName);
            const safeNickname = escapeHtml(item.nickname);
            html += `
                <div class="artist-card">
                    <div class="artist-card-icon">🎸</div>
                    <div class="artist-card-name" title="${safeGenre}">${safeGenre}</div>
                    <div class="artist-card-user">${safeNickname}</div>
                    <div class="artist-card-score">${scoreText}</div>
                    ${timeHtml}
                </div>
            `;
        });

        scroll.innerHTML = html;

        // PC 드래그 스크롤 활성화
        if (typeof enableDragScroll === 'function') {
            enableDragScroll(scroll);
        }
    } catch (error) {
        // console.error('장르 챌린지 랭킹 로딩 오류:', error);
    }
}

// 탭 전환 함수
function switchChallengeTab(tabType) {
    // 탭 버튼 활성화 상태 변경
    const tabs = document.querySelectorAll('.challenge-tab');
    tabs.forEach(tab => {
        if (tab.dataset.tab === tabType) {
            tab.classList.add('active');
        } else {
            tab.classList.remove('active');
        }
    });

    // 콘텐츠 표시 변경
    const artistContent = document.getElementById('artistContent');
    const genreContent = document.getElementById('genreContent');

    if (tabType === 'artist') {
        artistContent.classList.add('active');
        genreContent.classList.remove('active');
    } else {
        artistContent.classList.remove('active');
        genreContent.classList.add('active');
    }
}

// 전체 랭킹 미리보기 로딩 (TOP 3)
async function loadRankingPreview() {
    try {
        const response = await fetch('/api/ranking?mode=guess&type=score&period=all&limit=3');
        const data = await response.json();

        const preview = document.getElementById('rankingPreview');
        if (!preview || !Array.isArray(data) || data.length === 0) {
            return;
        }

        const medals = ['🥇', '🥈', '🥉'];
        let html = '';

        data.slice(0, 3).forEach((item, index) => {
            html += `
                <div class="ranking-preview-item">
                    <span class="ranking-preview-rank">${medals[index]}</span>
                    <span class="ranking-preview-name">${escapeHtml(item.nickname)}</span>
                    <span class="ranking-preview-score">${item.totalScore?.toLocaleString() || 0}</span>
                </div>
            `;
        });

        preview.innerHTML = html;
    } catch (error) {
        // console.error('랭킹 미리보기 로딩 오류:', error);
    }
}
