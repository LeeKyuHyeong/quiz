# 로그인 주체 슬림화 (세션에서 Member 엔티티·비밀번호 해시 제거)
- 일자: 2026-09-19
- 유형: 리팩토링 (+ 잠복 버그 1건)
- 우선순위: P0 (인증 — 로그인한 모든 요청이 거치는 객체)
- 판정: 조건부 — 자동 검증·로그인 상태 화면 확인 ✅, 운영 배포 후 Smoke 만 ⬜

## 1. 요청과 목적
- 사용자가 원한 것: 세션을 외부 저장소(Redis)로 옮기기 전에, 세션에 실리는 로그인 주체를 정리한다.
- 배경: `CustomUserDetails` 가 로그인 시점의 `Member` 엔티티를 통째로 들고 있었다. ① 직렬화 불가(`NotSerializableException: Member`) ② 세션에 비밀번호 해시와 EAGER 연관(`Badge`)이 실림 ③ 로그인 뒤 바뀐 회원 정보가 같은 세션에 반영되지 않음.
- 개발자 확인 결과(결정 사항): 닉네임이 옛 값으로 남는 것은 버그 — 주체에 닉네임 사본을 두지 않고 DB 에서 읽는다.
- 진행 중 둔 가정:
  - 회원 행은 물리 삭제되지 않는다(`memberRepository.delete*` 호출 0건 확인) → 세션은 있는데 회원이 없는 경우의 처리는 넣지 않았다 (`findLoginMember` 는 `orElseThrow`).
  - 상태·권한은 로그인 시점 값을 주체에 둔다. 바뀌면 세션을 끊는 방식(2026-09-18 기록)으로 최신성을 보장한다.

## 2. Acceptance Criteria
| # | 구분 | 조건 | 상태 | 근거 |
|---|---|---|---|---|
| 1 | 노출 | 세션의 로그인 정보는 직렬화할 수 있고 비밀번호 해시·`Member` 엔티티를 포함하지 않는다 | ✅ | `LoginPrincipalContentTest#sessionContent_isSerializable_andHoldsNoCredentialsOrEntity` (수정 전 `NotSerializableException`) |
| 2 | 연쇄 | 직렬화했다가 되살린 주체가 원본과 동등하다 (세션 저장소를 거쳐도 1계정 1세션 판단 유지) | ✅ | `#deserializedPrincipal_equalsOriginal` |
| 3 | 정상 | 닉네임을 바꾸면 같은 세션의 `/auth/status` 가 새 닉네임을 돌려준다 | ✅ | `#nicknameChange_isVisibleInSameSession` (수정 전 옛 닉네임) |
| 4 | 정상 | 로그인·마이페이지·게시판·솔로/멀티·관리자 기능이 이전과 같이 동작한다 | ✅ | 전체 회귀 402건 + 수동 시나리오 1~4 (개발자 확인 2026-09-19) |
| 5 | 권한 | 1계정 1세션, 정지·강등 시 세션 끊김, WebSocket 구독 검증이 그대로 동작한다 | ✅ | `SessionLifecycleContractTest`, `MemberStateChangeSessionTest`, `WebSocketAuthInterceptorTest` |
| 6 | 정상 | 비로그인 상태에서 솔로 설정·게시판·통계 화면이 열리고 닉네임은 null | ✅ | dev 기동(18082) curl: `/game/solo/guess`·`/game/retro`·`/board`·`/stats` 200, `memberNickname = null` |

## 3. 변경 사항
- `security/CustomUserDetails` — 필드를 `memberId`·`email`·`role`·`status`·`password` 로 축소. `Serializable`(`serialVersionUID`), `CredentialsContainer`(인증 후 Spring Security 가 `eraseCredentials()` 로 해시 제거). 생성자 `CustomUserDetails(Member)` 시그니처는 유지. `getMember()` 제거
- `service/MemberService#findLoginMember(CustomUserDetails)` (신규) — 주체의 회원을 DB 에서 읽는다, 비로그인이면 null
- 컨트롤러·서비스 18개 파일 — `getMember().getId()` → `getMemberId()` (55곳), 엔티티를 쓰던 22곳은 `findLoginMember`. `MemberService` 의존성 추가: `BoardController`·`SongReportController`·`StatsController`·`AdminBatchAffectedController`·`AdminSongReportController`
- `GameGuessController`·`RetroGameController` 의 설정 화면 — 닉네임을 모델로 전달. 템플릿 2개는 `#authentication.principal.member.nickname` → `${nickname}` (팬·장르 챌린지 설정 화면이 이미 쓰던 방식)
- 문서: `docs/ws-subscription-authorization.md` 의 `getMember().getId()` 표기 수정
- DB·설정 변경: 없음

