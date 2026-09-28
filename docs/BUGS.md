# Bugs found and fixed

Every entry states the evidence. Where a cause is unproven, it says so.
Bugs marked **(mine)** were introduced by this fork; the rest are upstream.

---

## v17 — the setup button disappeared, again

**Reported:** "Снова баг с общей папкой кнопка пропала" — the data-folder button was
missing from the first-run wizard for the third time.

My two previous attempts fixed the wrong thing. The first (v14) blamed
`pageSteps = { PageState.COMPLETE }`; the second (v15) changed it to `INCOMPLETE`.
Both were real problems, but neither was *the* problem, because the button kept vanishing.

### Actual cause: recycled ViewHolder state (mine, and upstream design)

`ViewPager2` is a `RecyclerView`, so one `SetupPageViewHolder` is reused across pages.
`SetupAdapter.onStepCompleted(pageFullyCompleted = true)` does this:

```kotlin
ViewUtils.hideView(binding.pageButtonContainer, 200)
ViewUtils.showView(binding.textConfirmation, 200)
```

and **`bind()` never undoes it**. The permissions page legitimately reports COMPLETE once
notifications are granted. When its holder is recycled for the data page, that page inherits
a hidden container: its buttons are created, and are invisible.

A second defect in the same method: `bind()` never called `removeAllViews()`, so re-binding
the same page (rotation, `notifyDataSetChanged`) appended a **second copy of every button**.

**Proof** — `tests/SetupPageTest.kt` models the adapter and fails on the old logic:

```
ViewHolder recycled from the permissions page to the data page:
  ok    permissions page shows "Done!"
  ok    data page did create its buttons
  FAIL  container visible again
  FAIL  "Done!" cleared
Same page bound twice:
  FAIL  still four buttons, not eight
```

After the fix all 12 assertions pass.

### Second cause: a page that collapses while still useful (mine)

Even with the reset, `pageFullyCompleted` hid the container wholesale — including the
data-folder button, which is *never* "done": pointing at a different folder is valid at any
time. `onStepCompleted` now collapses the page only when no button is still actionable:

```kotlin
if (pageFullyCompleted && !hasActionableButton()) { ... }
```

A button reporting `BUTTON_ACTION_UNDEFINED` (optional but live, which is exactly the folder
button) keeps the page open.

**Files:** `patch/android/adapters/SetupAdapter.kt`

---

## v16 — resolution off by one (mine)

The launch report showed `resolution_setup: 1` while the overlay said `0.5x`, and the
profile comment promised 0.75x. The real enum is:

```
ENUM(ResolutionSetup, Res1_4X=0, Res1_2X=1, Res3_4X=2, Res1X=3, Res5_4X=4, Res3_2X=5, Res2X=6, ...)
```

All **ten** `resolution_setup` values in `device_profiles.cpp` were one short of the value
their own comment described — `"1"` labelled "0.75x" is actually 0.5x. Every Mali profile
rendered blurrier than advertised.

Fixed per entry. The report now prints the scale (`1x (native)`), not the bare index; the
naked number is what hid the error.

## v16 — ASTC recompressed into an unsupported format (upstream)

`maxwell_to_vk.cpp:248` selects BC1/BC3 when recompression is enabled, **without checking
`textureCompressionBC`**. That this matters is proved by the branch 20 lines below, which
transcodes BCn away "on hardware that doesn't support BCn natively". Mali typically samples
ASTC but not BC — so the emulator can choose a format the GPU cannot sample.

**First fix was wrong and was reverted.** Overriding the format at the point of use
desynchronises three independent consumers — format selection (`maxwell_to_vk.cpp:248`),
size accounting (`texture_cache/util.cpp:611`) and the compressor
(`texture_cache/util.cpp:943`) — corrupting the upload buffer. Worse than the original bug.

Correct fix: normalise the *setting* once in `Device::Device()`, where BC support is known,
so all three consumers agree.

## v16 — three false statements in the launch report (mine)

- `astc_recompression: 0` was described as "textures are recompressed" — 0 means **off**.
- `use_speed_limit` at 100% was reported as "frames discarded on purpose" — 100% is normal
  speed, and flagging it sent the user chasing a non-problem.
- Resolution printed a bare enum index, meaningless to a reader.

---

## Earlier

