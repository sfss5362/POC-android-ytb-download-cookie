@echo off
chcp 65001 >nul 2>&1

echo ========================================
echo   YT Downloader Debug Build and Install
echo ========================================
echo.

set "PROJECT_ROOT=%~dp0"

echo Project Root: %PROJECT_ROOT%
echo.

:: Check build.gradle
if not exist "%PROJECT_ROOT%app\build.gradle" (
    echo ERROR: app/build.gradle not found!
    goto :end
)

:: Step 1: Check Java
echo [1/4] Checking Java...
java -version
if %errorlevel% neq 0 (
    echo ERROR: Java not found!
    goto :end
)
echo.

:: Step 2: Clean build cache
echo [2/4] Cleaning build cache...
cd /d "%PROJECT_ROOT%"
call gradlew.bat clean
if %errorlevel% neq 0 (
    echo WARNING: Clean failed, continuing anyway...
)
echo.

:: Step 3: Build Debug APK
echo [3/4] Building Debug APK...
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo ERROR: Build failed!
    goto :end
)
echo.

:: Step 4: Install APK
set "APK_PATH=%PROJECT_ROOT%app\build\outputs\apk\debug\app-debug.apk"
if exist "%APK_PATH%" (
    echo ========================================
    echo   Build Success! Installing...
    echo ========================================
    echo.
    echo APK Path: %APK_PATH%
    echo Installing to device...
    echo.

    call adb install -r "%APK_PATH%"
    if %errorlevel% equ 0 (
        echo.
        echo ========================================
        echo   Install Success!
        echo ========================================
        echo.
        echo The app has been installed on your device.
        echo You can now launch it from your phone.
    ) else (
        echo.
        echo ========================================
        echo   Install Failed!
        echo ========================================
        echo.
        echo The APK was built but installation failed.
        echo Please check:
        echo   1. USB debugging is enabled
        echo   2. Device is connected via USB
        echo   3. You allowed the installation on your device
        echo.
        echo APK location: %APK_PATH%
        echo You can manually install it using: adb install -r "%APK_PATH%"
    )
) else (
    echo ERROR: APK not found at %APK_PATH%
)

:end
echo.
echo Press any key to exit...
pause >nul
