@echo off
setlocal
pushd %~dp0

set LIB=../../lib/luaj-jse-2.0.3.jar

md jane\bean 2>nul
md jane\handler 2>nul
rem md jane\handler\... 2>nul

rem java -cp %LIB% lua genbeans_csharp.lua Jane ClientCS ../../allbeans.lua
..\..\tool\luajit genbeans_csharp.lua Jane ClientCS ../../allbeans.lua

pause
