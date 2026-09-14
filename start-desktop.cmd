@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
    py -3 -m venv .venv
    if errorlevel 1 exit /b 1
)
.venv\Scripts\python.exe -c "import qrcode" >nul 2>&1
if errorlevel 1 (
    .venv\Scripts\python.exe -m pip install -r desktop\requirements.txt
    if errorlevel 1 exit /b 1
)
.venv\Scripts\python.exe desktop\server.py %*
