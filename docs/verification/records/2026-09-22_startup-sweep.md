# O-022 — 서버가 내려간 사이 창을 닫은 참가자를 기동 뒤 정리한다
- 일자: 2026-09-22 (회사 PC)
- 유형: 버그 수정 (설계 한계 → 기동 뒤 정리)
- 대상: `RoomPresenceService` 기동 정리(startup sweep), 테스트 2 클래스 5건
- 판정: **조건부** — 자동 계약 5건·전체 회귀 ✅, 운영 확인은 다음 배포에서 🙋

## 1. 요청·결정·가정
- 요청: 09-22 남긴 열린 것(O-022·O-023·O-018·O-019)을 닫을 수 있게 진행 (개발자).
- 문제: 배포 전환·재시작으로 서버가 내려가 있는 사이 창을 닫은 참가자는 나가기 신호(옛 프로세스에 감)도, 새 프로세스의 방 토픽 구독도 없다. 새 프로세스의 presence 집합에 그 사람이 없으니 끊김 나가기가 잡히지 않고 대기실에 최대 24시간(`RoomCleanupBatch`) 남는다. presence 집합이 메모리인 한 구조적(session-store-jdbc §8 표 "재시작 중 창을 닫은 사람 ⬜ 한계").
- 결정(에이전트, 개발자 "닫을 수 있게 진행" 위임): 기동 뒤 지연을 두고 **진행 중인 방(WAITING·PLAYING)의 참가자 가운데 방 토픽 연결이 없는 사람의 나가기를 끊김 유예(60초)로 예약**한다. 살아 있는 화면은 그 사이 재구독(WS)·페이지 GET·폴링 GET(`cancelDisconnectLeave`)으로 취소한다 — 기존 취소 규칙 그대로라 폴링 폴백 사용자도 보호된다.
- 지연 `game.multi.startup-sweep-delay-ms` 기본 **90초**: 배포 순서가 "새 색 기동 → 헬스 → nginx 전환 → **30초 드레인** → 옛 색 정지" 라 클라이언트는 새 프로세스 기동 뒤 30초+ 가 지나서야 끊기고, 재연결 예산이 31초(1+2+4+8+16)다. 90초 + 유예 60초 = 기동 150초 안에 살아 있는 신호가 오면 남는다. 음수면 끔.
- 가정: 종료된 방(결과 화면)은 대상에서 뺐다 — 거기서 창을 닫은 사람은 방장이 재시작할 때 JOINED 로 되살아날 수 있는데(Q-27 ② 경로는 `absentSince` 가 메모리라 재시작 전 끊김만 안다), 그 경우 방장이 강퇴로 정리한다. 폴링 폴백 사용자를 결과 화면 재시작 직후 6초 안에 내보낼 위험이 커서 넣지 않았다.

## 2. AC
| # | AC | 상태 |
|---|---|---|
| 1 | [정상] 기동 뒤 지연이 지나면, 진행 중인 방에서 연결 없는 참가자는 유예 뒤 방에서 나간다 | ✅ `RoomPresenceStartupSweepReadyTest` 1 + `RoomPresenceStartupSweepTest` 1 |
| 2 | [정상] 구독 중인 참가자는 남는다 | ✅ 위 두 테스트 |
| 3 | [연쇄] 폴링으로 살아 있는 화면(연결 끊김 취소)은 남는다 | ✅ `sweep_thenPollingCancel_keepsParticipant` |
| 4 | [경계] 종료된 방(결과 화면)은 대상이 아니다 | ✅ `sweep_ignoresFinishedRooms` |
| 5 | [경계] 지연이 음수면 기동 이벤트가 정리를 예약하지 않는다(테스트 프로필 기본) | ✅ `negativeDelay_disablesSweep` |
| 6 | [운영] 배포 전환 중 대기실에 있던 사람은 남고, 그 사이 창을 닫은 사람은 기동 150초 안에 나간다 | 🙋 다음 배포 |

## 3. 변경
- `RoomPresenceService`: `GameRoomRepository`·`GameRoomParticipantRepository`·`TaskScheduler`·`TransactionTemplate` 주입, `@EventListener(ApplicationReadyEvent)` 가 `startup-sweep-delay-ms` 뒤 `sweepAfterStartup()` 예약. 정리는 `findByStatus(WAITING|PLAYING)` × `findGameParticipants` 에서 `isConnected` 가 아닌 참가자를 `scheduleLeaveOnDisconnect(…, disconnectGraceMs)`(이미 예약된 나가기는 유지). INFO `Presence startup sweep scheduled: delayMs= graceMs=` / `Presence startup sweep: participantsWithoutConnection= graceMs=` / 꺼짐 `Presence startup sweep disabled`.
- `src/test/resources/application.properties`: `game.multi.startup-sweep-delay-ms=-1` — 테스트 컨텍스트는 스위트 내내 캐시되므로 지연 정리가 다른 테스트 도중에 돌지 않게 끈다. `RoomPresenceStartupSweepReadyTest` 만 5000 으로 켠다.
- `RoomPresenceContractTest.disconnectWhileShuttingDown_isIgnored`: 생성자 인자 추가(계약 불변).
- 새 테스트: `RoomPresenceStartupSweepTest` 4건(정리를 직접 호출), `RoomPresenceStartupSweepReadyTest` 1건(기동 이벤트 → 5초 뒤 정리 → 유예 300ms 뒤 LEFT).

## 4. 영향 범위
- `RoomPresenceService` 생성자 호출처: Spring + `RoomPresenceContractTest` 1곳(수정).
- 정리가 예약하는 것은 기존 `scheduleLeaveOnDisconnect` 와 같은 대기 목록·같은 이유(DISCONNECT) — 취소 규칙(구독·페이지 GET·재참가·폴링)이 그대로 적용된다.
- 로컬 dev(`spring-boot:run` 재시작)도 90초 뒤 정리가 돌지만 클라이언트가 수 초 안에 재구독하므로 영향 없음.

## 5. 실행한 검증
| 항목 | 결과 |
|---|---|
| `RoomPresenceStartupSweepTest` 4 · `RoomPresenceStartupSweepReadyTest` 1 · `RoomPresenceContractTest` 18 | ✅ PASS (`-Dtest=` 6 클래스 50건 중 이 셋 23/23; Ready 테스트 5.4초 = 지연 5초 + 유예) |
| 전체 회귀 `./mvnw test` | (아래 §7) |

## 6. 미검증·한계
- 🙋 운영: 다음 배포 전환에서 `docker logs` 에 `Presence startup sweep scheduled` → 90초 뒤 `Presence startup sweep: participantsWithoutConnection=N` 이 찍히고, 대기실에 남아 있던 계정은 남고(취소), 전환 중 창을 닫은 계정은 `Room leave applied: reason=DISCONNECT` 로 나가는지.
- ⬜ 결과 화면에서 배포 중 창 닫기 → 방장 재시작 → JOINED 로 되살아남(§1 가정, 강퇴로 복구). 발생 창이 좁아 이슈로 두지 않고 한계로 기록.
- ⬜ presence 집합·페이지 토큰은 여전히 메모리(단일 인스턴스 결정).

## 7. 전체 회귀
- `./mvnw test` (회사 PC, JAVA_HOME corretto-17): **61 클래스 · 468건 / 실패 0 / 오류 0**, BUILD SUCCESS, 06:52 min. 463(af96d74) + 5(이 작업) = 468. `LoginAttemptLimitTest` 는 O-023 수정본으로 통과(같은 날 records/2026-09-22_rate-limit-clock).
