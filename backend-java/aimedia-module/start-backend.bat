@echo off
chcp 936 >nul
setlocal
set "HERE=%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=D:\jdk17"
set "JAVABIN=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVABIN%" set "JAVABIN=java"

echo ============================================
echo  aimedia 后端启动器 (MavwApplication, port 8088)
echo ============================================

cd /d "%HERE%"

set "JAR=%HERE%target\aimedia-launcher.jar"
if exist "%JAR%" goto runjar

echo 未找到 %JAR%，开始用 Maven 构建 (profile=launcher)...
set "MVN="
where mvn >nul 2>&1
if not errorlevel 1 set "MVN=mvn"
if not defined MVN if exist "D:\maven\apache-maven-3.8.9\bin\mvn.cmd" set "MVN=D:\maven\apache-maven-3.8.9\bin\mvn.cmd"
if not defined MVN goto nomvn
call "%MVN%" -B -Plauncher -DskipTests package
if not exist "%JAR%" goto buildfail

:runjar
echo 启动: %JAR%
echo 请先确保 Python 引擎已启动 (backend-python: python run.py --port 8010)
"%JAVABIN%" -jar "%JAR%"
goto end

:nomvn
echo [ERROR] 找不到 mvn，且没有已构建 jar。请装 Maven 或在 IDEA 里运行 MavwApplication。
pause
exit /b 1

:buildfail
echo [ERROR] 构建失败。
pause
exit /b 1

:end
endlocal