# Shared by hook scripts. Gradle needs JDK 17+, and the system java on this Mac is 1.8.
if [ -z "$JAVA_HOME" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(1[7-9]|2[0-9])'; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
