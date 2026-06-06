# System.md — 면접 준비용 소스 분석 정리

> 최종 업데이트: 2026-05-11
> 프로젝트: 멀티플레이어 음악 맞추기 게임 (Spring Boot 3.4.1 + Java 17 + MariaDB)

---

## 1. 아키텍처 개요

```
[Browser] ←→ [Thymeleaf SSR + REST API + STOMP/SockJS]
                        ↓
              [Service Layer (22개 서비스)]
                        ↓
              [JPA Repository → MariaDB]
                        ↓
              [Batch Scheduler (20+ 배치)]
```

- **SSR + REST 혼합**: 페이지 렌더링은 Thymeleaf, 상태 변경은 REST API (`@ResponseBody`)
- **WebSocket은 서버→클라이언트 push 전용**: `@MessageMapping` 없음, POST는 REST 유지
- **인메모리 + DB 하이브리드 상태 관리**: 게임 진행 중 상태는 메모리(ConcurrentHashMap), 영구 데이터는 DB

---

## 2. 동시성 처리

### 2.1 Room-Level Locking

**핵심 파일**: `MultiGameService.java`

```java
private final ConcurrentHashMap<Long, Set<Long>> usedSongsByRoom = new ConcurrentHashMap<>();
private final ConcurrentHashMap<Long, Object> roomLocks = new ConcurrentHashMap<>();
```

| 구간 | 보호 대상 | 방식 |
|------|-----------|------|
| 정답 제출 (`handleCorrectAnswer`) | 동시 정답자 방지 | `synchronized(roomLocks.computeIfAbsent(...))` + double-check |
| 곡 스킵 (`skipCurrentSong`) | 동시 스킵 요청 | 동일 room-level lock |
| 곡 선택 (`selectSong`) | 중복 곡 방지 | `ConcurrentHashMap.newKeySet()` 원자적 add |

**면접 포인트**:
- `synchronized` 메서드 레벨 → 방 단위 락으로 변경한 이유: 다른 방 요청까지 블로킹하는 성능 병목 제거
- `ConcurrentHashMap` vs `Collections.synchronizedMap()`: 세그먼트 락으로 읽기 무잠금, 높은 처리량
- `computeIfAbsent()`로 락 객체 생성 자체도 원자적

### 2.2 Optimistic Locking

```java
// GameRoom.java
@Version
private Long version;
```

- DB 레벨에서 동시 업데이트 충돌 감지
- `OptimisticLockingFailureException` 발생 시 재시도 가능
- 인메모리 락과 DB 락의 이중 보호

### 2.3 면접 질문

- Q: `ConcurrentHashMap`과 `Collections.synchronizedMap()` 차이?
  - A: 세그먼트 락 기반, 읽기는 Lock-Free, 쓰기는 버킷 단위 락 → 높은 동시 처리량
- Q: 메서드 레벨 `synchronized` 대신 Optimistic Locking 적용 시 트레이드오프?
  - A: 충돌 빈도 낮으면 성능 우수, 높으면 재시도 오버헤드. 게임 방은 독립적이므로 충돌 확률 낮음
- Q: 인메모리 상태(`Map`)와 DB 상태 불일치 시나리오?
  - A: 서버 재시작 시 인메모리 유실. 해결: finishGame()에서 반드시 DB 동기화, cleanupRoom()에서 락 제거

---

## 3. 실시간 통신 (WebSocket)

### 3.1 아키텍처

```
[Client] ←SockJS→ [/ws endpoint] ←STOMP→ [Simple Broker /topic]
                                              ↓
                                    /topic/room/{roomCode}
```

- **STOMP over SockJS**: 브라우저 호환성 + HTTP 세션 통합
- **단일 토픽**: 방 코드별 `/topic/room/{roomCode}`, `type` 필드로 메시지 구분
- **Polling Fallback**: WebSocket 5회 재연결 실패 → 자동 polling 전환

### 3.2 메시지 타입

