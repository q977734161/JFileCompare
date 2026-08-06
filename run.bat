@echo off
setlocal
cd /d "%~dp0"
call mvn -q compile
if errorlevel 1 (
  echo Maven build failed.
  exit /b 1
)
java -cp "target\classes;lib\*" FileCompareTool
