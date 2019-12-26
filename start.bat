@echo off
setlocal
pushd %~dp0

for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%~g
if %JAVA_VER% GEQ 1.9 (
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
) else (
set JVM=^
-Xms128m ^
-Xmx512m ^
-server ^
-XX:+UseConcMarkSweepGC ^
-XX:+AggressiveOpts ^
-XX:AutoBoxCacheMax=65535 ^
-XX:SoftRefLRUPolicyMSPerMB=1000 ^
-XX:+HeapDumpOnOutOfMemoryError ^
-Xloggc:log/gc.log ^
-XX:+PrintGCDetails ^
-XX:+PrintGCDateStamps ^
-XX:+PrintReferenceGC ^
-verbose:gc ^
-Dsun.stdout.encoding=gbk ^
-Dsun.stderr.encoding=gbk
)

rem -Djava.rmi.server.hostname=127.0.0.1 ^
rem -Djava.net.preferIPv4Stack=true ^
rem -Dcom.sun.management.jmxremote.port=1100 ^
rem -Dcom.sun.management.jmxremote.rmi.port=1099 ^
rem -Dcom.sun.management.jmxremote.ssl=false ^
rem -Dcom.sun.management.jmxremote.local.only=false ^
rem -Dcom.sun.management.jmxremote.authenticate=true ^
rem -Dcom.sun.management.jmxremote.password.file=jmxremote.password ^
rem -Dcom.sun.management.jmxremote.access.file=jmxremote.access

rem -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=1234

set LIB=^
lib/slf4j-api-1.7.30.jar;^
lib/logback-core-1.2.3.jar;^
lib/logback-classic-1.2.3.jar;^
jane-core.jar;^
jane-test.jar

set MAIN=%1
if "%MAIN%" equ ""  set MAIN=jane.test.TestMain
if "%MAIN%" equ "b" set MAIN=jane.test.TestDBBenchmark

md log 2>nul
md db  2>nul

move /y log\gc.log log\gc.old.log 1>nul 2>nul

java %JVM% -cp %LIB%;. %MAIN% %2 %3 %4 %5 %6 %7 %8 %9
