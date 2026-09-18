# 로그인 세션 수명 정리 (정지·강등 시 세션 끊기, 중복 로그인 감지, 유휴 만료 60분)
- 일자: 2026-09-18
- 유형: 버그
- 우선순위: P0 (인증·세션)
- 판정: 조건부 — 자동 검증 전부 ✅, 브라우저 화면 확인 3건이 🙋

## 1. 요청과 목적
- 사용자가 원한 것: Redis·AWS 작업 전에, 세션 구조를 분석하다 발견된 결함을 먼저 고친다.
- 개발자 확인 결과(결정 사항):
  - 중복 로그인 감지는 실제로 동작해야 한다.
  - 일반 페이지는 1시간 방치하면 로그아웃된다 (30분 → 60분). 열린 탭의 주기 확인이 세션을 연장하면 안 된다.
  - 멀티 화면에서 WebSocket 으로 연결돼 있는 동안은 로그인이 유지돼야 한다.
- 진행 중 둔 가정:
  - 권한 변경은 승격·강등 모두 대상 회원의 세션을 끊는다 (다시 로그인해야 새 권한 적용).
  - 상태 변경은 ACTIVE 가 아닌 값(BANNED·INACTIVE 등)으로 바꿀 때만 세션을 끊는다.
  - 만료 표시된 세션의 요청은 `Accept` 에 `text/html` 이 있으면 화면 이동으로 보고 302, 아니면 fetch 로 보고 401 JSON.
  - 세션이 끝났을 때의 안내 문구는 원인을 구분하지 않는다 (서버가 "다른 기기"와 "관리자 조치"를 구분할 정보를 갖고 있지 않음).

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | 관리자가 회원을 정지하면 그 회원은 기존 세션으로 더 이용할 수 없고 다시 로그인할 수도 없다 | ✅ | `MemberStateChangeSessionTest#bannedMember_losesExistingSession` |
| 2 | 권한 | ADMIN → USER 강등 시 기존 세션이 끊기고, 다시 로그인해도 `/admin/**` 는 403 | ✅ | `MemberStateChangeSessionTest#demotedAdmin_losesAdminAccess` |
| 3 | 경계 | 상태를 ACTIVE 로 바꾸는 변경은 세션을 끊지 않는다 | ✅ | `MemberStateChangeSessionTest#activatingMember_keepsSession` |
| 4 | 정상 | 다른 기기에서 로그인하면 먼저 있던 세션이 만료 표시되고, 상태 확인이 `SESSION_INVALIDATED` 를 돌려준다 | ✅ | `SessionLifecycleContractTest#secondLogin_isDetectedByFirstBrowser` |
| 5 | 예외 | 만료 표시된 세션의 fetch 는 로그인 HTML 이 아니라 401 `SESSION_INVALIDATED`, 화면 이동은 `/auth/login?expired=true` | ✅ | `#fetchWithInvalidatedSession_gets401Json`, `#secondLogin_isDetectedByFirstBrowser` |
| 6 | 정상 | 아무 요청 없이 유휴 한도를 넘기면 로그아웃된다 | ✅ | `#idleSession_expires` |
| 7 | 정상 | 열린 탭의 주기적 상태 확인은 세션을 연장하지 않는다 | ✅ | `#periodicSessionCheck_doesNotExtendSession` |
| 8 | 연쇄 | WebSocket 연결 중 주기적 `/auth/status` 호출이 있으면 유휴 한도를 넘겨도 유지된다 | ✅ | `#webSocketKeepAlive_keepsSessionAlive` |
| 9 | 연쇄 | WebSocket 연결만으로는 유지되지 않는다 (keepalive 가 필요한 이유). 만료되면 그 세션의 WebSocket 도 닫힌다 | ✅ | `#webSocketAlone_doesNotKeepSessionAlive` |
| 10 | 경계 | 비로그인 방문자의 상태 확인은 세션을 만들지 않는다 | ✅ | `#anonymousSessionCheck_createsNoSession`, dev 서버 curl |
| 11 | 노출 | 화면(JS): 다른 기기 로그인 시 30초 안에 토스트 → 로그인 페이지. 시간 초과 시 안내 1회. 비로그인 방문자는 첫 확인 뒤 폴링 중지. WS 연결 중 5분마다 keepalive, 끊기면 중지 | ✅ 로직 / 🙋 실제 브라우저 | Node 스크래치 하네스 8건 (저장소 밖, 아래 5번) / 수동 시나리오 1·2 |
| 12 | 노출 | 로그인 페이지가 `?expired=true` 일 때 안내 문구를 보여준다 | 🙋 | 수동 시나리오 3 |
| 13 | 정상 | 유휴 한도 60분 (설정값) | ✅ 설정 / ⬜ 실제 60분 대기 | `application.properties`. 실제 시간 대기는 하지 않음 — 유휴 만료 동작 자체는 #6 이 3초로 확인 |

