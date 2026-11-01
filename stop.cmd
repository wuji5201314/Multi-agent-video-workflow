@echo off
chcp 65001 >nul
echo 停止 8010 端口的服务 ...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /R /C:":8010 .*LISTENING"') do (
  echo   结束 PID %%p
  taskkill /F /PID %%p >nul 2>&1
)
echo 完成。
