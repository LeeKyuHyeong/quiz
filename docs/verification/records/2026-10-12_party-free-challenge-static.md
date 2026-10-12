# 파티 퀴즈 — 정지형(사진·텍스트)은 두 팀 오답이어도 "자유 도전" 아님 + O-037 결정
- 일자: 2026-10-12 (집 PC)
- 유형: 수정(규칙 정렬)·결정 반영
- 우선순위: P2 (`party` 브랜치 · 로컬 전용)
- 판정: **수용 가능**

## 1. 요청과 목적
- push 전 공격 점검 2회차의 보통 항목(O-036 ④): 사진·텍스트 문제에서 두 팀이 모두 틀리면 TV 에 "양 팀 오답 — 자유 도전!" 이 떴다. plan §4-2 의 규칙은 "둘 다 오답 → 정지형(A)은 [못 맞힘], 재생형(B·C)은 자유 도전". 기존 테스트 `#bothWrongMeansFreeChallenge` 가 텍스트 문제로 자유 도전을 기대하고 있었다.
- 사용자(10-12): "자유 도전은 테스트 수정 허가할게" · "O-037 은 A 로" (휴대폰 예비 콘솔 안 씀).

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | 재생형에서 두 팀 오답 → 자유 도전(기존 동작 유지), 다음 문제에서 풀림 | ✅ | `#bothWrongMeansFreeChallenge`(재생형 문제로 바꿈) |
| 2 | 정상 | 정지형(텍스트)에서 두 팀 오답(순서 무관) → 자유 도전 아님, `bothWrong` 만 | ✅ | `#staticBothWrongIsNotFreeChallenge`(신규, red→green) |
| 3 | 연쇄 | 재시작 뒤에도 자유 도전 이어짐 | ✅ | `#restoresFreeChallengeAfterRestart`(재생형 문제로 바꿈) |
| 4 | 정상 | 정지형 두 팀 오답이면 TV 에 "양 팀 오답"·두 팀 흐림·"곧 정답 공개", 콘솔은 "[못 맞힘] 으로 정답 공개" | ✅ | 브라우저: 진행 중이던 실제 게임이 마침 텍스트 문제·두 팀 오답(SHOW) — 상태를 건드리지 않고 앱만 재시작해 확인 |

## 3. 변경 사항
- `PartyBoardView` 에 `bothWrong` 추가(`wrongTeam` 다음). `PartyGameService#boardView`: `freeChallenge = bothWrong && 재생형(AUDIO)`.
- `party-board.js`: `bothWrong` 이고 자유 도전이 아니면 "양 팀 오답", 두 팀 흐림, 바닥 "곧 정답 공개". `party-console.js`: 판정 안내 한 줄.
- **기존 테스트 수정(사용자 허가)**: `#bothWrongMeansFreeChallenge`·`#restoresFreeChallengeAfterRestart` 의 문제를 텍스트(QUIZ) → 재생형(SOUND·AUDIO)으로. 단언은 그대로 + `bothWrong` 단언 추가. 신규 `#staticBothWrongIsNotFreeChallenge`.
- 문서: `party-status.md` 서버 계약 9 · O-037 A 반영(`ALLOW_LAN=0` 고정, 사용자 몫 5·6 필요 없음, 플레이어 창 안 씀) · R-052 갱신 · O-036 ④·O-037 ⑫ 처리 표시.

## 4. 영향 범위 분석
- `PartyBoardView` 생성은 `boardView()` 한 곳. 보드·플레이어 창·콘솔(`consoleView().board()`)이 같은 JSON 을 읽는다 — 키 추가뿐이라 플레이어 창 무관. `freeChallenge` 를 읽는 곳: `party-board.js` 2곳, `party-console.js` 1곳(grep) — 모두 손봄.

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| Unit(red→green) | `./mvnw test -Dtest=PartyGameServiceTest` | 구현 전 `#staticBothWrongIsNotFreeChallenge` 1 실패("Expecting value to be false but was true") → 구현 뒤 49 통과 | ✅ |
| 사용자 시나리오 | 앱 재시작 후 보드·콘솔 탭 | 상태 `bothWrong=true`·`freeChallenge=false`·TEXT, 보드 "양 팀 오답"·두 팀 흐림·"곧 정답 공개", 콘솔 "양 팀 모두 오답 → [못 맞힘] 으로 정답 공개 (정지형은 자유 도전 없음)" | ✅ |
| 전체 회귀 | `./mvnw test` | 636 passed, 0 failed, 0 skipped | ✅ |

본게임 상태 파일은 확인 전 복사본과 같음(cmp) — 조작 없이 재시작만.

## 6. 수동 확인 시나리오
- 없음. 재생형 자유 도전 화면은 10-06 확인(records/2026-10-06_party-board.md) 그대로이고 이번에 그 분기는 바꾸지 않았다.

## 7. checklist 점검
- 점검함: 노출(새 키는 오답 여부뿐, 정답 없음), 회귀(재생형 자유 도전·재시작 복구), 화면 3곳(보드 문구·흐림·바닥, 콘솔).
- 해당 없음: DB·권한.

## 8. 발견된 문제와 조치
- 없음.

## 9. 전체 회귀
- `./mvnw test`(리포트 비우고): **636 passed, 0 failed, 0 skipped**(635 + 신규 1).

## 10. Regression 등록
- R-052 갱신(재생형만 자유 도전, 정지형 테스트 추가).
