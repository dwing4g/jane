@echo off
setlocal
pushd %~dp0

luajit counter.lua ..\src\jane\tool
luajit counter.lua ..\src\jane\core

pause
