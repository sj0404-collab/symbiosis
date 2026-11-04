# Symbiosis

A fork of the [Eden](https://git.eden-emu.dev/eden-emu/eden) Android emulator, tuned for
**ARM Mali GPUs on 8 GB devices where no custom Vulkan driver can be installed**.

Target hardware for this work: **Mali-G57 MC2 / MediaTek MT6789, 8 GB RAM (~5 GB usable)**.

## Where everything is

The app opens on a **native library screen** — real Android views, not a WebView page. Six
buttons across the top: *Rescan*, *Folders*, *Converter*, *Keys*, *Tools*, *Guide*. Every file
in the selected folders gets a row; a file that cannot launch says why in words instead of
disappearing.

**New here? Read [`docs/GUIDE.md`](docs/GUIDE.md)** — the same manual that ships inside the
app under *Guide*, with a search box. It covers, in order: keys → folder → launch, what the
badges mean, what to do with a compressed NSZ, where every screen lives, and what to check
first when a game will not start.

Why the rewrite: the start screen used to be an HTML page inside a WebView. File handling and
"what to offer" lived in JavaScript, settings lived in Kotlin, the layout lived in an HTML
string, and the honest answer to "where is it?" was "depends which half". The screen no longer
decides anything: `NativeLibraryPresenter` builds the rows, `Manual` holds the text, and both
are plain Kotlin checked by a host test, so the layout cannot silently disagree with the logic.

## Two cores in one

Eden is compiled in; Kenji-NX (55 MB) is downloaded once and runs in its own `:kenji`
process, so a fault in one cannot close the other. The cores were previously an
either/or switch, and the second core ignored the settings screen entirely — its
start-up arguments were numbers written into `KenjiBridge`.

The hybrid fixes both halves:

| Rule | Where |
|---|---|
| **Shared** settings (scale, dock, shader cache, NCE) follow Eden until you change them for the second core | `EngineHybrid.seed` |
| **Own** settings (memory mode, PPTC, macro HLE, anisotropy, internet, integrity) belong to Kenji and Eden never overwrites them | `EngineHybrid.merge` |
| **Core per game**, not per app: long-press a tile in the library, and the choice is remembered for that game | `EngineStore.coreFor` |

Settings reach the core as a string in the Intent, because SharedPreferences are not
shared between processes — `:kenji` would otherwise read a stale copy of the file.
`tests/EngineHybridTest.kt` (103 checks) covers the merge, the per-game choice and the
wiring down to the native call.

---

## Why this fork exists

Stock Eden assumes a desktop-class GPU or an Adreno with replaceable drivers. On Mali:

- **OpenGL is unavailable.** Eden's GL renderer requires desktop OpenGL 4.6
  (`gl_device.cpp:163` throws otherwise); Android offers GLES 3.2. Vulkan is the only path.
- **No custom drivers.** Unlike Adreno, there is no widely deployed Turnip/adrenotools
  equivalent. The vendor blob is all there is.
- **Eden has no Mali knowledge.** `vulkan_device.cpp` carries a large Qualcomm quirk block;
  for ARM there is nothing, even though a Bifrost G71 and an Immortalis G720 differ enormously.

## Settings are requests, not guarantees

The core insight behind this fork. Between a toggle and the GPU sit silent filters that
never report back, so a setting can stay on while doing nothing:

| Setting | Gate | Effect on Mali |
|---|---|---|
| `astc_recompression` | `!device.IsOptimalAstcSupported()` — `maxwell_to_vk.cpp:248` | **Dead.** Mali decodes ASTC in hardware, so the value is read and discarded. |
| `force_max_clock` | `ShouldBoostClocks()` — `vulkan_device.cpp:947` | **Dead.** The allow-list names AMD, NVIDIA, Intel, Qualcomm, Samsung. ARM is absent. |
| `use_vsync` | `ChooseSwapPresentMode()` — `vk_swapchain.cpp:44` | **Renegotiated silently** against surface capabilities; caps the frame rate. |
| `use_asynchronous_shaders` | `vk_pipeline_cache.cpp:353` | Survives, but worker count collapses to 1 on fragile drivers. |

The **launch report** (Diagnostics → Launch report, or the overlay button next to the FPS
counter) states, per setting, whether it reached the renderer, what it became if not, and
which line of code decided that.

---

## Repository layout

```
patch/
  symbiosis/              C++ layer, ~6500 lines (src/common/symbiosis/)
  shaders/                present_retro.frag
  android/                Kotlin fragments, adapters, utils, layouts, strings
  native_symbiosis.cpp    JNI bridge, 53 entry points
  upstream_changes.patch  git diff against upstream Eden
tools/
  apply_patch.sh          restores the tree into a fresh Eden checkout
  setup_env.sh            JDK 17 + Android SDK/NDK + swap
  run_build.sh            gradle assembleLegacyDebug
tests/
  t_*.cpp                 host tests for the C++ layer
  SetupPageTest.kt        setup-page visibility logic
  test_l10n.py            Russian localisation checks
docs/
  GUIDE.md                user manual: how to use the app and where everything is
  BUGS.md                 every bug found, with evidence
```

## Building

**Builds run in GitHub Actions, not on a workstation.** Go to the
[Actions tab](https://github.com/sj0404-collab/symbiosis/actions), pick
**Build APK**, press *Run workflow*. About 25 minutes; the APK is attached as an
artefact and, if the box is ticked, uploaded to gofile.io with its MD5 printed
in the run summary.

Three workflows:

| Workflow | Runs | Purpose |
|---|---|---|
| **Tests** | ~4 min | Host test suite, the localisation check and the agent/chat suite. Runs on every change to `tests/`, `tools/run_tests.sh`, the C++ layer or `agent/`. |
| **Build APK** | ~25 min | The above, then the full NDK build, then verifies the fixes are present in the produced APK. |
| **Panel APK** | ~5 min | The Symbiosis panel as a standalone, self-contained app. Independent of the emulator. |

### Panel APK

The panel is a separate, much smaller app: the control panel for this account
and for the fork, with no emulator in it. **The pages are packaged inside the
APK**, so it opens with no connection; the network is used only for what the
panel actually does - the GitHub API, starting workflows, reaching a session
tunnel.

It is served to the WebView from `assets/panel/` under a private https origin
rather than `file://`, because the page keeps its token in `localStorage` and
calls `api.github.com`: a `file://` document gets an opaque origin, loses that
storage between launches and has its cross-origin requests blocked.

Because the pages ship in the APK, editing `docs/` no longer updates an
installed app on its own - rebuild and reinstall. The build verifies that each
page is really inside the APK and byte-identical to `docs/`, so a build that
quietly shipped an empty `assets/` fails instead of reaching a phone.

`ubuntu-latest` deliberately: Eden's native build is CMake + NDK and targets
Linux, and `windows-latest` would add path-length and line-ending failures for
no benefit.

Building locally is possible but not recommended — it needs ~6 GB of swap,
`kotlin.compiler.execution.strategy=in-process` to survive the OOM killer, and
takes over half an hour on a small machine:

```bash
bash tools/setup_env.sh
git clone --depth 50 https://git.eden-emu.dev/eden-emu/eden.git /work/eden
bash tools/apply_patch.sh --patch ./patch --eden /work/eden
bash tools/run_build.sh
```

## Running the tests

```bash
git clone --depth 50 https://git.eden-emu.dev/eden-emu/eden.git /tmp/eden
bash tools/apply_patch.sh --patch ./patch --eden /tmp/eden
bash tools/run_tests.sh --eden /tmp/eden
```

Two traps the harness now guards against, both of which once made the suite
look green while checking nothing:

- **Never** put `<eden>/src/common` on the include path. Eden ships its own
  `common/assert.h`, which shadows the standard `<cassert>`; every `assert()`
  then expands to nothing.
- Eden's `logging.h` calls `format.get()`, which needs **fmt 10**. Ubuntu
  packages fmt 9, so set `FMT_INCLUDE` to a fmt 10 checkout — otherwise every
  test that touches the layer fails to compile and is skipped.

11 of 14 tests build and run; the 3 that need most of `video_core` are reported
as SKIP, and the harness fails if that number grows.

## Honest status

Everything here is verified on the host — unit tests, `llvm-nm` symbol checks,
`aapt2` resource dumps, and a clean re-apply of the patch onto a fresh clone.
**None of it has been tested on physical Android hardware**, because none is available
in the build environment. Where a conclusion is a guess, it is labelled as one.

## Licence

GPL-3.0-or-later, matching upstream Eden.
