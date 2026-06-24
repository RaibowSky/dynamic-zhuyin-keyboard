#!/bin/bash
JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot"
PATH="$JAVA_HOME/bin:$PATH"
exec "$JAVA_HOME/bin/java" -jar "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" "$@"