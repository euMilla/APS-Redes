@echo off
setlocal
cd /d "%~dp0"

echo Abrindo APS Redes em janelas separadas...
echo.
if not exist "target\classes\aps" (
    echo Arquivos compilados nao encontrados. Compilando a aplicacao...
    call "%~dp0build.bat" /nopause
    if errorlevel 1 (
        echo.
        echo Build falhou. Corrija as mensagens acima antes de abrir o sistema.
        pause
        exit /b 1
    )
) else (
    echo Aplicacao compilada encontrada. Abrindo sem recompilar.
)

echo.
echo 1. Servidor sera aberto em uma janela propria.
echo 2. Dois clientes serao abertos para teste com contas diferentes.
echo.

start "APS Redes - Servidor" "%ComSpec%" /k call "%~dp0run-server.bat"
echo Aguardando servidor ficar pronto na porta 5050...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline=(Get-Date).AddSeconds(12); do { if (Get-NetTCPConnection -LocalPort 5050 -State Listen -ErrorAction SilentlyContinue) { exit 0 }; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $deadline); exit 1"
if errorlevel 1 (
    echo Aviso: servidor ainda nao respondeu na porta 5050. Os clientes podem pedir nova tentativa.
)
start "APS Redes - Cliente 1" "%ComSpec%" /k call "%~dp0run-client.bat" "membro-demo"
timeout /t 1 /nobreak >nul
start "APS Redes - Cliente 2" "%ComSpec%" /k call "%~dp0run-client.bat" "inspetor-demo"

echo Pronto. Use as janelas que abriram.
echo Senha membro: membro123
echo Senha admin: admin123
echo Usuarios demo: membro-demo, inspetor-demo, coordenador-demo
for /f "delims=" %%I in ('powershell -NoProfile -Command "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.PrefixOrigin -ne 'WellKnown' } | Select-Object -First 1 -ExpandProperty IPAddress"') do set "LAN_IP=%%I"
if not defined LAN_IP set "LAN_IP=127.0.0.1"
echo Host padrao no cliente: %LAN_IP%
echo Host para outras maquinas na rede: %LAN_IP%
echo Para abrir outra conta neste computador, execute: run-client.bat coordenador-demo
pause
