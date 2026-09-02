@echo off
title Courier
setlocal

set "EXE=%~dp0release\Courier-Windows\Courier.exe"
set "JAR=%~dp0release\Courier-Desktop-latest.jar"

if exist "%EXE%" (
    echo Starting Courier Windows Executable...
    start "" "%EXE%"
    exit /b 0
)

if not exist "%JAR%" (
    echo.
    echo   ERROR: No desktop build found.
    echo   Expected: %EXE% or %JAR%
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

echo Starting Courier JAR...
start "" /b javaw -jar "%JAR%"

endlocal
