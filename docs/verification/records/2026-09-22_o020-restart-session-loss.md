# O-020 원인 — 서버 프로세스가 바뀌면 방 참가자가 전원 유령이 된다
- 일자: 2026-09-22 (회사 PC, 로컬 dev 8082)
- 유형: 버그 원인 확인 (수정 없음)
- 대상 커밋: `05ffe5b` (운영과 같은 코드 — 배포 run #230 `35671021581` build·deploy Success 확인)
- 판정: **원인 확정(재현)** — 수정은 Redis 세션 저장소(PR-2)에서. 집 PC 사례(방 `WPNYZP`)가 같은 경로였는지는 집 서버 콘솔 확인 전까지 추정

## 1. 앞선 판정 정정
| 앞선 기록 | 앞선 내용 | 정정 |
|---|---|---|
| records/2026-09-22_multi-presence §3 1-② · `RoomPresenceService` javadoc · `multi-presence-design.md` 표 6·7행 | 배포(프로세스 교체)로 집합이 비어도 "클라이언트가 새 인스턴스에 재연결·재구독하며 다시 채워진다" | **틀림.** HTTP 세션이 메모리(spring-session·remember-me 없음)라 프로세스가 바뀌면 로그인이 전부 사라진다. 새 프로세스에 구독이 들어가지 않으므로 집합이 다시 채워지지 않고, 끊김 나가기도 잡히지 않는다. 운영 blue/green 배포도 같다(records/2026-09-19_slim-login-principal "blue/green 전환으로 메모리 세션이 사라지므로") |
| records/2026-09-22_multi-presence §6-1 8번 추정 | 세션 60분 유휴 만료 → 로그인 화면 | 유휴 만료는 불가능 — WS 연결 중 `/auth/status` 5분 keep-alive, 폴링 중 2초 요청이 세션을 연장한다. 로그아웃 원인은 세션 소멸(프로세스 교체)이고, 토스트 문구가 "오랫동안 이용하지 않아"라 유휴 만료처럼 보였을 뿐 |
| (이 세션 회사 PC 첫 추정) | 재연결 SUBSCRIBE 가 "로그인이 필요합니다"로 거부된다 | 거부 로그 0건. 재연결이 1번째 시도에서 멈춰 SUBSCRIBE 까지 가지 않았다(§3, O-021) |

## 2. 재현 절차
1. dev 기동(`JAVA_HOME=corretto-17`, `-Dspring-boot.run.profiles=dev`). 내장 브라우저 `localhost`(a@a.com, 관리자 #8)·`127.0.0.1`(트루본짱 #1) — 로그인은 개발자
2. 방 `3YM2GV` 생성(관리자 방장) → 트루본짱 참가 → 두 탭 대기실, `GameWebSocket.connected=true` 확인
3. 탭마다 `[WS]` 콘솔·토스트·세션 무효 처리·pagehide 를 `localStorage.__o020` 에 기록하는 훅
4. 10:35:13 `touch target/classes/com/kh/game/GameApplication.class` → devtools 재시작(10:35:18 종료 → 10:35:35 기동)
5. 10:37:56 까지 관찰 후 화면·서버 로그·DB 대조

## 3. 결과
| 시각(KST) | 관리자(방장) | 트루본짱 |
|---|---|---|
| 10:35:19.54 | `[WS] Connection closed` → `Reconnecting in 1000ms (attempt 1/5)` | 같음 |
| 10:36:01~02 | `SessionManager` 세션 무효 → 토스트 "오랫동안 이용하지 않아 로그아웃되었습니다." | 같음 |
| 10:36:03~04 | pagehide → `/auth/login?expired=true` | 같음 |

| 확인 | 결과 |
|---|---|
| 서버 로그 `Room leave applied` | ✅ 0건 (10:37:56 까지, 재시작 뒤 2분 20초 — 유예 60초 초과) |
| 서버 로그 `WS SUBSCRIBE denied` | 0건 — 재연결이 SUBSCRIBE 까지 가지 않음 |
| DB (개발자 실행 조회) | ✅ `3YM2GV` WAITING, member 1·8 모두 **JOINED** |
| 화면 | ✅ 두 탭 모두 로그인 화면 |

→ 집 PC 증상("화면은 나갔는데 서버는 나가기 로그 없이 참가자로 셈")과 같다.

경로: 프로세스 교체 → 세션·presence 집합·페이지 토큰·대기 목록 모두 소멸 → pagehide 신호는 모르는 토큰이라 무시, 구독이 없으니 끊김 나가기 없음 → 참가 행이 JOINED 로 남음(대기실은 `RoomCleanupBatch` 24시간, PLAYING 은 2시간 뒤 정리).

## 4. 영향
- **운영 배포마다** 방에 있던 사람 전원이 로그인 화면으로 가고 서버에는 유령으로 남는다. 방장이 유령이면 그 방은 아무도 시작 못 함. O-018 전부터 있던 동작(세션 메모리) — 회귀 아님. O-018 의 "배포 중 게임 유지" 운영 확인은 이대로면 실패한다
- 로그아웃 토스트 문구가 원인과 다르다("오랫동안 이용하지 않아")

## 5. 부수 관찰 (미확인)
- **O-021 후보**: 재연결이 1번째 시도 뒤 멈췄다(2번째 시도 로그·오류 콜백·폴링 폴백 모두 없음). 추정: 서버가 내려간 동안 시작한 SockJS 연결이 CONNECTED 전에 닫히면 `socket.onclose` 가 `connected===true` 일 때만 재시도해서(`ws-client.js` 112행 부근) 아무 처리도 없다. 세션이 살아 있어도(Redis 이후) 재연결 안 됨 → 유령이 될 수 있음. 재현 확인 필요
- 트루본짱의 첫 참가 시도가 "이미 다른 방에 참가중입니다"로 거부됐고, 로비를 한 번 연 뒤 재시도는 성공. 그 시점 JOINED 행은 종료된 방 `NZJ65V`(09-15, FINISHED) 하나 — `findActiveParticipation` 은 FINISHED 방을 제외하므로 거부 사유와 맞지 않음. 원인 미확인
- dev DB 에 `3YM2GV`(member 1·8 JOINED)·`NZJ65V`(member 1 JOINED) 가 남아 있음 — 로비 "내 방 참가 정보 초기화"로 정리 가능

## 6. 다음
| 항목 | 상태 | 절차 |
|---|---|---|
| 집 사례 귀속 | 🙋 | 집 dev 콘솔에서 09-22 07:51~09:10 사이 `Restarting`/기동 배너 여부 |
| 수정 | Redis 세션 저장소(PR-2) | 이 기록 §2 절차를 수정 전후 비교 기준으로 — 수정 후 기대: 재시작 뒤 재연결·재구독, 두 명 WAITING 유지, 화면 대기실 유지 |
| 재시작 중 창을 닫은 사람 | ⬜ Redis 이후 확인 | 집합에 들어갈 기회가 없어 유령 가능 (presence 는 여전히 메모리) |
| 문서 정정 | 이 기록 §1 | `RoomPresenceService` javadoc·`multi-presence-design.md` 6·7행은 PR-2 에서 함께 고친다(코드 주석 수정이 단독 배포되지 않게) |
| O-021 | OPEN | §5 |
