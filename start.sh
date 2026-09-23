#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif [ -x /c/Java/jdk-25/bin/java.exe ]; then
    JAVA=/c/Java/jdk-25/bin/java.exe
else
    JAVA=java
fi

exec "$JAVA" \
    "-Djava.net.preferIPv4Stack=true" \
    "-Djava.awt.headless=false" \
    "-Drei.computer-use.diagnostics.enabled=true" \
    -jar "$SCRIPT_DIR/target/rei-0.0.1-SNAPSHOT.jar" "$@"
