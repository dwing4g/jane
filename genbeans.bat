@echo off
setlocal
pushd %~dp0

set LIB=lib/luaj-jse-2.0.3.jar

md src\sas\bean 2>nul
md src\sas\handler 2>nul
rem md src\sas\handler\... 2>nul

java -cp %LIB% lua genbeans.lua

pause
