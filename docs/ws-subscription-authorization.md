# WebSocket 구독 인가 + 폴링 API 참가자 검사

> 작성일: 2026-09-15 · 기준 커밋: `6ab32a1` (main) · 상태: **✅ 완료 — 커밋 `06a6e4a`(인가) + `a155717`(대기실 튕김 버그), 2026-09-15 운영 배포·엔드포인트 확인(§6-3). 운영 실방 진행 확인만 사용자 몫**
>
> 출발점: 면접 답변 점검 중 발견. "방 코드만 알면 비로그인 상태로도 그 방의 라운드 정보와 채팅을 전부 받을 수 있다"(구독 인가 부재) + "`WebSocketAuthInterceptor`가 실제로는 한 번도 인증을 세팅하지 않는다"(데드 코드).
>
> 이 문서는 §1~§4 가 개발 전 분석·계획, §5~§7 이 개발 직후 채우는 결과·검증·이력서 정리다. §5~§7 의 `[ ]` 와 `____` 를 개발 후 채운다.

---

## 1. 현재 상태 (사실, 코드 근거)

### 1-1. `WebSocketAuthInterceptor` 는 도달 불가 코드

| 항목 | 근거 |
|---|---|
| 세션 속성에서 `HTTP_SESSION` 키를 읽는다 | `config/WebSocketAuthInterceptor.java:34` |
| 그 키를 넣는 코드가 프로젝트에 없다 | `grep -rn HTTP_SESSION src/main` → 인터셉터 1곳뿐 |
| Spring 의 세션 복사 도구 `HttpSessionHandshakeInterceptor` 는 키가 `HTTP.SESSION.ID` 이고, 그마저 등록돼 있지 않다 | `WebSocketConfig.registerStompEndpoints` 에 `addInterceptors` 없음. 상수는 로컬 `spring-websocket-6.2.1.jar` 바이트코드에서 확인 |
| 따라서 `httpSession == null` → `accessor.setUser(auth)` 분기는 실행된 적 없음 | — |
| `SecurityConfig` 주석은 "STOMP CONNECT 인증은 WebSocketAuthInterceptor 가 세션 기준으로 처리한다"고 적혀 있음 | `config/SecurityConfig.java` csrf 블록 주석 → **문서와 코드 불일치** |
| CLAUDE.md 도 "`WebSocketConfig` + `WebSocketAuthInterceptor` (STOMP 인증 전파)" 로 설명 | 정정 대상 |

### 1-2. 그런데 Principal 은 이미 핸드셰이크에서 전파된다

- Spring `AbstractHandshakeHandler.determineUser()` 는 핸드셰이크 요청의 `getPrincipal()` 을 WebSocket 세션 사용자로 넣는다. SockJS HTTP 전송(xhr 계열)도 `AbstractHttpSockJsSession` 이 같은 principal 을 보관한다.
- `/ws/**` 는 `permitAll` 이지만 Spring Security 필터 체인은 통과하므로, 로그인 사용자의 요청에서 `HttpServletRequest.getUserPrincipal()` 은 `Authentication` 을 돌려준다.
- 결론: **인터셉터를 "고쳐서 동작시키는" 것은 이미 되는 일의 중복 구현.** CONNECT 블록은 삭제하고, 이 클래스를 SUBSCRIBE 인가 자리로 재활용한다. (§3 결정 0 에서 실측으로 확정)

### 1-3. 토픽 구독과 폴링 GET 모두 인가가 없다

| 경로 | 인증 | 참가자 검사 | 위치 |
|---|---|---|---|
| STOMP SUBSCRIBE `/topic/room/{code}` | 없음 | 없음 | 서버 측 구독 검사 코드 없음 |
| GET `/game/multi/room/{code}/round` | 없음 | 없음 | `MultiGameController.getRoundInfo` (1018행 부근) |
| GET `/game/multi/room/{code}/chats` | 없음 | 없음 | `MultiGameController.getChats` (1091행 부근) |
| GET `/game/multi/room/{code}/status` | 선택 | 강퇴 감지용으로만 사용, 응답은 비로그인에도 반환 | `MultiGameController.getRoomStatus` (714행 부근) |
| GET `/room/{code}/play`, `/result` 페이지 | 있음 | 있음(리다이렉트) | 페이지만 막혀 있고 **데이터 API 는 열림** |

