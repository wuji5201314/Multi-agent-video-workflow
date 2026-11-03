@echo off
chcp 936 >nul
setlocal
set "HERE=%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=D:\jdk17"
set "JAVABIN=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVABIN%" set "JAVABIN=java"

echo ============================================
echo  Java Backend  (MavwApplication, port 8088)
echo ============================================

cd /d "%HERE%"

set "JAR=%HERE%target\mavw-standalone-backend-0.1.0.jar"

if exist "%JAR%" goto runjar

echo No prebuilt jar found, building with Maven...
set "MVN="
where mvn >nul 2>&1
if not errorlevel 1 set "MVN=mvn"
if not defined MVN if exist "D:\maven\apache-maven-3.8.9\bin\mvn.cmd" set "MVN=D:\maven\apache-maven-3.8.9\bin\mvn.cmd"
if not defined MVN goto nomvn
call "%MVN%" -B -DskipTests package
if not exist "%JAR%" goto buildfail

:runjar
echo Running: %JAR%
echo JAVA: %JAVABIN%
"%JAVABIN%" -jar "%JAR%"
goto end

:nomvn
echo [ERROR] mvn not found and no jar. Install Maven or run MavwApplication in IDEA.
pause
exit /b 1

:buildfail
echo [ERROR] build failed.
pause
exit /b 1

:end
endlocal