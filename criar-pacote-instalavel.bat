@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

echo.
echo Gerando pacote instalavel/portatil...
echo.

call "%~dp0build.bat" /nopause
if errorlevel 1 (
    echo Build falhou. Pacote nao gerado.
    if /i not "%~1"=="/nopause" pause
    exit /b 1
)

set "DIST=dist\APS-Redes"
if exist "%DIST%" rmdir /s /q "%DIST%"
mkdir "%DIST%"
mkdir "%DIST%\config"
mkdir "%DIST%\database"
mkdir "%DIST%\src"
mkdir "%DIST%\scripts"

xcopy /e /i /y "config" "%DIST%\config" >nul
xcopy /e /i /y "src" "%DIST%\src" >nul
xcopy /e /i /y "database" "%DIST%\database" >nul
xcopy /e /i /y "scripts" "%DIST%\scripts" >nul
copy "pom.xml" "%DIST%\" >nul
copy ".env.example" "%DIST%\" >nul
copy "build.bat" "%DIST%\" >nul
copy "instalar-aplicacao.bat" "%DIST%\" >nul
copy "iniciar-aplicacao.bat" "%DIST%\" >nul
copy "run-server.bat" "%DIST%\" >nul
copy "run-client.bat" "%DIST%\" >nul
copy "setup-mysql.bat" "%DIST%\" >nul
copy "modo-demo.bat" "%DIST%\" >nul
copy "README.md" "%DIST%\" >nul
if exist "target" xcopy /e /i /y "target" "%DIST%\target" >nul
if exist "lib" xcopy /e /i /y "lib" "%DIST%\lib" >nul

powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path '%DIST%' -DestinationPath 'dist\APS-Redes-instalavel.zip' -Force"

echo.
echo Pacote criado em dist\APS-Redes-instalavel.zip
if /i not "%~1"=="/nopause" pause
