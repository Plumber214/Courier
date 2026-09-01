@echo off
title Install Courier on Android
setlocal

set "APK=%~dp0Courier-Android-latest.apk"

REM Points at the stable alias, never a version-pinned filename. This script
REM hardcoded Courier-Android-v1.0.0.apk, which stopped existing several
REM releases ago.

if not exist "%APK%" (
    echo.
    echo   ERROR: No Android build found.
    echo   Expected: %APK%
    echo.
    echo   Build one with:  gradlew publishAndroidRelease
    echo.
    pause
    exit /b 1
)

echo Checking for connected Android devices via ADB...
adb devices
echo.
echo Installing Courier-Android-latest.apk...
adb install -r "%APK%"
if %errorlevel% equ 0 (
    echo.
    echo [SUCCESS] Courier has been installed on your Android device!
) else (
    echo.
    echo [NOTE] You can also copy 'Courier-Android-latest.apk' directly to your
    echo        phone storage and open it to install.
)
endlocal
pause
