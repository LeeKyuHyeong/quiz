# LLM 코딩 가이드라인 (행동 원칙) — 전문

> 2026-09-16 `CLAUDE.md`에서 분리. 요약은 CLAUDE.md "행동 원칙", 원문·근거·프로젝트 적용 예시 전체는 이 문서.

LLM 코딩에서 흔히 발생하는 실수를 줄이기 위한 행동 가이드라인. Andrej Karpathy의 LLM 코딩 함정 관찰을 기반으로 함.

**Tradeoff:** 이 가이드라인은 **속도보다 신중함**에 무게를 둔다. 사소한 작업(오타 수정, 명백한 한 줄 변경)에는 판단껏 유연하게 적용할 것.

### 1. Think Before Coding (코딩 전에 먼저 생각)

**가정하지 마라. 혼란을 숨기지 마라. Tradeoff를 드러내라.**

구현에 들어가기 전에:

- **가정을 명시적으로 진술**한다. 불확실하면 추측하지 말고 질문한다.
- **여러 해석이 가능하면 모두 제시**한다. 조용히 하나만 골라서 진행하지 않는다.
- **더 단순한 접근이 존재하면 말한다.** 필요할 때는 반박(push back)한다.
- **불명확하면 멈춘다.** 무엇이 헷갈리는지 명시하고 묻는다.

**이 프로젝트에서의 적용 예시:**

- **멀티플레이어 상태 머신 작업 시:** `GameRoom`은 방 상태 `RoomStatus`(`WAITING → PLAYING → FINISHED`)와 라운드 단계 `RoundPhase`(`PREPARING → PLAYING → RESULT`) 2단으로 흐른다. 한 phase 처리만 고치라는 요청이라도 `GameRoomService`·`MultiGameService` + WebSocket push(`GameBroadcastService`) + REST 액션(`/round-ready` 등) + 클라이언트 JS(`ws-client.js`, 연결 실패 시 polling fallback)가 동시에 영향. 어디까지 영향이 가는지 먼저 확인한다.
- **AnswerValidation 작업 시:** `AnswerValidationService`(정규화) + `AnswerGeneratorUtil`(영→한 음역 변환) + `SongAnswer` 테이블(수동 정답)이 3중으로 얽혀 있음. "답이 인정 안 돼요"라는 요청은 어느 layer 문제인지 확인하고 패치한다. 추측으로 정규화 로직을 손대지 않는다.
- **배치 작업 추가/수정 시:** `BatchScheduler`에 등록된 24개 잡 중 데이터 선후·실행 시각이 얽힌 잡이 있는지(`BatchService` seed의 cron 기본값·대상 엔티티) 확인 후 진행. 신규 배치는 클래스 + `BatchScheduler`의 `createTask`/`executeManually` 분기 + `BatchService` seed 세 곳을 함께 맞춘다.
- **Multi-tier(LP) 로직:** 승급/강등 경계 처리는 `MultiTierService` + `LpDecayBatch` + 매치 종료 시 LP 변동 로직이 동시에 영향. 한 군데만 고치지 않는다.
- **"화면이 깨졌어요" 요청:** 어떤 모드(Solo Guess / Host / Fan Challenge / Genre Challenge / Retro / Multi)인지, 어떤 테마(라이트/다크/`.game-page`)인지, 어떤 브레이크포인트(PC/768px/480px)인지 먼저 확인한다.

### 2. Simplicity First (단순함이 먼저)

**문제를 해결하는 최소 코드. 투기적인 것은 없다.**

- 요청되지 않은 기능은 추가하지 않는다.
- 1회용 코드에 추상화 계층을 만들지 않는다.
- 요청되지 않은 "유연성"이나 "설정 가능성"을 끼워넣지 않는다.
- 발생할 수 없는 시나리오에 예외 처리를 하지 않는다.
- 200줄로 쓴 것이 50줄로 가능했다면, 다시 쓴다.

자문: **"시니어 엔지니어가 이건 오버엔지니어링이라고 할까?"** 그렇다면 단순화한다.

**이 프로젝트에서의 안티 패턴:**

- 한 Service에서만 쓰는 로직을 `XxxStrategy` 인터페이스 + 구현체로 분리하지 말 것.
- Batch Job 추가하는데 새로운 추상 `AbstractScheduledJob` 만들지 말 것. `BatchScheduler` + `BatchConfig`(cron 외부화) 기존 패턴을 따른다.
- 게임 모드 추가 요청 시 "확장성"을 이유로 게임 엔진을 일반화하지 말 것. 기존 `GameSessionService` / `MultiGameService` / `FanChallengeService` 패턴을 그대로 답습한다.
- 관리자 페이지 한 곳에서만 쓰는 DTO를 굳이 entity와 양방향 매핑하지 말 것. 명확히 분리한다.
- 한 fragment에서만 쓰는 Thymeleaf 헬퍼를 굳이 `#strings` 확장으로 만들지 말 것. 인라인이면 인라인이다.

### 3. Surgical Changes (외과적 변경)

**필요한 곳만 건드린다. 내가 만든 흔적만 정리한다.**

기존 코드를 수정할 때:

