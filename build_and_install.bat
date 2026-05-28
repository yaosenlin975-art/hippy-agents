@echo off
setlocal enabledelayedexpansion

set "PROJECT_DIR=%~dp0"
set "LOG_FILE=%PROJECT_DIR%docs\build-logs.txt"
set "APK_PATH=%PROJECT_DIR%app\build\outputs\apk\debug\app-debug.apk"
set "PACKAGE_NAME=com.lin.hippyagent"
set "ACTIVITY_NAME=com.lin.hippyagent.ui.MainActivity"

cd /d "%PROJECT_DIR%"

echo ============================================
echo   HippyAgent Build and Install
echo ============================================
echo.

:retry_build
echo [1/3] Building Debug APK...
echo Building... (log: docs/build-logs.txt)
call gradlew.bat assembleDebug > "%LOG_FILE%" 2>&1
if !errorlevel! neq 0 (
    echo.
    echo [ERROR] Build failed! Check docs/build-logs.txt
    echo Press any key to retry, close window to cancel...
    pause >nul
    goto retry_build
)

if not exist "%APK_PATH%" (
    echo.
    echo [ERROR] APK not found: %APK_PATH%
    echo Press any key to retry build...
    pause >nul
    goto retry_build
)

echo.
:check_adb
echo [2/3] Checking ADB device...
adb devices 2>nul | findstr /r "device$" >nul
if !errorlevel! neq 0 (
    echo [ERROR] No ADB device detected.
    echo   1. ADB installed and in PATH?
    echo   2. Device connected with USB debugging?
    echo Press any key to retry, close window to cancel...
    pause >nul
    goto check_adb
)

:retry_install
echo [3/3] Installing APK...
adb install -r "%APK_PATH%"
if !errorlevel! neq 0 (
    echo.
    echo [WARN] Install failed, try uninstall then reinstall...
    adb uninstall %PACKAGE_NAME%
    echo [RETRY] Reinstalling APK...
    adb install "%APK_PATH%"
    if !errorlevel! neq 0 (
        echo.
        echo [ERROR] Install still failed.
        echo Press any key to retry install, close to cancel...
        pause >nul
        goto retry_install
    )
)

echo.
echo [DONE] Launching app...
adb shell am start -n %PACKAGE_NAME%/%ACTIVITY_NAME%

echo.
echo ============================================
echo   Build and Install Complete!
echo ============================================
echo Window will close in 5 seconds...
timeout /t 5 /nobreak >nul