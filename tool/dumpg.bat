@echo off
setlocal
pushd %~dp0

luajit dumpg.lua ..\genbeans.lua
luajit dumpg.lua csharp\genbeans_csharp.lua

pause
