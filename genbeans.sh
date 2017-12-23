#!/bin/sh

cd `dirname $0`

if [ `uname -s` == Darwin ]; then
	LUAJIT=tool/luajit_mac
else
	LUAJIT=tool/luajit
fi

# LIB=lib/luaj-jse-2.0.3.jar

mkdir -p src/jane/bean 2> /dev/null
mkdir -p src/jane/handler 2> /dev/null
# mkdir -p src/jane/handler/... 2> /dev/null

mkdir -p port/csharp/Jane/Bean 2> /dev/null
mkdir -p port/csharp/Jane/Handler 2> /dev/null
# mkdir -p port/csharp/Jane/Handler/... 2> /dev/null

$LUAJIT tool/format_allbeans.lua allbeans.lua

# java -cp $LIB lua tool/genbeans.lua jane.bean Server,Client allbeans.lua src
# java -cp $LIB lua tool/genbeans_csharp.lua Jane.Bean ClientCS allbeans.lua port/csharp
# java -cp $LIB lua tool/genbeans_lua.lua Client allbeans.lua port/lua/src

$LUAJIT tool/genbeans.lua jane.bean Server,Client allbeans.lua src
$LUAJIT tool/genbeans_csharp.lua Jane.Bean ClientCS allbeans.lua port/csharp
$LUAJIT tool/genbeans_lua.lua Client allbeans.lua port/lua/src
