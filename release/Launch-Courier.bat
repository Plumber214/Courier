@echo off
title Courier
setlocal

set "JAR=%~dp0Courier-Desktop-latest.jar"

REM Points at the stable alias, never a version-pinned filename. This script
REM hardcoded Courier-Desktop-v1.0.0.jar, which stopped existing several
REM releases ago and failed silently.

if not exist "%JAR%" (
    echo.
    echo   ERROR: No desktop build found.
    echo   Expected: %JAR%
    echo.
    echo   Build one with:  gradlew publishDesktopRelease
    echo.
    pause
    exit /b 1
)

echo Starting Courier Video Downloader...
start "" /b javaw -jar "%JAR%"
endlocal
