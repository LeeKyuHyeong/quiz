# 파티 퀴즈 P-2 Part ① — `PartyItem` 엔티티 + TSV 가져오기
- 일자: 2026-09-29
- 유형: 신규
- 우선순위: P1
- 판정: 수용 가능 (서비스 계층까지. 화면·업로드 경로는 Part ④)

## 1. 요청과 목적
- 사용자가 원한 것: P-2 서버를 Part 로 쪼개 진행. Part ① = 문제 테이블을 자바에서 읽고 쓸 수 있게 하고, `docs/party-content/*.tsv` 를 그대로 넣을 수 있게 한다.
- 개발자 확인 결과(결정 사항, 2026-09-29 "추천대로"):
  1. URL 없는 AUDIO·파일명 없는 IMAGE 는 **거부하지 않고 저장**. 출제에서 빼는 것은 Part ②.
  2. 같은 파일을 다시 올리면 **대분류 + 정답이 같은 행을 덮어쓴다**.
  3. `비고`·`추천자`·`제목스포` 열은 저장하지 않는다.
  4. AC 는 초안 그대로.
- 진행 중 둔 가정:
  - 난이도 하/중/상 = 1/2/3. 빈 값은 null.
  - URL 칸에 값이 있는데 영상 ID 를 못 뽑으면 그 행은 거부(빈 채로 저장하지 않음 — 오타를 숨기지 않기 위해).
  - 시작초·길이는 0 이상 정수만. 열 크기를 넘는 값은 그 행만 거부.
  - 제시 값은 AUDIO/IMAGE/TEXT 3종(README 기준). plan §2 DDL 주석의 VIDEO 는 "지금은 안 씀"이라 enum 에 넣지 않았다.
  - 덮어쓸 때 `use_yn`·`is_youtube_valid`·`youtube_checked_at` 은 건드리지 않는다(관리자가 끈 문제가 재가져오기로 다시 켜지지 않게).

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | TSV 의 각 행이 `party_item` 한 줄로 저장된다 | ✅ | `PartyItemImportTest#savesEachRow` |
| 2 | 정상 | YouTube URL 에서 영상 ID 만 저장, 비어 있어도 저장 | ✅ | `#extractsVideoId`, `#savesRowsWithoutMedia` |
| 3 | 정상 | 난이도 하/중/상 → 1/2/3 | ✅ | `#savesEachRow` |
| 4 | 정상 | 실제 TSV 7개가 거부 행 없이 전부 저장된다 | ✅ | `#importsRealContentFiles` (H2) |
| 5 | 예외 | 필수값이 빠진 행만 거부, 줄 번호·이유 보고, 나머지는 저장 | ✅ | `#reportsMissingRequired` |
| 6 | 예외 | 모르는 대분류·제시·난이도, 열 수 불일치, 읽을 수 없는 URL·숫자는 거부 | ✅ | `#rejectsMalformedRows` |
| 7 | 예외 | 머리글이 19열 형식이 아니면 아무것도 저장하지 않는다 | ✅ | `#rejectsWrongHeader` |
| 8 | 경계 | 같은 파일을 두 번 올려도 같은 문제가 두 개 생기지 않는다(덮어쓰기) | ✅ | `#reimportOverwrites` |
| 9 | 경계 | 정답이 같아도 대분류가 다르면 다른 문제 | ✅ | `#sameAnswerDifferentCategory` |
| 10 | 경계 | 빈 줄·CRLF·머리글만 있는 파일 | ✅ | `#toleratesBlankLinesAndCrlf` |
| 11 | 경계 | 열 크기를 넘는 값은 그 행만 거부 | ✅ | `#rejectsTooLongValue` |
| 12 | 경계 | SPEED 는 문제텍스트 없이 저장 | ✅ | `#speedNeedsNoQuestionText` |
| 13 | 연쇄 | 엔티티를 붙인 뒤 dev 가 기동한다(`validate`) | ✅ | 로컬 MySQL 8.0.46, `Started GameApplication in 8.696 seconds`, health 200 |
| 14 | 연쇄 | 기존 테스트 481건이 그대로 통과 | ✅ | 전체 493 = 481 + 12 |
| 15 | 정상 | 실제 TSV 가 **로컬 DB** 에 들어간다 | ⬜ | 가져오기를 부르는 화면·경로가 아직 없다(Part ④) → O-027 |

