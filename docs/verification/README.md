# 검증 이력

작업 단위 검증 기록의 인덱스(최신이 위). 규칙은 전역 `~/.claude/CLAUDE.md` §4·§6, 형식은 `~/.claude/verification/templates.md`.

- `records/YYYY-MM-DD_<name>.md` — 작업별 기록 (요청·결정·가정 → AC → 변경 → 영향 범위 → 실행한 검증 → 미검증)
- `regression-list.md` — 반드시 다시 확인할 시나리오 (버그 수정마다 1행 추가)
- `open-issues.md` — 미검증(⬜)·직접 확인 필요(🙋)·보류 항목
- `domain-map.md`는 인수받은 프로젝트용이라 만들지 않음 (본인 작성 소스)

**2026-09-16 이전 이력**은 이 폴더가 생기기 전이라 [`../finish.md`](../finish.md) 진행 현황 표가 기록이다. 소급 작성하지 않는다.

| 일자 | 기록 | 유형 | 우선순위 | 판정 |
|---|---|---|---|---|
| 2026-09-21 | [멀티 언로드 나가기 페이지 토큰 (게임 시작 8초 뒤 전원 이탈)](records/2026-09-21_multi-unload-token.md) | 버그 | P0 | 수용 가능 |
| 2026-09-20 | [인증 남용 방어](records/2026-09-20_auth-abuse-defense.md) | 버그 | P0 | 수용 가능 |
| 2026-09-19 | [로그인 주체 슬림화](records/2026-09-19_slim-login-principal.md) | 리팩토링 | P0 | 수용 가능 |
| 2026-09-18 | [로그인 세션 수명 정리](records/2026-09-18_session-lifecycle.md) | 버그 | P0 | 조건부 |
| 2026-09-16 | [CLAUDE.md 요약·분리](records/2026-09-16_claude-md-summary.md) | 문서 | P3 | 수용 가능 |
| 2026-09-16 | [검증 문서 체계 도입](records/2026-09-16_verification-docs-setup.md) | 문서 | P3 | 수용 가능 |
