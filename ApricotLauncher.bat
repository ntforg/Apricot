@echo off
setlocal EnableExtensions
rem ============================================================
rem  Apricot Launcher
rem  Downloads the Apricot client from GitHub, keeps it up to
rem  date, and launches it. Put this file in its own folder;
rem  the client is installed into a "Apricot" subfolder.
rem ============================================================
set "REPO=ntforg/Apricot"
set "INSTALLDIR=%~dp0Apricot"

echo Apricot Launcher
echo ================

rem Look up the latest release tag on GitHub.
set "LATEST="
for /f "usebackq delims=" %%v in (`powershell -NoProfile -Command "try { (Invoke-RestMethod 'https://api.github.com/repos/%REPO%/releases/latest').tag_name } catch {}"`) do set "LATEST=%%v"

set "CURRENT="
if exist "%INSTALLDIR%\launcher-version.txt" set /p CURRENT=<"%INSTALLDIR%\launcher-version.txt"

if not defined LATEST (
    if exist "%INSTALLDIR%\Play.bat" (
        echo Could not reach GitHub; launching the installed client ^(%CURRENT%^).
        goto launch
    )
    echo Could not reach GitHub, and no client is installed yet.
    echo Check your internet connection and try again.
    pause
    exit /b 1
)

if "%CURRENT%"=="%LATEST%" (
    echo Client is up to date ^(%CURRENT%^).
    goto launch
)

echo Downloading Apricot %LATEST%...
set "ZIP=%TEMP%\Apricot-%LATEST%-windows-x64.zip"
curl -f -# -L -o "%ZIP%" "https://github.com/%REPO%/releases/download/%LATEST%/Apricot-%LATEST%-windows-x64.zip"
if errorlevel 1 (
    echo Download failed.
    if exist "%INSTALLDIR%\Play.bat" (
        echo Launching the installed client ^(%CURRENT%^) instead.
        goto launch
    )
    pause
    exit /b 1
)

echo Extracting...
if not exist "%INSTALLDIR%" mkdir "%INSTALLDIR%"
tar -xf "%ZIP%" -C "%INSTALLDIR%"
if errorlevel 1 (
    echo Extraction failed.
    del "%ZIP%" 2>nul
    pause
    exit /b 1
)
del "%ZIP%" 2>nul
(echo %LATEST%)>"%INSTALLDIR%\launcher-version.txt"
echo Updated to %LATEST%.

:launch
cd /d "%INSTALLDIR%"
call "%INSTALLDIR%\Play.bat"
endlocal
