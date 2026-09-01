@echo off
title Courier
setlocal

set "JAR=%~dp0release\Courier-Desktop-latest.jar"

REM Point at the stable alias, never a version-pinned filename. This script
REM previously hardcoded Courier-Desktop-v1.0.0.jar, which stopped existing
REM several releases ago -- it failed silently and users kept running an old
REM build without knowing.

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

echo Starting Courier...
start "" /b javaw -jar "%JAR%"
endlocal
