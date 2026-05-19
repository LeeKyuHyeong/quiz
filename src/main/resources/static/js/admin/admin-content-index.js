/**
 * 콘텐츠 관리 통합 페이지 JavaScript
 * 노래 관리, 정답 관리, 장르 관리, 대중성 투표 탭을 하나의 JS로 통합
 */

var currentTab = 'song';
var currentParams = {};
var tabParams = {}; // 탭별 검색 파라미터 저장

// YouTube API 관련
var ytPlayer = null;
var ytApiReady = false;

// 대중성 투표 정렬 상태
var popularitySort = '';
var popularityDirection = 'desc';

// ========== Initialization ==========

document.addEventListener('DOMContentLoaded', function() {
    // URL에서 탭 파라미터 확인
    const urlParams = new URLSearchParams(window.location.search);
    const tab = urlParams.get('tab') || currentTab;
    currentTab = tab;

    // sessionStorage에서 탭별 파라미터 복원
    const savedTabParams = sessionStorage.getItem('admin_content_tabParams');
    if (savedTabParams) {
        try {
            tabParams = JSON.parse(savedTabParams);
        } catch (e) {
            tabParams = {};
        }
    }

    // 초기 탭 콘텐츠 로드 (저장된 파라미터가 있으면 사용)
    loadTabContent(tab, tabParams[tab] || null);

    // 탭 버튼 활성화
    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
    const activeBtn = document.querySelector('[data-tab="' + tab + '"]');
    if (activeBtn) {
        activeBtn.classList.add('active');
    }

    // YouTube API 로드
    loadYouTubeAPI();

    // 모달 이벤트 설정
    setupModalEvents();
});

// 브라우저 뒤로가기/앞으로가기 처리
window.addEventListener('popstate', function(event) {
    if (event.state && event.state.tab) {
        currentTab = event.state.tab;
        loadTabContent(event.state.tab);
        document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
        const activeBtn = document.querySelector('[data-tab="' + event.state.tab + '"]');
        if (activeBtn) activeBtn.classList.add('active');
    }
});

// ========== Tab Management ==========

function switchTab(tab) {
    if (currentTab === tab) return;

    currentTab = tab;
    currentParams = tabParams[tab] ? new URLSearchParams(tabParams[tab]) : {};

    // 탭 버튼 활성화 상태 변경
    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
    const activeBtn = document.querySelector('[data-tab="' + tab + '"]');
    if (activeBtn) activeBtn.classList.add('active');

    // URL 업데이트
    const url = new URL(window.location);
    url.searchParams.set('tab', tab);
    history.pushState({tab: tab}, '', url);

    // 콘텐츠 로드 (저장된 파라미터가 있으면 사용)
    loadTabContent(tab, tabParams[tab] || null);
}

// 탭별 파라미터 저장
function saveTabParams(tab, params) {
    if (params) {
        tabParams[tab] = typeof params === 'string' ? params : new URLSearchParams(params).toString();
    } else {
        delete tabParams[tab];
    }
    sessionStorage.setItem('admin_content_tabParams', JSON.stringify(tabParams));
}

function loadTabContent(tab, params) {
    const container = document.getElementById('tabContent');
    container.innerHTML = '<div class="loading-spinner"><div class="spinner"></div><span>로딩 중...</span></div>';

    let url = '';
    switch (tab) {
        case 'song':
            url = '/admin/song/content';
            break;
        case 'answer':
            url = '/admin/answer/content';
            break;
        case 'genre':
            url = '/admin/genre/content';
            break;
        case 'popularity':
            url = '/admin/song-popularity/content';
            break;
        case 'report':
            url = '/admin/report/content';
            break;
        default:
            url = '/admin/song/content';
    }

    // 파라미터 추가
    if (params) {
        const searchParams = typeof params === 'string' ? params : new URLSearchParams(params).toString();
        url += '?' + searchParams;
        // 탭별 파라미터 저장
        saveTabParams(tab, searchParams);
    }

    fetch(url)
        .then(response => response.text())
        .then(html => {
            container.innerHTML = html;
            initializeTabScripts();
        })
        .catch(error => {
            container.innerHTML = '<div class="error-message">콘텐츠를 불러오는데 실패했습니다.</div>';
            console.error('Error loading tab content:', error);
        });
}

function initializeTabScripts() {
    // 동적으로 로드된 인라인 스크립트만 실행
    // 주의: 외부 스크립트(script.src)는 로드하지 않음 - 함수 충돌 방지
    const scripts = document.querySelectorAll('#tabContent script');

    scripts.forEach(script => {
        // 인라인 스크립트만 실행 (외부 스크립트는 무시)
        if (!script.src && script.textContent) {
            const newScript = document.createElement('script');
            newScript.textContent = script.textContent;
            document.body.appendChild(newScript);
        }
    });

    // 탭별 초기화
    if (currentTab === 'song') {
        initSongTabScripts();
    } else if (currentTab === 'answer') {
        initAnswerTabScripts();
    } else if (currentTab === 'genre') {
        initGenreTabScripts();
    } else if (currentTab === 'popularity') {
        initPopularityTabScripts();
    } else if (currentTab === 'report') {
        initReportTabScripts();
    }
}

// 외부 스크립트 순차 로드
function loadScriptsSequentially(urls, callback) {
    if (urls.length === 0) {
        callback();
        return;
    }

    const url = urls.shift();

    // 이미 로드된 스크립트인지 확인
    if (document.querySelector(`script[src="${url}"]`)) {
        loadScriptsSequentially(urls, callback);
        return;
    }

    const script = document.createElement('script');
    script.src = url;
    script.onload = () => loadScriptsSequentially(urls, callback);
    script.onerror = () => {
        console.error('Failed to load script:', url);
        loadScriptsSequentially(urls, callback);
    };
    document.body.appendChild(script);
}

