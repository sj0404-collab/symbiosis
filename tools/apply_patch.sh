#!/usr/bin/env bash
# Restores the Symbiosis tree into a fresh Eden checkout.
#
# Written as a script because copying these files by hand was forgotten twice,
# and each miss cost a full 30-minute build that appeared to succeed while
# silently shipping the old code.
#
# Paths are arguments rather than constants so the same script serves the local
# sandbox and a CI runner; hard-coding /work/eden meant CI needed its own copy,
# and two copies drift.
#
# Usage:
#   bash tools/apply_patch.sh                          # sandbox defaults
#   bash tools/apply_patch.sh --patch <dir> --eden <dir>
set -euo pipefail

# Resolved before any cd, otherwise BASH_SOURCE is relative to the new cwd.
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PATCH_DIR="${PATCH_DIR:-/home/user/symbiosis-patch}"
EDEN_DIR="${EDEN_DIR:-/work/eden}"

while [ $# -gt 0 ]; do
  case "$1" in
    --patch) PATCH_DIR="$2"; shift 2 ;;
    --eden)  EDEN_DIR="$2";  shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# When run from a checkout of this repository, default to its own patch/ dir.
if [ ! -d "$PATCH_DIR" ]; then
  if [ -d "$SELF_DIR/../patch" ]; then
    PATCH_DIR="$(cd "$SELF_DIR/../patch" && pwd)"
  fi
fi

[ -d "$PATCH_DIR" ] || { echo "patch directory not found: $PATCH_DIR" >&2; exit 1; }
[ -d "$EDEN_DIR" ]  || { echo "eden checkout not found: $EDEN_DIR"    >&2; exit 1; }

# PINNED TAG: this patch is cut against v0.2.1, not master. Upstream master
# carries b870bd255 "[android] config: load configuration on game start", which
# calls reloadGlobalConfig() on every launch; ReadPathValues() begins with
# game_dirs.clear(), so the configured game folders are wiped each time a game
# starts. v0.2.1 predates that.

P="$PATCH_DIR"
E="$EDEN_DIR"
A="$E/src/android/app/src/main"

echo "patch: $P"
echo "eden : $E"

cd "$E"

# --3way needs history, hence the depth-50 clone; the icon is excluded because
# a binary delete cannot be applied without a full index line.
# Capture the outcome rather than swallowing it. A shallow clone makes --3way
# fall back to direct application, which drops files and still exits 0 - the
# build then succeeds against a half-applied patch, which is worse than failing.
apply_log=$(git apply --3way --exclude='*ic_launcher_foreground.png' \
  "$P/upstream_changes.patch" 2>&1 || true)
printf '%s\n' "$apply_log" | grep -v cleanly || true

if printf '%s' "$apply_log" | grep -q "lacks the necessary blob"; then
  echo "ERROR: shallow checkout. git apply --3way needs full history:" >&2
  echo "  git clone --branch <tag> https://git.eden-emu.dev/eden-emu/eden.git" >&2
  exit 1
fi
if printf '%s' "$apply_log" | grep -q "patch does not apply"; then
  echo "ERROR: the patch does not apply to this checkout." >&2
  exit 1
fi
if git status --porcelain | grep -qE '^(UU|AA)'; then
  echo "ERROR: unresolved conflicts:" >&2
  git status --porcelain | grep -E '^(UU|AA)' >&2
  exit 1
fi
rm -f "$A/res/drawable/ic_launcher_foreground.png"

