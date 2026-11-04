@echo off
chcp 936 >nul
setlocal
set "HERE=%~dp0"
set "PORT=%~1"
if not defined PORT set "PORT=8010"

echo ============================================
echo  Python Rendering Engine  (port %PORT%)
echo ============================================

if not exist "%HERE%.venv\Scripts\python.exe" (
  echo [ERROR] venv not found: %HERE%.venv\Scripts\python.exe
  echo Please run: python -m venv .venv  and install deps first.
  pause
  exit /b 1
)

cd /d "%HERE%"
"%HERE%.venv\Scripts\python.exe" run.py --port %PORT%
endlocal