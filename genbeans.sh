#!/bin/sh

cd `dirname $0`

LIB=lib/luaj-jse-2.0.3.jar

mkdir -p src/sas/bean 2> /dev/null
mkdir -p src/sas/handler 2> /dev/null
# mkdir -p src/sas/handler/... 2> /dev/null

java -cp $LIB lua genbeans.lua
