#!/bin/bash
# --------------------------------------------
# Hafen launcher script for Linux/macOS
# Equivalent to the Windows .bat version
# --------------------------------------------

dir="$(cd "$(dirname "$0")" && pwd)"

# Use the bundled Java runtime if present, otherwise fall back to system Java.
if [ -x "$dir/jre/bin/java" ]; then
    JAVA="$dir/jre/bin/java"
else
    JAVA=java
fi

# Optional: print where Java is being run from
echo "Launching Hafen..."
echo "Using Java from: $JAVA"
echo

# Run the Java application
"$JAVA" \
  -Dsun.java2d.uiScale.enabled=false \
  -Dsun.java2d.win.uiScaleX=1.0 \
  -Dsun.java2d.win.uiScaleY=1.0 \
  -Xss8m \
  -Xms1024m \
  -Xmx4096m \
  --add-exports java.base/java.lang=ALL-UNNAMED \
  --add-exports java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
  -DrunningThroughSteam=false \
  -jar hafen.jar "$@"
