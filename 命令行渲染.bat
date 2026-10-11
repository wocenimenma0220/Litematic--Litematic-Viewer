@echo off
rem Command-line render: this.bat <file.litematic> [out.png] [w] [h]
setlocal
chcp 65001 >nul
set "DIR=%~dp0"

set "JAVA_EXE=%DIR%runtime\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=%DIR%..\..\jdk-25.0.4.1+1\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"

"%JAVA_EXE%" -Xmx4g -Dfile.encoding=UTF-8 "-Dlitematicrender.home=%DIR%" -cp "%DIR%bin" io.github.litematicrender.Main --render %*
if errorlevel 1 pause
endlocal