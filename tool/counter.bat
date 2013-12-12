@echo off
setlocal
pushd %~dp0

luajit counter.lua ..\src\sas\core

pause
