# CLAUDE.md 요약·분리
- 일자: 2026-09-16
- 유형: 문서
- 우선순위: P3
- 판정: 수용 가능

## 1. 요청과 목적
- 사용자가 원한 것: 965줄·53KB인 프로젝트 CLAUDE.md를 500줄 이하로 줄이고, 뺀 내용은 `docs/`로 옮긴다. 500줄보다 더 줄일 방안도 적용.
- 개발자 확인 결과(결정 사항): 요약 진행, 삭제가 아니라 이동.
- 진행 중 둔 가정: 코드에서 `ls`로 확인 가능한 파생 정보(클래스 목록)는 CLAUDE.md에 개수만 남긴다. 멀티플레이 규칙은 변경이 잦아 CLAUDE.md에만 두고 분리 문서에는 복제하지 않는다(드리프트 방지).

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | CLAUDE.md가 500줄 미만이다 | ✅ | 231줄·20.7KB (원본 965줄·53KB) |
| 2 | 정상 | 뺀 내용이 docs/에 전문으로 존재하고 CLAUDE.md 문서 지도가 그 경로를 가리킨다 | ✅ | 4개 파일 생성, 경로 존재 검사 10/10 ok |
| 3 | 정상 | 원본의 규칙·식별자가 새 CLAUDE.md 또는 분리 문서 중 한 곳에는 남아 있다 | ✅ | 백틱 토큰 diff → 남은 차이는 예시 값·표현 차이뿐(아래 8번) |
| 4 | 경계 | 코드·배포에 영향 없음 | ✅ | md만 변경, `deploy.yml` `**.md` 제외 |

## 3. 변경 사항
- `CLAUDE.md` — 전면 재작성(문서 지도 + 요약). 원본 백업은 이 세션 스크래치에만 있고 git 이력(`5d77fef` 이전)이 원본.
- `docs/guides/llm-coding-guidelines.md` — 행동 원칙 전문(원본 7~120행, `sed`로 그대로 추출)
- `docs/guides/security-review.md` — Security Review Guide 전문(387~720행) + 예시가 Spring Security 전환 전 `HttpSession` 방식이라는 주의문
- `docs/guides/css-style.md` — CSS Style Guide 전문(724~916행)
- `docs/architecture-reference.md` — 패키지 클래스 목록·게임 모드·데이터 흐름·Key Services·티어·뱃지·배치 목록·점수표(198~242, 258~347행). Multiplayer Flow(243~257행)는 제외
- DB·설정 변경: 없음

## 4. 영향 범위 분석
- CLAUDE.md를 읽는 것은 Claude Code뿐. 다른 문서가 CLAUDE.md의 특정 섹션을 링크하지 않음(`grep "CLAUDE.md#"` 0건).
- `System.md`·`README.md`는 건드리지 않음. `System.md`와 `architecture-reference.md`는 일부 주제가 겹치나 성격이 다름(설계 배경 vs 목록).

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 정적 | `wc -l -c CLAUDE.md` | 231줄, 20660B | ✅ |
| 정적 | 문서 지도 경로 10개 존재 검사 | 전부 ok | ✅ |
| 정적 | 원본 vs 신규+docs 백틱 식별자 diff(`comm -23`) | 36건 차이, 전부 예시 값·표현 차이. `CHAT` 키 4개·`addSystemMessage`는 복원 | ✅ |
| 빌드 | 해당 없음(md만) | — | ⬜ 코드 변경 없음 |

## 6. 수동 확인 시나리오
- 다음 세션 시작 시 CLAUDE.md만 읽고 멀티플레이 나가기 3종·schema.sql 규칙·JDK 경로를 답할 수 있는지. (사용자 체감 확인)

## 7. checklist 점검
- 점검함: §1 영향 범위(참조 검색)
- 해당 없음: §2~§7

## 8. 발견된 문제와 조치
- 식별자 diff에서 빠진 것 중 의미 있는 것: `CHAT` payload 키(`id`·`memberId`·`isHost`·`messageType` `CORRECT_ANSWER`)와 `addSystemMessage` → CLAUDE.md Multiplayer Flow에 복원. 나머지(`positivelee`, `noreply@example.com`, `99999`, `D:\server-infra.md` 폐기 메모 등)는 예시 값이라 의도적으로 제외.
- 분리 문서의 보안 예시 코드가 현재 인증 구조(Spring Security)와 다름 → 삭제하지 않고 문서 머리에 주의문 추가. 예시 자체의 현행화는 별도 작업.

## 9. 미검증 영역과 남은 위험
- 요약 과정에서 뉘앙스가 줄어든 항목이 있을 수 있다. 다음 실제 작업에서 CLAUDE.md만으로 부족했던 항목이 나오면 그때 보강한다.
- 더 줄일 여지(선택): Configuration `.env` 표 → `docs/runbook.md`, Multiplayer Flow 상세 → `System.md` §3(단일 출처 유지 조건), 검증 설정의 외부 연동 줄 → 전역 템플릿 참조. 적용하면 ~180줄.

## 10. Regression 등록
- 없음