// ========== Song Tab Functions ==========

function initSongTabScripts() {
    const filterForm = document.getElementById('filterForm');
    if (filterForm) {
        filterForm.addEventListener('submit', (e) => {
            e.preventDefault();
            handleFormSubmit();
            const params = new URLSearchParams(new FormData(filterForm)).toString();
            loadTabContent('song', params);
        });
    }

    const resetBtn = document.querySelector('.tab-content .btn-reset');
    if (resetBtn) {
        resetBtn.addEventListener('click', (e) => {
            e.preventDefault();
            saveTabParams('song', null); // 저장된 파라미터 삭제
            loadTabContent('song');
        });
    }

    updateSelectedArtists();
    sortArtistList();
}

function refreshSongList() {
    const form = document.getElementById('filterForm');
    if (form) {
        const params = new URLSearchParams(new FormData(form)).toString();
        loadTabContent('song', params);
    } else {
        loadTabContent('song');
    }
}

// ========== Song CRUD Functions ==========

function openSongModal() {
    const modal = document.getElementById('songModal');
    const modalTitle = document.getElementById('modalTitle');
    const songForm = document.getElementById('songForm');

    modalTitle.textContent = '노래 추가';
    songForm.reset();
    document.getElementById('songId').value = '';
    document.getElementById('currentYoutubeId').textContent = '';
    document.querySelector('input[name="useYn"][value="Y"]').checked = true;
    document.querySelector('input[name="isSolo"][value="false"]').checked = true;
    document.querySelector('input[name="isPopular"][value="true"]').checked = true;
    modal.classList.add('show');
    document.body.style.overflow = 'hidden';
}

function closeSongModal() {
    const modal = document.getElementById('songModal');
    const songForm = document.getElementById('songForm');
    modal.classList.remove('show');
    songForm.reset();
    document.getElementById('currentYoutubeId').textContent = '';
    document.body.style.overflow = '';
}

async function editSong(id) {
    try {
        const response = await fetch(`/admin/song/detail/${id}`);
        if (!response.ok) throw new Error('노래 정보를 불러올 수 없습니다.');

        const song = await response.json();
        const modal = document.getElementById('songModal');
        const modalTitle = document.getElementById('modalTitle');

        modalTitle.textContent = '노래 수정';
        document.getElementById('songId').value = song.id;
        document.getElementById('title').value = song.title || '';
        document.getElementById('artist').value = song.artist || '';
        document.getElementById('startTime').value = song.startTime || 0;
        document.getElementById('playDuration').value = song.playDuration || 10;
        document.getElementById('genreId').value = song.genreId || '';
        document.getElementById('releaseYear').value = song.releaseYear || '';

        const isSoloRadio = document.querySelector(`input[name="isSolo"][value="${song.isSolo}"]`);
        if (isSoloRadio) isSoloRadio.checked = true;

        const isPopularRadio = document.querySelector(`input[name="isPopular"][value="${song.isPopular !== false}"]`);
        if (isPopularRadio) isPopularRadio.checked = true;

        const useYnRadio = document.querySelector(`input[name="useYn"][value="${song.useYn || 'Y'}"]`);
        if (useYnRadio) useYnRadio.checked = true;

        const currentYoutubeIdDiv = document.getElementById('currentYoutubeId');
        if (song.youtubeVideoId) {
            currentYoutubeIdDiv.innerHTML = `<span class="file-info">현재 Video ID: ${song.youtubeVideoId}</span>`;
        } else {
            currentYoutubeIdDiv.textContent = '';
        }

        modal.classList.add('show');
        document.body.style.overflow = 'hidden';
    } catch (error) {
        showToast(error.message, 'error');
    }
}

async function deleteSong(id) {
    if (!confirm('정말 삭제하시겠습니까?')) return;

    try {
        const response = await fetch(`/admin/song/delete/${id}`, { method: 'POST' });
        const result = await response.json();

        if (result.success) {
            showToast(result.message, 'success');
            refreshSongList();
        } else {
            showToast(result.message, 'error');
        }
    } catch (error) {
        showToast('삭제 중 오류가 발생했습니다.', 'error');
    }
}

async function toggleStatus(id) {
    try {
        const response = await fetch(`/admin/song/toggle/${id}`, { method: 'POST' });
        const result = await response.json();

        if (result.success) {
            const useYn = result.useYn;
            const isActive = useYn === 'Y';

            // 테이블 뷰: 해당 행의 상태 배지 업데이트
            const row = document.querySelector(`tr button.btn-toggle[data-id="${id}"]`)?.closest('tr');
            if (row) {
                const statusSpan = row.querySelector('.status');
                if (statusSpan) {
                    statusSpan.classList.remove('active', 'inactive');
                    statusSpan.classList.add(isActive ? 'active' : 'inactive');
                    statusSpan.textContent = isActive ? '사용' : '미사용';
                }
            }

            // 그리드 뷰: 카드 상태 업데이트
            const card = document.querySelector(`.song-card button.btn-toggle[data-id="${id}"]`)?.closest('.song-card');
            if (card) {
                card.classList.toggle('disabled', !isActive);
                const statusSpan = card.querySelector('.song-card-header .status');
                if (statusSpan) {
                    statusSpan.classList.remove('active', 'inactive');
                    statusSpan.classList.add(isActive ? 'active' : 'inactive');
                    statusSpan.textContent = isActive ? '사용' : '미사용';
                }
            }

            showToast(result.message, 'success');
        } else {
            showToast(result.message, 'error');
        }
    } catch (error) {
        showToast('상태 변경 중 오류가 발생했습니다.', 'error');
    }
}

