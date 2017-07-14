@echo off
setlocal
pushd %~dp0

luajit fixcodec.lua *.java       ..\
luajit fixcodec.lua *.cs         ..\
luajit fixcodec.lua *.lua        ..\
luajit fixcodec.lua *.sh         ..\
luajit fixcodec.lua *.properties ..\

pause
