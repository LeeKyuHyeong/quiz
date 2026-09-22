# 로그인 세션 저장소를 DB 로 (Spring Session JDBC) — 배포·재시작에도 로그인·방 참가 유지 (O-020) + 재연결 멈춤 수정 (O-021)
- 일자: 2026-09-22 (회사 PC, 로컬 dev 8082 + 3306 MariaDB `song`)
- 유형: 버그 수정 (P0 — 배포마다 전원 로그아웃·방 참가자 전원 유령) + 부수 결함 수정 (P1)
- 브랜치: `main` 로컬 커밋 `95da209`(세션 저장소) · `2e37cb9`(재연결) — **미푸시** (푸시 = 운영 배포. 운영 DDL 선반영이 먼저다, §7)
- 판정: **조건부** — 자동 계약 14/14 · 전체 회귀 458/459(1건은 이 PC 의 시간 경합, §5) · 로컬 실브라우저 재시작 2회 ✅. 운영 배포·첫 로그아웃·2대 구성은 🙋
- 원인 기록: records/2026-09-22_o020-restart-session-loss (같은 날 오전). 설계 배경: `multi-presence-design.md` §6 표 6·7행 정정

## 1. 결정 (개발자 2026-09-22)
| 항목 | 결정 |
|---|---|
| 저장소 | **VPS = Spring Session JDBC(기존 MariaDB)**, AWS 시연은 별도 `session-redis` 프로파일(이번 범위 밖). 오전의 "VPS 에도 Redis" 는 메모리 3.8GiB·컨테이너 추가 부담으로 다시 뒤집음 — 09-18 "VPS 에 Redis 안 올림" 유지 |
| 프로파일 | `prod` 는 `spring.profiles.group.prod=session-jdbc` 로 항상 포함. `dev`·`test` 는 `SessionAutoConfiguration` 제외 = 메모리. 로컬 확인은 `dev,session-jdbc` |
| 기존 테스트 | `SessionLifecycleContractTest` 설정 방식(유휴 한도 주는 길)·대기 시간 수정 허가. 계약 3번의 "만료 직후 아직 열려 있다" assert 1줄 삭제 허가(§4) |
| 범위 | `AdminController` 의 `adminMember`(Member 엔티티)·`admin` 세션 속성 삭제 포함(직렬화 불가 + 비밀번호 해시 노출). **O-021 은 범위 예외**(이번 수정의 가치를 무력화) |

## 2. 선택마다 공격 (심각 = 운영 장애·로그인 불가·유령 재발·보안 노출)
| # | 공격 | 판정 | 대응 |
|---|---|---|---|
| 1 | 새 색이 뜰 때 `SPRING_SESSION` 없음 → 세션 만드는 첫 요청부터 500, 헬스는 UP 이라 전환됨 → 전체 장애 | **심각(무완화)** | 절차: 운영 DDL 을 먼저 실행하고 확인한 뒤 push (§7). `initialize-schema=embedded`(H2 만 자동) 유지 |
| 2 | 세션 만료 시 WS 닫힘이 사라짐 — 앱 코드 0건, Tomcat `WsSessionListener` 가 해 주던 것. Spring Session JDBC 는 이벤트 발행 0건(소스 확인) | 중 | `WebSocketHttpSessionGuard`(session-jdbc 만): 핸드셰이크에 HTTP 세션 ID 를 싣고 30초(테스트 300ms)마다 저장소에 없으면 닫음 |
| 3 | `/auth/validate-session` 이 세션을 연장하면 계약 2 재발 | 중 → 해당 없음 | 소스 확인: `isRequestedSessionIdValid()` 는 메모리상 시각만 바꾸고 `commitSession` 은 `getSession()` 을 부른 요청만 저장. 계약 테스트 2 통과로 확인 |
| 4 | JDK 직렬화 — 세션에 실리는 클래스가 바뀌는 배포는 기존 행 역직렬화 실패 | 중 | 그런 배포 때 `TRUNCATE SPRING_SESSION_ATTRIBUTES; TRUNCATE SPRING_SESSION;`(= 전원 로그아웃 1회). 직렬화 대상 전수 확인: 로그인 주체·SecurityContext·CSRF·SavedRequest·솔로 게임 List/Map/String/Integer ✅, `Member` ✗ → 삭제 |
| 5 | `PRINCIPAL_NAME VARCHAR(100)` 과 이메일 길이 | 경 | member.email 도 100. 우리 DDL 은 255 |
| 6 | 요청마다 SELECT+UPDATE 1 | 경 | quiz-db 94MiB/256M, `max-connections=50`. 만료 행은 매분 cron |
| 7 | 쿠키 `JSESSIONID`→`SESSION`, 첫 배포 전원 로그아웃 1회 | 경 | JS·nginx·문서에 JSESSIONID 참조 0건. `deleteCookies` 두 이름 |
| 8 | `SessionRegistryImpl` 을 그대로 두면 재시작 뒤 레지스트리가 비어 오늘 증상 그대로 | **심각(놓치면)** | `SpringSessionBackedSessionRegistry` 로 교체(`SessionStoreConfig`). `getAllPrincipals` 미지원(소스 확인) → `MemberSessionService` 는 회원으로 principal 을 만들어 조회 |
| 9 | 유휴 3초를 `HttpSessionListener` 로 주면 JDBC 에서 안 걸림 | 중 | 저장소마다: 메모리는 리스너(Tomcat 은 `server.servlet.session.timeout` 을 분 단위 올림 → 1분이 됨, 실측), JDBC 는 `spring.session.timeout` |
| 10 | Boot 3 에 `store-type=none` 없음 | 중 | dev·test `spring.autoconfigure.exclude`, `session-jdbc` 가 빈 값으로 되돌림(뒤 프로파일이 이김) |
| 11 | test 가 메모리면 JDBC 경로를 안 탐 | 중 | `SessionLifecycleJdbcContractTest`(H2 자동 스키마) — Redis 없이 CI 에서 저장소 경로 검증 |
| 12 | `adminMember` 읽는 곳 | 경 | Java·템플릿 0건 |

