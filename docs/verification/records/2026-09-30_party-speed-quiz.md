# 파티 퀴즈 P-2 Part ③ — 스피드퀴즈 규칙 (`PartySpeedQuizService`)
- 일자: 2026-09-30
- 유형: 신규
- 우선순위: P1
- 판정: 수용 가능 (서비스 계층까지. 화면·경로는 Part ④)

## 1. 요청과 목적
- 사용자가 원한 것: 본게임 첫 대분류 선택권을 정하는 몸풀기. plan §1-4·§8 기준.
- 개발자 확인 결과(결정 사항, 2026-09-30 저녁 "1~3 전부 추천대로"):
  1. **[재대결]과 [초기화]를 따로 둔다.** 재대결은 결과만 비우고 설정·나온 제시어 기록은 유지, 초기화는 전부 비우고 파일 삭제.
  2. 시간 판정은 서버 시계, **여유 1초**.
  3. AC 는 초안 그대로.
- 진행 중 둔 가정:
  - 턴이 끝나는 경우는 셋: 제한 시간(+1초) 경과 · [턴 종료] · 제시어 소진. 콘솔이 턴 종료를 보내지 못해도 시간이 지나면 다음 조작 때 끝난 것으로 확정한다(상대 팀을 바로 시작할 수 있다).
  - 보드·콘솔 상태의 "끝남" 표시는 남은 시간 0초부터, 정답·패스 거부는 +1초 뒤부터. 그 1초는 전송 중인 요청을 위한 것이다.
  - 되돌리기는 턴이 진행 중이면 그 제시어를 다시 띄우고(뒤에 뽑혔던 제시어는 다시 나올 수 있게 돌려놓음), 턴이 끝난 뒤에는 수만 줄인다. 제시어 소진으로 끝난 턴도 같다(시간이 남아 있어도 턴을 되살리지 않는다).
  - 시간이 끝날 때 떠 있던 제시어는 나온 것으로 친다(본 제시어라 다시 내지 않는다).
  - 설정의 주제는 실제 제시어에 있는 중분류만 받는다. 제한 시간 상한은 두지 않았다.
  - 팀 이름은 상태에 없다(본게임 상태와 서로 참조하지 않는다 — plan §1-4). 화면이 A=남팀·B=여팀으로 표시한다.
  - "다음 출제자!" 안내는 화면 몫(제시어가 바뀔 때 JS 가 띄운다). 서버 상태에 없음.
  - `party.speed-state-file` 이 비어 있으면 메모리. dev 만 `../party-images/party-speed-state.json`.

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 (`PartySpeedQuizServiceTest`) |
|---|---|---|---|---|
| 1 | 정상 | 기본 설정: 전체 주제·90초·90초·말로·보드 표시 | ✅ | `#defaultSetup` |
| 2 | 정상 | 주제·팀별 제한 시간·설명 방식·표시 위치 설정 | ✅ | `#configures` |
| 3 | 예외 | 0 이하 제한 시간, 없는 주제, 턴 진행 중 설정 변경 거부 | ✅ | `#rejectsBadSetup` |
| 4 | 정상 | 시작하면 그 팀의 시간으로 타이머 + 첫 제시어 | ✅ | `#configures` |
| 5 | 정상 | 정답 +1 / 패스 +1, 둘 다 다음 제시어 | ✅ | `#correctAndPass` |
| 6 | 예외 | 나온 제시어는 상대 팀 턴에도 다시 안 나옴 | ✅ | `#wordsNeverRepeat` |
| 7 | 경계 | 제시어가 다 떨어지면 턴 종료 | ✅ | `#wordsNeverRepeat`, `#cannotStartWithoutWords` |
| 8 | 경계 | 제한 시간 +1초까지 받고 그 뒤 거부 | ✅ | `#timeLimitWithGrace` (손으로 돌리는 시계) |
| 9 | 예외 | 마친 팀 재시작 거부, 진행 중 다른 팀 시작 거부 | ✅ | `#oneTurnAtATime` |
| 10 | 정상 | 되돌리기: 수 −1, 그 제시어 다시 현재 | ✅ | `#undoRestoresWord` |
| 11 | 정상 | 시간 종료 뒤 되돌리기: 수만 줄고 제시어 안 뜸 | ✅ | `#undoAfterTimeOver` |
| 12 | 예외 | 연속 두 번 되돌리기 거부 | ✅ | `#undoRestoresWord` |
| 13 | 정상 | 두 팀 종료 → 정답 수 많은 팀이 첫 선택권, 동점이면 없음 | ✅ | `#result` |
| 14 | 정상 | 재대결은 결과만 비우고 나온 제시어·설정 유지 | ✅ | `#rematchKeepsUsedWords` |
| 15 | 연쇄 | 본게임 점수 불변 | ✅ | `#doesNotTouchMainGame` |
| 16 | 노출 | "콘솔만"이면 보드 상태에 제시어 없음 | ✅ | `#boardHidesWord` (JSON 문자열 검사) |
| 17 | 노출 | 안 나온 제시어는 어느 상태에도 없음 | ✅ | 같은 테스트 |
| 18 | 정상 | 재시작 뒤 설정·결과·나온 제시어·남은 시간이 이어짐 | ✅ | `#restoresAfterRestart` |
| 19 | 정상 | 초기화는 전부 비우고 파일 삭제 | ✅ | `#resetClearsEverything` |
| 20 | 연쇄 | 기존 테스트 그대로 통과 | ✅ | 전체 538 = 481 + 12 + 26 + 19 |
| 21 | 정상 | 실제 제시어 80개로 dev 에서 한 판 | ⬜ | 부르는 경로가 없다(Part ④) → O-028 |

