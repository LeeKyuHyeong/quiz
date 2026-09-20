# 인증 남용 방어 (요청 제한 우회·로그인 무제한 시도·인증 메일 재발송·버킷 누적)
- 일자: 2026-09-20
- 유형: 버그 (보안)
- 우선순위: P0 (인증)
- 판정: 조건부 — 자동 검증 ✅, 남은 것은 로컬 MariaDB 확인·화면 확인·운영 반영(🙋)

## 1. 요청과 목적
- 사용자가 원한 것: Redis·AWS 작업 전에 지금 운영 중인 사이트의 구멍부터 막는다.
- 발견한 결함 4건 (2026-09-19)
  1. 요청 제한이 `X-Forwarded-For` 의 **첫 값**을 클라이언트 IP 로 썼다. nginx 는 클라이언트가 보낸 헤더 뒤에 실제 IP 를 덧붙이므로(`$proxy_add_x_forwarded_for`, 서버 설정 확인 2026-09-19) 첫 값은 클라이언트가 정한다 → 요청마다 값을 바꾸면 요청 제한 7곳이 전부 무력화.
  2. 실제 로그인 요청 `POST /auth/login-process` 에 요청 제한도 실패 횟수 제한도 없었다 (제한은 화면이 먼저 부르는 `/auth/check-login` 에만).
  3. 인증 메일 재발송 간격 제한이 없었다. 재발급이 기존 코드를 지워 코드당 5회 시도 제한도 함께 초기화됐다.
  4. IP 별 버킷이 지워지지 않아 접속 IP 수만큼 메모리에 쌓였다 (1번과 겹치면 위조 헤더 값 수만큼).
- 구현 중 추가 발견: `POST /admin/login-process`(구 관리자 로그인 폼)가 permitAll 인 **두 번째 비밀번호 검사 경로**였다. 실패 테스트에서 로그인 성공 이력이 실제로 남는 것을 확인. 계정 잠금을 피해 가는 경로라 같은 제한을 걸었다.
- 개발자 확인 결과(결정 사항, 2026-09-19~20)
  - 범위: quiz A1~A4 + O-010·O-001 확인 + dashboard C1·C2 + account D. itsm 제외.
  - 계정 잠금: 연속 5회 실패 → 5분. 성공하거나 시간이 지나면 풀린다. 영구 잠금·관리자 해제 절차 없음.
  - 실패 횟수 저장 위치: DB `member` 컬럼 (메모리·Redis 아님).
  - 재발송 간격 60초.
