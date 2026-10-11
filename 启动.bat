@echo off
rem Litematic Render desktop launcher (Electron shell + Java engine).
setlocal
chcp 65001 >nul
set "DIR=%~dp0"

set "ELECTRON=%DIR%webapp\node_modules\electron\dist\electron.exe"
if not exist "%ELECTRON%" (
    echo [ERROR] Electron not found:
    echo         %ELECTRON%
    echo Run install.bat first to fetch runtime dependencies.
    pause
    exit /b 1
)

rem Electron refuses to create windows while this is inherited from a parent shell. Some tooling
rem (including the harness this project is developed in) exports ELECTRON_RUN_AS_NODE globally, which
rem makes require('electron') resolve as plain Node and crash the main process with
rem "Cannot read properties of undefined (reading 'handle')" before the engine ever starts.
set "ELECTRON_RUN_AS_NODE="

rem /wait keeps this console attached, so a main-process crash prints its stack here instead of
rem vanishing; start without /wait would swallow it and leave only an empty log file.
start /wait "" "%ELECTRON%" "%DIR%webapp"
if errorlevel 1 (
    echo.
    echo [ERROR] The app exited with code %errorlevel%. The stack trace above, if any, names the cause.
    pause
)
endlocal