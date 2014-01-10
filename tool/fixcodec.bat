@echo off
setlocal
pushd %~dp0

luajit fixcodec.lua ..\src
luajit fixcodec.lua csharp\jane

pause
