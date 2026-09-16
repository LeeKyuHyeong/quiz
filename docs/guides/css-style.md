# CSS Style Guide — 전문

> 2026-09-16 `CLAUDE.md`에서 분리. 규칙표·변수표·z-index·브레이크포인트는 CLAUDE.md, 필수 템플릿과 예시 코드는 이 문서.

**⚠️ CRITICAL: 색상 하드코딩 금지!**

### 필수 규칙
- **색상값을 직접 쓰지 말고 반드시 CSS 변수 사용** (`#1e293b` ❌ → `var(--text-primary)` ✅)
- 새로운 색상이 필요하면 `common.css`의 `:root`에 변수 추가 후 사용
- **⚠️ 라이트/다크 모드 1:1 매칭 필수** - 모든 스타일은 라이트와 다크 모드 양쪽에 정의해야 함
- **⚠️ 반응형 3단계 필수** - 모든 레이아웃/크기 관련 스타일은 PC/태블릿/모바일 3단계로 정의해야 함

### 테마 시스템 구조
```
common.css
├── :root { }                    → 라이트 모드 기본값
├── [data-theme="dark"] { }      → 다크 모드 오버라이드
└── .game-page { }               → 게임 페이지 전용 (항상 다크)
```

### 주요 CSS 변수
| 용도 | 변수명 |
|------|--------|
| 기본 텍스트 | `--text-primary` |
| 보조 텍스트 | `--text-secondary` |
| 흐린 텍스트 | `--text-muted` |
| 기본 배경 | `--bg-base` |
| 카드 배경 | `--bg-surface` |
| 강조 배경 | `--bg-elevated` |
| 테두리 | `--border-color` |

### 주의사항: `.game-page` 클래스
- `.game-page`는 CSS 변수를 다크 모드로 강제 오버라이드함
- **흰색 배경 요소**에서 `var(--text-primary)`를 쓰면 흰 글씨가 됨!
- 해결법: 해당 CSS 파일의 다크 테마 섹션(`[data-theme="dark"]`)에서 별도 처리

### 예시: 흰색 배경 모달 처리
```css
/* 기본 스타일 */
.modal-content {
    background: white;
    color: var(--text-primary);  /* 라이트 모드에서 정상 작동 */
}

/* 다크 모드 또는 .game-page에서 오버라이드 */
[data-theme="dark"] .modal-content,
.game-page .modal-content {
    background: var(--bg-surface);  /* 다크 배경으로 변경 */
    color: var(--text-primary);     /* 이제 흰 글씨가 맞음 */
}
```

### ⚠️ CSS 작성 필수 템플릿

새로운 컴포넌트 CSS 작성 시 반드시 아래 구조를 따라야 함:

```css
/* ========================================
   [컴포넌트명] - 기본 스타일 (라이트 모드 + PC)
   ======================================== */
.component {
    background: var(--bg-surface);
    color: var(--text-primary);
    padding: 2rem;
    font-size: 1rem;
}

/* ========================================
   [컴포넌트명] - 다크 모드
   ======================================== */
[data-theme="dark"] .component {
    background: var(--bg-elevated);
    border-color: var(--border-color);
}

/* .game-page는 항상 다크 모드이므로 함께 처리 */
.game-page .component {
    background: var(--bg-elevated);
    border-color: var(--border-color);
}

/* ========================================
   [컴포넌트명] - 태블릿 (768px 이하)
   ======================================== */
@media (max-width: 768px) {
    .component {
        padding: 1.5rem;
        font-size: 0.95rem;
    }
}

/* ========================================
   [컴포넌트명] - 모바일 (480px 이하)
   ======================================== */
@media (max-width: 480px) {
    .component {
        padding: 1rem;
        font-size: 0.9rem;
    }
}
```

### 체크리스트: CSS 작성 완료 전 확인

