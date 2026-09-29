# 파티 퀴즈 착수 — 집 PC 기준선 테스트 + `party_item` 테이블
- 일자: 2026-09-29
- 유형: 신규 (DB 구조만, 자바 코드 없음)
- 우선순위: P2
- 판정: 수용 가능 — 기준선 481 ✅ · 로컬 DB 생성 ✅ · dev 기동 ✅. 엔티티 대조는 P-2 몫(O-026)

## 1. 요청과 목적
- 사용자가 원한 것: 회사 PC 에서 하던 파티 퀴즈 작업을 집 PC 에서 이어받아, plan §11-3 의 첫 단계(기준선 테스트 → `party_item` DDL → dev 기동)까지 진행.
- 개발자 확인 결과(결정 사항): 2026-09-29 저녁 "추천대로 1번 2번 진행". P-2 서버 구현은 AC 합의 뒤 착수(이번 범위 밖).
- 진행 중 둔 가정: plan §2 DDL 에서 두 열의 타입을 바꿨다(§8) → **같은 날 개발자 확정**, plan §2 DDL 본문도 정정.

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | `party` 브랜치에서 기존 테스트가 전부 통과한다(회사 PC 미실행분의 첫 실행 검증) | ✅ | `./mvnw test` 481 · 0 Skipped |
| 2 | 정상 | `schema.sql` 과 로컬 DB 에 같은 `party_item` 이 있다 | ✅ | `schema.sql` 에서 잘라낸 CREATE 문을 그대로 실행, `information_schema.columns` 22열 |
| 3 | 정상 | 테이블 추가 뒤 dev 가 기동한다 | ✅ | `Started GameApplication in 12.196 seconds`, `/actuator/health` 200 UP |
| 4 | 예외 | `party_item` 이 엔티티와 어긋나면 `validate` 가 기동을 막는다 | ⬜ | `PartyItem` 엔티티가 아직 없어 이번 기동은 이 테이블을 검사하지 않았다 → O-026 |
| 5 | 연쇄 | 운영 DB·main 브랜치는 건드리지 않는다 | ✅ | 변경은 `party` 브랜치 작업 트리 + 로컬 DB 뿐, push 없음 |

## 3. 변경 사항
- `src/main/resources/sql/schema.sql` — `party_item` CREATE 추가(`menu_config` 와 `ranking_history` 사이, 덤프 형식 유지). +30줄.
- DB·설정 변경: **로컬 DB `song` 에 `party_item` CREATE**(집 PC, 0행). 운영 DB 변경 없음. 회사 PC·행사용 노트북은 각자 같은 CREATE 가 필요하다.

## 4. 영향 범위 분석
- `schema.sql` 을 읽는 코드: 없음(수동 적용 문서). 테스트는 H2 `create-drop` 이라 이 파일을 쓰지 않는다.
- 영향 받는 화면/기능: 없음. `party_item` 을 참조하는 코드가 아직 없다.

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 전체 회귀(기준선) | `./mvnw test` (JDK 17, Docker 29.4.2 실행 중, `party` 브랜치, `schema.sql` 수정 전 시작) | Tests run: 481, Failures 0, Errors 0, **Skipped 0**, 3분 55초, BUILD SUCCESS | ✅ |
| 콘텐츠 TSV 재검사 | `awk -F'\t' 'NR>1 && NF!=19'` · `cut -f4 *.tsv \| sort \| uniq -d` | 19열 위반 0 · 정답 중복 0 · 347행(머리글 제외) | ✅ |
| DB 생성 | `schema.sql` 의 `party_item` CREATE 블록을 잘라 로컬 DB 에 실행 | exit 0, 테이블 33 → 34 | ✅ |
| DB 구조 확인 | `information_schema.columns` · `SHOW INDEX` | 22열, PRIMARY + `idx_party_item_cat(category, sub_category, use_yn)`, 0행 | ✅ |
| dev 기동 | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` | 8082 기동 12.2초, Schema-validation 오류 없음 | ✅ |
| Smoke | `curl` `/actuator/health` · `/` · `/auth/login` · `/admin/party/state` | 200 UP · 200 · 200 · 302(비로그인 → 로그인 화면) | ✅ |
| 엔티티 ↔ 테이블 대조 | — | 엔티티 없음 | ⬜ O-026 |

## 6. 수동 확인 시나리오
- 없음(화면 변경 없음).

## 7. checklist 점검
- 점검함: DB 변경 기록, 운영 무영향, 시크릿 미기록(DB 비밀번호는 dev 속성 파일에서 환경변수로 읽어 사용, 출력·기록 안 함).
- 해당 없음: 화면·권한·에러 메시지·호출처(코드 변경 없음).

## 8. 발견된 문제와 조치
- **plan §2 DDL 과 다르게 만든 열 2개(가정)**: `use_yn CHAR(1)` → `varchar(1) NOT NULL DEFAULT 'Y'`, `difficulty TINYINT` → `int(11)`. 이유: `ddl-auto=validate` 가 자바 `String`·`Integer` 에 VARCHAR·INTEGER 를 기대하므로 CHAR·TINYINT 는 엔티티에 `columnDefinition` 을 따로 달아야 한다. 기존 `song` 테이블도 `use_yn varchar`·`int(11)` 이다. 날짜 열도 기존과 같이 `datetime(6)`. plan §2 의 DDL 본문은 고치지 않았다(P-2 에서 엔티티와 함께 확정 뒤 정정).
- **집 PC 로컬 DB 는 MariaDB 가 아니라 MySQL 8.0.46**(`C:\Program Files\MySQL\MySQL Server 8.0`, 서비스 `mysqld.exe`). CLAUDE.md·plan 은 MariaDB 로 적고 있다. MariaDB JDBC 드라이버로 접속·기동은 정상. 운영(MariaDB 11.8)과 SQL 방언이 달라 "로컬에서 확인"의 의미가 제한된다 → O-026 에 함께 등록, 문서 정정은 별도 제안.
- 앞선 기록 `2026-09-29_party-quiz-plan.md` §5 의 TSV 행 수(339)는 그 뒤 수정 전 값이다. 현재 **347행**(anime 43·game 61·person 32·quiz 50·screen 44·sound 37·speed 80, 파이썬으로 머리글 제외 집계). `party-status.md` 의 346 은 합산 오류다. 그 기록은 고치지 않고 여기에 남긴다.
- `anime.tsv` 12행(로보카 폴리)은 제시가 IMAGE 인데 `이미지파일명` 이 비어 있다 — 가져오기 검증에서 걸릴 행(P-2 Part ① 에서 처리 방식 결정).

## 9. 미검증 영역과 남은 위험
- `party_item` 열 타입이 `PartyItem` 엔티티와 맞는지 / 엔티티가 없음 / P-2 에서 엔티티 추가 뒤 dev 기동(O-026).
- 행사용 노트북의 DB 종류·`party_item` 생성 / 노트북 세팅 전 / plan §2-3 절차 때 확인.

## 10. Regression 등록
- 없음(버그 수정 아님).
