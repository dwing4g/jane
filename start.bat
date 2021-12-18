@echo off
setlocal
pushd %~dp0

set JVM=^
-Xms128m ^
-Xmx512m ^
-server ^
-XX:+UseG1GC ^
-XX:MaxGCPauseMillis=200 ^
-XX:AutoBoxCacheMax=65535 ^
-XX:SoftRefLRUPolicyMSPerMB=1000 ^
-XX:+HeapDumpOnOutOfMemoryError ^
-Xlog:gc=info,gc+heap=info:log/gc.log:time ^
-Dsun.stdout.encoding=gbk ^
-Dsun.stderr.encoding=gbk

set LIB=^
jane-core.jar;^
jane-test.jar

set MAIN=%1
if "%MAIN%" equ ""  set MAIN=jane.test.TestMain
if "%MAIN%" equ "b" set MAIN=jane.test.TestDBBenchmark

md log 2>nul
md db  2>nul

java %JVM% -cp %LIB%;. %MAIN% %2 %3 %4 %5 %6 %7 %8 %9
