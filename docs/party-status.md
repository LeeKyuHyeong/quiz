# 12-26 파티 퀴즈 — 한 장 현황 (2026-09-30 21:30 기준, 집 PC)

> 진입점. 근거·배경은 `party-quiz-plan.md`, 문제 목록은 `party-content/`, 화면은 `party-mockup/index.html`. **`party` 브랜치**에만 커밋한다(main 에 올리면 `.tsv`·`.html` 이 CI 필터 밖이라 배포가 돈다).

## 무엇을 만드나
2026-12-26(토) 1박 2일, **17명(남 9 · 여 8, MC = 본인, 8 vs 8 — 10-06 변경)**, 1995~2001년생. 기존 노래맞추기 사이트에 **대분류 7개**를 붙여 MC 가 TV 앞에서 진행하는 **남 vs 여 팀전**. 운영 서버는 안 건드리고 **노트북 로컬 인스턴스**로만 돈다.

## 파일 지도
| 파일 | 역할 | 줄 |
|---|---|---|
| `docs/party-quiz-plan.md` | 마스터 계획서. §0 결정표 · §1 대분류 · §2 격리·DDL · §4 화면·패턴 · §7 검증 · §11 **착수 체크리스트** | 350 |
| `docs/party-content/README.md` + TSV 7개 | 문제 초안, 열 정의, **MC 판정 규칙 표**, 선정 기준 | 50 + 347행 |
| `docs/party-mockup/index.html` | 콘솔·보드·플레이어·스피드 콘솔·스피드 보드 5화면 목업(상태 버튼) | 476 |
| `docs/verification/records/2026-09-29_party-quiz-plan.md` | 검증 기록(문서만, 코드 0) · O-025 | 61 |
| `src/main/java/com/kh/game/party/` | 파티 코드 전부(32개 파일). 기존 자바 파일은 수정 없음 | |
| `src/test/java/com/kh/game/party/` | 자동 111건: `PartyControllerTest` 16 · `PartyGameServiceTest` 43 · `PartySpeedQuizServiceTest` 30 · `PartyItemImportTest` 18 · `PartyImageAccessTest` 4. `PartyDevCheck` 는 로컬 DB 수동 점검(전체 실행에 안 걸림) | |
| `docs/verification/records/2026-09-29_party-item-ddl.md` · `…_party-item-import.md` · `2026-09-30_party-game-service.md` · `2026-09-30_party-speed-quiz.md` · `2026-09-30_party-controllers-hardening.md` | 착수·Part ①~④ 검증 기록(마지막 것이 공격·수정 3회) | |

## 개발 진행 (P-2 서버를 Part 4개로 나눔)
| Part | 내용 | 상태 | 증거 |
|---|---|---|---|
| 착수 | 기준선 테스트, `party_item` 테이블 | **완료** `9fd29b1` | 481 통과 · dev 기동 |
| ① | `PartyItem` 엔티티 + TSV 가져오기 | **완료** `5f54ec6` | 실제 TSV 7개 거부 0 |
| ② | 본게임 규칙 `PartyGameService` | **완료** `b3d73f2` | |
| ③ | 스피드퀴즈 `PartySpeedQuizService` | **완료** `e77a8aa` | |
| ④ | `/admin/party/**` 컨트롤러 3개 + 사진 경로 + 공격·수정 3회(결함 20여 건 수정, R-034~R-049) | **완료**(이 커밋) | 전체 591 통과 · 실제 MySQL 에 347행 · 재시작 뒤 이어짐 |
| P-3 | 화면(console → board → player → speed) + 사진 올리기·문제 목록 + `youtube-player.js` 옵션 + 사이드바 1줄 | **다음** | |

전체 테스트 수: **592**(기존 481 + 파티 111), 0 Skipped(Docker 켠 집 PC). Docker 없는 회사 PC 는 Redis 13건이 Skipped 로 나오는 것이 정상.

## 이어서 할 일 — P-3 화면
서버는 끝났다. 남은 것은 화면과, 화면이 있어야만 볼 수 있는 확인(O-029: 실제 로그인·브라우저 폴링·타이머).
정해진 것: **스피드퀴즈는 두 팀이 서로 다른 주제로 붙는다**(턴마다 설정에서 주제 선택 — 콘솔 화면에 반영). 시작 전에 정할 것(O-030): 행사 실행 프로파일, 노트북의 사진·상태 폴더 절대 경로.

## 서버 계약 (P-3 화면이 지킬 것 — plan §11-2 의 초안을 대신한다)
**조회(1초 폴링, DB 를 읽지 않음)**
- `GET /admin/party/state` 보드·플레이어 창 · `GET /admin/party/console/state` 콘솔
- `GET /admin/party/speed/state` · `GET /admin/party/speed/console/state`

