@echo off
rem Build Chunk Collector and install it into the normal RuneLite client
rem (loads from .runelite\sideloaded-plugins; requires --developer-mode
rem in RuneLite (configure) -> Client arguments)
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-11.0.31.11-hotspot"
cd /d "%~dp0"
call gradlew.bat jar
if errorlevel 1 (
	echo BUILD FAILED - plugin not deployed
	pause
	exit /b 1
)
if not exist "%USERPROFILE%\.runelite\sideloaded-plugins" mkdir "%USERPROFILE%\.runelite\sideloaded-plugins"
copy /y "build\libs\chunk-tcg.jar" "%USERPROFILE%\.runelite\sideloaded-plugins\chunk-tcg.jar"
echo.
echo Deployed to sideloaded-plugins. Restart RuneLite to load the new version.
pause