async function togglePopular(id) {
    try {
        const response = await fetch(`/admin/song/togglePopular/${id}`, { method: 'POST' });
        const result = await response.json();

        if (result.success) {
            const buttons = document.querySelectorAll(`button.btn-popular[data-id="${id}"]`);
            buttons.forEach(btn => {
                const isPopular = result.isPopular;
                btn.classList.remove('popular', 'maniac');
                btn.classList.add(isPopular ? 'popular' : 'maniac');
                btn.textContent = isPopular ? (btn.closest('.card-actions') ? '대중' : '대중곡') : '매니악';
            });
        } else {
            showToast(result.message, 'error');
        }
    } catch (error) {
        showToast('상태 변경 중 오류가 발생했습니다.', 'error');
    }
}

async function saveSong(e) {
    e.preventDefault();

    const formData = new FormData();
    const songId = document.getElementById('songId').value;
    if (songId) formData.append('id', songId);

    formData.append('title', document.getElementById('title').value);
    formData.append('artist', document.getElementById('artist').value);
    formData.append('startTime', document.getElementById('startTime').value || 0);
    formData.append('playDuration', document.getElementById('playDuration').value || 10);

    const genreId = document.getElementById('genreId').value;
    if (genreId) formData.append('genreId', genreId);

    const releaseYear = document.getElementById('releaseYear').value;
    if (releaseYear) formData.append('releaseYear', releaseYear);

    formData.append('isSolo', document.querySelector('input[name="isSolo"]:checked').value);
    formData.append('isPopular', document.querySelector('input[name="isPopular"]:checked').value);
    formData.append('useYn', document.querySelector('input[name="useYn"]:checked').value);

    const youtubeUrl = document.getElementById('youtubeUrl').value;
    if (youtubeUrl) formData.append('youtubeUrl', youtubeUrl);

    try {
        const response = await fetch('/admin/song/save', {
            method: 'POST',
            body: formData
        });

        const result = await response.json();

        if (result.success) {
            showToast(result.message, 'success');
            closeSongModal();
            refreshSongList();
        } else {
            showToast(result.message, 'error');
        }
    } catch (error) {
        showToast('저장 중 오류가 발생했습니다.', 'error');
    }
}

// ========== Answer Tab Functions ==========

function initAnswerTabScripts() {
    // answerFilterForm 또는 search-form 클래스로 폼 찾기
    const searchForm = document.getElementById('answerFilterForm') || document.querySelector('.tab-content .search-form');
    if (searchForm) {
        searchForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const formData = new FormData(searchForm);
            const params = new URLSearchParams(formData).toString();
            loadTabContent('answer', params);
        });
    }

    const resetBtn = document.querySelector('.tab-content .btn-reset');
    if (resetBtn) {
        resetBtn.addEventListener('click', (e) => {
            e.preventDefault();
            saveTabParams('answer', null); // 저장된 파라미터 삭제
            loadTabContent('answer');
        });
    }
}

function resetAnswerFilter() {
    const keywordInput = document.querySelector('[name="keyword"]');
    if (keywordInput) keywordInput.value = '';
    saveTabParams('answer', null); // 저장된 파라미터 삭제
    loadTabContent('answer');
}

function sortAnswerBy(field) {
    const params = new URLSearchParams(window.location.search);
    const currentSort = params.get('sort');
    const currentDirection = params.get('direction') || 'desc';

    let direction = 'desc';
    if (currentSort === field) {
        direction = currentDirection === 'asc' ? 'desc' : 'asc';
    }
    loadTabContent('answer', {sort: field, direction: direction});
}

function loadAnswerPage(page) {
    const params = new URLSearchParams(window.location.search);
    params.set('page', page);
    loadTabContent('answer', params.toString());
}

// ========== Genre Tab Functions ==========

function initGenreTabScripts() {
    // genreFilterForm ID 또는 .search-form 클래스로 폼 찾기
    const searchForm = document.getElementById('genreFilterForm') || document.querySelector('.tab-content .search-form');
    if (searchForm) {
        searchForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const formData = new FormData(searchForm);
            const params = new URLSearchParams(formData).toString();
            loadTabContent('genre', params);
        });
    }

    const resetBtn = document.querySelector('.tab-content .btn-reset');
    if (resetBtn) {
        resetBtn.addEventListener('click', (e) => {
            e.preventDefault();
            saveTabParams('genre', null); // 저장된 파라미터 삭제
            loadTabContent('genre');
        });
    }
}

async function toggleGenreStatus(id) {
    try {
        const response = await fetch(`/admin/genre/toggle/${id}`, { method: 'POST' });
        const result = await response.json();

        if (result.success) {
            const isActive = result.useYn === 'Y';

            const row = document.querySelector(`tr button.btn-toggle[data-id="${id}"]`)?.closest('tr');
            if (row) {
                const statusSpan = row.querySelector('.status');
                if (statusSpan) {
                    statusSpan.classList.remove('active', 'inactive');
                    statusSpan.classList.add(isActive ? 'active' : 'inactive');
                    statusSpan.textContent = isActive ? '사용' : '미사용';
                }
            }

            showToast(result.message, 'success');
        } else {
            showToast(result.message, 'error');
        }
    } catch (error) {
        showToast('상태 변경 중 오류가 발생했습니다.', 'error');
    }
}

function resetGenreFilter() {
    const keywordInput = document.querySelector('[name="keyword"]');
    if (keywordInput) keywordInput.value = '';
    saveTabParams('genre', null); // 저장된 파라미터 삭제
    loadTabContent('genre');
}

function sortGenreBy(field) {
    const params = new URLSearchParams(window.location.search);
    const currentSort = params.get('sort');
    const currentDirection = params.get('direction') || 'desc';

    let direction = 'desc';
    if (currentSort === field) {
        direction = currentDirection === 'asc' ? 'desc' : 'asc';
    }
    loadTabContent('genre', {sort: field, direction: direction});
}

