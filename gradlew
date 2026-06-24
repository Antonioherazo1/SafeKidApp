#!/bin/sh

#
# Gradle start up script for POSIX
#

# Attempt to set APP_HOME
APP_HOME=$( cd "${0%[/\\]*}" > /dev/null && pwd -P ) || exit

APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD=$JAVA_HOME/bin/java
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory"
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1 ; then
        die "ERROR: JAVA_HOME is not set and no 'java' command found"
    fi
fi

exec "$JAVACMD" \
    $DEFAULT_JVM_OPTS \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    -Dorg.gradle.appname="$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
