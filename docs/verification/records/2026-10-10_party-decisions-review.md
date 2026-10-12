# 파티 퀴즈 10-10 결정 반영 + 점검 3회 (팀 이름 · 문제 관리 · 엑셀 따옴표 · 실행 배치 · 배치 스케줄)
- 일자: 2026-10-10 (집 PC)
- 유형: 신규 + 수정
- 우선순위: P1 (행사 진행 — `party` 브랜치 · 로컬 전용. 단 `BatchScheduler` 는 공용 코드)
- 판정: **수용 가능** — 자동 테스트·배치 파일 실제 실행 ✅, 새 화면은 10-12 사용자가 브라우저로 확인 ✅ (처음 판정은 조건부)

## 1. 요청과 목적
- 사용자 결정(10-10 채팅): 사진 업로드는 남은 개발 뒤 여유가 있으면(그 전까지 폴더에 직접 복사) · 결과 JSON 은 안 만든다 · **팀 이름 변경 추가** · O-030 은 추천대로(③ 실행 배치 파일, ④ A = 지우지 않고 숨기기, 숨기기만, ⑤ B = 편집 도구 규칙 + 엑셀 따옴표 풀기).
- 진행 방식(사용자 지시): 추천대로 구현 → "생길 수 있는 문제 파악 → **심각**이면 수정" 을 3회.
- 심각의 기준(이 작업에서 정함): 행사 진행을 막음 · 참가자에게 정답 노출 · 데이터·게임 상태 손실/손상 · MC 가 알아채기 어려운 잘못된·중복 출제 · 보안 구멍.
- 가정: ④ A 의 숨기기는 문제 목록 화면에 붙어야 하므로 원래 P-3 ⑤ 였던 **문제 올리기·목록 화면을 이번에 만들었다**.

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | 팀 이름을 바꾸면 보드·콘솔에 보이고, 앞뒤 공백은 지운다 | ✅ | `PartyGameServiceTest#renamesTeams`, `PartyControllerTest#renamesTeams` |
| 2 | 예외 | 빈 이름·11자 이상·두 팀 같은 이름은 거부되고 이름이 안 바뀐다 | ✅ | `#rejectsBadTeamNames`, 컨트롤러 400 |
| 3 | 정상 | 팀 이름은 [새 게임] 두 종류와 재시작 뒤에도 남는다 | ✅ | `#teamNamesSurviveNewGameAndRestart` |
| 4 | 정상 | 문제 목록이 대분류별 상태(낼 수 있음·숨김·미완성+이유)를 보여 준다 | ✅ | `PartyControllerTest#listsItemsWithStatus` |
| 5 | 정상 | 숨긴 문제는 출제·남은 수에서 빠지고 다시 켜면 돌아온다 | ✅ | `#hidesAndRestoresItem` |
| 6 | 권한 | 목록·숨기기·팀 이름·문제 관리 화면은 관리자만, POST 는 CSRF 필요 | ✅ | `#useToggleRejectsBadRequests`, `PartyPageControllerTest#contentPageNeedsAdmin` |
| 7 | 정상 | 다시 올린 파일의 대분류에서 빠진 켜진 문제를 알리되 숨기지 않는다 | ✅ | `PartyItemImportTest#reportsItemsMissingFromFile` |
| 8 | 경계 | 열 수가 틀린 줄이 있는 대분류는 빠진 문제를 알리지 않는다 | ✅ | `#skipsMissingReportWhenColumnsAreBroken` |
| 9 | 정상 | 엑셀식 `"…""…"` 칸은 원래 글자로, 손으로 쓴 따옴표는 그대로 | ✅ | `#unwrapsExcelQuotedCells`, `#keepsPlainQuotes` |
| 10 | 정상 | 배치 파일이 사진·상태 폴더를 고정하고 `dev,session-jdbc` 로 띄운다 | ✅ | 실제 실행: 로그의 `Party image dir`·스냅샷 경로 = 인자로 준 폴더 |
| 11 | 경계 | 인터넷이 없어도 배치 파일로 뜬다(Maven 파일을 받아 둔 뒤) | ✅ | 원격 저장소를 끊은 설정(`mirror id=central → 127.0.0.1:9`)으로 기동 10초 |
| 12 | 보안 | 배치 파일 기본값은 이 노트북에서만 열린다, `ALLOW_LAN=1` 이면 열린다 | ✅ | `netstat`: 기본 `127.0.0.1:8082` · 이 PC 주소로 접속 거부 / `ALLOW_LAN=1` 은 `0.0.0.0:8082` + 경고 |
| 13 | 경계 | 배치 스케줄이 꺼지면 기동해도 아무것도 안 오르고, party dev 기본값이 꺼짐 | ✅ | `BatchSchedulerToggleTest` 3건 + 실제 로그 "배치 스케줄 꺼짐" 22줄 |
| 14 | 정상 | 문제 관리 화면에서 올리기·결과·목록·숨기기, 콘솔 팀 이름 저장 | ✅ | 2026-10-12 사용자 브라우저 확인: 팀 이름 변경 적용 · `quiz.tsv` 올리기 "덮어씀 50" · 한 문제 숨기기 → 다시 켜기 (아래 6) |

