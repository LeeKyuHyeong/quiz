# Regression 목록

버그를 고치면 재현 테스트를 남기고 여기에 1행 추가한다. 자동(테스트 클래스) 항목은 `./mvnw test`로 전부 돈다. 수동 항목은 배포 후 확인한다.
초기 항목은 `docs/finish.md` 진행 현황(2026-09-15·16)에서 옮겼다.

| ID | 도메인 | 시나리오 | 검증 방법 | 출처 | 등록일 |
|---|---|---|---|---|---|
| R-001 | Multi | 나간(LEFT) 참가자는 정원에 세지 않아 빈 자리를 다시 채울 수 있다 | `GameRoomCapacityTest` | finish ① | 2026-09-16 |
| R-002 | Multi | PLAYING 중 나가기가 실제로 적용되고 방장이면 위임, 마지막이면 방 종료 | `GameRoomLeaveDuringPlayTest` | finish ② | 2026-09-16 |
| R-003 | Multi | 탭 닫기 `/unload`는 CSRF 없이 도달하고 8초 유예 뒤 적용, 그 사이 페이지 GET·재참가면 취소 | `MultiGameControllerUnloadTest` | finish ③ | 2026-09-16 |
| R-004 | Multi | CHAT push 항목이 폴링 `/chats` 항목과 같은 형태(`id`·`memberId`·`isHost`·`CORRECT_ANSWER`), 시스템 메시지도 push | `MultiGameControllerChatPushTest` | finish ④⑤ | 2026-09-16 |
| R-005 | Multi | LEFT 참가자의 `/chat`·`/skip-vote`·`/round`·`/chats` 거부 | `MultiGameServiceLeftParticipantTest`, `MultiGameControllerPollingAuthTest` | finish ⑥ | 2026-09-16 |
| R-006 | Multi | FINISHED 방에서 `nextRound`·`skipCurrentSong`·`startRound` 무시 (LP·전적 이중 반영 방지) | `MultiGameServiceFinishedRoomGuardTest` | finish ⑦ | 2026-09-16 |
| R-007 | Multi | 결과가 비어 있는 방의 결과 URL 재방문 시 500이 아니라 로비로 | `MultiGameControllerResultPageTest` | finish ⑧ | 2026-09-16 |
| R-008 | Multi | 방 생성 서버 검증: 이름 2~30·인원 2~10·라운드 1~20, 실패는 `success:false` | `MultiGameControllerCreateValidationTest` | finish ⑩ | 2026-09-16 |
| R-009 | Multi | 비공개 방: 생성 본문 그대로 저장, 로비에 🔒로 보이되 코드 미노출, 코드로만 입장 | `MultiGameControllerRoomLifecycleTest` | finish 2026-09-15 버그 | 2026-09-16 |
| R-010 | Multi | JS 생성 요청 키 = `GameSettings` 필드명 (Jackson이 미지 키를 조용히 버리는 문제) | `MultiGameControllerRoomLifecycleTest` 계약 테스트 | finish 2026-09-15 버그 | 2026-09-16 |
| R-011 | Multi | 대기실 `ROOM_UPDATE` push 수신 시 로비로 튕기지 않음 (`success === false`만 종료로 판단) | 수동: 브라우저 A/B, B 참가·준비 토글 시 A 유지 | finish 2026-09-15 버그 | 2026-09-16 |
| R-012 | Multi | 구독 거부 사용자의 `ws-client.js`가 1초 무한 재접속 없이 폴링으로 폴백 | 수동: 비참가자로 방 페이지 열고 네트워크 탭 확인 (Jest 없음) | finish ⑨ | 2026-09-16 |
| R-013 | Multi/WS | 비로그인·비참가자 STOMP SUBSCRIBE 거부, `/round`·`/chats` 401 | `WebSocketAuthInterceptorTest`, `MultiGameControllerPollingAuthTest` | finish 2026-09-15 보안 | 2026-09-16 |
| R-014 | Auth | 로그인 `redirect` 파라미터는 `/`로 시작하는 내부 경로만 허용 (오픈 리다이렉트) | `AuthControllerLoginRedirectTest` | finish ⑬ | 2026-09-16 |
| R-015 | Auth | 비밀번호 재설정: 이메일 코드 인증 → 저장 → 세션 만료. 관리자 초기화는 메일 성공 후에만 저장 | `AuthControllerPasswordResetTest`, `MemberServicePasswordTest` | finish 2026-09-15 기능 | 2026-09-16 |
| R-016 | System | 없는 경로는 500 에러 페이지가 아니라 404. `/ws/**`만 CSRF 예외 | `GlobalExceptionHandlerTest`, `SecurityFilterChainTest` | finish 2026-09-15 후속 | 2026-09-16 |
| R-017 | Auth/Admin | 관리자가 회원을 정지·권한 변경하면 그 회원의 기존 세션이 끊긴다 (ACTIVE 로의 변경은 유지) | `MemberStateChangeSessionTest` | records/2026-09-18_session-lifecycle | 2026-09-18 |
| R-018 | Auth | 1계정 1세션: 두 번째 로그인 시 먼저 있던 세션이 만료 표시되고 상태 확인 `SESSION_INVALIDATED`·fetch 401·화면 이동 302 (`CustomUserDetails` equals/hashCode) | `SessionLifecycleContractTest#secondLogin_*`, `#fetchWithInvalidatedSession_*` | records/2026-09-18_session-lifecycle | 2026-09-18 |
| R-019 | Auth/WS | `/auth/validate-session` 은 세션을 연장하지 않는다. WebSocket 연결 중에는 `/auth/status` keepalive 로만 유지된다. **세션 저장소를 바꿀 때(Redis) 이 테스트를 그 프로파일로도 돌릴 것** | `SessionLifecycleContractTest` (실제 Tomcat + STOMP) | records/2026-09-18_session-lifecycle | 2026-09-18 |
| R-020 | Auth | 로그인 세션 내용: 직렬화 가능, 비밀번호 해시·`Member` 엔티티 미포함, 되살린 주체가 원본과 동등(1계정 1세션 판단 유지). 닉네임 등 회원 정보는 로그인 뒤 변경이 같은 세션에 반영 | `LoginPrincipalContentTest` | records/2026-09-19_slim-login-principal | 2026-09-19 |
| R-021 | Auth/Security | 요청 제한의 클라이언트 IP 는 `getRemoteAddr()`(운영은 `forward-headers-strategy=native`). 위조한 `X-Forwarded-For` 로 새 버킷을 받을 수 없다. **프록시 구성을 바꿀 때(ALB 등) 이 테스트의 헤더 모양을 그 구성에 맞춰 다시 볼 것** | `ClientIpSpoofingTest` (실제 Tomcat), `CustomAuthenticationSuccessHandlerTest#*_ignoresClientSuppliedForwardedFor` | records/2026-09-20_auth-abuse-defense | 2026-09-20 |
| R-022 | Auth | 로그인 요청은 IP 당 분당 20회, 계정당 연속 5회 실패 시 5분 잠금(IP 무관·동시 요청 포함 정확히 5회). 성공·비밀번호 재설정·시간 경과로 해제. 정지 계정·없는 이메일의 문구는 그대로. `/admin/login-process` 도 같은 잠금 | `LoginAttemptLimitTest` | records/2026-09-20_auth-abuse-defense | 2026-09-20 |
| R-023 | Auth/Mail | 인증 메일은 같은 주소에 60초에 한 번. 거부된 재요청은 기존 코드·시도 횟수를 지우지 않는다 | `EmailVerificationCooldownTest` | records/2026-09-20_auth-abuse-defense | 2026-09-20 |
| R-024 | Security | 요청 제한 버킷은 10분 유휴 시 제거. 계속 쓰이는(한도에 걸린) 버킷은 남는다 | `LoginRateLimiterTest` | records/2026-09-20_auth-abuse-defense | 2026-09-20 |
| R-025 | Multi | 새로고침·대기실→플레이·플레이→결과 이동 뒤에 도착한 옛 페이지의 언로드 신호로 방에서 빠지지 않는다(게임 시작 8초 뒤 전원 이탈 재발 방지). 최신 페이지의 신호만 유예 뒤 나가기, 토큰 없음·모르는 토큰(배포 전 페이지)은 무시 | `MultiGameControllerUnloadTokenTest` + 수동: 두 계정으로 게임 시작 15초 유지·대기실 새로고침 | records/2026-09-21_multi-unload-token | 2026-09-21 |
| R-026 | Multi | 방 토픽 WebSocket 연결이 모두 끊기면 60초 뒤 나간다(창 전체 닫기). 재구독·페이지 GET·재참가·폴링은 취소, 새 구독이 옛 끊김보다 먼저 와도 유지, 탭 두 개는 둘 다 닫혀야 나감. 결과 화면에서 끊긴 참가자는 재시작 때 다시 잡아 대기실에 남지 않고, 결과 화면 방장은 구독해 재시작 후 방장 유지. 탭 닫기 신호(8초)를 끊김이 늦추지 않음. 하트비트 {서버 10초, 클라이언트 30초} → 멈춘 연결 90초 | `RoomPresenceContractTest`(17) + `RoomPresenceHeartbeatTest`(3) + 수동: 두 계정 대기실 탭 닫기 60초 위임 | records/2026-09-22_multi-presence | 2026-09-22 |
| R-027 | Auth | 로그인 세션이 DB 에 있어 프로세스가 바뀌어도(재시작·배포) 로그인·방 참가가 유지된다. 상태 확인은 세션을 연장하지 않고, 세션이 끝나면 WebSocket 이 닫힌다 | `SessionLifecycleJdbcContractTest`(저장소 경로 7건) + 수동: records/2026-09-22_session-store-jdbc §6 재시작 절차 | records/2026-09-22_session-store-jdbc | 2026-09-22 |
| R-028 | Multi | WS 재연결 시도가 CONNECTED 전에 실패해도(서버 내려감) 다음 시도·폴링 폴백으로 이어진다. 페이지를 떠나며 닫은 연결은 재연결하지 않는다 | 수동(JS, O-002): 대기실에서 서버 20초 내리고 올림 → 콘솔 attempt 2~5 → `Connected` | records/2026-09-22_session-store-jdbc §6 | 2026-09-22 |
| R-029 | Admin | 관리자 로그인이 세션에 `Member` 엔티티를 넣지 않는다(비밀번호 해시·직렬화 불가) | `grep -rn "adminMember" src` 0건 · `SessionLifecycleJdbcContractTest` 가 DB 저장소에서 로그인 주체 직렬화를 탄다 | records/2026-09-22_session-store-jdbc | 2026-09-22 |
| R-030 | Auth | 세션이 있어도 로그인 주체가 없으면(로그인 페이지만 본 방문자, 다른 탭 로그아웃 뒤 생긴 익명 세션) 상태 확인은 NOT_LOGGED_IN 이다 — DB 레지스트리는 익명 세션도 돌려주므로 | `SessionLifecycleContractTest`·`SessionLifecycleJdbcContractTest` (anonymousWithSessionCookie / logoutInAnotherTab) | records/2026-09-22_session-store-jdbc §9 | 2026-09-22 |
