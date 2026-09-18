# Open Issues

미검증(⬜)·직접 확인 필요(🙋)·보류 항목. 상태: OPEN / IN-PROGRESS / CLOSED. 닫을 때 해결 기록 링크를 남긴다.
초기 항목은 `docs/finish.md`의 🔲 항목에서 옮겼다.

| ID | 상태 | 도메인 | 내용 | 미검증/보류 이유 | 확인 방법 | 등록 | 해결 기록 |
|---|---|---|---|---|---|---|---|
| O-001 | OPEN | Multi | 2026-09-16 멀티 결함 11건이 운영 미반영 | 미푸시 | 푸시 → 배포 후 수동 4건: 대기실 F5 자리 유지 / 탭 닫고 8초 뒤 자리 해제·방장 위임 / 플레이 중 게임 종료 후 방 만들기 / WS 상태에서 정답 강조·시스템 메시지 | 2026-09-16 | |
| O-002 | OPEN | Multi | `ws-client.js` 재연결 수정(R-012)에 저장소 내 자동 테스트 없음 | Jest 제거 결정(finish §1-3), JS는 Maven 밖 | 회귀 시 수동만 가능. JS 테스트 하네스 도입 여부는 별도 결정 | 2026-09-16 | |
| O-003 | OPEN | Infra | 서버 실제 재부팅 리허설 미실시 | runbook §6 명령만 검증 | 서버 재부팅 → 컨테이너 자동 기동·헬스·외부 200 확인 | 2026-09-16 | |
| O-004 | OPEN | Infra | 외부 uptime 모니터 + README 배지 | finish §4 9 미착수 | 모니터 등록 후 배지 녹색 | 2026-09-16 | |
| O-005 | OPEN | Song | `SongFileCheckBatch`·`uploads` 볼륨·`Song.file_path`·`GameRoom.password` 데드 코드 | 스키마 변경(운영 ALTER) 동반이라 보류 | 별도 작업으로 분리, finish 7 참조 | 2026-09-16 | |
| O-006 | OPEN | Infra | SHA 태그 이미지 누적(디스크 18%) | 급하지 않음 | 최근 N개 보존 정리 규칙 | 2026-09-16 | |
| O-007 | OPEN | Multi/WS | 강퇴된 사용자의 기존 STOMP 구독이 서버에서 끊기지 않음 | `SimpUserRegistry` 필요, 후속 과제 | `docs/ws-subscription-authorization.md` 7-3 | 2026-09-16 | |
| O-008 | OPEN | Test | Playwright E2E가 저장소에 없어 사용자 시나리오는 수동 체크리스트뿐 | `tests/`·`specs/`·`package.json` gitignore, 회사 PC에는 파일 없음 | 저장소 포함 여부 결정 | 2026-09-16 | |
| O-009 | OPEN | Auth | 세션 수명 변경의 브라우저 화면 확인 3건 (중복 로그인 토스트 / 대기실 keepalive 네트워크 / 정지 회원 안내) | 에이전트는 로그인 비밀번호 입력 불가 | records/2026-09-18_session-lifecycle §6 | 2026-09-18 | |
| O-010 | OPEN | Auth | 세션 수명 변경 운영 미반영 (`fix/session-lifecycle` 브랜치) | 미머지 | main 머지 → 배포 → runbook §1 Smoke + §6 시나리오 1 | 2026-09-18 | |
| O-011 | OPEN | Auth | 로그인 주체가 `Member` 엔티티를 통째로 들고 있음 — 세션에 비밀번호 해시 포함, 닉네임 변경이 같은 세션에 반영 안 됨(현재 변경 기능 없음) | 다음 작업(주체 슬림화)으로 분리 | 슬림화 PR 에서 직렬화 내용 테스트 | 2026-09-18 | |
| O-012 | OPEN | Security | `LoginRateLimiter.resolveClientIp` 가 `X-Forwarded-For` 첫 값을 그대로 신뢰 — 헤더 위조로 우회 가능성 | 범위 밖, 프록시 구성(nginx/ALB)과 함께 결정 필요 | nginx 가 XFF 를 덮어쓰는지 확인 후 결정 | 2026-09-18 | |
