@echo off
setlocal
set "JAVA_CMD=java"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)

"%JAVA_CMD%" --enable-native-access=ALL-UNNAMED "-Djava.net.preferIPv4Stack=true" "-Djava.awt.headless=false" "-Drei.computer-use.diagnostics.enabled=true" -jar "%~dp0target\rei-0.0.1-SNAPSHOT.jar" %*
exit /b %errorlevel%