| Type | 트리거 | 페이로드 |
|------|--------|----------|
| `ROOM_UPDATE` | join/leave/ready/kick | 참가자 목록, 상태 |
| `GAME_START` | startGame | (empty) |
| `ROUND_UPDATE` | startRound/nextRound/skipSong | 라운드 정보, 타이머 |
| `ROUND_RESULT` | 정답/전원스킵 | 정답자, 점수 |
| `GAME_FINISH` | 게임 종료 | (empty) |
| `CHAT` | sendChat | 메시지, 닉네임, 타입 |
| `KICKED` | kick | 대상 멤버 ID |
| `RESTART` | restart | (empty) |

### 3.3 인증 브릿지

```
HTTP Session → SockJS 핸드셰이크 → STOMP CONNECT →
WebSocketAuthInterceptor가 SecurityContext 추출 → accessor.setUser(auth)
```

- `HttpSessionCsrfTokenRepository` 사용: SockJS XHR transport가 세션 쿠키 자동 상속
- CONNECT 시점에 Spring Security 인증을 STOMP 세션에 전파

### 3.4 재연결 전략

```javascript
// ws-client.js - 지수 백오프
const delay = Math.min(1000 * Math.pow(2, attempts - 1), 16000);
// 1s → 2s → 4s → 8s → 16s → fallback to polling
```

### 3.5 면접 질문

- Q: WebSocket 대신 SSE(Server-Sent Events)를 선택하지 않은 이유?
  - A: SSE는 단방향(서버→클라이언트)만 지원. STOMP는 구독/발행 패턴 제공, SockJS는 브라우저 fallback 내장
- Q: WebSocket 연결 실패 시 처리 방식?
  - A: 지수 백오프 재연결(5회) → polling fallback → 동일 핸들러로 처리하여 코드 중복 최소화
- Q: 단일 토픽 vs 메시지 타입별 토픽 설계 선택 이유?
  - A: 방 참가자는 모든 이벤트를 수신해야 함. 타입별 토픽은 구독 관리 복잡도만 증가

---

## 4. 예외 처리 아키텍처

### 4.1 계층 구조

```
RuntimeException
└── BusinessException (400 기본, HttpStatus 커스텀 가능)
    └── ResourceNotFoundException (404)
```

### 4.2 GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    // BusinessException → status from exception
    // IllegalArgumentException → 400
    // IllegalStateException → 409
    // AccessDeniedException → 403 (MVC: login redirect)
    // Exception → 500
}
```

**REST vs MVC 자동 분기**:
```java
private boolean isApiRequest(HttpServletRequest request) {
    return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))
        || accept.contains("application/json");
}
```

- API 요청 → `{success: false, message: "..."}` JSON
- MVC 요청 → `error.html` 또는 redirect

### 4.3 하위 호환성

```javascript
// common.js - fetch wrapper
// 4xx/5xx 응답에 {success} 필드가 있으면 → 200으로 변환
// 기존 JS 코드가 HTTP 200만 기대하므로 호환성 유지
```

### 4.4 면접 질문

- Q: `@ControllerAdvice` vs `@RestControllerAdvice` 차이?
  - A: `@RestControllerAdvice`는 `@ResponseBody` 자동 적용. 여기서는 REST/MVC 분기 로직으로 양쪽 모두 처리
- Q: 60개 try-catch를 제거하면서 기존 동작을 어떻게 유지했는가?
  - A: Service에서 `BusinessException` 던지면 GlobalExceptionHandler가 동일 형식으로 응답. fetch wrapper가 HTTP status → success 필드 변환

---

## 5. 보안

### 5.1 인증/인가

| 항목 | 구현 |
|------|------|
| 인증 방식 | 세션 기반 (Spring Security) |
| 비밀번호 | BCrypt (기본 10 라운드) |
| CSRF | `HttpSessionCsrfTokenRepository` + meta 태그 + fetch wrapper 자동 주입 |
| 세션 | 최대 1개/사용자, 30분 타임아웃, 중복 로그인 차단 |
| 권한 | `ROLE_ADMIN` (admin/**), authenticated (mypage/**), 나머지 permitAll |

### 5.2 인증 흐름

```
Form 로그인 (/auth/login-process)
    ↓ email/password
