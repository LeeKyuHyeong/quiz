# Security Review Guide — 전문

> 2026-09-16 `CLAUDE.md`에서 분리. 요약 규칙(Quick Reference)과 프로젝트 고유 보안 사실은 CLAUDE.md, 예시 코드·상세 체크리스트는 이 문서.
>
> **주의**: 아래 Java 예시의 `session.getAttribute("member")` 방식은 Spring Security 전환(`68c9d74`) 이전 코드 기준이다. 현재는 `SecurityConfig`가 `/admin/**`을 일괄 보호하고 로그인 사용자는 `CustomUserDetails`(`@AuthenticationPrincipal`)로 받는다. 원칙(인증→권한→소유권 검증)은 그대로 적용한다.

**⚠️ CRITICAL: 모든 기능 구현 시 보안 검토 필수!**

### 기능 구현 단계별 보안 체크

모든 기능 구현은 다음 5단계를 거쳐야 함:

```
1. 설계 → 2. 구현 → 3. 보안 검토 → 4. 테스트 → 5. 코드 리뷰
                        ↑
                   [필수 단계]
```

### 1. SQL Injection 방지

**⚠️ 절대 금지: 문자열 연결로 쿼리 생성**

```java
// ❌ 취약한 코드 - 절대 금지
@Query("SELECT s FROM Song s WHERE s.title = '" + title + "'")
List<Song> findByTitle(String title);

String sql = "SELECT * FROM song WHERE artist = '" + artist + "'";
jdbcTemplate.query(sql, ...);

// ✅ 안전한 코드 - Spring Data JPA 메서드 쿼리
List<Song> findByTitle(String title);
List<Song> findByArtistContaining(String artist);

// ✅ 안전한 코드 - 파라미터 바인딩 사용
@Query("SELECT s FROM Song s WHERE s.title = :title")
List<Song> findByTitle(@Param("title") String title);

// ✅ 안전한 코드 - Native Query도 파라미터 바인딩
@Query(value = "SELECT * FROM song WHERE genre_id = :genreId", nativeQuery = true)
List<Song> findByGenre(@Param("genreId") Long genreId);
```

**검토 체크리스트:**

| 항목 | 확인 |
|------|------|
| `@Query`에 문자열 연결(`+`) 없음 | ☐ |
| Native Query 사용 시 `:param` 바인딩 사용 | ☐ |
| `JdbcTemplate` 사용 시 `?` 플레이스홀더 사용 | ☐ |
| 동적 쿼리는 `Specification` 또는 `QueryDSL` 사용 | ☐ |
| 검색/필터 기능에 사용자 입력 직접 삽입 없음 | ☐ |

---

### 2. XSS (Cross-Site Scripting) 방지

**⚠️ Thymeleaf 기본 이스케이프 활용 + 위험 패턴 금지**

```html
<!-- ❌ 취약한 코드 - 절대 금지 -->
<span th:utext="${userInput}"></span>  <!-- utext는 HTML 그대로 출력 -->
<div th:attr="onclick='alert(' + ${userInput} + ')'"></div>
<script th:inline="javascript">
    var data = /*[[${unsafeData}]]*/ '';  // 문자열 외부 삽입
</script>

<!-- ✅ 안전한 코드 - 기본 이스케이프 사용 -->
<span th:text="${userInput}"></span>  <!-- HTML 이스케이프됨 -->
<span th:text="${board.content}"></span>

<!-- ✅ 안전한 코드 - JavaScript 내 데이터 전달 -->
<script th:inline="javascript">
    var data = /*[[${safeData}]]*/ '';  // 문자열 리터럴 내부는 안전
    var config = /*[[${jsonData}]]*/ {};  // JSON도 이스케이프됨
</script>

<!-- ✅ 안전한 코드 - data 속성 활용 -->
<div th:data-song-id="${song.id}" th:data-title="${song.title}"></div>
<script>
    const songId = element.dataset.songId;  // 안전하게 접근
</script>
```

**JavaScript에서 DOM 조작 시:**

```javascript
// ❌ 취약한 코드
element.innerHTML = userInput;
document.write(userInput);
$('#target').html(userInput);

// ✅ 안전한 코드
element.textContent = userInput;  // HTML 태그 무효화
$('#target').text(userInput);     // jQuery에서 안전한 방법

// ✅ HTML이 필요한 경우 - 서버에서 화이트리스트 필터링 후 전달
element.innerHTML = sanitizedHtmlFromServer;
```

**서버 측 입력 검증 (Service Layer):**