**조회(폴링 금지 — DB 를 읽는다. 화면을 열 때와 조작 응답에서 받는다)**
- `GET /admin/party/remaining` 대분류 → 중분류 → 남은 수(`SONG` 은 5년 묶음, 각 대분류에 `전체`) · `GET /admin/party/speed/topics` · `GET /admin/party/items/summary`

**조작(POST, CSRF 헤더, 모든 요청에 `version` = 화면이 마지막으로 받은 버전)**
- 본게임 `/admin/party/` + `pick`(category, subCategory — `SONG` 이면 노래) · `show` · `cancel` · `play` · `pause` · `restart` · `hint` · `wrong`(team) · `correct`(team) · `miss` · `score`(team, delta) · `next` · `scores` · `wait` · `new`(**keepUsed** 필수) · `end`
- 스피드 `/admin/party/speed/` + `setup`(topic 여러 개 가능, limitA, limitB, mode, **wordOnBoard** 필수) · `start`(team) · `correct` · `pass` · `undo` · `finish` · `rematch` · `reset`
- 올리기 `POST /admin/party/items/import`(multipart `file`, UTF-8 TSV) · 사진 `GET /admin/party/images/<파일명>`
- 성공 200 `{success:true, state:<콘솔 상태>, remaining|topics:{…}}` · 값이 틀림 400 · 지금 할 수 없음 409 `{success:false, message}` · 화면이 낡음 409 `{…, stale:true}`

**화면 구현 규칙**
1. **409 를 받으면 상태를 다시 받는다.** `stale:true` 는 "조작이 적용되지 않았다"는 뜻이다(다른 창이 먼저 바꿨거나 같은 버튼을 두 번 보냄).
2. 버전은 "같은가 다른가"만 본다(크기 비교 금지). 스피드퀴즈 보드는 버전이 같아도 시간이 흐르면 `phase`·`remainingSeconds`·`word` 가 바뀐다 — 버전이 같다고 다시 그리기를 건너뛰지 않는다.
3. 스피드퀴즈 타이머는 서버의 `remainingSeconds` 로 그린다. 화면 시계로 0 을 계산해 `/finish` 를 보내지 않는다(서버가 자기 시계로 끝낸다). 본게임 타이머는 `timerElapsedSeconds`.
4. 단계는 `WAIT → READY → SHOW → REVEAL → WAIT`. READY 는 콘솔에만 있고 보드에는 `WAIT` 로 보인다. 콘솔은 READY 에서 `state.item`(영상 ID·시작초·사진 주소)으로 **띄우기 전에 재생·사진을 확인**하고, 안 되면 [다시 뽑기]. 띄운 뒤에 안 되면 `cancel`.
5. 정답·인정답안·보조·출처·난이도는 콘솔의 `card` 에만 있다. 보드 상태에는 정답 공개(`reveal`) 전까지 없다.
6. 응답이 JSON 이 아니거나 로그인 화면으로 넘어가면 "로그아웃됨"으로 표시한다(세션 만료·앱 재시작).
7. 조작 버튼은 응답이 올 때까지 잠근다. 스피드퀴즈 정답·패스는 서버가 0.3초 안의 두 번째를 409 로 거른다.
8. fetch 에는 `Accept: application/json` 을 붙인다(기존 공통 오류 처리가 이 헤더로 JSON/HTML 을 가른다).