## 3. 변경 사항
- `src/main/java/com/kh/game/party/PartyItem.java` — 엔티티(22열)
- `.../party/PartyCategory.java`, `PartyPresentation.java` — enum(`varchar` + `@Enumerated(STRING)`, 기존 관례)
- `.../party/PartyItemRepository.java` — `findByCategoryAndAnswer`
- `.../party/PartyItemImportService.java`, `PartyImportResult.java` — TSV 읽기·검증·덮어쓰기
- `src/test/java/com/kh/game/party/PartyItemImportTest.java` — 12건
- DB·설정 변경: 없음(테이블은 앞 기록 `2026-09-29_party-item-ddl.md` 에서 생성).
- 기존 파일 수정: 없음.

## 4. 영향 범위 분석
- 새 패키지 `com.kh.game.party` 는 `@SpringBootApplication` 기본 스캔 범위 안 → 설정 변경 없이 엔티티·리포지토리·서비스가 등록된다.
- 호출처: 없음(컨트롤러는 Part ④). 기존 코드에서 이 패키지를 참조하는 곳 없음.
- **main 병합 금지 사유가 이 커밋부터 실제가 된다** — `PartyItem` 엔티티가 있으므로 `party_item` 없는 운영 DB 에서는 `validate` 가 기동을 막는다.
- 영상 ID 추출 정규식은 `AdminSongController#extractYoutubeVideoId`(private)와 같은 식을 복사했다. 기존 파일을 건드리지 않기 위한 선택이며, 공용화는 별도 제안.

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 실패 테스트 선행 | 규칙 구현 전 `./mvnw test -Dtest=PartyItemImportTest` | 10건 중 `reportsMissingRequired` 1건 실패 확인 | ✅ |
| Integration | `./mvnw test -Dtest=PartyItemImportTest` | 12 passed | ✅ |
| 전체 회귀 | `./mvnw test` (JDK 17, Docker 29.4.2) | Tests run: 493, Failures 0, Errors 0, Skipped 0 | ✅ |
| 기동(`validate`) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` | 기동 8.7초, Schema-validation 오류 없음, `/actuator/health` 200 UP | ✅ |
| 로컬 DB 적재 | — | 경로 없음 | ⬜ O-027 |

로그의 `Unexpected error occurred in scheduled task` 4건은 기준선(변경 전 481 실행)에도 같은 수로 있다 — 이번 변경과 무관.

## 6. 수동 확인 시나리오
- 없음(화면 없음). Part ④ 에서 작성.

## 7. checklist 점검
- 점검함: §1 엔티티 ↔ `schema.sql` ↔ 로컬 DB 일치(기동으로 확인) · §2 빈 값·공백·긴 값·한글·CRLF·BOM · §3 쿼리는 JPA 메서드 쿼리(문자열 연결 없음), 값 검증은 enum·정규식 화이트리스트 · §4 한 번의 가져오기는 한 트랜잭션, 거부 행은 예외를 밖으로 던지지 않아 나머지가 롤백되지 않음 · §7 가져오기 결과 INFO 1줄(건수만, 내용 없음).
- 해당 없음: 권한(경로 없음) · 멀티 · 화면.
- **남은 것**: §4 동시 2회 가져오기 — `(category, answer)` 에 유니크 제약이 없어 동시에 올리면 중복될 수 있다. MC 한 명이 쓰는 로컬 도구라 두지 않았다(§9).

## 8. 발견된 문제와 조치
- 열 크기를 넘는 값이 DB 예외로 가져오기 전체를 실패시킬 수 있었다 → 행 단위 거부(`limited`), 테스트 `#rejectsTooLongValue` 추가.
- `anime.tsv` 12행(로보카 폴리, IMAGE 인데 파일명 없음)은 결정 1 에 따라 저장된다. 파일명은 수집 때 채운다.

## 9. 미검증 영역과 남은 위험
- 로컬 DB 실제 적재 / 경로 없음 / Part ④ 뒤 화면에서 7개 파일 올리고 `SELECT category, COUNT(*) FROM party_item GROUP BY category` (O-027).
- H2 에서만 확인한 저장 — MySQL·MariaDB 의 utf8mb4 이모지(QUIZ 이모지 속담)는 O-027 때 함께 확인.
- 동시 가져오기 중복 / 단일 사용자 도구 / 필요해지면 유니크 키 추가.

## 10. Regression 등록
- 없음(버그 수정 아님).