CustomUserDetailsService.loadUserByUsername(email)
    ↓ Member → CustomUserDetails
CustomAuthenticationSuccessHandler
    ↓ 로그인 이력 기록 (IP, User-Agent), lastLoginAt 갱신
    ↓ JSON 응답 {success: true, nickname: "..."}
```

### 5.3 방어 포인트

| 공격 | 방어 |
|------|------|
| SQL Injection | JPA 파라미터 바인딩, `@Param` + `SecurityInputValidator`의 페이로드 패턴 차단 (SLEEP, BENCHMARK, DBMS_PIPE, WAITFOR, UNION SELECT, XOR 등) |
| XSS | `th:text` (자동 이스케이프), `textContent` |
| CSRF | 모든 POST에 CSRF 토큰 필수 |
| IDOR | Service 레벨에서 소유권 검증 |
| Brute Force / Bot | `LoginRateLimiter` — bucket4j 기반 IP별 분당 20회 토큰 버킷 + 화이트리스트. 로그인 실패 메시지 일반화 |
| 가짜 이메일 가입 | 회원가입 전 6자리 이메일 인증(`EmailVerificationService`). 코드 TTL 5분, 인증 후 10분 내 가입 유효 |

### 5.4 이메일 인증 흐름

```
[Client] /auth/send-verification (email)
          ↓ LoginRateLimiter.tryAcquire(ip)
          ↓ SecurityInputValidator.validateEmailOrThrow(email)
          ↓ memberRepository.existsByEmail() 차단
          ↓ EmailVerification 레코드 저장 (코드 + 만료시각)
          ↓ Brevo API 호출 (HTTPS POST /v3/smtp/email)
[Brevo] → 사용자 메일함

[Client] /auth/verify-code (email, code)
          ↓ 만료/시도횟수 검사 (max 5회)
          ↓ EmailVerification.verified=true, verifiedAt=now

[Client] /auth/register
          ↓ isRecentlyVerified(email) — 최근 10분 내 인증 여부
          ↓ consumeVerification(email) — 사용 후 레코드 삭제
```

**왜 Gmail SMTP가 아닌 Brevo HTTP API인가**:
- cafe24가 outbound SMTP 포트(25/465/587)를 전부 차단 → `java.net.SocketTimeoutException`
- HTTPS(443) 기반 API는 호스팅사 정책 영향 없음
- 대시보드 추적, 반송/스팸 통계 등 부가 가시성

### 5.5 면접 질문

- Q: 세션 기반 인증과 JWT 인증의 트레이드오프?
  - A: 세션은 서버 상태 유지 필요(수평 확장 어려움), JWT는 무상태지만 토큰 무효화 어려움. SSR + 단일 서버이므로 세션이 적합
- Q: CSRF 토큰을 WebSocket에서 어떻게 처리?
  - A: SockJS는 XHR transport 사용 시 HTTP 세션 쿠키를 자동 상속. STOMP CONNECT 헤더에도 CSRF 전달
- Q: 동시 로그인 차단(maximumSessions=1) 구현 원리?
  - A: `SessionRegistry`가 사용자별 세션 추적, 새 로그인 시 기존 세션 만료 처리
- Q: 토큰 버킷 vs 슬라이딩 윈도우 vs 고정 윈도우 Rate Limit의 차이?
  - A: 토큰 버킷은 평균 속도 + 순간 버스트 모두 제어 가능. 고정 윈도우는 경계 시각에 2배 트래픽 허용 문제. 슬라이딩 윈도우는 정확하지만 메모리/연산 비용 큼. 본 프로젝트는 단일 인스턴스라 bucket4j 인메모리로 충분
- Q: SMTP 차단 환경에서 메일 발송 방법?
  - A: HTTPS API 기반 트랜잭셔널 메일 서비스(Brevo/Sendgrid/Resend/AWS SES). 포트 443은 어느 호스팅도 차단하지 않음. 추가로 도메인 평판/통계도 얻을 수 있음

---

## 6. ELO/LP 티어 시스템

### 6.1 구조

```
BRONZE → SILVER → GOLD → PLATINUM → DIAMOND → MASTER → CHALLENGER
  LP: 0-99 per tier | 100 도달 시 승격 | 0 미만 시 강등