- 진행 중 둔 가정
  - 운영 컨테이너에서 nginx 는 Tomcat `RemoteIpValve` 기본 내부 프록시 대역(10/8·172.16/12·192.168/16·127/8) 안의 주소로 보인다 → 배포 후 Smoke 로 확인(§6-4). 아니면 모든 사용자가 한 버킷을 공유하게 되므로 롤백 사안.

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| A1-1 | 예외 | `X-Forwarded-For` 앞쪽 값을 요청마다 바꿔도 실제 접속 IP 기준으로 21번째 요청이 429 | ✅ | `ClientIpSpoofingTest#spoofedForwardedFor_*` (실제 Tomcat + `forward-headers-strategy=native`, 수정 전 200) |
| A1-2 | 정상 | 위조하지 않은 요청은 프록시가 넣은 IP 로 제한되고 다른 IP 는 영향 없음 | ✅ | `#plainRequest_*` |
| A1-3 | 노출 | 로그인 이력·관리자 폼의 IP 도 헤더 값이 아닌 실제 IP | ✅ | `CustomAuthenticationSuccessHandlerTest#*_ignoresClientSuppliedForwardedFor` (수정 전 실패). 관리자 폼은 같은 메서드 사용 |
| A2-1 | 예외 | 같은 IP 의 로그인 요청은 분당 20회, 이후 429 + 안내 문구 | ✅ | `LoginAttemptLimitTest#loginRequests_areRateLimitedPerIp` |
| A2-2 | 예외 | 연속 5회 실패 뒤에는 올바른 비밀번호로도 거부 + 잠금 안내(남은 분) | ✅ | `#fiveConsecutiveFailures_lockTheAccount` |
| A2-3 | 권한 | IP 를 바꿔 가며 시도해도 계정 기준으로 잠긴다 | ✅ | `#lockIsPerAccount_notPerIp` |
| A2-4 | 정상 | 잠금 시간이 지나면 로그인되고 횟수는 0 | ✅ | `#lockExpires` |
| A2-5 | 경계 | 4회 실패·성공·4회 실패는 잠기지 않는다 | ✅ | `#successResetsTheCounter` |
| A2-6 | 연쇄 | 비밀번호 재설정 성공 시 잠금 해제 | ✅ | `#passwordReset_unlocks` (`/auth/password-reset` 경유) |
| A2-7 | 노출 | 정지 계정은 몇 번을 시도해도 정지 안내만 (일시 잠금 안내와 섞이지 않음) | ✅ | `#bannedAccount_keepsItsOwnMessage` |
| A2-8 | 노출 | 없는 이메일은 몇 번을 시도해도 같은 일반 문구 | ✅ | `#unknownEmail_neverRevealsLockState` |
| A2-9 | 경계 | 동시에 16건을 보내도 비밀번호 검사까지 가는 시도는 5건 | ✅ | `#parallelAttempts_cannotExceedTheLimit` (H2) |
| A2-10 | 권한 | `/admin/login-process` 도 같은 잠금·같은 횟수를 따른다 | ✅ | `#adminLoginForm_honorsTheLock`, `#adminLoginForm_failuresCount` |
| A2-11 | 정상 | **MariaDB 에서도** 정확히 5번째 실패 뒤에 잠긴다 (UPDATE 의 SET 대입 순서가 H2 와 다름) | 🙋 | §6-2. H2 로는 확인 불가 |
| A2-12 | 정상 | 로그인 화면에 잠금·429 안내 문구가 보인다 | 🙋 | §6-3 |
| A3-1 | 예외 | 60초 안의 재요청은 메일을 보내지 않고 남은 초를 안내, 기존 코드·시도 횟수 유지 | ✅ | `EmailVerificationCooldownTest#resendWithinCooldown_*` |
| A3-2 | 정상 | 60초가 지나면 새 코드로 재발송 | ✅ | `#resendAfterCooldown_isAllowed` |
| A3-3 | 경계 | 가입용·재설정용은 같은 테이블이라 간격도 공유 | ⬜ | 가입된 주소에는 가입용 발송이 애초에 거부돼 두 용도가 한 주소에서 만나는 경우가 없다 — 별도 테스트 없음 |
| A3-4 | 정상 | 가입·재설정 화면에 재발송 안내 문구가 보인다 | 🙋 | §6-3 |
| A4-1 | 정상 | 10분 넘게 쓰이지 않은 버킷만 지운다 | ✅ | `LoginRateLimiterTest#idleBuckets_areEvicted` |
| A4-2 | 경계 | 지워진 IP 가 다시 오면 새 버킷으로 정상 동작 / 계속 쓰이는 버킷은 유지(한도에 걸린 IP 가 풀려나지 않음) | ✅ | `#evictedIp_startsFresh`, `#activeBucket_isKept` |

## 3. 변경 사항
- `security/LoginRateLimiter` — `resolveClientIp` 가 헤더를 읽지 않고 `getRemoteAddr()` 를 쓴다(운영은 `server.forward-headers-strategy=native` 라 Tomcat 이 실제 IP 를 넣어 준다). 버킷 마지막 사용 시각을 기록하고 요청 처리 중 1분에 한 번 10분 유휴 버킷 제거(스케줄러 없음)
- `security/CustomAuthenticationSuccessHandler`·`controller/admin/AdminController` — 각자 갖고 있던 헤더 파싱 제거, `resolveClientIp` 사용
- `security/LoginAttemptFilter` (신규) — `POST /auth/login-process` 에서 IP 제한 → 계정 잠금 확인. `UsernamePasswordAuthenticationFilter` 앞(CsrfFilter 뒤라 CSRF 가 틀린 요청은 세지 않는다). `SecurityConfig` 에 `addFilterBefore` 1줄 — **매처 순서는 건드리지 않음**
- `service/LoginAttemptService` (신규) — `reserveAttempt(email)`: 잠금 만료 해제 → 시도 1회를 미리 세는 UPDATE(한도 미만일 때만 성공, 한도에 닿으면 같은 UPDATE 에서 잠금 시각 기록) → 실패하면 남은 시간 반환. `REQUIRES_NEW`(호출자 트랜잭션이 로그인 실패로 롤백돼도 횟수는 남는다)
- `repository/MemberRepository` — 위 UPDATE 3개 + 조회 1개. `service/MemberService` — 로그인 성공(`recordLoginSuccess`·`login`)과 `resetPassword` 에서 횟수 0
- `entity/Member` — `loginFailCount`·`loginLockedUntil`. **`updatable = false` + setter 없음**: 엔티티를 통째로 저장하는 곳(게임 결과 등)이 동시에 늘어난 횟수를 옛 값으로 덮어쓰지 않게 쿼리로만 바꾼다
- `service/EmailVerificationService`·`EmailVerificationRepository` — 발급 전 마지막 발급 시각 확인(60초)
- **DB·설정 변경: `member` 에 컬럼 2개 추가 — `schema.sql` 반영. 로컬·운영 DB 에 직접 ALTER 필요(§6-1, §6-4)**
  ```sql
  ALTER TABLE member
    ADD COLUMN login_fail_count INT NOT NULL DEFAULT 0,
    ADD COLUMN login_locked_until DATETIME NULL;
  ```

