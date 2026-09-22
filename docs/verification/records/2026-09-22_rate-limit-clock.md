# O-023 — 요청 제한 테스트가 기계 속도에 좌우되지 않게 시계를 주입한다
- 일자: 2026-09-22 (회사 PC)
- 유형: 테스트 안정화 (운영 코드는 시계 주입 지점만 추가, 동작 불변)
- 대상: `LoginRateLimiter`, `LoginAttemptLimitTest`
- 판정: **수용 가능**

## 1. 요청·결정·가정
- 문제: `LoginAttemptLimitTest.loginRequests_areRateLimitedPerIp` 가 회사 PC 에서 간헐 실패(429 기대, 200). 로그인 20회(요청마다 비밀번호 해시 비교)가 3초를 넘기면 greedy 보충(분당 20 = 3초에 1개)으로 21번째 토큰이 생긴다. 회사 PC 3.28·3.42초, 집 PC 통과.
- 결정(에이전트, 개발자 "닫을 수 있게 진행" 위임 — O-023 은 테스트 수정 허가가 필요한 항목이었음): 테스트를 느슨하게 하지 않고 **제한기의 시계를 주입 가능하게** 해 테스트가 시계를 돌린다. Bucket4j `LocalBucketBuilder.withCustomTimePrecision(TimeMeter)` (8.14.0 jar `javap` 로 확인) 로 버킷 보충과 유휴 정리가 같은 시계를 본다.
- 운영 시계는 `TimeMeter.SYSTEM_NANOTIME`(단조). Bucket4j 기본은 `SYSTEM_MILLISECONDS` 였으므로 정밀도만 바뀌고 정책(20/분 greedy)은 같다.

## 2. AC
| # | AC | 상태 |
|---|---|---|
| 1 | [정상] 같은 IP 로그인 20회는 200, 21번째는 429 + 안내 문구 — 기계가 느려도 같은 결과 | ✅ 손으로 돌리는 시계라 벽시계와 무관 |
| 2 | [경계] 2초 뒤에도 429, 4초 뒤에는 1회 200 후 다시 429 (greedy 보충 계약을 처음으로 고정) | ✅ 추가 assert 3개 |
| 3 | [회귀] 기존 유휴 버킷 정리 테스트 3건(`LoginRateLimiterTest`)·보안 필터 체인 테스트는 그대로 통과 | ✅ |
| 4 | [노출] 운영 빈은 기본 생성자(실제 시계) — Spring 은 생성자가 둘이면 기본 생성자를 쓴다 | ✅ `SecurityFilterChainTest`·전체 회귀에서 실제 시계 경로 기동 |

## 3. 변경
- `LoginRateLimiter`: `TimeMeter` 필드, `public LoginRateLimiter()` → `SYSTEM_NANOTIME`, 패키지 생성자 `LoginRateLimiter(TimeMeter)`; `resolveBucket` 에 `.withCustomTimePrecision(timeMeter)`; `tryAcquire(ip)` 가 `timeMeter.currentTimeNanos()` 를 정리 기준으로 넘김. `lastEviction` 초기값도 같은 시계.
- `LoginAttemptLimitTest`: 중첩 `ManualClock implements TimeMeter`(1시간에서 시작, `advance`) + `@TestConfiguration` 의 `@Primary LoginRateLimiter manualClockLoginRateLimiter()`. 필터·컨트롤러가 이 빈을 받는다(운영 `@Component` 빈은 남아 있지만 `@Primary` 가 이김). 이 클래스는 자기 컨텍스트를 따로 띄운다.
- 첫 시도의 잘못된 assert: "59초 뒤 429" 를 넣었다가 실패(200) — greedy 보충은 59초면 19개가 돌아온다. 계약을 2초/4초로 고쳐 고정.

## 4. 영향 범위
- `new LoginRateLimiter()` 호출처: `SecurityFilterChainTest` TestConfig 1곳(기본 생성자 그대로), `LoginRateLimiterTest` 3곳(그대로).
- `tryAcquire(ip, nowNanos)` 의 의미: nowNanos 는 정리 판단에만 — 기존 유닛 테스트가 `System.nanoTime()` 기준 값을 넘기고 기본 생성자의 시계도 nanoTime 이라 일관.

## 5. 실행한 검증
| 항목 | 결과 |
|---|---|
| `LoginAttemptLimitTest` 11 | ✅ PASS (첫 실행 1 실패 → assert 정정 → 11/11) |
| `LoginRateLimiterTest` 3 · `SecurityFilterChainTest` | ✅ PASS (`-Dtest=` 6 클래스 실행) |
| 전체 회귀 | (아래 §6) |

## 6. 전체 회귀
- `./mvnw test` (회사 PC): **61 클래스 · 468건 / 실패 0**, BUILD SUCCESS, 06:52 min (O-022 기동 정리 테스트 5건 포함, records/2026-09-22_startup-sweep §7 과 같은 실행). 이 회사 PC 에서 오전엔 간헐 실패하던 `loginRequests_areRateLimitedPerIp` 가 같은 스위트 안에서 통과.
