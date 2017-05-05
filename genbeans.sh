#!/bin/sh

cd `dirname $0`

LIB=lib/luaj-jse-2.0.3.jar

mkdir -p src/jane/bean 2> /dev/null
mkdir -p src/jane/handler 2> /dev/null
# mkdir -p src/jane/handler/... 2> /dev/null

# java -cp $LIB lua genbeans.lua jane.bean Server,Client allbeans.lua src
tool/luajit genbeans.lua jane.bean Server,Client allbeans.lua src