```

### 6.2 LP 계산

**상대 레이팅 있을 때 (ELO 기반)**:
```java
avgOpponentRating = 참가자 평균 레이팅 (자신 제외)
lpChange = MultiTier.calculateLpChange(myTier, myLp, avgOpponentRating, totalPlayers, rank)
```

**상대 레이팅 없을 때 (순위 기반)**:
```java
lpChange = MultiTier.calculateLpChange(totalPlayers, rank)
```

### 6.3 LP 소멸

- **조건**: 멀티플레이어 30일 이상 미접속
- **감소**: -7 LP / 주기
- **보호**: Bronze 0 LP는 감소 안 함
- **자동 강등**: LP < 0 시 하위 티어로

### 6.4 면접 질문

- Q: ELO 레이팅 시스템의 원리?
  - A: 상대 레이팅 대비 기대 승률 계산 → 실제 결과와 비교하여 LP 변동. 강한 상대를 이기면 LP 많이 획득
- Q: 티어 시스템의 수평 확장 문제?
  - A: 현재 단일 서버 인메모리. Redis Sorted Set으로 전환하면 실시간 랭킹 + 수평 확장 가능

---

## 7. 정답 검증 알고리즘

### 7.1 정규화

```java
normalize(input):
  1. toLowerCase()                        // 대소문자 무시
  2. replaceAll("\\s+", "")              // 공백 제거
  3. replaceAll("[^a-z0-9가-힣]", "")    // 특수문자 제거
```

**예시**: `"Spring Day!"` → `"springday"`, `"봄 날~"` → `"봄날"`

### 7.2 다중 정답 지원

```
1. Song.title 정규화 비교
2. SongAnswer 테이블의 대체 정답들 순회 비교
```

- `SongAnswer`: 영어→한국어 음역, 약어, 별칭 등 저장
- `AnswerGeneratorUtil`: 영어→한국어 발음 자동 변환 (~700개 단어 매핑)

### 7.3 초성 힌트 (팬 챌린지)

```java
// 한글 분해: (char - '가') / 588 → 초성 인덱스
"봄날" → "ㅂㄴ"
"Dynamite" → "D*******"
```

### 7.4 면접 질문

- Q: Fuzzy Matching을 적용하지 않은 이유?
  - A: 음악 제목은 오타가 아닌 정확한 정답이 필요. 대신 SongAnswer 테이블로 허용 변형을 관리
- Q: 정규화만으로 부족한 케이스?
  - A: "사랑" vs "LOVE" 같은 의미 동의어. SongAnswer 테이블에 수동 등록으로 해결

---

## 8. 배치 아키텍처

### 8.1 구조

```
BatchScheduler.init()
    ↓
BatchConfig 테이블에서 활성 배치 조회
    ↓
CronTrigger로 동적 스케줄링 (런타임 변경 가능)
    ↓
