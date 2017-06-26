@echo off
setlocal
pushd %~dp0

set LIB=../../lib/luaj-jse-2.0.3.jar

md Jane\Bean 2>nul
md Jane\Handler 2>nul
rem md Jane\Handler\... 2>nul

rem java -cp %LIB% lua genbeans_csharp.lua Jane.Bean ClientCS ../../allbeans.lua
..\..\tool\luajit genbeans_csharp.lua Jane.Bean ClientCS ../../allbeans.lua

pause
