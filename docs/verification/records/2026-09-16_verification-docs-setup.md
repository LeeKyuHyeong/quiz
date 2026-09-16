# 검증 문서 체계 도입
- 일자: 2026-09-16
- 유형: 문서
- 우선순위: P3
- 판정: 수용 가능

## 1. 요청과 목적
- 사용자가 원한 것: 전역 CLAUDE.md의 검증 규칙을 이 저장소에 적용. ① 프로젝트 CLAUDE.md의 "작업 보고 문서 금지" 규칙 제거(요청·판단·기술 선택·검증 결과가 남지 않아 누락된 기능을 놓친 경험) ② `docs/verification/` 신설과 `finish.md` 정리 ④ 검증 설정 섹션 작성
- 개발자 확인 결과(결정 사항): ①②는 지시대로. P0 시나리오 6개 확정(2026-09-16), 회사 PC 운용 방식은 미확정
- 진행 중 둔 가정: `finish.md`는 내용 변경 없이 `docs/finish.md`로 이동만 하고 이전 이력의 기록으로 삼는다(소급 record 작성 안 함)

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 정상 | 프로젝트 CLAUDE.md에 "구현 가이드 / 작업 보고 문서 금지" 문장이 없다 | ✅ | `grep "구현 가이드" CLAUDE.md` 0건 |
| 2 | 정상 | `finish.md`가 `docs/finish.md`로 이동하고 참조 2곳(CLAUDE.md, ws 문서)이 새 경로를 가리킨다 | ✅ | `git status` R, `grep -rn finish.md` |
| 3 | 정상 | `docs/verification/`에 README·regression-list·open-issues·records/ 가 있고 기존 이력(finish 진행 현황)의 테스트·🔲 항목이 옮겨졌다 | ✅ | 본 파일 |
| 4 | 정상 | CLAUDE.md 끝에 검증 설정 섹션이 있고 P0 시나리오는 초안으로 표시된다 | ✅ | CLAUDE.md `## 검증 설정` |

## 3. 변경 사항
- `CLAUDE.md` — 규칙 1줄 제거, `finish.md` 참조 경로 수정, `## 검증 설정` 섹션 추가
- `finish.md` → `docs/finish.md` — 이동만
- `docs/ws-subscription-authorization.md` — 7-4 제목의 경로 수정
- `docs/verification/README.md`, `regression-list.md`(R-001~016), `open-issues.md`(O-001~008), `records/` — 신규
- DB·설정 변경: 없음

## 4. 영향 범위 분석
- `finish.md` 참조: CLAUDE.md 1곳, docs/ws-subscription-authorization.md 1곳 → 모두 수정. Java·yml 참조 0건
- 배포: `deploy.yml` `paths-ignore`가 `**.md`라 이 변경만으로는 배포가 돌지 않음

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 빌드 | 해당 없음(md만 변경) | — | ⬜ 코드 변경 없음 |
| 정적 | `grep` 참조 잔존 확인, `git status` | 잔존 0건 | ✅ |

## 6. 수동 확인 시나리오
- 없음

## 7. checklist 점검
- 점검함: §1 영향 범위(참조 검색)
- 해당 없음: §2~§7 (문서 작업)

## 8. 발견된 문제와 조치
- 회사 PC의 기본 java가 1.8, 프로젝트 CLAUDE.md의 JDK 경로는 집 PC 기준 → 검증 설정에 회사 PC 경로(`~/.jdks/corretto-17.0.12`) 병기

## 9. 미검증 영역과 남은 위험
- P0 시나리오 6개는 2026-09-16 사용자 확정. 전역 checklist는 같은 날 스택별 병기(C안)로 수정
- `docs/finish.md`와 `docs/verification/`의 역할: finish는 2026-09-16 이전 이력·마무리 점검표, 이후 작업은 records. finish 진행 현황에 새 행을 더 추가하지 않는다

## 10. Regression 등록
- 없음 (버그 수정 아님). 기존 이력에서 R-001~R-016 초기 이관
