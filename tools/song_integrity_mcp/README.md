# Song Integrity MCP

노래 데이터 정합성 체크를 위한 MCP(Model Context Protocol) 서버

## 팀 구성 (8개 에이전트)

| # | Agent | 역할 |
|---|-------|------|
| 1 | **DataManager** | DB 데이터 저장/관리 |
| 2 | **YouTubeSearcher** | YouTube 정보 검색 |
| 3 | **DataVerifier** | 검색 결과 검증 |
| 4 | **DataComparator** | 현재/실제 데이터 비교 |
| 5 | **SearchHelper** | 검색/검증 보조 |
| 6 | **DecisionAdvisor** | 사용자 결정 요청 |
| 7 | **DBUpdater** | 운영DB 적용 |
| 8 | **AnswerMatcher** | YouTube 제목과 song_answer 정답 매칭 |

## 설치

```bash
cd D:/game/tools/song_integrity_mcp
pip install -r requirements.txt
```

## Claude Desktop 설정

`claude_desktop_config.json`에 추가:

```json
{
  "mcpServers": {
    "song_integrity_mcp": {
      "command": "python",
      "args": ["D:/game/tools/song_integrity_mcp/song_integrity_mcp.py"],
      "env": {
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

## 사용 가능한 도구

### 세션 관리
- `integrity_start_session` - 정합성 체크 세션 시작
- `integrity_check_next` - 다음 노래 체크
- `integrity_get_summary` - 세션 요약

### 노래 체크
- `integrity_check_song` - 특정 노래 체크
- `integrity_search_alternatives` - 대체 영상 검색
- `integrity_verify_video` - YouTube ID 유효성 확인

### 결정 및 적용
- `integrity_make_decision` - 결정 (keep/update/delete/replace/skip)
- `integrity_apply_all` - 모든 결정 적용

### 정답 매칭
- `integrity_check_answer_match` - YouTube 제목에서 추출한 노래제목과 song_answer 대표정답 비교
- `integrity_check_answer_match_batch` - 여러 노래 일괄 정답 매칭
- `integrity_get_song_answers` - 노래의 정답 목록 조회

### 상태 확인
- `integrity_get_stats` - DB 통계
- `integrity_agent_status` - 에이전트 상태
- `integrity_clear_cache` - 캐시 초기화

## 워크플로우

```
1. integrity_start_session     → 세션 시작
2. integrity_check_next        → 다음 노래 체크 (반복)
3. integrity_make_decision     → 각 노래에 대해 결정
4. integrity_apply_all         → 모든 결정 적용 (dry_run 먼저)
5. integrity_get_summary       → 최종 요약 확인
```

## 테스트

```bash
# 전체 테스트
python test_mcp.py

# 개별 테스트
python test_mcp.py db          # DB 연결 테스트
python test_mcp.py song 123    # 특정 노래 체크
python test_mcp.py youtube     # YouTube 검색 테스트
python test_mcp.py session     # 세션 워크플로우 테스트
python test_mcp.py status      # 에이전트 상태 확인
```
