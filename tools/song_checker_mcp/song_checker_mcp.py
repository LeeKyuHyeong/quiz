"""
노래 검사 MCP 서버 (Song Checker)
가수 이름을 입력받아 해당 가수의 곡들을 검사하고, MV vs Lyrics 판별, 가수-곡 정보 검증을 수행합니다.

=== 메인 워크플로우 ===
1. start_artist_check: 가수 검사 시작 → 곡 목록 반환
2. 각 곡에 대해:
   a. fetch_youtube_title: YouTube 영상 제목 조회
   b. check_mv_or_lyrics: MV/Lyrics 판별
   c. [MV인 경우] Claude 웹서칭 → suggest_lyrics_video
   d. Claude 웹서칭으로 검증 → verify_song_artist
   e. finalize_check: 검수 완료
3. get_pending_updates: 수정 대기 목록 확인
4. apply_updates: DB 업데이트 적용

환경변수:
- DB_TYPE: 'sqlite' (기본값) 또는 'mariadb'
- MARIADB_HOST: MariaDB 호스트 (필수)
- MARIADB_PORT: MariaDB 포트 (기본값: 3308)
- MARIADB_USER: MariaDB 사용자 (기본값: root)
- MARIADB_PASSWORD: MariaDB 비밀번호 (필수)
- MARIADB_DATABASE: MariaDB 데이터베이스명 (기본값: song)
"""

import os
import json
import httpx
from datetime import datetime
from typing import Optional, List, Dict, Any
from pathlib import Path
from contextlib import contextmanager

from mcp.server.fastmcp import FastMCP
from pydantic import BaseModel, Field, ConfigDict

# ========== DB 설정 ==========
DB_TYPE = os.environ.get('DB_TYPE', 'mariadb')

# MariaDB 설정 (운영 서버)
MARIADB_CONFIG = {
    'host': os.environ.get('MARIADB_HOST'),
    'port': int(os.environ.get('MARIADB_PORT', '3308')),
    'user': os.environ.get('MARIADB_USER', 'root'),
    'password': os.environ.get('MARIADB_PASSWORD'),
    'database': os.environ.get('MARIADB_DATABASE', 'song'),
    'charset': 'utf8mb4'
}

# 데이터 저장 경로
DATA_DIR = Path(__file__).parent / "data"
TEMP_DIR = DATA_DIR / "temp"
DATA_DIR.mkdir(exist_ok=True)
TEMP_DIR.mkdir(exist_ok=True)

# MCP 서버 초기화
mcp = FastMCP("song_checker_mcp")

# ========== 전역 세션 상태 ==========
_current_check_session: Dict[str, Any] = {}

# ========== MV/Lyrics 판별 상수 ==========
MV_INDICATORS = [
    'MV', 'M/V', 'Music Video', 'Official Video', 'Official MV',
    '뮤직비디오', '뮤비', 'OFFICIAL MV', 'MUSIC VIDEO'
]

LYRICS_INDICATORS = [
    'Lyrics', 'Lyric', '가사', 'Audio', 'Official Audio',
    'Visualizer', 'LYRICS', 'LYRIC', '(가사)', '[가사]'
]


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
            import sqlite3
            sqlite_path = DATA_DIR / "songs.db"
            conn = sqlite3.connect(sqlite_path)
            yield conn
    finally:
        if conn:
            conn.close()


def get_placeholder():
    """DB 타입에 따른 placeholder 반환"""
    return '%s' if DB_TYPE == 'mariadb' else '?'


# ========== 입력 모델 정의 ==========
class ArtistCheckInput(BaseModel):
    """가수 검사 시작 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    artist: str = Field(..., description="검사할 가수명")


class YouTubeTitleInput(BaseModel):
    """YouTube 제목 조회 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID (11자)")


class MVCheckInput(BaseModel):
    """MV/Lyrics 판별 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    youtube_video_id: str = Field(..., description="YouTube 비디오 ID")
    youtube_title: str = Field(..., description="YouTube 영상 제목")


class SuggestLyricsInput(BaseModel):
    """Lyrics 영상 제안 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    song_id: int = Field(..., description="곡 ID")
    suggested_video_id: str = Field(..., description="제안된 Lyrics 영상 ID")
    search_query_used: str = Field(..., description="검색에 사용한 쿼리")