## 3. 변경 사항
- `party/PartyGameService.java` — `renameTeams`, `newGame` 이 팀 이름을 이어받음(기본 이름이면 저장 안 함 — 기존 "새 게임 뒤 파일 없음" 동작 유지).
- `party/PartyController.java` — `POST /teams`.
- `party/PartyItemController.java` — `GET /items/list`, `POST /items/{id}/use`, 올리기 응답에 `missing`.
- `party/PartyItemImportService.java` · `PartyImportResult.java` — 엑셀 따옴표 풀기, `missing`(+ 열 수 틀린 대분류 제외).
- `party/PartyPageController.java` — `GET /admin/party/content`.
- `templates/admin/party/content.html` · `static/js/admin/party-content.js` · `static/css/admin/party-content.css` **신규**. `console.html`·`party-console.js`·`party-console.css` — 팀 이름 칸, [문제 관리] 링크.
- `batch/BatchScheduler.java`(**공용 코드**) — `game.batch.schedule-enabled`(기본 true). `application-dev.properties`(party) — false.
- `scripts/party-start.bat` **신규**(ASCII, CRLF).
- 테스트: `PartyGameServiceTest` +3 · `PartyControllerTest` +4 · `PartyItemImportTest` +4 · `PartyPageControllerTest` +1 · `BatchSchedulerToggleTest` **신규** 3.
- DB·설정 변경: **스키마 변경 없음**(`use_yn` 은 원래 있음). 설정 키 `game.batch.schedule-enabled` 추가(기본 true — main 에 들어가도 동작 변화 없음).

## 4. 영향 범위 분석
- `PartyImportResult` 생성자: 서비스 2곳만(검색). `BatchScheduler.scheduleTask` 는 기동·전체 새로고침·관리자 켜기 모두 거침 → 플래그 우회 없음(2회차 리뷰 확인). 수동 실행은 `executeManually` 라 무관.
- 다른 예약 작업(`WebSocketHttpSessionGuard`·presence·세션 만료 정리)은 외부 네트워크·곡·문제를 건드리지 않음(2·3회차 리뷰 확인). 파티 경로는 외부 서비스를 부르지 않음.

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| Unit(red→green) | 각 기능마다 실패 테스트 먼저 → 구현 | 팀 이름·목록·숨기기·따옴표·빠진 문제·배치 스케줄 모두 red 확인 후 green | ✅ |
| 전체 회귀 | `./mvnw test`(리포트 비우고) | 1회차 후 628 · 2회차 후 **630 passed, 0 failed, 11 skipped**(Docker 꺼짐 → Redis) | ✅ |
| 배치 파일 | `cmd /c scripts\party-start.bat <임시폴더>` 4회 + `ALLOW_LAN=1` 복사본 1회 | 아래 8 의 문제를 고친 뒤 정상 | ✅ |
| 오프라인 기동 | 원격 저장소를 끊은 Maven 설정으로 `spring-boot:run` | 기동 10초 | ✅ |
| 독립 리뷰 | 회차마다 맥락 없는 리뷰 에이전트 1개(읽기 전용) + 직접 점검 | 아래 8 | ✅ |
| 브라우저 | 사용자 확인(10-12) — 아래 6 의 2~4 | 팀 이름·덮어씀 50·숨기기/다시 켜기 통과 | ✅ |