WebSocket 구독만 막으면 비참가자는 `ws-client.js` 의 재시도 5회(약 31초) 뒤 폴링 폴백으로 넘어가 같은 데이터를 GET 으로 받는다. → **구독 인가와 폴링 GET 검사는 한 세트.**

### 1-4. 인가 판단에 쓸 데이터는 이미 있다

- `GameRoomParticipant.status` = `JOINED` / `PLAYING` / `LEFT`. 강퇴도 `LEFT` (`GameRoomService.kickParticipant`).
- 방장은 방 생성 시 참가자로 저장(`createRoom`), 재시작 시 참가자 상태가 `JOINED` 로 초기화(`restart`). → 대기실·플레이·결과 페이지 모두 **"JOINED 또는 PLAYING 인 참가자"** 로 통일 판단 가능.
- 결과 페이지는 `leave-to-lobby` 호출 전에 `GameWebSocket.disconnect()` 를 먼저 하므로(`multi-result.js:115`) LEFT 이후 구독이 남는 경로 없음.
- 종료된 방에서 `beforeunload` 의 `/leave` beacon 은 무시됨(`leaveRoom` 컨트롤러 573행) → 결과 페이지 진입 시 참가자 레코드가 살아 있다.
- 관리자 화면은 WebSocket 을 쓰지 않는다(`templates/admin` 에 sockjs 참조 0건) → 관리자 예외 불필요.
- 회원 id 는 `accessor.getUser()` → `Authentication.getPrincipal()` → `CustomUserDetails.getMember().getId()`. `Member` 재조회 불필요.

---

## 2. 설계 결정

| # | 결정 | 이유 |
|---|---|---|
| 1 | 구독 인가 위치는 `WebSocketAuthInterceptor.preSend` 의 **SUBSCRIBE** 분기 | 기존 `ChannelInterceptor` 등록(`configureClientInboundChannel`) 재사용, diff 최소 |
| 2 | `spring-security-messaging`(`@EnableWebSocketSecurity`) **도입 안 함** | 방 참가자 여부는 커스텀 로직이라 결국 인터셉터가 필요. 그 모듈은 STOMP CONNECT CSRF 검증을 함께 켜서 SockJS xhr 전송에 별도 처리가 따라온다 |
| 3 | 클래스명 `WebSocketAuthInterceptor` **유지** | 사용자 결정(2026-09-15). 역할은 "인증 전파"→"구독 인가"로 바뀌므로 클래스 Javadoc 과 `SecurityConfig` 주석·CLAUDE.md 만 정정 |
| 4 | 폴링 GET 3개(`/round`, `/chats`, `/status`)에 **같은 참가자 검사 포함** | 사용자 결정(2026-09-15). 없으면 반쪽 수정(§1-3) |
| 5 | 거부 방식: `AccessDeniedException` throw | Spring 이 ERROR 프레임 전송 후 세션 종료. 클라이언트 `ws-client.js` 는 연결 오류로 받아 5회 재시도 → 폴링 폴백 → 폴링도 403 → 비참가자는 아무것도 못 받음. 정상 참가자에게는 이 경로가 발생하지 않음 |
| 6 | 핸드셰이크(`/ws/**` permitAll)·`/ws/**` CSRF 예외·매처 순서는 **그대로** | 핸드셰이크를 인증 필수로 바꾸면 SockJS `/ws/info` 가 로그인 페이지로 리다이렉트되어 폴백 판단이 꼬인다. 거부는 SUBSCRIBE 한 곳에서 |
| 7 | 참가자 조회는 `GameRoomParticipantRepository` 파생 쿼리 1개 추가 (roomCode + memberId + status IN) | 인터셉터는 `clientInboundChannel` 스레드에서 실행되므로 엔티티 그래프를 끌고 다니지 않는 `exists` 쿼리가 적합 |

