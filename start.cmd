@echo off
chcp 65001 >nul
setlocal
set ROOT=%~dp0
set PORT=8010

echo [1/3] 检查端口 %PORT% ...
netstat -ano | findstr /R /C:":%PORT% .*LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo       端口 %PORT% 已有服务在跑，直接打开页面。
  start "" http://127.0.0.1:%PORT%/
  goto :eof
)

echo [2/3] 启动后端 ...
cd /d "%ROOT%backend-python"
start "yq-studio" /min "%ROOT%backend-python\.venv\Scripts\python.exe" scripts\serve.py --port %PORT%

echo [3/3] 等待就绪 ...
for /l %%i in (1,1,30) do (
  timeout /t 1 /nobreak >nul
  curl.exe -s -o NUL --noproxy "*" --max-time 2 http://127.0.0.1:%PORT%/api/health && goto :ready
)
echo       启动超时，请查看 backend-python 下的报错。
pause
goto :eof

:ready
echo       已就绪：http://127.0.0.1:%PORT%/
start "" http://127.0.0.1:%PORT%/
endlocal
