@echo off
chcp 936 >nul
setlocal
set "HERE=%~dp0"

echo ============================================
echo  Frontend  (Vite dev server, port 5173)
echo ============================================

cd /d "%HERE%"

if not exist "node_modules" (
  echo First run, installing deps...
  call npm install
  if errorlevel 1 (
    echo [ERROR] npm install failed
    pause
    exit /b 1
  )
)

call npm run dev
endlocal