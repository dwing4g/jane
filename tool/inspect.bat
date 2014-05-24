@echo off
setlocal
pushd %~dp0

luajit inspect.lua ..\genbeans.lua

luajit inspect.lua ..\port\csharp\genbeans_csharp.lua

luajit inspect.lua ..\port\lua\genbeans_lua.lua
luajit inspect.lua ..\port\lua\util.lua
luajit inspect.lua ..\port\lua\stream.lua
luajit inspect.lua ..\port\lua\bean.lua
luajit inspect.lua ..\port\lua\test.lua

pause