- 인접한 코드, 주석, 포매팅을 "개선"하지 않는다.
- 망가지지 않은 것을 리팩토링하지 않는다.
- 내가 다르게 작성할 스타일이라도 **기존 스타일에 맞춘다.**
- 무관한 dead code를 발견하면 *언급만* 한다. 삭제하지 않는다.

내 변경이 고아(orphan)를 만들었다면:

- *내 변경*으로 인해 사용되지 않게 된 import/변수/함수만 제거한다.
- 변경 전부터 있던 dead code는 요청 없이 제거하지 않는다.

검증 기준: **변경된 모든 라인은 사용자 요청과 직접 연결되어야 한다.**

**이 프로젝트에서의 특별 주의:**

- **`common.css`의 CSS 변수 시스템(`:root`, `[data-theme="dark"]`, `.game-page`)은 의도된 디자인이다.** 무관한 작업 중에 변수 정의를 "정리"하지 않는다.
- **`SecurityConfig`의 `authorizeHttpRequests` 매처 순서**는 의존성이 있다(정적 자원·`/ws/**`·`/auth/**`·`/admin/login` `permitAll` → `/admin/**` `hasRole("ADMIN")` → `/mypage/**` `authenticated` → 나머지 `permitAll`). 새 경로 규칙을 추가하면서 기존 매처 순서를 재배열하지 않는다. (구 `AdminInterceptor`/`SessionValidationInterceptor`는 Spring Security 전환 때 제거됨, `68c9d74`)
- **Thymeleaf fragment(`fragments/header.html`, `fragments/footer.html` 등)는 모든 화면이 의존한다.** "더 깔끔하게" 변경하지 않는다.
- **`docker-compose.yml`은 푸시하면 배포 스크립트의 `git pull --ff-only`로 서버에 그대로 반영된다**(아래 CI/CD 참고). blue/green 두 벌이 `x-app-common` 앵커를 공유하므로 무관한 작업 중에 compose 파일을 건드리지 않는다.
- **CI/CD 워크플로(`.github/workflows/deploy.yml`)는 한 번 맞춰놨다.** 다른 작업 중에 build step 순서나 path filter를 "최적화"하지 않는다.
- **`BatchConfig` 테이블의 cron 식**은 운영 중 DB에서 조정한다. 코드에서 cron 기본값을 무관한 작업 중에 변경하지 않는다.

### 4. Goal-Driven Execution (목표 주도 실행)

**성공 기준을 정의한다. 검증될 때까지 루프를 돈다.**

| 명령형 지시 | 목표형 변환 |
|------------|------------|
| "validation 추가해줘" | "잘못된 입력 테스트를 쓰고, 통과시켜라" |
| "버그 고쳐줘" | "버그를 재현하는 테스트/시나리오를 만들고, 통과시켜라" |
| "X를 리팩토링해줘" | "리팩토링 전후로 테스트가 모두 통과하는지 확인" |

다단계 작업은 짧은 계획을 먼저 제시:

```
1. [단계] → 검증: [확인 방법]
2. [단계] → 검증: [확인 방법]
3. [단계] → 검증: [확인 방법]
```

**이 프로젝트에서의 검증 패턴:**

- **Controller 추가:** Controller 작성 → `./mvnw test` 통과 → 가능하면 `MockMvc` 테스트로 200 / 4xx / 5xx 응답 검증.
- **JPA Repository 메서드 추가:** 메서드 작성 → dev 프로파일 실행 → 실제 데이터로 결과 row 수/내용 확인.
- **Batch Job 작업:** Job 추가/수정 → `AdminBatchController`에서 수동 trigger 또는 cron 조정 → `BatchExecutionHistory`에서 `COMPLETED` 확인 → 결과 테이블 검증.
- **JPA 쿼리 최적화("느려요"):** `EXPLAIN` 결과를 **먼저** 확인하고, 인덱스/쿼리 변경 후 다시 `EXPLAIN`으로 검증.
- **Thymeleaf 화면 변경:** `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (포트 8082) → 라이트/다크 + PC/태블릿(768px)/모바일(480px) 모두 시각 확인.
- **CSS 변경:** 라이트 + 다크 + `.game-page` 3개 테마 + PC/768px/480px 3개 브레이크포인트 = **9개 조합**을 의식해서 작성, 가능한 한 모두 확인.
- **Docker 배포 작업:** 푸시 → Actions 로그에서 헬스체크 통과·upstream 전환 확인 → 서버에서 `docker compose logs -f app-blue`(또는 `app-green`, 활성 색은 `/etc/nginx/conf.d/quiz-upstream.conf`)로 부팅 로그 확인 → 영향 받는 엔드포인트에 curl로 응답 검증. 수동 재기동이 필요하면 `app`이 아니라 활성 색 서비스명을 지정한다.

---

## 이 가이드라인이 잘 작동하고 있다는 신호

- diff에 불필요한 변경이 없다 — 요청한 변경만 나타난다.
- 오버엔지니어링으로 인한 재작성이 줄어든다 — 처음부터 단순하다.
- 실수 *후*가 아니라 구현 *전*에 명확화 질문이 온다.
- 깔끔하고 최소한의 PR — 지나가다 하는 "개선"이 없다.