function loadGenrePage(page) {
    const params = new URLSearchParams(window.location.search);
    params.set('page', page);
    loadTabContent('genre', params.toString());
}

function openGenreModal() {
    const modal = document.getElementById('genreModal');
    if (modal) {
        modal.classList.add('show');
        document.getElementById('genreModalTitle').textContent = '장르 추가';
        document.getElementById('genreForm').reset();
        const genreIdInput = document.getElementById('genreIdInput');
        if (genreIdInput) genreIdInput.value = '';
        document.body.style.overflow = 'hidden';
    }
}

function closeGenreModal() {
    const modal = document.getElementById('genreModal');
    if (modal) {
        modal.classList.remove('show');
        document.body.style.overflow = '';
    }
}

async function editGenre(id) {
    try {
        const response = await fetch(`/admin/genre/detail/${id}`);
        if (!response.ok) throw new Error('장르 정보를 불러올 수 없습니다.');

        const genre = await response.json();
        const modal = document.getElementById('genreModal');

        document.getElementById('genreModalTitle').textContent = '장르 수정';
        document.getElementById('genreIdInput').value = genre.id;
        document.getElementById('genreCode').value = genre.code || '';
        document.getElementById('genreName').value = genre.name || '';

        const useYnRadio = document.querySelector(`input[name="genreUseYn"][value="${genre.useYn || 'Y'}"]`);
        if (useYnRadio) useYnRadio.checked = true;

        modal.classList.add('show');
        document.body.style.overflow = 'hidden';
    } catch (error) {
        showToast(error.message, 'error');
    }
}

async function deleteGenre(id) {
    if (!confirm('정말 삭제하시겠습니까? 해당 장르의 모든 곡에서 장르가 제거됩니다.')) return;

    try {
        const response = await fetch(`/admin/genre/delete/${id}`, { method: 'POST' });
        const result = await response.json();

        if (result.success) {
            showToast(result.message, 'success');
            loadTabContent('genre');
        } else {
            showToast(result.message, 'error');
        }
    } catch (error) {
        showToast('삭제 중 오류가 발생했습니다.', 'error');
    }
}

async function saveGenre(e) {
    e.preventDefault();

    const formData = new FormData();
    const genreId = document.getElementById('genreIdInput').value;
    if (genreId) formData.append('id', genreId);

    formData.append('code', document.getElementById('genreCode').value);
    formData.append('name', document.getElementById('genreName').value);
    formData.append('useYn', document.querySelector('input[name="genreUseYn"]:checked').value);

    try {
        const response = await fetch('/admin/genre/save', {
            method: 'POST',
            body: formData
        });

        const result = await response.json();

        if (result.success) {
            showToast(result.message, 'success');
            closeGenreModal();
            loadTabContent('genre');
        } else {
            showToast(result.message, 'error');
        }
    } catch (error) {
        showToast('저장 중 오류가 발생했습니다.', 'error');
    }
}

// ========== Report Tab Functions ==========

function initReportTabScripts() {
    const statusFilter = document.getElementById('statusFilter');
    if (statusFilter) {
        statusFilter.onchange = function() {
            const params = this.value ? `status=${this.value}` : '';
            loadTabContent('report', params);
        };
    }

    const resetBtn = document.querySelector('.tab-content .btn-reset');
    if (resetBtn) {
        resetBtn.addEventListener('click', (e) => {
            e.preventDefault();
            saveTabParams('report', null); // 저장된 파라미터 삭제
            loadTabContent('report');
        });
    }
}

function goToReportPage(page) {
    const statusFilter = document.getElementById('statusFilter');
    const params = new URLSearchParams();
    if (statusFilter && statusFilter.value) params.set('status', statusFilter.value);
    params.set('page', page);
    loadTabContent('report', params.toString());
}

async function processReport(id, status) {
    const adminNote = prompt('관리자 메모 (선택사항):') || '';

    try {
        const response = await fetch(`/admin/report/process/${id}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ status: status, adminNote: adminNote })
        });

        const result = await response.json();
        if (result.success) {
            showToast(result.message, 'success');
            loadTabContent('report');
        } else {
            showToast(result.message, 'error');
        }
    } catch (error) {
        showToast('처리 중 오류가 발생했습니다.', 'error');
    }
}

function openProcessModal(button) {
    const id = button.dataset.id;
    const title = button.dataset.songTitle || '';
    const artist = button.dataset.songArtist || '';
    const reportType = button.dataset.reportType || '';
    const description = button.dataset.description || '';
    const currentStatus = button.dataset.status || 'PENDING';

    document.getElementById('reportProcessId').value = id;
    document.getElementById('reportProcessSongTitle').textContent = title;
    document.getElementById('reportProcessSongArtist').textContent = artist;
    document.getElementById('reportProcessType').textContent = reportType;
    document.getElementById('reportProcessDescription').textContent = description || '(내용 없음)';

    const statusSelect = document.getElementById('reportProcessStatus');
    statusSelect.value = (currentStatus === 'PENDING') ? 'CONFIRMED' : currentStatus;

    document.getElementById('reportProcessAdminNote').value = '';

    openModal('reportProcessModal');
}

function closeProcessModal() {
    closeModal('reportProcessModal');
}

async function submitProcessReport(event) {
    event.preventDefault();

    const id = document.getElementById('reportProcessId').value;
    const status = document.getElementById('reportProcessStatus').value;
    const adminNote = document.getElementById('reportProcessAdminNote').value || '';

    if (!id || !status) {
        showToast('필수 정보가 누락되었습니다.', 'error');
        return;
    }

    try {
        const response = await fetch(`/admin/report/process/${id}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ status: status, adminNote: adminNote })
        });

        const result = await response.json();
        if (result.success) {
            showToast(result.message || '처리되었습니다.', 'success');
            closeProcessModal();
            loadTabContent('report');
        } else {
            showToast(result.message || '처리에 실패했습니다.', 'error');
        }
    } catch (error) {
        showToast('처리 중 오류가 발생했습니다.', 'error');
    }
}

