# 파티 퀴즈 O-034 정리 — 10-10 점검에서 남긴 보통·경미 13건
- 일자: 2026-10-12 (집 PC)
- 유형: 수정·문서
- 우선순위: P2 (`party` 브랜치 · 로컬 전용 · 행사 1회. 행사를 막지 않는 것들)
- 판정: **조건부** — 코드 수정분 ✅(테스트·브라우저), 배치 파일 실제 실행·로그 인코딩은 노트북 세팅 때 🙋, ⑫ 는 사용자 결정 대기

## 1. 요청과 목적
- 사용자: "'심각' 건이 안 나올 때 O-034 정리 진행해줘" (10-12). push 전 공격 점검 3회(records/2026-10-12_party-attack-review.md)가 3회차 심각 0 으로 끝난 뒤 진행.
- 정리 방침(가정): 고치는 비용이 작고 MC 가 헷갈릴 만한 것은 고친다 · 앱이 할 일이 아닌 것(운영 습관)은 화면 안내·`party-status.md` 행사 메모로 · 결정이 필요한 것(⑫)은 남긴다. 서버 규칙·DB 스키마는 바꾸지 않는다.

## 2. 항목별 처리 (AC)
| O-034 | 내용 | 처리 | 상태 | 근거 |
|---|---|---|---|---|
| ① | 콘솔 남은 수가 다른 탭의 숨기기를 모름 | 콘솔 창으로 돌아올 때(`focus`·`visibilitychange`) 남은 수를 한 번 다시 받음 | ✅ | 브라우저: `focus` 이벤트 → `/remaining` 1회 |
| ② | [문제 관리] 링크가 같은 탭 → SHOW 중 누르면 소리 끊김 | 새 탭(`target=_blank`, "↗") + 문제 관리 화면의 "← 콘솔" 링크를 안내 문구로(콘솔 두 개 = 소리 겹침 방지) | ✅ | 브라우저: 링크 `target=_blank`, 문제 관리 머리글 문구 |
| ③ | 뽑아 둔·띄운 문제를 숨겨도 내려가지 않음 | 안내만: 문제 관리 화면 "지금 띄운·뽑아 둔 문제는 숨겨도 이번 라운드는 그대로 — [거두기]·다시 뽑기" | ✅ 문서 | 의도: 띄운 문제를 서버가 몰래 내리면 보드가 갑자기 바뀐다 |
| ④ | 다시 올려도 숨김이 안 풀림(표시 없음) | 올리기 결과에 `hidden`(이 파일에 있지만 숨김인 문제) → 화면 "숨김 상태인 문제 n개 — [숨김] 필터에서 [다시 켜기]" | ✅ | `PartyItemImportTest#reportsItemsStillHidden`, `PartyControllerTest#importReportsHiddenAndUnchecked`(red→green) · 브라우저 |
| ⑤ | 정답 표기를 고친 행은 새 id 라 연습경기 이력이 못 막음 | 안내만: 문제 관리 화면 "표기는 연습경기 **전에** 고치세요" | ✅ 문서 | 옛 행은 "이 파일에 없는 문제"로 알려져 숨길 수 있다 |
| ⑥ | 팀 이름 칸 `namesDirty` 가 안 풀림 · 저장 버튼이 바쁠 때도 열림 | 칸을 원래 이름으로 되돌리면 다시 다른 창 변경을 따라감 · 조작 중엔 [팀 이름 저장] 잠금 | ✅ | 브라우저(콘솔 상태 응답만 가짜로 — 서버 미변경): 되돌린 뒤 "다른창1" 따라감 / 고친 채면 "내가고침" 유지. 잠금은 `setBusy` 한 줄(코드 확인) |
| ⑦ | 엑셀이 쉼표 든 칸도 감싸는지 미확인 | 받아들임: 편집은 VS Code·메모장(10-10 결정 ⑤B). 감싸도 힌트에 따옴표가 보이는 표시 문제뿐 | ✅ 수용 | — |
| ⑧ | 앱 로그가 MS949 | 배치 파일 실행 인자에 `--logging.charset.console=UTF-8`(Boot 3.4.1 메타데이터에 키 확인) · 안내 "VS Code·메모장으로" | 🙋 | 노트북 세팅 때 `party-app.log` 를 VS Code 로 열어 한글이 안 깨지는지 |
| ⑨ | 배치 파일 두 번 실행·상대 경로 인자·이전 로그 1벌 | 8082 가 LISTENING 이면 로그를 건드리기 전에 멈춤 · 인자는 `%~f1`(절대 경로) · 이전 실행의 `.write-test` 를 먼저 지우고 쓰기 시험. 이전 로그 1벌은 그대로(수용) | ✅ 부분 / 🙋 | 같은 줄만 담은 시험 bat: 8082 사용 중 → `PORT_IN_USE` exit 1, 8099 → `PORT_FREE`, `rel\sub` → 절대 경로. 실제 배치 파일 전체 실행은 노트북 세팅 때 |
| ⑩ | [모두 숨기기]가 로그아웃에도 끝까지 돎 · 실패한 올리기 뒤 앞 결과 패널이 남음 | 응답이 없으면(로그아웃·서버 없음) 남은 수를 실패로 세고 멈춤 · 올리기 실패 시 결과·거부·빠진 문제·새 안내 패널을 모두 지움 | ✅ | 브라우저: `/use` 요청을 막고 79개 [모두 숨기기] → 요청 1번에서 멈춤, "79개는 숨기지 못했습니다", 실제 숨김 0 / 빈 파일 → "빈 파일입니다" + 패널 0개 |
| ⑪ | 열 수 틀린 줄 때문에 빠진 문제 검사를 건너뛰어도 표시 없음 | 결과에 `unchecked`(대분류 이름인 것만) → 화면 "○○ — 열 수가 틀린 줄이 있어 검사를 건너뛰었습니다" | ✅ | `PartyItemImportTest#reportsCategoriesSkippedForMissingCheck`, `PartyControllerTest#importReportsHiddenAndUnchecked` · 브라우저 |
| ⑫ | `ALLOW_LAN=1` 이면 시드 관리자 비밀번호(공개 저장소)로 로그인 가능 | **사용자 결정 대기** — 아래 §6 | ⬜ | 기본값 `ALLOW_LAN=0` 이라 행사 기본 구성엔 영향 없음 |
| ⑬ | bat·dev 기본값이 달라 다른 방법으로 재시작하면 새 게임 | `party-status.md` 행사 메모 "재시작은 반드시 배치 파일로" | ✅ 문서 | — |

