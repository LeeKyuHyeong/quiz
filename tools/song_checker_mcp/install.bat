@echo off
echo ========================================
echo Song Checker MCP Server Installation
echo ========================================
echo.

REM Check if Python is installed
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python is not installed or not in PATH.
    echo Please install Python 3.8+ from https://www.python.org/
    pause
    exit /b 1
)

echo [1/3] Creating virtual environment...
python -m venv venv
if errorlevel 1 (
    echo [ERROR] Failed to create virtual environment.
    pause
    exit /b 1
)

echo [2/3] Activating virtual environment...
call venv\Scripts\activate.bat

echo [3/3] Installing dependencies...
pip install -r requirements.txt
if errorlevel 1 (
    echo [ERROR] Failed to install dependencies.
    pause
    exit /b 1
)

echo.
echo ========================================
echo Installation Complete!
echo ========================================
echo.
echo To use this MCP server, add the following to your Claude Desktop config:
echo.
echo Location (Windows): %%APPDATA%%\Claude\claude_desktop_config.json
echo.
echo {
echo   "mcpServers": {
echo     "song_checker_mcp": {
echo       "command": "python",
echo       "args": ["%~dp0song_checker_mcp.py"],
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
echo ========================================
pause