function disableSong(id) {
    return disableReportedSong(id);
}

async function disableReportedSong(id) {
    if (!confirm('해당 곡을 비활성화하고 신고를 승인 처리하시겠습니까?')) return;

    try {
        const response = await fetch(`/admin/report/disable-song/${id}`, { method: 'POST' });
        const result = await response.json();

        if (result.success) {
            showToast(result.message, 'success');
            loadTabContent('report');
        } else {
            showToast(result.message, 'error');
        }
    } catch (error) {
        showToast('처리 중 오류가 발생했습니다.', 'error');
    }
}

// ========== Popularity Tab Functions ==========

function initPopularityTabScripts() {
    const searchForm = document.querySelector('.tab-content .search-form');
    if (searchForm) {
        searchForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const formData = new FormData(searchForm);
            const params = new URLSearchParams(formData).toString();
            loadTabContent('popularity', params);
        });
    }

    const resetBtn = document.querySelector('.tab-content .btn-reset');
    if (resetBtn) {
        resetBtn.addEventListener('click', (e) => {
            e.preventDefault();
            saveTabParams('popularity', null); // 저장된 파라미터 삭제
            loadTabContent('popularity');
        });
    }
}

function sortPopularityBy(column) {
    const params = new URLSearchParams();
    const form = document.querySelector('.tab-content .search-form');

    if (form) {
        new FormData(form).forEach((value, key) => {
            if (value) params.set(key, value);
        });
    }

    if (popularitySort === column) {
        popularityDirection = popularityDirection === 'asc' ? 'desc' : 'asc';
    } else {
        popularitySort = column;
        popularityDirection = 'desc';
    }

    params.set('sort', column);
    params.set('direction', popularityDirection);
    params.set('page', '0');

    loadTabContent('popularity', params.toString());
}

async function viewSongDetail(button) {
    const songId = button.dataset.songId;
    const title = button.dataset.title;
    const artist = button.dataset.artist;

    document.getElementById('songDetailTitle').textContent = `${title} - ${artist}`;

    const detailContent = document.getElementById('songDetailContent');
    detailContent.innerHTML = '<div class="loading-spinner"><div class="spinner"></div><span>로딩 중...</span></div>';

    openModal('songDetailModal');

    try {
        const response = await fetch(`/admin/song-popularity/song/${songId}/votes`);
        if (!response.ok) throw new Error('데이터를 불러올 수 없습니다.');

        const data = await response.json();
        renderSongVotes(data, detailContent);
    } catch (error) {
        detailContent.innerHTML = `<div class="error-message"><p>${error.message}</p></div>`;
    }
}

