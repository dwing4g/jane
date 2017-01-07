@echo off
setlocal
pushd %~dp0

dotnet new
del Program.cs 2> nul
dotnet restore
dotnet build
rem dotnet publish

rem del project.json
rem del project.lock.json

pause
