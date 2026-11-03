@echo off
chcp 936 >nul
setlocal
set "HERE=%~dp0"

echo ============================================
echo  前端 (Vite dev server, port 5173)
echo ============================================

cd /d "%HERE%"

if not exist "node_modules" (
  echo 首次运行，安装依赖...
  call npm install
  if errorlevel 1 (
    echo [ERROR] npm install 失败
    pause
    exit /b 1
  )
)

call npm run dev
endlocal