function renderSongVotes(data, container) {
    if (data.votes.length === 0) {
        container.innerHTML = '<p class="empty-message">투표 데이터가 없습니다.</p>';
        return;
    }

    const table = document.createElement('table');
    table.className = 'data-table vote-detail-table';

    const thead = document.createElement('thead');
    thead.innerHTML = `
        <tr>
            <th>회원</th>
            <th>평점</th>
            <th>평가</th>
            <th>투표일</th>
        </tr>
    `;
    table.appendChild(thead);

    const tbody = document.createElement('tbody');
    data.votes.forEach(vote => {
        const tr = document.createElement('tr');
        const ratingStars = '★'.repeat(vote.rating) + '☆'.repeat(5 - vote.rating);
        const createdAt = new Date(vote.createdAt).toLocaleString('ko-KR', {
            year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
        });

        tr.innerHTML = `
            <td>${escapeHtml(vote.memberNickname)}</td>
            <td class="rating-stars">${ratingStars}</td>
            <td><span class="rating-label rating-${vote.rating}">${vote.ratingLabel}</span></td>
            <td>${createdAt}</td>
        `;
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);

    container.textContent = '';
    container.appendChild(table);

    if (data.totalPages > 1) {
        const pageInfo = document.createElement('p');
        pageInfo.className = 'page-info';
        pageInfo.textContent = `총 ${data.totalElements}건 중 ${data.votes.length}건 표시`;
        container.appendChild(pageInfo);
    }
}

// ========== Row & Pagination Functions ==========

function goToPage(page) {
    const form = document.getElementById('filterForm') || document.querySelector('.tab-content .search-form');
    const params = new URLSearchParams();

    if (form) {
        new FormData(form).forEach((v, k) => { if (v) params.set(k, v); });
    }
    params.set('page', page);
    loadTabContent(currentTab, params.toString());
}

// ========== Sort & View Mode (Song Tab) ==========

function sortBy(column) {
    const form = document.getElementById('filterForm');
    if (!form) return;

    const sortInput = form.querySelector('input[name="sort"]');
    const directionInput = form.querySelector('input[name="direction"]');
    const currentSort = sortInput.value;
    const currentDirection = directionInput.value;

    if (currentSort === column) {
        directionInput.value = currentDirection === 'asc' ? 'desc' : 'asc';
    } else {
        sortInput.value = column;
        directionInput.value = 'desc';
    }
    form.querySelector('input[name="page"]').value = '0';

    const params = new URLSearchParams(new FormData(form)).toString();
    loadTabContent('song', params);
}

function setViewMode(mode) {
    const form = document.getElementById('filterForm');
    if (!form) return;

    form.querySelector('input[name="viewMode"]').value = mode;
    form.querySelector('input[name="page"]').value = '0';

    const params = new URLSearchParams(new FormData(form)).toString();
    loadTabContent('song', params);
}

// ========== Artist Filter Functions ==========

function updateSelectedArtists() {
    const checkboxes = document.querySelectorAll('#artistCheckboxGrid input[type="checkbox"]');
    if (!checkboxes.length) return;

    const tagsContainer = document.getElementById('selectedArtistsTags');
    const countSpan = document.getElementById('selectedCount');

    let selectedArtists = [];
    checkboxes.forEach(cb => {
        if (cb.checked) {
            const item = cb.closest('.artist-checkbox-item');
            selectedArtists.push({ name: item.dataset.name, count: item.dataset.count });
        }
    });

    if (countSpan) countSpan.textContent = selectedArtists.length;
    updateSelectAllCheckboxState();

    if (tagsContainer) {
        tagsContainer.replaceChildren();
        if (selectedArtists.length === 0) {
            const span = document.createElement('span');
            span.className = 'no-selection-text';
            span.textContent = '선택된 아티스트가 없습니다';
            tagsContainer.appendChild(span);
        } else {
            selectedArtists.forEach(a => {
                const tag = document.createElement('span');
                tag.className = 'selected-artist-tag';
                tag.innerHTML = `<span class="tag-name">${escapeHtml(a.name)}</span><span class="tag-count">(${a.count})</span>`;
                const removeBtn = document.createElement('button');
                removeBtn.type = 'button';
                removeBtn.className = 'tag-remove';
                removeBtn.textContent = '×';
                removeBtn.onclick = () => removeArtist(a.name);
                tag.appendChild(removeBtn);
                tagsContainer.appendChild(tag);
            });
        }
    }
}

function removeArtist(name) {
    const checkboxes = document.querySelectorAll('#artistCheckboxGrid input[type="checkbox"]');
    checkboxes.forEach(cb => { if (cb.value === name) cb.checked = false; });
    updateSelectedArtists();
}

function handleFormSubmit() {
    const checkboxes = document.querySelectorAll('#artistCheckboxGrid input[type="checkbox"]');
    const allCheckboxes = Array.from(checkboxes);
    const checkedCount = allCheckboxes.filter(cb => cb.checked).length;
    if (checkedCount === allCheckboxes.length && allCheckboxes.length > 0) {
        allCheckboxes.forEach(cb => { cb.checked = false; });
    }
}

function toggleSelectAllArtists() {
    const selectAllCheckbox = document.getElementById('selectAllArtists');
    const checkboxes = document.querySelectorAll('#artistCheckboxGrid input[type="checkbox"]');
    const visibleCheckboxes = Array.from(checkboxes).filter(cb => !cb.closest('.artist-checkbox-item').classList.contains('hidden'));
    visibleCheckboxes.forEach(cb => { cb.checked = selectAllCheckbox.checked; });
    updateSelectedArtists();
}

function updateSelectAllCheckboxState() {
    const selectAllCheckbox = document.getElementById('selectAllArtists');
    if (!selectAllCheckbox) return;
    const checkboxes = document.querySelectorAll('#artistCheckboxGrid input[type="checkbox"]');
    const visibleCheckboxes = Array.from(checkboxes).filter(cb => !cb.closest('.artist-checkbox-item').classList.contains('hidden'));
    if (visibleCheckboxes.length === 0) {
        selectAllCheckbox.checked = false;
        selectAllCheckbox.indeterminate = false;
    } else {
        const checkedCount = visibleCheckboxes.filter(cb => cb.checked).length;
        selectAllCheckbox.checked = checkedCount === visibleCheckboxes.length;
        selectAllCheckbox.indeterminate = checkedCount > 0 && checkedCount < visibleCheckboxes.length;
    }
}

function filterArtistList() {
    const searchInput = document.getElementById('artistSearchInput');
    const keyword = searchInput.value.toLowerCase().trim();
    const items = document.querySelectorAll('#artistCheckboxGrid .artist-checkbox-item');
    items.forEach(item => {
        const name = item.dataset.name.toLowerCase();
        item.classList.toggle('hidden', keyword !== '' && !name.includes(keyword));
    });
    updateSelectAllCheckboxState();
}

function sortArtistList() {
    const sortSelect = document.getElementById('artistSortOrder');
    const grid = document.getElementById('artistCheckboxGrid');
    if (!sortSelect || !grid) return;

    const sortOrder = sortSelect.value;
    const items = Array.from(grid.querySelectorAll('.artist-checkbox-item'));
    items.sort((a, b) => {
        if (sortOrder === 'count') {
            return parseInt(b.dataset.count) - parseInt(a.dataset.count);
        } else {
            return a.dataset.name.localeCompare(b.dataset.name, 'ko');
        }
    });
    items.forEach(item => grid.appendChild(item));
}

function toggleShowMore() {
    const grid = document.getElementById('artistCheckboxGrid');
    const btn = document.getElementById('showMoreBtn');
    if (grid.classList.contains('expanded')) {
        grid.classList.remove('expanded');
        btn.textContent = '더보기 ▼';
    } else {
        grid.classList.add('expanded');
        btn.textContent = '접기 ▲';
    }
}

// ========== YouTube Preview ==========

function loadYouTubeAPI() {
    if (document.querySelector('script[src*="youtube.com/iframe_api"]')) return;

    const tag = document.createElement('script');
    tag.src = "https://www.youtube.com/iframe_api";
    const firstScriptTag = document.getElementsByTagName('script')[0];
    firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);
}

function onYouTubeIframeAPIReady() {
    ytApiReady = true;
}

