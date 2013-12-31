@echo off
setlocal
pushd %~dp0

md log 2>nul
md db 2>nul

start.bat lua -i src\jane\test\TestLuaClient.lua
