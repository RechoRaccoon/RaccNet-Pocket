#!/bin/sh
# Gradle start up script for UN*X
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
