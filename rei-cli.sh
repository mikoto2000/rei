#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
LAUNCHER="$SCRIPT_DIR/terminal/launcher/target/rei-launcher-0.0.1-SNAPSHOT.jar"
if [ ! -f "$LAUNCHER" ]; then
    echo 'Build the lightweight client with ./mvnw -f terminal/pom.xml package' >&2
    exit 2
fi
exec "$JAVA" --enable-native-access=ALL-UNNAMED -jar "$LAUNCHER" --backend-jar "$SCRIPT_DIR/target/rei-0.0.1-SNAPSHOT.jar" "$@"