| 항목 | 확인 |
|------|------|
| `:root` (라이트 모드) 스타일 정의 | ☐ |
| `[data-theme="dark"]` 스타일 정의 | ☐ |
| `.game-page` 스타일 정의 (필요시) | ☐ |
| `@media (max-width: 768px)` 태블릿 스타일 | ☐ |
| `@media (max-width: 480px)` 모바일 스타일 | ☐ |
| 색상값 하드코딩 없음 | ☐ |
| CSS 변수만 사용 | ☐ |

### 반응형 브레이크포인트

| 구분 | 브레이크포인트 | 용도 |
|------|---------------|------|
| **모바일** | `max-width: 480px` | 스마트폰 세로 |
| **태블릿** | `max-width: 768px` | 태블릿/스마트폰 가로 |
| **데스크탑** | `min-width: 769px` | PC (기본) |
| **대형** | `min-width: 1200px` | 대형 모니터 (선택적) |

```css
/* 기본: 데스크탑 스타일 */
.container { padding: 2rem; }

/* 태블릿 이하 */
@media (max-width: 768px) {
    .container { padding: 1.5rem; }
}

/* 모바일 */
@media (max-width: 480px) {
    .container { padding: 1rem; }
}
```

**⚠️ 금지:** 임의의 브레이크포인트 사용 (450px, 375px 등)

**예외:** `game-multi.css`는 채팅+스코어보드 레이아웃 특성상 `900px` 브레이크포인트 허용

### Z-Index 계층

| 계층 | 값 | 용도 |
|------|-----|------|
| 기본 | `1-10` | 로컬 스태킹 (카드 내 요소) |
| 고정 | `100` | 사이드바, 네비게이션 |
| 드롭다운 | `500` | 드롭다운, 팝오버 |
| 모달 배경 | `900` | 모달 오버레이 |
| 모달 | `1000` | 모달 콘텐츠 |
| 토스트 | `5000` | 알림 토스트 |
| 최상위 | `10000` | 뱃지 토스트 (특수) |

**⚠️ 금지:** 임의의 z-index 값 사용 (9999, 99999 등)

### 단위 & 값 규칙

- **Width/Height:** 반응형 단위 우선 사용
  - 우선순위: `%` → `vw/vh` → `rem` → `px` (최후의 수단)
  - 컨테이너 기준: `%` 사용 (예: `width: 100%`)
  - 뷰포트 기준: `vw/vh` 사용 (예: `max-height: 80vh`)
  - 고정 크기 필요 시: `rem` 사용 (예: `min-width: 20rem`)
- **길이:** `rem` 사용 (px 금지, 예외: `1px` 보더)
- **Border-radius:** `rem` 단위만 사용
  - 작음: `0.25rem` / 중간: `0.5rem` / 큼: `0.75rem` / 매우 큼: `1rem` / 원형: `50%`
- **Spacing:** `0.25rem` 단위로 증가 (0.5rem, 0.75rem, 1rem, 1.5rem, 2rem)
- **Transition:** `0.2s` (빠름) / `0.3s` (기본) / `0.5s` (느림)

```css
/* ❌ 잘못된 예 */
width: 350px;            /* 고정 px */
height: 500px;           /* 고정 px */
border-radius: 6px;      /* px 사용 */
padding: 13px;           /* px 사용 */

/* ✅ 올바른 예 */
width: 100%;             /* 부모 기준 반응형 */
max-width: 24rem;        /* 최대값 제한 */
height: 80vh;            /* 뷰포트 기준 */
border-radius: 0.5rem;   /* 표준 값 */
padding: 0.75rem;        /* rem 사용 */
```

### RGBA 투명도 처리

투명도가 필요한 색상도 변수 사용 권장. `common.css`에 정의된 `--overlay-*` 변수 활용:

```css
/* ❌ 하드코딩 */
background: rgba(0, 0, 0, 0.5);
color: rgba(255, 255, 255, 0.7);

/* ✅ 변수 사용 */
background: var(--overlay-medium);
color: var(--text-secondary);
```