## 구현하면서 정한 것 (plan 과 다르거나 plan 에 없던 것)
- `party_item`: `difficulty INT`, `use_yn VARCHAR(1)`, 날짜 `DATETIME(6)`, **유니크 키 (category, answer)**. 난이도 하/중/상 = 1/2/3.
- 제시 값은 AUDIO/IMAGE/TEXT 3종만 받는다(VIDEO 는 안 씀).
- 가져오기: URL 없는 AUDIO·파일명 없는 IMAGE 도 저장(출제에서만 제외). 대분류 + 정답이 같으면 덮어쓰되 **빈 칸은 기존 값을 지우지 않는다**(값을 비우는 것은 화면에서). `use_yn` 은 건드리지 않고, 영상이 바뀌면 재생 불가 표시를 푼다. 한 파일 안의 같은 대분류·정답은 두 번째 행 거부. `비고`·`추천자`·`제목스포` 는 저장 안 함. UTF-8 만 받는다.
- **사진 문제는 파일이 `party.image-dir` 폴더에 실제로 있어야 출제된다.** 파일명에 경로·윈도우 금지 글자가 있으면 거부(한글·공백은 됨).
- 본게임: 단계 READY 추가 · 라운드 번호는 [띄우기] 때 오름 · 띄우기 전 다시 뽑으면 앞 문제는 소모 안 됨(단, 다시 뽑기가 실패하면 들고 있던 문제는 그대로) · 판정 정정은 점수 ± 만 · 1점 고정 · 노래는 RETRO·비인기곡 포함 · **[거두기](`cancel`)로 정답 공개 없이 내림** · **[새 게임]은 낸 문제를 남길지 선택** · 잘못 누른 [종료]는 [대기]로 복귀.
- 스피드퀴즈: **두 팀이 다른 주제로 붙는다** — 턴 사이에 설정을 바꿔 팀마다 주제를 고른다. 주제 여러 개 가능(떨어지면 다른 주제로 이어감) · [재대결](결과만)과 [초기화](전부) · 서버 시계로 판정, 여유 1초는 정답·패스에만 · 0.3초 안의 두 번째 정답·패스는 거부 · 되돌리기 1단계 · 이번 턴 제시어 기록을 콘솔에 제공 · 팀 이름·"다음 출제자!" 안내는 화면 몫.
- 상태 파일: 바뀔 때마다 저장, 비울 때 `.bak-버전`, 못 읽으면 `.bad-시각` 으로 남김. 버전은 절대 줄지 않는다.
- 설정: `party.image-dir` · `party.state-file` · `party.speed-state-file`(dev 만, `../party-images` 아래, 비어 있으면 메모리·사진 없음) · `party.speed-min-answer-interval-ms`(기본 300).

## PC 별 주의
| | 집 PC | 회사 PC |
|---|---|---|
| 역할 | 빌드·테스트·기동 | 분석·문서(기동 안 함) |
| 로컬 DB | **MySQL 8.0.46**(MariaDB 아님, O-030). `party_item` 생성(유니크 키 포함) + **문제 347행 적재됨**. `a@a.com` 비밀번호는 코드의 시드와 다르다 | DB 없음. 기동하려면 `schema.sql` 의 `party_item` CREATE 를 먼저 실행 |
| Docker | 있음 → 0 Skipped | 없음 → Redis 13건 Skipped |
| 출력 스타일 | `.claude/settings.local.json` 의 `"outputStyle": "Learning"` 을 지움(09-29) | 같은 줄이 있으면 Claude 가 코드 일부를 비워 두고 구현을 넘긴다 → 같은 줄을 지운다(이 파일은 git 미추적) |

회사 PC 에서 시작할 때: `git fetch` → `git switch party` → `git pull`. 이 문서 → "이어서 할 일" 순서로 보면 된다.

## 확정된 것 (바뀌면 §0 표를 고친다)
- 대분류: SONG(기존 `song` 읽기만, 5년 묶음) · SCREEN(장면 캡처 1장) · ANIME · GAME(게임 5개, 정답은 캐릭터·유닛) · PERSON(사진만) · QUIZ(초성·이모지) · SOUND(TV 프로그램 시그널·CM송) + 몸풀기 SPEED(스피드퀴즈, 팀별 제한 시간).
- 진행 패턴 3종: A 정지형(IMAGE·TEXT) / B 재생형(AUDIO, 외침 → 일시정지 → 오답이면 상대 팀) / C 재생형+힌트(GAME 만 3개).
- 점수 1점 = 탈출 1명(현장 처리, 앱은 A vs B 점수). 라운드 수 제한 없음. 판정은 먼저 손 든 팀만.
- 화면: 노트북 콘솔 + TV 보드(확장 디스플레이, 1초 폴링) + 플레이어 창(휴대폰 콘솔 예비). 상태는 서버 싱글턴 + JSON 스냅샷. 정답은 REVEAL 전엔 보드 API 에 안 실림. **연습경기(10-06)**: 별도 화면 없음 — 한 판 하고 [새 게임 · 이력 유지]로 본게임 시작하면 연습 때 띄운 문제는 다시 안 나옴([전부 초기화]는 당일 시작 전 1회). 타이머 없음, 재생형은 양 팀 오답 뒤 자유 도전.
- 코드: `com.kh.game.party` 패키지, `party_item` 테이블, 경로 `/admin/party/**`. 기존 파일 수정은 `youtube-player.js` 옵션 · 사이드바 1줄 · `schema.sql`. **`party` 브랜치**, main 금지(운영 `validate` 실패).
- 저작권: 로컬 + 현장 공연(제29조) + 사적 복제(제30조) 여지. 사진·캡처·클립 파일은 노트북 폴더만, git·서버·클라우드 금지. YouTube 다운로드 금지. 파티 플레이어는 480×270 보이게(O-025 는 기존 모드 문제로 등록만).
- 선물 ≈ 10.5만원: 승리 팀 핸드크림 8종 + 립밤 8종 + 상자 8(탈출 순서대로 조합 선택, 무향 1종) / 진 팀 페레로로쉐 5구 5 + 로아커 4 중 선택. 여행 비용·정산은 저장소 밖(공개 저장소) 로컬 파일. 12/19 도착, 예비 스타벅스 e-gift.

