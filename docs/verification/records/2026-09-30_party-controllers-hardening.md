# 파티 퀴즈 P-2 Part ④ — `/admin/party/**` 경로 + 공격·수정 3회
- 일자: 2026-09-30
- 유형: 신규 + 버그(이미 커밋한 Part ①·②·③ 의 결함 수정 포함)
- 우선순위: P1
- 판정: **조건부** — 서버는 자동 테스트와 실제 로컬 DB 로 확인. 남은 것은 실제 로그인·브라우저로만 볼 수 있는 항목(O-029)

## 1. 요청과 목적
- 사용자가 원한 것: Part ④(경로)를 추천안대로 진행하되, 그 전에 "추천대로 했을 때 생길 문제를 공격"하고, **공격 → 수정을 3번 돌려 코드에 반영**할 것.
- 개발자 확인 결과(결정 사항):
  - Part ④ 범위는 경로만(화면·사진 올리기·단건 등록은 P-3).
  - 다시 올릴 때 **빈 칸은 기존 값을 지우지 않는다**.
  - 로컬 DB 에 실제 TSV 347행을 넣는다.
  - 로컬 DB 의 회원 테이블 내용은 읽지 않는다(건수·`a@a.com` 의 상태 값만 조회).
- 진행 중 둔 가정(개발자 확인 전, 바꾸려면 말해 주면 됨):
  1. **스피드퀴즈 주제**: 여러 개를 고를 수 있고, 고른 주제가 떨어지면 다른 주제로 이어간다(턴이 중간에 끊기지 않게). 주제 하나는 10개라 한 팀 한 턴(15~25개)도 못 채운다.
  2. **새 게임**은 "낸 문제를 남길지"(`keepUsed`)를 반드시 받는다. true = 다음 판(점수·이력만 비움), false = 전부 비움.
  3. **띄운 문제 거두기**(`/cancel`)를 추가했다 — 죽은 영상·깨진 사진을 정답 공개 없이 내린다. 라운드 번호는 되돌리고 이력에 남기지 않으며 그 문제는 다시 나오지 않는다.
  4. **잘못 누른 종료**는 [대기]로 되돌릴 수 있다(점수·이력 유지).
  5. 스피드퀴즈 정답·패스는 **0.3초 안에 다시 누르면 세지 않는다**(`party.speed-min-answer-interval-ms`, 기본 300).
  6. 스피드퀴즈의 여유 1초는 정답·패스에만 있다. 제한 시간이 되면 상대 팀 시작·설정·재대결이 바로 되고, 여유 안의 정답은 세되 새 제시어는 뽑지 않는다.
  7. 사진 파일명은 경로 구분자·윈도우 금지 글자(`\ / : * ? " < > |`)·`..` 가 있으면 가져올 때 거부한다. 한글·공백은 된다.
  8. 다 쓴 중분류는 남은 수 0 으로 남긴다(목록에서 빠지지 않는다).
  9. `PartyDevCheck`(로컬 DB 수동 점검)를 저장소에 둔다. `-Dparty.devcheck=true` 없이는 돌지 않고, 진행 중인 게임이 있으면 멈춘다.

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 권한 | 비로그인은 조회·조작 모두 로그인 화면으로 | ✅ | `PartyControllerTest#anonymousIsRedirected`(7개 GET + POST) |
| 2 | 권한 | 일반 회원은 조회·조작·올리기 모두 403 | ✅ | `#memberIsForbidden` |
| 3 | 권한 | 관리자라도 CSRF 없는 POST 는 403, 상태·DB 불변 | ✅ | `#postNeedsCsrf` |
| 4 | 권한 | 사진은 관리자만, 폴더 밖 파일은 못 받음, 없는 사진 404 | ✅ | `PartyImageAccessTest` 4건 |
| 5 | 정상 | 본게임 조회 3종·조작 16종, 조작 응답 = 바뀐 콘솔 상태 + 남은 수 | ✅ | `#playsOneRound`·`#playsAudioWithHints`·`#cancelsShownQuestion`·`#newGameNeedsKeepUsed` |
| 6 | 예외 | 단계가 틀린 조작은 409 + 이유, 상태 불변 | ✅ | `#wrongPhaseIs409` |
| 7 | 예외 | 모르는 대분류·팀·묶음·숫자, 빠진 값은 400 | ✅ | `#badInputIs400` |
| 8 | 예외 | 화면이 본 버전과 다르면 409(`stale:true`), 한 번만 적용 | ✅ | `#staleVersionIs409`·`#speedErrors` |
| 9 | 정상 | 스피드퀴즈 조회 3종·조작 8종 | ✅ | `#playsSpeedQuiz`·`#speedSetupTakesSeveralTopics` |
| 10 | 정상 | TSV 올리기 결과(만든 수·덮어쓴 수·거부 행) | ✅ | `#importReportsResult` |
| 11 | 예외 | 빈 파일·UTF-8 아님(CP949·UTF-16)·file 없음은 400, 저장 0 | ✅ | `#importRejectsBadFile` |
| 12 | 정상 | 요약: 대분류별 전체·꺼짐·낼 수 있음·미완성 | ✅ | `#summaryCounts` |
| 13 | 정상 | **실제 TSV 7개가 로컬 MySQL 에 347행** | ✅ | `PartyDevCheck#importAndPlay`: ANIME 43·GAME 61·PERSON 32·QUIZ 50·SCREEN 44·SOUND 37·SPEED 80 |
| 14 | 정상 | 이모지 문제가 깨지지 않는다 | ✅ | 같은 점검: 17행을 DB 에서 읽어 TSV 와 글자 단위 비교 |
| 15 | 경계 | MySQL 에서 다시 올려도 중복·예외 없이 전부 덮어쓰기 | ✅ | 같은 점검: created 0 · updated 347 · 347행 (유니크 키 추가 뒤 재실행 포함 3회) |
| 16 | 정상 | 실제 곡으로 노래 1문제, QUIZ 1문제, 사진 1문제, 스피드퀴즈 1턴 → 앱 재시작 뒤 이어짐 | ✅ | `#importAndPlay` → 새 JVM `#afterRestart`: REVEAL·3라운드·1:1·이력 3 동일, 이어서 진행 |
| 17 | 정상 | 폴링 경로는 DB 를 읽지 않는다 | ✅ | 같은 점검: 폴링 20회 동안 SQL 0건(dev 는 SQL DEBUG 출력) |
| 18 | 연쇄 | `SecurityConfig` 무변경 | ✅ | `git diff` 에 없음 |
| 19 | 연쇄 | 기존 테스트 그대로 통과 | ✅ | 전체 591 = 481 + 110 |
| 20 | 정상 | **실제 로그인**한 브라우저에서 같은 경로가 돈다(세션 쿠키·CSRF 메타 토큰·1초 폴링) | 🙋 | 화면이 없고 `a@a.com` 비밀번호가 코드 시드와 다름 → O-029 |

