/**
 * client/auth/login.html - 로그인
 * Phase 2: Spring Security formLogin 연동
 */

// 서버 SecurityInputValidator.EMAIL_PATTERN과 동일 (^[A-Za-z0-9+_.-]+@(.+)$)
const EMAIL_REGEX = /^[A-Za-z0-9+_.-]+@(.+)$/;
const EMAIL_MAX_LENGTH = 320;

function validateEmailClient(email) {
    if (!email || email.trim().length === 0) {
        return '이메일을 입력해주세요.';
    }
    if (email.length > EMAIL_MAX_LENGTH) {
        return '이메일이 너무 깁니다.';
    }
    if (!EMAIL_REGEX.test(email)) {
        return '올바른 이메일 형식이 아닙니다.';
    }
    return null;
}

// 이메일 입력 시 실시간 형식 피드백
const emailInputEl = document.getElementById('email');
const errorMessageEl = document.getElementById('errorMessage');
emailInputEl.addEventListener('blur', function () {
    const email = this.value.trim();
    if (!email) return;
    const err = validateEmailClient(email);
    if (err) {
        errorMessageEl.textContent = err;
        errorMessageEl.style.display = 'block';
    } else {
        errorMessageEl.style.display = 'none';
    }
});

document.getElementById('loginForm').addEventListener('submit', async function(e) {
    e.preventDefault();
    await attemptLogin(false);
});

async function attemptLogin(forceLogin) {
    const email = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;
    const errorMessage = document.getElementById('errorMessage');

    // 이메일 형식 사전 검증 (불필요한 서버 호출 차단)
    const emailErr = validateEmailClient(email);
    if (emailErr) {
        errorMessage.textContent = emailErr;
        errorMessage.style.display = 'block';
        return;
    }
    if (!password) {
        errorMessage.textContent = '비밀번호를 입력해주세요.';
        errorMessage.style.display = 'block';
        return;
    }

    try {
        // Step 1: 중복 로그인 사전 체크 (forceLogin이 아닌 경우)
        if (!forceLogin) {
            const checkResponse = await fetch('/auth/check-login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email })
            });
            const checkResult = await checkResponse.json();

            if (!checkResult.canProceed) {
                const confirmMsg = checkResult.inGame
                    ? '현재 다른 기기에서 게임이 진행 중입니다.\n강제 로그인 시 해당 게임이 중단됩니다.\n\n강제 로그인하시겠습니까?'
                    : '다른 기기에서 이미 로그인 중입니다.\n강제 로그인하시겠습니까?';

                if (confirm(confirmMsg)) {
                    await attemptLogin(true);
                }
                return;
            }
        }

        // Step 2: Spring Security formLogin으로 인증
        const formData = new URLSearchParams();
        formData.append('email', email);
        formData.append('password', password);

        // CSRF 토큰 (Phase 4)
        const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
        if (csrfToken && csrfHeader) {
            headers[csrfHeader] = csrfToken;
        }

        const response = await fetch('/auth/login-process', {
            method: 'POST',
            headers: headers,
            body: formData.toString()
        });

        const result = await response.json();

        if (result.success) {
            const redirect = document.getElementById('redirectUrl').value;
            window.location.href = redirect || '/';
        } else {
            errorMessage.textContent = result.message;
            errorMessage.style.display = 'block';
        }
    } catch (error) {
        errorMessage.textContent = '로그인 처리 중 오류가 발생했습니다.';
        errorMessage.style.display = 'block';
    }
}
