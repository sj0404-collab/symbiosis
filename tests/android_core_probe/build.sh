#!/usr/bin/env bash
# Builds an ephemeral test harness in CI, NEVER a user/release APK.
# Uses the actual production C++ bridge + Kotlin callback class and two
# hash-pinned, downloaded ARM64 core binaries. No firmware, keys or games.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${PROBE_WORK:?set PROBE_WORK to a disposable directory}
: "${ANDROID_HOME:?}" "${KOTLINC_HOME:?}"
SDK="$ANDROID_HOME"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
CXX="$SDK/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang++"
mkdir -p "$WORK"/{common/symbiosis,classes,dex,apk/lib/arm64-v8a}
cp "$ROOT/patch/symbiosis/got_patch.h" "$WORK/common/symbiosis/got_patch.h"
curl -fsSL --retry 3 -o "$WORK/core_sym.so" https://github.com/sj0404-collab/symbiosis/releases/download/engine-kenji/libkenji.so
curl -fsSL --retry 3 -o "$WORK/symbek.apk" https://github.com/sj0404-collab/SymbEK/releases/download/v1.0.121/Kenji-Space-1.0.121.apk
SYMBEK_APK="$WORK/symbek.apk" SYMBIOSIS_CORE="$WORK/core_sym.so" python3 "$ROOT/tests/test_symbek_reference.py"
unzip -p "$WORK/symbek.apk" lib/arm64-v8a/libkenjinx.so > "$WORK/core_space.so"
echo 'd781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590  '"$WORK/core_space.so" | sha256sum -c -
cp "$WORK/core_sym.so" "$WORK/apk/lib/arm64-v8a/libcore_sym.so"
cp "$WORK/core_space.so" "$WORK/apk/lib/arm64-v8a/libcore_space.so"
for lib in symbiosis_kenji kenjinxjni; do
  "$CXX" -std=c++17 -O2 -shared -fPIC -static-libstdc++ -Wl,-z,max-page-size=16384 \
    -Wl,-soname,"lib$lib.so" -I"$WORK" \
    "$ROOT/patch/symbiosis/kenji_bridge.cpp" "$ROOT/patch/symbiosis/got_patch.cpp" \
    -llog -ldl -landroid -o "$WORK/apk/lib/arm64-v8a/lib$lib.so"
done
"$KOTLINC_HOME/bin/kotlinc" "$ROOT/patch/android/kenjinx/KenjinxNative.kt" \
  "$ROOT/patch/android/utils/KenjiUiText.kt" \
  "$ROOT/patch/android/utils/KenjiUiCoordinator.kt" \
  "$ROOT/patch/android/activities/KenjiUiPresenter.kt" \
  "$ROOT/tests/android_core_probe/src/dev/symbiosis/coreprobe/UiProbeActivity.kt" \
  -jvm-target 17 -classpath "$ANDROID_JAR" -d "$WORK/classes"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$WORK/classes" \
  "$ROOT/tests/android_core_probe/src/org/yuzu/yuzu_emu/utils/KenjiBridge.java" \
  "$ROOT/tests/android_core_probe/src/dev/symbiosis/coreprobe/ProbeActivity.java"
mapfile -d '' CLASSES < <(find "$WORK/classes" -name '*.class' -print0)
"$BT/d8" --min-api 28 --lib "$ANDROID_JAR" --output "$WORK/dex" \
  "${CLASSES[@]}" "$KOTLINC_HOME/lib/kotlin-stdlib.jar"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$ROOT/tests/android_core_probe/AndroidManifest.xml" \
  -o "$WORK/unsigned.apk"
cp "$WORK/dex/classes.dex" "$WORK/apk/classes.dex"
(cd "$WORK/apk" && zip -q -X -r -9 "$WORK/unsigned.apk" classes.dex lib)
"$BT/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
keytool -genkeypair -keystore "$WORK/debug.keystore" -storepass android -keypass android \
  -alias ci -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Disposable CI probe' >/dev/null 2>&1
"$BT/apksigner" sign --ks "$WORK/debug.keystore" --ks-key-alias ci \
  --ks-pass pass:android --key-pass pass:android --out "$WORK/core-probe.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify "$WORK/core-probe.apk"
echo 'Disposable diagnostic APK built; not a release artifact.'
