@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul 2>&1

title BYD Logger - Release Build

:: ================================================================
::  KONFIGURACE - upravte pokud se zmenila umisteni
:: ================================================================
set "JAVA_HOME=D:\Android\AndroidStudio\jbr"
set "KEY_ALIAS=byd-logger"

:: Cesta k projektu = slozka tohoto .bat souboru
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

set "KEYSTORE_DIR=%PROJECT_DIR%\keystore"
set "KEYSTORE_FILE=%KEYSTORE_DIR%\release.keystore"
set "LOCAL_PROPS=%PROJECT_DIR%\local.properties"
set "AAB_FILE=%PROJECT_DIR%\app\build\outputs\bundle\release\app-release.aab"

:: Auto-detect Gradle 8.4 v cache (nevyzaduje globalni instalaci)
set "GRADLE="
for /d %%D in ("%USERPROFILE%\.gradle\wrapper\dists\gradle-8.4-bin\*") do (
    if exist "%%D\gradle-8.4\bin\gradle.bat" set "GRADLE=%%D\gradle-8.4\bin\gradle.bat"
)

:: ================================================================
echo.
echo  ================================================
echo   BYD Logger - Release Build pro Google Play
echo  ================================================
echo.

:: ================================================================
:: [1/4] KONTROLA NASTROJU
:: ================================================================
echo  [1/4] Kontrola nastroju...

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo.
    echo  [CHYBA] Java nenalezena v: %JAVA_HOME%
    echo  Zkontrolujte promennou JAVA_HOME na zacatku tohoto souboru.
    goto :error
)

if "%GRADLE%"=="" (
    echo.
    echo  [CHYBA] Gradle 8.4 nenalezen.
    echo  Spustte nejprve build v Android Studiu - stahne Gradle automaticky.
    goto :error
)

echo        Java  : %JAVA_HOME%
echo        Gradle: %GRADLE%
echo.

:: ================================================================
:: [2/4] KEYSTORE A PODPISOVA KONFIGURACE
:: ================================================================
echo  [2/4] Kontrola keystoru a podpisovych udaju...

if not exist "%KEYSTORE_FILE%" (
    echo.
    echo  Keystore neexistuje - prvni spusteni.
    echo  Vytvarim novy podpisovy klic pro Google Play.
    echo.
    echo  POZOR: Zazalohujte keystore soubor a hesla na bezpecne misto!
    echo  Bez nich NELZE aktualizovat aplikaci na Google Play.
    echo.

    if not exist "%KEYSTORE_DIR%" mkdir "%KEYSTORE_DIR%"

    :: Vyzvat k zadani hesel (bez maskovani - standardni omezeni .bat)
    set /p "STORE_PASS=  Heslo pro keystore (min. 6 znaku): "
    if "!STORE_PASS!"=="" ( echo  Heslo nesmi byt prazdne. & goto :error )

    set /p "KEY_PASS=  Heslo pro klic      (min. 6 znaku): "
    if "!KEY_PASS!"=="" ( echo  Heslo nesmi byt prazdne. & goto :error )
    echo.

    echo  Generuji RSA 2048-bit klic (platnost 27 let)...
    "%JAVA_HOME%\bin\keytool.exe" -genkeypair ^
        -keystore "%KEYSTORE_FILE%" ^
        -alias "%KEY_ALIAS%" ^
        -keyalg RSA -keysize 2048 -validity 10000 ^
        -storepass "!STORE_PASS!" ^
        -keypass "!KEY_PASS!" ^
        -dname "CN=Developer, OU=Personal, O=Personal, L=CZ, ST=CZ, C=CZ" ^
        -noprompt

    if errorlevel 1 (
        echo.
        echo  [CHYBA] Vytvoreni keystoru selhalo.
        echo  Heslo musi mit alespon 6 znaku. Vyhybejte se znakum: ^& ^| ^< ^> ^^
        goto :error
    )

    call :save_credentials "!STORE_PASS!" "!KEY_PASS!"

    echo.
    echo  ================================================
    echo   KEYSTORE VYTVOREN - ULOZIT NA BEZPECNE MISTO
    echo  ================================================
    echo   Soubor : %KEYSTORE_FILE%
    echo   Heslo  : !STORE_PASS!
    echo   Klic   : !KEY_PASS!
    echo  ================================================
    echo.
    echo  Stisknete Enter pro pokracovani...
    pause > nul

) else (
    :: Keystore existuje - overit ze jsou hesla v local.properties
    powershell -NoProfile -Command ^
        "if (-not (Select-String -Path '%LOCAL_PROPS%' -Pattern '^keystore\.store\.password' -Quiet)) { exit 1 }" > nul 2>&1

    if errorlevel 1 (
        echo.
        echo  Keystore nalezen ale hesla chybi v local.properties.
        set /p "STORE_PASS=  Zadejte heslo pro keystore: "
        set /p "KEY_PASS=  Zadejte heslo pro klic:    "
        echo.
        call :save_credentials "!STORE_PASS!" "!KEY_PASS!"
        echo        Hesla ulozena do local.properties.
    ) else (
        echo        OK - keystore i udaje nalezeny.
    )
)
echo.

