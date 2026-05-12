/**
 * client/auth/register.html - 회원가입 (이메일 인증 포함)
 *
 * 흐름:
 * 1. 이메일 입력 → [인증코드 받기] 클릭 → /auth/send-verification
 * 2. 메일로 받은 6자리 코드 입력 → [인증 확인] 클릭 → /auth/verify-code
 * 3. 인증 완료 시에만 [회원가입] 버튼이 동작
 */

// 서버 SecurityInputValidator.EMAIL_PATTERN과 동일
const EMAIL_REGEX = /^[A-Za-z0-9+_.-]+@(.+)$/;
const EMAIL_MAX_LENGTH = 320;

function validateEmailClient(email) {
    if (!email || email.trim().length === 0) return '이메일을 입력해주세요.';
    if (email.length > EMAIL_MAX_LENGTH) return '이메일이 너무 깁니다.';
    if (!EMAIL_REGEX.test(email)) return '올바른 이메일 형식이 아닙니다.';
    return null;
}

let emailVerified = false;
let emailAvailable = false;

const $ = (id) => document.getElementById(id);

const emailEl = $('email');
const sendCodeBtn = $('sendCodeBtn');
const codeGroup = $('codeGroup');
const verifyCodeEl = $('verifyCode');
const verifyCodeBtn = $('verifyCodeBtn');
const emailHint = $('emailHint');
const verifyHint = $('verifyHint');
const errorMessage = $('errorMessage');
const successMessage = $('successMessage');

function setHint(el, text, type) {
    el.textContent = text;
    el.className = 'field-hint' + (type ? ' ' + type : '');
}

function lockEmailField(lock) {
    emailEl.readOnly = lock;
    sendCodeBtn.disabled = lock;
}

// 이메일 변경 시 인증 상태 리셋
emailEl.addEventListener('input', () => {
    if (emailVerified) {
        emailVerified = false;
        codeGroup.classList.add('hidden');
        setHint(emailHint, '', '');
        setHint(verifyHint, '', '');
        lockEmailField(false);
    }
});

// 이메일 중복 체크 (blur 시)
emailEl.addEventListener('blur', async function () {
    const email = this.value.trim();
    if (!email) {
        setHint(emailHint, '', '');
        emailAvailable = false;
        return;
    }
    const formatErr = validateEmailClient(email);
    if (formatErr) {
        setHint(emailHint, formatErr, 'error');
        emailAvailable = false;
        return;
    }

    try {
        const response = await fetch(`/auth/check-email?email=${encodeURIComponent(email)}`);
        const result = await response.json();
        if (result.available) {
            setHint(emailHint, '사용 가능한 이메일입니다. 인증을 진행해주세요.', 'success');
            emailAvailable = true;
        } else {
            setHint(emailHint, '이미 사용 중인 이메일입니다.', 'error');
            emailAvailable = false;
        }
    } catch (e) {
        // ignore network error here
    }
});

// 인증 코드 발송
sendCodeBtn.addEventListener('click', async () => {
    const email = emailEl.value.trim();
    const formatErr = validateEmailClient(email);
    if (formatErr) {
        setHint(emailHint, formatErr, 'error');
        return;
    }
    if (!emailAvailable) {
        setHint(emailHint, '사용 가능한 이메일을 먼저 확인해주세요.', 'error');
        return;
    }

    sendCodeBtn.disabled = true;
    setHint(emailHint, '인증 코드 발송 중...', '');

    try {
        const response = await fetch('/auth/send-verification', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email })
        });
        const result = await response.json();

        if (response.ok && result.success) {
            setHint(emailHint, '메일을 확인하고 6자리 코드를 입력해주세요. (5분 내 유효)', 'success');
            codeGroup.classList.remove('hidden');
            verifyCodeEl.focus();
            startResendCooldown(30);
        } else {
            setHint(emailHint, result.message || '코드 발송에 실패했습니다.', 'error');
            sendCodeBtn.disabled = false;
        }
    } catch (e) {
        setHint(emailHint, '서버 오류로 코드 발송에 실패했습니다.', 'error');
        sendCodeBtn.disabled = false;
    }
});