| Version | Bug | Evidence |
|---|---|---|
| v12 (mine) | `SetWindowAdaptPass` returned before `layers.clear()`; stale descriptors → device lost → app dropped to the game list on any settings change | traced through `applySettings() → RefreshBaseSettings() → SetWindowAdaptPass()` |
| v11 (mine) | `ApplyCurrentOnStartup` re-applied the mode on every launch, silently overwriting manual edits — "quality settings do nothing to the FPS" | test: set 2 → after launch 3 (old) vs 2 (new) |
| v11 | `load/` never created, so mods were ignored; Android never calls `Common::FS::CreateEdenPaths()` | added `SharedDataDirectory.ensureLayout()`, 17 directories |
| v5 (mine) | Use-after-free: TextureCache registered a memory donor capturing `this` with no deregistration | ASan: `stack-use-after-scope in FakeCache::FakeCache` → after fix, `reclaimed 0 MiB (no crash)` |

---

## v18 — the game list vanished after choosing a shared folder

**Reported:** "игру не находит ... вчера видели, сегодня уже не видят", with keys and
firmware still detected. Screenshots confirm it: Keys/Firmware both green, game list empty.

### Cause (mine)

`SharedDataDirectory.redirectNow()` calls `NativeConfig.reloadGlobalConfig()` so the new
data root's settings take effect. That chain is:

```
reloadGlobalConfig() -> AndroidConfig::ReloadAllValues()   android_config.cpp:21
                     -> ReadAndroidValues() -> ReadPathValues()
                     -> AndroidSettings::values.game_dirs.clear()   android_config.cpp:70
```

`game_dirs` lives in `config.ini`, which is **per data root**. Point the emulator at a
shared folder that has no `config.ini` of its own and the list is cleared and repopulated
from nothing. Keys and firmware survive because they are files on disk, not config entries -
which is exactly the asymmetry the screenshots show.

Fixed by capturing the folders before the redirect and merging them back afterwards.
Merging rather than overwriting lets both installations contribute a folder.

**Proof** — `tests/RedirectTest.kt` models the per-root config and fails on the old logic:

```
v17 behaviour (the reported bug):
  ok    game folders are gone after redirect
fixed - shared folder has no config of its own:
  ok    the folder was carried over
fixed - shared folder lists its own folders:
  ok    both folders present - merged, not replaced
fixed - no duplicates when both roots list the same folder:
  ok    listed once, not twice
```

## v18 — the data-folder button was disabled, not missing

The button never disappeared. It reported `BUTTON_ACTION_COMPLETE` once a shared folder was
active, and the adapter greys completed buttons out and sets `isEnabled = false`
(`SetupAdapter.kt:140`). A greyed-out button is indistinguishable from a missing one - the
screenshot shows it faint above Keys/Firmware/Games.

Wrong state for this button: keys and firmware are done once installed, but *re-picking a
folder is valid at any time*. It now always reports `UNDEFINED`.

## v18 — Utilities sections numbered out of order

Layout order is firmware, ROM, saves, shared folder, crash analysis; the labels read
1, 2, 3, **5**, **4**. Renumbered to match what is drawn.

## Tooling added in v18

- `tools/collect_logs.sh` - one-shot ADB capture: logcat, native crash lines, OOM kills,
  the layer log, and the on-device data root contents.
- `tools/emulator.sh` - arm64 AVD helper. It states plainly what it cannot verify: with no
  KVM for a foreign architecture the guest runs under QEMU TCG, and its Vulkan is
  SwiftShader, so no Mali-specific behaviour can be reproduced there.

---

## v20 — crash on leaving a game; mods and saves invisible

Leaving a game still died in the native list (icons + overlay + resume scan).
The home screen is now a PWA (`docs/library.html`) in a WebView: HTML
updates from GitHub Pages without an APK rebuild, and launching a game
opens EmulationActivity. Coming back does not rebuild a RecyclerView and
does not walk the disk.

Mods and saves are read from `<data root>/load` and
`<data root>/nand/user/save`. If those folders are empty, the page lists
other Eden installs on the device so the data root can be pointed at the
one that actually has them.

---

## v19 — crash on restart while searching for games

**Reported:** вылет при перезапуске APK, когда он ищет игры. Папки по
умолчанию ложились слоями и мешали запуску.

The previous fix stopped `GamesViewModel.init` from calling `getGames()`,
but two other walks still ran on every `onResume` and every cold start:

- `refreshStatusStrip()` → `SetupStatus.games()` → `scanOneFolder` plus
  `whyNotGames()` → `diagnoseRom` (a full header parse per file)
- `refreshFolderCards()` → `GameFolderScanner.scan()` (ContentResolver
  walk of every configured folder)

