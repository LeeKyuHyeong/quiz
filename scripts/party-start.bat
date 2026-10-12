@echo off
setlocal
rem ============================================================
rem  Party quiz launcher (event laptop) - party branch only, not production.
rem  Pins the photo/state folder so it never depends on where you start from (O-030 3).
rem  Edit the two lines below once for the laptop. No spaces in PARTY_DIR.
rem  Usage: party-start.bat             (uses PARTY_DIR below)
rem         party-start.bat D:\other    (this run only - for checks; a relative path is made absolute)
rem  Run it once while online first: Maven must cache its files to start offline.
rem  ASCII only on purpose: non-ASCII text in .bat files can make cmd misread lines.
rem ============================================================
set "PARTY_DIR=C:\party"
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
rem ALLOW_LAN=0: only this laptop can open the app (default). The seed admin password is in the public repo,
rem so anyone on the same Wi-Fi could log in, read answers and kick the MC out.
rem ALLOW_LAN=1: phones on the same network can connect (backup phone console). Use only on a locked hotspot.
set "ALLOW_LAN=0"

if not "%~1"=="" set "PARTY_DIR=%~f1"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 17 not found: %JAVA_HOME%
    echo         Fix the JAVA_HOME line in this file.
    pause
    exit /b 1
)
if not "%PARTY_DIR%"=="%PARTY_DIR: =%" (
    echo [ERROR] PARTY_DIR must not contain spaces: %PARTY_DIR%
    pause
    exit /b 1
)
if not exist "%PARTY_DIR%" mkdir "%PARTY_DIR%"
rem If the state file cannot be written the app still starts but loses restart recovery silently.
del "%PARTY_DIR%\.write-test" 2>nul
echo ok> "%PARTY_DIR%\.write-test" 2>nul
if not exist "%PARTY_DIR%\.write-test" (
    echo [ERROR] Cannot write to %PARTY_DIR%
    pause
    exit /b 1
)
del "%PARTY_DIR%\.write-test"

rem A second run would fail on the port anyway, but only after moving the running app's log aside.
netstat -ano -p tcp | findstr /r /c:":8082 .*LISTENING" >nul
if not errorlevel 1 (
    echo [ERROR] Port 8082 is already in use - the app is probably running in another window.
    echo         Use that window, or stop it with Ctrl+C there first.
    pause
    exit /b 1
)

set "BIND=--server.address=127.0.0.1"
if "%ALLOW_LAN%"=="1" set "BIND="

set "LOG=%PARTY_DIR%\party-app.log"
if exist "%LOG%" move /y "%LOG%" "%PARTY_DIR%\party-app.prev.log" >nul

rem Run from the repo root. Some PCs do not search the current folder for programs, hence .\mvnw.cmd
cd /d "%~dp0.."
echo Photo/state folder : %PARTY_DIR%
echo   (put photo files here, named as in the TSV image column)
echo App log            : %LOG%
echo.
echo In 1-2 minutes open:
echo   console  http://localhost:8082/admin/party/console
echo   board    http://localhost:8082/admin/party/board
echo.
if "%ALLOW_LAN%"=="1" (
    echo [WARNING] ALLOW_LAN=1 - phones on this network can reach the app. Keep the hotspot locked.
) else (
    echo Only this laptop can open the app. For the backup phone console set ALLOW_LAN=1 in this file.
)
echo Closing this window stops the app. To stop: Ctrl+C here.
rem Logs go to a file, not this window: clicking the window pauses its output and every request that logs would hang.
rem The log is UTF-8: open it in VS Code or Notepad.
rem Batch schedules are off: offline at night the YouTube check batch would mark every song as deleted.
call .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev,session-jdbc "-Dspring-boot.run.arguments=--party.image-dir=%PARTY_DIR% --party.state-file=%PARTY_DIR%\party-state.json --party.speed-state-file=%PARTY_DIR%\party-speed-state.json --game.batch.schedule-enabled=false --spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=INFO --logging.charset.console=UTF-8 %BIND%" > "%LOG%" 2>&1
echo.
echo The app has stopped. See the end of the log: %LOG%
pause