class VerifyArtistInput(BaseModel):
    """가수-곡 검증 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    song_id: int = Field(..., description="곡 ID")
    is_correct: bool = Field(..., description="가수-곡 매칭이 올바른지")
    correct_artist: Optional[str] = Field(None, description="올바른 가수명 (틀린 경우)")
    verification_sources: List[str] = Field(..., description="검증에 사용한 출처 목록")


class MarkUpdateInput(BaseModel):
    """수정 표시 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    song_id: int = Field(..., description="곡 ID")
    update_type: str = Field(..., description="수정 유형: 'artist', 'video_id', 'both'")
    new_artist: Optional[str] = Field(None, description="새 가수명")
    new_video_id: Optional[str] = Field(None, description="새 YouTube ID")
    reason: str = Field(..., description="수정 사유")


class FinalizeCheckInput(BaseModel):
    """최종 검수 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    song_id: int = Field(..., description="곡 ID")
    final_notes: Optional[str] = Field(None, description="검수 메모")


class ApplyUpdatesInput(BaseModel):
    """업데이트 적용 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    confirm: bool = Field(..., description="적용 확인")
    song_ids: Optional[List[int]] = Field(None, description="특정 곡만 적용 시 ID 목록")


class CancelUpdateInput(BaseModel):
    """수정 취소 입력"""
    model_config = ConfigDict(str_strip_whitespace=True)
    song_id: int = Field(..., description="취소할 곡 ID")


# ========== 유틸리티 함수 ==========
def save_session_to_file(artist: str):
    """세션 데이터를 파일로 저장"""
    safe_artist = "".join(c for c in artist if c.isalnum() or c in (' ', '-', '_')).strip()
    file_path = TEMP_DIR / f"{safe_artist}_check_result.json"
    with open(file_path, 'w', encoding='utf-8') as f:
        json.dump(_current_check_session, f, ensure_ascii=False, indent=2, default=str)
    return str(file_path)


def is_mv_video(title: str) -> Optional[bool]:
    """YouTube 제목으로 MV인지 판별
    Returns:
        True: MV로 확정
        False: Lyrics/Audio로 확정
        None: 불확실 (추가 확인 필요)
    """
    title_upper = title.upper()

    # Lyrics 지시자가 있으면 Lyrics
    for indicator in LYRICS_INDICATORS:
        if indicator.upper() in title_upper:
            return False

    # MV 지시자가 있으면 MV
    for indicator in MV_INDICATORS:
        if indicator.upper() in title_upper:
            return True

    # 둘 다 없으면 불확실
    return None


# ========== MCP 도구 구현 ==========

