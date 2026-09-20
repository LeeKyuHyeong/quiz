/**
 * client/auth/password-reset.html - 비밀번호 재설정 (로그인 불필요, 이메일 인증)
 *
 * 흐름:
 * 1. 이메일 입력 → [인증코드 받기] → /auth/password-reset/send-code (가입된 이메일만)
 * 2. 6자리 코드 입력 → [인증 확인] → /auth/verify-code
 * 3. 새 비밀번호 입력 → [비밀번호 변경] → /auth/password-reset → 로그인 페이지로 이동
 *
 * 비밀번호는 단방향 해시라 "찾기"는 불가능하고 재설정만 가능하다.
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

const $ = (id) => document.getElementById(id);

const emailEl = $('email');
const sendCodeBtn = $('sendCodeBtn');
const codeGroup = $('codeGroup');
const verifyCodeEl = $('verifyCode');
const verifyCodeBtn = $('verifyCodeBtn');
const passwordGroup = $('passwordGroup');
const passwordConfirmGroup = $('passwordConfirmGroup');
const submitBtn = $('submitBtn');
const emailHint = $('emailHint');
const verifyHint = $('verifyHint');
const errorMessage = $('errorMessage');
const successMessage = $('successMessage');
const emailLockedByServer = emailEl.readOnly;

function setHint(el, text, type) {
    el.textContent = text;
    el.className = 'field-hint' + (type ? ' ' + type : '');
}

function showMessage(el, text) {
    el.textContent = text;
    el.style.display = 'block';
}

function lockEmailField(lock) {
    if (!emailLockedByServer) emailEl.readOnly = lock;
    sendCodeBtn.disabled = lock;
}

// 이메일 변경 시 인증 상태 리셋
emailEl.addEventListener('input', () => {
    if (emailVerified) {
        emailVerified = false;
        codeGroup.classList.add('hidden');
        passwordGroup.classList.add('hidden');
        passwordConfirmGroup.classList.add('hidden');
        submitBtn.disabled = true;
        setHint(emailHint, '', '');
        setHint(verifyHint, '', '');
        lockEmailField(false);
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

    sendCodeBtn.disabled = true;
    setHint(emailHint, '인증 코드 발송 중...', '');

    try {
        const response = await fetch('/auth/password-reset/send-code', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify({ email })
        });
        const result = await response.json();

        if (response.ok && result.success) {
            setHint(emailHint, '메일을 확인하고 6자리 코드를 입력해주세요. (5분 내 유효)', 'success');
            codeGroup.classList.remove('hidden');
            verifyCodeEl.focus();
            // 서버의 재발송 간격(EmailVerificationService.RESEND_COOLDOWN_SECONDS = 60)과 같아야 한다 — 짧으면 버튼은 눌리는데 서버가 거부한다
            startResendCooldown(60);
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
            sendCodeBtn.disabled = emailVerified;
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
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify({ email, code })
        });
        const result = await response.json();

        if (response.ok && result.success) {
            emailVerified = true;
            setHint(verifyHint, '✓ 이메일 인증 완료. 새 비밀번호를 입력해주세요.', 'success');
            verifyCodeEl.readOnly = true;
            lockEmailField(true);
            passwordGroup.classList.remove('hidden');
            passwordConfirmGroup.classList.remove('hidden');
            submitBtn.disabled = false;
            $('password').focus();
        } else {
            setHint(verifyHint, result.message || '인증에 실패했습니다.', 'error');
            verifyCodeBtn.disabled = false;
        }
    } catch (e) {
        setHint(verifyHint, '서버 오류로 인증에 실패했습니다.', 'error');
        verifyCodeBtn.disabled = false;
    }
});

// 비밀번호 확인
$('passwordConfirm').addEventListener('input', function () {
    const password = $('password').value;
    const passwordHint = $('passwordHint');
    if (this.value && this.value !== password) {
        setHint(passwordHint, '비밀번호가 일치하지 않습니다.', 'error');
    } else if (this.value && this.value === password) {
        setHint(passwordHint, '비밀번호가 일치합니다.', 'success');
    } else {
        setHint(passwordHint, '', '');
    }
});

// 재설정 제출
$('resetForm').addEventListener('submit', async function (e) {
    e.preventDefault();

    const email = emailEl.value.trim();
    const password = $('password').value;
    const passwordConfirm = $('passwordConfirm').value;

    errorMessage.style.display = 'none';
    successMessage.style.display = 'none';

    if (!emailVerified) {
        showMessage(errorMessage, '이메일 인증을 먼저 완료해주세요.');
        return;
    }
    if (password.length < 4) {
        showMessage(errorMessage, '비밀번호는 4자 이상이어야 합니다.');
        return;
    }
    if (password !== passwordConfirm) {
        showMessage(errorMessage, '비밀번호가 일치하지 않습니다.');
        return;
    }

    submitBtn.disabled = true;
    try {
        const response = await fetch('/auth/password-reset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify({ email, newPassword: password })
        });
        const result = await response.json();

        if (response.ok && result.success) {
            showMessage(successMessage, result.message + ' 잠시 후 로그인 화면으로 이동합니다.');
            setTimeout(() => { window.location.href = '/auth/login'; }, 1500);
        } else {
            showMessage(errorMessage, result.message || '비밀번호 변경에 실패했습니다.');
            submitBtn.disabled = false;
        }
    } catch (e) {
        showMessage(errorMessage, '서버 오류가 발생했습니다.');
        submitBtn.disabled = false;
    }
});
