@echo off
setlocal
pushd %~dp0

md bin 2> nul
cd src
for %%a in (*.lua) do luac -s -o ..\bin\%%~na.lc %%a & luajit -b %%a ..\bin\%%~na.lj

dir ..\bin\*.*

pause
