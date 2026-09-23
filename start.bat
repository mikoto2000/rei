@echo off
setlocal
"C:\Java\jdk-25\bin\java.exe" "-Djava.net.preferIPv4Stack=true" "-Djava.awt.headless=false" "-Drei.computer-use.diagnostics.enabled=true" -jar "%~dp0target\rei-0.0.1-SNAPSHOT.jar" %*
exit /b %errorlevel%
