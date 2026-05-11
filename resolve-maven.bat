@echo off
set "MAVEN_CMD="

for /f "delims=" %%I in ('where mvn.cmd 2^>nul') do (
    set "MAVEN_CMD=%%I"
    exit /b 0
)

for /f "delims=" %%I in ('where mvn 2^>nul') do (
    set "MAVEN_CMD=%%I"
    exit /b 0
)

set "IJ_MAVEN=%ProgramFiles%\JetBrains\IntelliJ IDEA Community Edition 2025.1.3\plugins\maven\lib\maven3\bin\mvn.cmd"
if exist "%IJ_MAVEN%" (
    set "MAVEN_CMD=%IJ_MAVEN%"
    exit /b 0
)

set "IJ_MAVEN=%ProgramFiles%\JetBrains\IntelliJ IDEA 2025.1.3\plugins\maven\lib\maven3\bin\mvn.cmd"
if exist "%IJ_MAVEN%" (
    set "MAVEN_CMD=%IJ_MAVEN%"
    exit /b 0
)

set "NETBEANS_MAVEN=%ProgramFiles%\Apache NetBeans\java\maven\bin\mvn.cmd"
if exist "%NETBEANS_MAVEN%" (
    set "MAVEN_CMD=%NETBEANS_MAVEN%"
    exit /b 0
)

echo Maven nao encontrado.
echo Instale o Maven e coloque no PATH, ou abra o projeto pelo IntelliJ/NetBeans e use o Maven integrado.
echo Se a IDE estiver em outro caminho, edite scripts\resolve-maven.bat.
exit /b 1
