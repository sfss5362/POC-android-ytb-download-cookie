@echo off
chcp 65001 >nul 2>&1

echo ========================================
echo   YT Downloader Android Build (Release)
echo ========================================
echo.

set "PROJECT_ROOT=%~dp0"
set "DIST_DIR=%PROJECT_ROOT%dist"

echo Project Root: %PROJECT_ROOT%
echo.

:: Check build.gradle
if not exist "%PROJECT_ROOT%app\build.gradle" (
    echo ERROR: app/build.gradle not found!
    goto :end
)

:: Create dist directory
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

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

:: Step 3: Build Release APK
echo [3/4] Building Release APK (this may take several minutes)...
call gradlew.bat assembleRelease
if %errorlevel% neq 0 (
    echo ERROR: Build failed!
    goto :end
)
echo.

:: Step 4: Copy APK
echo [4/4] Copying APK to dist folder...
set "APK_PATH=%PROJECT_ROOT%app\build\outputs\apk\release\app-release-unsigned.apk"
if exist "%APK_PATH%" (
    copy /y "%APK_PATH%" "%DIST_DIR%\ytdownloader-release.apk"
    echo.
    echo ========================================
    echo   Build Success!
    echo ========================================
    echo.
    echo Output: %DIST_DIR%\ytdownloader-release.apk
    echo.
    echo NOTE: This is an unsigned APK.
    echo To install, you need to sign it first or use debug build.
) else (
    echo ERROR: APK not found at %APK_PATH%
)

:end
echo.
echo Press any key to exit...
pause >nul
