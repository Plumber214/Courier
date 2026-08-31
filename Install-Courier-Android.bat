@echo off
title Install Courier on Android
echo Checking for connected Android devices via ADB...
adb devices
echo Installing Courier-Android-v1.0.0.apk...
adb install -r "%~dp0release\Courier-Android-v1.0.0.apk"
if %errorlevel% equ 0 (
    echo.
    echo [SUCCESS] Courier has been installed on your Android device!
) else (
    echo.
    echo [NOTE] You can also copy 'release\Courier-Android-v1.0.0.apk' directly to your phone storage and open it to install.
)
pause
