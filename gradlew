#!/bin/sh
# Local build launcher (uses cached Gradle 8.14.2 + JDK 17)
export JAVA_HOME=/home/gopal/jdk17
GRADLE_BIN=$(echo "$HOME/.gradle/wrapper/dists/gradle-8.14.2-all/"*/gradle-8.14.2/bin/gradle)
exec "$GRADLE_BIN" "$@"
