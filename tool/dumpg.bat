@echo off
setlocal
pushd %~dp0

luajit dumpg.lua genbeans.lua
luajit dumpg.lua genbeans_csharp.lua
luajit dumpg.lua genbeans_lua.lua
luajit dumpg.lua format_allbeans.lua
luajit dumpg.lua sync_vsproj.lua
luajit dumpg.lua dumpg.lua
luajit dumpg.lua counter.lua
luajit dumpg.lua fixcodec.lua
luajit dumpg.lua inspect.lua

luajit dumpg.lua ..\allbeans.lua

luajit dumpg.lua ..\port\lua\src\platform.lua
luajit dumpg.lua ..\port\lua\src\util.lua
luajit dumpg.lua ..\port\lua\src\queue.lua
luajit dumpg.lua ..\port\lua\src\stream.lua
luajit dumpg.lua ..\port\lua\src\rc4.lua
luajit dumpg.lua ..\port\lua\src\bean.lua
luajit dumpg.lua ..\port\lua\src\network.lua
luajit dumpg.lua ..\port\lua\src\network_test.lua
luajit dumpg.lua ..\port\lua\src\test.lua

pause