### 검사 규칙 (SUBSCRIBE · 폴링 GET 공통)

1. 목적지가 `/topic/room/{code}` 형식이 아니면 거부 (이 프로젝트에 다른 토픽 없음) — SUBSCRIBE 만 해당
2. 인증 사용자가 없으면 거부 (비로그인)
3. 해당 방 코드 + 회원 id 로 상태가 `JOINED` 또는 `PLAYING` 인 참가자가 없으면 거부

### 수용하는 한계 (면접에서 "알고 있는 한계"로 말할 것)

- 인가는 **SUBSCRIBE 시점 1회**. 강퇴된 사용자가 페이지를 떠나지 않으면 기존 구독으로 계속 수신 가능. 클라이언트는 KICKED 수신 시 로비로 이동(`multi-waiting.js handleKicked`)하므로 정상 사용자에겐 무관. 서버에서 세션을 강제 종료하려면 `SimpUserRegistry` 기반 세션 관리가 추가로 필요해 이번 범위 제외.
- 핸드셰이크 시점의 Principal 스냅샷을 쓰므로 로그아웃 후에도 열린 WebSocket 은 닫힐 때까지 유지. HTTP 세션 30분 만료와 같은 수준으로 수용.

---

## 3. 구현 계획 (단계 → 검증)

```
0. 가설 실측: 인터셉터 SUBSCRIBE 분기에 accessor.getUser() 디버그 로그 → dev 실행
   → 검증: 로그인 상태로 대기실 진입 시 로그에 이메일이 찍힌다 (핸드셰이크 전파 확정)
        비로그인(시크릿 창)으로 /ws 접속 시 null 이 찍힌다
1. GameRoomParticipantRepository 에 exists 파생 쿼리 추가
   → 검증: @DataJpaTest 또는 기존 repository 테스트 패턴으로 JOINED/PLAYING true, LEFT/미참가/다른 방 false
2. WebSocketAuthInterceptor 재작성: CONNECT 블록 삭제, SUBSCRIBE 검사, 레포지토리 주입
   → 검증: StompHeaderAccessor 로 SUBSCRIBE 메시지 조립 단위 테스트 (서버 기동 불필요)
        - 참가자 → 메시지 그대로 반환
        - 비로그인 / 비참가자 / LEFT / 잘못된 목적지 → AccessDeniedException
        - CONNECT·SEND 등 다른 명령 → 무조건 통과 (기존 동작 유지)
3. MultiGameController: 참가자 검사 헬퍼 1개 + /round, /chats, /status 3곳 적용
   → 검증: MockMvc 로 비로그인 401·비참가자 403·참가자 200. 기존 응답 형식(success/message) 유지
4. SecurityConfig csrf 주석·CLAUDE.md "STOMP 인증 전파" → "STOMP 구독 인가" 정정
   → 검증: grep 으로 "인증 전파" 잔존 0건
5. ./mvnw clean test 전체 통과 → dev 기동 → 2브라우저 수동 시나리오 (§6-2)
```

### 손대지 않을 것

`SecurityConfig` 매처 순서, `/ws/**` permitAll·CSRF 예외, `ws-client.js` 재연결 로직, `GameBroadcastService`, 채팅 전송(POST `/chat`)·라운드 액션 POST 들(이미 `userDetails` 검사 있음).

### 영향 파일 (예상)

