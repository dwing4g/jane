@echo off
setlocal
pushd %~dp0

luajit fixcodec.lua ..\src
luajit fixcodec.lua ..\port\csharp\jane
luajit fixcodec.lua ..\port\lua\src

pause
