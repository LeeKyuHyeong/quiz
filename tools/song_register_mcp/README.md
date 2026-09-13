# Song Register MCP

노래 등록 MCP 서버 - 다중 에이전트 방식으로 YouTube에서 노래 정보를 추출하고 DB에 등록합니다.

## 폴더 구조

```
프로젝트루트/
├── tools/
│   └── song_register_mcp/
│       ├── song_register_mcp.py    # MCP 서버 메인
│       ├── requirements.txt        # Python 의존성
│       ├── install.bat             # Windows 설치 스크립트
│       └── README.md               # 이 문서
└── claude_desktop_config.json      # Claude Desktop 설정 예시
```

## 설치

### Windows

```cmd
cd tools\song_register_mcp
install.bat
```

또는 수동:

```cmd
cd tools\song_register_mcp
pip install -r requirements.txt
```

### macOS/Linux

```bash
cd tools/song_register_mcp
pip install -r requirements.txt
```

## Claude Desktop 설정

`%APPDATA%\Claude\claude_desktop_config.json` (Windows)
`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS)

```json
{
  "mcpServers": {
    "playwright": {
      "command": "npx",
      "args": ["-y", "@playwright/mcp@latest"]
    },
    "song_register_mcp": {
      "command": "python",
      "args": ["C:/프로젝트경로/tools/song_register_mcp/song_register_mcp.py"],
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

> ⚠️ `args` 경로를 실제 프로젝트 경로로 수정하세요!

## 사용법

Claude Desktop에서:

```
아이유-밤편지 등록해줘
```

## 워크플로우

```
1번 agent_song_info      → 노래 정보 수집
2번 agent_ad_analyzer    → 광고/시작시간 분석  
3번 agent_genre_classifier → 장르 감별
4번 agent_sql_builder    → SQL 생성
5번 agent_verifier       → 정보 검증
6번 agent_db_executor    → DB 저장
```

## INSERT문 예시

```sql
INSERT INTO song (title, artist, start_time, play_duration, genre_id, release_year, is_solo, use_yn, youtube_video_id)
VALUES (
    '밤편지',
    '아이유 (IU)',
    3,
    258,
    5,
    2017,
    1,
    'Y',
    'GsT6_HXXD94'
);
```

## 장르 매핑

| 입력 | 자동 매핑 |
|------|-----------|
| K-POP 발라드 | KPOP_BALLAD (id=5) |
| 힙합 | HIPHOP (id=13) |
| R&B | RNB (id=15) |
| 아이돌 | KPOP_IDOL (id=2) |

매핑 실패 시 `new_genre_code`, `new_genre_name`으로 신규 장르 추가 가능.

## MCP Tools

| Tool | 설명 |
|------|------|
| `register_song` | 워크플로우 시작 안내 |
| `agent_song_info` | 노래 정보 저장 |
| `agent_ad_analyzer` | 광고/시작시간 분석 |
| `agent_genre_classifier` | 장르 감별 |
| `agent_sql_builder` | INSERT문 생성 |
| `agent_verifier` | 정보 검증 |
| `agent_db_executor` | DB INSERT 실행 |
| `list_songs` | 등록된 노래 조회 |
| `list_genres` | 장르 목록 조회 |
| `get_song_status` | 등록 상태 확인 |
