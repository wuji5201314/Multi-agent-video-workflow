@echo off
chcp 936 >nul
echo Stopping services on ports 8010 / 8088 / 5173...
for %%P in (8010 8088 5173) do (
  for /f "tokens=5" %%q in ('netstat -ano ^| findstr /R /C:":%%P .*LISTENING"') do (
    echo   kill port %%P pid %%q
    taskkill /F /PID %%q >nul 2>&1
  )
)
echo Done.