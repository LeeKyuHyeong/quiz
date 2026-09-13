@echo off
echo === Song Register MCP 설치 ===
echo.

cd /d "%~dp0"

echo [1/3] Python 의존성 설치...
pip install -r requirements.txt

echo.
echo [2/3] data 폴더 생성...
if not exist "data" mkdir data
if not exist "data\temp" mkdir data\temp

echo.
echo [3/3] 설치 완료!
echo.
echo === Claude Desktop 설정 방법 ===
echo.
echo 1. %%APPDATA%%\Claude\claude_desktop_config.json 열기
echo 2. 아래 내용 추가:
echo.
echo {
echo   "mcpServers": {
echo     "song_register_mcp": {
echo       "command": "python",
echo       "args": ["%CD:\=/%/song_register_mcp.py"],
echo       "env": {
echo         "DB_TYPE": "mariadb",
echo         "MARIADB_HOST": "YOUR_DB_HOST",
echo         "MARIADB_PORT": "3308",
echo         "MARIADB_USER": "root",
echo         "MARIADB_PASSWORD": "YOUR_PASSWORD",
echo         "MARIADB_DATABASE": "song"
echo       }
echo     }
echo   }
echo }
echo.
echo 현재 경로: %CD%
echo.
pause
