@echo off
setlocal
pushd %~dp0

set NATIVEPATH=lib/native
set PATH=%PATH%;%NATIVEPATH%
set JVM=-Xms512m -Xmx512m -server -XX:+UseConcMarkSweepGC -Xloggc:log/gc.log -XX:+PrintGCTimeStamps -Dlibrary.leveldbjni.path=%NATIVEPATH%
set LIB=lib/slf4j-api-1.7.5.jar;lib/logback-core-1.0.13.jar;lib/logback-classic-1.0.13.jar;lib/mina-core-2.0.7.jar;lib/luaj-jse-2.0.3.jar

set MAIN=%1
if "%MAIN%" equ "" set MAIN=jane.test.TestMain
if "%MAIN%" equ "b" set MAIN=jane.test.TestDBBenchmark

md log 2>nul
md db 2>nul

java %JVM% -cp %LIB%;jane-core.jar;jane-test.jar;. %MAIN% %2 %3 %4 %5 %6 %7 %8 %9