## 3. 공격 → 수정 3회
각 회차: 공격 목록 → 재현되는 것은 실패 테스트 → 수정 → 파티 테스트 전체 → (실제 DB 점검).

### 1회차 — 추천안 자체를 공격 (직접, 코드·로컬 DB 로 확인)
| 결함 | 수정 | 회귀 |
|---|---|---|
| 새 게임·초기화가 버전을 0 으로 되돌려 보드가 화면을 건너뜀 | 새 상태의 버전은 현재 시각에서 시작하고 앞 버전보다 항상 큼 | R-034 |
| 다시 올리면 빈 칸이 기존 값을 지움 / URL 을 바꿔도 재생 불가 표시가 남음 | 빈 칸은 유지, 영상이 바뀌면 표시 해제 | R-035 |
| 파일명만 있으면 사진 문제가 "낼 수 있음" (TSV 94행에 파일명, 실제 파일 0개) | 파일이 폴더에 있어야 출제. `party.image-dir` + `/admin/party/images/**` | R-036 |
| 같은 조작이 두 번 적용됨 | POST 에 화면이 본 버전, 다르면 409 | R-037 |
| 400 과 409 를 구분할 수 없음 | `PartyInputException`(400) / `PartyGameException`(409) | — |
| 콘솔 상태가 호출마다 곡·문제 전체를 읽음 | 남은 수·주제는 별도 경로, 폴링 응답은 메모리만 | — |
실패 확인: 4건(`versionNeverGoesBackOnNewGame`·`versionNeverGoesBack`·`reimportKeepsValuesWhenCellBlank`·`reimportResetsInvalidFlagOnlyWhenVideoChanges`) → 수정 뒤 통과.

