#!/bin/sh

cd `dirname $0`

# LIB=lib/luaj-jse-2.0.3.jar

mkdir -p src/jane/bean 2> /dev/null
mkdir -p src/jane/handler 2> /dev/null
# mkdir -p src/jane/handler/... 2> /dev/null

mkdir -p port/csharp/Jane/Bean 2> /dev/null
mkdir -p port/csharp/Jane/Handler 2> /dev/null
# mkdir -p port/csharp/Jane/Handler/... 2> /dev/null

tool/luajit tool/format_allbeans.lua allbeans.lua

# java -cp $LIB lua tool/genbeans.lua jane.bean Server,Client allbeans.lua src
# java -cp $LIB lua tool/genbeans_csharp.lua Jane.Bean ClientCS allbeans.lua port/csharp
# java -cp $LIB lua tool/genbeans_lua.lua Client allbeans.lua port/lua/src

tool/luajit tool/genbeans.lua jane.bean Server,Client allbeans.lua src
tool/luajit tool/genbeans_csharp.lua Jane.Bean ClientCS allbeans.lua port/csharp
tool/luajit tool/genbeans_lua.lua Client allbeans.lua port/lua/src
