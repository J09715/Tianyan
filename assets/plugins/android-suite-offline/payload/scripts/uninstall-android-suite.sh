#!/bin/sh
set -eu

TOOL_DIR="${TIANYAN_TOOL_DIR:?missing TIANYAN_TOOL_DIR}"
rm -f /opt/tianyan/bin/java /opt/tianyan/bin/javac /opt/tianyan/bin/gradle /opt/tianyan/bin/cmake /opt/tianyan/bin/ninja /opt/tianyan/bin/flutter /opt/tianyan/bin/dart
rm -rf "$TOOL_DIR"
rm -rf /opt/android-sdk /opt/gradle-8.14.2 /opt/tianyan/toolchains/android /opt/flutter
rm -f /root/.gradle/init.d/tianyan-android-ndk.gradle
