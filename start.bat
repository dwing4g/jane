@echo off
setlocal
pushd %~dp0

set JVM=-Xms64m -Xmx512m -server -XX:+UseConcMarkSweepGC -Xloggc:log/gc.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps -verbose:gc
set LIB=lib/slf4j-api-1.7.7.jar;lib/log4j-core-2.0.1.jar;lib/log4j-api-2.0.1.jar;lib/log4j-slf4j-impl-2.0.1.jar;lib/mina-core-2.0-head-20140512.jar;lib/luaj-jse-2.0.3.jar;lib/h2-1.4.180.jar

set MAIN=%1
if "%MAIN%" equ ""  set MAIN=jane.test.TestMain
if "%MAIN%" equ "b" set MAIN=jane.test.TestDBBenchmark

md log 2>nul
md db  2>nul

java %JVM% -cp %LIB%;jane-core.jar;jane-test.jar;. %MAIN% %2 %3 %4 %5 %6 %7 %8 %9
