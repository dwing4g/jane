#!/bin/sh

cd `dirname $0`

JVM="-Xms512m -Xmx512m -server -XX:+UseConcMarkSweepGC -Xloggc:log/gc.log -XX:+PrintGCTimeStamps -Dfile.encoding=UTF-8"
LIB="lib/slf4j-api-1.7.6.jar:lib/log4j-core-2.0-rc1.jar:lib/log4j-api-2.0-rc1.jar:lib/log4j-slf4j-impl-2.0-rc1.jar:lib/mina-core-2.0.7.jar:lib/luaj-jse-2.0.3.jar"

MAIN=$1
if [ "$MAIN" == "nohup" ]; then NOHUP="nohup"; MAIN=$2; fi
if [ "$MAIN" == ""      ]; then MAIN=jane.test.TestMain; fi
if [ "$MAIN" == "b"     ]; then MAIN=jane.test.TestDBBenchmark; fi

mkdir -p log 2> /dev/null
mkdir -p db  2> /dev/null

if [ "$NOHUP" == "" ]; then java $JVM -cp $LIB:jane-core.jar:jane-test.jar:. $MAIN ${@:2:9}
else                  nohup java $JVM -cp $LIB:jane-core.jar:jane-test.jar:. $MAIN ${@:3:9} 1>> log/stdout.log 2>> log/stderr.log & fi
