# Song Checker MCP Server

가수 이름을 입력받아 해당 가수의 곡들을 검사하고, MV vs Lyrics 판별, 가수-곡 정보 검증을 수행하는 MCP 서버입니다.

## 기능

1. **곡 조회**: 가수명으로 DB에서 곡 목록 조회 (제목, YouTube ID, 재생시간)
2. **MV/Lyrics 판별**: YouTube 제목 기반으로 MV인지 Lyrics인지 자동 판별
3. **Lyrics 영상 교체**: MV인 경우 Claude 웹서칭으로 Lyrics 영상 찾아서 교체
4. **가수-곡 검증**: 웹서칭으로 가수-곡제목 매칭 정확도 검증
5. **일괄 업데이트**: 검수 완료 후 수정사항 DB 적용

## 설치

### Windows

```cmd
cd tools\song_checker_mcp
install.bat
```

### 수동 설치

```bash
cd tools/song_checker_mcp
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

## Claude Desktop 설정

`%APPDATA%\Claude\claude_desktop_config.json` (Windows) 또는 `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS)에 추가:

```json
{
  "mcpServers": {
    "song_checker_mcp": {
      "command": "python",
      "args": ["D:/game/tools/song_checker_mcp/song_checker_mcp.py"],
      "env": {
        "DB_TYPE": "mariadb",
        "MARIADB_HOST": "YOUR_DB_HOST",
        "MARIADB_PORT": "3308",
        "MARIADB_USER": "root",
        "MARIADB_PASSWORD": "YOUR_PASSWORD",
        "MARIADB_DATABASE": "song"
      }
    }
  }
}
```

## MCP 도구 목록

### 워크플로우 시작/상태

| 도구 | 설명 |
|------|------|
| `start_artist_check` | 가수 검사 시작, 곡 목록 반환 |
| `get_check_status` | 현재 검사 진행 상태 조회 |

### MV vs Lyrics 검사

| 도구 | 설명 |
|------|------|
| `fetch_youtube_title` | YouTube oEmbed API로 영상 제목 조회 |
| `check_mv_or_lyrics` | 제목 기반 MV/Lyrics 판별 |
| `suggest_lyrics_video` | Lyrics 영상 ID 제안 (교체용) |

### 가수-곡 검증

| 도구 | 설명 |
|------|------|
| `verify_song_artist` | 가수-곡 매칭 검증 결과 저장 |
| `mark_song_for_update` | 수정 필요 항목 표시 |

### 최종 검수/적용

| 도구 | 설명 |
|------|------|
| `finalize_check` | 곡 검수 완료 표시 |
| `get_pending_updates` | 대기 중인 수정사항 조회 |
| `apply_updates` | 수정사항 DB 적용 |
| `cancel_update` | 특정 수정사항 취소 |

## 사용 예시

```
사용자: 아이유 검사해줘

Claude:
1. start_artist_check("아이유") 호출 → 곡 15개 조회됨
2. fetch_youtube_title("abc123") → "아이유(IU) - 밤편지 MV"
3. check_mv_or_lyrics → MV로 판별됨
4. [웹서칭] "아이유 밤편지 가사 YouTube" 검색
5. suggest_lyrics_video(song_id=123, suggested_video_id="xyz789")
6. [웹서칭] "밤편지 원곡 가수" 검색
7. verify_song_artist(song_id=123, is_correct=True, sources=["멜론", "나무위키"])
8. finalize_check(song_id=123)
... (모든 곡 반복)
9. get_pending_updates() → 3개 수정 대기
10. apply_updates(confirm=True) → DB 업데이트 완료
```

## MV vs Lyrics 판별 기준

### MV로 판별되는 키워드
- MV, M/V, Music Video, Official Video, Official MV
- 뮤직비디오, 뮤비

### Lyrics로 판별되는 키워드
- Lyrics, Lyric, 가사, Audio, Official Audio, Visualizer

### 불확실한 경우
- 위 키워드가 모두 없으면 사용자 확인 필요로 표시

## 환경변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DB_TYPE` | mariadb | DB 유형 (mariadb/sqlite) |
| `MARIADB_HOST` | - (필수) | MariaDB 호스트 |
| `MARIADB_PORT` | 3308 | MariaDB 포트 |
| `MARIADB_USER` | root | MariaDB 사용자 |
| `MARIADB_PASSWORD` | - (필수) | MariaDB 비밀번호 |
| `MARIADB_DATABASE` | song | MariaDB 데이터베이스 |

## 데이터 저장

검사 결과는 `data/temp/{가수명}_check_result.json`에 임시 저장됩니다.
