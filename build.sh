#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

sh ./mvnw package -DskipTests "$@"
sh ./mvnw -f terminal/pom.xml package -DskipTests "$@"
