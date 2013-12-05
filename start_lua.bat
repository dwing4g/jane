@echo off
setlocal
pushd %~dp0

start.bat lua -i src\sas\test\TestLuaClient.lua