## 3. 구현
- `pom.xml` spring-session-jdbc · `application.properties` 프로파일 그룹·`spring.session.timeout` · `application-dev.properties`/`test application.properties` 제외 · `application-session-jdbc.properties` 신규 · `sql/schema.sql` 두 테이블
- `SessionStoreConfig`(신규): `!session-jdbc` → `SessionRegistryImpl`+`HttpSessionEventPublisher`, `session-jdbc` → `SpringSessionBackedSessionRegistry`. `SecurityConfig` 는 주입, `deleteCookies("JSESSIONID","SESSION")`
- `MemberSessionService`: 회원 ID → `Member` → `CustomUserDetails`(비밀번호 지움) 로 `getAllSessions` — 메모리(equals=회원 ID)·DB(이름=이메일) 둘 다 맞음
- `WebSocketHttpSessionGuard`(신규, session-jdbc) + `WebSocketConfig`: `HttpSessionHandshakeInterceptor`(세션 ID 만 복사), `addDecoratorFactory`
- `AdminController`: 세션 속성 2개 삭제
- `ws-client.js`(O-021): `socket.onclose` 가 CONNECTED 전 실패도 `_handleDisconnect`, `disconnect()` 는 `closing` 표시

## 4. 자동 검증
| 항목 | 결과 |
|---|---|
| `SessionLifecycleContractTest` 7건 (메모리) | ✅ 7/7 |
| `SessionLifecycleJdbcContractTest` 7건 (`test,session-jdbc`, H2) — 유휴 만료·상태 확인 비연장·**WS 만으로 비유지 + 만료 시 WS 닫힘(가드)**·keepalive 유지·중복 로그인 감지·401 JSON·익명 무쿠키 | ✅ 7/7 |
| 계약 3번 assert 삭제 | "만료 후 5초에 아직 열려 있다"(Tomcat 의 게으른 감지 서술) — 가드는 능동 감지라 먼저 닫음. 계약 문장·나머지 assert 불변. 삭제 전 JDBC 6/7 로 이 줄만 실패 확인 |
| `MemberSessionServiceTest` 3건 (재작성) | ✅ |
| `SecurityFilterChainTest` 13건 | ✅ (테스트 설정에 레지스트리 빈 추가 — 수동 조립이라 새 생성자 인자 필요) |
| 소스 대조 (spring-session 3.4.1, boot 3.4.1 sources) | `commitSession`/`isRequestedSessionIdValid` · `getAllPrincipals` Unsupported · `determineTimeout` 폴백 · `continueOnError(true)` · JDBC `publishEvent` 0건 · 쿠키명 `SESSION` |
| 전체 회귀 `./mvnw test` | **459건 중 458 ✅**, 1 ❌ `LoginAttemptLimitTest.loginRequests_areRateLimitedPerIp` — §5 |

