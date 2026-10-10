# 랭킹 첫 목록 쿼리 인덱스 — `member (status, guess_score)` (career 【확인 42】)
- 일자: 2026-10-10
- 유형: 신규 (DB 구조 — 가산 DDL) + 문서
- 우선순위: P2 (운영 반영 절차는 P0 규칙: schema.sql 과 운영 DB 일치)
- 판정: 조건부 — 코드·로컬 DB·회귀 ✅, 운영 ALTER·운영 EXPLAIN 🙋(O-032), 운영 백업 사본(MariaDB 11.8) 재실험 ⬜(O-033)

## 1. 요청과 목적
- 사용자가 원한 것: career `04-gap-analysis-java3y-261006.md` §4-1 【확인 42】 — 랭킹 쿼리에 `EXPLAIN` 전후를 남기며 인덱스를 추가하고, "왜 이 하나만 / 왜 30개는 안 만드나" 를 본인 말로 답할 수 있게 한다. 서류 문장은 실행계획 변화로만, 시간 수치는 쓰지 않는다.
- 개발자 확인 결과(결정 사항): 10-06 계획대로 1개만. 대상은 **화면이 실제로 쓰는 쿼리**로 교정(아래 가정 ①).
- 진행 중 둔 가정:
  - ① **10-06 실험이 겨눈 `findTopByTotalScore`(total_score) 는 호출처가 없다** — `MemberService.getTopRankingByScore` 를 부르는 컨트롤러가 0건(`grep -rn getTopRankingByScore src/main/java`). `/ranking` 화면의 첫 목록과 `/api/ranking` 기본값(`mode=guess&type=score&period=all`)은 `findTopGuessRankingByScore` = `status='ACTIVE' AND guess_games>0 ORDER BY guess_score DESC`. 인덱스는 이 쿼리에 맞춰 `(status, guess_score)`. 죽은 메서드 `findTopByTotalScore`·`findTopByAccuracy`·`findTopByTotalGames` 는 이번 범위 밖(Surgical Changes — 언급만).
  - ② 열 순서는 등치(status) → 정렬(guess_score). 범위 조건 `guess_games>0` 은 인덱스에 넣지 않는다 — 정렬 열 앞에 범위 열이 오면 정렬 순서를 못 쓰고 filesort 가 돌아온다(10-06 ④ 문제 정정).
  - ③ 멀티 티어(`findTopMultiTierRanking`)는 정렬 첫 키가 `CASE multi_tier … END` 식이라 인덱스로 정렬을 못 한다(10-06 실험 2). 다른 랭킹 열(multi_score·weekly_*·best_*)은 같은 모양이지만 **행 수(운영 백업 46행·로컬 46행)에서 filesort 비용이 문제가 아니므로** 만들지 않는다 — "패턴마다 인덱스" 를 안 하는 이유는 ③ 답.

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | 엔티티 `@Index` 와 `schema.sql` `KEY` 가 같은 이름·열 순서 | ✅ | `Member.java:11-13`, `schema.sql` member 블록 `idx_member_status_guess_score (status, guess_score)` |
| 2 | 정상 | 로컬 dev DB(MySQL 8.0.46)에 같은 인덱스가 있고 앱 `validate` 가 통과 | ✅ / 🙋 | ALTER 적용·`SHOW INDEX` 2행(§5). dev 기동은 🙋(인덱스는 validate 대상이 아님) |
| 3 | 정상 | 인덱스로 실행계획이 `ALL + Using filesort` 에서 인덱스 순서 읽기로 바뀐다 | ✅(조건) | §5 — 로컬 MySQL 은 **FORCE INDEX 일 때** `ref · Backward index scan`, 옵티마이저 자율 선택은 여전히 ALL(아래 §6 관찰). 운영(MariaDB 11.8) 은 🙋 |
| 4 | 예외 | 인덱스가 없는 운영 DB 에 새 색이 떠도 기동·헬스에 영향 없다(가산 DDL, validate 비검사) | ✅ | Hibernate `validate` 는 인덱스를 검사하지 않음(SchemaValidator 는 테이블·컬럼·타입만). 그래도 runbook §10 순서(ALTER → push)로 간다 |
| 5 | 연쇄 | 전체 회귀 통과(H2 는 `create-drop` 으로 `@Index` 를 DDL 에 반영 — 이름 충돌·문법 오류가 있으면 여기서 깨진다) | ✅ | `./mvnw test` 497 · 0 실패(§5) |
| 6 | 노출 | 비밀값·개인정보 없음 — 실행 스크립트는 scratchpad, 기록엔 계정·비밀번호 없음 | ✅ | 이 파일 |

