#!/bin/bash

cd `dirname $0`

JVM="\
-Xms128m \
-Xmx512m \
-server \
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=200 \
-XX:AutoBoxCacheMax=65535 \
-XX:SoftRefLRUPolicyMSPerMB=1000 \
-XX:+HeapDumpOnOutOfMemoryError \
-Xlog:gc=info,gc+heap=info:log/gc.log:time \
-Dsun.stdout.encoding=utf-8 \
-Dsun.stderr.encoding=utf-8"

LIB="\
jane-core.jar:\
jane-test.jar"

MAIN=$1
if [ "$MAIN" == "nohup" ]; then NOHUP=nohup; MAIN=$2; else NOHUP=""; fi
MAIN=${MAIN:-jane.test.TestMain}
if [ "$MAIN" == "b" ]; then MAIN=jane.test.TestDBBenchmark; fi

# PID=`jps -l | grep $MAIN | awk '{print $1}'`
PID=`ps x | grep java | grep $MAIN | grep -v grep | awk '{print $1}'`

if [ $PID ]; then
	echo 'server is running already'
	exit 1
fi

mkdir -p log 2> /dev/null
mkdir -p db  2> /dev/null

if [ -z $NOHUP ]; then java $JVM -cp $LIB:. $MAIN ${@:2:9}
else             nohup java $JVM -cp $LIB:. -Dtinylog.writerConsole.level=off $MAIN ${@:3:9} 1>> log/stdout.log 2>> log/stderr.log & fi