| 파일 | 변경 |
|---|---|
| `config/WebSocketAuthInterceptor.java` | CONNECT 블록 삭제, SUBSCRIBE 인가 추가, 레포지토리 주입 |
| `repository/GameRoomParticipantRepository.java` | exists 파생 쿼리 1개 |
| `controller/client/MultiGameController.java` | 헬퍼 1개 + 3개 GET 적용 |
| `config/SecurityConfig.java` | 주석 1줄 |
| `CLAUDE.md` | config 패키지 설명 1줄 |
| `src/test/.../WebSocketAuthInterceptorTest.java` | 신규 |
| `src/test/.../MultiGameControllerTest.java`(기존 있으면 추가, 없으면 신규) | 폴링 GET 3개 |

---

## 4. 검증 명령 (개발 직후 그대로 실행)

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-17"

# 단위·통합 테스트 전체
./mvnw clean test

# 이번 변경분만
./mvnw test -Dtest='WebSocketAuthInterceptorTest,MultiGameControllerTest'

# dev 기동 (포트 8082)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

```bash
# 비로그인 폴링 GET 이 막히는지 (방 코드는 실제 대기 중인 방으로)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8082/game/multi/room/ABCDEF/round
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8082/game/multi/room/ABCDEF/chats
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8082/game/multi/room/ABCDEF/status
```

```bash
# 문서 불일치 잔존 확인
grep -rn "인증 전파\|HTTP_SESSION" CLAUDE.md src/main
```

---

## 5. 결과 (개발 직후 기입)

> 개발 완료일: 2026-09-15 · 커밋: `06a6e4a` (인가), `a155717` (대기실 `ROOM_UPDATE` 버그, 별도 커밋) · 테스트: `./mvnw clean test` **335 건 통과, 실패 0** (신규 17 건 포함, 5분 06초). 변경 파일: main 6개(`WebSocketAuthInterceptor`, `GameRoomParticipantRepository`, `GameRoomService`, `MultiGameController`, `SecurityConfig` 주석, CLAUDE.md 1줄) + 테스트 3개 신규

| 단계 | 상태 | 근거 (커밋 · 테스트명 · 로그) |
|---|---|---|
| 0. 핸드셰이크 Principal 전파 실측 | ✅ 2026-09-15 | dev 기동 후 JDK `java.net.http.WebSocket` 프로브로 `/ws/websocket` 에 CONNECT→SUBSCRIBE. **로그인**(1회용 계정, 확인 후 삭제): 서버 로그 `[STEP0] SUBSCRIBE dest=/topic/room/STEP0 user=step0-probe@test.local sessionAttrKeys=[]`, CONNECTED 프레임에 `user-name:` 헤더 포함. **비로그인**: `user=null sessionAttrKeys=[]`, 그런데 SUBSCRIBE 는 ERROR 없이 수락됨(구독 인가 부재 실증). `sessionAttrKeys=[]` 가 양쪽 모두라 `HTTP_SESSION` 분기는 실행된 적 없음 → Principal 은 핸드셰이크가 넣은 것 |
| 1. 레포지토리 exists 쿼리 | ✅ 2026-09-15 | `GameRoomParticipantRepository.existsByGameRoomRoomCodeAndMemberIdAndStatusIn(roomCode, memberId, statuses)` 파생 쿼리. 호출은 `GameRoomService.isActiveParticipant(roomCode, memberId)` 한 곳(JOINED/PLAYING). 테스트 `GameRoomParticipantRepositoryTest` 4건(H2) |
| 2. 인터셉터 SUBSCRIBE 인가 | ✅ 2026-09-15 | `WebSocketAuthInterceptor` 재작성: CONNECT 블록 삭제, SUBSCRIBE 에서 목적지 형식 → 로그인 → 참가자 순으로 검사, 실패 시 `AccessDeniedException` + WARN 로그. 테스트 `WebSocketAuthInterceptorTest` 5건(Mockito, 서버 기동 없음) |
| 3. 폴링 GET 참가자 검사 | ✅ 2026-09-15 (**2개**, `/status` 제외 — 아래 "계획과 달라진 점") | `MultiGameController.denyUnlessParticipant()` 헬퍼 → `/round`, `/chats`. 테스트 `MultiGameControllerPollingAuthTest` 8건(MockMvc+H2): 비로그인 **401** / 미참가·LEFT **403** / 참가자 **200** |
| 4. 주석·CLAUDE.md 정정 | ✅ 2026-09-15 | `SecurityConfig` csrf 주석, CLAUDE.md config 설명. `grep -rn "인증 전파\|HTTP_SESSION" CLAUDE.md src/main` 잔존 0건 |
| 5. 전체 테스트 + dev 수동 시나리오 | ✅ 2026-09-15 | §6. `clean test` 335건, 프로브, 2브라우저(A/B) + 시크릿 창 + C 계정 수동 시나리오 9건 전부 통과. 도중에 대기실 `ROOM_UPDATE` 버그(§5 부수 문제) 발견·수정 |

