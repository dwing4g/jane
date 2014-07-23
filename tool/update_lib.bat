@echo off
setlocal
pushd %~dp0

set MAPDB=..\..\mapdb

copy /y %MAPDB%\src\main\java\org\mapdb\*.java ..\lib\mapdb\org\mapdb\

pause