# C++ layer
mkdir -p "$E/src/common/symbiosis"
cp "$P"/symbiosis/*.cpp "$P"/symbiosis/*.h "$E/src/common/symbiosis/"

# Shader
cp "$P"/shaders/present_retro.frag "$E/src/video_core/host_shaders/"

# JNI bridge
cp "$P"/native_symbiosis.cpp "$A/jni/"

# Upstream JNI sources this fork replaces wholesale.
#
# game_metadata.cpp is here rather than in the diff because the changes are
# spread across every function in the file - a null check, a mutex, and the
# removal of a by-value return that copied a half-megabyte icon per call.
# Expressed as a diff that is one hunk per function, and each one shifts the
# line numbers of the next; the file is 160 lines, so shipping it whole is
# both smaller and immune to the hunk-offset arithmetic that has broken this
# patch twice already. It is already listed in jni/CMakeLists.txt upstream,
# so no build wiring changes.
if [ -d "$P/jni" ]; then
  cp "$P"/jni/*.cpp "$A/jni/"
  echo "  replaced JNI sources: $(ls "$P/jni" | tr '\n' ' ')"
fi

# The Kenji bridge is a SEPARATE library, appended to the jni CMakeLists rather
# than patched in by line number - upstream's file is 35 lines long and any
# hunk anchored past that simply fails to apply, silently taking the bridge out
# of the build. Appending is immune to the file changing length.
#
# Two names on purpose. Kotlin loads symbiosis_kenji; the core resolves
# DllImport("libkenjinxjni") at runtime and calls debug_break from inside
# deviceInitialize, so a missing libkenjinxjni.so aborts the process on the
# first device call. Same source, built twice, a few kilobytes each.
cp "$P"/symbiosis/kenji_bridge.cpp "$A/jni/symbiosis_kenji_bridge.cpp"
CM="$A/jni/CMakeLists.txt"
if ! grep -q "symbiosis_kenji" "$CM"; then
  cat >> "$CM" <<'CMAKE'

# ── Kenji-NX bridge ────────────────────────────────────────────────
add_library(symbiosis_kenji SHARED symbiosis_kenji_bridge.cpp)
target_link_libraries(symbiosis_kenji PRIVATE log dl android)

# The name the core itself looks for.
add_library(kenjinxjni SHARED symbiosis_kenji_bridge.cpp)
target_link_libraries(kenjinxjni PRIVATE log dl android)
CMAKE
  echo "  added the Kenji bridge to jni/CMakeLists.txt"
fi

# The probe service, and the reason it exists: a native abort kills its own
# process and nothing else. Declaring android:process=":kenji" means the first
# contact with a freshly downloaded core happens somewhere expendable - if it
# aborts, the launcher hears onServiceDisconnected and says so, instead of
# vanishing.
#
# Inserted with python rather than sed: the manifest is XML, and a regex that
# happens to match twice would produce a file that no longer parses.
MANIFEST="$A/AndroidManifest.xml"
python3 - "$MANIFEST" <<'PYEOF'
import sys

path = sys.argv[1]
text = open(path, encoding='utf-8').read()

if 'KenjiProbeService' in text:
    print('  manifest already declares the Kenji probe service')
    sys.exit(0)

service = """
        <!-- Loads the downloaded Kenji core in its own process, so a crash
             inside it cannot take the app down. -->
        <service
            android:name="org.yuzu.yuzu_emu.utils.KenjiProbeService"
            android:process=":kenji"
            android:exported="false" />
        <activity
            android:name="org.yuzu.yuzu_emu.activities.KenjiPlayerActivity"
            android:process=":kenji"
            android:exported="false"
            android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"
            android:launchMode="singleTask" />
"""

marker = '</application>'
if marker not in text:
    print('  ::warning:: no </application> in the manifest; service not added')
    sys.exit(0)

text = text.replace(marker, service + '    ' + marker, 1)
open(path, 'w', encoding='utf-8').write(text)
print('  declared KenjiProbeService in :kenji')
PYEOF

# Android 11+ hides other packages unless we declare them. Without this
# Spaces.official() always looks empty even when Kenji is installed.
python3 - "$MANIFEST" <<'PYQ'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
if 'org.kenjinx.android' in text and '<queries>' in text:
    print('  queries already list Kenji')
    raise SystemExit(0)
block = '''
    <queries>
        <package android:name="org.kenjinx.android" />
        <package android:name="org.ryujinx.android" />
        <package android:name="org.ryujinx.kenjinx" />
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:mimeType="*/*" />
        </intent>
    </queries>
'''
if '</manifest>' not in text:
    print('  ::warning:: no </manifest>; queries not added')
    raise SystemExit(0)
text = text.replace('</manifest>', block + '</manifest>', 1)
open(path, 'w', encoding='utf-8').write(text)
print('  declared package queries for official Kenji')
PYQ

python3 - "$MANIFEST" <<'PYKENJI'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
if 'KenjiPlayerActivity' in text:
    print('  KenjiPlayerActivity already in manifest')
    sys.exit(0)
act = '''
        <activity
            android:name="org.yuzu.yuzu_emu.activities.KenjiPlayerActivity"
            android:process=":kenji"
            android:exported="false"
            android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"
            android:launchMode="singleTask" />
