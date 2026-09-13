"""
노래 등록 MCP 서버 (운영 DB 연동)
다중 에이전트 방식으로 YouTube URL에서 노래 정보를 추출하고 DB에 등록합니다.

운영 DB 테이블: song, genre, song_answer

=== 메인 워크플로우 ===
1. start_artist_workflow: 아티스트 입력 → 웹 검색으로 대표곡 13~16개 파악 → DB 미등록곡 목록 제시
2. 사용자가 '다음곡 주세요' → lyrics YouTube URL 제공
3. agent_song_info ~ agent_db_executor: 노래 등록 파이프라인 실행
4. 등록 완료 후 Claude가 '다음곡 주세요'라고 안내 → 2번 반복
5. 모든 곡 등록 완료 후 '다음 아티스트 주세요' → 1번 반복

=== 곡 등록 파이프라인 ===
1. agent_song_info: 노래 정보 저장
2. agent_ad_analyzer: 노래 시작 시간 설정
3. agent_genre_classifier: 장르 분류
4. agent_answer_collector: 정답 수집 (사람들이 부르는 별칭들)
5. agent_sql_builder: SQL INSERT문 생성
6. agent_verifier: 정보 검증
7. agent_db_executor: DB에 song + song_answer INSERT 실행

환경변수:
- DB_TYPE: 'sqlite' (기본값) 또는 'mariadb'
- MARIADB_HOST: MariaDB 호스트 (필수)
- MARIADB_PORT: MariaDB 포트 (기본값: 3308)
- MARIADB_USER: MariaDB 사용자 (기본값: root)
- MARIADB_PASSWORD: MariaDB 비밀번호 (필수)
- MARIADB_DATABASE: MariaDB 데이터베이스명 (기본값: song)
"""

import os
import re
import json
import sqlite3
import asyncio
from datetime import datetime
from typing import Optional, Dict, Any, List
from pathlib import Path
from contextlib import contextmanager

from mcp.server.fastmcp import FastMCP
from pydantic import BaseModel, Field, ConfigDict

# ========== DB 설정 ==========
DB_TYPE = os.environ.get('DB_TYPE', 'mariadb')  # 'sqlite' 또는 'mariadb' - 기본값 mariadb (운영 DB)

# MariaDB 설정 (운영 서버)
MARIADB_CONFIG = {
    'host': os.environ.get('MARIADB_HOST'),
    'port': int(os.environ.get('MARIADB_PORT', '3308')),
    'user': os.environ.get('MARIADB_USER', 'root'),
    'password': os.environ.get('MARIADB_PASSWORD'),
    'database': os.environ.get('MARIADB_DATABASE', 'song'),
    'charset': 'utf8mb4'
}

# SQLite 설정 (로컬)
DATA_DIR = Path(__file__).parent / "data"
SQLITE_PATH = DATA_DIR / "songs.db"
TEMP_DIR = DATA_DIR / "temp"

# 디렉토리 생성
DATA_DIR.mkdir(exist_ok=True)
TEMP_DIR.mkdir(exist_ok=True)

# MCP 서버 초기화
mcp = FastMCP("song_register_mcp")


# ========== 데이터베이스 연결 관리 ==========
@contextmanager
def get_db_connection():
    """DB 타입에 따른 연결 반환"""
    conn = None
    try:
        if DB_TYPE == 'mariadb':
            import pymysql
            missing = [k for k in ('MARIADB_HOST', 'MARIADB_PASSWORD') if not os.environ.get(k)]
            if missing:
                raise RuntimeError(f"환경변수 미설정: {', '.join(missing)}")
            conn = pymysql.connect(**MARIADB_CONFIG)
            yield conn
        else:
            conn = sqlite3.connect(SQLITE_PATH)
            yield conn
    finally:
        if conn:
            conn.close()


def get_placeholder():
    """DB 타입에 따른 placeholder 반환"""
    return '%s' if DB_TYPE == 'mariadb' else '?'


def init_db():
    """데이터베이스 및 테이블 초기화 (SQLite용, MariaDB는 이미 존재)"""
    with get_db_connection() as conn:
        cursor = conn.cursor()
        
        if DB_TYPE == 'mariadb':
            # MariaDB는 기존 테이블 사용 - 존재 확인만
            cursor.execute("SHOW TABLES LIKE 'song'")
            if cursor.fetchone():
                print("운영 DB 'song' 테이블 확인됨")
            cursor.execute("SHOW TABLES LIKE 'genre'")
            if cursor.fetchone():
                print("운영 DB 'genre' 테이블 확인됨")
        else:
            # SQLite용 테이블 생성 (로컬 테스트용)
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS song (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    start_time INTEGER DEFAULT 0,
                    play_duration INTEGER DEFAULT 180,
                    genre_id INTEGER,
                    release_year INTEGER,
                    is_solo INTEGER DEFAULT 0,
                    use_yn TEXT DEFAULT 'Y',
                    youtube_video_id TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)
            # 장르 테이블 (로컬 테스트용)
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS genre (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code TEXT UNIQUE,
                    name TEXT NOT NULL,
                    display_order INTEGER,
                    use_yn TEXT DEFAULT 'Y',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)
            # 기본 장르 데이터 (로컬 테스트용)
            default_genres = [
                (1, 'KPOP', 'K-POP', 1),
                (2, 'KPOP_IDOL', 'K-POP 아이돌', 2),
                (3, 'KPOP_HIPHOP', 'K-POP 힙합', 3),
                (4, 'KPOP_RNB', 'K-POP R&B', 4),
                (5, 'KPOP_BALLAD', 'K-POP 발라드', 5),
                (6, 'KPOP_INDIE', 'K-POP 인디', 6),
                (7, 'KPOP_TROT', '트로트', 7),
                (8, 'POP', 'POP', 10),
                (9, 'POP_DANCE', 'POP 댄스', 11),
                (10, 'POP_BALLAD', 'POP 발라드', 12),
                (11, 'JPOP', 'J-POP', 15),
                (12, 'CPOP', 'C-POP', 16),
                (13, 'HIPHOP', '힙합', 20),
                (14, 'RAP', '랩', 21),
                (15, 'RNB', 'R&B', 22),
                (16, 'SOUL', '소울', 23),
                (17, 'ROCK', '록', 30),
                (18, 'ROCK_CLASSIC', '클래식 록', 31),
                (19, 'ROCK_INDIE', '인디 록', 32),
                (20, 'METAL', '메탈', 33),
                (21, 'PUNK', '펑크', 34),
                (22, 'EDM', 'EDM', 40),
                (23, 'HOUSE', '하우스', 41),
                (24, 'TECHNO', '테크노', 42),
                (25, 'DISCO', '디스코', 43),
                (26, 'JAZZ', '재즈', 50),
                (27, 'BLUES', '블루스', 51),
                (28, 'CLASSIC', '클래식', 60),
                (29, 'OST', 'OST/영화음악', 70),
                (30, 'CCM', 'CCM/가스펠', 80),
            ]
            for g in default_genres:
                cursor.execute("""
                    INSERT OR IGNORE INTO genre (id, code, name, display_order, use_yn)
                    VALUES (?, ?, ?, ?, 'Y')
                """, g)
            conn.commit()
            print("SQLite DB 초기화 완료")

# 서버 시작 시 DB 초기화
try:
    init_db()
except Exception as e:
    print(f"DB 초기화 실패: {e}")


# ========== 입력 모델 정의 ==========
class SongSearchInput(BaseModel):
    """노래 검색 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    query: str = Field(..., description="검색어 (예: '아이유-밤편지', 'BTS Dynamite')")


class SongInfoInput(BaseModel):
    """노래 정보 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID (예: 'dQw4w9WgXcQ')")
    title: str = Field(..., description="노래 제목")
    artist: str = Field(..., description="아티스트명")
    play_duration: int = Field(..., description="노래 전체 재생 시간(초) - YouTube 영상 길이", ge=1)
    release_year: Optional[int] = Field(None, description="발매연도 (예: 2023)")
    is_solo: bool = Field(default=False, description="솔로 아티스트 여부")


class AdAnalysisInput(BaseModel):
    """광고 분석 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID")
    has_ad: bool = Field(default=False, description="광고 유무")
    ad_duration: int = Field(default=0, description="광고 길이(초)", ge=0)
    intro_duration: int = Field(default=0, description="인트로/무음 구간 길이(초) - 실제 노래 시작 전 구간", ge=0)


class GenreClassifyInput(BaseModel):
    """장르 분류 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID")
    detected_genre: str = Field(..., description="웹 검색으로 확인한 장르 (예: 'K-POP 발라드', 'Hip-Hop', 'R&B')")
    genre_id: Optional[int] = Field(None, description="직접 지정할 genre_id (기존 장르 목록에서 선택)")
    new_genre_code: Optional[str] = Field(None, description="새 장르 추가 시 코드 (예: 'KPOP_NEWAGE')")
    new_genre_name: Optional[str] = Field(None, description="새 장르 추가 시 이름 (예: 'K-POP 뉴에이지')")


class SqlBuildInput(BaseModel):
    """SQL 빌드 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID")


class VerifyInput(BaseModel):
    """검증 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID")
    actual_start_time: Optional[int] = Field(None, description="실제 확인된 시작 시간(초)")
    is_correct: bool = Field(default=True, description="정보가 정확한지 여부")
    note: Optional[str] = Field(None, description="검증 메모")


class ExecuteInput(BaseModel):
    """실행 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID")
    force: bool = Field(default=False, description="검증 없이 강제 실행")


class RegisterInput(BaseModel):
    """등록 워크플로우 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    query: str = Field(..., description="검색어 (예: '아이유-밤편지')")
    command: str = Field(..., description="명령어 (예: 등록해줘)")


class ArtistWorkflowInput(BaseModel):
    """아티스트 워크플로우 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    artist: str = Field(..., description="아티스트명 (예: 'BTS', '아이유')")
    representative_songs: List[str] = Field(..., description="웹 검색으로 파악한 대표곡 목록 (13~16곡)")


class NextSongInput(BaseModel):
    """다음 곡 요청 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_url: str = Field(..., description="lyrics YouTube 영상 URL")


