#!/usr/bin/env sh

# Resolving links
PRG="$0"
while [ -h "$PRG" ]; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`/"$link"
    fi
done

SAVED="`pwd`"
CDPATH=""
APP_HOME="`dirname "$PRG"`"
APP_HOME="`cd "$APP_HOME" && pwd`"
cd "$SAVED"

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Execute Gradle
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
