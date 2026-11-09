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
# API 27 is allowed only for the experimental software-ARM test harness; it
# is not a supported API level for the application or a release configuration.
PROBE_MIN_API=${PROBE_MIN_API:-28}
case "$PROBE_MIN_API" in 27|28) ;; *) echo 'unsupported probe API' >&2; exit 1 ;; esac
CXX="$SDK/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${PROBE_MIN_API}-clang++"
mkdir -p "$WORK"/{common/symbiosis,classes,dex,apk/lib/arm64-v8a}
MANIFEST="$ROOT/tests/android_core_probe/AndroidManifest.xml"
if [ "$PROBE_MIN_API" = 27 ]; then
  sed 's/android:minSdkVersion="28"/android:minSdkVersion="27"/' "$MANIFEST" > "$WORK/AndroidManifest.xml"
  grep -q 'android:minSdkVersion="27"' "$WORK/AndroidManifest.xml"
  MANIFEST="$WORK/AndroidManifest.xml"
fi
cp "$ROOT/patch/symbiosis/got_patch.h" "$WORK/common/symbiosis/got_patch.h"
curl -fsSL --retry 3 -o "$WORK/core_sym.so" https://github.com/sj0404-collab/symbiosis/releases/download/engine-kenji/libkenji.so
curl -fsSL --retry 3 -o "$WORK/symbek.apk" https://github.com/sj0404-collab/SymbEK/releases/download/v1.0.121/Kenji-Space-1.0.121.apk
SYMBEK_APK="$WORK/symbek.apk" SYMBIOSIS_CORE="$WORK/core_sym.so" python3 "$ROOT/tests/test_symbek_reference.py"
SYMBEK_APK="$WORK/symbek.apk" SYMBIOSIS_CORE="$WORK/core_sym.so" python3 "$ROOT/tests/test_kenji_isa_reference.py"
SYMBEK_APK="$WORK/symbek.apk" python3 "$ROOT/tests/test_symbek_graphics_dex.py"
unzip -p "$WORK/symbek.apk" lib/arm64-v8a/libkenjinx.so > "$WORK/core_space.so"
echo 'd781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590  '"$WORK/core_space.so" | sha256sum -c -
cp "$WORK/core_sym.so" "$WORK/apk/lib/arm64-v8a/libcore_sym.so"
cp "$WORK/core_space.so" "$WORK/apk/lib/arm64-v8a/libcore_space.so"
# Match the production runtime's bundled TLS dependencies. The real cores can
# fail at a later stage without these even if CPU validation succeeds.
cp "$ROOT/patch/libs/arm64-v8a/libcrypto.so" "$ROOT/patch/libs/arm64-v8a/libssl.so" "$WORK/apk/lib/arm64-v8a/"
# Reference JNI and its C++ runtime stay exclusively in the disposable test APK.
unzip -p "$WORK/symbek.apk" lib/arm64-v8a/libkenjinxjni.so > "$WORK/apk/lib/arm64-v8a/libreference_kenji_jni.so"
echo '71de6400f5f8e2e9cba272405431e7cc497bd96631449dd3e7af35a6618e18a8  '"$WORK/apk/lib/arm64-v8a/libreference_kenji_jni.so" | sha256sum -c -
unzip -p "$WORK/symbek.apk" lib/arm64-v8a/libc++_shared.so > "$WORK/apk/lib/arm64-v8a/libc++_shared.so"
# Genuine, pinned SymbEK OpenAL for this diagnostic only. The release workflow
# builds its own OpenAL; success here does not establish binary identity.
unzip -p "$WORK/symbek.apk" lib/arm64-v8a/libopenal.so > "$WORK/apk/lib/arm64-v8a/libopenal.so"
echo 'd63ccb1227308b331fd11bc5041671c3ac2842484779e309acd1bb9f3bf061a4  '"$WORK/apk/lib/arm64-v8a/libopenal.so" | sha256sum -c -
for lib in symbiosis_kenji kenjinxjni; do
  "$CXX" -std=c++17 -O2 -shared -fPIC -static-libstdc++ -Wl,-z,max-page-size=16384 \
    -Wl,-soname,"lib$lib.so" -I"$WORK" \
    "$ROOT/patch/symbiosis/kenji_bridge.cpp" "$ROOT/patch/symbiosis/got_patch.cpp" \
    -llog -ldl -landroid -o "$WORK/apk/lib/arm64-v8a/lib$lib.so"
