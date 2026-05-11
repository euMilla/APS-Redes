@echo off
setlocal
cd /d "%~dp0"

call "%~dp0scripts\resolve-maven.bat"
if errorlevel 1 (
    if /i not "%~1"=="/nopause" pause
    exit /b 1
)

echo.
echo Compilando o projeto...
call "%MAVEN_CMD%" "-Dmaven.repo.local=.m2-repository" clean package
set "STATUS=%ERRORLEVEL%"

echo.
if "%STATUS%"=="0" (
    call "%~dp0scripts\centralizar-jars.bat"
    if errorlevel 1 (
        echo Falha ao centralizar os JARs na pasta lib.
        if /i not "%~1"=="/nopause" pause
        exit /b 1
    )
    echo Build concluido com sucesso.
    echo JARs centralizados em lib\.
    if /i not "%~1"=="/nopause" (
        echo.
        echo Abrindo a aplicacao...
        call "%~dp0iniciar-aplicacao.bat"
        exit /b %STATUS%
    )
) else (
    echo Build falhou. Verifique as mensagens acima.
)
if /i not "%~1"=="/nopause" pause
exit /b %STATUS%
