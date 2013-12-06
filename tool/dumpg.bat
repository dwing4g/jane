@echo off
setlocal
pushd %~dp0

luajit dumpg.lua ..\genbeans.lua

pause
