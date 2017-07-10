@echo off
setlocal
pushd %~dp0

rem set LIB=lib/luaj-jse-2.0.3.jar

md src\jane\bean 2>nul
md src\jane\handler 2>nul
rem md src\jane\handler\... 2>nul

md port\csharp\Jane\Bean 2>nul
md port\csharp\Jane\Handler 2>nul
rem md port\csharp\Jane\Handler\... 2>nul

tool\luajit.exe tool\format_allbeans.lua allbeans.lua

rem java -cp %LIB% lua tool\genbeans.lua jane.bean Server,Client allbeans.lua src
rem java -cp %LIB% lua tool\genbeans_csharp.lua Jane.Bean ClientCS allbeans.lua port/csharp
rem java -cp %LIB% lua tool\genbeans_lua.lua Client allbeans.lua port/lua/src

tool\luajit.exe tool\genbeans.lua jane.bean Server,Client allbeans.lua src
tool\luajit.exe tool\genbeans_csharp.lua Jane.Bean ClientCS allbeans.lua port/csharp
tool\luajit.exe tool\genbeans_lua.lua Client allbeans.lua port/lua/src

tool\luajit.exe tool\sync_vsproj.lua port/csharp/jane_csharp.csproj port\csharp

pause