### 계획과 달라진 점

- **`/status` 는 참가자 검사에서 제외.** 구현 중 `multi-join.js:42` 가 방 코드 입력 시 `/status` 로 방 이름·방장·인원·상태를 **참가 전에** 미리 보여주는 것을 발견. 검사를 걸면 참가 페이지가 깨진다. 응답에 참가자 닉네임·준비 상태가 포함되는 것은 기존 미리보기 설계이며, 줄이려면 별도 결정 필요(비참가자에게는 인원수만 주는 축소 응답 등). 대기실 폴링의 강퇴 감지(`kicked`)도 이 엔드포인트라 유지가 자연스럽다.
- 인가 헬퍼는 레포지토리 직접 주입이 아니라 `GameRoomService.isActiveParticipant()` 를 인터셉터·컨트롤러가 공유(컨트롤러가 레포지토리를 직접 쓰지 않는 기존 계층 유지).
- 거부 시 클라이언트가 받는 ERROR 프레임의 `message` 는 Spring 이 예외를 `MessageDeliveryException` 으로 감싸서 `Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]` 로 나온다. 구체 사유(로그인 필요 / 비참가자 / 잘못된 목적지)는 서버 WARN 로그 `WS SUBSCRIBE denied: dest=… user=… reason=…` 에만 남는다. 클라이언트는 어차피 사유를 구분하지 않으므로 그대로 둠.

### 발견한 부수 문제 (이번 범위 밖, 기록만)

- **[수정함, 2026-09-15] 대기실 `ROOM_UPDATE` push 를 받으면 로비로 튕기는 버그** (`f3d36cc` WebSocket 전환 때부터). `multi-waiting.js handleRoomUpdate` 가 `if (!payload.success)` 로 "방 종료"를 판단하는데, push payload(`buildRoomStatus`)에는 `success` 키가 없다(폴링 GET `/status` 만 컨트롤러가 `success:true` 를 얹음). 그래서 B 가 참가하면 A 가 로비로 이동하고 "이미 참가중인 방이 있습니다" 가 뜬다. 운영은 2026-09-14 까지 WebSocket 이 성립한 적이 없어 폴링 경로만 돌았기 때문에 잠복. **수동 시나리오 §6-2 의 2·6·7·8 이 이 버그에 막혀** 구독 인가 검증의 선행 조건으로 수정: 조건을 `payload.success === false` 로(1줄). 다른 push 핸들러(play/result)에는 같은 검사 없음.

- 로컬 dev DB(MySQL 8.0, `song`)가 `schema.sql` 보다 뒤처져 있어 dev 기동 실패(`validate`). 누락: `email_verification` 테이블, `game_room.version` 컬럼. 2026-09-15 `schema.sql` 정의 그대로 로컬 DB 에 적용 후 기동 성공. 그 외 컬럼 차이 없음(378 컬럼 대조). 로컬 잔재 테이블 `song_history`·`song_260113` 은 그대로 둠.
- CLAUDE.md 의 JDK 경로 `/c/Program Files/Java/jdk-17` 은 이 PC 에 없음. 실제: `~/.jdks/corretto-17.0.12`. 기본 `java` 는 1.8.
- dev 프로파일 `logging.level.com.song=DEBUG` 는 패키지가 `com.kh.game` 이라 효과 없음(인터셉터의 기존 `log.debug` 도 출력 안 됨). 단계 0 로그는 INFO 로 넣었고 단계 2 에서 제거.