BatchExecutionHistory에 실행 결과 기록
```

### 8.2 주요 배치 (20+개)

| 카테고리 | 배치 | 기능 |
|----------|------|------|
| 정리 | SessionCleanupBatch | 2시간+ 진행 중/24시간+ 대기 방 정리 |
| 정리 | ChatCleanupBatch | 오래된 채팅 삭제 |
| 통계 | DailyStatsBatch | 일별 통계 집계 |
| 랭킹 | RankingUpdateBatch | 랭킹 갱신 |
| 랭킹 | WeeklyRankingResetBatch | 주간 랭킹 초기화 |
| 멤버 | LpDecayBatch | 30일 미접속 LP 감소 |
| 멤버 | BadgeAwardBatch | 뱃지 자격 검사 및 부여 |
| 곡 | YouTubeVideoCheckBatch | 삭제된 영상 감지 |
| 곡 | SongAnswerGenerationBatch | 정답 변형 자동 생성 |

### 8.3 설계 특징

- **DB 기반 설정**: `BatchConfig` 테이블에서 cron 표현식/활성 여부 관리
- **런타임 변경**: 관리자 화면에서 스케줄 변경 → `refreshSchedule(batchId)` 호출
- **수동 실행**: `executeManually(batchId)` 지원
- **독립 트랜잭션**: `Propagation.REQUIRES_NEW`로 배치 간 격리

### 8.4 면접 질문

- Q: Spring Batch가 아닌 자체 구현을 선택한 이유?
  - A: 단순 CRUD 배치가 대부분이라 Spring Batch의 Reader-Processor-Writer 패턴이 과도함. `TaskScheduler` + `CronTrigger`로 충분
- Q: DB 기반 동적 스케줄링의 장점?
  - A: 서버 재시작 없이 배치 주기 변경 가능, 관리자 UI에서 직접 제어

---

## 9. 팬 챌린지 & 뱃지 시스템

### 9.1 팬 챌린지 난이도

| 설정 | BEGINNER | NORMAL | HARDCORE |
|------|----------|--------|----------|
| 재생 시간 | 7초 | 5초 | 3초 |
| 답변 시간 | 10초 | 8초 | 5초 |
| 생명 | 5개 | 3개 | 3개 |
| 초성 힌트 | O | X | X |
| 랭크 기록 | X | X | O |

### 9.2 뱃지 카테고리

```
BEGINNER: 첫 게임, 첫 정답
SCORE: 10000/50000/100000/500000 누적 점수
VICTORY: 10/50/100 멀티 승리
STREAK: 5/10/20 연속 정답
TIER: Silver/Gold/Platinum 달성
SPECIAL: 팬 챌린지 퍼펙트, 하드코어 클리어
```

- **희귀도**: COMMON(회색) → RARE(파랑) → EPIC(보라) → LEGENDARY(금)
- **부여 방식**: `BadgeAwardBatch` 자동 + `BadgeService` 즉시 부여 혼합
- **중복 방지**: DB uniqueness constraint

---

## 10. JPA & 쿼리 패턴

### 10.1 엔티티 관계

```
Member ─1:N─ GameSession ─1:N─ GameRound ─1:N─ GameRoundAttempt
Member ─1:N─ MemberBadge ─N:1─ Badge
GameRoom ─1:N─ GameRoomParticipant ─N:1─ Member
GameRoom ─1:N─ GameRoomChat
Song ─1:N─ SongAnswer
Song ─N:1─ Genre
```

### 10.2 쿼리 최적화 패턴

**Null-Safe 동적 필터링**:
```java
@Query("SELECT s FROM Song s WHERE "
     + "(:keyword IS NULL OR LOWER(s.title) LIKE ...) AND "
     + "(:genreId IS NULL OR s.genre.id = :genreId) AND ...")
```

**CASE 기반 티어 랭킹**:
```java
ORDER BY CASE m.multiTier
    WHEN CHALLENGER THEN 6
    WHEN MASTER THEN 5 ... END DESC,
    COALESCE(m.multiLp, 0) DESC
```

**JPA SIZE() 함수**:
```java
WHERE SIZE(r.participants) < r.maxPlayers  // 컬렉션 크기 DB 레벨 비교
```

### 10.3 감사 필드

```java
// 방법 1: JPA 콜백
@PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
@PreUpdate  void onUpdate() { updatedAt = LocalDateTime.now(); }

// 방법 2: Hibernate 어노테이션
@CreationTimestamp private LocalDateTime createdAt;
@UpdateTimestamp   private LocalDateTime updatedAt;
```

### 10.4 면접 질문

- Q: N+1 문제가 발생할 수 있는 구간?
  - A: `GameRoom.participants`(LAZY) 순회 시. `JOIN FETCH` 또는 `@EntityGraph`로 해결 가능
- Q: `@Version` 사용 시 성능 영향?
  - A: UPDATE 쿼리에 `WHERE version = ?` 추가. 충돌 빈도 낮으면 거의 무시할 수준

---

## 11. 프론트엔드 패턴

### 11.1 CSRF 자동 주입

```javascript
// common.js - 전역 fetch wrapper
const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
// 모든 POST/PUT/DELETE에 자동 헤더 추가
```

### 11.2 세션 관리

- 30초 polling으로 동시 로그인 감지
- 401 응답 시 자동 로그인 페이지 redirect
- `navigator.sendBeacon()`으로 탭 닫기 시에도 leave 요청 보장

### 11.3 Dual-Mode (WS + Polling)

```javascript
let usingWebSocket = false;