## 4. 영향 범위 분석
- `resolveClientIp` 호출처: `AuthController` 7곳 + 신규 2곳. 헤더를 직접 읽는 다른 코드 없음(`grep X-Forwarded-For`·`getRemoteAddr`)
- dev·test 는 프록시가 없어 `getRemoteAddr()` = 127.0.0.1(화이트리스트) — 이전과 동일하게 제한에 걸리지 않는다. 기존 MockMvc 테스트 영향 없음
- 로그인 1회 = `/auth/check-login` + `/auth/login-process` 로 토큰 2개 소모 → 같은 IP 에서 실효 분당 10회 로그인. 공용 IP(NAT) 환경에서 걸릴 수 있으나 감수
- **기존 테스트 수정 2개 파일(단언 변경 없음)**: `SecurityFilterChainTest`(단독 컨텍스트에 `SecurityConfig` 새 의존성 빈 2개 추가), `CustomAuthenticationSuccessHandlerTest`(테스트 1건 추가)
- 배포 순서: 새 컬럼은 기본값이 있어 **ALTER 를 먼저 해도 구 버전이 그대로 돈다**(구 버전 INSERT 는 컬럼을 생략 → DEFAULT 0). 반대로 ALTER 없이 새 버전을 올리면 `ddl-auto=validate` 로 기동 실패 → blue/green 헬스체크에서 걸려 전환되지 않는다(서비스 중단은 없지만 배포 실패)

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 재현(수정 전) | 새 테스트 5개 클래스 실행 | 11건 실패: IP 위조 200, 로그인 21번째 200, 5회 실패 뒤 로그인 성공, 관리자 폼으로 성공 이력 생성, 동시 시도 무제한, 쿨다운 없음, 버킷 미제거 | ✅ |
| 빌드 + 전체 회귀 | `./mvnw clean test` | 421 passed (402 + 신규 19), 0 failed | ✅ |
| 로컬 MariaDB | §6-1·6-2 | — | 🙋 |
| 사용자 시나리오 | §6-3 | — | 🙋 |
| 운영 반영·Smoke | §6-4 | — | 🙋 |

## 6. 수동 확인 시나리오

### 6-1. 로컬 DB ALTER (개발자)
로컬 MariaDB `song` 에서 §3 의 ALTER 실행.
[기대] `SHOW COLUMNS FROM member LIKE 'login_%';` 에 2행 — `login_fail_count int NOT NULL 기본 0`, `login_locked_until datetime NULL`.

### 6-2. MariaDB 에서 잠금 시점 확인 (에이전트 — 6-1 뒤에 실행, 틀린 비밀번호만 보내므로 비밀번호 불필요)
dev 를 18082 로 기동 → `/auth/login` 에서 CSRF 토큰·쿠키를 받아 `test6@test.com` 에 틀린 비밀번호 6회.
[기대] 1~5회 "이메일 또는 비밀번호가 일치하지 않습니다.", 6회 "…로그인이 잠겼습니다. 5분 뒤에…". DB `login_fail_count = 5`, `login_locked_until` ≈ 5번째 시각 + 5분.
[실패 모양] 5회째에 이미 잠금 문구가 나오면 SET 대입 순서 문제 → UPDATE 를 고쳐야 한다.
- dev 는 접속 주소가 127.0.0.1(화이트리스트)이라 IP 제한은 이 절차로 볼 수 없다 — 계정 잠금만 확인.