### 2회차 — 독립 검토자(읽기 전용) 12건 + 직접 2건
| 결함 | 수정 | 회귀 |
|---|---|---|
| 다시 뽑기가 실패하면 들고 있던 문제가 "안 쓴 것"이 되어 두 번 출제 | 뽑을 것이 확인된 뒤에만 되돌림 | R-038 |
| 거부했다고 보고한 행이 기존 문제를 일부 바꿔 놓음 | 검증을 전부 끝낸 뒤에만 기존 행을 건드림 | R-039 |
| [종료]가 일방통행, [새 게임]이 백업 없이 지움 | 종료 → 대기 복귀, 비울 때 `.bak` | R-040 |
| 저장 중 꺼지면 파일이 사라짐, 못 읽는 파일을 덮어씀 | 한 번에 바꿔치기(ATOMIC_MOVE), `.tmp` 복구, `.bad-시각` 보존 | R-041 |
| 파일명의 `?` 하나가 본게임 전체를 오류로 만듦 | 가져올 때 거부, 조회는 예외 없이 "없는 사진" | R-042 |
| 겹친 올리기가 같은 문제를 두 번 넣고 이후 모든 올리기가 500 / 파일 안 중복이 조용히 합쳐짐 / MySQL 에서 표기 수정이 반영 안 됨 | 올리기는 한 번에 하나, **유니크 키**, 파일 안 중복은 행 거부, 정답은 늘 다시 씀 | R-043 |
| 스피드퀴즈 주제당 10개 → 앞 팀이 다 쓰면 뒤 팀이 시작 못 함 | 주제가 떨어지면 다른 주제로 | R-044 |
| 시간이 끝난 뒤 1초 동안 상대 팀 시작 불가, 안 보이는 제시어 소모 | 여유는 정답·패스에만, 새 제시어 안 뽑음 | R-045 |
| (직접) 버전 확인은 사람의 더블클릭을 못 막음 — 로컬 응답이 몇 ms 라 두 번째 클릭은 새 버전을 들고 온다 | 정답·패스 0.3초 최소 간격 | R-037 |
| (직접) 다 쓴 중분류가 목록에서 사라짐 | 0 으로 남김 | R-038 테스트 안 |
| `wordOnBoard` 가 빠지면 제시어가 TV 로 되돌아감 | 필수 값 | `#speedErrors` |
| 경로가 어디를 가리키는지 로그에 없음 | 기동 때 절대 경로, 사진 폴더 없으면 WARN | — |
| 테스트가 주장과 다른 이유로 통과 / 점검 클래스가 IDE 에서 딸려 돎 | 사진 경로 테스트 고침, `PartyDevCheck` 는 opt-in + 진행 중 게임 있으면 중단 + 실제 사진을 덮지 않음 | — |
실패 확인: 13건 → 수정 뒤 통과.

