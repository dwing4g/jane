@echo off
setlocal
pushd %~dp0

md log 2>nul
md db 2>nul

start.bat lua -i src\sas\test\TestLuaClient.lua