class AnswerCollectorInput(BaseModel):
    """정답 수집 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID")
    answers: List[str] = Field(..., description="정답 목록 (사람들이 부르는 다양한 이름들). 첫 번째가 대표 정답(is_primary=1)")


class BatchSongItem(BaseModel):
    """배치 등록용 개별 곡 정보"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID (11자리)")
    title: str = Field(..., description="노래 제목")
    artist: str = Field(..., description="아티스트명")
    play_duration: int = Field(..., description="재생시간(초)", ge=1)
    genre_id: int = Field(..., description="장르 ID")
    answers: List[str] = Field(..., description="정답 목록 (첫 번째가 대표 정답)")
    release_year: Optional[int] = Field(None, description="발매연도")
    start_time: int = Field(default=0, description="노래 시작 시간(초)")
    is_solo: bool = Field(default=False, description="솔로 아티스트 여부")


class BatchRegisterInput(BaseModel):
    """배치 등록 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    songs: List[BatchSongItem] = Field(..., description="등록할 곡 목록 (최대 20곡)")


class DataValidatorInput(BaseModel):
    """데이터 검증 입력 모델"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID")


# ========== 유틸리티 함수 ==========
def extract_video_id(url: str) -> Optional[str]:
    """YouTube URL에서 비디오 ID 추출"""
    patterns = [
        r'(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/embed/)([^&\n?#]+)',
        r'youtube\.com/shorts/([^&\n?#]+)',
        r'^([a-zA-Z0-9_-]{11})$'  # 비디오 ID만 입력된 경우
    ]
    for pattern in patterns:
        match = re.search(pattern, url)
        if match:
            return match.group(1)
    return None


def get_temp_file_path(video_id: str) -> Path:
    """비디오 ID에 해당하는 임시 파일 경로 반환"""
    return TEMP_DIR / f"{video_id}_info.txt"


def save_song_info(video_id: str, info: Dict[str, Any]) -> str:
    """노래 정보를 txt 파일로 저장"""
    file_path = get_temp_file_path(video_id)
    content = f"""=== 노래 정보 ===
YouTube ID: {video_id}
제목: {info.get('title', '미확인')}
아티스트: {info.get('artist', '미확인')}
전체재생시간(초): {info.get('play_duration', 0)}
장르ID: {info.get('genre_id', '미지정')}
장르명: {info.get('genre_name', '미지정')}
발매연도: {info.get('release_year', '미확인')}
솔로여부: {info.get('is_solo', False)}
사용여부: {info.get('use_yn', 'Y')}
수집일시: {datetime.now().isoformat()}

=== 광고/시작시간 정보 ===
광고유무: {info.get('has_ad', False)}
광고길이(초): {info.get('ad_duration', 0)}
인트로길이(초): {info.get('intro_duration', 0)}
노래시작시간(초): {info.get('start_time', 0)}

=== 장르 분류 정보 ===
감지된장르: {info.get('detected_genre', '')}
매핑된장르ID: {info.get('genre_id', '미지정')}
매핑된장르명: {info.get('genre_name', '미지정')}

=== 정답 목록 ===
정답들: {json.dumps(info.get('answers', []), ensure_ascii=False)}

=== 검증 정보 ===
검증완료: {info.get('verified', False)}
검증메모: {info.get('verification_note', '')}

=== SQL ===
{info.get('sql', '미생성')}
"""
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    return str(file_path)


def load_song_info(video_id: str) -> Optional[Dict[str, Any]]:
    """txt 파일에서 노래 정보 로드"""
    file_path = get_temp_file_path(video_id)
    if not file_path.exists():
        return None
    
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 파싱
    info = {'youtube_video_id': video_id}
    
    # 기본 정보 파싱
    title_match = re.search(r'제목: (.+)', content)
    if title_match:
        val = title_match.group(1).strip()
        if val != '미확인':
            info['title'] = val
    
    artist_match = re.search(r'아티스트: (.+)', content)
    if artist_match:
        val = artist_match.group(1).strip()
        if val != '미확인':
            info['artist'] = val
    
    play_duration_match = re.search(r'전체재생시간\(초\): (\d+)', content)
    if play_duration_match:
        info['play_duration'] = int(play_duration_match.group(1))
    
    genre_id_match = re.search(r'장르ID: (\d+)', content)
    if genre_id_match:
        info['genre_id'] = int(genre_id_match.group(1))
    
    genre_name_match = re.search(r'장르명: (.+)', content)
    if genre_name_match:
        val = genre_name_match.group(1).strip()
        if val != '미지정':
            info['genre_name'] = val
    
    release_year_match = re.search(r'발매연도: (\d{4})', content)
    if release_year_match:
        info['release_year'] = int(release_year_match.group(1))
    
    is_solo_match = re.search(r'솔로여부: (True|False)', content)
    if is_solo_match:
        info['is_solo'] = is_solo_match.group(1) == 'True'
    
    use_yn_match = re.search(r'사용여부: (.+)', content)
    if use_yn_match:
        info['use_yn'] = use_yn_match.group(1).strip()
    
    # 광고 정보 파싱
    has_ad_match = re.search(r'광고유무: (True|False)', content)
    if has_ad_match:
        info['has_ad'] = has_ad_match.group(1) == 'True'
    
    ad_duration_match = re.search(r'광고길이\(초\): (\d+)', content)
    if ad_duration_match:
        info['ad_duration'] = int(ad_duration_match.group(1))
    
    intro_duration_match = re.search(r'인트로길이\(초\): (\d+)', content)
    if intro_duration_match:
        info['intro_duration'] = int(intro_duration_match.group(1))
    
    start_time_match = re.search(r'노래시작시간\(초\): (\d+)', content)
    if start_time_match:
        info['start_time'] = int(start_time_match.group(1))
    
    # 장르 분류 정보 파싱
    detected_genre_match = re.search(r'감지된장르: (.+)', content)
    if detected_genre_match:
        val = detected_genre_match.group(1).strip()
        if val:
            info['detected_genre'] = val
    
    # 정답 목록 파싱
    answers_match = re.search(r'정답들: (\[.*?\])', content)
    if answers_match:
        try:
            info['answers'] = json.loads(answers_match.group(1))
        except:
            info['answers'] = []

    # 검증 정보 파싱
    verified_match = re.search(r'검증완료: (True|False)', content)
    if verified_match:
        info['verified'] = verified_match.group(1) == 'True'

    note_match = re.search(r'검증메모: (.+)', content)
    if note_match:
        info['verification_note'] = note_match.group(1).strip()

    # SQL 파싱
    sql_match = re.search(r'=== SQL ===\n(.+)', content, re.DOTALL)
    if sql_match:
        info['sql'] = sql_match.group(1).strip()

    return info


def get_all_genres() -> List[Dict[str, Any]]:
    """모든 장르 목록 조회"""
    try:
        with get_db_connection() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT id, code, name FROM genre WHERE use_yn = 'Y' ORDER BY display_order")
            rows = cursor.fetchall()
            return [{'id': r[0], 'code': r[1], 'name': r[2]} for r in rows]
    except:
        return []


