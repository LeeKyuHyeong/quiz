"""
Song Integrity MCP - Configuration
운영 DB 접속 정보 및 공통 설정
"""
import os

_missing = [k for k in ("MARIADB_HOST", "MARIADB_PASSWORD") if not os.getenv(k)]
if _missing:
    raise RuntimeError(f"환경변수 미설정: {', '.join(_missing)}")

# Database Configuration
DB_CONFIG = {
    "host": os.environ["MARIADB_HOST"],
    "port": int(os.getenv("MARIADB_PORT", "3308")),
    "user": os.getenv("MARIADB_USER", "root"),
    "password": os.environ["MARIADB_PASSWORD"],
    "database": os.getenv("MARIADB_DATABASE", "song"),
    "charset": "utf8mb4"
}

# YouTube API (optional, for enhanced search)
YOUTUBE_API_KEY = os.getenv("YOUTUBE_API_KEY", "")

# Agent Names
AGENT_NAMES = {
    "data_manager": "DataManager",
    "youtube_searcher": "YouTubeSearcher",
    "data_verifier": "DataVerifier",
    "data_comparator": "DataComparator",
    "search_helper": "SearchHelper",
    "decision_advisor": "DecisionAdvisor",
    "db_updater": "DBUpdater"
}

# Logging
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