## 3. 변경 사항
- `controller/admin/AdminMemberController` — `update-status`(ACTIVE 외)·`update-role` 뒤에 `memberSessionService.expireSessions(id)`
- `security/CustomUserDetails` — 회원 ID 기준 `equals`/`hashCode`. 없어서 `SessionRegistry` 가 같은 회원의 로그인들을 서로 다른 사람으로 취급했고 `maximumSessions(1)` 이 한 번도 동작하지 않았다
- `security/SessionCheckFilter` (신규) — `/auth/validate-session` 을 세션을 읽지 않고 세션 ID + `SessionRegistry` 로 답한다
- `security/SessionExpiredHandler` (신규) — 만료 세션 응답을 화면 이동(302)과 fetch(401 JSON)로 구분
- `config/SecurityConfig` — 상태 확인 전용 필터 체인(`@Order(0)`, 세션·익명 인증·CSRF 없음) 추가, 기본 체인 `@Order(1)`, `expiredUrl` → `expiredSessionStrategy`, `HttpSessionEventPublisher` 빈 추가(시간 초과된 세션을 레지스트리에서 제거). `authorizeHttpRequests` 매처 순서는 건드리지 않음
- `config/TomcatSessionAccessConfig` (신규) — 내장 Tomcat 인증 밸브의 `cache=false`. 기본값에서는 세션 쿠키가 달린 **모든** 요청에서 Tomcat 이 세션을 읽어 유휴 시간을 연장한다
- `controller/client/AuthController` — 컨트롤러의 `/auth/validate-session` 제거 (필터로 이동. DispatcherServlet 을 타면 FlashMap 조회가 세션을 읽는다)
- `static/js/common/common.js` — SessionManager: 로그인 확인 이력(`wasLoggedIn`)으로 시간 초과·비로그인 구분, 중복 처리 방지, 이동 경로 `?expired=true`
- `static/js/common/ws-client.js` — 연결 중 5분 주기 `/auth/status` keepalive, 끊김·disconnect 시 중지
- `static/js/client/auth-login.js` — `?expired=true` 안내 문구
- DB·설정 변경: `server.servlet.session.timeout` 30m → 60m (`application.properties`, 전 프로파일 공통). DB 변경 없음

## 4. 영향 범위 분석
- `/auth/validate-session` 호출처: `common.js` 1곳 (Java·템플릿·다른 JS 없음)
- `expiredUrl` / `SESSION_INVALIDATED` 사용처: `common.js` 의 fetch 래퍼(401 분기)와 SessionManager — 서버가 이 값을 돌려준 적이 없어 죽은 분기였고 이번에 살아남
- `new CustomUserDetails(` 호출: 운영 1곳·테스트 21곳 — 시그니처 불변이라 수정 없음
- **운영 동작이 바뀌는 것 (의도된 변경)**
  1. 같은 계정으로 두 번째 기기·브라우저에서 로그인하면 먼저 있던 쪽이 로그아웃된다. 지금까지는 설정만 있고 동작하지 않았다. 한 계정으로 브라우저 2개를 띄워 테스트하던 방식은 더 이상 안 된다 (같은 브라우저의 여러 탭은 세션이 하나라 영향 없음)
  2. 탭을 열어 두기만 하면 유지되던 로그인이 60분 무조작 시 끝난다
  3. 만료 세션으로 온 fetch·SockJS 요청은 302 대신 401 JSON 을 받는다 → ws-client 는 재접속 실패 → 폴링 폴백 → 401 → 안내 후 로그인 페이지
- 영향 없음 확인: 솔로 게임(라운드마다 요청 발생), 멀티 플레이(정답 POST + keepalive)

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 빌드 + 전체 회귀 | `./mvnw clean test` | 399 passed (기존 389 + 신규 10), 0 failed | ✅ |
| Integration | `./mvnw test -Dtest=MemberStateChangeSessionTest` (MockMvc, 실제 폼 로그인) | 3 passed. 수정 전 2건 실패 확인 | ✅ |
| Integration | `./mvnw test -Dtest=SessionLifecycleContractTest` (RANDOM_PORT, 실제 Tomcat + STOMP) | 7 passed. 수정 전 3건 실패 확인 | ✅ |
| JS 로직 | Node 스크래치 하네스 — `common.js`·`ws-client.js` 원본을 `vm` 으로 로드, 가짜 타이머·fetch 스텁 (저장소 밖. Jest 제거 결정 O-002) | 8 checks passed | ✅ |
| 로컬 실행 | dev 프로파일 기동 → curl: 비로그인 상태 확인 `NOT_LOGGED_IN`·쿠키 미생성, `/actuator/health` UP, 수정된 JS 서빙 확인 | 통과 | ✅ |
| 사용자 시나리오 | 아래 6번 | 확인 대기 (에이전트는 비밀번호 입력 불가, 브라우저 패널 localhost 접근 거부됨) | 🙋 |
| 배포 후 Smoke | 미배포 | — | ⬜ |

