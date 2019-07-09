#!/bin/bash

cd `dirname $0`

if [[ $1 == -* ]]; then SIG=$1; shift; else SIG=-15; fi
MAIN=${1:-jane.test.TestMain}

# PID=`jps -l | grep $MAIN | awk '{print $1}'`
PID=`ps x | grep java | grep $MAIN | grep -v grep | awk '{print $1}'`

if [ -z $PID ]; then
	echo 'server is not running'
else
	kill $SIG $PID
	echo 'server has been stopped'
fi
