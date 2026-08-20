#!/usr/bin/env sh
set -e
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
exec java $JAVA_OPTS $GRADLE_OPTS -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