And a leftover default `game_path` (or a parent stacked on its child)
sent those walks through `nand` / `load` / `cache` / `sdmc` — the tree
`ensureLayout()` itself creates. That is the hang that looked like
"infinite search" and the crash that killed the process before the UI
was up.

**Fix**

- Startup shows the cached list only. A walk happens on pull-to-refresh
  or when the user adds a folder.
- The status strip and folder cards read the cache. They do not touch
  the document provider.
- No default game folder. `game_path` is deleted, never re-added.
- Parent+child in the same list collapses to the child.
- Layout directory names are never descended into.

**Proof** — `tests/LazyScanTest.kt`.

---

## v21 — the launcher showed one game and would not open the rest

**Reported:** «одну игру видит а остальные не видит а раз не видит то и не
открывает либо проблема в том что лаунчер не прокручивается влево вправо может
поэтому не видит».

The guess in the report — that the rail does not scroll — is the one thing that
was **not** wrong.

### Cause (mine)

`renderLauncher()` built the carousel out of `visibleGames()`, and that function
applies the search box and the filter chips of the **Список** tab:

```js
RAIL_LIST = visibleGames();   // поиск и FILTER — принадлежности «Списка»
```

Type anything into «Найти игру…», or leave a chip on «Сейв», and the carousel
shrank to whatever survived the filter. The other games were not off-screen —
they were not in the rail at all, so the dock's «Запустить» could not reach them
either.

Worse, `FILTER = 'fresh'` produced an **empty** rail and the message «нет игр —
откройте Список и добавьте папку» with a full library sitting behind it.

Scrolling was never broken: with 8 games the rail scrolls 623 px and ‹ › both
work. What made it *look* broken is the presentation — the scrollbar is hidden
(`scrollbar-width:none`) and only ~1.5 slides fit (`padding: 8px 18vw`, `.slide`
is `min(42vw, 220px)`), so a single tile on screen is the normal appearance of a
carousel holding many games.

### Fix

`railGames()` returns the whole library; the search box and the chips stay in
«Список», which is the tab they belong to. Both go through the same
`sortGames()`, so the two tabs cannot drift apart in order. The dock prints
`3 / 12`, so the size of the library is on screen and one tile can never again be
read as «одна игра».

### Proof

Measured in Chromium against the real page — one library of 8 games, six states:

```
                                  rail slides   list rows
search "номер 3" in Список              1            1    <- the report
filter Сейв (one game has a save)       1            1    <- the report
filter Не играли                        0            0    <- "нет игр" over 8 games
after the fix, every state              8            as filtered
```

`tests/LauncherRailTest.kt` models the logic and then checks the shipped page:
it fails on the old file (4 checks) and passes on the new one. It also asserts
that `docs/library.html` and `patch/android/assets/library.html` are
byte-identical — the APK is served the second one, so a fix applied to only the
first would never reach the phone.

**Files:** `docs/library.html`, `patch/android/assets/library.html`,
`tests/LauncherRailTest.kt`

---

## v22 — the launcher sees one game out of a console dump

**Reported:** «почини чтобы видел игры все от приставки а то то ли свайп
влево-вправо не работает то ли игр видит только одну и всё».

Both halves were true, and they were two separate bugs that look identical
from the outside: with a single tile on screen, "the rail does not scroll" and
"the library has one game" cannot be told apart.

### Cause 1 — the importer looked one level down (mine)

`GameHelper` walked the selected folder at **depth 1** unless the folder
dialog's "recursive search" switch was ticked, and the switch is off by
default:

```kotlin
val scanDepth = if (gameDir.deepScan) 3 else 1
```

A console dump is normally kept one folder per game — `Games/Blade Chimera/game.nsp`,
or `Switch/Blade Chimera [0100B7B00F2E800]/Exefs/main`. At depth 1 the walk
sees only the files lying loose in the chosen root, so a library of twenty
games contributes however many happen to sit in the top directory. On a
device where exactly one did, that is the report verbatim.

The same number was written twice. `GameFolderScanner.depthFor()` had its own
`if (deepScan) 3 else 1`, and `listGames()` defaulted to `maxDepth = 1` — the
folder screen and the library were three separate copies of a rule, which is
how the counter came to promise games the importer then refused.

### Fix

`depthFor()` is the single answer, and its shallow answer is **3**: the folder
itself plus two levels, which is `Games/Title/game.nsp` and
`Switch/Title [id]/Exefs/main` alike. The switch still buys the two deeper
levels. `GameHelper` asks the scanner instead of computing its own; the
importer now also refuses to enter the data root's layout directories
(`nand`, `load`, `cache`, …), which the previous fix had only guaranteed for
the one-level walk. `MAX_DIRECTORIES` was always the real bound.

