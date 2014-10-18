@echo off
setlocal
pushd %~dp0

build.bat jarMVStore
rem luajit build_mvstore_src.lua

pause