---

## 6. 검증 기록 (개발 직후 기입)

### 6-1. 자동 테스트

| 테스트 | 케이스 | 결과 |
|---|---|---|
| `WebSocketAuthInterceptorTest` | 참가자 SUBSCRIBE 통과 | ✅ |
| | 비로그인 SUBSCRIBE 거부 (참가자 조회 호출 없음) | ✅ |
| | 비참가자·LEFT SUBSCRIBE 거부 (`isActiveParticipant=false`) | ✅ |
| | `/topic/room/` 외 목적지 4종(`/topic/other`, `/topic/room/`, `/topic/room/ABC/extra`, null) 거부 | ✅ |
| | CONNECT / SEND / DISCONNECT 는 통과 | ✅ |
| `GameRoomParticipantRepositoryTest` | JOINED / PLAYING true · LEFT false · 미참가 false · 다른 방/없는 방 false | ✅ 4건 |
| `MultiGameControllerPollingAuthTest` | `/round`, `/chats` × 비로그인 401 / 미참가 403 / LEFT 403 / 참가자 200 | ✅ 8건 |

### 6-1-b. 실제 STOMP 프로브 (dev 서버, 2026-09-15)

JDK `java.net.http.WebSocket` 으로 `/ws/websocket` 에 붙어 CONNECT → SUBSCRIBE (스크립트는 세션 스크래치, 저장소 미포함).

| 케이스 | 결과 |
|---|---|
| 비로그인 SUBSCRIBE `/topic/room/STEP5` | CONNECTED 는 되지만 SUBSCRIBE 직후 **ERROR 프레임** 수신 (수정 전에는 무응답 수락) |
| 서버 로그 | `WS SUBSCRIBE denied: dest=/topic/room/STEP5 user=null reason=로그인이 필요합니다.` |
| 비로그인 GET `/round` | **401** `{"success":false,"message":"로그인이 필요합니다."}` |
| 비로그인 GET `/chats` | **401** 동일 |
| 비로그인 GET `/status` | 200 `{"success":false,"message":"방을 찾을 수 없습니다."}` — 참가 전 미리보기 동작 유지 |

### 6-2. 수동 시나리오 (dev, 브라우저 2개 + 시크릿 창 1개 + C 계정 창 1개)

브라우저 콘솔에서 STOMP 구독을 시도하는 스니펫(홈 화면에서 실행, 방 코드만 바꿔 재사용):

```js
await new Promise(r => { const s = document.createElement('script'); s.src = 'https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js'; s.onload = r; document.head.appendChild(s); });
await new Promise(r => { const s = document.createElement('script'); s.src = 'https://cdn.jsdelivr.net/npm/stompjs@2.3.3/lib/stomp.min.js'; s.onload = r; document.head.appendChild(s); });
const log = []; const c = Stomp.over(new SockJS('/ws')); c.debug = null;
c.connect({}, () => { log.push('CONNECTED'); c.subscribe('/topic/room/75GARA', m => log.push('RECEIVED ' + m.body)); }, e => log.push('ERROR ' + (e && e.headers ? e.headers.message : e)));
await new Promise(r => setTimeout(r, 4000));
console.log('== 판정 ==', log.join(' | ') || '(응답 없음)');
```

통과 기준: `CONNECTED | ERROR Failed to send message … | ERROR Whoops! Lost connection`, `RECEIVED` 없음. (`Permissions policy violation: unload` 경고는 SockJS 라이브러리 것, 무관)

