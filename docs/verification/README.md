# 검증 이력

작업 단위 검증 기록의 인덱스(최신이 위). 규칙은 전역 `~/.claude/CLAUDE.md` §4·§6, 형식은 `~/.claude/verification/templates.md`.

- `records/YYYY-MM-DD_<name>.md` — 작업별 기록 (요청·결정·가정 → AC → 변경 → 영향 범위 → 실행한 검증 → 미검증)
- `regression-list.md` — 반드시 다시 확인할 시나리오 (버그 수정마다 1행 추가)
- `open-issues.md` — 미검증(⬜)·직접 확인 필요(🙋)·보류 항목
- `domain-map.md`는 인수받은 프로젝트용이라 만들지 않음 (본인 작성 소스)

**2026-09-16 이전 이력**은 이 폴더가 생기기 전이라 [`../finish.md`](../finish.md) 진행 현황 표가 기록이다. 소급 작성하지 않는다.

| 일자 | 기록 | 유형 | 우선순위 | 판정 |
|---|---|---|---|---|
| 2026-09-23 | [AWS 시연 — EC2 2대 + ALB + RDS + ElastiCache 실기동 (`session-redis`)](records/2026-09-23_aws-demo.md) | 인프라 | P1 | 수용 가능 — 세션 외부화 양방향 실측 ✅ · RDS TLS 강제 충돌 해소 · 시연 범위는 로그인 세션 한정 |
| 2026-09-22 | [AWS 시연용 세션 저장소 프로파일 `session-redis`](records/2026-09-22_session-redis-profile.md) | 기능 추가 | P1 (prod 무영향은 P0) | 수용 가능 — 481 ✅(CI 도 Redis 포함) · 재시작 유지 ✅ · 운영 배포 run 35740318687 Smoke ✅ |
| 2026-09-22 | [O-018·O-019·O-022 운영 확인 — 창 전체 닫기·휴대폰·인앱·bfcache·기동 뒤 정리](records/2026-09-22_o018-o019-prod-checks.md) | 운영 확인 | P1 | O-018·O-019 CLOSED, R-025 게임 항목·게임 중 배포 집에서 ✅ (§6) |
| 2026-09-22 | [O-022 — 서버가 내려간 사이 창을 닫은 참가자를 기동 뒤 정리](records/2026-09-22_startup-sweep.md) | 버그 | P1 | 수용 가능 |
| 2026-09-22 | [O-023 — 요청 제한 테스트에 시계 주입](records/2026-09-22_rate-limit-clock.md) | 테스트 | P2 | 수용 가능 |
| 2026-09-22 | [로그인 세션 저장소를 DB 로 — 배포·재시작에도 로그인·방 유지 (O-020) + WS 재연결 멈춤 (O-021)](records/2026-09-22_session-store-jdbc.md) | 버그 | P0 | 수용 가능 |
| 2026-09-22 | [O-020 원인 — 서버 프로세스 교체 시 방 참가자 전원 유령](records/2026-09-22_o020-restart-session-loss.md) | 버그 원인 | P0 | 원인 확정 · 수정 대기 |
| 2026-09-22 | [멀티 접속 상태(presence) — 창 전체 닫기 나가기 (O-018)](records/2026-09-22_multi-presence.md) | 기능 | P0 | 조건부 |
| 2026-09-21 | [멀티 언로드 나가기 페이지 토큰 (게임 시작 8초 뒤 전원 이탈)](records/2026-09-21_multi-unload-token.md) | 버그 | P0 | 수용 가능 |
| 2026-09-20 | [인증 남용 방어](records/2026-09-20_auth-abuse-defense.md) | 버그 | P0 | 수용 가능 |
| 2026-09-19 | [로그인 주체 슬림화](records/2026-09-19_slim-login-principal.md) | 리팩토링 | P0 | 수용 가능 |
| 2026-09-18 | [로그인 세션 수명 정리](records/2026-09-18_session-lifecycle.md) | 버그 | P0 | 조건부 |
| 2026-09-16 | [CLAUDE.md 요약·분리](records/2026-09-16_claude-md-summary.md) | 문서 | P3 | 수용 가능 |
| 2026-09-16 | [검증 문서 체계 도입](records/2026-09-16_verification-docs-setup.md) | 문서 | P3 | 수용 가능 |