## 콘텐츠 현황 (`docs/party-content/`)
| 파일 | 행 | 상태 | 남은 일 |
|---|---|---|---|
| `quiz.tsv` | 50 | **완성** | 없음 |
| `speed.tsv` | 80 | **완성** | 없음 |
| `sound.tsv` CM송 | 17 | **목록 확정** | 클립 URL·컷 위치(브랜드 직전) |
| `sound.tsv` TV 프로그램 | 20 | 목록 1차 | △ 6개(1박2일·런닝맨·나혼산·신서유기·하트시그널·환승연애) 듣고 판단, 무릎팍·해투·전국노래자랑 클립 조건, 우결 추가 여부 |
| `screen.tsv` | 44 | **목록 확정**(09-29, 쉬운 건 쉬운 대로) | 캡처 수집(비고의 장면 제안) |
| `anime.tsv` | 43 | **목록 확정**(09-29) | 클립·캡처. [듣고 판단] 16개 = 한국판 OP 제목 직전 컷 위치 |
| `game.tsv` | 61 | **목록 확정**(09-29) 캐릭터 맞히기 41(롤 4·로아 10·메이플 13·스타 4·옵치 10) + 게임 맞히기 20 | 대사 원문은 `비고` 나무위키 링크로 한 줄씩 확인(자동 추출 신뢰 불가), 이름·스킬명 들리는 대사 회피, 로아 직업 클립 존재 확인 | 로아 직업 클립 존재 확인, 줄임말 확인, 클립 |
| `person.tsv` | 32 | 공통 16 사진 시점 **2005~2015 로 확정**(10-06) | 여팀 강세 재편(정국·지드래곤 → 공통, 뉴진스·아이브 개인·김지원 보강)은 MC 가 직접 결정 · 사진 수집 |

공통 규칙: 19열 · 파일 간 정답·인정답안 중복 0(검사 `cut -f4 *.tsv | sort | uniq -d`) · 영화·드라마 제목은 SCREEN 밖에서 안 씀 · YouTube URL 은 재생 확인 전엔 비움 · 인정답안 열이 MC 판정 근거.

## 사용자 몫 (병행)
1. ~~카톡 설문~~ 안 함(10-06). 밸런스는 MC 의 중분류 선택으로.
2. `비고` 검색어로 클립·이미지 수집 → URL·시작초·이미지파일명 채우기.
3. TV 프로그램 △ 6개·조건부 3개 듣고 결정. 게임 대사 원문 확인. 인물 결정 2건.

## 다음 단계
- 콘텐츠 검토: screen·anime·game 확정, **person 결정 2건**이 남음. 판정 공통 규칙("정식 이름?")은 README 표. `anime.tsv` 12행(로보카 폴리)은 IMAGE 인데 이미지파일명이 비어 있다.
- 개발: 위 "개발 진행" 표. plan §11-3 의 앞 세 항목(파일 이동 · `party` 브랜치 · `party_item` CREATE)은 끝났다. 일정은 plan §9(리허설 1 = 11월 첫째 주, 코드 동결 12/8).

## 검증 상태
집 PC 에서 실행 확인: `./mvnw test` 592 통과 · 실제 로컬 DB 로 TSV 347행 적재·재적재·한 판·재시작 복구(`PartyDevCheck`) · `dev`·`dev,session-jdbc` 기동. **실제 로그인한 브라우저로는 아직 못 봤다**(O-029, 화면이 생긴 뒤). 열린 항목은 `docs/verification/open-issues.md` 의 O-025·O-029·O-030.

로컬 DB 점검을 다시 돌리려면(새 게임·초기화를 누르므로 진행 중인 게임이 있으면 스스로 멈춘다):
```bash
./mvnw test -Dparty.devcheck=true -Dtest=PartyDevCheck#importAndPlay
./mvnw test -Dparty.devcheck=true -Dtest=PartyDevCheck#afterRestart
```
끝나면 `../party-images/party-*.json` 을 지운다(점검이 만든 가짜 게임).
