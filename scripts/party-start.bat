@echo off
chcp 65001 >nul
setlocal
rem ============================================================
rem  파티 퀴즈 실행 (행사 노트북) — party 브랜치 전용, 운영과 무관
rem  사진·상태 파일 폴더를 늘 같은 곳으로 고정한다 (O-030 ③).
rem  처음 한 번 아래 두 줄을 노트북에 맞게 고친다. 경로에 공백이 없어야 한다.
rem  사용: party-start.bat            (아래 PARTY_DIR 사용)
rem        party-start.bat D:\다른폴더  (이번만 다른 폴더 — 점검용)
rem ============================================================
set "PARTY_DIR=C:\party"
set "JAVA_HOME=C:\Program Files\Java\jdk-17"

if not "%~1"=="" set "PARTY_DIR=%~1"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [오류] JDK 17 을 찾을 수 없습니다: %JAVA_HOME%
    echo        이 파일의 JAVA_HOME 줄을 노트북의 JDK 17 경로로 고치세요.
    pause
    exit /b 1
)
if not "%PARTY_DIR%"=="%PARTY_DIR: =%" (
    echo [오류] 폴더 경로에 공백이 있습니다: %PARTY_DIR%
    pause
    exit /b 1
)
if not exist "%PARTY_DIR%" mkdir "%PARTY_DIR%"

rem 저장소 루트에서 실행한다. 현재 폴더 실행 파일을 찾지 않는 PC 도 있어 .\mvnw.cmd 로 경로를 적는다
cd /d "%~dp0.."
echo 사진·상태 폴더: %PARTY_DIR%
echo 사진 파일은 이 폴더에 바로 넣는다 (TSV 의 이미지파일명과 같은 이름).
echo 브라우저: http://localhost:8082/admin/party/console  (보드는 /admin/party/board)
echo.
call .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev,session-jdbc "-Dspring-boot.run.arguments=--party.image-dir=%PARTY_DIR% --party.state-file=%PARTY_DIR%\party-state.json --party.speed-state-file=%PARTY_DIR%\party-speed-state.json"
pause
