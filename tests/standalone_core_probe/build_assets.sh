#!/usr/bin/env bash
# CI-only ARM64 Linux/Bionic experiment assets; no release APK is produced.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${ANDROID_HOME:?}" "${KOTLINC_HOME:?}" "${STANDALONE_WORK:?}"
W="$STANDALONE_WORK"
mkdir -p "$W"/{common/symbiosis,classes,dex}
CXX="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android27-clang++"
BT="$ANDROID_HOME/build-tools/35.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
cp "$ROOT/patch/symbiosis/got_patch.h" "$W/common/symbiosis/got_patch.h"
"$CXX" -std=c++17 -Wall -Wextra -Werror -O2 -static-libstdc++ -Wl,-z,max-page-size=16384 \
  "$ROOT/tests/standalone_core_probe/probe.cpp" -ldl -o "$W/standalone-core-probe"
"$CXX" -std=c++17 -Wall -Wextra -Werror -O2 -static-libstdc++ -Wl,-z,max-page-size=16384 \
  "$ROOT/tests/standalone_core_probe/property_init.cpp" -ldl -o "$W/property-init"
"$CXX" -std=c++17 -Wall -Wextra -Werror -O2 -static-libstdc++ -Wl,-z,max-page-size=16384 \
  "$ROOT/tests/standalone_core_probe/tls_handshake.cpp" -ldl -o "$W/tls-handshake-probe"
"$CXX" -std=c++17 -Wall -Wextra -Werror -O2 -shared -fPIC -static-libstdc++ -Wl,-z,max-page-size=16384 \
  "$ROOT/tests/standalone_core_probe/jni.cpp" -ldl -o "$W/libstandalonejni.so"
# Production bridge, unchanged; still contains explicitly release-blocked callbacks.
"$CXX" -std=c++17 -O2 -shared -fPIC -static-libstdc++ -Wl,-z,max-page-size=16384 \
  -Wl,-soname,libkenjinxjni.so -I"$W" \
  "$ROOT/patch/symbiosis/kenji_bridge.cpp" "$ROOT/patch/symbiosis/got_patch.cpp" \
  -llog -ldl -landroid -o "$W/libkenjinxjni.so"
"$KOTLINC_HOME/bin/kotlinc" "$ROOT/patch/android/kenjinx/KenjinxNative.kt" \
  -jvm-target 17 -classpath "$ANDROID_JAR" -d "$W/classes"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$W/classes" \
  "$ROOT/tests/standalone_core_probe/Main.java"
mapfile -d '' CLASSES < <(find "$W/classes" -name '*.class' -print0)
"$BT/d8" --min-api 27 --lib "$ANDROID_JAR" --output "$W/dex" \
  "${CLASSES[@]}" "$KOTLINC_HOME/lib/kotlin-stdlib.jar"
(cd "$W/dex" && zip -q -X "$W/standalone.jar" classes.dex)
file "$W/standalone-core-probe" "$W/libstandalonejni.so" "$W/standalone.jar"