## 3. 변경 사항
- `src/main/java/com/kh/game/entity/Member.java` — `@Table(indexes = @Index(name="idx_member_status_guess_score", columnList="status, guess_score"))` + 주석(대상 쿼리·열 순서 근거·운영 반영 절차 위치)
- `src/main/resources/sql/schema.sql` — `member` 블록에 `KEY idx_member_status_guess_score (status, guess_score)`
- `docs/runbook.md` §10 — 가산 DDL 선반영 절차(전 EXPLAIN → ALTER → 후 EXPLAIN → push), 되돌리기
- DB·설정 변경: **로컬 dev DB ALTER 적용 완료(10-10)** · **운영 DB ALTER 🙋 O-032**(runbook §10, push 전)

## 4. 영향 범위 분석
- 검색한 호출처와 결과: `findTopGuessRankingByScore` ← `MemberService.getGuessRankingByScore` ← `RankingController`(`/ranking` 모델 `guessScoreRanking`, `/api/ranking` 기본 분기) · `home.js`·`ranking.js` 가 `/api/ranking` 호출. 쿼리 문장 변경 없음 — 인덱스만 추가라 결과 집합·순서 동일.
- 영향 받는 화면/기능: 홈 랭킹 위젯·랭킹 페이지 첫 탭(읽기). 쓰기 경로: `guess_score`·`status` 를 바꾸는 UPDATE(게임 종료·회원 상태 변경)에 인덱스 유지 비용이 붙는다 — 46행에서 측정 의미 없음(10-06 실험 5 와 같은 판단).

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 로컬 DB 전 | jshell + MariaDB JDBC 3.4.1 → MySQL 8.0.46 `song`: `SELECT COUNT(*)` / `SHOW INDEX` / `EXPLAIN` | member 46행(ACTIVE 45 · INACTIVE 1, ACTIVE∧guess_games>0 = 16) · 인덱스 PRIMARY·uk_member_email·selected_badge_id 뿐 · `EXPLAIN`: **type ALL · key NULL · rows 45 · Using where; Using filesort** | ✅ |
| 로컬 DB ALTER | `ALTER TABLE member ADD INDEX idx_member_status_guess_score (status, guess_score)` | `SHOW INDEX` 에 Seq 1 `status`(Cardinality 2) · Seq 2 `guess_score`(17) | ✅ |
| 로컬 DB 후(자율) | 같은 `EXPLAIN` | **type ALL · possible_keys idx_member_status_guess_score · key NULL · rows 45 · filtered 33.33 · Using where; Using filesort** — 인덱스를 인식하고도 쓰지 않음 | ✅(관찰) |
| 로컬 DB 후(강제) | `FORCE INDEX (idx_member_status_guess_score)` | **type ref · key idx_member_status_guess_score · key_len 1 · ref const · rows 22 · Using where; Backward index scan** — filesort 없음 | ✅ |
| 로컬 DB 후(TREE) | `EXPLAIN FORMAT=TREE` 자율 vs 강제 | 자율: `Table scan (cost=5.25 rows=45) → Filter → Sort guess_score DESC → Limit 20` / 강제: `Index lookup (status='ACTIVE') (reverse) (cost=1.86 rows=22) → Filter guess_games>0 → Limit` — **비용 추정은 인덱스 쪽이 낮은데도 테이블 스캔을 골랐다** | ✅(관찰) |
| 로컬 DB 후(선택도) | `WHERE status='INACTIVE'`(1행) | **ref · rows 1 · Backward index scan** — 걸러지는 값이면 자율로도 인덱스를 쓴다 | ✅ |
| 운영 백업 사본(MariaDB 11.8) | 10-06 밤 실험(career 05-fund 02 ②-결과): `(status, total_score)` 추가 시 range·filesort 제거 | **total_score 는 죽은 쿼리라 이번 대상과 열이 다름** — `(status, guess_score)` 로 재실험 필요. 10-10 집 PC Docker 데몬 기동 실패로 미실행 | ⬜ O-033 |
| 빌드·전체 회귀 | `JAVA_HOME=jdk-17 ./mvnw test`(Docker 없음) | **497 run · 0 failures · 0 errors · 11 Skipped**(`SessionLifecycleRedisContractTest`·`SessionStoreProfileTest$Redis` — Testcontainers, Docker 부재) · surefire 리포트 132 | ✅ |
| 운영 | runbook §10 (본인 SSH) | — | 🙋 O-032 |

