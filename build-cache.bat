@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0drynor-tools.ps1" cache build "%~dp0..\cache-unpacked" "%~dp0..\cache"
exit /b %errorlevel%
