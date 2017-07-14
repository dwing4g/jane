@echo off
setlocal
pushd %~dp0

luajit inspect.lua genbeans.lua
luajit inspect.lua genbeans_csharp.lua
luajit inspect.lua genbeans_lua.lua

luajit inspect.lua ..\port\lua\src\platform.lua
luajit inspect.lua ..\port\lua\src\util.lua
luajit inspect.lua ..\port\lua\src\queue.lua
luajit inspect.lua ..\port\lua\src\stream.lua
luajit inspect.lua ..\port\lua\src\rc4.lua
luajit inspect.lua ..\port\lua\src\bean.lua
luajit inspect.lua ..\port\lua\src\network.lua
luajit inspect.lua ..\port\lua\src\network_test.lua
luajit inspect.lua ..\port\lua\src\test.lua

pause
