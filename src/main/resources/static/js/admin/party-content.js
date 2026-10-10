/**
 * 파티 문제 관리 — /admin/party/content
 * TSV 올리기(POST /admin/party/items/import) · 대분류별 현황(/items/summary) · 목록(/items/list) · 숨기기(/items/{id}/use).
 * 공통 fetch 래퍼가 CSRF 헤더를 붙이고 {success:false} 오류를 200 으로 바꾸므로 본문으로 판단한다.
 * 문제 내용은 전부 textContent.
 */
(function () {
    'use strict';

    const BASE = '/admin/party/items/';
    const CATEGORY_LABEL = {
        SCREEN: '드라마·영화', ANIME: '애니', GAME: '게임', PERSON: '인물',
        QUIZ: '초성·이모지', SOUND: '시그널·CM', SPEED: '스피드퀴즈 제시어'
    };
    const STATUS_LABEL = { PLAYABLE: '낼 수 있음', INCOMPLETE: '미완성', OFF: '숨김' };

    let category = null;
    let filter = 'ALL';
    let rows = [];
    let busy = false;

    const $ = (id) => document.getElementById(id);

    async function request(method, path, body) {
        const init = { method, headers: { Accept: 'application/json' } };
        if (body instanceof FormData) {
            init.body = body;
        } else if (body) {
            init.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
            init.body = new URLSearchParams(body);
        }
        let response;
        try {
            response = await fetch(path, init);
        } catch (e) {
            showNotice('서버 응답 없음 — 앱이 켜져 있는지 확인하세요');
            return null;
        }
        const type = response.headers.get('content-type') || '';
        if (response.redirected || !type.includes('application/json')) {
            showLoggedOut();
            return null;
        }
        return response.json();
    }

    // ---------- 올리기 ----------

    async function upload() {
        const file = $('pcFile').files[0];
        if (!file || busy) return;
        busy = true;
        $('pcUpload').disabled = true;
        hideNotice();
        const form = new FormData();
        form.append('file', file);
        try {
            const data = await request('POST', BASE + 'import', form);
            if (!data) return;
            if (data.success === false) {
                showNotice(data.message || '올리지 못했습니다');
                return;
            }
            renderUploadResult(file.name, data);
            await loadSummary();
            if (category) await loadList();
        } finally {
            busy = false;
            $('pcUpload').disabled = !$('pcFile').files[0];
        }
    }

    function renderUploadResult(fileName, data) {
        const result = $('pcUploadResult');
        result.textContent = fileName + ' — 새로 ' + data.created + ' · 덮어씀 ' + data.updated
            + ' · 거부 ' + data.errors.length;
        result.classList.toggle('free', data.errors.length > 0);
        result.hidden = false;

        const list = $('pcUploadErrors');
        list.replaceChildren();
        data.errors.forEach((error) => {
            list.append(el('li', '', error.line + '번째 줄: ' + error.message));
        });
        list.hidden = data.errors.length === 0;
    }

    // ---------- 현황 ----------

    async function loadSummary() {
        const data = await request('GET', BASE + 'summary');
        if (!Array.isArray(data)) return;
        const body = $('pcSummary').querySelector('tbody');
        body.replaceChildren();
        data.forEach((row) => {
            const tr = el('tr', 'pc-click' + (row.category === category ? ' on' : ''));
            tr.append(el('td', '', (CATEGORY_LABEL[row.category] || row.category) + ' (' + row.category + ')'),
                el('td', '', String(row.total)), el('td', '', String(row.playable)),
                el('td', row.incomplete > 0 ? 'pc-warn-text' : '', String(row.incomplete)),
                el('td', '', String(row.off)));
            tr.addEventListener('click', () => {
                category = row.category;
                filter = 'ALL';
                loadSummary();
                loadList();
            });
            body.append(tr);
        });
    }

    // ---------- 목록 ----------

    async function loadList() {
        const data = await request('GET', BASE + 'list?category=' + encodeURIComponent(category));
        if (!Array.isArray(data)) {
            if (data && data.success === false) showNotice(data.message);
            return;
        }
        rows = data;
        renderList();
    }

    function renderList() {
        $('pcListCard').hidden = false;
        $('pcListTitle').textContent = (CATEGORY_LABEL[category] || category) + ' 목록 (' + rows.length + ')';
        document.querySelectorAll('#pcFilters [data-filter]').forEach((chip) => {
            chip.classList.toggle('on', chip.dataset.filter === filter);
        });

        const body = $('pcList').querySelector('tbody');
        body.replaceChildren();
        const shown = rows.filter((row) => filter === 'ALL' || row.status === filter);
        if (shown.length === 0) {
            const tr = el('tr');
            const td = el('td', 'pc-muted', '해당하는 문제가 없습니다');
            td.colSpan = 5;
            tr.append(td);
            body.append(tr);
        }
        shown.forEach((row) => body.append(renderRow(row)));
    }

    function renderRow(row) {
        const tr = el('tr', row.status === 'OFF' ? 'pc-off' : '');
        const status = el('td');
        status.append(el('span', 'pc-chip st-' + row.status.toLowerCase(), STATUS_LABEL[row.status] || row.status));
        if (row.reason && row.status !== 'OFF') status.append(el('div', 'pc-muted', row.reason));

        const action = el('td');
        const button = el('button', 'pc-btn sm' + (row.status === 'OFF' ? '' : ' danger'),
            row.status === 'OFF' ? '다시 켜기' : '숨기기');
        button.type = 'button';
        button.addEventListener('click', () => setUse(row, row.status === 'OFF' ? 'Y' : 'N', button));
        action.append(button);

        tr.append(el('td', '', row.subCategory || '—'), el('td', 'pc-answer-cell', row.answer),
            el('td', '', row.presentation), status, action);
        return tr;
    }

    async function setUse(row, useYn, button) {
        if (busy) return;
        busy = true;
        button.disabled = true;
        try {
            const data = await request('POST', BASE + row.id + '/use', { useYn });
            if (!data) return;
            if (data.success === false) {
                showNotice(data.message || '바꾸지 못했습니다');
                return;
            }
            hideNotice();
            rows = rows.map((r) => (r.id === row.id ? data.item : r));
            renderList();
            loadSummary();
        } finally {
            busy = false;
            button.disabled = false;
        }
    }

    // ---------- 도우미 ----------

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function showNotice(message) {
        $('pcNotice').textContent = message;
        $('pcNotice').hidden = false;
    }

    function hideNotice() {
        $('pcNotice').hidden = true;
    }

    function showLoggedOut() {
        const banner = $('pcBanner');
        banner.replaceChildren(el('span', '', '로그아웃됨 (세션 만료 또는 앱 재시작) — '));
        const link = el('a', '', '다시 로그인');
        link.href = '/auth/login';
        banner.append(link);
        banner.hidden = false;
    }

    $('pcFile').addEventListener('change', () => {
        $('pcUpload').disabled = !$('pcFile').files[0];
    });
    $('pcUpload').addEventListener('click', upload);
    document.querySelectorAll('#pcFilters [data-filter]').forEach((chip) => {
        chip.addEventListener('click', () => {
            filter = chip.dataset.filter;
            renderList();
        });
    });

    loadSummary();
})();