function openYoutubePreview(btn) {
    const videoId = btn.dataset.videoId;
    const startTime = parseInt(btn.dataset.startTime) || 0;
    const duration = parseInt(btn.dataset.duration) || 10;
    const title = btn.dataset.title;
    const artist = btn.dataset.artist;

    document.getElementById('youtubePreviewTitle').textContent = 'YouTube 미리보기';
    document.getElementById('previewSongInfo').textContent = `${title} - ${artist}`;
    document.getElementById('previewTimeInfo').textContent = `시작: ${startTime}초 / 재생: ${duration}초`;
    document.getElementById('previewStatus').textContent = '로딩 중...';

    document.getElementById('youtubePreviewModal').classList.add('show');
    document.body.style.overflow = 'hidden';

    if (ytApiReady) {
        if (ytPlayer) ytPlayer.destroy();
        ytPlayer = new YT.Player('youtubePlayer', {
            height: '360', width: '640', videoId: videoId,
            playerVars: { 'start': startTime, 'autoplay': 1, 'controls': 1, 'rel': 0 },
            events: {
                'onReady': () => {
                    document.getElementById('previewStatus').textContent = '재생 중';
                    document.getElementById('previewStatus').style.color = '#10b981';
                },
                'onError': (event) => {
                    let errorMsg = '알 수 없는 오류';
                    switch(event.data) {
                        case 2: errorMsg = '잘못된 Video ID'; break;
                        case 5: errorMsg = 'HTML5 플레이어 오류'; break;
                        case 100: errorMsg = '영상을 찾을 수 없음 (삭제됨)'; break;
                        case 101: case 150: errorMsg = '소유자가 임베드를 허용하지 않음'; break;
                    }
                    document.getElementById('previewStatus').textContent = '오류: ' + errorMsg;
                    document.getElementById('previewStatus').style.color = '#ef4444';
                },
                'onStateChange': (event) => {
                    const statusEl = document.getElementById('previewStatus');
                    if (event.data === YT.PlayerState.PLAYING) { statusEl.textContent = '재생 중'; statusEl.style.color = '#10b981'; }
                    else if (event.data === YT.PlayerState.PAUSED) { statusEl.textContent = '일시정지'; statusEl.style.color = '#f59e0b'; }
                    else if (event.data === YT.PlayerState.ENDED) { statusEl.textContent = '재생 완료'; statusEl.style.color = '#6b7280'; }
                }
            }
        });
    } else {
        document.getElementById('previewStatus').textContent = 'YouTube API 로딩 중... 잠시 후 다시 시도해주세요.';
        document.getElementById('previewStatus').style.color = '#f59e0b';
    }
}

function closeYoutubePreview() {
    if (ytPlayer) { ytPlayer.destroy(); ytPlayer = null; }
    document.getElementById('youtubePlayer').replaceChildren();
    document.getElementById('youtubePreviewModal').classList.remove('show');
    document.body.style.overflow = '';
}

// ========== Modal Functions ==========

function openModal(modalId) {
    const modal = document.getElementById(modalId);
    if (modal) {
        modal.classList.add('show');
        document.body.style.overflow = 'hidden';
    }
}

function closeModal(modalId) {
    // modalId가 없으면 노래 모달 닫기 (하위호환)
    if (!modalId) {
        closeSongModal();
        return;
    }
    const modal = document.getElementById(modalId);
    if (modal) {
        modal.classList.remove('show');
        document.body.style.overflow = '';
    }
}

function setupModalEvents() {
    // ESC 키로 모달 닫기
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            const youtubeModal = document.getElementById('youtubePreviewModal');
            if (youtubeModal && youtubeModal.classList.contains('show')) {
                closeYoutubePreview();
                return;
            }

            const openModals = document.querySelectorAll('.modal.show');
            openModals.forEach(modal => {
                if (modal.id === 'songModal') closeSongModal();
                else if (modal.id === 'genreModal') closeGenreModal();
                else closeModal(modal.id);
            });
        }
    });

    // 모달 바깥 클릭 시 닫기
    document.querySelectorAll('.modal').forEach(modal => {
        modal.addEventListener('click', function(e) {
            if (e.target === this) {
                if (this.id === 'youtubePreviewModal') closeYoutubePreview();
                else if (this.id === 'songModal') closeSongModal();
                else if (this.id === 'genreModal') closeGenreModal();
                else closeModal(this.id);
            }
        });
    });

    // 노래 폼 제출
    const songForm = document.getElementById('songForm');
    if (songForm) {
        songForm.addEventListener('submit', saveSong);
    }

    // 장르 폼 제출
    const genreForm = document.getElementById('genreForm');
    if (genreForm) {
        genreForm.addEventListener('submit', saveGenre);
    }
}

// ========== Answer Modal Functions ==========

function openAnswerModal(button) {
    var songId = button.dataset.songId;
    var songTitle = button.dataset.songTitle;
    var songArtist = button.dataset.songArtist;

    document.getElementById('currentSongId').value = songId;
    document.getElementById('modalSongTitle').textContent = songTitle;
    document.getElementById('modalSongArtist').textContent = songArtist;
    document.getElementById('answerModalTitle').textContent = songTitle + ' - 정답 관리';

    var answerList = document.getElementById('answerList');
    answerList.innerHTML = '<div class="loading-spinner"><div class="spinner"></div><span>로딩 중...</span></div>';

    var modal = document.getElementById('answerModal');
    modal.classList.add('show');
    document.body.style.overflow = 'hidden';

    fetch('/admin/answer/song/' + songId)
        .then(function(response) { return response.json(); })
        .then(function(result) {
            if (result.success) {
                renderAnswerList(result.answers);
            } else {
                answerList.innerHTML = '<div class="error-message">' + escapeHtml(result.message) + '</div>';
            }
        })
        .catch(function(error) {
            answerList.innerHTML = '<div class="error-message">정답 목록을 불러오는데 실패했습니다.</div>';
        });
}