@mcp.tool()
async def start_artist_check(input: ArtistCheckInput) -> str:
    """
    가수 검사 워크플로우를 시작합니다.
    해당 가수의 모든 곡 목록을 조회하여 반환합니다.
    """
    global _current_check_session

    artist = input.artist.strip()

    try:
        with get_db_connection() as conn:
            cursor = conn.cursor()
            ph = get_placeholder()

            # 가수명으로 곡 조회 (LIKE 검색)
            query = f"""
                SELECT id, title, artist, youtube_video_id, start_time, play_duration
                FROM song
                WHERE artist LIKE {ph}
                  AND use_yn = 'Y'
                  AND youtube_video_id IS NOT NULL
                ORDER BY id
            """
            cursor.execute(query, (f'%{artist}%',))
            rows = cursor.fetchall()

            if not rows:
                return json.dumps({
                    "status": "not_found",
                    "message": f"'{artist}' 가수의 곡을 찾을 수 없습니다.",
                    "suggestion": "가수명을 정확히 입력해주세요."
                }, ensure_ascii=False)

            # 세션 초기화
            songs = []
            for row in rows:
                songs.append({
                    "song_id": row[0],
                    "title": row[1],
                    "artist": row[2],
                    "youtube_video_id": row[3],
                    "start_time": row[4],
                    "play_duration": row[5],
                    "check_status": "pending",
                    "youtube_title": None,
                    "is_mv": None,
                    "mv_check_result": None,
                    "suggested_lyrics_id": None,
                    "artist_verified": None,
                    "artist_verification_sources": [],
                    "needs_update": False,
                    "update_type": None,
                    "new_video_id": None,
                    "new_artist": None,
                    "final_notes": None
                })

            _current_check_session = {
                "artist": artist,
                "started_at": datetime.now().isoformat(),
                "songs": songs,
                "pending_updates": [],
                "completed_count": 0,
                "total_count": len(songs)
            }

            # 파일로 저장
            save_session_to_file(artist)

            # 곡 목록 요약 생성
            song_list = []
            for i, song in enumerate(songs, 1):
                song_list.append(
                    f"{i}. [{song['song_id']}] {song['title']} - {song['artist']} "
                    f"(video_id: {song['youtube_video_id']}, duration: {song['play_duration']}초)"
                )

            return json.dumps({
                "status": "success",
                "artist": artist,
                "total_songs": len(songs),
                "songs": song_list,
                "next_step": "각 곡에 대해 fetch_youtube_title을 호출하여 YouTube 영상 제목을 확인하세요."
            }, ensure_ascii=False, indent=2)

    except Exception as e:
        return json.dumps({
            "status": "error",
            "message": f"DB 조회 오류: {str(e)}"
        }, ensure_ascii=False)


@mcp.tool()
async def get_check_status() -> str:
    """
    현재 검사 진행 상태를 조회합니다.
    """
    if not _current_check_session:
        return json.dumps({
            "status": "no_session",
            "message": "진행 중인 검사 세션이 없습니다. start_artist_check를 먼저 실행하세요."
        }, ensure_ascii=False)

    completed = [s for s in _current_check_session["songs"] if s["check_status"] == "completed"]
    pending = [s for s in _current_check_session["songs"] if s["check_status"] == "pending"]
    in_progress = [s for s in _current_check_session["songs"] if s["check_status"] == "in_progress"]

    pending_updates = _current_check_session.get("pending_updates", [])

    return json.dumps({
        "status": "success",
        "artist": _current_check_session["artist"],
        "started_at": _current_check_session["started_at"],
        "progress": {
            "total": _current_check_session["total_count"],
            "completed": len(completed),
            "in_progress": len(in_progress),
            "pending": len(pending)
        },
        "pending_updates_count": len(pending_updates),
        "pending_songs": [f"[{s['song_id']}] {s['title']}" for s in pending[:5]],
        "completed_songs": [f"[{s['song_id']}] {s['title']}" for s in completed[-5:]]
    }, ensure_ascii=False, indent=2)


@mcp.tool()
async def fetch_youtube_title(input: YouTubeTitleInput) -> str:
    """
    YouTube oEmbed API로 영상 제목을 조회합니다.
    """
    video_id = input.youtube_video_id.strip()
    url = f"https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v={video_id}&format=json"

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(url)

            if response.status_code == 200:
                data = response.json()

                # 세션에 YouTube 제목 저장
                if _current_check_session:
                    for song in _current_check_session["songs"]:
                        if song["youtube_video_id"] == video_id:
                            song["youtube_title"] = data.get("title", "")
                            song["check_status"] = "in_progress"
                            break
                    save_session_to_file(_current_check_session["artist"])

                return json.dumps({
                    "status": "success",
                    "video_id": video_id,
                    "title": data.get("title", ""),
                    "author_name": data.get("author_name", ""),
                    "thumbnail_url": data.get("thumbnail_url", ""),
                    "next_step": "check_mv_or_lyrics를 호출하여 MV인지 Lyrics인지 판별하세요."
                }, ensure_ascii=False, indent=2)

            elif response.status_code == 404:
                return json.dumps({
                    "status": "not_found",
                    "video_id": video_id,
                    "message": "영상을 찾을 수 없습니다 (삭제되었거나 비공개)",
                    "action": "이 곡을 비활성화하거나 다른 영상 ID로 교체해야 합니다."
                }, ensure_ascii=False)

            elif response.status_code in [401, 403]:
                return json.dumps({
                    "status": "embed_disabled",
                    "video_id": video_id,
                    "message": "영상 임베드가 비활성화되어 있습니다.",
                    "action": "다른 영상 ID로 교체를 고려하세요."
                }, ensure_ascii=False)

            else:
                return json.dumps({
                    "status": "error",
                    "video_id": video_id,
                    "message": f"HTTP {response.status_code} 오류"
                }, ensure_ascii=False)

    except Exception as e:
        return json.dumps({
            "status": "error",
            "video_id": video_id,
            "message": f"네트워크 오류: {str(e)}"
        }, ensure_ascii=False)