같이 적은 행사 메모(push 전 공격 점검의 보통 항목 중 운영 습관인 것, `party-status.md` "행사 노트북 실행"): 노트북 전원·덮개·절전·Windows 업데이트 · 행사 중 빌드 금지(devtools 재시작) · 미리 듣기가 HDMI 로 TV 에 나감 · 플레이어 창은 보이게(가려지면 타이머 1분) · 인터넷이 끊긴 사이 새로고침했으면 복구 뒤 F5 · 콘솔은 한 창만. 문서 정정: 기존 자바 파일 수정 목록에 `BatchScheduler`·`application-dev.properties`, `session-jdbc` 면 폴링도 세션 행을 읽고 쓴다.

## 3. 변경 사항
- 서버: `PartyImportResult` 에 `hidden`·`unchecked` · `PartyItemImportService`(숨김 행 모으기, 검사 건너뛴 대분류) · `PartyItemController` 올리기 응답에 두 키.
- 화면: `party-content.js`·`content.html`(안내 2줄, 결과 패널 2개, 실패 시 지우기, 숨기기 반복 멈춤, 콘솔 링크 → 문구) · `party-console.js`(창 복귀 시 남은 수, 팀 이름 칸, 저장 버튼 잠금) · `console.html`(문제 관리 새 탭).
- `scripts/party-start.bat`(ASCII·CRLF 유지): 포트 확인, `%~f1`, 쓰기 시험 앞 삭제, 로그 UTF-8.
- 문서: `docs/party-status.md`.
- 테스트: `PartyItemImportTest` +2, `PartyControllerTest` +1. DB·설정 키 추가 없음(응답 JSON 에 키 2개 추가 — 기존 키 유지).