```java
// 게시판, 채팅 등 사용자 입력 처리 시
public String sanitizeInput(String input) {
    if (input == null) return null;
    // HTML 태그 제거 (필요시 jsoup 라이브러리 활용)
    return Jsoup.clean(input, Whitelist.none());
}

// 게시판 저장 전 검증
public Board saveBoard(BoardDto dto) {
    dto.setTitle(sanitizeInput(dto.getTitle()));
    dto.setContent(sanitizeInput(dto.getContent()));  // HTML 허용 시 Whitelist.basic() 사용
    // ...
}
```

**검토 체크리스트:**

| 항목 | 확인 |
|------|------|
| `th:utext` 사용 시 입력값이 아닌 안전한 데이터만 사용 | ☐ |
| JavaScript `innerHTML` 대신 `textContent` 사용 | ☐ |
| 사용자 입력을 URL 파라미터로 반영 시 인코딩 적용 | ☐ |
| 게시판/채팅 입력값 서버 측 필터링 적용 | ☐ |
| JSON API 응답에 Content-Type: application/json 설정 | ☐ |

---

### 3. 인증/인가 우회 방지

**⚠️ 모든 보호 리소스에 인증/인가 검증 필수**

**Controller 레벨 검증:**

```java
// ❌ 취약한 코드 - 인증 검증 없음
@GetMapping("/admin/members")
public String listMembers(Model model) {
    model.addAttribute("members", memberService.findAll());
    return "admin/members";
}

// ❌ 취약한 코드 - 세션만 확인, 권한 미확인
@PostMapping("/admin/song/delete/{id}")
public String deleteSong(@PathVariable Long id, HttpSession session) {
    if (session.getAttribute("member") != null) {  // 로그인만 확인
        songService.delete(id);
    }
    return "redirect:/admin/songs";
}

// ✅ 안전한 코드 - 권한까지 검증
@PostMapping("/admin/song/delete/{id}")
public String deleteSong(@PathVariable Long id, HttpSession session) {
    Member member = (Member) session.getAttribute("member");
    if (member == null || !"ADMIN".equals(member.getRole())) {
        throw new AccessDeniedException("관리자 권한이 필요합니다.");
    }
    songService.delete(id);
    return "redirect:/admin/songs";
}

// ✅ 더 안전한 코드 - Spring Security에서 일괄 처리 (현재 SecurityConfig의 /admin/** → hasRole("ADMIN"))
// 새 관리자 경로가 /admin/** 아래에 있는지 확인
```

**리소스 소유권 검증 (IDOR 방지):**

```java
// ❌ 취약한 코드 - 다른 사용자 데이터 접근 가능
@GetMapping("/mypage/history/{sessionId}")
public String viewHistory(@PathVariable Long sessionId, Model model) {
    GameSession session = gameSessionService.findById(sessionId);
    model.addAttribute("session", session);  // 누구의 세션이든 조회 가능!
    return "mypage/history";
}

// ✅ 안전한 코드 - 소유권 검증
@GetMapping("/mypage/history/{sessionId}")
public String viewHistory(@PathVariable Long sessionId, HttpSession httpSession, Model model) {
    Member member = (Member) httpSession.getAttribute("member");
    GameSession session = gameSessionService.findById(sessionId);

    // 본인 소유 데이터인지 확인
    if (!session.getMember().getId().equals(member.getId())) {
        throw new AccessDeniedException("본인의 게임 기록만 조회할 수 있습니다.");
    }

    model.addAttribute("session", session);
    return "mypage/history";
}

// ✅ Repository 레벨에서 검증하는 방법
@Query("SELECT g FROM GameSession g WHERE g.id = :sessionId AND g.member.id = :memberId")
Optional<GameSession> findByIdAndMemberId(@Param("sessionId") Long sessionId,
                                          @Param("memberId") Long memberId);
```

**API 엔드포인트 보호:**

```java
// ❌ 취약한 코드 - AJAX 요청에 인증 없음
@PostMapping("/api/room/{roomId}/kick/{memberId}")
@ResponseBody
public ResponseEntity<?> kickMember(@PathVariable Long roomId, @PathVariable Long memberId) {
    gameRoomService.kickMember(roomId, memberId);
    return ResponseEntity.ok().build();
}

// ✅ 안전한 코드 - 방장 권한 확인
@PostMapping("/api/room/{roomId}/kick/{memberId}")
@ResponseBody
public ResponseEntity<?> kickMember(@PathVariable Long roomId,
                                    @PathVariable Long memberId,
                                    HttpSession session) {
    Member currentMember = (Member) session.getAttribute("member");
    if (currentMember == null) {
        return ResponseEntity.status(401).body("로그인이 필요합니다.");
    }

    GameRoom room = gameRoomService.findById(roomId);
    if (!room.getHost().getId().equals(currentMember.getId())) {
        return ResponseEntity.status(403).body("방장만 강퇴할 수 있습니다.");
    }

    gameRoomService.kickMember(roomId, memberId);
    return ResponseEntity.ok().build();
}
```