@mcp.tool()
async def check_mv_or_lyrics(input: MVCheckInput) -> str:
    """
    YouTube 영상 제목을 기반으로 MV인지 Lyrics인지 판별합니다.
    """
    video_id = input.youtube_video_id.strip()
    title = input.youtube_title.strip()

    is_mv = is_mv_video(title)

    # 판별 근거 생성
    if is_mv is True:
        matched_indicators = [ind for ind in MV_INDICATORS if ind.upper() in title.upper()]
        result_text = f"MV로 판별됨 (제목에 '{', '.join(matched_indicators)}' 포함)"
        action_needed = True
        action_text = "Lyrics 영상으로 교체가 필요합니다. 웹서칭으로 '{가수} {곡제목} 가사' 검색 후 suggest_lyrics_video를 호출하세요."
    elif is_mv is False:
        matched_indicators = [ind for ind in LYRICS_INDICATORS if ind.upper() in title.upper()]
        result_text = f"Lyrics/Audio로 판별됨 (제목에 '{', '.join(matched_indicators)}' 포함)"
        action_needed = False
        action_text = "교체 불필요. verify_song_artist로 가수-곡 정보 검증을 진행하세요."
    else:
        result_text = "불확실 (MV/Lyrics 지시자 없음)"
        action_needed = None
        action_text = "영상을 직접 확인하거나, 일단 Lyrics로 간주하고 진행하세요."

    # 세션 업데이트
    if _current_check_session:
        for song in _current_check_session["songs"]:
            if song["youtube_video_id"] == video_id:
                song["is_mv"] = is_mv
                song["mv_check_result"] = result_text
                break
        save_session_to_file(_current_check_session["artist"])

    return json.dumps({
        "status": "success",
        "video_id": video_id,
        "youtube_title": title,
        "is_mv": is_mv,
        "result": result_text,
        "action_needed": action_needed,
        "next_step": action_text
    }, ensure_ascii=False, indent=2)


@mcp.tool()
async def suggest_lyrics_video(input: SuggestLyricsInput) -> str:
    """
    Claude 웹서칭 결과로 Lyrics 영상 ID를 제안합니다.
    MV로 판별된 곡에 대해 교체할 Lyrics 영상을 지정합니다.
    """
    song_id = input.song_id
    suggested_video_id = input.suggested_video_id.strip()
    search_query = input.search_query_used.strip()

    if not _current_check_session:
        return json.dumps({
            "status": "error",
            "message": "진행 중인 세션이 없습니다."
        }, ensure_ascii=False)

    # 해당 곡 찾기
    song = None
    for s in _current_check_session["songs"]:
        if s["song_id"] == song_id:
            song = s
            break

    if not song:
        return json.dumps({
            "status": "error",
            "message": f"곡 ID {song_id}를 찾을 수 없습니다."
        }, ensure_ascii=False)

    # Lyrics 영상 제안 저장
    song["suggested_lyrics_id"] = suggested_video_id
    song["needs_update"] = True
    song["update_type"] = "video_id"
    song["new_video_id"] = suggested_video_id

    # pending_updates에 추가
    update_entry = {
        "song_id": song_id,
        "title": song["title"],
        "artist": song["artist"],
        "update_type": "video_id",
        "old_value": song["youtube_video_id"],
        "new_value": suggested_video_id,
        "reason": f"MV를 Lyrics로 교체 (검색: {search_query})"
    }

    # 중복 체크 후 추가
    existing = [u for u in _current_check_session["pending_updates"] if u["song_id"] == song_id and u["update_type"] == "video_id"]
    if existing:
        _current_check_session["pending_updates"].remove(existing[0])
    _current_check_session["pending_updates"].append(update_entry)

    save_session_to_file(_current_check_session["artist"])

    return json.dumps({
        "status": "success",
        "song_id": song_id,
        "title": song["title"],
        "old_video_id": song["youtube_video_id"],
        "new_video_id": suggested_video_id,
        "search_query": search_query,
        "next_step": "verify_song_artist를 호출하여 가수-곡 정보를 검증하세요."
    }, ensure_ascii=False, indent=2)