def find_best_matching_genre(detected_genre: str, genres: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """감지된 장르와 가장 유사한 기존 장르 찾기"""
    detected_lower = detected_genre.lower().replace('-', '').replace(' ', '')
    
    # 정확히 일치하는 경우
    for g in genres:
        if g['name'].lower().replace('-', '').replace(' ', '') == detected_lower:
            return g
        if g['code'].lower().replace('_', '') == detected_lower:
            return g
    
    # 부분 일치 (포함 관계)
    for g in genres:
        name_lower = g['name'].lower().replace('-', '').replace(' ', '')
        code_lower = g['code'].lower().replace('_', '')
        if detected_lower in name_lower or name_lower in detected_lower:
            return g
        if detected_lower in code_lower or code_lower in detected_lower:
            return g
    
    # 키워드 매칭
    keyword_map = {
        'kpop': ['KPOP', 'K-POP'],
        'k-pop': ['KPOP', 'K-POP'],
        'ballad': ['BALLAD', '발라드'],
        '발라드': ['BALLAD', '발라드'],
        'hiphop': ['HIPHOP', '힙합'],
        'hip-hop': ['HIPHOP', '힙합'],
        '힙합': ['HIPHOP', '힙합'],
        'rnb': ['RNB', 'R&B'],
        'r&b': ['RNB', 'R&B'],
        'pop': ['POP'],
        'rock': ['ROCK', '록'],
        '록': ['ROCK', '록'],
        'idol': ['IDOL', '아이돌'],
        '아이돌': ['IDOL', '아이돌'],
        'trot': ['TROT', '트로트'],
        '트로트': ['TROT', '트로트'],
        'indie': ['INDIE', '인디'],
        '인디': ['INDIE', '인디'],
        'edm': ['EDM'],
        'jazz': ['JAZZ', '재즈'],
        '재즈': ['JAZZ', '재즈'],
        'ost': ['OST'],
        'classic': ['CLASSIC', '클래식'],
        '클래식': ['CLASSIC', '클래식'],
    }
    
    for keyword, matches in keyword_map.items():
        if keyword in detected_lower:
            for g in genres:
                for m in matches:
                    if m.upper() in g['code'].upper() or m in g['name']:
                        return g
    
    return None


# ========== Agent 1: 노래 정보 수집 ==========
@mcp.tool(
    name="agent_song_info",
    annotations={
        "title": "1번 에이전트: 노래 정보 수집",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": True
    }
)
async def agent_song_info(params: SongInfoInput) -> str:
    """
    [1번 인원] 노래 정보를 수집하여 .txt 파일로 저장합니다.
    
    YouTube 비디오 ID와 함께 노래의 제목, 아티스트, 전체 재생시간 등을 저장합니다.
    Playwright MCP로 YouTube에서 정보를 수집한 후 이 도구를 호출하세요.
    
    Args:
        params: 노래 정보
            - youtube_video_id: YouTube 비디오 ID (11자리)
            - title: 노래 제목
            - artist: 아티스트명
            - play_duration: 노래 전체 재생 시간(초) - YouTube 영상 길이
            - release_year: 발매연도 (선택)
            - is_solo: 솔로 아티스트 여부
        
    Returns:
        저장된 파일 경로와 수집된 정보 요약
    """
    video_id = params.youtube_video_id
    
    if not video_id or len(video_id) != 11:
        return json.dumps({
            "status": "error",
            "message": "유효한 YouTube 비디오 ID가 아닙니다. (11자리)",
            "youtube_video_id": video_id
        }, ensure_ascii=False, indent=2)
    
    # 정보 구성
    info = {
        'youtube_video_id': video_id,
        'title': params.title,
        'artist': params.artist,
        'play_duration': params.play_duration,
        'genre_id': None,
        'genre_name': None,
        'release_year': params.release_year,
        'is_solo': params.is_solo,
        'use_yn': 'Y',
        'has_ad': False,
        'ad_duration': 0,
        'intro_duration': 0,
        'start_time': 0,
        'detected_genre': '',
        'verified': False,
        'verification_note': '',
        'sql': '미생성'
    }
    
    # 파일 저장
    file_path = save_song_info(video_id, info)
    
    return json.dumps({
        "status": "success",
        "agent": "1번 - 노래정보 수집",
        "message": f"노래 정보를 수집하여 저장했습니다.",
        "file_path": file_path,
        "collected_info": {
            "youtube_video_id": video_id,
            "youtube_url": f"https://www.youtube.com/watch?v={video_id}",
            "title": info['title'],
            "artist": info['artist'],
            "play_duration": f"{info['play_duration']}초 ({info['play_duration']//60}분 {info['play_duration']%60}초)",
            "release_year": info['release_year'],
            "is_solo": info['is_solo']
        },
        "next_step": "2번 에이전트(agent_ad_analyzer)를 호출하여 광고/인트로 정보를 분석하세요."
    }, ensure_ascii=False, indent=2)


# ========== Agent 2: 광고 분석 ==========
@mcp.tool(
    name="agent_ad_analyzer",
    annotations={
        "title": "2번 에이전트: 광고/시작시간 분석",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": True
    }
)
async def agent_ad_analyzer(params: AdAnalysisInput) -> str:
    """
    [2번 인원] 광고 정보를 확인하고 노래 시작 시간을 계산합니다.
    
    YouTube 영상의 광고 유무, 광고 길이, 인트로 구간을 분석하여
    실제 노래가 시작되는 시간을 계산합니다.
    Playwright MCP로 영상을 확인한 후 이 도구를 호출하세요.
    
    Args:
        params: 광고 분석 정보
            - youtube_video_id: YouTube 비디오 ID
            - has_ad: 광고 유무
            - ad_duration: 광고 길이(초)
            - intro_duration: 인트로/무음 구간 길이(초) - 실제 노래 시작 전 시간
        
    Returns:
        계산된 노래 시작 시간과 분석 결과
    """
    video_id = params.youtube_video_id
    
    # 기존 정보 로드
    info = load_song_info(video_id)
    if not info:
        return json.dumps({
            "status": "error",
            "message": "노래 정보 파일이 없습니다. 먼저 agent_song_info를 실행하세요.",
            "youtube_video_id": video_id
        }, ensure_ascii=False, indent=2)
    
    # 광고 정보 업데이트
    info['has_ad'] = params.has_ad
    info['ad_duration'] = params.ad_duration
    info['intro_duration'] = params.intro_duration
    
    # 노래 시작 시간 = 인트로 시간 (광고는 스킵 가능하므로 제외)
    info['start_time'] = params.intro_duration
    
    # 파일 업데이트
    file_path = save_song_info(video_id, info)
    
    return json.dumps({
        "status": "success",
        "agent": "2번 - 광고/시작시간 분석",
        "message": "광고 정보를 분석하고 노래 시작 시간을 계산했습니다.",
        "analysis": {
            "has_ad": params.has_ad,
            "ad_duration": params.ad_duration,
            "intro_duration": params.intro_duration,
            "calculated_start_time": info['start_time']
        },
        "file_path": file_path,
        "next_step": "3번 에이전트(agent_genre_classifier)를 호출하여 장르를 분류하세요."
    }, ensure_ascii=False, indent=2)


# ========== Agent 3: 장르 감별사 ==========
@mcp.tool(
    name="agent_genre_classifier",
    annotations={
        "title": "3번 에이전트: 장르 감별사",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": True
    }
)
async def agent_genre_classifier(params: GenreClassifyInput) -> str:
    """
    [3번 인원] 웹 검색으로 확인한 장르를 기존 장르와 매핑하거나 신규 장르를 추가합니다.
    
    1. 웹 검색으로 노래의 장르를 확인
    2. 기존 장르 목록에서 가장 유사한 장르 찾기
    3. 없으면 새 장르 추가 (new_genre_code, new_genre_name 필요)
    
    Args:
        params: 장르 분류 정보
            - youtube_video_id: YouTube 비디오 ID
            - detected_genre: 웹 검색으로 확인한 장르 (예: 'K-POP 발라드')
            - genre_id: 직접 지정할 장르 ID (선택, 기존 목록에서 선택)
            - new_genre_code: 새 장르 코드 (선택, 예: 'KPOP_NEWAGE')
            - new_genre_name: 새 장르 이름 (선택, 예: 'K-POP 뉴에이지')
        
    Returns:
        매핑된 장르 정보 또는 신규 장르 추가 결과
    """
    video_id = params.youtube_video_id
    
    # 기존 정보 로드
    info = load_song_info(video_id)
    if not info:
        return json.dumps({
            "status": "error",
            "message": "노래 정보 파일이 없습니다. 먼저 이전 에이전트들을 실행하세요.",
            "youtube_video_id": video_id
        }, ensure_ascii=False, indent=2)
    
    info['detected_genre'] = params.detected_genre
    
    # 기존 장르 목록 조회
    genres = get_all_genres()
    
    # 1. genre_id가 직접 지정된 경우
    if params.genre_id:
        matched = next((g for g in genres if g['id'] == params.genre_id), None)
        if matched:
            info['genre_id'] = matched['id']
            info['genre_name'] = matched['name']
            file_path = save_song_info(video_id, info)
            
            return json.dumps({
                "status": "success",
                "agent": "3번 - 장르 감별사",
                "message": f"장르를 직접 지정했습니다: {matched['name']}",
                "genre": {
                    "id": matched['id'],
                    "code": matched['code'],
                    "name": matched['name']
                },
                "file_path": file_path,
                "next_step": "4번 에이전트(agent_answer_collector)를 호출하여 정답을 수집하세요."
            }, ensure_ascii=False, indent=2)

    # 2. 자동 매핑 시도
    matched = find_best_matching_genre(params.detected_genre, genres)

    if matched:
        info['genre_id'] = matched['id']
        info['genre_name'] = matched['name']
        file_path = save_song_info(video_id, info)

        return json.dumps({
            "status": "success",
            "agent": "3번 - 장르 감별사",
            "message": f"기존 장르와 매핑되었습니다: '{params.detected_genre}' → '{matched['name']}'",
            "detected_genre": params.detected_genre,
            "matched_genre": {
                "id": matched['id'],
                "code": matched['code'],
                "name": matched['name']
            },
            "file_path": file_path,
            "next_step": "4번 에이전트(agent_answer_collector)를 호출하여 정답을 수집하세요."
        }, ensure_ascii=False, indent=2)

    # 3. 매핑 실패 - 새 장르 추가 필요
    if params.new_genre_code and params.new_genre_name:
        try:
            ph = get_placeholder()
            with get_db_connection() as conn:
                cursor = conn.cursor()

                # 최대 display_order 조회
                cursor.execute("SELECT MAX(display_order) FROM genre")
                max_order = cursor.fetchone()[0] or 0
                new_order = max_order + 10

                # 새 장르 추가
                cursor.execute(f"""
                    INSERT INTO genre (code, name, display_order, use_yn, created_at, updated_at)
                    VALUES ({ph}, {ph}, {ph}, 'Y', NOW(), NOW())
                """, (params.new_genre_code, params.new_genre_name, new_order))

                new_genre_id = cursor.lastrowid
                conn.commit()

                info['genre_id'] = new_genre_id
                info['genre_name'] = params.new_genre_name
                file_path = save_song_info(video_id, info)

                return json.dumps({
                    "status": "success",
                    "agent": "3번 - 장르 감별사",
                    "message": f"새 장르를 추가했습니다: {params.new_genre_name}",
                    "new_genre": {
                        "id": new_genre_id,
                        "code": params.new_genre_code,
                        "name": params.new_genre_name,
                        "display_order": new_order
                    },
                    "file_path": file_path,
                    "next_step": "4번 에이전트(agent_answer_collector)를 호출하여 정답을 수집하세요."
                }, ensure_ascii=False, indent=2)
                
        except Exception as e:
            return json.dumps({
                "status": "error",
                "message": f"새 장르 추가 중 오류 발생: {str(e)}",
                "youtube_video_id": video_id
            }, ensure_ascii=False, indent=2)
    
    # 4. 매핑 실패하고 새 장르 정보도 없는 경우
    file_path = save_song_info(video_id, info)
    
    return json.dumps({
        "status": "need_input",
        "agent": "3번 - 장르 감별사",
        "message": f"'{params.detected_genre}'와 일치하는 기존 장르를 찾지 못했습니다.",
        "detected_genre": params.detected_genre,
        "available_genres": genres,
        "options": [
            "1. genre_id를 직접 지정하여 다시 호출",
            "2. new_genre_code와 new_genre_name을 입력하여 새 장르 추가"
        ],
        "file_path": file_path
    }, ensure_ascii=False, indent=2)


# ========== Agent 4: 정답 수집가 ==========
@mcp.tool(
    name="agent_answer_collector",
    annotations={
        "title": "4번 에이전트: 정답 수집",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": True
    }
)
async def agent_answer_collector(params: AnswerCollectorInput) -> str:
    """
    [4번 인원] 사람들이 해당 곡을 부르는 다양한 이름(정답)을 수집합니다.

    웹 검색으로 해당 곡의 별칭, 줄임말, 영어/한글 표기 등을 파악하여
    정답 목록을 저장합니다. 첫 번째 정답이 대표 정답(is_primary=1)입니다.

    예시:
    - "봄날" → ["봄날", "Spring Day", "봄날 BTS"]
    - "Dynamite" → ["Dynamite", "다이너마이트", "다나마"]
    - "피 땀 눈물" → ["피땀눈물", "피 땀 눈물", "Blood Sweat & Tears", "BST"]

    Args:
        params: 정답 수집 정보
            - youtube_video_id: YouTube 비디오 ID
            - answers: 정답 목록 (첫 번째가 대표 정답)

    Returns:
        저장된 정답 목록
    """
    video_id = params.youtube_video_id

    # 기존 정보 로드
    info = load_song_info(video_id)
    if not info:
        return json.dumps({
            "status": "error",
            "message": "노래 정보 파일이 없습니다. 먼저 이전 에이전트들을 실행하세요.",
            "youtube_video_id": video_id
        }, ensure_ascii=False, indent=2)

    # 정답 목록 저장
    info['answers'] = params.answers

    # 파일 업데이트
    file_path = save_song_info(video_id, info)

    return json.dumps({
        "status": "success",
        "agent": "4번 - 정답 수집가",
        "message": f"정답 {len(params.answers)}개를 수집했습니다.",
        "answers": params.answers,
        "primary_answer": params.answers[0] if params.answers else None,
        "file_path": file_path,
        "next_step": "5번 에이전트(agent_sql_builder)를 호출하여 INSERT문을 생성하세요."
    }, ensure_ascii=False, indent=2)


# ========== Agent 5: SQL 빌더 ==========
@mcp.tool(
    name="agent_sql_builder",
    annotations={
        "title": "5번 에이전트: SQL INSERT문 생성",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False
    }
)
async def agent_sql_builder(params: SqlBuildInput) -> str:
    """
    [5번 인원] 수집된 정보를 취합하여 DB INSERT문을 작성합니다.

    .txt 파일에서 노래 정보와 시작 시간, 장르, 정답 목록을 읽어와
    song 테이블과 song_answer 테이블에 삽입할 INSERT 문을 생성합니다.

    Args:
        params: YouTube 비디오 ID

    Returns:
        생성된 INSERT SQL문
    """
    video_id = params.youtube_video_id
    
    # 기존 정보 로드
    info = load_song_info(video_id)
    if not info:
        return json.dumps({
            "status": "error",
            "message": "노래 정보 파일이 없습니다. 먼저 이전 에이전트들을 실행하세요.",
            "youtube_video_id": video_id
        }, ensure_ascii=False, indent=2)
    
    # genre_id 처리
    genre_id_val = info.get('genre_id')
    genre_id_sql = 'NULL' if genre_id_val is None else str(genre_id_val)
    
    # release_year 처리
    release_year_val = info.get('release_year')
    release_year_sql = 'NULL' if release_year_val is None else str(release_year_val)
    
    # INSERT문 생성
    sql = f"""INSERT INTO song (title, artist, start_time, play_duration, genre_id, release_year, is_solo, use_yn, youtube_video_id)
VALUES (
    '{info.get('title', '').replace("'", "''")}',
    '{info.get('artist', '').replace("'", "''")}',
    {info.get('start_time', 0)},
    {info.get('play_duration', 0)},
    {genre_id_sql},
    {release_year_sql},
    {1 if info.get('is_solo', False) else 0},
    '{info.get('use_yn', 'Y')}',
    '{video_id}'
);"""
    
    # SQL 저장
    info['sql'] = sql
    file_path = save_song_info(video_id, info)
    
    return json.dumps({
        "status": "success",
        "agent": "5번 - SQL 빌더",
        "message": "INSERT문을 생성했습니다.",
        "sql": sql,
        "data_summary": {
            "title": info.get('title'),
            "artist": info.get('artist'),
            "start_time": info.get('start_time'),
            "play_duration": info.get('play_duration'),
            "genre_id": info.get('genre_id'),
            "genre_name": info.get('genre_name'),
            "release_year": info.get('release_year'),
            "is_solo": info.get('is_solo'),
            "answers": info.get('answers', [])
        },
        "file_path": file_path,
        "next_step": "6번 에이전트(agent_verifier)를 호출하여 정보를 검수하세요."
    }, ensure_ascii=False, indent=2)




# ========== Agent 5.5: 데이터 검증자 (보고자) ==========
@mcp.tool(
    name="agent_data_validator",
    annotations={
        "title": "5.5번 에이전트: 데이터 검증 (빈값/NULL 체크)",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False
    }
)
async def agent_data_validator(params: DataValidatorInput) -> str:
    """
    [5.5번 인원] INSERT 전 데이터 검증자 - NULL/빈값 체크 후 보고합니다.

    수집된 노래 정보에 NULL이나 빈 값이 있는지 확인하고,
    문제가 발견되면 사용자에게 보고합니다.
    
    필수 필드:
    - youtube_video_id: YouTube 비디오 ID (11자리)
    - title: 노래 제목
    - artist: 아티스트명
    - play_duration: 재생시간 (1 이상)
    - genre_id: 장르 ID
    - answers: 정답 목록 (최소 1개)

    Args:
        params: YouTube 비디오 ID

    Returns:
        검증 결과 (문제 있으면 상세 내역, 없으면 통과)
    """
    video_id = params.youtube_video_id
    
    # 기존 정보 로드
    info = load_song_info(video_id)
    if not info:
        return json.dumps({
            "status": "error",
            "agent": "5.5번 - 데이터 검증자",
            "message": "노래 정보 파일이 없습니다. 먼저 이전 에이전트들을 실행하세요.",
            "youtube_video_id": video_id
        }, ensure_ascii=False, indent=2)
    
    # 검증할 필드 목록
    issues = []
    warnings = []
    
    # 필수 필드 검증
    required_fields = {
        'youtube_video_id': 'YouTube 비디오 ID',
        'title': '노래 제목',
        'artist': '아티스트명',
        'play_duration': '재생시간',
        'genre_id': '장르 ID',
    }
    
    for field, field_name in required_fields.items():
        value = info.get(field)
        if value is None:
            issues.append(f"[NULL] {field_name}({field})이(가) NULL입니다")
        elif isinstance(value, str) and value.strip() == '':
            issues.append(f"[빈값] {field_name}({field})이(가) 빈 문자열입니다")
        elif field == 'play_duration' and (not isinstance(value, int) or value <= 0):
            issues.append(f"[잘못된 값] {field_name}({field})이(가) 유효하지 않습니다: {value}")
    
    # answers 필드 검증 (최소 1개 필요)
    answers = info.get('answers', [])
    if not answers:
        issues.append("[빈값] 정답 목록(answers)이 비어있습니다. 최소 1개의 정답이 필요합니다")
    else:
        empty_answers = [i for i, ans in enumerate(answers) if not ans or not ans.strip()]
        if empty_answers:
            issues.append(f"[빈값] 정답 목록 중 빈 값이 있습니다 (인덱스: {empty_answers})")
    
    # 선택 필드 경고 (NULL이어도 괜찮지만 알림)
    optional_fields = {
        'release_year': '발매연도',
        'start_time': '시작시간',
    }
    
    for field, field_name in optional_fields.items():
        value = info.get(field)
        if value is None:
            warnings.append(f"[선택] {field_name}({field})이(가) 설정되지 않음 (NULL 허용)")
    
    # 결과 생성
    if issues:
        return json.dumps({
            "status": "FAIL",
            "agent": "5.5번 - 데이터 검증자",
            "message": "⚠️ 데이터에 문제가 발견되었습니다! INSERT 전에 수정이 필요합니다.",
            "youtube_video_id": video_id,
            "issues_count": len(issues),
            "issues": issues,
            "warnings": warnings if warnings else None,
            "current_data": {
                "youtube_video_id": info.get('youtube_video_id'),
                "title": info.get('title'),
                "artist": info.get('artist'),
                "play_duration": info.get('play_duration'),
                "genre_id": info.get('genre_id'),
                "answers": info.get('answers', []),
                "release_year": info.get('release_year'),
                "start_time": info.get('start_time'),
            },
            "action_required": "문제가 있는 필드를 확인하고 해당 에이전트를 다시 실행하세요.",
            "next_step": "문제 해결 후 다시 agent_data_validator를 호출하세요."
        }, ensure_ascii=False, indent=2)
    
    return json.dumps({
        "status": "PASS",
        "agent": "5.5번 - 데이터 검증자",
        "message": "✅ 모든 필수 데이터가 정상입니다. INSERT 진행 가능합니다.",
        "youtube_video_id": video_id,
        "validated_data": {
            "youtube_video_id": info.get('youtube_video_id'),
            "title": info.get('title'),
            "artist": info.get('artist'),
            "play_duration": info.get('play_duration'),
            "genre_id": info.get('genre_id'),
            "answers_count": len(info.get('answers', [])),
            "release_year": info.get('release_year'),
            "start_time": info.get('start_time', 0),
        },
        "warnings": warnings if warnings else None,
        "next_step": "6번 에이전트(agent_verifier)를 호출하여 정보를 검수하세요."
    }, ensure_ascii=False, indent=2)


# ========== Agent 6: 검증자 ==========
@mcp.tool(
    name="agent_verifier",
    annotations={
        "title": "6번 에이전트: 정보 검수",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": True
    }
)
async def agent_verifier(params: VerifyInput) -> str:
    """
    [6번 인원] 수집된 정보와 노래 시작 시간, 정답 목록이 정확한지 검수합니다.

    웹 검색과 실제 YouTube 영상 확인을 통해 정보가 정확한지 검증하고
    검증 결과를 기록합니다.

    Args:
        params: 검증 정보
            - youtube_video_id: YouTube 비디오 ID
            - actual_start_time: 실제 확인된 시작 시간(초) - 수정 필요 시
            - is_correct: 정보가 정확한지 여부
            - note: 검증 메모

    Returns:
        검증 결과와 최종 정보
    """
    video_id = params.youtube_video_id
    
    # 기존 정보 로드
    info = load_song_info(video_id)
    if not info:
        return json.dumps({
            "status": "error",
            "message": "노래 정보 파일이 없습니다. 먼저 이전 에이전트들을 실행하세요.",
            "youtube_video_id": video_id
        }, ensure_ascii=False, indent=2)
    
    # 검증 정보 업데이트
    if params.actual_start_time is not None:
        info['start_time'] = params.actual_start_time
    
    info['verified'] = params.is_correct
    info['verification_note'] = params.note or ('검증 완료' if params.is_correct else '검증 실패')
    
    # genre_id 처리
    genre_id_val = info.get('genre_id')
    genre_id_sql = 'NULL' if genre_id_val is None else str(genre_id_val)
    
    # release_year 처리
    release_year_val = info.get('release_year')
    release_year_sql = 'NULL' if release_year_val is None else str(release_year_val)
    
    # SQL 재생성 (시작 시간이 변경되었을 수 있음)
    sql = f"""INSERT INTO song (title, artist, start_time, play_duration, genre_id, release_year, is_solo, use_yn, youtube_video_id)
VALUES (
    '{info.get('title', '').replace("'", "''")}',
    '{info.get('artist', '').replace("'", "''")}',
    {info.get('start_time', 0)},
    {info.get('play_duration', 0)},
    {genre_id_sql},
    {release_year_sql},
    {1 if info.get('is_solo', False) else 0},
    '{info.get('use_yn', 'Y')}',
    '{video_id}'
);"""
    info['sql'] = sql
    
    # 파일 업데이트
    file_path = save_song_info(video_id, info)
    
    result = {
        "status": "success",
        "agent": "6번 - 검증자",
        "message": "정보 검수를 완료했습니다.",
        "verification": {
            "is_correct": params.is_correct,
            "actual_start_time": info['start_time'],
            "note": info['verification_note']
        },
        "final_info": {
            "youtube_video_id": video_id,
            "youtube_url": f"https://www.youtube.com/watch?v={video_id}",
            "title": info.get('title'),
            "artist": info.get('artist'),
            "start_time": info['start_time'],
            "play_duration": info.get('play_duration'),
            "genre_id": info.get('genre_id'),
            "genre_name": info.get('genre_name'),
            "release_year": info.get('release_year'),
            "is_solo": info.get('is_solo'),
            "answers": info.get('answers', []),
            "verified": info['verified']
        },
        "sql": sql,
        "file_path": file_path
    }

    if params.is_correct:
        result["next_step"] = "7번 에이전트(agent_db_executor)를 호출하여 DB에 저장하세요."
    else:
        result["next_step"] = "정보가 정확하지 않습니다. 이전 에이전트를 다시 실행하여 정보를 수정하세요."

    return json.dumps(result, ensure_ascii=False, indent=2)


# ========== Agent 7: DB 실행자 ==========
@mcp.tool(
    name="agent_db_executor",
    annotations={
        "title": "7번 에이전트: DB INSERT 실행",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False
    }
)
async def agent_db_executor(params: ExecuteInput) -> str:
    """
    [7번 인원 - 마지막] DB에 song과 song_answer를 INSERT합니다.

    검증이 완료된 정보를 실제 데이터베이스에 저장합니다.
    song 테이블과 song_answer 테이블에 함께 저장합니다.
    force=True로 설정하면 검증 없이도 저장할 수 있습니다.

    Args:
        params:
            - youtube_video_id: YouTube 비디오 ID
            - force: 검증 없이 강제 실행 여부

    Returns:
        INSERT 실행 결과
    """
    video_id = params.youtube_video_id

    # 기존 정보 로드
    info = load_song_info(video_id)
    if not info:
        return json.dumps({
            "status": "error",
            "message": "노래 정보 파일이 없습니다. 먼저 이전 에이전트들을 실행하세요.",
            "youtube_video_id": video_id
        }, ensure_ascii=False, indent=2)

    # 검증 확인
    if not info.get('verified', False) and not params.force:
        return json.dumps({
            "status": "error",
            "message": "검증되지 않은 정보입니다. agent_verifier로 검증을 완료하거나 force=True로 강제 실행하세요.",
            "youtube_video_id": video_id,
            "verified": info.get('verified', False)
        }, ensure_ascii=False, indent=2)

    # DB INSERT 실행
    try:
        ph = get_placeholder()

        with get_db_connection() as conn:
            cursor = conn.cursor()

            # 중복 체크
            cursor.execute(f"SELECT id FROM song WHERE youtube_video_id = {ph}", (video_id,))
            existing = cursor.fetchone()

            if existing:
                song_id = existing[0]
                # UPDATE로 변경
                cursor.execute(f"""
                    UPDATE song
                    SET title={ph}, artist={ph}, start_time={ph}, play_duration={ph},
                        genre_id={ph}, release_year={ph}, is_solo={ph}, use_yn={ph}
                    WHERE youtube_video_id={ph}
                """, (
                    info.get('title', ''),
                    info.get('artist', ''),
                    info.get('start_time', 0),
                    info.get('play_duration', 0),
                    info.get('genre_id'),
                    info.get('release_year'),
                    1 if info.get('is_solo', False) else 0,
                    info.get('use_yn', 'Y'),
                    video_id
                ))
                action = "UPDATE"

                # 기존 정답 삭제 후 재등록
                cursor.execute(f"DELETE FROM song_answer WHERE song_id = {ph}", (song_id,))
            else:
                # INSERT with created_at
                cursor.execute(f"""
                    INSERT INTO song (title, artist, start_time, play_duration, genre_id, release_year, is_solo, use_yn, youtube_video_id, created_at)
                    VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph}, NOW())
                """, (
                    info.get('title', ''),
                    info.get('artist', ''),
                    info.get('start_time', 0),
                    info.get('play_duration', 0),
                    info.get('genre_id'),
                    info.get('release_year'),
                    1 if info.get('is_solo', False) else 0,
                    info.get('use_yn', 'Y'),
                    video_id
                ))
                song_id = cursor.lastrowid
                action = "INSERT"

            # song_answer INSERT
            answers = info.get('answers', [])
            answers_inserted = 0
            for idx, answer in enumerate(answers):
                is_primary = 1 if idx == 0 else 0
                cursor.execute(f"""
                    INSERT INTO song_answer (song_id, answer, is_primary, created_at)
                    VALUES ({ph}, {ph}, {ph}, NOW())
                """, (song_id, answer, is_primary))
                answers_inserted += 1

            conn.commit()

            # 저장된 레코드 조회
            cursor.execute(f"SELECT * FROM song WHERE youtube_video_id = {ph}", (video_id,))
            row = cursor.fetchone()
            columns = [desc[0] for desc in cursor.description]
            saved_record = dict(zip(columns, row))

            # 저장된 정답 조회
            cursor.execute(f"SELECT answer, is_primary FROM song_answer WHERE song_id = {ph}", (song_id,))
            saved_answers = [{"answer": r[0], "is_primary": bool(r[1])} for r in cursor.fetchall()]

        return json.dumps({
            "status": "success",
            "agent": "7번 - DB 실행자 (마지막)",
            "message": f"DB에 성공적으로 {action}되었습니다! (DB: {DB_TYPE})",
            "action": action,
            "db_type": DB_TYPE,
            "saved_record": saved_record,
            "saved_answers": saved_answers,
            "answers_count": answers_inserted,
            "workflow_complete": True,
            "next_step": "'다음곡 주세요'라고 말씀해 주세요."
        }, ensure_ascii=False, indent=2, default=str)

    except Exception as e:
        return json.dumps({
            "status": "error",
            "message": f"DB 작업 중 오류 발생: {str(e)}",
            "youtube_video_id": video_id
        }, ensure_ascii=False, indent=2)


# ========== 메인 등록 워크플로우 ==========
@mcp.tool(
    name="register_song",
    annotations={
        "title": "노래 등록 워크플로우",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False
    }
)
async def register_song(params: RegisterInput) -> str:
    """
    노래 등록 워크플로우를 시작합니다.
    
    '등록해줘' 명령어와 함께 검색어를 입력하면 
    다중 에이전트 워크플로우를 안내합니다.
    
    Args:
        params: 검색어와 명령어 (예: query="아이유-밤편지", command="등록해줘")
        
    Returns:
        워크플로우 시작 안내
    """
    query = params.query
    command = params.command
    
    if '등록' not in command:
        return json.dumps({
            "status": "info",
            "message": "'등록해줘'라고 말씀해주시면 워크플로우를 시작합니다.",
            "query": query
        }, ensure_ascii=False, indent=2)
    
    # 장르 목록 조회
    genres = get_all_genres()
    
    return json.dumps({
        "status": "workflow_start",
        "message": "노래 등록 워크플로우를 시작합니다.",
        "query": query,
        "workflow": [
            {
                "step": 1,
                "agent": "agent_song_info",
                "description": "노래 정보 저장",
                "action": "youtube_video_id, title, artist, play_duration(전체재생시간), release_year, is_solo 입력"
            },
            {
                "step": 2,
                "agent": "agent_ad_analyzer",
                "description": "노래 시작 시간 설정",
                "action": "인트로 길이 확인 후 입력 (기본값: 0)"
            },
            {
                "step": 3,
                "agent": "agent_genre_classifier",
                "description": "장르 분류",
                "action": "장르 ID 직접 지정 또는 자동 매핑"
            },
            {
                "step": 4,
                "agent": "agent_answer_collector",
                "description": "정답 수집",
                "action": "곡의 다양한 이름/별칭 수집 (첫 번째가 대표 정답)"
            },
            {
                "step": 5,
                "agent": "agent_sql_builder",
                "description": "SQL INSERT문 생성",
                "action": "자동으로 SQL 생성"
            },
            {
                "step": 6,
                "agent": "agent_verifier",
                "description": "정보 검증",
                "action": "정보 확인 후 승인"
            },
            {
                "step": 7,
                "agent": "agent_db_executor",
                "description": "DB에 저장 (song + song_answer)",
                "action": "최종 저장 후 '다음곡 주세요' 안내"
            }
        ],
        "available_genres": genres,
        "next_step": "사용자에게 YouTube URL을 요청하세요."
    }, ensure_ascii=False, indent=2)


# ========== 아티스트 워크플로우 도구 ==========
# 현재 진행 중인 아티스트 세션 (메모리에 저장)
_current_artist_session: Dict[str, Any] = {}

# 아티스트 이름 매핑 (영어 <-> 한글)
ARTIST_NAME_ALIASES: Dict[str, List[str]] = {
    # K-POP 아이돌 그룹
    "BTS": ["방탄소년단", "Bangtan Boys"],
    "방탄소년단": ["BTS", "Bangtan Boys"],
    "BLACKPINK": ["블랙핑크"],
    "블랙핑크": ["BLACKPINK"],
    "TWICE": ["트와이스"],
    "트와이스": ["TWICE"],
    "APINK": ["에이핑크", "A Pink"],
    "에이핑크": ["APINK", "A Pink"],
    "SNSD": ["소녀시대", "Girls' Generation"],
    "소녀시대": ["SNSD", "Girls' Generation"],
    "EXO": ["엑소"],
    "엑소": ["EXO"],
    "NCT": ["엔시티"],
    "엔시티": ["NCT"],
    "SEVENTEEN": ["세븐틴"],
    "세븐틴": ["SEVENTEEN"],
    "STRAY KIDS": ["스트레이키즈", "SKZ"],
    "스트레이키즈": ["STRAY KIDS", "SKZ"],
    "ITZY": ["있지"],
    "있지": ["ITZY"],
    "AESPA": ["에스파"],
    "에스파": ["AESPA"],
    "RED VELVET": ["레드벨벳"],
    "레드벨벳": ["RED VELVET"],
    "NEWJEANS": ["뉴진스"],
    "뉴진스": ["NEWJEANS"],
    "IVE": ["아이브"],
    "아이브": ["IVE"],
    "LE SSERAFIM": ["르세라핌"],
    "르세라핌": ["LE SSERAFIM"],
    "(G)I-DLE": ["여자아이들", "GIDLE"],
    "여자아이들": ["(G)I-DLE", "GIDLE"],
    "MAMAMOO": ["마마무"],
    "마마무": ["MAMAMOO"],
    "BIGBANG": ["빅뱅"],
    "빅뱅": ["BIGBANG"],
    "2NE1": ["투애니원"],
    "투애니원": ["2NE1"],
    "SUPER JUNIOR": ["슈퍼주니어", "SJ", "슈주"],
    "슈퍼주니어": ["SUPER JUNIOR", "SJ", "슈주"],
    "SHINEE": ["샤이니"],
    "샤이니": ["SHINEE"],
    "GOT7": ["갓세븐"],
    "갓세븐": ["GOT7"],
    "MONSTA X": ["몬스타엑스"],
    "몬스타엑스": ["MONSTA X"],
    "ATEEZ": ["에이티즈"],
    "에이티즈": ["ATEEZ"],
    "TXT": ["투모로우바이투게더", "TOMORROW X TOGETHER"],
    "투모로우바이투게더": ["TXT", "TOMORROW X TOGETHER"],
    "ENHYPEN": ["엔하이픈"],
    "엔하이픈": ["ENHYPEN"],
    "TREASURE": ["트레저"],
    "트레저": ["TREASURE"],
    "KARA": ["카라"],
    "카라": ["KARA"],
    "WONDER GIRLS": ["원더걸스"],
    "원더걸스": ["WONDER GIRLS"],
    "SISTAR": ["씨스타"],
    "씨스타": ["SISTAR"],
    "T-ARA": ["티아라"],
    "티아라": ["T-ARA"],
    "MISS A": ["미쓰에이"],
    "미쓰에이": ["MISS A"],
    "4MINUTE": ["포미닛"],
    "포미닛": ["4MINUTE"],
    "INFINITE": ["인피니트"],
    "인피니트": ["INFINITE"],
    "BEAST": ["비스트", "HIGHLIGHT", "하이라이트"],
    "비스트": ["BEAST", "HIGHLIGHT", "하이라이트"],
    "HIGHLIGHT": ["하이라이트", "BEAST", "비스트"],
    "하이라이트": ["HIGHLIGHT", "BEAST", "비스트"],
    "BTOB": ["비투비"],
    "비투비": ["BTOB"],
    "VIXX": ["빅스"],
    "빅스": ["VIXX"],
    "WINNER": ["위너"],
    "위너": ["WINNER"],
    "IKON": ["아이콘"],
    "아이콘": ["IKON"],
    "ASTRO": ["아스트로"],
    "아스트로": ["ASTRO"],
    "THE BOYZ": ["더보이즈"],
    "더보이즈": ["THE BOYZ"],
    "FROMIS_9": ["프로미스나인"],
    "프로미스나인": ["FROMIS_9"],
    "LOONA": ["이달의소녀", "이달의 소녀"],
    "이달의소녀": ["LOONA"],
    "WJSN": ["우주소녀", "Cosmic Girls"],
    "우주소녀": ["WJSN", "Cosmic Girls"],
    "OH MY GIRL": ["오마이걸"],
    "오마이걸": ["OH MY GIRL"],
    "BRAVE GIRLS": ["브레이브걸스"],
    "브레이브걸스": ["BRAVE GIRLS"],

    # 솔로 아티스트
    "IU": ["아이유"],
    "아이유": ["IU"],
    "TAEYEON": ["태연"],
    "태연": ["TAEYEON"],
    "BAEKHYUN": ["백현"],
    "백현": ["BAEKHYUN"],
    "ZICO": ["지코"],
    "지코": ["ZICO"],
    "DEAN": ["딘"],
    "딘": ["DEAN"],
    "CRUSH": ["크러쉬"],
    "크러쉬": ["CRUSH"],
    "HEIZE": ["헤이즈"],
    "헤이즈": ["HEIZE"],
    "CHUNGHA": ["청하"],
    "청하": ["CHUNGHA"],
    "SUNMI": ["선미"],
    "선미": ["SUNMI"],
    "HYUNA": ["현아"],
    "현아": ["HYUNA"],
    "PSY": ["싸이"],
    "싸이": ["PSY"],
    "RAIN": ["비", "정지훈"],
    "비": ["RAIN", "정지훈"],
    "G-DRAGON": ["지드래곤", "GD", "권지용"],
    "지드래곤": ["G-DRAGON", "GD", "권지용"],
    "TAEYANG": ["태양", "동영배"],
    "태양": ["TAEYANG", "동영배"],
    "ZION.T": ["자이언티"],
    "자이언티": ["ZION.T"],
    "LOCO": ["로꼬"],
    "로꼬": ["LOCO"],
    "GRAY": ["그레이"],
    "그레이": ["GRAY"],
    "JAY PARK": ["박재범"],
    "박재범": ["JAY PARK"],
    "HYOLYN": ["효린"],
    "효린": ["HYOLYN"],
    "SOMI": ["전소미", "소미"],
    "전소미": ["SOMI", "소미"],
    "JESSI": ["제시"],
    "제시": ["JESSI"],
    "HWASA": ["화사"],
    "화사": ["HWASA"],
}


def get_artist_aliases(artist: str) -> List[str]:
    """아티스트의 모든 이름 변형 반환 (영어/한글)"""
    aliases = [artist]

    # 대소문자 무시 매칭
    artist_upper = artist.upper().strip()

    for key, values in ARTIST_NAME_ALIASES.items():
        if key.upper() == artist_upper:
            aliases.extend(values)
            break
        # 값 목록에서도 검색
        for val in values:
            if val.upper() == artist_upper:
                aliases.append(key)
                aliases.extend([v for v in values if v.upper() != artist_upper])
                break

    # 중복 제거
    return list(set(aliases))


def get_artist_songs_from_db(artist: str) -> List[str]:
    """DB에서 해당 아티스트의 등록된 곡 제목 목록 조회 (영어/한글 변형 모두 검색)"""
    try:
        # 아티스트의 모든 이름 변형 가져오기
        aliases = get_artist_aliases(artist)

        ph = get_placeholder()
        all_titles = set()

        with get_db_connection() as conn:
            cursor = conn.cursor()

            # 각 이름 변형으로 검색
            for alias in aliases:
                cursor.execute(f"""
                    SELECT title FROM song
                    WHERE LOWER(artist) LIKE LOWER({ph})
                    AND use_yn = 'Y'
                """, (f'%{alias}%',))
                rows = cursor.fetchall()
                for row in rows:
                    all_titles.add(row[0])

        return list(all_titles)
    except Exception as e:
        print(f"DB 조회 오류: {e}")
        return []


def normalize_song_title(title: str) -> str:
    """곡 제목 정규화 (비교용)"""
    # 소문자 변환, 특수문자 제거, 공백 정규화
    normalized = title.lower()
    normalized = re.sub(r'[^\w\s가-힣]', '', normalized)
    normalized = re.sub(r'\s+', ' ', normalized).strip()
    return normalized


def find_unregistered_songs(representative_songs: List[str], registered_songs: List[str]) -> List[str]:
    """미등록 곡 목록 찾기"""
    # 등록된 곡들 정규화
    registered_normalized = {normalize_song_title(s) for s in registered_songs}

    unregistered = []
    for song in representative_songs:
        song_normalized = normalize_song_title(song)
        # 정규화된 제목이 등록된 곡에 없으면 미등록
        if song_normalized not in registered_normalized:
            # 부분 매칭도 확인
            is_registered = False
            for reg in registered_normalized:
                if song_normalized in reg or reg in song_normalized:
                    is_registered = True
                    break
            if not is_registered:
                unregistered.append(song)

    return unregistered


@mcp.tool(
    name="start_artist_workflow",
    annotations={
        "title": "아티스트 대표곡 등록 워크플로우 시작",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": True
    }
)
async def start_artist_workflow(params: ArtistWorkflowInput) -> str:
    """
    아티스트의 대표곡 등록 워크플로우를 시작합니다.

    웹 검색으로 파악한 대표곡 목록(13~16곡)과 DB에 등록된 곡을 비교하여
    아직 등록되지 않은 곡들의 목록을 제시합니다.

    Args:
        params: 아티스트 정보
            - artist: 아티스트명
            - representative_songs: 웹 검색으로 파악한 대표곡 목록 (13~16곡)

    Returns:
        미등록 곡 목록과 다음 단계 안내
    """
    global _current_artist_session

    artist = params.artist
    representative_songs = params.representative_songs

    # 아티스트 별칭 조회 (영어/한글 변형)
    artist_aliases = get_artist_aliases(artist)

    # DB에서 등록된 곡 조회 (모든 별칭으로 검색)
    registered_songs = get_artist_songs_from_db(artist)

    # 미등록 곡 찾기
    unregistered_songs = find_unregistered_songs(representative_songs, registered_songs)

    # 세션 저장
    _current_artist_session = {
        "artist": artist,
        "artist_aliases": artist_aliases,
        "representative_songs": representative_songs,
        "registered_songs": registered_songs,
        "unregistered_songs": unregistered_songs,
        "current_index": 0,
        "completed_songs": []
    }

    # 장르 목록 조회
    genres = get_all_genres()

    if not unregistered_songs:
        return json.dumps({
            "status": "all_registered",
            "message": f"{artist}의 모든 대표곡이 이미 등록되어 있습니다.",
            "artist": artist,
            "artist_aliases": artist_aliases,
            "registered_count": len(registered_songs),
            "registered_songs": registered_songs,
            "next_step": "'다음 아티스트 주세요'라고 말씀해 주세요."
        }, ensure_ascii=False, indent=2)

    return json.dumps({
        "status": "workflow_started",
        "message": f"{artist}의 대표곡 등록 워크플로우를 시작합니다.",
        "artist": artist,
        "artist_aliases": artist_aliases,
        "searched_names": f"DB 검색에 사용된 이름: {', '.join(artist_aliases)}",
        "total_representative": len(representative_songs),
        "already_registered": len(registered_songs),
        "to_register": len(unregistered_songs),
        "registered_songs": registered_songs,
        "unregistered_songs": unregistered_songs,
        "available_genres": genres,
        "next_step": "'다음곡 주세요'라고 말씀하시면 첫 번째 곡의 lyrics YouTube URL을 요청드립니다.",
        "first_song": unregistered_songs[0] if unregistered_songs else None
    }, ensure_ascii=False, indent=2)


@mcp.tool(
    name="request_next_song",
    annotations={
        "title": "다음 곡 요청",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False
    }
)
async def request_next_song() -> str:
    """
    다음 곡의 lyrics YouTube URL을 요청합니다.

    사용자가 '다음곡 주세요'라고 하면 이 도구를 호출하여
    현재 등록할 곡 정보를 안내합니다.

    Returns:
        현재 등록할 곡 정보와 URL 요청 안내
    """
    global _current_artist_session

    if not _current_artist_session:
        return json.dumps({
            "status": "no_session",
            "message": "진행 중인 아티스트 워크플로우가 없습니다.",
            "next_step": "먼저 start_artist_workflow를 호출하여 아티스트와 대표곡 목록을 입력하세요."
        }, ensure_ascii=False, indent=2)

    artist = _current_artist_session.get("artist", "")
    unregistered = _current_artist_session.get("unregistered_songs", [])
    current_idx = _current_artist_session.get("current_index", 0)
    completed = _current_artist_session.get("completed_songs", [])

    if current_idx >= len(unregistered):
        return json.dumps({
            "status": "all_completed",
            "message": f"{artist}의 모든 미등록 곡 등록이 완료되었습니다!",
            "artist": artist,
            "completed_songs": completed,
            "total_completed": len(completed),
            "next_step": "'다음 아티스트 주세요'라고 말씀해 주세요."
        }, ensure_ascii=False, indent=2)

    current_song = unregistered[current_idx]
    remaining = len(unregistered) - current_idx

    return json.dumps({
        "status": "awaiting_url",
        "message": f"다음 곡의 lyrics YouTube URL을 제공해 주세요.",
        "artist": artist,
        "current_song": current_song,
        "progress": f"{current_idx + 1}/{len(unregistered)}",
        "remaining": remaining,
        "completed_so_far": completed,
        "instruction": f"'{artist} - {current_song}'의 lyrics 버전 YouTube URL을 붙여넣어 주세요."
    }, ensure_ascii=False, indent=2)


@mcp.tool(
    name="process_song_url",
    annotations={
        "title": "곡 URL 처리",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": True
    }
)
async def process_song_url(params: NextSongInput) -> str:
    """
    사용자가 제공한 lyrics YouTube URL을 처리합니다.

    URL에서 video_id를 추출하고 현재 곡 정보와 함께
    노래 등록 파이프라인을 시작할 준비를 합니다.

    Args:
        params: YouTube URL 정보
            - youtube_url: lyrics YouTube 영상 URL

    Returns:
        추출된 정보와 다음 단계 안내
    """
    global _current_artist_session

    if not _current_artist_session:
        return json.dumps({
            "status": "no_session",
            "message": "진행 중인 아티스트 워크플로우가 없습니다.",
            "next_step": "먼저 start_artist_workflow를 호출하세요."
        }, ensure_ascii=False, indent=2)

    # URL에서 video_id 추출
    video_id = extract_video_id(params.youtube_url)

    if not video_id:
        return json.dumps({
            "status": "error",
            "message": "유효한 YouTube URL이 아닙니다.",
            "provided_url": params.youtube_url,
            "instruction": "올바른 YouTube URL을 제공해 주세요. (예: https://www.youtube.com/watch?v=xxxxx)"
        }, ensure_ascii=False, indent=2)

    artist = _current_artist_session.get("artist", "")
    unregistered = _current_artist_session.get("unregistered_songs", [])
    current_idx = _current_artist_session.get("current_index", 0)

    if current_idx >= len(unregistered):
        return json.dumps({
            "status": "error",
            "message": "등록할 곡이 없습니다.",
            "next_step": "'다음 아티스트 주세요'라고 말씀해 주세요."
        }, ensure_ascii=False, indent=2)

    current_song = unregistered[current_idx]

    # 장르 목록
    genres = get_all_genres()

    return json.dumps({
        "status": "ready_to_register",
        "message": "URL이 확인되었습니다. 노래 등록 파이프라인을 시작합니다.",
        "youtube_video_id": video_id,
        "youtube_url": f"https://www.youtube.com/watch?v={video_id}",
        "song_info": {
            "title": current_song,
            "artist": artist
        },
        "available_genres": genres,
        "pipeline": [
            {"step": 1, "agent": "agent_song_info", "description": "노래 정보 저장"},
            {"step": 2, "agent": "agent_ad_analyzer", "description": "시작 시간 설정"},
            {"step": 3, "agent": "agent_genre_classifier", "description": "장르 분류"},
            {"step": 4, "agent": "agent_answer_collector", "description": "정답 수집"},
            {"step": 5, "agent": "agent_sql_builder", "description": "SQL 생성"},
            {"step": 6, "agent": "agent_verifier", "description": "정보 검증"},
            {"step": 7, "agent": "agent_db_executor", "description": "DB 저장 (song + song_answer)"}
        ],
        "next_step": "agent_song_info를 호출하세요. (youtube_video_id, title, artist, play_duration, release_year, is_solo 입력)"
    }, ensure_ascii=False, indent=2)


@mcp.tool(
    name="mark_song_completed",
    annotations={
        "title": "곡 등록 완료 표시",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False
    }
)
async def mark_song_completed() -> str:
    """
    현재 곡의 등록이 완료되었음을 표시하고 다음 곡으로 넘어갑니다.

    agent_db_executor 실행 후 이 도구를 호출하여
    진행 상황을 업데이트합니다.

    Returns:
        완료 상태와 다음 단계 안내
    """
    global _current_artist_session

    if not _current_artist_session:
        return json.dumps({
            "status": "no_session",
            "message": "진행 중인 아티스트 워크플로우가 없습니다."
        }, ensure_ascii=False, indent=2)

    artist = _current_artist_session.get("artist", "")
    unregistered = _current_artist_session.get("unregistered_songs", [])
    current_idx = _current_artist_session.get("current_index", 0)
    completed = _current_artist_session.get("completed_songs", [])

    if current_idx < len(unregistered):
        completed_song = unregistered[current_idx]
        completed.append(completed_song)
        _current_artist_session["completed_songs"] = completed
        _current_artist_session["current_index"] = current_idx + 1

    new_idx = _current_artist_session["current_index"]
    remaining = len(unregistered) - new_idx

    if remaining <= 0:
        return json.dumps({
            "status": "artist_completed",
            "message": f"{artist}의 모든 대표곡 등록이 완료되었습니다!",
            "artist": artist,
            "total_completed": len(completed),
            "completed_songs": completed,
            "next_step": "'다음 아티스트 주세요'라고 말씀해 주세요."
        }, ensure_ascii=False, indent=2)

    next_song = unregistered[new_idx]

    return json.dumps({
        "status": "song_completed",
        "message": f"'{completed[-1]}' 등록 완료! 다음 곡으로 넘어갑니다.",
        "artist": artist,
        "completed_song": completed[-1],
        "progress": f"{new_idx}/{len(unregistered)}",
        "remaining": remaining,
        "next_song": next_song,
        "completed_so_far": completed,
        "next_step": "'다음곡 주세요'라고 말씀해 주세요."
    }, ensure_ascii=False, indent=2)


@mcp.tool(
    name="request_next_artist",
    annotations={
        "title": "다음 아티스트 요청",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": True
    }
)
async def request_next_artist() -> str:
    """
    다음 아티스트 정보를 요청합니다.

    사용자가 '다음 아티스트 주세요'라고 하면 이 도구를 호출하여
    새로운 아티스트 워크플로우를 시작할 준비를 합니다.

    Returns:
        이전 세션 요약과 다음 단계 안내
    """
    global _current_artist_session

    previous_summary = None
    if _current_artist_session:
        previous_summary = {
            "artist": _current_artist_session.get("artist", ""),
            "completed_songs": _current_artist_session.get("completed_songs", []),
            "total_completed": len(_current_artist_session.get("completed_songs", []))
        }

    # 세션 초기화
    _current_artist_session = {}

    return json.dumps({
        "status": "awaiting_artist",
        "message": "새로운 아티스트의 대표곡을 등록합니다.",
        "previous_session": previous_summary,
        "instruction": "아티스트명을 알려주세요. 웹 검색으로 해당 아티스트의 대표곡 13~16곡을 파악한 후 start_artist_workflow를 호출하세요.",
        "workflow": [
            "1. 사용자가 아티스트명 제공",
            "2. 웹 검색으로 대표곡 13~16곡 파악",
            "3. start_artist_workflow 호출 (artist, representative_songs)",
            "4. 미등록 곡 목록 확인",
            "5. '다음곡 주세요' -> URL 제공 -> 등록 파이프라인 반복"
        ]
    }, ensure_ascii=False, indent=2)


@mcp.tool(
    name="get_workflow_status",
    annotations={
        "title": "워크플로우 상태 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False
    }
)
async def get_workflow_status() -> str:
    """
    현재 진행 중인 아티스트 워크플로우의 상태를 조회합니다.

    Returns:
        현재 세션 상태 정보
    """
    global _current_artist_session

    if not _current_artist_session:
        return json.dumps({
            "status": "no_session",
            "message": "진행 중인 아티스트 워크플로우가 없습니다.",
            "next_step": "아티스트명을 알려주시면 start_artist_workflow로 새 워크플로우를 시작합니다."
        }, ensure_ascii=False, indent=2)

    artist = _current_artist_session.get("artist", "")
    unregistered = _current_artist_session.get("unregistered_songs", [])
    current_idx = _current_artist_session.get("current_index", 0)
    completed = _current_artist_session.get("completed_songs", [])
    registered = _current_artist_session.get("registered_songs", [])

    remaining = len(unregistered) - current_idx
    current_song = unregistered[current_idx] if current_idx < len(unregistered) else None

    return json.dumps({
        "status": "in_progress",
        "artist": artist,
        "progress": {
            "total_representative": len(_current_artist_session.get("representative_songs", [])),
            "already_registered": len(registered),
            "to_register": len(unregistered),
            "completed_this_session": len(completed),
            "remaining": remaining
        },
        "current_song": current_song,
        "completed_songs": completed,
        "remaining_songs": unregistered[current_idx:] if current_idx < len(unregistered) else []
    }, ensure_ascii=False, indent=2)


# ========== 배치 등록 도구 (YOLO 모드) ==========
@mcp.tool(
    name="batch_register_songs",
    annotations={
        "title": "여러 곡 한번에 등록 (YOLO 모드)",
        "readOnlyHint": False,
        "destructiveHint": False,
        "idempotentHint": False,
        "openWorldHint": False
    }
)
async def batch_register_songs(params: BatchRegisterInput) -> str:
    """
    여러 곡을 한 번에 DB에 등록합니다. (YOLO 모드 - 검증 없이 바로 등록)

    Claude가 YouTube에서 정보를 수집한 후, 이 도구를 호출하여
    여러 곡을 한 번에 등록할 수 있습니다. 최대 20곡까지 지원합니다.

    사용 예시:
    1. Claude가 Playwright로 각 YouTube 영상의 재생시간 확인
    2. 웹 검색으로 장르, 정답 목록 수집
    3. 이 도구 호출하여 한 번에 등록

    Args:
        params: 등록할 곡 목록
            - songs: BatchSongItem 배열 (최대 20곡)
              각 곡에 필요한 정보:
              - youtube_video_id: YouTube 비디오 ID
              - title: 노래 제목
              - artist: 아티스트명
              - play_duration: 재생시간(초)
              - genre_id: 장르 ID (6=K-POP 인디, 29=OST 등)
              - answers: 정답 목록 (첫 번째가 대표 정답)
              - release_year: 발매연도 (선택)
              - start_time: 시작시간 (기본값 0)
              - is_solo: 솔로 여부 (기본값 False)

    Returns:
        등록 결과 (성공/실패 곡 목록)
    """
    if len(params.songs) > 20:
        return json.dumps({
            "status": "error",
            "message": "한 번에 최대 20곡까지만 등록할 수 있습니다."
        }, ensure_ascii=False, indent=2)

    results = {
        "success": [],
        "failed": []
    }

    try:
        with get_db_connection() as conn:
            cursor = conn.cursor()
            ph = get_placeholder()

            for song in params.songs:
                try:
                    # === 데이터 검증 (NULL/빈값 체크) ===
                    validation_issues = []
                    
                    # youtube_video_id 검증
                    if not song.youtube_video_id or not song.youtube_video_id.strip():
                        validation_issues.append("youtube_video_id가 비어있음")
                    
                    # title 검증
                    if not song.title or not song.title.strip():
                        validation_issues.append("title(제목)이 비어있음")
                    
                    # artist 검증
                    if not song.artist or not song.artist.strip():
                        validation_issues.append("artist(아티스트)가 비어있음")
                    
                    # play_duration 검증
                    if not song.play_duration or song.play_duration <= 0:
                        validation_issues.append(f"play_duration이 유효하지 않음: {song.play_duration}")
                    
                    # genre_id 검증
                    if not song.genre_id:
                        validation_issues.append("genre_id가 비어있음")
                    
                    # answers 검증
                    if not song.answers or len(song.answers) == 0:
                        validation_issues.append("answers(정답 목록)이 비어있음")
                    else:
                        empty_answers = [i for i, ans in enumerate(song.answers) if not ans or not ans.strip()]
                        if empty_answers:
                            validation_issues.append(f"answers 중 빈 값 존재 (인덱스: {empty_answers})")
                    
                    # 검증 실패 시 사용자에게 보고
                    if validation_issues:
                        results["failed"].append({
                            "video_id": song.youtube_video_id,
                            "title": song.title,
                            "reason": f"⚠️ 데이터 검증 실패: {', '.join(validation_issues)}"
                        })
                        continue
                    
                    # 중복 체크 (youtube_video_id로 확인)
                    cursor.execute(f"SELECT id FROM song WHERE youtube_video_id = {ph}", (song.youtube_video_id,))
                    if cursor.fetchone():
                        results["failed"].append({
                            "video_id": song.youtube_video_id,
                            "title": song.title,
                            "reason": "이미 등록된 곡입니다"
                        })
                        continue

                    # song 테이블 INSERT (youtube_video_id 사용)
                    is_solo_val = 1 if song.is_solo else 0

                    cursor.execute(f"""
                        INSERT INTO song (title, artist, genre_id, youtube_video_id, play_duration, start_time, release_year, is_solo, use_yn)
                        VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph}, 'Y')
                    """, (song.title, song.artist, song.genre_id, song.youtube_video_id,
                          song.play_duration, song.start_time, song.release_year, is_solo_val))

                    song_id = cursor.lastrowid

                    # song_answer 테이블 INSERT
                    for idx, answer in enumerate(song.answers):
                        is_primary = 1 if idx == 0 else 0
                        cursor.execute(f"""
                            INSERT INTO song_answer (song_id, answer, is_primary)
                            VALUES ({ph}, {ph}, {ph})
                        """, (song_id, answer, is_primary))

                    results["success"].append({
                        "song_id": song_id,
                        "video_id": song.youtube_video_id,
                        "title": song.title,
                        "artist": song.artist,
                        "answers": song.answers
                    })

                except Exception as e:
                    results["failed"].append({
                        "video_id": song.youtube_video_id,
                        "title": song.title,
                        "reason": str(e)
                    })

            conn.commit()

    except Exception as e:
        return json.dumps({
            "status": "error",
            "message": f"DB 연결 오류: {str(e)}"
        }, ensure_ascii=False, indent=2)

    return json.dumps({
        "status": "success",
        "message": f"총 {len(results['success'])}곡 등록 완료, {len(results['failed'])}곡 실패",
        "db_type": DB_TYPE,
        "success_count": len(results["success"]),
        "failed_count": len(results["failed"]),
        "success": results["success"],
        "failed": results["failed"]
    }, ensure_ascii=False, indent=2)


# ========== 조회 도구 ==========
@mcp.tool(
    name="list_songs",
    annotations={
        "title": "등록된 노래 목록 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False
    }
)
async def list_songs() -> str:
    """등록된 모든 노래 목록을 조회합니다."""
    try:
        with get_db_connection() as conn:
            cursor = conn.cursor()
            cursor.execute("""
                SELECT s.*, g.name as genre_name 
                FROM song s 
                LEFT JOIN genre g ON s.genre_id = g.id 
                ORDER BY s.created_at DESC 
                LIMIT 50
            """)
            rows = cursor.fetchall()
            columns = [desc[0] for desc in cursor.description]
            
            songs = [dict(zip(columns, row)) for row in rows]
        
        return json.dumps({
            "status": "success",
            "db_type": DB_TYPE,
            "total": len(songs),
            "songs": songs
        }, ensure_ascii=False, indent=2, default=str)
    except Exception as e:
        return json.dumps({
            "status": "error",
            "message": str(e)
        }, ensure_ascii=False, indent=2)


@mcp.tool(
    name="list_genres",
    annotations={
        "title": "장르 목록 조회",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False
    }
)
async def list_genres() -> str:
    """등록된 장르 목록을 조회합니다. genre_id 매핑에 사용하세요."""
    try:
        genres = get_all_genres()
        
        return json.dumps({
            "status": "success",
            "db_type": DB_TYPE,
            "total": len(genres),
            "genres": genres,
            "usage": "agent_genre_classifier 호출 시 genre_id 파라미터에 해당 id를 사용하세요."
        }, ensure_ascii=False, indent=2, default=str)
    except Exception as e:
        return json.dumps({
            "status": "error",
            "message": str(e)
        }, ensure_ascii=False, indent=2)


@mcp.tool(
    name="get_song_status",
    annotations={
        "title": "노래 등록 상태 확인",
        "readOnlyHint": True,
        "destructiveHint": False,
        "idempotentHint": True,
        "openWorldHint": False
    }
)
async def get_song_status(params: SqlBuildInput) -> str:
    """특정 YouTube 비디오 ID의 등록 진행 상태를 확인합니다."""
    video_id = params.youtube_video_id
    
    # 임시 파일 확인
    info = load_song_info(video_id)
    
    # DB 확인
    db_record = None
    try:
        ph = get_placeholder()
        with get_db_connection() as conn:
            cursor = conn.cursor()
            cursor.execute(f"SELECT * FROM song WHERE youtube_video_id = {ph}", (video_id,))
            row = cursor.fetchone()
            
            if row:
                columns = [desc[0] for desc in cursor.description]
                db_record = dict(zip(columns, row))
    except Exception as e:
        db_record = {"error": str(e)}
    
    return json.dumps({
        "status": "success",
        "youtube_video_id": video_id,
        "youtube_url": f"https://www.youtube.com/watch?v={video_id}",
        "db_type": DB_TYPE,
        "temp_file_exists": info is not None,
        "temp_file_info": info,
        "db_record_exists": db_record is not None and "error" not in db_record,
        "db_record": db_record
    }, ensure_ascii=False, indent=2, default=str)


# ========== 서버 실행 ==========
if __name__ == "__main__":
    mcp.run()
