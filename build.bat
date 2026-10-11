@echo off
setlocal
pushd "%~dp0"
if errorlevel 1 exit /b %errorlevel%

call mvnw.cmd package -DskipTests %*
if errorlevel 1 goto failed

call mvnw.cmd -f terminal\pom.xml package -DskipTests %*
if errorlevel 1 goto failed

popd
exit /b 0

:failed
set "REI_BUILD_EXIT_CODE=%errorlevel%"
popd
exit /b %REI_BUILD_EXIT_CODE%