## 4. 영향 범위 분석
- 호출처는 `getMember()` 를 제거하고 컴파일러로 전수 확인: 운영 코드 77곳(18파일), 테스트 2줄. 컴파일되지 않는 곳(템플릿·SpEL)은 grep: `principal.member` 2곳(위 템플릿), `#authentication` 그 외 없음
- 테스트의 `new CustomUserDetails(member)` 21곳 — 시그니처 유지로 수정 없음
- **기존 테스트 수정 2개 파일**
  - `CustomUserDetailsServiceTest` — `getMember().getId()` → `getMemberId()`(기대값 동일). `getMember().getNickname()` 단언은 주체가 더 이상 닉네임을 들지 않아 `getEmail()` 단언으로 **교체**
  - `CustomAuthenticationSuccessHandlerTest` — 목 `memberService.findLoginMember` 스텁 1줄 추가(단언 변경 없음)
- 동작이 달라지는 것: 엔티티를 쓰던 22곳은 요청마다 회원 PK 조회 1건이 늘고, 대신 최신 값(권한·닉네임)을 쓴다. 멀티 폴링·액션 경로는 ID 만 써서 쿼리 증가 없음
- 배포 시 기존 세션: blue/green 전환으로 메모리 세션이 사라지므로 구·신 주체 클래스가 섞이는 일은 없다

## 5. 실행한 검증
| 계층 | 명령/방법 | 결과 | 상태 |
|---|---|---|---|
| 빌드 + 전체 회귀 | `./mvnw clean test` | 402 passed (399 + 신규 3), 0 failed | ✅ |
| Integration | `./mvnw test -Dtest=LoginPrincipalContentTest` | 3 passed. 수정 전 3건 실패 확인(직렬화 불가 2, 옛 닉네임 1) | ✅ |
| 로컬 실행 | dev 프로파일을 18082 로 기동 → curl 로 비로그인 화면 9개 200·`/mypage` 302, 설정 화면의 `memberNickname = null` 확인 | 통과 | ✅ |
| 사용자 시나리오 | 아래 6번 — 개발자가 dev 에서 직접 수행 (2026-09-19) | 4건 모두 기대대로 | ✅ |
| 배포 후 Smoke | 미배포 | — | ⬜ |

## 6. 수동 확인 시나리오
[전제] dev 서버를 **다시 기동**한다(이전에 띄워 둔 8082 서버는 옛 클래스 + 새 템플릿이 섞인 상태).
1. `test1` 로그인 → 솔로(노래 맞추기)·레트로 설정 화면. [기대] 닉네임 칸에 내 닉네임이 자동 입력된다.
2. 게시판 글쓰기 → 댓글 → 좋아요 → 본인 글 삭제. [기대] 모두 성공, 작성자 닉네임 표시.
3. `/stats`. [기대] "내 기록" 영역이 보인다.
4. 관리자(`a@a.com`) → 곡 신고 처리 또는 배치 영향 곡 복원 1건. [기대] 성공, 처리자 기록.
- 결과: ☑ 통과 (4건 모두, 개발자 확인 2026-09-19)

## 7. checklist 점검
- 점검함: 호출처 전수(컴파일러 + 템플릿 grep) / 화면 필드명 일치(`nickname` 모델 속성 ↔ 템플릿) / 비로그인 접근(AC 6) / LAZY 접근 — 엔티티가 요청 안에서 새로 읽혀 이전(분리된 엔티티)보다 안전 / 로그·응답에 개인정보 추가 없음
- 해당 없음: DB·프로파일 설정, 입력 경계값, 멀티 상태 머신, 동시성

## 8. 발견된 문제와 조치
- 닉네임 변경이 같은 세션에 반영되지 않음(잠복 — 현재 변경 기능 없음) / 주체가 엔티티 사본을 보유 / 주체에서 제거하고 DB 조회 / `LoginPrincipalContentTest#nicknameChange_*`
- 작성한 테스트의 단언이 과했다: `com.kh.game.entity` 문자열 전체를 금지했더니 `Member` 안에 선언된 enum(`MemberRole`·`MemberStatus`) 이름에 걸렸다 → 엔티티 클래스 인스턴스만 금지하는 패턴으로 좁힘

## 9. 미검증 영역과 남은 위험
- 운영 배포 후 Smoke (⬜, O-010 과 함께)
- `serialVersionUID = 1L` — 세션을 Redis 에 두게 되면 주체 필드 변경 시 기존 세션 역직렬화 실패를 어떻게 다룰지 정해야 한다 (Redis 작업에서)

## 10. Regression 등록
- R-020