## 6. 관찰 — ③ 답의 재료 (해석은 본인이 career 05-fund 02 ③ 에)
1. **옵티마이저가 인덱스를 알고도 안 쓴 이유 후보**: `status='ACTIVE'` 가 46행 중 45행이라 걸러지는 게 없고, 45행 정렬은 메모리에서 끝난다. 같은 인덱스로 `status='INACTIVE'`(1행)는 바로 쓴다 — **선택도**가 인덱스 사용을 가른다(10-06 실험 3 `is_correct` 1%:99% 와 같은 현상). TREE 비용이 인덱스 쪽이 낮은데도 스캔을 고른 건 MySQL 8 의 소규모 테이블·`LIMIT`+`ORDER BY` 휴리스틱 영향으로 보이나 **근거 문서로 확인하지 않았다 — 추정이라고 말한다**.
2. 10-06 운영 백업(ACTIVE 23 / INACTIVE 23)에서는 MariaDB 가 `range` 로 골랐다 — 분포가 반반이면 걸러지는 값이 있어 인덱스가 선택됨. **운영은 로컬과 분포가 다르다 → 운영 EXPLAIN 을 봐야 "운영에서 인덱스를 탄다" 를 말할 수 있다**(O-032 전엔 말하지 않는다).
3. 서류 문장 후보(claims 신설 전, 운영 EXPLAIN 뒤 확정): "랭킹 조회가 `ALL + filesort` 로 돌던 것에 등치 열 → 정렬 열 순 복합 인덱스 하나를 추가해 인덱스 역순 읽기로 바꿈 — 정렬 식이 `CASE` 인 쿼리와 행 수가 작은 나머지 정렬 열에는 두지 않음". ❌ 시간 수치 · ❌ "성능 N% 개선" · ❌ "30개 쿼리 튜닝".
4. 죽은 코드: `findTopByTotalScore`·`findTopByAccuracy`·`findTopByTotalGames`(+ `MemberService.getTopRanking*` 3개) 호출처 0 — 별도 정리 대상(언급만).

## 7. 미검증 · 열린 항목
- 🙋 O-032 운영 ALTER 선반영 + 전후 EXPLAIN(runbook §10) → 결과를 이 기록 §5 에 추가 → push → 배포 Smoke(`/ranking` 200, 랭킹 목록 동일).
- ⬜ O-033 운영 백업 사본(MariaDB 11.8, Docker)으로 `(status, guess_score)` 전후 재실험 — 10-10 집 PC Docker 데몬 미기동(`dockerDesktopLinuxEngine` 파이프 없음). 운영 EXPLAIN(O-032)이 있으면 대체 가능.
- ⬜ dev 기동 `validate` 통과 확인(인덱스는 비검사라 결과가 뻔하지만 기록상 1회).
- 전체 회귀: 497 ✅(0 실패, Redis 11 Skipped — Docker 부재. CI 에서는 실행됨)