### 3회차 — 독립 검토자 6건(수정의 미완성 1 + 화면 계약 5)
| 결함 | 수정 | 회귀 |
|---|---|---|
| 바꿔치기가 실패하면 `.tmp` 가 본 파일보다 새것인데 본 파일로 복원 / 복원 뒤 같은 버전 번호로 다른 내용 / `.bak` 이 한 칸 | 버전이 높은 쪽으로 복원, 복원 뒤 버전을 올림, `.bak-버전` 으로 여러 개 | R-046 |
| 죽은 영상은 TV 에 띄운 뒤에야 알 수 있고, 빠져나올 길이 정답 공개뿐 | 콘솔 상태에 뽑은 순간부터 영상·사진 정보, `/cancel` | R-047 |
| 새 게임이 낸 문제 기록까지 지워 2판·3판에 같은 문제 | `keepUsed` | R-048 |
| 스피드 설정이 주제 하나만 받음(목업은 복수 선택) | 주제 여러 개 | R-049 |
| 목업이 그리는 값이 JSON 에 없음 | 두 팀 제한 시간(`limits`), 난이도, 이번 턴 제시어 기록(`log`), 서버가 잰 타이머(`timerElapsedSeconds`), 사진 주소 인코딩 | — |
| 재시작하면 세션이 메모리라 창이 전부 로그아웃 | 코드 변경 없음 — 행사 때 `dev,session-jdbc` 로 띄운다(이 PC 에서 기동 확인). P-3 화면은 로그인 화면 응답을 "로그아웃됨"으로 처리 | O-030 |
실패 확인: 4건(`restoresNewerTempSnapshot`·`restoresAfterRestart` ×2·`newGameKeepsBackup`) → 수정 뒤 통과. 3회차에서는 상태 전이의 새 버그는 나오지 않았다.

## 4. 변경 사항
- 신규(main): `PartyController`·`PartySpeedController`·`PartyItemController`·`PartyExceptionHandler`·`PartyWebConfig`·`PartyImageStore`·`PartyInputException`·`PartyStaleException`·`PartyVersioned`
- 수정(main, 전부 파티 패키지): `PartyGameService`·`PartyGameState`·`PartyBoardView`·`PartyConsoleView`·`PartySnapshotHolder`·`PartySongBand`·`PartyItem`·`PartyItemImportService`·`PartySpeedQuizService`·`PartySpeedState`·`PartySpeedBoardView`·`PartySpeedConsoleView`
- 신규(test): `PartyControllerTest` 16 · `PartyImageAccessTest` 4 · `PartyDevCheck`(수동 점검, 전체 실행에 안 걸림). 기존 3개 클래스 +33건.
- 파티 밖 자바 파일 수정: 없음.
- **DB·설정 변경**
  - `schema.sql`: `party_item` 에 `UNIQUE KEY uk_party_item_category_answer (category, answer)`. **집 PC 로컬 DB 에 같은 ALTER 실행**(중복 0건 확인 뒤). 다른 PC·행사 노트북은 `schema.sql` 로 만들면 포함된다.
  - 로컬 DB `party_item` 에 347행 적재(개발자 승인).
  - `application-dev.properties`: `party.image-dir`·`party.speed-state-file`(+ 기존 `party.state-file`), 전부 `../party-images` 아래.
  - `src/test/resources/application.properties`: `party.speed-min-answer-interval-ms=0`.

## 5. 영향 범위 분석
- 새 경로는 전부 `/admin/party/**` — 기존 매처(`/admin/**` → ADMIN)가 그대로 걸린다. 사진 경로도 같다.
- `PartyExceptionHandler` 는 `basePackages = com.kh.game.party` 로 파티 컨트롤러에만 적용. 기존 `GlobalExceptionHandler` 무변경.
- `PartyItem` 에 유니크 제약을 붙였다 → H2 테스트 DB 에도 생긴다. 운영 DB 에는 테이블 자체가 없다(이 브랜치는 운영에 올라가지 않는다).
- 스냅샷 파일 형식: `PartySpeedState.setup.topic`(문자열) → `topics`(집합). 옛 파일의 `topic` 은 무시된다(모르는 필드 허용). 옛 파일은 점검이 만든 것뿐이라 지웠다.