@mcp.tool()
async def verify_song_artist(input: VerifyArtistInput) -> str:
    """
    가수-곡제목 매칭이 올바른지 검증 결과를 저장합니다.
    웹서칭으로 확인한 결과를 입력받습니다.
    """
    song_id = input.song_id
    is_correct = input.is_correct
    correct_artist = input.correct_artist
    sources = input.verification_sources

    if not _current_check_session:
        return json.dumps({
            "status": "error",
            "message": "진행 중인 세션이 없습니다."
        }, ensure_ascii=False)

    # 해당 곡 찾기
    song = None
    for s in _current_check_session["songs"]:
        if s["song_id"] == song_id:
            song = s
            break

    if not song:
        return json.dumps({
            "status": "error",
            "message": f"곡 ID {song_id}를 찾을 수 없습니다."
        }, ensure_ascii=False)

    # 검증 결과 저장
    song["artist_verified"] = is_correct
    song["artist_verification_sources"] = sources

    result_message = ""

    if not is_correct and correct_artist:
        song["needs_update"] = True
        if song["update_type"] == "video_id":
            song["update_type"] = "both"
        else:
            song["update_type"] = "artist"
        song["new_artist"] = correct_artist

        # pending_updates에 추가
        update_entry = {
            "song_id": song_id,
            "title": song["title"],
            "artist": song["artist"],
            "update_type": "artist",
            "old_value": song["artist"],
            "new_value": correct_artist,
            "reason": f"가수 정보 수정 (출처: {', '.join(sources)})"
        }

        # 중복 체크 후 추가
        existing = [u for u in _current_check_session["pending_updates"] if u["song_id"] == song_id and u["update_type"] == "artist"]
        if existing:
            _current_check_session["pending_updates"].remove(existing[0])
        _current_check_session["pending_updates"].append(update_entry)

        result_message = f"가수 정보 불일치! '{song['artist']}' → '{correct_artist}'로 수정 예정"
    else:
        result_message = "가수-곡 정보가 올바릅니다."

    save_session_to_file(_current_check_session["artist"])

    return json.dumps({
        "status": "success",
        "song_id": song_id,
        "title": song["title"],
        "current_artist": song["artist"],
        "is_correct": is_correct,
        "correct_artist": correct_artist if not is_correct else None,
        "verification_sources": sources,
        "result": result_message,
        "next_step": "finalize_check를 호출하여 이 곡의 검수를 완료하세요."
    }, ensure_ascii=False, indent=2)