## 6. 수동 확인 시나리오
1. **중복 로그인 감지** — [전제] dev 기동, 브라우저 A(일반)·B(시크릿). [행동] A 에서 `test1@test.com` 로그인 후 홈에 머문다 → B 에서 같은 계정 로그인. [기대] A 에 30초 안에 "다른 기기에서 로그인했거나…" 토스트 → 1.5초 뒤 로그인 페이지, 상단에 "로그인 세션이 종료되었습니다" 문구.
2. **멀티 대기실 keepalive** — [행동] 방을 만들고 대기실에서 개발자도구 Network 탭을 연다. [기대] WS 연결 후 `/status`·`/chats` 폴링이 멈추고, 5분마다 `/auth/status` 1건, 30초마다 `/auth/validate-session` 1건.
3. **정지 회원** — [행동] 관리자(`a@a.com`)로 `test2` 를 정지 → `test2` 가 로그인해 있던 브라우저에서 아무 메뉴나 클릭. [기대] 로그인 페이지 + 종료 안내. 다시 로그인하면 "정지된 계정입니다."
- 결과: ☐ 통과 ☐ 실패

## 7. checklist 점검
- 점검함: 호출처 전수 검색(§4) / 프로파일별 설정(세션 타임아웃은 공통 파일 1곳) / 비로그인·낮은 권한 직접 호출(AC 2·10, 상태 확인 경로 POST 는 기본 체인으로 떨어져 CSRF 403 — curl 확인) / 세션 만료·소켓 끊김 후 상태(AC 5·9) / 실패 사유 표시(AC 11·12) / 로컬↔운영 차이(운영도 `java -jar app.war` 내장 Tomcat — `Dockerfile` 확인, 밸브 설정 동일 적용)
- 해당 없음: DB·매핑, 입력 경계값, 멀티 상태 머신, 동시성

## 8. 발견된 문제와 조치
| 문제 | 원인 | 조치 | 테스트 |
|---|---|---|---|
| 정지·강등된 회원이 기존 세션으로 계속 이용 | 로그인 주체가 로그인 시점 `Member` 를 들고 있는데 상태·권한 변경 시 세션을 끊지 않음 | 변경 직후 `expireSessions` | R-017 |
| 1계정 1세션이 동작하지 않음 | `CustomUserDetails` 에 `equals`/`hashCode` 없음 | 회원 ID 기준으로 정의 | R-018 |
| 중복 로그인 감지 화면 기능이 죽어 있음 | 서버가 `SESSION_INVALIDATED` 를 돌려주는 곳이 없음 (`68c9d74` 에서 인터셉터 제거 후) | 필터·만료 전략에서 반환 | R-018 |
| 탭을 열어 두면 세션이 만료되지 않음 | 30초 주기 확인이 세션을 읽음 — ① ConcurrentSessionFilter ② 익명 인증 객체 생성 ③ DispatcherServlet FlashMap ④ Tomcat 인증 밸브 캐시, 네 곳 모두 | 전용 필터 체인 + 밸브 `cache=false` | R-019 |
| `SessionRegistry` 에 끝난 세션이 계속 쌓임 | `HttpSessionEventPublisher` 미등록 | 빈 등록 | — (부수 효과, R-019 의 `idleSession_expires` 가 `NOT_LOGGED_IN` 으로 확인) |

분석 중 "WebSocket 연결 중 세션 만료" 를 버그로 보고했다가 철회했다 — 브라우저에서는 위 30초 확인이 세션을 살리고 있어 발생하지 않았다. 그 확인이 연장을 멈추는 이번 변경부터 keepalive 가 필요해져 함께 넣었다.

## 9. 미검증 영역과 남은 위험
- 실제 브라우저 화면 3건 (🙋, O-009)
- 운영 배포 후 Smoke (⬜, O-010). 배포 시 기존 로그인 세션은 blue/green 전환으로 어차피 끊긴다
- 닉네임을 바꿔도 같은 세션에는 옛 값이 남는 문제 — 지금은 닉네임 변경 기능이 없어(호출처 0) 발생하지 않음. 로그인 주체 슬림화 작업에서 처리 (O-011)
- `X-Forwarded-For` 첫 값 신뢰 (레이트리미터 우회 가능성) — 범위 밖, 별도 결정 (O-012)

## 10. Regression 등록
- R-017, R-018, R-019