| # | 시나리오 | 기대 | 결과 |
|---|---|---|---|
| 1 | A 가 방 생성 → 대기실 | 콘솔 `[WS] Connected`, 로그에 A 이메일 | ✅ 2026-09-15 (사용자) |
| 2 | B 가 방 코드로 참가 → 대기실 | A 화면에 B 즉시 표시(WS push) | ✅ 2026-09-15 (사용자). **첫 시도에서 A 가 로비로 튕김** → `handleRoomUpdate` `success` 버그(§5 부수 문제) 수정 후 재확인 통과 |
| 3 | 시크릿 창(비로그인)에서 브라우저 콘솔로 `/ws` 접속 후 `/topic/room/{code}` 구독 | ERROR 프레임, 구독 실패 | ✅ 2026-09-15 — 실방 `75GARA` 에 JDK 프로브: CONNECTED → SUBSCRIBE → **ERROR 프레임**. 브라우저 콘솔 재현은 사용자 출력이 `>>> CONNECT` 까지만 캡처되어 미확정 |
| 4 | 시크릿 창에서 `/round` `/chats` `/status` curl | 401 | ✅ 2026-09-15 (사용자): `/round`·`/chats` 401 `로그인이 필요합니다.`, `/status` 200(미리보기, 참가자 닉네임 포함) |
| 5 | C(로그인, 미참가)가 같은 방 코드로 구독 시도 | ERROR 프레임 → 5회 재시도 → 폴링 → 403 | ✅ 2026-09-15 (사용자, 브라우저 콘솔 STOMP): `CONNECTED \| ERROR Failed to send message to ExecutorSubscribableChannel[clientInboundChannel] \| ERROR Whoops! Lost connection` — CONNECT 는 되고 SUBSCRIBE 만 거부, 서버가 소켓 종료, `RECEIVED` 0건 |
| 6 | 게임 시작 → 라운드 → 정답 → 결과 페이지 | A·B 모두 WS 유지, 결과 페이지 구독 성공 | ✅ 2026-09-15 (사용자) |
| 7 | 결과 페이지에서 "한번 더" → 대기실 재진입 | 재구독 성공(참가자 JOINED 초기화) | ✅ 2026-09-15 (사용자) |
| 8 | A 가 B 강퇴 | B 로비 이동, 이후 B 가 방 코드로 구독 시도 시 거부(LEFT) | ✅ 2026-09-15 (사용자): B 로비 이동 확인 후 B 창에서 구독 시도 → 5번과 동일하게 `CONNECTED \| ERROR … \| Lost connection`, `RECEIVED` 0건 |
| 9 | nginx 없는 dev 라 WS 직결. `/ws/websocket` 101 확인 | 101 | ✅ 프로브가 raw websocket 으로 접속 성공(=101) |

### 6-3. 운영 반영 (배포 후 기입)

| 항목 | 결과 |
|---|---|
| 배포 커밋 / 색 전환 | `5405aa5` (cf1701a..5405aa5, 7 커밋) · Actions run 34946579099: build-and-push 1분 33초 → deploy 1분 39초, 2026-09-15 17:25 KST 완료 |
| 운영 `/ws/websocket` 101 유지 | ✅ 101 (`/ws/info` 200) |
| 운영에서 비로그인 `/round` 401 | ✅ `/round`·`/chats` 401 `로그인이 필요합니다.`, `/status` 200(미리보기), `/rooms` 200 `[]`, `/actuator/health` 외부 403(의도) |
| 배포 후 30분 내 멀티 방 정상 진행 (사용자 확인) | [ ] 미실시 — dev 에서는 방장 퇴장 시 방장 위임까지 확인(사용자, 2026-09-15) |

---

## 7. 이력서 · 포트폴리오 정리용 (개발 직후 기입)

### 7-1. 한 줄 요약 (이력서 bullet 후보)