## 6. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| Integration | `./mvnw test -Dtest='Party*Test'` | 110 passed (컨트롤러 16 · 본게임 43 · 사진 4 · 가져오기 18 · 스피드 29) | ✅ |
| 실패 테스트 선행 | 회차마다 수정 전 실행 | 4 + 13 + 4 = 21건 실패 확인 → 수정 뒤 통과 | ✅ |
| 전체 회귀 | `./mvnw test` (JDK 17, Docker 29.4.2) | Tests run: 591, Failures 0, Errors 0, Skipped 0, 3분 54초 | ✅ |
| 실제 DB(MySQL 8.0.46) | `./mvnw test -Dparty.devcheck=true -Dtest=PartyDevCheck#importAndPlay` → `#afterRestart` (1·2·3회차 뒤 각각) | §2 의 13~17 | ✅ |
| 기동 | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` | 기동, 비로그인 `/admin/party/state` 302 | ✅ |
| 기동(세션 DB) | `-Dspring-boot.run.profiles=dev,session-jdbc` | 기동 9.2초, health 200, 쿠키 `SESSION` | ✅ |
| 전체 실행에 점검이 딸려 돌지 않는지 | 전체 테스트 로그에서 `PartyDevCheck` 검색, 실행 뒤 `D:\dev\party-images` 확인 | 0건, 폴더 비어 있음 | ✅ |
| 실제 로그인으로 경로 호출 | curl 로그인 1회 시도 | **실패** — 이 DB 의 `a@a.com` 은 코드 시드와 다른 비밀번호. 재시도하지 않음 | ❌ → 🙋 O-029 |

## 7. 수동 확인 시나리오 (🙋 O-029, P-3 화면이 생긴 뒤)
1. [전제] `dev,session-jdbc` 로 기동, 관리자 로그인, 콘솔과 보드를 다른 창에 연다.
2. [행동] 문제 뽑기 → 띄우기 → 정답. 스피드퀴즈 한 턴(정답을 빠르게 두 번 눌러 본다).
3. [기대 결과] 보드가 1초 안에 따라온다. 빠른 두 번째 정답은 세지지 않는다. 앱을 다시 켠 뒤 새로고침하면 로그인과 게임이 그대로다.
- 결과: ☐ 통과 ☐ 실패

## 8. checklist 점검
- 점검함: §1 엔티티 ↔ `schema.sql` ↔ 로컬 DB(유니크 키), 설정은 dev·test 에만 · §2 빈 파일·인코딩·긴 값·금지 글자·경계 시각 · §3 비로그인·낮은 권한·CSRF·경로 밖 파일, 입력 화이트리스트(enum·정규식) · §4 단계 전이 거부, 중복 요청(버전·최소 간격), 겹친 올리기, 재시작 복구 · §6 거부 이유가 응답에 실림 · §7 H2 와 MySQL 차이(대소문자·끝 공백·이모지 비교)는 실제 DB 점검으로 확인, 절대 경로 로그.
- 해당 없음: 화면, 멀티.

## 9. 발견된 문제와 조치
- §3 의 표.
- 로그인 검증 실패로 `a@a.com` 의 로그인 실패 횟수가 1 올라갔다(잠금 아님, 다음 로그인 성공 때 0 으로 돌아간다).
- 점검이 만든 상태 파일(`D:\dev\party-images\*.json`)은 지웠다. 로컬 DB 의 347행은 남겼다.

## 10. 미검증 영역과 남은 위험
- 실제 로그인·브라우저 폴링·CSRF 메타 토큰 / 화면 없음, 시드 비밀번호 불일치 / O-029.
- 브라우저 타이머와 서버 판정(여유 1초)·0.3초 간격이 실제 진행에서 맞는지 / 화면 없음 / 리허설 1.
- 콘텐츠·운영 결정(주제당 제시어 수, 행사 실행 프로파일, 상대 경로, 고아 행, 엑셀 저장) / 결정 필요 / O-030.
- 두 검토자는 코드를 읽기만 했다(실행 없음). 지적의 재현은 이 기록의 테스트가 한 것이다.

## 11. Regression 등록
- R-034 ~ R-049 (16건).
