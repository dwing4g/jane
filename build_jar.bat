@echo off
setlocal
pushd %~dp0

call ant jar
rem call ant -Dbuild.compiler=org.eclipse.jdt.core.JDTCompilerAdapter

pause
