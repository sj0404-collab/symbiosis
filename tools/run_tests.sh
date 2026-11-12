#!/usr/bin/env bash
# Builds and runs the host test suite against a patched Eden checkout.
#
# Two things here are easy to get wrong and both make the suite worthless:
#
#  1. Do NOT put <eden>/src/common on the include path. Eden ships its own
#     common/assert.h, which shadows the standard <cassert>; every assert()
#     then expands to nothing and the tests pass without checking anything.
#     Only -I<eden>/src is correct.
#
#  2. Eden's logging is a real dependency of the layer. Rather than linking
#     half the emulator, a tiny stub supplies FmtLogMessageImpl.
#
# Usage: bash tools/run_tests.sh [--eden <dir>]
set -uo pipefail

EDEN_DIR="${EDEN_DIR:-/tmp/citest}"
# A test that will not link proves nothing. Allow a known few (they need most
# of video_core), but fail if the number grows - that means the environment is
# broken, not the code.
MAX_SKIP="${MAX_SKIP:-99}"
while [ $# -gt 0 ]; do
  case "$1" in
    --max-skip) MAX_SKIP="$2"; shift 2 ;;
    --eden) EDEN_DIR="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

E="$EDEN_DIR"
S="$E/src/common/symbiosis"
[ -d "$S" ] || { echo "patched Eden not found at $E (run tools/apply_patch.sh first)" >&2; exit 1; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Eden's logging.h needs fmt 10 (format.get()); Ubuntu ships fmt 9. Point
# FMT_INCLUDE at a fmt 10 checkout to build against that instead. FMT_HEADER_ONLY
# avoids needing the compiled library at all.
FMT_FLAGS=""
if [ -n "${FMT_INCLUDE:-}" ]; then
  FMT_FLAGS="-isystem $FMT_INCLUDE -DFMT_HEADER_ONLY"
  LINK_FMT=""
else
  LINK_FMT="-lfmt"
fi
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT

# Minimal stand-in for Eden's logging backend.
cat > "$BUILD/log_stub.cpp" <<'STUB'
#include <cstdarg>
#include <string_view>
#include "common/logging.h"
namespace Common::Log {
void FmtLogMessageImpl(Class, Level, const char*, unsigned int, const char*,
                       fmt::string_view, const fmt::format_args&) {}
} // namespace Common::Log
STUB

# zstd нужен по-настоящему: без него NSZ не распаковывается, и именно это
# раньше уходило в APK незамеченным. Поэтому пакет обязателен, а не «если
# найдётся»: отсутствие libzstd-dev — ошибка окружения, а не повод тихо
# пропустить тест распаковки.
ZSTD_FLAGS=""
ZSTD_LIBS=""
if printf '#include <zstd.h>\nint main(){return 0;}\n' | g++ -x c++ -o "$BUILD/zstd_probe" -lzstd - >/dev/null 2>&1; then
  ZSTD_FLAGS="-DSYMBIOSIS_HOST_ZSTD"
  ZSTD_LIBS="-lzstd"
else
  echo "libzstd-dev не найден (нужен для теста распаковки NSZ)." >&2
  echo "  sudo apt-get install -y libzstd-dev" >&2
  exit 1
fi

CORE="$S/symbiosis_log.cpp $S/symbiosis_types.cpp $BUILD/log_stub.cpp"
# Settings::values lives here; several tests read or write real settings.
SETTINGS="$E/src/common/settings.cpp"

deps_for() {
  case "$1" in
    t_mali|t_parse) echo "$S/mali_tuning.cpp $CORE" ;;
    t_fw)           echo "$S/firmware_vault.cpp $CORE" ;;
    t_rom)          echo "$S/rom_tools.cpp $S/ncz.cpp $S/aes128.cpp $CORE" ;;
    t_nsz)          echo "$S/rom_tools.cpp $S/ncz.cpp $S/aes128.cpp $CORE" ;;
    t_save)         echo "$S/save_guard.cpp $S/thermal_monitor.cpp $CORE" ;;
    t_guard)        echo "$CORE" ;;
    t_audio)        echo "$S/audio_metrics.cpp $CORE" ;;
    # Подмена слота в таблице переходов: проверяется на настоящей .so, собранной
    # самим тестом. Нужен libdl — он в LINK-флагах по умолчанию для этого теста.
    t_got)          echo "$S/got_patch.cpp $CORE" ;;
    t_modes)        echo "$S/auto_modes.cpp $S/device_profiles.cpp $SETTINGS $CORE" ;;
    t_uaf)          echo "$S/memory_governor.cpp $CORE" ;;
    t_v12)          echo "$S/device_profiles.cpp $S/auto_modes.cpp $SETTINGS $CORE" ;;
    t_fps)          echo "$S/device_profiles.cpp $S/auto_modes.cpp $SETTINGS $CORE" ;;
    *)              echo "" ;;
  esac
}

