@echo off

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0SkyVault.ps1" %*

if errorlevel 1 (
    echo.
    echo The launcher exited with an error.
    pause
)