추가로 확인한 것: 턴이 없을 때의 조작 거부(`#needsTurn`), 시간이 끝난 팀 다음에 상대 팀 바로 시작(`#nextTeamAfterExpiry`), 진행 중 재대결 거부(`#cannotRematchWhileRunning`).

## 3. 변경 사항
- `src/main/java/com/kh/game/party/PartySpeedQuizService.java` — 규칙
- `.../PartySpeedState.java`, `PartySpeedHolder.java`, `PartySpeedMode.java`
- `.../PartySpeedBoardView.java`, `PartySpeedConsoleView.java`
- `.../PartySnapshotHolder.java` — **신규 공통 부모.** Part ② 의 `PartyGameHolder` 에 있던 파일 저장·복원 코드를 옮겼고, `PartyGameHolder`·`PartySpeedHolder` 는 파일 경로와 상태 타입만 정한다. 동작 변경 없음(로그 문구만 `Party snapshot …` 으로 바뀜).
- `.../PartyItemRepository.java` — `findByCategoryAndUseYn` 추가
- `src/main/resources/application-dev.properties` — `party.speed-state-file` 1줄
- `src/test/java/com/kh/game/party/PartySpeedQuizServiceTest.java` — 19건
- DB 변경: 없음. 기존(파티 밖) 자바 파일 수정: 없음.

## 4. 영향 범위 분석
- `PartyGameHolder` 를 고쳤다 → 쓰는 곳은 `PartyGameService` 하나. `PartyGameServiceTest` 의 복구 3건(`restoresAfterRestart`·`newGameClearsEverything`·`brokenSnapshotStartsFresh`)이 그대로 통과.
- 시계는 생성자로 넣는다(테스트는 손으로 돌리는 시계, 운영은 시스템 시계). `LoginRateLimiter` 의 O-023 과 같은 방식.
- 호출처: 없음(컨트롤러는 Part ④).

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| Integration | `./mvnw test -Dtest='Party*Test'` | 57 passed (26 + 12 + 19) | ✅ |
| 테스트가 규칙을 잡는지 | 4곳을 일부러 망가뜨리고 재실행(시간 만료 확정 제거 · 중복 제외 제거 · 보드 표시 설정 무시 · 되돌린 뒤 기록 안 지움) 후 원복 | 6건 실패: `boardHidesWord` · `correctAndPass` · `timeLimitWithGrace` · `undoRestoresWord` · `wordsNeverRepeat` · `nextTeamAfterExpiry`. 원복 뒤 `diff` 일치 | ✅ |
| 전체 회귀 | `./mvnw test` (JDK 17, Docker 29.4.2) | Tests run: 538, Failures 0, Errors 0, Skipped 0, 3분 52초 | ✅ |
| 기동 | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` | 기동 10.1초, `/actuator/health` 200 UP | ✅ |
| 실제 제시어로 한 판 | — | 경로 없음 | ⬜ O-028 |

Part ② 와 같이 구현과 테스트를 같은 시점에 작성했다(실패 테스트 선행이 아님). 그래서 "일부러 망가뜨리기"로 따로 확인했다.

## 6. 수동 확인 시나리오
- 없음(화면 없음). Part ④·P-3 에서 작성 — 특히 콘솔 타이머가 0 이 되는 순간의 버튼 잠금과 서버 판정의 어긋남.

## 7. checklist 점검
- 점검함: §2 0·음수 제한 시간, 제시어 0개, 경계 시각(59·61·62초) · §4 허용되지 않는 전이 거부(진행 중 설정·시작·재대결, 마친 팀 재시작, 턴 없는 조작), 같은 요청 중복(연속 되돌리기), 재시작 뒤 남은 시간 · §3 보드 상태에 제시어 미노출(설정에 따라) · §7 턴 종료 로그(팀·정답 수·패스 수).
- 해당 없음: 권한(경로 없음) · 멀티 · 화면 · DB 변경 · 날짜 시간대(같은 JVM 의 `LocalDateTime` 끼리만 비교).
- 남은 것: 서머타임 없는 `Asia/Seoul` 전제. 노트북 시계를 턴 도중에 바꾸면 남은 시간이 틀어진다(하지 않는다).

## 8. 발견된 문제와 조치
- 없음.

## 9. 미검증 영역과 남은 위험
- 실제 제시어(`speed.tsv` 80개)로 dev 에서 한 판 / 경로 없음 / Part ④ 뒤(O-028).
- 브라우저 타이머와 서버 판정의 1초 여유가 실제로 충분한지 / 화면 없음 / P-3 뒤 리허설.
- dev 에서 `party-speed-state.json` 이 실제로 생기는지 / 같은 이유 / O-028.

## 10. Regression 등록
- 없음(버그 수정 아님).