:: ================================================================
:: [3/4] BUILD
:: ================================================================
echo  [3/4] Spoustim gradle bundleRelease...
echo        (prvni build muze trvat 2-5 minut)
echo.

cd /d "%PROJECT_DIR%"
call "%GRADLE%" bundleRelease

if errorlevel 1 (
    echo.
    echo  [CHYBA] Build selhal. Viz chybovy vystup vyse.
    goto :error
)

:: ================================================================
:: [4/4] VYSLEDEK
:: ================================================================
echo.
echo  [4/4] Kontrola vystupu...

if not exist "%AAB_FILE%" (
    echo.
    echo  [CHYBA] AAB soubor nenalezen na ocekavane ceste:
    echo    %AAB_FILE%
    goto :error
)

for %%F in ("%AAB_FILE%") do set "AAB_BYTES=%%~zF"
set /a "AAB_MB=!AAB_BYTES! / 1048576"
set /a "AAB_KB=(!AAB_BYTES! - !AAB_MB! * 1048576) / 1024"

echo.
echo  ================================================
echo   BUILD USPESNY
echo  ================================================
echo.
echo   Soubor  : app\build\outputs\bundle\release\
echo              app-release.aab
echo   Velikost: !AAB_MB! MB
echo.
echo   Jak nahrat na Google Play:
echo    1. Otevrit play.google.com/console
echo    2. Aplikace - Vase aplikace - Produkce
echo    3. Kliknout "Vytvorit novou verzi"
echo    4. Nahrat app-release.aab
echo    5. Vyplnit poznamky k verzi - Ulozit a odeslat
echo.

:: Otevrit Explorer a oznacit AAB soubor
explorer /select,"%AAB_FILE%"

goto :end

:: ================================================================
:: POMOCNA FUNKCE: ulozit hesla do local.properties
:: ================================================================
:save_credentials
set "_SP=%~1"
set "_KP=%~2"

:: Pouzit PowerShell s env promennou pro bezpecny prenos hesel
set "PS_STORE_PASS=!_SP!"
set "PS_KEY_PASS=!_KP!"
set "PS_ALIAS=%KEY_ALIAS%"
set "PS_PROPS=%LOCAL_PROPS%"

powershell -NoProfile -Command ^
    "$f = $env:PS_PROPS; ^
     $alias = $env:PS_ALIAS; ^
     $sp = $env:PS_STORE_PASS; ^
     $kp = $env:PS_KEY_PASS; ^
     $lines = (Get-Content $f) | Where-Object { $_ -notmatch '^keystore\.' }; ^
     $lines += 'keystore.path=keystore/release.keystore'; ^
     $lines += \"keystore.alias=$alias\"; ^
     $lines += \"keystore.store.password=$sp\"; ^
     $lines += \"keystore.key.password=$kp\"; ^
     $lines | Set-Content $f"

exit /b 0

:: ================================================================
:error
echo.
echo  ================================================
echo   BUILD SELHAL
echo  ================================================
echo.
pause
exit /b 1

:end
echo  Stisknete libovolnou klavesu...
pause > nul
exit /b 0
