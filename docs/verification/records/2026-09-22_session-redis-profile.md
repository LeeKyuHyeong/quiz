# AWS 시연용 세션 저장소 프로파일 `session-redis` — Redis(ElastiCache) 에 로그인 세션
- 일자: 2026-09-22 밤 (집 PC)
- 유형: 기능 추가 (P1 — VPS 운영에는 안 켜지지만 같은 배포본에 실리므로 prod 무영향이 P0 조건)
- 브랜치: `main` (로컬 커밋, 푸시는 개발자 확인 후 — 코드 변경이라 main 푸시 = VPS 재배포)
- 판정: **수용 가능** — 자동 481건 0 실패(Redis 계약 9·격리 4 포함), 로컬 실브라우저 재시작 유지 ✅, prod 경로(`dev,session-jdbc`) 기동·Redis 무접촉 ✅, **운영 배포 run 35740318687 Success(23:32:46, CI 에서 Redis 계약 9/9 실제 실행·481/481) + 외부 Smoke ✅**(§6). 운영 로그의 Redis 문자열 0건만 🙋. AWS 실기동은 다음 단계(본인: 내일)
- 배경: SM 지원 결정 3(b) "AWS 프리티어에 quiz 를 EC2+RDS 로 올려 보기"(career jd-map 09-18) + 09-22 결정 "VPS 는 Spring Session JDBC, Redis 는 AWS 시연 프로파일만"(records/2026-09-22_session-store-jdbc §1). VPS 를 AWS 로 옮기는 게 아니다 — 새 계정 Free plan 은 크레딧 $100~200·최대 6개월이고 그 뒤는 월 $30~60, VPS 는 월 3만원(본인 09-22).

## 1. 결정 (개발자 2026-09-22 밤)
| 항목 | 결정 |
|---|---|
| 범위 | quiz 만, 서울 리전. `session-redis` 프로파일(로그인 세션만 Redis). **요청 제한·presence·언로드 토큰은 그대로 인스턴스 메모리**(09-18: 2대 시연은 멀티 미대응) |
| 테스트 | Redis 계약 테스트는 **Testcontainers(실제 `redis:7-alpine`)**. Docker 없는 회사 PC 는 그 클래스만 건너뜀(`disabledWithoutDocker`), CI(ubuntu)·집 PC 는 실행. (대안 embedded-redis 는 Windows 바이너리가 Redis 5 라 제외) |
| 푸시 | prod 무영향을 테스트로 고정 + 로컬 `dev,session-jdbc` 기동 확인 뒤, 푸시는 확인받고 |
| 시연 기본값 | EC2 t4g.small 1대 → 마지막에 2대째, RDS MariaDB db.t4g.micro, ElastiCache cache.t4g.micro 1노드(TLS), 예산 알림 $20 — 콘솔 단계에서 재확인 |

## 2. AC
```
[정상] dev,session-redis 로 기동하면 로그인 세션이 Redis 에 저장되고(쿠키 SESSION), 앱을 재시작해도 로그인·대기실 참가가 유지된다.
[정상] 세션 수명 계약 9건이 Redis 저장소에서도 같다 (R-019).
[정상] 1계정 1세션·관리자 강제 종료·비밀번호 재설정 뒤 세션 만료가 Redis 에서도 된다 (principal 인덱스 = indexed 저장소).
[예외] Redis 가 없거나 끊기면 /actuator/health 가 DOWN 이고 요청은 실패한다. 폴백 없음.
[노출] session-redis 가 아닌 프로파일(prod·dev·test)에는 Redis 연결·헬스 지표가 생기지 않는다 → VPS 배포 영향 0.
[연쇄] 요청 제한·presence·언로드 토큰은 그대로 인스턴스 메모리.
[경계] ElastiCache 는 CONFIG 명령이 막혀 있어 configure-action=none. 테스트도 같은 설정.
```

