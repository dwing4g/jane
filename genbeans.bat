@echo off
setlocal
pushd %~dp0

set LIB=lib/luaj-jse-2.0.3.jar

md src\jane\bean 2>nul
md src\jane\handler 2>nul
rem md src\jane\handler\... 2>nul

rem java -cp %LIB% lua genbeans.lua
tool\luajit genbeans.lua

pause
