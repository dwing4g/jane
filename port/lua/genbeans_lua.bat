@echo off
setlocal
pushd %~dp0

set LIB=../../lib/luaj-jse-2.0.3.jar

rem java -cp %LIB% lua genbeans_lua.lua
..\..\tool\luajit genbeans_lua.lua Client ../../allbeans.lua

pause