## 3. 구현 (빨강 → 초록)
- **빨강에서 잡힌 것 (구현 전 `SessionStoreProfileTest` 실행)**: Redis 클라이언트를 클래스패스에 올리기만 해도 ① `test` 컨텍스트에 `redisConnectionFactory` 가 생기고 ② **`session-jdbc`(= prod) 가 기동 실패** — `SessionAutoConfiguration` 은 Redis 설정을 JDBC 보다 먼저 가져오므로 Redis 저장소(기본형, 인덱스 없음)가 잡히고 `FindByIndexNameSessionRepository` 가 없어 `SessionStoreConfig` 가 죽는다. 의존성만 추가해 푸시했으면 VPS 새 색이 뜨지 못했다(헬스 실패 → 전환 안 됨 → 배포 실패). → Redis 자동구성 3개(`RedisAutoConfiguration`·`RedisRepositoriesAutoConfiguration`·`RedisReactiveAutoConfiguration`)를 **기본에서 제외**하고 `session-redis` 만 되돌린다. `spring.autoconfigure.exclude` 는 프로파일 파일이 통째로 덮어쓰므로 dev·test·session-jdbc 도 같은 목록을 든다.
- `pom.xml`: `spring-session-data-redis`·`spring-boot-starter-data-redis`(Lettuce)·`org.testcontainers:testcontainers-junit-jupiter`(test). **Testcontainers 는 Boot BOM 의 1.20.4 가 아니라 2.0.5**(`<testcontainers.version>`): 집 PC Docker Engine 29.4.2 는 API 최소 1.44 를 요구하는데 1.x 의 docker-java 는 1.32 로 붙어 `/info` 가 400(빈 본문) → "Could not find a valid Docker environment". 수정은 2.x 에만(testcontainers-java #11212·#11216, 2.0.2). 2.x 는 JUnit 5 아티팩트가 `testcontainers-junit-jupiter` 로 바뀜, 패키지 `org.testcontainers.junit.jupiter`·`org.testcontainers.containers.GenericContainer` 는 그대로
- `application-session-redis.properties`(신규): `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`/`REDIS_SSL` 환경변수, connect/command 타임아웃 2초(Lettuce 기본 60초 매달림 방지), `repository-type=indexed`(principal 인덱스), `namespace=quiz:session`, `configure-action=none`(ElastiCache)
- `SessionStoreConfig`: 메모리 `!session-jdbc & !session-redis`, 저장소 레지스트리 `{session-jdbc, session-redis}` (클래스명 `Jdbc`→`Store`)
- `WebSocketHttpSessionGuard`: `{session-jdbc, session-redis}` — Redis 는 키스페이스 이벤트를 켜야만 만료 이벤트가 오고 ElastiCache 는 앱이 못 켜므로, JDBC 와 같이 주기 확인으로 닫는다
- 테스트(신규): `SessionLifecycleRedisContractTest`(부모 계약 9건을 `session-redis` + Testcontainers 로) · `SessionStoreProfileTest`(test = Redis 빈·세션 저장소·가드 없음 / session-jdbc = JDBC 저장소·Redis 빈 없음·가드 있음 / session-redis = indexed Redis 저장소·가드 + 헬스 UP → 컨테이너 정지 → DOWN)
- **발견 2 (헬스)**: Redis 를 멈추면 `/actuator/health` 가 503 DOWN 이 아니라 **500** — 스택: `SessionRepositoryFilter.commitSession → RedisIndexedSessionRepository.save → RedisCommandTimeoutException(2초)`. 헬스 엔드포인트에 닿기 전에 이 요청의 세션(익명 GET 에도 생김)을 저장하다 죽는다. 배포 게이트·LB 헬스 체크는 200 만 통과시키므로 "Redis 가 죽으면 트래픽을 받지 않는다" 는 성립하지만, 헬스가 저장소 상태를 말하지 못한다. JDBC(prod)도 DB 가 죽으면 같은 모양일 것(미실측). 수정 후보는 `/actuator/**` 를 세션 없는 체인으로 — 범위 밖, O-024
- Boot 3.4.1 소스 확인: `RedisSessionProperties`(`repository-type` DEFAULT/INDEXED, `configure-action` NOTIFY_KEYSPACE_EVENTS/NONE, `namespace`, `cleanup-cron`), `RedisSessionConfiguration`·`RedisHealthContributorAutoConfiguration` 모두 `@ConditionalOnBean(RedisConnectionFactory)` — 팩토리를 안 만들면 세션 저장소도 헬스 지표도 안 생긴다

## 4. 자동 검증
| 항목 | 결과 |
|---|---|
| 빨강 (의존성만 추가, 설정 전) | ✅ `SessionStoreProfileTest$InMemory` 실패(`["redisConnectionFactory"]`) · `$Jdbc` 컨텍스트 기동 실패(`FindByIndexNameSessionRepository` 없음) |
| `SessionStoreProfileTest` test·session-jdbc (Docker 없이) | ✅ 2/2 · Redis 중첩 2건은 Docker 없을 때 Skipped 2 (설계대로) |
| `SessionLifecycleJdbcContractTest` 9건 (prod 경로 회귀) | ✅ 9/9 |
| `SessionLifecycleRedisContractTest` 9건 (Testcontainers `redis:7-alpine`, 컨테이너 기동 0.36초) — 유휴 만료·상태 확인 비연장·**WS 만으로 비유지 + 만료 시 WS 닫힘(가드)**·keepalive 유지·중복 로그인 감지·401 JSON·익명 무쿠키·익명 쿠키 NOT_LOGGED_IN·다른 탭 로그아웃 | ✅ 9/9 (36.4초) — `configure-action=none` 그대로, 키스페이스 이벤트 없이 |
| `SessionStoreProfileTest$Redis` 2건 | ✅ 2/2 — indexed 저장소·가드 ✅ / 헬스: Redis 살아 있음 200 `UP` → 컨테이너 정지 → **500**(503 DOWN 이 아님 — §3 발견 2, O-024). 계약은 "UP 을 돌려주지 않는다" 로 두고 통과 |
| 전체 회귀 `./mvnw test` (집 PC, Docker 있음) | ✅ **481건 0 실패 0 Skipped**, 63 파일, 3분 32초 (468 + 신규 13: Redis 계약 9 + 격리 4) |

## 5. 로컬 실브라우저 (`dev,session-redis`, 로컬 `quiz-redis` 컨테이너 `redis:7-alpine`, MariaDB 3306 `song`)
| # | 확인 | 결과 |
|---|---|---|
| 0 | 기동 후 쿠키·헬스·키 | ✅ `Set-Cookie: SESSION=…; HttpOnly; SameSite=Lax`, `/actuator/health` `{"status":"UP"}`, Redis 에 `quiz:session:sessions:<id>`·`…:expires:<id>` 키 |
| 1 | 익명 세션 재시작 유지 (curl) | ✅ 로그인 페이지 1회 → 쿠키·CSRF 토큰 기록 → 앱 정지·재기동 → 같은 쿠키로 요청: **새 Set-Cookie 없음, CSRF 토큰 동일** = 세션 내용을 Redis 에서 다시 읽음 |
| 2 | 로그인 세션 재시작 유지 (개발자 로그인 `a@a.com`, 방 `4VCYNA` 생성 → 대기실 탭 그대로 두고 앱 정지 23:19:10 → 기동 23:19:21) | ✅ 재기동 27초 뒤 같은 탭: `isLoggedIn:true` 관리자, 방 `WAITING` 참가자 관리자#8 방장, 로그아웃 토스트 없음. 콘솔 `[WS] Connection closed → attempt 1~3 failed → attempt 4 Connected`(O-021 수정 경로). Redis 에 principal 인덱스 키 `quiz:session:index:…PRINCIPAL_NAME_INDEX_NAME:a@a.com` 존재(indexed 저장소) |
| 3 | prod 경로 회귀 — `dev,session-jdbc` 기동 (같은 배포본에서 Redis 가 끼어들지 않는지) | ✅ `Set-Cookie: SESSION`, 헬스 UP, `SPRING_SESSION` 행 3, **Redis `client list` 1(= redis-cli 자신)·`dbsize` 47 그대로** = Redis 무접촉. 이 PC 로컬 DB 에 `SPRING_SESSION` 두 테이블이 없어 첫 요청이 500(§2-1 의 알려진 조건) → schema.sql 의 DDL 을 로컬 DB 에 실행 뒤 통과(mariadb 클라이언트 컨테이너, `--skip-ssl`) |

환경 메모: Docker Desktop 4.72 가 전날 크래시로 남긴 유닉스 소켓 파일(`%LOCALAPPDATA%\Docker\run\dockerInference`·`userAnalyticsOtlpHttp.sock`·`%LOCALAPPDATA%\docker-secrets-engine\engine.sock`)을 지우지 못해(오류 1920) 기동 실패 → 디렉터리째 `*.stale-*` 로 이름을 바꾸고 재실행해 해결. Docker Engine 29.4.2 는 Testcontainers 2.0.2+ 필요(§3).

## 6. 남은 것
| 항목 | 상태 | 절차 |
|---|---|---|
| main 푸시 → VPS 재배포 Smoke | ✅ 푸시 `4f4ab8b` 23:26 → run 35740318687 build(CI Docker 에서 `SessionLifecycleRedisContractTest` 9/9, 전체 481/481)·deploy Success 23:32:46 → 23:33:03 외부 `GET /` **200**, `/auth/login` **`Set-Cookie: SESSION`**(JDBC 그대로), `/actuator/health` 403(nginx deny 그대로) | 🙋 남은 것: `docker logs $(docker ps -q --filter name=app-) 2>&1 \| grep -ci "redis\|lettuce"` → 0 기대 |
| AWS 실기동 (EC2 + RDS + ElastiCache, `session-redis`) | ⬜ 다음 단계 | 별도 기록. ElastiCache TLS 면 `REDIS_SSL=true`, 인증 토큰이면 `REDIS_PASSWORD` |
| 저장소가 죽을 때 헬스 500 (503 DOWN 아님) | ⬜ O-024 | `/actuator/**` 를 세션 없는 체인으로 — 범위 밖 |
| 회사 PC 에서 Redis 테스트는 Skipped | 기록 | Docker 없음. CI 가 실행하므로 push 전 확인은 CI 결과로 |
| Testcontainers 2.0.5 와 Boot BOM(1.20.4) 차이 | 기록 | Boot 를 올릴 때 `<testcontainers.version>` 덮어쓰기가 여전히 필요한지 확인 |

## 7. checklist 점검
- 영향 범위: `SessionRegistry`·`SessionRepository` 주입처(`SecurityConfig`·`SessionCheckFilter`·`MemberSessionService`·`WebSocketHttpSessionGuard`) 그대로, 프로파일 조건만 확장. `spring.autoconfigure.exclude` 를 든 파일 4곳(base·dev·test·session-jdbc) 전부 같은 목록 — `SessionStoreProfileTest` 가 고정. JS·템플릿·nginx 변경 없음(쿠키명 `SESSION` 동일).
- 설정 키: Boot 3.4.1 `RedisSessionProperties`·`spring.data.redis.*` 를 jar 로 확인(`javap`). `RedisReactiveAutoConfiguration` 은 클래스패스에 없지만 자동구성 후보 목록에 있어 제외 지정이 오류가 아님(기동 확인).
- 예외 삼킴 없음. Mock 은 없음(실제 Tomcat·Redis·H2).
- 보안: Redis 비밀번호·호스트는 환경변수만, 파일에 값 없음. `namespace` 로 키 접두어 분리.
- 운영: prod 는 이 프로파일이 켜지지 않는다. 배포 규칙(runbook §9)은 그대로 — 세션 클래스 변경 시 Redis 는 `FLUSHDB`(시연 전용이라 절차 불필요).