done
# Synthetic test fixture is packaged only into this disposable probe APK.
"$CXX" -std=c++17 -O2 -shared -fPIC -static-libstdc++ -Wl,-z,max-page-size=16384 \
  -Wl,-soname,libcore_mock.so "$ROOT/tests/android_core_probe/mock_core.cpp" \
  -llog -o "$WORK/apk/lib/arm64-v8a/libcore_mock.so"
# Independent native command-line loader; runs from adb shell without any APK.
"$CXX" -std=c++17 -Wall -Wextra -Werror -O2 -static-libstdc++ -Wl,-z,max-page-size=16384 \
  "$ROOT/tests/standalone_core_probe/probe.cpp" -ldl -o "$WORK/standalone-core-probe"
"$CXX" -std=c++17 -Wall -Wextra -Werror -O2 -shared -fPIC -static-libstdc++ -Wl,-z,max-page-size=16384 \
  "$ROOT/tests/standalone_core_probe/jni.cpp" -ldl -o "$WORK/libstandalonejni.so"
"$CXX" -std=c++17 -O2 -shared -fPIC -static-libstdc++ -Wl,-z,max-page-size=16384 \
  -Wl,-soname,libgraphics_reference_probe.so -I"$ROOT/patch/symbiosis" \
  "$ROOT/tests/android_core_probe/graphics_reference_probe.cpp" \
  -llog -ldl -landroid -lnativewindow -o "$WORK/apk/lib/arm64-v8a/libgraphics_reference_probe.so"
"$KOTLINC_HOME/bin/kotlinc" "$ROOT/patch/android/kenjinx/KenjinxNative.kt" \
  "$ROOT/patch/android/utils/KenjiUiText.kt" \
  "$ROOT/patch/android/utils/KenjiUiCoordinator.kt" \
  "$ROOT/patch/android/activities/KenjiUiPresenter.kt" \
  "$ROOT/tests/android_core_probe/src/dev/symbiosis/coreprobe/UiProbeActivity.kt" \
  -jvm-target 17 -classpath "$ANDROID_JAR" -d "$WORK/classes"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -classpath "$WORK/classes:$KOTLINC_HOME/lib/kotlin-stdlib.jar" -d "$WORK/classes" \
  "$ROOT/tests/android_core_probe/src/org/yuzu/yuzu_emu/utils/KenjiBridge.java" \
  "$ROOT/tests/android_core_probe/src/dev/symbiosis/coreprobe/EngineStageActivity.java" \
  "$ROOT/tests/android_core_probe/src/dev/symbiosis/coreprobe/ProbeActivity.java" \
  "$ROOT/tests/android_core_probe/src/dev/symbiosis/coreprobe/GraphicsProbeActivity.java"
# A tiny app_process entry and the actual production callback class, packaged
# only as a DEX jar (not an APK) for the independent JNI initialization probe.
mkdir -p "$WORK/standalone-classes" "$WORK/standalone-dex"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$WORK/standalone-classes" \
  "$ROOT/tests/standalone_core_probe/Main.java"
mapfile -d '' STANDALONE_CLASSES < <(find "$WORK/classes/org/kenjinx/android" "$WORK/standalone-classes" -name '*.class' -print0)
"$BT/d8" --min-api "$PROBE_MIN_API" --lib "$ANDROID_JAR" --output "$WORK/standalone-dex" \
  "${STANDALONE_CLASSES[@]}" "$KOTLINC_HOME/lib/kotlin-stdlib.jar"
(cd "$WORK/standalone-dex" && zip -q -X "$WORK/standalone.jar" classes.dex)
mapfile -d '' CLASSES < <(find "$WORK/classes" -name '*.class' -print0)
"$BT/d8" --min-api "$PROBE_MIN_API" --lib "$ANDROID_JAR" --output "$WORK/dex" \
  "${CLASSES[@]}" "$KOTLINC_HOME/lib/kotlin-stdlib.jar"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$MANIFEST" \
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