## 5. 전체 회귀 실패 1건 — 이번 변경과 무관한 시간 경합
로그인 20회 뒤 21번째가 429 대신 200. 실패 테스트 소요 3.28초(전체)·3.42초(단독) — 버킷이 greedy 보충(분당 20 = 3초당 1토큰)이라 **20회가 3초를 넘기면 토큰이 하나 돌아온다.** 이 회사 PC 가 느려 드러남(같은 코드의 첫 전체 회귀에서는 통과, 집 PC 에서도 통과). 레이트리미터·테스트 모두 손대지 않음. O-023 으로 등록.

## 6. 로컬 실브라우저 — O-020 절차 그대로, 수정 후 (`dev,session-jdbc`, 방 `RNDL5T`, 관리자 #8 방장 / 트루본짱 #1)
| # | 확인 | 결과 |
|---|---|---|
| 0 | 기동 후 쿠키 | ✅ `Set-Cookie: SESSION=…; HttpOnly; SameSite=Lax` (JSESSIONID 아님 = JDBC 활성) |
| 1 | 재시작 1회차 (12:51:13 종료 → 12:51:50 기동, 37초) | ✅ 두 탭 `isLoggedIn:true`, 대기실 유지, 참가자 2명, 세션 무효 토스트 없음, `Room leave applied` 없음 · ❌ **WS 는 `attempt 1/5` 뒤 멈춤, 폴백도 없음 → O-021 확정** |
| 2 | O-021 수정 후 재시작 2회차 (13:00:21 종료 → 13:00:42 기동, 21초) | ✅ 두 탭 `Connection attempt failed`×3 → **5차에 `Connected to /topic/room/RNDL5T`**, `attempts:0`·`subscription` 있음, 로그인·참가자 유지, 나가기 로그 없음 |
| 3 | DB | 🙋 §7 조회 |

관찰: 재시도 예산 1+2+4+8+16 = 31초. 운영 blue/green 은 다운타임 없이 전환되므로 1차에서 붙지만, 31초 넘게 내려가면 폴링 폴백(끊김 나가기는 폴링 GET 이 취소하므로 유령은 안 됨, 푸시만 없음).

## 7. 남은 것
| 항목 | 상태 | 절차 |
|---|---|---|
| dev DB 증거 | 🙋 | `SELECT SESSION_ID, PRINCIPAL_NAME, FROM_UNIXTIME(LAST_ACCESS_TIME/1000) FROM SPRING_SESSION;` → 2행(a@a.com, 트루본짱 이메일). 참가자: `RNDL5T` member 1·8 JOINED·WAITING(정상 참가자) |
| **운영 DDL 선반영** | 🙋 **push 전 필수** | VPS `docker exec -i quiz-db mariadb -u… song < (schema.sql 끝 두 CREATE TABLE)` → `SHOW TABLES LIKE 'SPRING_SESSION%';` 2행 → 그 뒤 push |
| 운영 배포·첫 로그아웃 1회 | 🙋 | 전환 뒤 로그인 → `SPRING_SESSION` 1행 → 아무 커밋 push 로 재배포 → **로그인 유지·대기실 유지** 확인(= 운영 O-020 닫힘) |
| 운영 세션 만료 WS 닫힘 | 🙋 | 로그아웃(`/auth/security-logout`) 뒤 30초 안에 `docker logs` 에 `WebSocket closed: HTTP session ended` |
| 재시작 중 창을 닫은 사람 | ⬜ 한계 | 서버가 내려간 사이 닫힌 탭은 신호도 구독도 없음 → 정리 배치 몫. O-022 |
| presence·언로드 토큰은 여전히 메모리 | ⬜ 범위 밖 | 2대 구성(AWS)은 멀티 미대응(09-18) |
| 세션 클래스 변경 배포 규칙 | 기록 | §2-4, runbook §9 |

## 8. checklist 점검
- 영향 범위: `SessionRegistry` 주입처(`SecurityConfig`·`SessionCheckFilter`·`MemberSessionService`), `HttpSession` 사용처 전수(솔로 게임 4종·관리자), WS 핸드셰이크, 로그아웃 쿠키, 테스트 수동 조립 1곳. 설정 키는 Boot 3.4.1 소스로 확인(`spring.session.timeout`·`spring.session.jdbc.*`)
- 예외 삼킴 없음. Mock 은 단위 테스트 경계(레지스트리·리포지토리)에만
- 운영: 첫 배포 순서(DDL → push), 첫 로그아웃 1회, 직렬화 클래스 변경 시 truncate — runbook §9