## 6. 수동 확인 시나리오 (2026-10-12 사용자 실행 — 2·3·4 통과, 화면 깨짐·거부 줄 표시는 따로 보고 없음)
1. [전제] `scripts/party-start.bat` 로 기동(또는 평소대로), 관리자 로그인.
2. 콘솔 설정 → 팀 이름 두 칸 입력 → [팀 이름 저장] → 보드 상단·점수판 이름이 1초 안에 바뀜. 11자·같은 이름은 거부 안내.
3. 콘솔 [문제 관리] → `docs/party-content/quiz.tsv` 올리기 → "새로 0 · 덮어씀 50 · 거부 0", 빠진 문제 없음.
4. 대분류 표에서 QUIZ → 목록 50 → 한 문제 [숨기기] → 표의 숨김 1 → 콘솔 새로고침 뒤 남은 수 1 감소 → [다시 켜기].
5. [기대] 화면 깨짐 없음(라이트·다크), 거부 줄이 있으면 "n번째 줄: 이유" 로 보임.

## 7. checklist 점검
- 점검함: 영향 범위(위 4) · 권한·CSRF · XSS(전부 `textContent`) · 중복 조작(업로드·숨기기 `busy`, 팀 이름은 version) · 상태 전이·재시작(팀 이름 스냅샷) · 로컬/운영 차이(배치 스케줄 기본값 true 로 main 무영향) · 테스트 DB 차이(대소문자 비교를 DB 의 `utf8mb4_unicode_ci` 와 맞춤).
- 해당 없음: 스키마·마이그레이션, 멀티플레이.

## 8. 발견된 문제와 조치 (점검 3회)
| 회차 | 문제 | 심각도 | 조치 |
|---|---|---|---|
| 1 | 배치 파일이 dev 프로필로 배치를 전부 켬 → 오프라인 02:00 YouTube 검사가 네트워크 오류를 "삭제"로 보고 **노래 전부 끔**, 자동 복구 없음 | 심각 | `game.batch.schedule-enabled` + bat 에서 false (R-053) |
| 1 | TSV 에서 지우거나 정답을 고친 옛 행이 계속 출제되는데 어디에도 안 보임(인물 교체를 앞두고 있음) | 심각 | 올리기 결과 `missing` + [모두 숨기기] (R-054) |
| 1 | 앱 로그가 창으로 나가 창을 클릭하면(빠른 편집) 로그를 쓰는 요청이 멈춤 + dev SQL 로그 | 심각 | 로그를 파일로, SQL 로그 끔 |
| 1 | (직접 실행에서 발견) UTF-8 한글 + `chcp 65001` 에서 cmd 가 줄을 잘못 읽어 주석 일부(`uTube`)를 명령으로 실행 | 심각 | 배치 파일을 ASCII 로만 |
| 1 | (직접 실행에서 발견) 이 PC 는 현재 폴더의 실행 파일을 찾지 않음(`NoDefaultCurrentDirectoryInExePath=1`) → `mvnw.cmd` 못 찾음 | 심각 | `.\mvnw.cmd` |
| 1 | 문제 관리 화면 거부 줄 이유가 `undefined`(필드는 `reason`) | 버그 | 수정 |
| 2 | 배치 차단이 bat 한 곳뿐 — mvnw·IDE·launch.json 으로 재시작하면 1회차 위험 재발 | 심각으로 올림 | party dev 프로필 기본값 false (R-053) |
| 2 | 1회차 수정의 결함: 탭이 빠진 줄은 정답 칸이 어긋나 멀쩡한 문제를 "빠짐"으로 띄움 → [모두 숨기기]로 조용히 숨겨질 수 있음 | 수정(자기 결함) | 열 수가 틀린 줄이 있는 대분류는 `missing` 계산 안 함 (R-054) |
| 3 | 시드 관리자 비밀번호가 공개 저장소에 있어, 8082 가 와이파이에 열리면 참가자가 로그인해 **정답을 보고 MC 를 로그아웃** 시킬 수 있음(이번 변경이 아니라 원래 있던 것) | 심각 | bat 기본 `--server.address=127.0.0.1`, 휴대폰 예비 콘솔은 `ALLOW_LAN=1` + 경고 |
| 1~3 | 나머지 보통·경미 13건 | — | O-034 에 기록(사용자 지시: 심각만 수정) |
| 3 | 운영(main)의 같은 원인: 네트워크 오류 = 영상 무효 → 곡 끔 | main 범위 | O-035 로 분리 |

## 9. 미검증 영역과 남은 위험
- 엑셀이 쉼표 든 칸도 감싸는지(O-034 ⑦) — 엑셀로 실제 저장해 봐야 앎.
- 실제 노트북·핫스팟에서 배치 파일(리허설 1).
- O-034 ⑫: 휴대폰 예비 콘솔을 쓰려면 관리자 비밀번호 바꾸는 방법이 필요.

## 10. Regression 등록
- R-053, R-054