pass=0; fail=0; skip=0; failed=""
for f in "$HERE"/tests/t_*.cpp; do
  [ -e "$f" ] || continue
  n=$(basename "$f" .cpp)
  # shellcheck disable=SC2046
  # shellcheck disable=SC2086
  # -fno-rtti — как в сборке Eden. Без него тест на хосте собирается, а CI
  # падает на `use of dynamic_cast requires -frtti` через двадцать пять минут
  # (сборка 41387047290). Флаг в тестах, а не в коде, потому что проверять
  # надо именно ту конфигурацию, в которой код поедет на телефон.
  if g++ -std=c++20 -fno-rtti -O1 $FMT_FLAGS $ZSTD_FLAGS -I"$E/src" -o "$BUILD/$n" "$f" $(deps_for "$n") \
       $LINK_FMT $ZSTD_LIBS > "$BUILD/$n.err" 2>&1; then
    # Тестам NSZ нужны файлы-образцы; путь передаётся явно, чтобы бинарник
    # работал и запущенный из любой папки.
    if out=$(SYMBIOSIS_TEST_DATA="$HERE/tests/data" "$BUILD/$n" 2>&1); then
      echo "  PASS  $n — $(printf '%s' "$out" | tail -1)"; pass=$((pass+1))
    else
      echo "  FAIL  $n"; printf '%s\n' "$out" | tail -5 | sed 's/^/        /'
      fail=$((fail+1)); failed="$failed $n"
    fi
  else
    # A test whose dependencies are not wired up is reported, never hidden.
    # A test that will not build is reported with the real reason, never a
    # blank. An empty parenthesis told me nothing on CI and cost a whole run.
    reason=$(grep -m1 -E "undefined reference|error:|fatal error:" "$BUILD/$n.err" | head -1)
    echo "  SKIP  $n"
    echo "        reason: ${reason:-unknown}"
    sed 's/^/        | /' "$BUILD/$n.err" | head -6
    skip=$((skip+1))
  fi
done