'''
if 'KenjiProbeService' in text:
    text = text.replace('android:name="org.yuzu.yuzu_emu.utils.KenjiProbeService"',
                        'android:name="org.yuzu.yuzu_emu.utils.KenjiProbeService"', 1)
    # insert after the probe service tag
    marker = 'android:name="org.yuzu.yuzu_emu.utils.KenjiProbeService"'
    i = text.find(marker)
    end = text.find('/>', i)
    if end < 0:
        print('  ::warning:: probe service tag not closed; player activity not added')
        sys.exit(0)
    text = text[:end+2] + act + text[end+2:]
    open(path, 'w', encoding='utf-8').write(text)
    print('  declared KenjiPlayerActivity in :kenji')
else:
    print('  ::warning:: KenjiProbeService missing; player activity not added')
PYKENJI

# Keep the WebView across rotation. Without this MainActivity is destroyed,
# library.html reloads and every cover is decoded again — that is the lag.
python3 - "$MANIFEST" <<'PYROT'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
needle = 'android:name="org.yuzu.yuzu_emu.ui.main.MainActivity"'
if needle not in text:
    print('  ::warning:: MainActivity not in manifest; rotation keep not applied')
    sys.exit(0)
if 'orientation|screenSize' in text and needle in text:
    # already has some configChanges near the file; still ensure MainActivity has it
    pass
cfg = 'android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode"'
old = '''            android:name="org.yuzu.yuzu_emu.ui.main.MainActivity"
            android:exported="true"'''
new = '''            android:name="org.yuzu.yuzu_emu.ui.main.MainActivity"
            ''' + cfg + '''
            android:exported="true"'''
if cfg in text and old not in text:
    print('  MainActivity already keeps the WebView on rotate')
elif old in text:
    text = text.replace(old, new, 1)
    open(path, 'w', encoding='utf-8').write(text)
    print('  MainActivity keeps WebView on rotate')
else:
    print('  ::warning:: MainActivity block shape changed; rotation keep not applied')
PYROT

# R8 rules for our own classes.
#
# A release build renames and deletes whatever R8 cannot see being used, and
# three things here are reached by NAME only: the probe service (a string in
# the manifest), EnginesFragment (created by the navigation graph) and the JNI
# surfaces. Without these rules the release APK builds cleanly and then dies
# with ClassNotFoundException - in the one build type a person actually gets.
APP_DIR="$(cd "$A/../.." && pwd)"
cp "$P"/android/proguard-symbiosis.pro "$APP_DIR/proguard-symbiosis.pro"
GRADLE="$APP_DIR/build.gradle.kts"
if grep -q "proguard-symbiosis.pro" "$GRADLE"; then
  echo "  R8 rules already wired in"
else
  python3 - "$GRADLE" <<'PGEOF'
import sys

path = sys.argv[1]
text = open(path, encoding='utf-8').read()

# Appended to every proguardFiles(...) that already lists the project rules.
# Anchoring on that string rather than on a build type keeps this working if
# upstream adds or renames a variant.
needle = '"proguard-rules.pro"'
if needle not in text:
    print("  ::warning:: no proguardFiles entry found; R8 rules not wired in")
else:
    text = text.replace(needle, needle + ',\n                "proguard-symbiosis.pro"')
    open(path, 'w', encoding='utf-8').write(text)
    print("  wired proguard-symbiosis.pro into the R8 rules")
PGEOF
fi


# Kotlin
J="$A/java/org/yuzu/yuzu_emu"
cp "$P"/android/fragments/*.kt "$J/fragments/"
cp "$P"/android/adapters/*.kt  "$J/adapters/"
cp "$P"/android/utils/*.kt     "$J/utils/"
# Встроенная копия панели: та же страница, показывается когда сети нет.
# Панель не обязана быть онлайн - она обязана работать.
if [ -d "$P/android/assets" ]; then
  mkdir -p "$A/assets"
  cp "$P"/android/assets/* "$A/assets/"
  echo "  copied assets: $(ls "$P/android/assets" | tr '\n' ' ')"
fi

# ui/ holds upstream screens this fork modifies wholesale rather than by diff.
if [ -d "$P/android/ui" ]; then
  cp "$P"/android/ui/*.kt "$J/ui/"
fi
if [ -d "$P/android/ui/main" ]; then
  cp "$P"/android/ui/main/*.kt "$J/ui/main/"
fi
if [ -d "$P/android/model" ]; then
  cp "$P"/android/model/*.kt "$J/model/"
fi
if [ -d "$P/android/activities" ]; then
  mkdir -p "$J/activities"
  cp "$P"/android/activities/*.kt "$J/activities/"
  mkdir -p "$A/java/org/kenjinx/android"
  if [ -d "$P/android/kenjinx" ]; then
    cp "$P"/android/kenjinx/*.kt "$A/java/org/kenjinx/android/"
    echo "  KenjinxNative shim installed"
  fi
  echo "  replaced activities: $(ls "$P/android/activities" | tr '\n' ' ')"
fi

# Resources
cp "$P"/android/layout/*.xml   "$A/res/layout/"
# Landscape variants of screens this fork modifies. Missing one made view
# binding type the view as nullable and failed the Kotlin compile.
if [ -d "$P/android/layout-land" ]; then
  cp "$P"/android/layout-land/*.xml "$A/res/layout-land/"
fi
# navigation/ is only present once a screen has been added to the graph.
if [ -d "$P/android/navigation" ]; then
  cp "$P"/android/navigation/*.xml "$A/res/navigation/"
fi
cp "$P"/android/drawable/*.xml "$A/res/drawable/"
# Merge, never replace. A wholesale copy silently drops any upstream string the
# fork's file predates: build #10 failed on "resource string/off not found"
# because v0.2.1's arrays.xml references strings my master-derived copy lacked.
python3 "$SELF_DIR/merge_strings.py" \
  "$A/res/values/strings.xml"    "$P/android/values/strings-en.xml" "$A/res/values/strings.xml"
python3 "$SELF_DIR/merge_strings.py" \
  "$A/res/values-ru/strings.xml" "$P/android/values/strings-ru.xml" "$A/res/values-ru/strings.xml"

# The Kotlin daemon is OOM-killed on a small machine; in-process is slower to
# start but survives.
GP="$E/src/android/gradle.properties"
# Gradle tuning depends on the machine, so decide it here rather than shipping
# one setting that is wrong everywhere.
#
# Upstream ships deliberately tiny limits (1.2 GB heap, one worker, no
# parallelism) so the build survives a small laptop. On a CI runner with four
# cores and 16 GB that is the difference between ~10 and ~25 minutes, and the
# native compile is entirely CPU-bound.
total_kb=$(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null || echo 0)
cores=$(nproc 2>/dev/null || echo 2)

python3 - "$GP" "$total_kb" "$cores" <<'PYTUNE'
import sys

gp, total_kb, cores = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
text = open(gp).read() if __import__("os").path.exists(gp) else ""
big = total_kb >= 7_000_000  # ~7 GB and up counts as a real build machine

if big:
    # Leave headroom for the Kotlin daemon and the C++ compilers, which live
    # outside the Gradle JVM.
    heap = min(6144, max(2048, total_kb // 1024 // 3))
    settings = {
        "org.gradle.jvmargs": f"-Xms512m -Xmx{heap}m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8",
        "org.gradle.workers.max": str(cores),
        "org.gradle.parallel": "true",
        "org.gradle.caching": "true",
        "kotlin.parallel.tasks.in.project": "true",
        # A daemon can hold the Kotlin compiler in memory; only safe when there
        # is enough RAM that the OOM killer will not take it out mid-build.
        "kotlin.compiler.execution.strategy": "daemon",
        "kotlin.daemon.jvmargs": "-Xmx2g",
    }
else:
    settings = {
        "org.gradle.workers.max": "1",
        "org.gradle.parallel": "false",
        # In-process, because a separate Kotlin daemon is the first thing the
        # OOM killer takes on a 2 GB machine.
        "kotlin.compiler.execution.strategy": "in-process",
    }

lines, seen = [], set()
for line in text.splitlines():
    key = line.split("=", 1)[0].strip()
    if key in settings:
        if key in seen:
            continue
        seen.add(key)
        lines.append(f"{key}={settings[key]}")
    else:
        lines.append(line)

for key, value in settings.items():
    if key not in seen:
        lines.append(f"{key}={value}")

open(gp, "w").write("\n".join(lines) + "\n")
print(f"gradle tuned for {'a large' if big else 'a small'} machine: "
      f"{total_kb // 1024} MB RAM, {cores} cores")
PYTUNE

# Служба конвертера: распаковка должна переживать уход с экрана и показывать
# прогресс в шторке. Без объявления в манифесте startForegroundService падает
# с «Service not declared», поэтому добавление здесь, а не «когда-нибудь».
python3 - "$MANIFEST" <<'PYCONV'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
if 'ConverterService' in text:
    print('  manifest already declares the converter service')
    raise SystemExit(0)
if 'FOREGROUND_SERVICE_SPECIAL_USE' not in text:
    permission = '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />\n'
    if '<uses-permission' in text:
        i = text.index('<uses-permission')
        text = text[:i] + permission + text[i:]
service = """
        <!-- Распаковка NSZ/XCZ/NCZ: своя служба, потому что работа длится
             дольше экрана и должна быть видна в шторке. -->
        <service
            android:name="org.yuzu.yuzu_emu.utils.ConverterService"
            android:exported="false"
            android:foregroundServiceType="specialUse" />
"""
marker = '</application>'
if marker not in text:
    print('  ::warning:: no </application>; converter service not added')
    raise SystemExit(0)
text = text.replace(marker, service + '    ' + marker, 1)
open(path, 'w', encoding='utf-8').write(text)
print('  declared ConverterService (specialUse)')
PYCONV

# Новые .cpp сами в список не попадут: upstream_changes.patch перечисляет
# файлы common руками, а хунки в нём уже сдвинуты. Поэтому список
# дополняется здесь, и отдельно проверяется результат: без строки в CMake
# символы Ncz::* и Aes::* остаются неразрешёнными, и падает не компиляция, а
# линковка libyuzu-android — через двадцать минут и без указания на причину.
CMCOMMON="$E/src/common/CMakeLists.txt"
for extra in symbiosis/ncz.cpp symbiosis/ncz.h symbiosis/aes128.cpp symbiosis/aes128.h symbiosis/zstd_include.h; do
  [ -f "$E/src/$extra" ] || echo "  ::warning:: $extra не скопирован" >&2
done
if [ -f "$CMCOMMON" ] && ! grep -q "symbiosis/ncz.cpp" "$CMCOMMON"; then
python3 - "$CMCOMMON" <<'PYNCZ'
import re
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
anchor = '    symbiosis/rom_tools.cpp'
extra = '    symbiosis/aes128.cpp\n    symbiosis/aes128.h\n    symbiosis/ncz.cpp\n    symbiosis/ncz.h\n    symbiosis/zstd_include.h\n'
if anchor in text:
    text = text.replace(anchor, extra + anchor, 1)
else:
    idx = text.find('symbiosis/')
    if idx < 0:
        print('  ::warning:: no symbiosis sources in common CMakeLists')
        raise SystemExit(0)
    line_start = text.rfind('\n', 0, idx) + 1
    text = text[:line_start] + extra + text[line_start:]
open(path, 'w', encoding='utf-8').write(text)
print('  added ncz/aes128 sources to common CMakeLists')
PYNCZ

# Строка в CMakeLists.txt должна быть ровно одна; повторный запуск скрипта на
# неочищенном дереве не должен добавлять файлы дважды.
dups=$(grep -c "symbiosis/ncz.cpp" "$CMCOMMON")
if [ "$dups" != "1" ]; then
  echo "ERROR: symbiosis/ncz.cpp встречается в common CMakeLists $dups раз" >&2
  exit 1
fi
fi

changed=$(git status --porcelain | wc -l)
echo "PATCH_APPLIED files=$changed"
if [ "$changed" -lt 80 ]; then
  echo "ERROR: only $changed files changed; a complete apply touches ~87." >&2
  exit 1
fi
