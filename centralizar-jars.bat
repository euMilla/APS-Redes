@echo off
setlocal
cd /d "%~dp0.."

if not exist "lib" mkdir "lib"

if exist ".m2-repository" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$lib=Join-Path (Get-Location) 'lib'; Get-ChildItem -LiteralPath '.m2-repository' -Recurse -Filter '*.jar' -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $lib -Force }"
    if errorlevel 1 exit /b 1
)

if exist "target" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$lib=Join-Path (Get-Location) 'lib'; Get-ChildItem -LiteralPath 'target' -Filter '*.jar' -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $lib -Force }"
    if errorlevel 1 exit /b 1
)

exit /b 0
