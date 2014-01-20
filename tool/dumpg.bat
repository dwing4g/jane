@echo off
setlocal
pushd %~dp0

luajit dumpg.lua ..\genbeans.lua
luajit dumpg.lua ..\port\csharp\genbeans_csharp.lua

pause