@mcp.tool()
async def mark_song_for_update(input: MarkUpdateInput) -> str:
    """
    특정 곡에 대해 수정이 필요함을 표시합니다.
    """
    song_id = input.song_id
    update_type = input.update_type
    new_artist = input.new_artist
    new_video_id = input.new_video_id
    reason = input.reason

    if not _current_check_session:
        return json.dumps({
            "status": "error",
            "message": "진행 중인 세션이 없습니다."
        }, ensure_ascii=False)

    # 해당 곡 찾기
    song = None
    for s in _current_check_session["songs"]:
        if s["song_id"] == song_id:
            song = s
            break

    if not song:
        return json.dumps({
            "status": "error",
            "message": f"곡 ID {song_id}를 찾을 수 없습니다."
        }, ensure_ascii=False)

    song["needs_update"] = True
    song["update_type"] = update_type

    updates_added = []

    if update_type in ["artist", "both"] and new_artist:
        song["new_artist"] = new_artist
        update_entry = {
            "song_id": song_id,
            "title": song["title"],
            "artist": song["artist"],
            "update_type": "artist",
            "old_value": song["artist"],
            "new_value": new_artist,
            "reason": reason
        }
        _current_check_session["pending_updates"].append(update_entry)
        updates_added.append(f"artist: {song['artist']} → {new_artist}")

    if update_type in ["video_id", "both"] and new_video_id:
        song["new_video_id"] = new_video_id
        update_entry = {
            "song_id": song_id,
            "title": song["title"],
            "artist": song["artist"],
            "update_type": "video_id",
            "old_value": song["youtube_video_id"],
            "new_value": new_video_id,
            "reason": reason
        }
        _current_check_session["pending_updates"].append(update_entry)
        updates_added.append(f"video_id: {song['youtube_video_id']} → {new_video_id}")

    save_session_to_file(_current_check_session["artist"])

    return json.dumps({
        "status": "success",
        "song_id": song_id,
        "title": song["title"],
        "updates_added": updates_added,
        "reason": reason
    }, ensure_ascii=False, indent=2)


@mcp.tool()
async def finalize_check(input: FinalizeCheckInput) -> str:
    """
    특정 곡의 검수를 완료 표시합니다.
    """
    song_id = input.song_id
    final_notes = input.final_notes

    if not _current_check_session:
        return json.dumps({
            "status": "error",
            "message": "진행 중인 세션이 없습니다."
        }, ensure_ascii=False)

    # 해당 곡 찾기
    song = None
    for s in _current_check_session["songs"]:
        if s["song_id"] == song_id:
            song = s
            break

    if not song:
        return json.dumps({
            "status": "error",
            "message": f"곡 ID {song_id}를 찾을 수 없습니다."
        }, ensure_ascii=False)

    song["check_status"] = "completed"
    song["final_notes"] = final_notes
    _current_check_session["completed_count"] = len([s for s in _current_check_session["songs"] if s["check_status"] == "completed"])

    save_session_to_file(_current_check_session["artist"])

    # 다음 검사할 곡 찾기
    pending_songs = [s for s in _current_check_session["songs"] if s["check_status"] == "pending"]

    result = {
        "status": "success",
        "song_id": song_id,
        "title": song["title"],
        "final_notes": final_notes,
        "progress": f"{_current_check_session['completed_count']}/{_current_check_session['total_count']} 완료"
    }

    if pending_songs:
        next_song = pending_songs[0]
        result["next_song"] = {
            "song_id": next_song["song_id"],
            "title": next_song["title"],
            "youtube_video_id": next_song["youtube_video_id"]
        }
        result["next_step"] = f"다음 곡: fetch_youtube_title(youtube_video_id='{next_song['youtube_video_id']}')"
    else:
        result["next_step"] = "모든 곡 검사 완료! get_pending_updates로 수정 대기 목록을 확인하세요."

    return json.dumps(result, ensure_ascii=False, indent=2)


@mcp.tool()
async def get_pending_updates() -> str:
    """
    대기 중인 수정사항 목록을 조회합니다.
    """
    if not _current_check_session:
        return json.dumps({
            "status": "no_session",
            "message": "진행 중인 세션이 없습니다."
        }, ensure_ascii=False)

    pending = _current_check_session.get("pending_updates", [])

    if not pending:
        return json.dumps({
            "status": "success",
            "message": "수정이 필요한 항목이 없습니다.",
            "pending_count": 0
        }, ensure_ascii=False)

    # 유형별 그룹화
    video_updates = [u for u in pending if u["update_type"] == "video_id"]
    artist_updates = [u for u in pending if u["update_type"] == "artist"]

    return json.dumps({
        "status": "success",
        "artist": _current_check_session["artist"],
        "pending_count": len(pending),
        "video_id_updates": len(video_updates),
        "artist_updates": len(artist_updates),
        "updates": pending,
        "next_step": "apply_updates(confirm=True)를 호출하여 DB에 적용하세요."
    }, ensure_ascii=False, indent=2)


