#!/usr/bin/env bash
# Builds a native Windows executable with GraalVM native-image:
#
#   GRAALVM_HOME=/c/devtools/graalvm-community-openjdk-21.0.2+13.1 native-image/build.sh
#
# Needs GraalVM for JDK 21 and Visual Studio 2022 Build Tools (C++ workload; native-image
# finds them on its own). Output: build/native/RacingSim.exe plus the DLLs native-image
# copies next to it (AWT), zipped to build/native/RacingSim-windows-x64.zip.
#
# native-image/traced holds the reflection / JNI / resource configuration recorded by the
# tracing agent during a scripted run (RACINGSIM_MOVIE) of the JVM build:
#   $GRAALVM_HOME/bin/java -agentlib:native-image-agent=config-output-dir=native-image/traced \
#       -cp "build/install/racing-sim/lib/*" com.racingsim.app.Main
# native-image/extra adds every game asset and the Windows native libraries (LWJGL, Bullet),
# so screens the scripted run never opened still find their files.
# native-image/awt holds the JNI configuration of AWT's screen-device lookup, recorded from a tiny
# probe (GraphicsEnvironment.getDefaultScreenDevice().getDefaultConfiguration()). The font cache's
# clean-up thread (Java2D Disposer) takes that path when it frees glyph caches, which the game's
# traced runs do not reliably hit; without it the thread dies on a missing JNI field.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM for JDK 21}"
CONSOLE="${CONSOLE:-0}"     # CONSOLE=1 keeps a console window (log output) for debugging

./gradlew -q installDist
rm -rf build/native && mkdir -p build/native

# Only the Windows natives (the other platforms' jars just add size)
CP=$(ls build/install/racing-sim/lib/*.jar | grep -v -E 'natives-(linux|macos)|natives-windows-(x86|arm64)|Libbulletjme-(Linux|MacOSX)' | tr '\n' ';')

LINK=()
if [ "$CONSOLE" = "0" ]; then
    LINK=(-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS -H:NativeLinkerOption=/ENTRY:mainCRTStartup)
fi

"$GRAALVM_HOME/bin/native-image.cmd" \
    -cp "$CP" \
    -H:+UnlockExperimentalVMOptions \
    -H:ConfigurationFileDirectories=native-image/traced,native-image/extra,native-image/awt \
    -H:-UnlockExperimentalVMOptions \
    --no-fallback \
    -march=compatibility \
    -Djava.awt.headless=false \
    -R:MaxHeapSize=2g \
    "${LINK[@]}" \
    -o build/native/RacingSim \
    com.racingsim.app.Main

cp LICENSE README.md build/native/
# AWT font configuration (Main points java.home at the executable's folder)
mkdir -p build/native/lib && cp "$GRAALVM_HOME/lib/fontconfig.bfc" build/native/lib/
(cd build/native && rm -f RacingSim-windows-x64.zip && \
    /c/Windows/System32/tar.exe -a -c -f RacingSim-windows-x64.zip RacingSim.exe *.dll lib LICENSE README.md)
ls -la build/native
