@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul 2>&1

title BYD Logger - Testy

:: ================================================================
::  Spusti testy projektu.
::
::  [1] Unit testy (vypocty) - bezi na PC, zadne zarizeni netreba.
::  [2] Migracni testy databaze - POTREBUJI pripojeny telefon nebo emulator.
::      Testuji, ze upgrade schematu neztrati data. Spustit VZDY pred
::      instalaci nove verze na telefon s realnymi daty.
::
::      POZOR: gradle connectedAndroidTest po dobehnuti testovanou aplikaci
::      ODINSTALUJE a odinstalace maze jeji data. Debug build proto bezi jako
::      samostatny balicek com.byd.charging.debug (applicationIdSuffix v
::      build.gradle) - testy se dotknou jen jeho, ostra aplikace
::      com.byd.charging zustava netknuta. Tenhle suffix NIKDY neodstranovat.
:: ================================================================

set "JAVA_HOME=D:\Android\AndroidStudio\jbr"

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

:: Auto-detect Gradle 8.4 v cache
set "GRADLE="
for /d %%D in ("%USERPROFILE%\.gradle\wrapper\dists\gradle-8.4-bin\*") do (
    if exist "%%D\gradle-8.4\bin\gradle.bat" set "GRADLE=%%D\gradle-8.4\bin\gradle.bat"
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo  [CHYBA] Java nenalezena v: %JAVA_HOME%
    goto :error
)
if "%GRADLE%"=="" (
    echo  [CHYBA] Gradle 8.4 nenalezen. Spustte nejprve build v Android Studiu.
    goto :error
)

cd /d "%PROJECT_DIR%"

echo.
echo  ================================================
echo   [1/2] Unit testy (vypocty spotreby a baterie)
echo  ================================================
echo.

call "%GRADLE%" :app:testDebugUnitTest --console=plain
if errorlevel 1 (
    echo.
    echo  [CHYBA] Unit testy selhaly. Report:
    echo    app\build\reports\tests\testDebugUnitTest\index.html
    goto :error
)

echo.
echo  ================================================
echo   [2/2] Migracni testy databaze
echo  ================================================
echo.

:: Zjistit, zda je pripojene zarizeni
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

set "DEVICE_COUNT=0"
for /f "skip=1 tokens=2" %%S in ('"%ADB%" devices 2^>nul') do (
    if "%%S"=="device" set /a DEVICE_COUNT+=1
)

if "!DEVICE_COUNT!"=="0" (
    echo  [PRESKOCENO] Zadne zarizeni neni pripojene.
    echo.
    echo  Migracni testy overuji, ze upgrade databaze v5 - v6 zachova vase data.
    echo  Pripojte telefon s povolenym ladenim pres USB a spustte tento skript znovu.
    echo  Teprve potom instalujte novou verzi aplikace.
    echo.
    goto :end
)

echo  Nalezeno zarizeni: !DEVICE_COUNT!
echo.

call "%GRADLE%" :app:connectedDebugAndroidTest --console=plain
if errorlevel 1 (
    echo.
    echo  [CHYBA] Migracni testy selhaly - NEINSTALUJTE novou verzi. Report:
    echo    app\build\reports\androidTests\connected\index.html
    goto :error
)

echo.
echo  ================================================
echo   VSECHNY TESTY PROSLY
echo  ================================================
echo   Migrace databaze v5 - v6 zachova stavajici data.
echo   Muzete spustit build_release.bat a nainstalovat novou verzi.
echo.

goto :end

:error
echo.
echo  ================================================
echo   TESTY SELHALY
echo  ================================================
echo.
pause
exit /b 1

:end
echo  Stisknete libovolnou klavesu...
pause > nul
exit /b 0