// 초기: WebSocket 연결 시도
GameWebSocket.connect(roomCode, handlers, startPollingFallback);

// 실패 시: polling으로 전환
function startPollingFallback() {
    usingWebSocket = false;
    setInterval(fetchRoomStatus, 2000);
}
```

- 동일 핸들러로 WS 메시지와 polling 응답 처리 → 코드 중복 최소화

### 11.4 YouTube 플레이어 통합

- `isValidYoutubeVideoId()`: 11자 영숫자 + `-_` 검증
- 상태 머신: UNSTARTED(-1) → CUED(5) → PLAYING(1)
- `pendingPlay` 플래그: CUED 상태 대기 후 재생
- 에러 시 자동 스킵 + 호스트 UI 업데이트

---

## 12. 인프라 & 배포

### 12.1 Docker 구성

```yaml
app:
  image: quiz-app:latest
  memory: 512M (-Xms256m -Xmx512m, G1GC)
  base: eclipse-temurin:17-jre-alpine
  environment:
    SPRING_PROFILES_ACTIVE: prod
    SPRING_DATASOURCE_*: ${DB_*}
    BREVO_API_KEY: ${BREVO_API_KEY}    # 이메일 인증
    MAIL_FROM:     ${MAIL_FROM}

db:
  image: mariadb:10.11
  memory: 256M
  healthcheck: 10s interval
```

**환경변수 적용 시점 트랩**: Docker Compose의 `${변수}` 치환은 **컨테이너 생성 순간**에 일어남. 컨테이너가 실행 중일 때 `.env`를 수정해도 다음 `--force-recreate`까지 반영되지 않음. 새 환경변수를 추가했을 때는 반드시 `docker compose up -d --no-deps --force-recreate app`.

### 12.2 CI/CD

```
Push to main → GitHub Actions
    ↓ mvn clean package -DskipTests
    ↓ Docker build (latest + SHA tag)
    ↓ Push to DockerHub
    ↓ SSH to server → docker-compose up -d
```

**알려진 한계**:
- 워크플로우가 `docker-compose`(v1) 명령을 호출하지만 운영 서버엔 v2 plugin(`docker compose`)만 설치됨. 컨테이너 교체가 silently 실패할 가능성이 있어 배포 후 수동 확인 권장
- `docker-compose.yml` 자체는 자동 동기화되지 않음. 환경변수 매핑이 바뀌면 서버 파일을 직접 수정 필요

### 12.3 면접 질문

- Q: 무중단 배포를 어떻게 구현하겠는가?
  - A: 현재는 단일 컨테이너 교체(다운타임 발생). Blue-Green 또는 Rolling Update 필요. Nginx reverse proxy + health check로 구현 가능
- Q: Docker 메모리 설정 기준?
  - A: JVM Heap 512M + Native 메모리 여유. 전체 컨테이너 512M 제한은 GC 오버헤드 시 OOMKilled 위험 → 실제로는 768M 권장

---

## 13. 외부 API 연동

### 13.1 YouTube 영상 검증 (이중 체크)

```
Phase 1: oEmbed API (noembed.com)
├── 200 → 유효
├── 401/403 → 임베드 비활성화
└── 실패 → Phase 2

Phase 2: Thumbnail 크기 체크
├── > 1KB → 유효 (실제 썸네일)
└── < 1KB → 삭제된 영상 (기본 이미지)
```

- 타임아웃: 10초
- 배치에서 주기적 검증 (`YouTubeVideoCheckBatch`)

### 13.2 Brevo Transactional Email API (이메일 인증)

**엔드포인트**: `POST https://api.brevo.com/v3/smtp/email`

