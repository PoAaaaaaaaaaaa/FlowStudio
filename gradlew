#!/bin/sh
# Gradle 启动脚本。CI 上直接调用；本机没装 gradle 时用 tools/build_apk.sh。
set -e
APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ]; then JAVACMD=$JAVA_HOME/bin/java; else JAVACMD=java; fi
if [ -f "$CLASSPATH" ]; then
  exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
else
  echo "缺少 gradle/wrapper/gradle-wrapper.jar" >&2
  exit 1
fi
