@echo off
setlocal
set "JAVA_CMD=java"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)
if not exist "%~dp0terminal\launcher\target\rei-launcher-0.0.1-SNAPSHOT.jar" (
    echo Build the lightweight client with mvnw.cmd -f terminal/pom.xml package 1>&2
    exit /b 2
)
"%JAVA_CMD%" --enable-native-access=ALL-UNNAMED -jar "%~dp0terminal\launcher\target\rei-launcher-0.0.1-SNAPSHOT.jar" --backend-jar "%~dp0target\rei-0.0.1-SNAPSHOT.jar" %*
exit /b %errorlevel%