```java
// EmailVerificationService.sendCodeEmail
RestClient client = RestClient.builder()
    .baseUrl(brevoBaseUrl)
    .defaultHeader("api-key", brevoApiKey)
    .build();

client.post().uri("/smtp/email")
    .contentType(MediaType.APPLICATION_JSON)
    .body(Map.of(
        "sender",      Map.of("name", mailFromName, "email", mailFrom),
        "to",          List.of(Map.of("email", toEmail)),
        "subject",     subject,
        "htmlContent", html
    ))
    .retrieve()
    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { /* 본문 로깅 + BusinessException */ })
    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> { /* 일시 장애 BusinessException */ })
    .body(Map.class);
```

**에러 응답 처리 분리**:

| 응답 | 원인 | 사용자 메시지 | 로그 레벨 |
|------|------|-------------|----------|
| 4xx | API key 오류, sender 미검증, IP 미화이트리스트 | "이메일 발송 요청이 거절되었습니다." | `error` (응답 body 통째로) |
| 5xx | Brevo 일시 장애 | "메일 서버 일시 장애입니다. 잠시 후 다시 시도해주세요." | `warn` |
| 네트워크 (RestClientException) | 타임아웃, DNS | "이메일 발송에 실패했습니다." | `error` (스택트레이스) |

**운영 주의사항**:
- Brevo `Security → Authorised IPs`에 서버 IP 등록 필수 (미등록 시 모든 호출 401)
- Sender 이메일은 Brevo에서 사전 검증 필요 (스푸핑 방지)
- 무료 티어 300/일 제한 → 일일 한도 초과 시 4xx

### 13.3 면접 질문

- Q: 외부 API 호출에 `RestTemplate` 대신 `RestClient`를 쓴 이유?
  - A: Spring 6.1+ 표준 동기 HTTP 클라이언트. `RestTemplate`은 유지보수 모드. `WebClient`는 리액티브 스택 의존성이 과함. `RestClient`는 fluent API + 동기 + 의존성 추가 불필요
- Q: 외부 API 4xx/5xx를 다르게 처리한 이유?
  - A: 4xx는 우리 코드/설정 버그(API key 오타, 미검증 sender) → `error` 로그 + 즉시 디버깅 신호. 5xx는 일시 장애 → `warn` + 재시도 가능 메시지. 알람 룰 만들 때 의미 분리됨

---

## 14. 설계 패턴 요약

| 패턴 | 적용 위치 | 면접 키워드 |
|------|-----------|-------------|
| State Machine | GameRoom 상태 전이 | WAITING→PLAYING→FINISHED |
| Observer (Pub/Sub) | WebSocket 브로드캐스트 | STOMP topic 구독 |
| Strategy | 게임 모드별 점수 계산 | 시간 기반/횟수 기반/ELO |
| Factory | 채팅 메시지 생성 | `GameRoomChat.chat()`, `.system()` |
| Singleton Cache | 금칙어 캐시 | `ConcurrentHashMap` + `@PostConstruct` |
| Template Method | 배치 실행 프레임워크 | BatchScheduler + 개별 Batch |
| Facade | GameBroadcastService | `SimpMessagingTemplate` 래핑 |
| Double-Check Lock | 정답 제출 | `if (winner != null)` + synchronized |

---

## 15. 확장 시 고려사항 (아키텍처 한계 & 개선안)

| 현재 한계 | 개선 방향 | 면접 키워드 |
|-----------|-----------|-------------|
| 단일 서버 인메모리 상태 | Redis로 게임 상태 이전 | 수평 확장, 상태 외부화 |
| 세션 기반 인증 | JWT + Redis 세션 스토어 | Stateless, Token Refresh |
| 분산 캐시 없음 | Redis/Caffeine 도입 | 캐시 전략, TTL, Invalidation |
| 동기 브로드캐스트 | 메시지 큐(RabbitMQ/Kafka) | 비동기 처리, 이벤트 소싱 |
| 단일 DB | Read Replica, Sharding | CQRS, 읽기/쓰기 분리 |
| 자체 배치 | Spring Batch / 전용 워커 | Chunk 처리, 재시작, 실패 복구 |