- STOMP 토픽 구독과 폴링 API 에 방 참가자 기반 인가를 추가해, 방 코드만으로 타인의 게임 데이터를 수신할 수 있던 문제를 차단 (`ChannelInterceptor` SUBSCRIBE 검사 + REST 참가자 검사, 신규 테스트 17건 · 전체 335건 통과)
- 핸드셰이크 Principal 전파 원리를 확인해 도달 불가였던 인증 인터셉터 코드를 제거하고 문서·코드 불일치를 정정

### 7-2. 면접 답변 초안 (30초)

> 멀티플레이 실시간 통신을 점검하다가 STOMP 토픽 구독에 인가가 없다는 걸 발견했습니다. 방 코드만 알면 참가하지 않은 사용자도 라운드 정보와 채팅을 받을 수 있는 상태였습니다. 처음엔 인증 인터셉터가 있어서 막혀 있다고 생각했는데, 코드를 따라가 보니 그 인터셉터가 읽는 세션 속성은 어디에서도 채워지지 않는 도달 불가 코드였습니다. 반대로 Spring 핸드셰이크가 이미 Principal 을 전파하고 있어서 인증 자체는 문제가 없었고, 빠진 건 "이 사용자가 이 방의 참가자인가" 라는 인가였습니다. 그래서 인터셉터를 SUBSCRIBE 인가로 재작성하고, WebSocket 이 막히면 폴링으로 넘어가는 구조라 폴링 GET 세 곳에도 같은 검사를 넣었습니다. 강퇴 후 기존 구독이 남는 건 알고 있는 한계로, 클라이언트 이동으로 처리하고 서버 세션 강제 종료는 범위에서 뺐습니다.

### 7-3. 예상 후속 질문과 답

| 질문 | 답 방향 |
|---|---|
| 왜 `spring-security-messaging` 을 안 썼나 | 참가자 검사는 커스텀 로직이라 결국 인터셉터가 필요하고, 그 모듈은 CONNECT CSRF 를 함께 켜서 SockJS xhr 폴백에 별도 처리가 따라옴. 기존 패턴 유지가 diff 최소 |
| 인터셉터에서 DB 조회해도 되나 | 구독은 페이지 진입당 1회. `exists` 쿼리 1건. 채팅·라운드 push 경로에는 영향 없음 |
| 강퇴된 사용자의 구독은 | SUBSCRIBE 시점 인가라 남는다. 클라이언트가 KICKED 로 이동. 서버 강제 종료는 `SimpUserRegistry` 필요, 후속 과제 |
| 스케일 아웃하면 | 인가는 인스턴스 로컬이라 그대로 동작. 브로커(SimpleBroker)가 문제이고 그건 별도 과제 |
| 이걸 어떻게 발견했나 | 면접 답변을 코드와 대조하다가. "방 단위로 세션을 관리한다"는 표현이 코드와 맞지 않아 파고들었음 |

### 7-4. `finish.md` 진행 현황에 추가할 행 (형식 맞춤)

```
| 보안 | STOMP 구독 인가 + 폴링 GET(`/round`·`/chats`) 참가자 검사, 도달 불가 `WebSocketAuthInterceptor` CONNECT 블록 제거 | ✅ 2026-09-15 | `06a6e4a`. 배경: 방 코드만으로 비참가자·비로그인이 토픽 구독·`/round`·`/chats` 수신 가능. 인터셉터는 `HTTP_SESSION` 키를 읽으나 채우는 곳 없음(핸드셰이크가 이미 Principal 전파 — dev 프로브로 실측). `/status` 는 참가 전 미리보기(`multi-join.js`)가 써서 제외. 검증: 신규 테스트 17건, `./mvnw clean test` 335건 통과, dev STOMP 프로브로 비로그인 SUBSCRIBE ERROR·`/round` 401 확인. 2브라우저 수동 시나리오·운영 반영 미실시. 상세 `docs/ws-subscription-authorization.md` |
```
