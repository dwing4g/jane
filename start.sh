#!/bin/sh

cd `dirname $0`

JVM="\
-Xms512m \
-Xmx512m \
-server \
-XX:+UseConcMarkSweepGC \
-Xloggc:log/gc.log \
-XX:+PrintGCDetails \
-XX:+PrintGCDateStamps \
-verbose:gc \
-Dsun.stdout.encoding=utf-8 \
-Dsun.stderr.encoding=utf-8"

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

# JVM="\
# -Xms512m \
# -Xmx512m \
# -server \
# -XX:+UseG1GC \
# -XX:MaxGCPauseMillis=20 \
# -Xloggc:log/gc.log \
# -XX:+PrintGCDateStamps \
# -Dsun.stdout.encoding=utf-8 \
# -Dsun.stderr.encoding=utf-8"

LIB="\
lib/slf4j-api-1.7.25.jar:\
lib/logback-core-1.2.3.jar:\
lib/logback-classic-1.2.3.jar:\
lib/mina-core-2.0.16.jar:\
lib/luaj-jse-2.0.3.jar:\
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

mv -f log/gc.log log/gc.old.log

if [ -z $NOHUP ]; then java $JVM -cp $LIB:. $MAIN ${@:2:9}
else             nohup java $JVM -cp $LIB:. $MAIN removeAppender=STDOUT ${@:3:9} 1>> log/stdout.log 2>> log/stderr.log & fi
