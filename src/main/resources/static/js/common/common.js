// ========== 조건부 디버그 로깅 ==========
const Debug = {
    // 개발 환경 여부 (localhost 또는 127.0.0.1)
    isDev: window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1',

    // 일반 로그 (개발 환경에서만 출력)
    log(...args) {
        if (this.isDev) console.log(...args);
    },

    // 경고 로그 (개발 환경에서만 출력)
    warn(...args) {
        if (this.isDev) console.warn(...args);
    },

    // 에러 로그 (항상 출력 - 운영 환경에서도 에러는 추적 필요)
    error(...args) {
        console.error(...args);
    },

    // 테이블 형태 로그 (개발 환경에서만)
    table(data) {
        if (this.isDev) console.table(data);
    }
};

// 토스트 알림 표시 (전역 함수)
function showToast(message, type = 'info') {
    // 기존 토스트 제거
    const existingToast = document.querySelector('.toast-notification');
    if (existingToast) {
        existingToast.remove();
    }

    const toast = document.createElement('div');
    toast.className = `toast-notification toast-${type}`;
    toast.textContent = message;
    document.body.appendChild(toast);

    // 애니메이션을 위해 약간의 딜레이 후 show 클래스 추가
    requestAnimationFrame(() => {
        toast.classList.add('show');
    });

    // 3초 후 자동 제거
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// HTML 이스케이프 (XSS 방지)
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Common utilities
const Utils = {
    formatDate(dateStr) {
        if (!dateStr) return '-';
        const date = new Date(dateStr);
        return date.toLocaleDateString('ko-KR');
    },
    
    formatDateTime(dateStr) {
        if (!dateStr) return '-';
        const date = new Date(dateStr);
        return date.toLocaleString('ko-KR');
    },
    
    formatNumber(num) {
        if (num === null || num === undefined) return '0';
        return num.toLocaleString('ko-KR');
    },
    
    debounce(func, wait) {
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
};

// Global error handler (운영 환경에서는 로깅 서비스로 전송 권장)
window.addEventListener('unhandledrejection', function(event) {
    // 운영 환경에서는 console.error 대신 로깅 서비스 사용 권장
    if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
        console.error('Unhandled promise rejection:', event.reason);
    }
});

// ========== 로그인 세션 상태 감시 (다른 기기 로그인·시간 초과 감지) ==========
// /auth/validate-session 은 세션을 연장하지 않는다 (서버 SessionCheckFilter). 연장은 실제 사용자 요청만 한다.
const SessionManager = {
    checkInterval: null,
    isChecking: false,
    wasLoggedIn: false,     // 이 페이지에서 로그인 상태가 한 번이라도 확인됐는가

    // 세션 체크 시작 (30초 간격)
    startSessionCheck() {
        // 인증 페이지에서는 체크하지 않음
        if (window.location.pathname.startsWith('/auth/')) {
            return;
        }

        // 이미 체크 중이면 중복 실행 방지
        if (this.checkInterval) {
            return;
        }

        // 30초마다 세션 유효성 체크
        this.checkInterval = setInterval(() => this.validateSession(), 30000);

        // 페이지 로드 시 즉시 1회 체크
        setTimeout(() => this.validateSession(), 1000);
    },

    // 세션 체크 중지
    stopSessionCheck() {
        if (this.checkInterval) {
            clearInterval(this.checkInterval);
            this.checkInterval = null;
        }
    },

    // 세션 유효성 검증
    async validateSession() {
        if (this.isChecking) return;
        this.isChecking = true;

        try {
            const response = await fetch('/auth/validate-session', {
                headers: { 'X-Requested-With': 'XMLHttpRequest' }
            });

            const result = await response.json();

            if (result.valid) {
                this.wasLoggedIn = true;
            } else if (result.reason === 'SESSION_INVALIDATED') {
                this.handleSessionInvalidated(result.message);
            } else if (this.wasLoggedIn) {
                this.handleSessionInvalidated('오랫동안 이용하지 않아 로그아웃되었습니다.');
            } else {
                // 로그인하지 않은 방문자 — 감시할 세션이 없다
                this.stopSessionCheck();
            }
        } catch (error) {
            // 네트워크 오류는 무시 (오프라인 등)
            // 운영 환경에서는 로깅 서비스 사용 권장
        } finally {
            this.isChecking = false;
        }
    },

    // 세션 종료 처리 (다른 기기 로그인, 관리자 조치, 시간 초과)
    handleSessionInvalidated(message) {
        if (!this.checkInterval && !this.wasLoggedIn) {
            return;  // 이미 처리했다 (주기 확인과 fetch 401 이 겹칠 수 있다)
        }
        this.stopSessionCheck();
        this.wasLoggedIn = false;

        // 토스트 알림 표시
        const msg = message || '다른 기기에서 로그인하여 현재 세션이 종료되었습니다.';
        showToast(msg, 'warning');

        // 잠시 후 로그인 페이지로 이동 (토스트 확인 시간)
        setTimeout(() => {
            window.location.href = '/auth/login?expired=true';
        }, 1500);
    }
};

// 전역 fetch 래퍼 - CSRF 토큰 자동 첨부 + 세션 무효화 감지
const originalFetch = window.fetch;
window.fetch = async function(input, init = {}) {
    // CSRF 토큰 자동 첨부 (상태 변경 요청에만)
    const method = (init.method || 'GET').toUpperCase();
    if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
        const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

        if (csrfToken && csrfHeader) {
            init.headers = init.headers || {};
            // Headers 객체인 경우와 plain object인 경우 모두 처리
            if (init.headers instanceof Headers) {
                if (!init.headers.has(csrfHeader)) {
                    init.headers.set(csrfHeader, csrfToken);
                }
            } else {
                if (!init.headers[csrfHeader]) {
                    init.headers[csrfHeader] = csrfToken;
                }
            }
        }
    }

    const response = await originalFetch.call(this, input, init);

    // 401 응답에서 세션 무효화 감지
    if (response.status === 401) {
        try {
            const clonedResponse = response.clone();
            const result = await clonedResponse.json();

            if (result.error === 'SESSION_INVALIDATED') {
                SessionManager.handleSessionInvalidated(result.message);
            }
        } catch (e) {
            // JSON 파싱 실패 시 무시
        }
    }

    // 4xx/5xx JSON 에러 응답을 200으로 변환 (기존 코드 호환성 유지)
    // GlobalExceptionHandler가 {success: false, message: "..."} JSON을 반환하므로
    // 기존 JS 코드가 response.json() → data.success 패턴으로 처리 가능하도록 함
    if (!response.ok && response.status !== 401) {
        const contentType = response.headers.get('content-type');
        if (contentType && contentType.includes('application/json')) {
            try {
                const body = await response.clone().json();
                if (body.success !== undefined) {
                    // {success, message} 형식의 JSON이면 200 Response로 래핑
                    return new Response(JSON.stringify(body), {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' }
                    });
                }
            } catch (e) {
                // JSON 파싱 실패 시 원본 응답 반환
            }
        }
    }

    return response;
};

// 페이지 로드 시 세션 체크 시작
document.addEventListener('DOMContentLoaded', function() {
    SessionManager.startSessionCheck();
});