### 6-3. 화면 확인 (개발자, dev)
1. `test6@test.com` 로그인 화면에서 틀린 비밀번호 5회 → 6회째. [기대] 로그인 폼 아래에 잠금 안내가 보이고, **올바른 비밀번호로도** 같은 안내.
2. 그 상태에서 비밀번호 재설정(메일 코드) 완료 → 새 비밀번호로 로그인. [기대] 바로 로그인된다. (dev 에서 메일이 실제로 나가지 않으면 이 항목은 운영에서)
3. 회원가입 화면에서 인증 코드 받기 → 바로 다시 받기. [기대] "인증 메일은 60초에 한 번만… N초 뒤에 다시 시도해주세요." 가 화면에 보인다. 60초 뒤에는 다시 발송.
4. 잠금 해제: 5분 기다리거나 `UPDATE member SET login_fail_count = 0, login_locked_until = NULL WHERE email = 'test6@test.com';`

### 6-4. 운영 반영 (개발자 SSH — 한 단계씩, 출력을 보고 다음 단계)
① 수동 백업 (runbook §4-1)
```bash
docker exec quiz-db sh -c 'mariadb-dump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines song' | gzip > /root/backup/song-before-login-lock-$(date +%Y%m%d-%H%M).sql.gz && ls -la /root/backup | tail -2
```
[기대] 방금 시각의 파일, 크기가 평소 백업과 비슷.

② ALTER (구 버전이 도는 상태에서 실행해도 안전)
```bash
docker exec -i quiz-db sh -c 'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" song' <<'EOF'
ALTER TABLE member
  ADD COLUMN login_fail_count INT NOT NULL DEFAULT 0,
  ADD COLUMN login_locked_until DATETIME NULL;
SHOW COLUMNS FROM member LIKE 'login_%';
EOF
```
[기대] 2행 출력. 사이트는 계속 정상(구 버전은 새 컬럼을 모른 채 동작).

③ main 머지·푸시 → Actions: 테스트 421 → 유휴 색 헬스체크 통과 → upstream 전환.

④ Smoke — **가장 중요한 확인은 IP**
- 본인 계정으로 로그인 → 관리자 화면 회원 상세의 로그인 이력 IP. [기대] 본인의 공인 IP. **`172.x`·`127.0.0.1` 이면 모든 사용자가 한 버킷을 공유하는 상태 → 즉시 롤백(runbook §2).**
- 에이전트: 외부에서 브라우저 UA·GET 으로 `/`·`/auth/login` 200 확인.
- 서버: `docker compose logs --since 10m app-<활성색> | grep -c "RateLimit.*차단"` [기대] 0 또는 본인 시험분만.

## 7. checklist 점검
- 점검함: 호출처 전수(IP 판별 3곳·비밀번호 검사 경로 2곳) / 동시성(확인-증가 사이의 틈, 엔티티 저장의 덮어쓰기) / 트랜잭션 롤백 시 횟수 유지(`REQUIRES_NEW`) / 에러 문구의 계정 존재 노출(없는 이메일은 일반 문구 유지. 잠금 문구는 계정 존재를 드러내지만 `/auth/check-email` 이 이미 공개하는 정보) / 로그에 개인정보 추가 없음 / 스키마 변경의 배포 순서
- 해당 없음: CSS·템플릿 변경 없음(기존 오류 표시 영역에 문구만 달라짐), 멀티 상태 머신, 배치

## 8. 발견된 문제와 조치
- `/admin/login-process` 가 공개된 비밀번호 검사 경로 / 구 관리자 로그인 폼이 permitAll 로 남아 있음 / 같은 제한 적용. 폼 자체의 제거 여부는 O-015
- 필터의 경로 비교를 `getServletPath()` 로 썼더니 MockMvc 에서 빈 문자열이라 필터가 돌지 않았다 → Spring Security 와 같은 `AntPathRequestMatcher` 로 교체

## 9. 미검증 영역과 남은 위험
- **여러 IP 로 여러 계정에 1~2회씩 넣어 보는 공격(유출 비밀번호 목록)은 막지 못한다** — IP 제한도 계정 잠금도 걸리지 않는다. CAPTCHA·유출 비밀번호 대조가 필요하나 이 규모에서는 두지 않기로 함(O-014)
- 남의 계정을 일부러 5번 틀려 5분씩 잠그는 것은 가능하다 — 짧은 잠금 + 비밀번호 재설정으로 본인이 풀 수 있게 한 것이 완화책
- H2 는 MariaDB 의 UPDATE 대입 순서를 재현하지 못한다(A2-11)

## 10. Regression 등록
- R-021 ~ R-024
