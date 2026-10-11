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

rem Electron refuses to create windows while this is inherited from a parent shell.
set "ELECTRON_RUN_AS_NODE="

start "" "%ELECTRON%" "%DIR%webapp"
endlocal