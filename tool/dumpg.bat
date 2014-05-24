@echo off
setlocal
pushd %~dp0

luajit dumpg.lua ..\genbeans.lua

luajit dumpg.lua ..\port\csharp\genbeans_csharp.lua

luajit dumpg.lua ..\port\lua\genbeans_lua.lua
luajit dumpg.lua ..\port\lua\util.lua
luajit dumpg.lua ..\port\lua\stream.lua
luajit dumpg.lua ..\port\lua\bean.lua
luajit dumpg.lua ..\port\lua\test.lua

pause
