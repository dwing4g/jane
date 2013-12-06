@echo off
setlocal
pushd %~dp0

luajit fixcodec.lua ..\src

pause
