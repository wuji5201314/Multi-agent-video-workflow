@echo off
chcp 936 >nul
setlocal
set "HERE=%~dp0"
set "ROOT=%HERE%.."

echo ============================================================
echo  Multi-Agent Video Workflow - Start All
echo    Python :8010  -^>  Java :8088  -^>  Frontend :5173
echo ============================================================

echo.
echo [1/3] Starting Python engine (:8010)...
start "MAVW-Python" cmd /k ""%ROOT%\backend-python\start.bat" 8010"

echo       waiting for Python...
for /l %%i in (1,1,30) do (
  timeout /t 1 /nobreak >nul
  curl.exe -s -o NUL --noproxy "*" --max-time 2 http://127.0.0.1:8010/api/health && goto py_ready
)
echo       [WARN] Python not ready in 30s, check Python window.
:py_ready

echo.
echo [2/3] Starting Java backend (:8088)...
start "MAVW-Java" cmd /k ""%HERE%backend\start-backend.bat""

echo       waiting for Java...
for /l %%i in (1,1,60) do (
  timeout /t 1 /nobreak >nul
  curl.exe -s -o NUL --noproxy "*" --max-time 2 http://127.0.0.1:8088/api/health && goto java_ready
)
echo       [WARN] Java not ready in 60s, check Java window (first run may download deps).
:java_ready

echo.
echo [3/3] Starting frontend (:5173)...
start "MAVW-Frontend" cmd /k ""%HERE%frontend\start-frontend.bat""

echo.
echo       waiting for frontend...
for /l %%i in (1,1,40) do (
  timeout /t 1 /nobreak >nul
  curl.exe -s -o NUL --noproxy "*" --max-time 2 http://127.0.0.1:5173/ && goto fe_ready
)
echo       [WARN] Frontend not ready in 40s, check frontend window.
:fe_ready

echo.
echo ============================================================
echo  Started. Open in browser:  http://127.0.0.1:5173/
echo ============================================================
start "" http://127.0.0.1:5173/
endlocal