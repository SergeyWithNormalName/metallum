#!/bin/bash
cd "$(dirname "$0")"

if [ -z "$JAVA_HOME" ]; then
    if [ -x "/usr/libexec/java_home" ]; then
        export JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home 2>/dev/null)"
    fi
    if [ -z "$JAVA_HOME" ]; then
        if [ -d "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home" ]; then
            export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
        elif [ -d "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home" ]; then
            export JAVA_HOME="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
        elif [ -d "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home" ]; then
            export JAVA_HOME="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
        fi
    fi
fi
if [ -n "$JAVA_HOME" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "Compiling the mod..."
./gradlew build
echo ""
echo "Compilation finished. Press [Enter] to exit..."
read