# Kotlin tests, when a compiler is available.
if command -v kotlinc >/dev/null 2>&1; then
  # Sources a Kotlin test compiles against, beyond itself.
  #
  # A test that calls real code cannot be built from its own file alone, and
  # the loop below compiles exactly one file. `LibraryIndexTest` imports
  # `LibraryIndex`, both plain Kotlin with no Android imports, so the pair
  # compiles with a bare kotlinc. Anything needing the Android framework is
  # still modelled, not imported.
  kt_deps_for() {
    case "$1" in
      LibraryIndexTest) echo "$HERE/patch/android/utils/LibraryIndex.kt" ;;
      # The native launcher keeps its decisions in a presenter and its text in
      # Manual, both plain Kotlin. A test that calls them should compile against
      # them rather than against a hand-written copy of the same logic.
      NativeLibraryPresenterTest) echo "$HERE/patch/android/utils/LibraryIndex.kt \
        $HERE/patch/android/utils/NativeLibraryPresenter.kt \
        $HERE/patch/android/utils/Manual.kt" ;;
      # The hybrid decides which Eden setting reaches the second core, and
      # which stays its own. Plain Kotlin, so it compiles against the real file.
      EngineHybridTest) echo "$HERE/patch/android/utils/EngineHybrid.kt" ;;
      SignedCoreManifestTest) echo "$HERE/patch/android/utils/SignedCoreManifest.kt" ;;
      # Палитра: цвет игры, читаемость текста, монограммы. Чистые функции —
      # поэтому проверяются без Android.
      SymbiosisThemeTest) echo "$HERE/patch/android/utils/SymbiosisTheme.kt" ;;
      # Список папок с играми: после сбоя у пользователя осталась одна папка
      # из четырёх. Правила восстановления проверяются без телефона.
      GameFolderListTest) echo "$HERE/patch/android/utils/GameFolderList.kt" ;;
      # Папка данных второго ядра: раскладка bis/system/Contents/registered и
      # поиск готовых папок. Ошибка здесь — либо 300 МБ копирования на каждый
      # запуск, либо чёрный экран без причины. Чистые File, без Android.
      KenjiDataTest) echo "$HERE/patch/android/utils/KenjiData.kt" ;;
      # Совместимость ядра с устройством: разбор ELF и auxv, вывод «подходит/
      # не подходит» с причиной. Чистые функции над байтами.
      DeviceCompatTest) echo "$HERE/patch/android/utils/DeviceCompat.kt" ;;
      # Сводка по сейвам: дни, часы без сохранения, склонения. Ошибка здесь
      # меняет решение «начинать заново или нет».
      SavesOverviewTest) echo "$HERE/patch/android/utils/SavesOverview.kt" ;;
      # WebDAV: разбор ответов сервера и сборка путей.
      WebDavParseTest) echo "$HERE/patch/android/utils/WebDavParse.kt" ;;
      # Журнал ядра: чтение хвоста файла и подсчёт ошибок. Чистый Kotlin без
      # Android — поэтому проверяется на хосте; ошибка здесь означает
      # OutOfMemory на телефоне или «ошибок: 0» при семи ошибках.
      KernelLogTest) echo "$HERE/patch/android/utils/KernelLogFormat.kt" ;;
      # Архив журнала: правила «что сохранить и что стереть», лимиты и
      # подписи. Чистые функции — проверяются без телефона.
      KernelLogArchiveTest) echo "$HERE/patch/android/utils/KernelLogArchive.kt" ;;
      # Причины отказа конвертера: таблица «сообщение ядра → фраза для
      # человека». Ошибка здесь означает, что вместо причины человек увидит
      # «не удалось».
      ConverterReasonsTest) echo "$HERE/patch/android/utils/ConverterReasons.kt" ;;
      # Плагины: разбор JSON, проверка правил, порядок применения и пометки на
      # плитках. Чистый Kotlin, поэтому проверяется целиком на хосте.
      PluginEngineTest) echo "$HERE/patch/android/utils/PluginJson.kt \
        $HERE/patch/android/utils/PluginApi.kt \
        $HERE/patch/android/utils/PluginEngine.kt \
        $HERE/patch/android/utils/BuiltinPlugins.kt \
        $HERE/patch/android/utils/EngineHybrid.kt \
        $HERE/patch/android/utils/NativeLibraryPresenter.kt \
        $HERE/patch/android/utils/LibraryIndex.kt" ;;
      # Торренты: bencode, разбор .torrent, арифметика кусков, рукопожатие и
      # трекеры. Всё чистые функции над байтами — проверяются на хосте.
      TorrentFormatTest) echo "$HERE/patch/android/utils/TorrentBencode.kt \
        $HERE/patch/android/utils/TorrentMeta.kt \
        $HERE/patch/android/utils/TorrentPlan.kt \
        $HERE/patch/android/utils/TorrentWire.kt \
        $HERE/patch/android/utils/TorrentTracker.kt \
        $HERE/patch/android/utils/SourceIndex.kt" ;;
      # Скачивание по-настоящему: локальный трекер и локальный пир на
      # 127.0.0.1, настоящие сокеты. Log — заглушка из tests/host.
      # Панель быстрых настроек: что применяется сразу, а что только после
      # перезапуска игры. Ошибка здесь = «настройка включена, эффекта нет».
      QuickPanelTest) echo "$HERE/patch/android/utils/QuickPanel.kt" ;;
      # Формат по первым байтам: самый частый отказ конвертера — выбран не
      # образ, а архив, и раньше это выяснялось через сорок минут.
      RomSniffTest) echo "$HERE/patch/android/utils/RomSniff.kt" ;;
      # Текст в шторке: при отказе причина должна быть первой строкой, а не
      # заменяться путём к папке.
      ConverterOutcomeTest) echo "$HERE/patch/android/utils/ConverterReasons.kt" ;;
      # Разбор отчёта о падении второго ядра: файл пишет обработчик сигнала,
      # читает обычный код после смерти процесса.
      KenjiCrashTest) echo "$HERE/patch/android/utils/KenjiCrash.kt" ;;
      KenjiUiTextTest) echo "$HERE/patch/android/utils/KenjiUiText.kt" ;;
      KenjiUiCoordinatorTest) echo "$HERE/patch/android/utils/KenjiUiCoordinator.kt $HERE/patch/android/utils/KenjiUiText.kt" ;;
      KenjiUiSessionTest) echo "$HERE/patch/android/utils/KenjiUiSession.kt $HERE/patch/android/utils/KenjiUiDispatcher.kt $HERE/patch/android/utils/KenjiUiCoordinator.kt $HERE/patch/android/utils/KenjiUiText.kt $HERE/patch/android/kenjinx/KenjinxNative.kt" ;;
      KenjiInputMapTest) echo "$HERE/patch/android/utils/KenjiInputMap.kt" ;;
      KenjiSurfaceStartGateTest) echo "$HERE/patch/android/utils/KenjiSurfaceStartGate.kt" ;;
      KenjiStartupCoordinatorTest) echo "$HERE/patch/android/utils/KenjiStartupCoordinator.kt" ;;
      GameSessionLockTest) echo "$HERE/patch/android/utils/GameSessionLock.kt" ;;
      KenjiOverlaySnapshotTest) echo "$HERE/patch/android/utils/KenjiOverlaySnapshot.kt \
        $HERE/tests/host/kenji_overlay/Bundle.kt \
        $HERE/tests/host/kenji_overlay/Settings.kt \
        $HERE/tests/host/kenji_overlay/OverlayControlData.kt \
        $HERE/tests/host/kenji_overlay/NativeConfig.kt" ;;
      # Записки «эту игру ядро роняло»: формат, совпадение по имени файла,
      # снятие записки явным выбором. 05.11 игра считалась переведённой на
      # Eden, а запуск снова ушёл на Kenji — разбор здесь.
      CoreFallbackNotesTest) echo "$HERE/patch/android/utils/CoreFallbackNotes.kt" ;;
      # Разбор контейнера без ключей: NSZ под именем NSP, XCI с расширением
      # .nsp, обрезанный файл, билет с идентификатором игры. 05.11 ключи были
      # на месте, а плитки говорили «проверьте ключи» — это была догадка.
      NspProbeTest) echo "$HERE/patch/android/utils/NspProbe.kt \
        $HERE/patch/android/utils/TicketKey.kt \
        $HERE/patch/android/utils/TitleKeys.kt \
        $HERE/patch/android/utils/ExtraKeys.kt" ;;
      # Ключ игры — не то же, что 204 ключа консоли. Проверяем, что его
      # наличие определяется по файлам, а не по «keys present: true».
      # Прошивка у двух ядер одна: ссылка вместо второй копии на 300 МБ.
      KenjiFirmwareShareTest) echo "$HERE/patch/android/utils/KenjiData.kt" ;;
      # Где второе ядро ищет прошивку: подпапки в registered и плоские .nca
      # в bis/system/Contents. Ссылка на папку Eden — ноль файлов для Kenji.
      KenjiFirmwareLayoutTest) echo "$HERE/patch/android/utils/KenjiData.kt" ;;
      # Паника гостя: `SvcBreak reason=0x0` — игра остановила себя сама.
      # Раньше разбор знал только .NET-исключения и на экране оставалось
      # «кадров нет» вместо приговора.
      KenjiPanicTest) echo "$HERE/patch/android/utils/KenjiCrash.kt \
        $HERE/patch/android/utils/KenjiData.kt \
        $HERE/patch/android/utils/KernelLogFormat.kt \
        $HERE/patch/android/utils/EngineHybrid.kt" ;;
      TitleKeysTest) echo "$HERE/patch/android/utils/TitleKeys.kt \
        $HERE/patch/android/utils/ExtraKeys.kt" ;;
      # Дополнительные ключи: какая строка — ключ игры, что дописывать в
      # prod.keys/title.keys, чего не перезаписывать. Ошибка здесь снова
      # оставила бы «нет ключа для этой игры» без объяснения.
      # ExtraKeys теперь приводит строки прав билета к идентификатору игры —
      # значит нужен и TitleKeys, иначе тест не собирается.
      ExtraKeysTest) echo "$HERE/patch/android/utils/ExtraKeys.kt \
        $HERE/patch/android/utils/TitleKeys.kt" ;;
      # Ключ из билета: разметка тикета и AES — без ядра и без сети.
      # Альбомная ориентация лаунчера: имена размеров совпадают, значения ужаты.
      # Тест читает ресурсы, Kotlin-исходников ему не нужно.
      LauncherLandscapeTest) echo "" ;;
      TicketKeyTest) echo "$HERE/patch/android/utils/TicketKey.kt \
        $HERE/patch/android/utils/TitleKeys.kt \
        $HERE/patch/android/utils/ExtraKeys.kt" ;;
      # Ровность кадров: почему «60 → 15 → 45 → 5» и когда виноват звук, а не
      # картинка. Чистая арифметика над рядами длительностей кадров.
      FramePacingTest) echo "$HERE/patch/android/utils/FramePacing.kt" ;;
      # Разбор снимка от нативной части: мусор и пустой ответ не должны
      # ронять экран диагностики.
      FrameMetricsTextTest) echo "$HERE/patch/android/utils/FrameMetricsText.kt \
        $HERE/patch/android/utils/FramePacing.kt" ;;
      TorrentDownloadTest) echo "$HERE/patch/android/utils/TorrentBencode.kt \
        $HERE/patch/android/utils/TorrentMeta.kt \
        $HERE/patch/android/utils/TorrentPlan.kt \
        $HERE/patch/android/utils/TorrentWire.kt \
        $HERE/patch/android/utils/TorrentTracker.kt \
        $HERE/patch/android/utils/TorrentDownload.kt \
        $HERE/tests/host/log_stub.kt" ;;
      *)                echo "" ;;
    esac
  }

  for f in "$HERE"/tests/*.kt; do
    [ -e "$f" ] || continue
    n=$(basename "$f" .kt)
    # shellcheck disable=SC2046
    if ! kotlinc "$f" $(kt_deps_for "$n") -include-runtime -d "$BUILD/$n.jar" \
          > "$BUILD/$n.kerr" 2>&1; then
      # A test that does not build used to be reported as "FAIL" with the
      # reason thrown away (2>/dev/null), which then surfaced as the useless
      # "Unable to access jarfile". Say what the compiler said instead.
      why=$(grep -m1 -E "^.*error:" "$BUILD/$n.kerr" | head -1)
      echo "  FAIL  $n — does not build"
      echo "        ${why:-no compiler output}"
      fail=$((fail+1)); failed="$failed $n"
      continue
    fi
    # Run from the repository root. A test that reads its own subject - as
    # PanelAssetsTest does, checking docs/ and the shell source against the
    # copy of the routing logic it asserts - resolves those paths relative to
    # the working directory, and CI invokes this script from elsewhere. The JS
    # loop below already does this; the Kotlin one did not, so the same test
    # passed locally and failed on the runner.
    if out=$(cd "$HERE" && java -jar "$BUILD/$n.jar" 2>&1); then
      echo "  PASS  $n — $(printf '%s' "$out" | tail -1)"; pass=$((pass+1))
    else
      echo "  FAIL  $n"; printf '%s\n' "$out" | tail -8 | sed 's/^/        /'
      fail=$((fail+1)); failed="$failed $n"
    fi
  done
else
  echo "  note: kotlinc not on PATH, Kotlin tests not run"
fi

# Node tests. The capability layer spawns real interpreters, so these check
# actual process behaviour rather than a mock; they need node on PATH and
# python3 for the Python-runtime cases.
if command -v node >/dev/null 2>&1; then
  for f in "$HERE"/tests/*.js; do
    [ -e "$f" ] || continue
    n=$(basename "$f" .js)
    if out=$(cd "$HERE" && node "$f" 2>&1); then
      echo "  PASS  $n — $(printf '%s' "$out" | tail -1)"; pass=$((pass+1))
    else
      echo "  FAIL  $n"; printf '%s\n' "$out" | tail -15 | sed 's/^/        /'
      fail=$((fail+1)); failed="$failed $n"
    fi
  done
else
  echo "  note: node not on PATH, JS tests not run"
fi

echo
echo "passed=$pass failed=$fail skipped=$skip"
[ "$fail" -eq 0 ] || { echo "failing:$failed"; exit 1; }
if [ "$skip" -gt "$MAX_SKIP" ]; then
  echo "too many tests could not be built ($skip > $MAX_SKIP): the toolchain is"
  echo "missing something - libfmt-dev, or the patched Eden tree."
  exit 1
fi
