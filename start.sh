#!/bin/bash

cd `dirname $0`

JAVA_VER=`java -version 2>&1 | awk -F "[ \"]" '/version/{print $4}'`
if [[ $JAVA_VER > 1.9 ]] || [[ $JAVA_VER > 10 ]]; then
JVM="\
-Xms512m \
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
else
JVM="\
-Xms512m \
-Xmx512m \
-server \
-XX:+UseConcMarkSweepGC \
-XX:+AggressiveOpts \
-XX:AutoBoxCacheMax=65535 \
-XX:SoftRefLRUPolicyMSPerMB=1000 \
-XX:+HeapDumpOnOutOfMemoryError \
-Xloggc:log/gc.log \
-XX:+PrintGCDetails \
-XX:+PrintGCDateStamps \
-XX:+PrintReferenceGC \
-verbose:gc \
-Dsun.stdout.encoding=utf-8 \
-Dsun.stderr.encoding=utf-8"
fi

# -Djava.rmi.server.hostname=127.0.0.1 \
# -Djava.net.preferIPv4Stack=true \
# -Dcom.sun.management.jmxremote.port=1100 \
# -Dcom.sun.management.jmxremote.rmi.port=1099 \
# -Dcom.sun.management.jmxremote.ssl=false \
# -Dcom.sun.management.jmxremote.local.only=false \
# -Dcom.sun.management.jmxremote.authenticate=true \
# -Dcom.sun.management.jmxremote.password.file=jmxremote.password \
# -Dcom.sun.management.jmxremote.access.file=jmxremote.access"

# -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=1234

LIB="\
lib/slf4j-api-1.7.28.jar:\
lib/logback-core-1.2.3.jar:\
lib/logback-classic-1.2.3.jar:\
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

mv -f log/gc.log log/gc.old.log 2> /dev/null

if [ -z $NOHUP ]; then java $JVM -cp $LIB:. $MAIN ${@:2:9}
else             nohup java $JVM -cp $LIB:. $MAIN removeAppender=ASYNC_STDOUT ${@:3:9} 1>> log/stdout.log 2>> log/stderr.log & fi
