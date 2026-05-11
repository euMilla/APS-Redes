@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

echo.
echo Iniciando cliente JavaFX...
echo.
if not exist "server-storage\logs" mkdir "server-storage\logs"
if not exist "lib\javafx-controls-21.0.5-win.jar" (
    echo JARs centralizados nao encontrados. Preparando pasta lib...
    call "%~dp0scripts\centralizar-jars.bat"
)
if not exist "target\classes\aps" (
    echo Arquivos compilados nao encontrados. Compilando aplicacao...
    call "%~dp0build.bat" /nopause
    if errorlevel 1 exit /b 1
)
if not exist "lib\javafx-controls-21.0.5-win.jar" (
    echo Dependencias nao encontradas em lib. Compilando aplicacao...
    call "%~dp0build.bat" /nopause
    if errorlevel 1 exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Get-NetTCPConnection -LocalPort 5050 -State Listen -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
if errorlevel 1 (
    echo Servidor local nao encontrado na porta 5050. Abrindo servidor...
    start "APS Redes - Servidor" "%ComSpec%" /k call "%~dp0run-server.bat"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline=(Get-Date).AddSeconds(12); do { if (Get-NetTCPConnection -LocalPort 5050 -State Listen -ErrorAction SilentlyContinue) { exit 0 }; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $deadline); exit 1"
)
set "CLIENT_NAME=%~1"
if "%CLIENT_NAME%"=="" set "CLIENT_NAME=Inspetor APS"
for /f "delims=" %%I in ('powershell -NoProfile -Command "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.PrefixOrigin -ne 'WellKnown' } | Select-Object -First 1 -ExpandProperty IPAddress"') do set "APS_HOST=%%I"
if "%APS_HOST%"=="" set "APS_HOST=127.0.0.1"
set "LIB=%cd%\lib"
set "MODULE_PATH=%LIB%\javafx-base-21.0.5-win.jar;%LIB%\javafx-graphics-21.0.5-win.jar;%LIB%\javafx-controls-21.0.5-win.jar;%LIB%\javafx-fxml-21.0.5-win.jar;%LIB%\javafx-swing-21.0.5-win.jar"
set "CP=target\classes"
set "CP=%CP%;%LIB%\webcam-capture-0.3.12.jar"
set "CP=%CP%;%LIB%\bridj-0.7.0.jar"
set "CP=%CP%;%LIB%\slf4j-api-1.7.36.jar"
set "CP=%CP%;%LIB%\slf4j-simple-2.0.16.jar"
set "INSTANCE_HOME=%cd%\server-storage\instances\client-home-%random%-%random%"
mkdir "%INSTANCE_HOME%" 2>nul
echo Perfil isolado desta janela: %INSTANCE_HOME%
echo Nome sugerido: %CLIENT_NAME%
echo Endereco IP usado no login: %APS_HOST%
java --enable-native-access=javafx.graphics,ALL-UNNAMED -DAPS_PROFILE_NAME="%CLIENT_NAME%" -DAPS_HOST="%APS_HOST%" -Duser.home="%INSTANCE_HOME%" -Djava.util.prefs.userRoot="%INSTANCE_HOME%\prefs" --module-path "%MODULE_PATH%" --add-modules javafx.controls,javafx.fxml,javafx.swing -cp "%CP%" aps.client.ApsClientApp 2>>"server-storage\logs\client-error.log"
set "STATUS=%ERRORLEVEL%"

echo.
echo Cliente finalizado.
pause
exit /b %STATUS%
