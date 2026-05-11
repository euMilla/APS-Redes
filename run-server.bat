@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

echo.
echo Iniciando servidor central na porta 5050...
echo Mantenha esta janela aberta enquanto os clientes estiverem conectados.
echo.
if not exist "server-storage\logs" mkdir "server-storage\logs"
if not exist "lib\mysql-connector-j-8.4.0.jar" (
    echo JARs centralizados nao encontrados. Preparando pasta lib...
    call "%~dp0scripts\centralizar-jars.bat"
)
if not exist "target\classes\aps" (
    echo Arquivos compilados nao encontrados. Compilando aplicacao...
    call "%~dp0build.bat" /nopause
    if errorlevel 1 exit /b 1
)
if not exist "lib\mysql-connector-j-8.4.0.jar" (
    echo Dependencias nao encontradas em lib. Compilando aplicacao...
    call "%~dp0build.bat" /nopause
    if errorlevel 1 exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ports=@(5050,5051); $ids=Get-NetTCPConnection -LocalPort $ports -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; foreach($id in $ids){ $p=Get-Process -Id $id -ErrorAction SilentlyContinue; if($p -and $p.ProcessName -eq 'java'){ Write-Host ('Encerrando servidor Java antigo na porta 5050/5051. PID: ' + $id); Stop-Process -Id $id -Force } }"
set "LIB=%cd%\lib"
set "CP=target\classes"
set "CP=%CP%;%LIB%\mysql-connector-j-8.4.0.jar"
set "CP=%CP%;%LIB%\protobuf-java-3.25.1.jar"
java -cp "%CP%" aps.server.CentralServerApp 2>>"server-storage\logs\server-error.log"
set "STATUS=%ERRORLEVEL%"

echo.
echo Servidor finalizado.
pause
exit /b %STATUS%
