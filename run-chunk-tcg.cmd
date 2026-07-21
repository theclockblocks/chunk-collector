@echo off
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-11.0.31.11-hotspot"
cd /d "%~dp0"
call gradlew.bat run
pause