**검토 체크리스트:**

| 항목 | 확인 |
|------|------|
| `/admin/**` 경로가 `SecurityConfig`의 `hasRole("ADMIN")` 매처에 걸리는지 확인 | ☐ |
| 모든 POST/PUT/DELETE에 인증 검증 존재 | ☐ |
| 리소스 접근 시 소유권(IDOR) 검증 존재 | ☐ |
| API 엔드포인트에도 세션 검증 적용 | ☐ |
| 권한 상승 가능한 기능에 role 검증 존재 | ☐ |
| 비밀번호 변경 시 현재 비밀번호 확인 | ☐ |

---

### 4. 추가 보안 검토 항목

#### CSRF (Cross-Site Request Forgery)

```html
<!-- Thymeleaf 폼에서 자동 CSRF 토큰 삽입 (Spring Security 사용 시) -->
<form th:action="@{/board/save}" method="post">
    <!-- th:action 사용 시 자동으로 _csrf 토큰 추가됨 -->
    <input type="text" name="title" />
    <button type="submit">저장</button>
</form>

<!-- AJAX 요청 시 CSRF 토큰 전달 -->
<meta name="_csrf" th:content="${_csrf.token}"/>
<meta name="_csrf_header" th:content="${_csrf.headerName}"/>

<script>
const csrfToken = document.querySelector('meta[name="_csrf"]').content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;

fetch('/api/board/save', {
    method: 'POST',
    headers: {
        [csrfHeader]: csrfToken,
        'Content-Type': 'application/json'
    },
    body: JSON.stringify(data)
});
</script>
```

#### 파일 업로드 보안

```java
// ✅ 안전한 파일 업로드 처리
public String saveUploadFile(MultipartFile file) {
    // 1. 파일 확장자 화이트리스트 검증
    String originalFilename = file.getOriginalFilename();
    String extension = getExtension(originalFilename).toLowerCase();
    List<String> allowedExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "mp3");

    if (!allowedExtensions.contains(extension)) {
        throw new IllegalArgumentException("허용되지 않는 파일 형식입니다.");
    }

    // 2. 파일 크기 검증 (application.yml에서도 설정)
    if (file.getSize() > 50 * 1024 * 1024) {  // 50MB
        throw new IllegalArgumentException("파일 크기가 너무 큽니다.");
    }

    // 3. 저장 파일명은 UUID로 변경 (경로 조작 방지)
    String savedFilename = UUID.randomUUID().toString() + "." + extension;

    // 4. 저장 경로는 웹 루트 외부로 설정
    Path uploadPath = Paths.get(uploadDir).resolve(savedFilename);
    Files.copy(file.getInputStream(), uploadPath);

    return savedFilename;
}
```

#### 민감 정보 로깅 금지

```java
// ❌ 금지 - 비밀번호, 개인정보 로깅
log.info("Login attempt: username={}, password={}", username, password);
log.debug("Member info: {}", member);  // toString()에 민감정보 포함 시

// ✅ 안전한 로깅
log.info("Login attempt: username={}", username);
log.info("Login success: memberId={}", member.getId());
```

---

### 보안 검토 최종 체크리스트

기능 구현 완료 후 아래 항목을 모두 확인:

| 카테고리 | 항목 | 확인 |
|----------|------|------|
| **SQL Injection** | 모든 쿼리에 파라미터 바인딩 사용 | ☐ |
| | 동적 검색 조건에 문자열 연결 없음 | ☐ |
| **XSS** | 사용자 입력 출력 시 `th:text` 사용 | ☐ |
| | JavaScript에서 `textContent` 사용 | ☐ |
| | 서버 측 입력값 필터링 적용 | ☐ |
| **인증** | 보호 리소스에 로그인 검증 존재 | ☐ |
| | 관리자 기능에 권한 검증 존재 | ☐ |
| **인가** | 리소스 소유권(IDOR) 검증 존재 | ☐ |
| | API 엔드포인트 권한 검증 존재 | ☐ |
| **CSRF** | 상태 변경 요청에 CSRF 토큰 적용 | ☐ |
| **파일** | 업로드 파일 확장자 화이트리스트 검증 | ☐ |
| | 저장 파일명 UUID로 변경 | ☐ |
| **로깅** | 민감정보(비밀번호 등) 로깅 없음 | ☐ |
| **세션** | 로그인 성공 시 세션 ID 재생성 | ☐ |
