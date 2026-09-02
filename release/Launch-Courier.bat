@echo off
title Courier
setlocal

set "JAR=%~dp0Courier-Desktop-latest.jar"

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

where javaw >nul 2>&1
if errorlevel 1 (
    echo.
    echo   ERROR: Java was not found on PATH.
    echo   Courier needs a Java 17 or newer runtime.
    echo.
    pause
    exit /b 1
)

echo Starting Courier Video Downloader...

REM javaw detaches with no console, so a startup crash leaves no trace here and
REM the app simply "doesn't start". That is how an unlaunchable jar shipped in
REM v1.4.0 and v1.5.0 without anyone noticing.
REM
REM To see the actual error when Courier will not start, run it in the
REM foreground and read stderr:
REM
REM     java -jar Courier-Desktop-latest.jar
REM
start "" /b javaw -jar "%JAR%"

endlocal
