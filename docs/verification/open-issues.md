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
