@echo off
setlocal
pushd %~dp0

md bin 2> nul
cd ..\..\tool
for %%a in (..\port\lua\src\*.lua) do luajit.exe -b %%a ..\port\lua\bin\%%~na.lj

dir ..\port\lua\bin\*.*

pause
