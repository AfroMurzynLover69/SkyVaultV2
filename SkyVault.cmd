@echo off
REM Nakladka na SkyVault.ps1 - pozwala uruchomic launcher dwuklikiem
REM oraz omija blokady wykonywania skryptow PowerShell.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0SkyVault.ps1" %*

if errorlevel 1 (
    echo.
    echo Launcher zakonczyl prace z bledem.
    pause
)
