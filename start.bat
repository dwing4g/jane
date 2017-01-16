@echo off
setlocal
pushd %~dp0

set JVM=^
-Xms128m ^
-Xmx512m ^
-server ^
-XX:+UseConcMarkSweepGC ^
-Xloggc:log/gc.log ^
-XX:+PrintGCDetails ^
-XX:+PrintGCDateStamps ^
-verbose:gc

rem set JVM=^
rem -Xms128m ^
rem -Xmx512m ^
rem -server ^
rem -XX:+UseG1GC ^
rem -XX:MaxGCPauseMillis=20 ^
rem -Xloggc:log/gc.log ^
rem -XX:+PrintGCDateStamps

set LIB=^
lib/slf4j-api-1.7.22.jar;^
lib/log4j-core-2.7.jar;^
lib/log4j-api-2.7.jar;^
lib/log4j-slf4j-impl-2.7.jar;^
lib/mina-core-2.0.16.jar;^
lib/luaj-jse-2.0.3.jar;^
jane-core.jar;^
jane-test.jar

set MAIN=%1
if "%MAIN%" equ ""  set MAIN=jane.test.TestMain
if "%MAIN%" equ "b" set MAIN=jane.test.TestDBBenchmark

md log 2>nul
md db  2>nul

java %JVM% -cp %LIB%;. %MAIN% %2 %3 %4 %5 %6 %7 %8 %9