### Cause 2 — the rail fought the finger (mine)

Three things, all visible only in the shipped page:

- **The cover was draggable.** `.slide img` had no `draggable="false"`, no
  `-webkit-user-drag:none` and no `user-select:none`, so on Android a long
  press on a cover started a native image drag that pre-empted the swipe.
- **Positioning used `scrollIntoView`**, which scrolls *every* scrollable
  ancestor. The page is the document; the rail is a `div`. Now `railCentre()`
  computes `offsetLeft` and calls `rail.scrollTo`.
- **The rail was rebuilt 20 times a minute.** `findGames()` polls
  `loadGames()` every 700 ms, and each call replaced `rail.innerHTML` and
  re-centred on the selected tile — a rebuild landing mid-flick threw the
  carousel back to where it started. The rail is now only rebuilt when the
  set of paths changes (`RAIL_SIG`), the selection survives a rebuild by
  path, and the scroll listener cannot override a programmatic scroll
  (`RAIL_BUSY_UNTIL`). Arrow keys step the rail, since it has no focusable
  field of its own.

### Also fixed on the way

- **No keys no longer means "no games".** `getGames()` used to answer
  `emptyList()` and clear `cachedGameList` when keys were absent, and the
  panel's `rememberedGames()` did the same — so a library scanned yesterday
  became "no games" today. Both now return the remembered list; the status
  strip still says «Клюки ✕», which is the true part.
- **An empty scan clears the list.** `GamesFragment` collected
  `if (it.isNotEmpty()) setAdapter(it)`, so a scan that found nothing left the
  *previous* list on screen under a "no games" notice.
- **The importer honours the layout rule v19 promised.** v19 fixed the
  hang by refusing to descend into `nand` / `load` / `cache` — but it only
  taught `GameFolderScanner` that, not `addGamesRecursive`. At depth 1 that
  was invisible, because the data root's own children are already one level
  down. The moment the importer goes deeper it walks the same tree v19 called
  a hang, so it now checks the directory name too. The invariant now lives in
  one place and both walkers read it.

### Why a deeper walk is not what crashed the device

patch2's `GameHelper` raises the depth and `build2.yml` records why they
stopped: the scan was tried at 24, then 8, and a build at the native 3
crashed exactly the same. The crash that mattered was the build flavour —
six device crashes, all `assembleLegacy`, all `-DYUZU_LEGACY=ON` on a Mali-G57,
where upstream's own mainline APK runs on the same phone. Raising the depth
was never the variable, so it was abandoned for a reason that has since been
answered. Note also that `build2.yml` triggers on `patch2/**`: it builds a
different tree from the one here, and nothing in this fix touches it.

### Proof — `tests/DeepLibraryTest.kt`, `tests/LauncherRailTest.kt`

Both fail on the old tree and pass on the new one:

```
DeepLibraryTest    on HEAD: 11 checks failed   after the fix: all checks passed
LauncherRailTest   on HEAD: 10 checks failed   after the fix: all checks passed
```

`DeepLibraryTest` models the two walks over an in-memory tree and pins the
property that actually broke — the counter and the importer must agree, and
neither may enter a layout directory — then reads the shipped sources, which
is what catches the two copies of the depth rule drifting apart again.
`LauncherRailTest` already checked the data (v21); it now also checks the
gesture, which nothing was asserting.

**Files:** `patch/android/utils/GameFolderScanner.kt`,
`patch/android/utils/GameHelper.kt`, `patch/android/utils/LivePanel.kt`,
`patch/android/ui/GamesFragment.kt`, `docs/library.html`,
`patch/android/assets/library.html`, `tests/DeepLibraryTest.kt`,
`tests/LauncherRailTest.kt`, `tests/GameCountTest.kt`

**Unproven:** no Android device was available to measure a real swipe. The
gesture fixes are argued from the page and from how a WebView handles image
drag; they are not a measurement. `CarouselRecyclerView` — the native
landscape list — is upstream and was not touched.

---

## Open / unproven

- **Crash a few seconds into Blade Chimera (NSP).** Not reproduced; no device logs. The
  launch report now records the loader status and shows it as the first line, so the next
  occurrence should name its own cause.
- **20 FPS in menus vs 40 in game.** Not explained. Presentation logic is shared.
- **Firmware compression is impossible.** Measured on 200 MB: `xz -9` produced 209,768,460
  bytes from 209,766,400 — 2 KB *larger*. NCA content is encrypted, so it is incompressible.