function closeAnswerModal() {
    var modal = document.getElementById('answerModal');
    if (modal) {
        modal.classList.remove('show');
        document.body.style.overflow = '';
    }
    var newAnswerInput = document.getElementById('newAnswer');
    if (newAnswerInput) newAnswerInput.value = '';
    var primaryCheckbox = document.getElementById('newAnswerPrimary');
    if (primaryCheckbox) primaryCheckbox.checked = false;
}

function renderAnswerList(answers) {
    var answerList = document.getElementById('answerList');
    answerList.textContent = '';

    if (!answers || answers.length === 0) {
        answerList.innerHTML = '<div class="empty-message">등록된 정답이 없습니다.</div>';
        return;
    }

    answers.forEach(function(answer) {
        var item = document.createElement('div');
        item.className = 'answer-item';

        var answerText = document.createElement('span');
        answerText.className = 'answer-tag' + (answer.isPrimary ? ' primary' : '');
        answerText.textContent = answer.answer;

        var actions = document.createElement('span');
        actions.className = 'answer-actions';

        var editBtn = document.createElement('button');
        editBtn.className = 'btn btn-sm btn-secondary';
        editBtn.textContent = '수정';
        editBtn.onclick = function() { editAnswer(answer.id, answer.answer, answer.isPrimary); };

        var deleteBtn = document.createElement('button');
        deleteBtn.className = 'btn btn-sm btn-danger';
        deleteBtn.textContent = '삭제';
        deleteBtn.onclick = function() { deleteAnswer(answer.id); };

        actions.appendChild(editBtn);
        actions.appendChild(deleteBtn);

        item.appendChild(answerText);
        item.appendChild(actions);
        answerList.appendChild(item);
    });
}

function addAnswer() {
    var songId = document.getElementById('currentSongId').value;
    var answerText = document.getElementById('newAnswer').value.trim();
    var isPrimary = document.getElementById('newAnswerPrimary').checked;

    if (!answerText) {
        showToast('정답을 입력해주세요.', 'error');
        return;
    }

    fetch('/admin/answer/add', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ songId: songId, answer: answerText, isPrimary: isPrimary })
    })
    .then(function(response) { return response.json(); })
    .then(function(result) {
        if (result.success) {
            showToast(result.message, 'success');
            document.getElementById('newAnswer').value = '';
            document.getElementById('newAnswerPrimary').checked = false;
            refreshAnswerList(songId);
        } else {
            showToast(result.message, 'error');
        }
    })
    .catch(function(error) {
        showToast('정답 추가 중 오류가 발생했습니다.', 'error');
    });
}

function deleteAnswer(answerId) {
    if (!confirm('이 정답을 삭제하시겠습니까?')) return;

    fetch('/admin/answer/delete/' + answerId, { method: 'POST' })
        .then(function(response) { return response.json(); })
        .then(function(result) {
            if (result.success) {
                showToast(result.message, 'success');
                var songId = document.getElementById('currentSongId').value;
                refreshAnswerList(songId);
            } else {
                showToast(result.message, 'error');
            }
        })
        .catch(function(error) {
            showToast('정답 삭제 중 오류가 발생했습니다.', 'error');
        });
}

function editAnswer(answerId, answerText, isPrimary) {
    document.getElementById('editAnswerId').value = answerId;
    document.getElementById('editAnswerText').value = answerText;
    document.getElementById('editAnswerPrimary').checked = isPrimary;

    var modal = document.getElementById('editAnswerModal');
    modal.classList.add('show');
}

function closeEditModal() {
    var modal = document.getElementById('editAnswerModal');
    if (modal) {
        modal.classList.remove('show');
    }
}

function saveEditAnswer() {
    var answerId = document.getElementById('editAnswerId').value;
    var answerText = document.getElementById('editAnswerText').value.trim();
    var isPrimary = document.getElementById('editAnswerPrimary').checked;

    if (!answerText) {
        showToast('정답을 입력해주세요.', 'error');
        return;
    }

    fetch('/admin/answer/update/' + answerId, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ answer: answerText, isPrimary: isPrimary })
    })
    .then(function(response) { return response.json(); })
    .then(function(result) {
        if (result.success) {
            showToast(result.message, 'success');
            closeEditModal();
            var songId = document.getElementById('currentSongId').value;
            refreshAnswerList(songId);
        } else {
            showToast(result.message, 'error');
        }
    })
    .catch(function(error) {
        showToast('정답 수정 중 오류가 발생했습니다.', 'error');
    });
}

function autoGenerateAnswers() {
    var songId = document.getElementById('currentSongId').value;

    fetch('/admin/answer/auto-generate/' + songId, { method: 'POST' })
        .then(function(response) { return response.json(); })
        .then(function(result) {
            if (result.success) {
                showToast(result.message, 'success');
                refreshAnswerList(songId);
            } else {
                showToast(result.message, 'error');
            }
        })
        .catch(function(error) {
            showToast('자동 생성 중 오류가 발생했습니다.', 'error');
        });
}

function refreshAnswerList(songId) {
    fetch('/admin/answer/song/' + songId)
        .then(function(response) { return response.json(); })
        .then(function(result) {
            if (result.success) {
                renderAnswerList(result.answers);
            }
        })
        .catch(function(error) {
            console.error('Error refreshing answer list:', error);
        });
}

// ========== Helper Functions ==========

// openModal 하위호환 - 노래 모달 열기
function openSongModalAlias() {
    openSongModal();
}

// 전역으로 노출 (하위호환)
window.openModal = function(modalIdOrNone) {
    if (!modalIdOrNone || modalIdOrNone === 'songModal') {
        openSongModal();
    } else {
        const modal = document.getElementById(modalIdOrNone);
        if (modal) {
            modal.classList.add('show');
            document.body.style.overflow = 'hidden';
        }
    }
};