@mcp.tool()
async def apply_updates(input: ApplyUpdatesInput) -> str:
    """
    대기 중인 수정사항을 DB에 적용합니다.
    """
    if not input.confirm:
        return json.dumps({
            "status": "cancelled",
            "message": "확인되지 않아 취소되었습니다."
        }, ensure_ascii=False)

    if not _current_check_session:
        return json.dumps({
            "status": "error",
            "message": "진행 중인 세션이 없습니다."
        }, ensure_ascii=False)

    pending = _current_check_session.get("pending_updates", [])

    if input.song_ids:
        pending = [p for p in pending if p["song_id"] in input.song_ids]

    if not pending:
        return json.dumps({
            "status": "success",
            "message": "적용할 수정사항이 없습니다."
        }, ensure_ascii=False)

    results = {"success": [], "failed": []}

    try:
        with get_db_connection() as conn:
            cursor = conn.cursor()
            ph = get_placeholder()

            for update in pending:
                try:
                    if update["update_type"] == "video_id":
                        query = f"UPDATE song SET youtube_video_id = {ph}, updated_at = NOW() WHERE id = {ph}"
                        cursor.execute(query, (update["new_value"], update["song_id"]))
                    elif update["update_type"] == "artist":
                        query = f"UPDATE song SET artist = {ph}, updated_at = NOW() WHERE id = {ph}"
                        cursor.execute(query, (update["new_value"], update["song_id"]))

                    results["success"].append({
                        "song_id": update["song_id"],
                        "title": update["title"],
                        "update_type": update["update_type"],
                        "old_value": update["old_value"],
                        "new_value": update["new_value"]
                    })
                except Exception as e:
                    results["failed"].append({
                        "song_id": update["song_id"],
                        "title": update["title"],
                        "error": str(e)
                    })

            conn.commit()

        # 적용된 항목 pending에서 제거
        applied_song_ids = [r["song_id"] for r in results["success"]]
        _current_check_session["pending_updates"] = [
            p for p in _current_check_session["pending_updates"]
            if not (p["song_id"] in applied_song_ids and p["update_type"] in [r["update_type"] for r in results["success"] if r["song_id"] == p["song_id"]])
        ]

        save_session_to_file(_current_check_session["artist"])

        return json.dumps({
            "status": "success",
            "applied_count": len(results["success"]),
            "failed_count": len(results["failed"]),
            "success": results["success"],
            "failed": results["failed"]
        }, ensure_ascii=False, indent=2)

    except Exception as e:
        return json.dumps({
            "status": "error",
            "message": f"DB 업데이트 오류: {str(e)}"
        }, ensure_ascii=False)


@mcp.tool()
async def cancel_update(input: CancelUpdateInput) -> str:
    """
    특정 곡의 대기 중인 수정사항을 취소합니다.
    """
    song_id = input.song_id

    if not _current_check_session:
        return json.dumps({
            "status": "error",
            "message": "진행 중인 세션이 없습니다."
        }, ensure_ascii=False)

    pending = _current_check_session.get("pending_updates", [])
    original_count = len(pending)

    _current_check_session["pending_updates"] = [p for p in pending if p["song_id"] != song_id]
    removed_count = original_count - len(_current_check_session["pending_updates"])

    # 곡 상태도 업데이트
    for song in _current_check_session["songs"]:
        if song["song_id"] == song_id:
            song["needs_update"] = False
            song["update_type"] = None
            song["new_video_id"] = None
            song["new_artist"] = None
            break

    save_session_to_file(_current_check_session["artist"])

    return json.dumps({
        "status": "success",
        "song_id": song_id,
        "removed_updates": removed_count,
        "remaining_updates": len(_current_check_session["pending_updates"])
    }, ensure_ascii=False, indent=2)


# ========== 서버 실행 ==========
if __name__ == "__main__":
    print(f"Song Checker MCP 서버 시작 (DB: {DB_TYPE})")
    if DB_TYPE == 'mariadb':
        print(f"MariaDB: {MARIADB_CONFIG['host']}:{MARIADB_CONFIG['port']}/{MARIADB_CONFIG['database']}")
    mcp.run()