## 4. 영향 범위 분석
- `PartyImportResult` 생성자 호출처: `PartyItemImportService` 2곳뿐(grep). 응답은 `PartyItemController#importTsv` 한 곳, 읽는 화면은 `party-content.js` 한 곳. `PartyDevCheck` 는 `created`·`errors` 만 읽는다.
- `party-console.js` 의 `/remaining` 은 DB 를 읽지만 창 복귀 때만(폴링 아님) — 서버 계약 유지, 계약 주석 갱신.

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| Unit/Integration(red→green) | `./mvnw test -Dtest=PartyItemImportTest` → 구현 전 2 실패(단언) / `-Dtest=PartyControllerTest#importReportsHiddenAndUnchecked` → 구현 전 "No value at JSON path $.hidden" | 구현 뒤 `PartyControllerTest,PartyItemImportTest` 21 + 24 통과 | ✅ |
| 사용자 시나리오 | 앱 재시작(`dev,session-jdbc` — 로그인 유지) 후 앱 패널. 실제 콘텐츠는 `코끼리`(SPEED, id 268) 하나만 잠깐 숨겼다가 되돌림(되돌린 뒤 목록 값이 이전과 같음) | 위 표 ①②④⑥⑩⑪ | ✅ |
| 배치 파일 일부 | 새 줄만 담은 시험 bat(스크래치) | ⑨ 표 | ✅ 부분 |
| 전체 회귀 | `./mvnw test`(리포트 비우고) | 635 passed, 0 failed, 0 skipped | ✅ |

본게임 상태 파일은 확인 전 복사본과 같음(cmp) — 콘솔 확인은 본게임이 SHOW 중이라 서버 조작 없이 응답만 가짜로 했다.

## 6. 수동 확인 / 결정 요청
**🙋 노트북 세팅 때(배치 파일 실제 실행)**
1. `party-start.bat` 더블클릭 → 1~2분 뒤 콘솔이 열리고, `PARTY_DIR\party-app.log` 를 VS Code 로 열면 한글 로그가 안 깨진다(⑧).
2. 앱이 뜬 상태에서 배치 파일을 한 번 더 더블클릭 → "[ERROR] Port 8082 is already in use" 후 멈추고, 떠 있는 앱의 `party-app.log` 는 그대로 커진다(⑨).

**⬜ ⑫ 결정 요청 — 휴대폰 예비 콘솔(`ALLOW_LAN=1`)을 쓸지**
- 사실: dev 시드 계정 `a@a.com`(ADMIN)·`test1~6@test.com` 의 비밀번호가 공개 저장소 코드에 있다. `party-status.md` 사용자 몫 5번이 예비 관리자로 권하는 `test1` 도 같다. 노트북에는 비밀번호 바꾸는 화면이 없고, 메일 재설정은 Brevo(오프라인 불가).
- 선택지: **A. 안 쓴다(권장)** — `ALLOW_LAN=0` 그대로, 콘솔은 노트북만. 할 일 없음. / B. 쓴다 — 노트북 DB 에 비밀번호를 아는 본인 계정을 넣고(집 PC 에서 승격한 계정 행을 옮기거나 노트북에서 가입), 시드 계정은 SQL 로 `role='USER'`·정지로 바꾼 뒤 잠근 핫스팟에서만 `ALLOW_LAN=1`.

## 7. checklist 점검
- 점검함: 노출(문제 관리 화면은 새 탭 · 정답 화면이라 TV 금지 안내 유지), 실패 경로(올리기 실패·로그아웃 중 반복), 동시성(콘솔 두 개 — 링크 제거·안내), XSS(새 패널 전부 `textContent`), 회귀(기존 올리기 결과 키 유지).
- 해당 없음: DB 스키마, 권한(경로 추가 없음).

## 8. 발견된 문제와 조치
- 없음(정리 작업). 정리 중 생긴 한 가지: 문제 관리를 새 탭으로 바꾸자 그 화면의 "← 콘솔" 링크가 두 번째 콘솔을 여는 길이 돼 문구로 바꿨다.

## 9. 전체 회귀
- `./mvnw test`(리포트 비우고): **635 passed, 0 failed, 0 skipped**(632 + 이번 3).

## 10. Regression 등록
- R-056 — 올리기 결과의 `hidden`·`unchecked`.
