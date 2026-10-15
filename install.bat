@echo off
rem Fetches the runtime dependencies the desktop shell needs (Electron).
rem Only needed once, and only if you want to run from source; the packaged
rem green zip already contains everything.
setlocal
chcp 65001 >nul
set "DIR=%~dp0"

set "NODE=%DIR%tools\node\node.exe"
set "NPM=%DIR%tools\node\npm.cmd"
if not exist "%NODE%" set "NODE=node"
if not exist "%NPM%" set "NPM=npm"

echo Installing Electron into webapp\ ...
pushd "%DIR%webapp"
set "ELECTRON_MIRROR=https://cdn.npmmirror.com/binaries/electron/"
set "npm_config_registry=https://registry.npmmirror.com/"
call "%NPM%" install --no-audit --no-fund
set "RC=%ERRORLEVEL%"
popd

if not "%RC%"=="0" (
    echo [ERROR] npm install failed.
    pause
    exit /b 1
)
echo Done. Double-click LitematicRender.exe in the project root to start the app.
pause
endlocal