// 재발송 쿨다운 (스팸 방지 UX)
function startResendCooldown(seconds) {
    const originalText = '인증코드 받기';
    let remaining = seconds;
    sendCodeBtn.disabled = true;
    sendCodeBtn.textContent = `재발송 (${remaining}s)`;
    const timer = setInterval(() => {
        remaining -= 1;
        if (remaining <= 0) {
            clearInterval(timer);
            sendCodeBtn.disabled = false;
            sendCodeBtn.textContent = originalText;
        } else {
            sendCodeBtn.textContent = `재발송 (${remaining}s)`;
        }
    }, 1000);
}

// 코드 검증
verifyCodeBtn.addEventListener('click', async () => {
    const email = emailEl.value.trim();
    const code = verifyCodeEl.value.trim();

    if (!code.match(/^\d{6}$/)) {
        setHint(verifyHint, '6자리 숫자 코드를 입력해주세요.', 'error');
        return;
    }

    verifyCodeBtn.disabled = true;
    setHint(verifyHint, '확인 중...', '');

    try {
        const response = await fetch('/auth/verify-code', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, code })
        });
        const result = await response.json();

        if (response.ok && result.success) {
            emailVerified = true;
            setHint(verifyHint, '✓ 이메일 인증 완료', 'success');
            verifyCodeEl.readOnly = true;
            verifyCodeBtn.disabled = true;
            lockEmailField(true);
        } else {
            setHint(verifyHint, result.message || '인증에 실패했습니다.', 'error');
            verifyCodeBtn.disabled = false;
        }
    } catch (e) {
        setHint(verifyHint, '서버 오류로 인증에 실패했습니다.', 'error');
        verifyCodeBtn.disabled = false;
    }
});

// 비밀번호 확인 (기존 기능)
$('passwordConfirm').addEventListener('input', function () {
    const password = $('password').value;
    const passwordHint = $('passwordHint');
    if (this.value && this.value !== password) {
        passwordHint.textContent = '비밀번호가 일치하지 않습니다.';
        passwordHint.className = 'field-hint error';
    } else if (this.value && this.value === password) {
        passwordHint.textContent = '비밀번호가 일치합니다.';
        passwordHint.className = 'field-hint success';
    } else {
        passwordHint.textContent = '';
    }
});

// 회원가입 폼 제출
$('registerForm').addEventListener('submit', async function (e) {
    e.preventDefault();

    const email = emailEl.value.trim();
    const password = $('password').value;
    const passwordConfirm = $('passwordConfirm').value;
    const nickname = $('nickname').value;
    const username = $('username').value;

    errorMessage.style.display = 'none';
    successMessage.style.display = 'none';

    // 이메일 형식 최종 검증 (인증 후 사용자가 임의로 값을 바꾼 경우 대비)
    const emailFormatErr = validateEmailClient(email);
    if (emailFormatErr) {
        errorMessage.textContent = emailFormatErr;
        errorMessage.style.display = 'block';
        return;
    }

    if (!emailVerified) {
        errorMessage.textContent = '이메일 인증을 먼저 완료해주세요.';
        errorMessage.style.display = 'block';
        return;
    }

    if (!username || username.trim().length === 0 || username.length > 50) {
        errorMessage.textContent = '성명을 1~50자 이내로 입력해주세요.';
        errorMessage.style.display = 'block';
        return;
    }

    if (password !== passwordConfirm) {
        errorMessage.textContent = '비밀번호가 일치하지 않습니다.';
        errorMessage.style.display = 'block';
        return;
    }

    try {
        const response = await fetch('/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password, nickname, username })
        });
        const result = await response.json();

        if (result.success) {
            successMessage.textContent = '회원가입이 완료되었습니다. 로그인 페이지로 이동합니다.';
            successMessage.style.display = 'block';
            setTimeout(() => { window.location.href = '/auth/login'; }, 1500);
        } else {
            errorMessage.textContent = result.message;
            errorMessage.style.display = 'block';
        }
    } catch (e) {
        errorMessage.textContent = '회원가입 처리 중 오류가 발생했습니다.';
        errorMessage.style.display = 'block';
    }
});
