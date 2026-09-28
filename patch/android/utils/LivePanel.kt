// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.os.Environment
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.model.Game

/**
 * Данные для PWA-интерфейса. Страница живёт на GitHub Pages и в assets;
 * APK только читает диск и запускает игру.
 */
object LivePanel {

    const val BRIDGE_VERSION = 16

    private const val PANEL_URL = "https://sj0404-collab.github.io/symbiosis/library.html"

    const val OFFLINE_URL = "file:///android_asset/library.html"

    // Без метки времени: иначе WebView перезагружает страницу каждую минуту
    // и список мигает пустым.
    fun panelUrl(): String = "$PANEL_URL?bridge=$BRIDGE_VERSION"

    fun statusJson(context: Context): String {
        val items = JSONArray()
        runCatching { SetupStatus.all(context) }.getOrDefault(emptyList()).forEach { item ->
            items.put(
                JSONObject().apply {
                    put("label", runCatching { context.getString(item.labelRes) }.getOrDefault("?"))
                    put("present", item.present)
                    put("detail", item.detail)
                    put("bytes", item.bytes ?: 0L)
                    put("size", item.bytes?.takeIf { it > 0 }
                        ?.let { GameFolderScanner.humanSize(it) } ?: "")
                }
            )
        }
        return JSONObject().apply {
            put("items", items)
            put("dataRoot", runCatching { SetupStatus.dataRoot() }.getOrDefault("—"))
        }.toString()
    }

    /** Из кэша и конфига. Диск не обходится. */
    fun foldersJson(context: Context): String {
        val arr = JSONArray()
        val dirs = runCatching { NativeConfig.getGameDirs().toList() }.getOrDefault(emptyList())
        val cached = rememberedGames()
        dirs.forEach { dir ->
            val name = GameFolderScanner.displayNameOf(dir.uriString)
            val prefix = GameFolderScanner.pathOf(dir.uriString)
            val inFolder = cached.filter {
                val p = GameFolderScanner.pathOf(it.path)
                p == prefix || p.startsWith("$prefix/")
            }
            // Размер — список файлов через SAF, без разбора ROM.
            val listed = runCatching {
                GameFolderScanner.listFilesFlat(context, dir.uriString)
            }.getOrDefault(emptyList())
            val bytes = listed.sumOf { it.bytes }
            arr.put(
                JSONObject().apply {
                    put("uri", dir.uriString)
                    put("name", name)
                    put("games", inFolder.size)
                    put("bytes", bytes)
                    put("size", if (bytes > 0) GameFolderScanner.humanSize(bytes) else "")
                    put("skipped", 0)
                    put("unreadable", false)
                }
            )
        }
        return JSONObject().put("folders", arr).toString()
    }

    fun keysPresent(): Boolean =
        runCatching { NativeLibrary.areKeysPresent() }.getOrDefault(false)

    /**
     * The library as the last successful scan left it.
     *
     * Reads the cache and the persisted copy, never a folder - a page that
     * asks for the list must not be able to start a walk of the disk.
     *
     * It used to answer an empty list whenever keys were missing, so a
     * library that was scanned yesterday became "no games" today, and the
     * one thing the panel can say about it is on the status strip: «Ключи
     * ✕». Games whose title, path and cover are already known are shown;
     * what cannot be done without keys is written next to them, not instead
     * of them.
     */
    fun rememberedGames(): List<Game> =
        runCatching { GameHelper.rememberedGames() }.getOrDefault(emptyList())

    fun gamesJson(): String {
        val arr = JSONArray()
        rememberedGames().forEach { g ->
            val probe = saveProbe(g)
            val addons = runCatching { GameAddons.visibleFor(g) }.getOrDefault(emptyList())
            arr.put(
                JSONObject().apply {
                    put("title", g.title)
                    put("path", g.path)
                    put("programId", g.programId)
                    put("developer", g.developer)
                    put("saveDir", probe.path)
                    put("hasSave", probe.bytes >= MIN_SAVE_BYTES)
                    put("saveBytes", probe.bytes)
                    put("saveSize", if (probe.bytes >= MIN_SAVE_BYTES)
                        GameFolderScanner.humanSize(probe.bytes) else "")
                    put("addons", GameAddons.toJson(addons))
                    put("titleId", titleIdHex(g.programId))
                    put("version", g.version)
                    val bytes = fileBytesOf(g.path)
                    put("fileBytes", bytes)
                    put("fileSize", if (bytes > 0) GameFolderScanner.humanSize(bytes) else "")
                    val last = lastPlayedOf(g.path)
                    put("lastPlayed", last)
                    put("lastPlayedLabel", formatLastPlayed(last))
                    val shBytes = runCatching { GameCardMeta.shaderBytes(titleIdHex(g.programId)) }.getOrDefault(0L)
                    put("shaderBytes", shBytes)
                    put("shaderSize", if (shBytes > 0) GameFolderScanner.humanSize(shBytes) else "")
                    val meta = runCatching { GameCardMeta.forGame(g) }.getOrNull()
                    if (meta != null) {
                        put("play", meta.playLabel)
                        put("playSeconds", meta.playSeconds)
                        put("shots", meta.shots)
                        put("lastShot", meta.lastShot)
                    }
                }
            )
        }
        return JSONObject().put("games", arr).put("keys", keysPresent()).toString()
    }

    fun memoryJson(): String = runCatching {
        NativeSymbiosis.getMemoryJson()
    }.getOrDefault("""{"leftMb":0,"warn":false,"note":"память недоступна"}""")

    fun prepareShaders(): String {
        var flipped = 0
        runCatching {
            val cls = Class.forName("org.yuzu.yuzu_emu.features.settings.model.BooleanSetting")
            val setBool = cls.methods.firstOrNull { it.name == "setBoolean" && it.parameterTypes.size == 1 }
            for (v in (cls.enumConstants ?: emptyArray())) {
                val n = (v as Enum<*>).name
                if (n.contains("DISK_SHADER") || n.contains("ASYNCHRONOUS_SHADER")) {
                    setBool?.invoke(v, true)
                    flipped++
                }
            }
            NativeConfig.saveGlobalConfig()
        }
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val cache = root?.let { File(it, "shader") }
        val size = cache?.let { GameFolderScanner.directoryBytes(it.absolutePath) } ?: 0L
        return JSONObject().apply {
            put("ok", true)
            put("enabled", flipped)
            put("path", cache?.absolutePath ?: "")
            put("size", if (size > 0) GameFolderScanner.humanSize(size) else "пусто")
            put(
                "note",
                "Диск-кэш включён. Полный прогрев бывает только в игре — " +
                    "зайдите в меню и пробегитесь по локациям один раз. " +
                    "Следующие запуски берут готовые шейдеры и не зависают."
            )
        }.toString()
    }

    /** Обложка JPEG, base64. Пустая строка — нет иконки, не ошибка. */
    fun iconJpeg(path: String): String = encodeIcon(path, 128, 72)

    /** Большая обложка для карусели лаунчера. */
    fun coverJpeg(path: String): String = encodeIcon(path, 360, 78)

    private fun encodeIcon(path: String, maxPx: Int, quality: Int): String = runCatching {
        val raw = GameMetadata.getIcon(path)
        if (raw == null || raw.isEmpty()) return@runCatching ""
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching ""
        var sample = 1
        while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            ?: return@runCatching ""
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrDefault("")

    /** Последний скриншот с диска. Пустая строка — файла нет, не заглушка. */
    fun shotJpeg(path: String): String = GameCardMeta.shotJpeg(path)

    fun filesJson(context: Context, uriString: String): String {
        val arr = JSONArray()
        runCatching { GameFolderScanner.listFilesFlat(context, uriString) }
            .getOrDefault(emptyList())
            .forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("name", e.name)
                        put("bytes", e.bytes)
                        put("size", GameFolderScanner.humanSize(e.bytes))
                        put("launchable", GameFolderScanner.isLaunchable(e.name))
                    }
                )
            }
        return JSONObject().put("files", arr).toString()
    }

    fun dataRootJson(context: Context): String {
        val path = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val load = path?.let { File(it, "load") }
        val saves = path?.let { File(it, "nand/user/save") }
        return JSONObject().apply {
            put("path", path ?: "")
            put("hasLoad", load?.isDirectory == true)
            put("hasSaves", saves?.isDirectory == true)
            put("loadCount", load?.listFiles()?.size ?: 0)
            put("savesCount", saves?.listFiles()?.size ?: 0)
        }.toString()
    }

    fun modsJson(): String {
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val load = root?.let { File(it, "load") }
        val obj = JSONObject()
        obj.put("path", load?.absolutePath ?: "")
        if (load == null || !load.isDirectory) {
            obj.put("emptyReason", "папки load/ нет в корне данных — выберите папку, где лежит официальный Eden (там keys, nand, load)")
            obj.put("items", JSONArray())
            return obj.toString()
        }
        val items = JSONArray()
        // Только папки TitleID, в которых реально лежат файлы.
        // Пустые каталоги от прошлых прогонов и мусор в load/ давали
        // «8 модов» при одном настоящем.
        load.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            if (!looksLikeTitleId(dir.name) && !hasModPayload(dir)) return@forEach
            if (!hasModPayload(dir)) return@forEach
            val kids = dir.listFiles()?.joinToString(", ") { it.name } ?: ""
            items.put(
                JSONObject().apply {
                    put("titleId", dir.name)
                    put("path", dir.absolutePath)
                    put("contents", kids.ifBlank { "пусто" })
                }
            )
        }
        obj.put("items", items)
        if (items.length() == 0) {
            obj.put("emptyReason", null)
        }
        return obj.toString()
    }

    fun savesJson(): String {
        val picked = runCatching { SaveSource.statusJson() }.getOrNull()
            ?.let { JSONObject(it) }
        if (picked != null && picked.optInt("titles") > 0) {
            return JSONObject().apply {
                put("path", picked.optString("path"))
                put("items", picked.optJSONArray("items") ?: JSONArray())
                put("fromPicked", true)
            }.toString()
        }
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val dir = root?.let { File(it, "nand/user/save") }
        val obj = JSONObject()
        obj.put("path", dir?.absolutePath ?: "")
        if (dir == null || !dir.isDirectory) {
            obj.put("emptyReason", "папки nand/user/save нет. Выберите папку сейвов один раз — как ключи.")
            obj.put("items", JSONArray())
            return obj.toString()
        }
        val items = JSONArray()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { user ->
            if (user.name == "0000000000000000") return@forEach
            user.listFiles()?.filter { it.isDirectory }?.forEach { title ->
                if (fileBytes(title) < MIN_SAVE_BYTES) return@forEach
                items.put(
                    JSONObject().apply {
                        put("name", title.name)
                        put("detail", "профиль ${user.name.take(8)}…")
                    }
                )
            }
        }
        obj.put("items", items)
        return obj.toString()
    }

    private fun looksLikeTitleId(name: String): Boolean =
        name.length == 16 && name.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Меньше этого — пустые слоты, которые игра рисует как NEW GAME. */
    private const val MIN_SAVE_BYTES = 2048L

    data class SaveProbe(val path: String, val bytes: Long)

    /**
     * Настоящий сейв — файлы с данными в nand/user/save/<user>/<titleid>/.
     * Папку слота Eden создаёт при первом запуске, даже если слоты пустые.
     * Именно поэтому на карточке писало «сейв есть», а в игре — NEW GAME.
     */
    fun saveProbe(game: Game): SaveProbe {
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
            ?: return SaveProbe("", 0)
        val nand = File(root, "nand/user/save")
        val tid = titleIdHex(game.programId)
        var total = 0L
        var path = ""
        runCatching {
            val sd = game.saveDir
            if (sd.isNotBlank()) {
                val b = fileBytes(File(sd))
                if (b > 0) { total += b; path = sd }
            }
        }
        if (tid.isNotEmpty() && nand.isDirectory) {
            nand.listFiles()?.forEach { user ->
                if (!user.isDirectory || user.name == "0000000000000000") return@forEach
                val folder = File(user, tid)
                val b = fileBytes(folder)
                if (b > 0) {
                    total += b
                    if (path.isEmpty()) path = folder.absolutePath
                }
            }
        }
        return SaveProbe(path, total)
    }

    fun titleIdHex(programId: String): String {
        val raw = programId.trim()
        if (looksLikeTitleId(raw)) return raw.uppercase()
        val n = raw.toLongOrNull() ?: return ""
        if (n == 0L) return ""
        return n.toString(16).uppercase().padStart(16, '0')
    }

    fun realSaveBytes(nandSave: File): Long {
        if (!nandSave.isDirectory) return 0
        var total = 0L
        nandSave.listFiles()?.forEach { user ->
            if (!user.isDirectory || user.name == "0000000000000000") return@forEach
            total += fileBytes(user)
        }
        return total
    }

    private fun fileBytes(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        var sum = 0L
        val q = ArrayDeque<File>()
        q.add(dir)
        var steps = 0
        while (q.isNotEmpty() && steps < 80) {
            steps++
            val kids = q.removeFirst().listFiles() ?: continue
            for (k in kids) {
                if (k.isFile) sum += k.length() else if (k.isDirectory) q.add(k)
            }
        }
        return sum
    }

    private fun dirHasFiles(dir: File): Boolean {
        val q = ArrayDeque<File>()
        q.add(dir)
        var steps = 0
        while (q.isNotEmpty() && steps < 40) {
            steps++
            val cur = q.removeFirst()
            val kids = cur.listFiles() ?: continue
            for (k in kids) {
                if (k.isFile && k.length() > 0) return true
                if (k.isDirectory) q.add(k)
            }
        }
        return false
    }

    private fun hasModPayload(dir: File): Boolean = dirHasFiles(dir)

    /** Другие установки Eden на устройстве — чтобы не гадать, куда делись моды. */
    fun suggestRootsJson(context: Context): String {
        val current = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val candidates = mutableListOf<Pair<String, String>>()
        val sd = Environment.getExternalStorageDirectory()
        candidates += "официальный Eden" to File(sd, "Android/data/dev.eden.eden_emulator/files").absolutePath
        candidates += "Eden legacy" to File(sd, "Android/data/dev.legacy.eden_emulator/files").absolutePath
        candidates += "Eden Debug" to File(sd, "Android/data/dev.legacy.eden_emulator.debug/files").absolutePath
        candidates += "/sdcard/Eden" to File(sd, "Eden").absolutePath
        candidates += "/sdcard/Eden/files" to File(sd, "Eden/files").absolutePath
        candidates += "/sdcard/Eden Debug" to File(sd, "Eden Debug").absolutePath
        runCatching { SharedDataDirectory.privatePath(context) }.getOrNull()?.let {
            candidates += "приватная этого APK" to it
        }

        val arr = JSONArray()
        val seen = HashSet<String>()
        for ((label, path) in candidates) {
            if (!seen.add(path)) continue
            if (path == current) continue
            val dir = File(path)
            if (!dir.isDirectory) continue
            val keys = File(dir, "keys/prod.keys").isFile
            val mods = File(dir, "load").listFiles()?.isNotEmpty() == true
            val saves = File(dir, "nand/user/save").listFiles()?.isNotEmpty() == true
            if (!keys && !mods && !saves) continue
            arr.put(
                JSONObject().apply {
                    put("label", label)
                    put("path", path)
                    put("keys", keys)
                    put("mods", mods)
                    put("saves", saves)
                }
            )
        }
        return JSONObject().put("roots", arr).toString()
    }

    fun gameFrom(path: String, title: String): Game = Game(
        title = title.ifBlank { path.substringAfterLast('/').ifBlank { "game" } },
        path = path
    )

    fun fileBytesOf(path: String): Long {
        if (path.isBlank()) return 0
        if (path.startsWith("/")) {
            val f = File(path)
            return if (f.isFile) f.length() else 0L
        }
        return runCatching { NativeLibrary.getSize(path) }.getOrDefault(0L).coerceAtLeast(0L)
    }

    fun lastPlayedOf(path: String): Long = runCatching {
        val ctx = org.yuzu.yuzu_emu.YuzuApplication.appContext
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .getLong(path + "_LastPlayed", 0L)
    }.getOrDefault(0L)

    fun markPlayed(path: String) {
        if (path.isBlank()) return
        runCatching {
            val ctx = org.yuzu.yuzu_emu.YuzuApplication.appContext
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit()
                .putLong(path + "_LastPlayed", System.currentTimeMillis())
                .apply()
        }
    }

    internal fun formatLastPlayed(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0L) return ""
        fun startOfDay(ts: Long): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = ts
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        val startToday = startOfDay(now)
        val day = 24L * 3600_000
        return when {
            ms >= startToday -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = ms
                "сегодня %02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            }
            ms >= startToday - day -> "вчера"
            else -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = ms
                "%02d.%02d".format(cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1)
            }
        }
    }

    fun removeFolder(uri: String): String {
        if (uri.isBlank()) return org.json.JSONObject().put("ok", false).put("message", "пустой uri").toString()
        val dirs = runCatching { NativeConfig.getGameDirs().toList() }.getOrDefault(emptyList())
        val next = dirs.filter { it.uriString != uri }
        if (next.size == dirs.size) {
            return org.json.JSONObject().put("ok", false).put("message", "такой папки нет в списке").toString()
        }
        runCatching { NativeConfig.setGameDirs(next.toTypedArray()) }
        runCatching { NativeConfig.saveGlobalConfig() }
        return org.json.JSONObject().put("ok", true)
            .put("message", "папка убрана из библиотеки, файлы на диске целы")
            .put("left", next.size)
            .toString()
    }

    fun shouldLaunchKenji(context: Context): Boolean = Spaces.shouldUseKenjiPlayer(context)

    fun shotsJson(path: String, title: String): String {
        val g = org.yuzu.yuzu_emu.utils.GameHelper.cachedGameList.firstOrNull { it.path == path }
            ?: gameFrom(path, title)
        return GameCardMeta.listShotsJson(g)
    }

    /**
     * Two players. Eden/Symbiosis is compiled in. Kenji, if downloaded,
     * starts KenjiPlayerActivity in the :kenji process.
     */
    fun enginesJson(context: Context): String {
        val raw = EnginePreference.selectedRaw(context)
        val kenjiReady = EngineLoader.state(context, EngineLoader.Engine.KENJI) is EngineLoader.State.Ready
        val launch = if (raw == EngineLoader.Engine.KENJI && kenjiReady)
            EngineLoader.Engine.KENJI else EngineLoader.Engine.EDEN
        val arr = JSONArray()
        EngineLoader.Engine.values().forEach { e ->
            val st = EngineLoader.state(context, e)
            val usable = st is EngineLoader.State.Builtin || st is EngineLoader.State.Ready
            val state = when (st) {
                is EngineLoader.State.Builtin -> "builtin"
                is EngineLoader.State.Ready -> "ready"
                is EngineLoader.State.Missing -> "missing"
                is EngineLoader.State.Broken -> "broken"
            }
            val note = when (st) {
                is EngineLoader.State.Missing ->
                    "нет файла · ${(EngineLoader.KNOWN_SIZE[e] ?: 0L) / 1048576} МБ"
                is EngineLoader.State.Broken -> st.reason
                is EngineLoader.State.Ready -> "скачан · отдельный плеер в процессе :kenji"
                is EngineLoader.State.Builtin -> "встроено, запускает игры"
            }
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("label", e.label)
                    .put("state", state)
                    .put("usable", usable)
                    .put("selected", raw == e)
                    .put("launches", e == launch)
                    .put("note", note)
            )
        }
        return JSONObject()
            .put("current", raw.id)
            .put("currentLabel", raw.label)
            .put("launch", launch.id)
            .put("launchLabel", launch.label)
            .put("items", arr)
            .put("note", "два плеера: Symbiosis в основном процессе, второе ядро — в :kenji. Падение одного не закрывает лаунчер.")
            .toString()
    }

    fun selectEngine(context: Context, id: String): String {
        val engine = EngineLoader.Engine.values().firstOrNull { it.id == id }
            ?: return JSONObject().put("ok", false).put("message", "нет ядра «$id»").toString()
        val st = EngineLoader.state(context, engine)
        val usable = st is EngineLoader.State.Builtin || st is EngineLoader.State.Ready
        if (!usable) {
            val why = when (st) {
                is EngineLoader.State.Missing -> "сначала скачайте второе ядро (~${(EngineLoader.KNOWN_SIZE[engine] ?: 0L) / 1048576} МБ) — кнопка «Ядра» на лаунчере"
                is EngineLoader.State.Broken -> (st as EngineLoader.State.Broken).reason
                else -> "ядро недоступно"
            }
            return JSONObject().put("ok", false).put("needDownload", st is EngineLoader.State.Missing)
                .put("message", why).toString()
        }
        EnginePreference.select(context, engine)
        val kenjiReady = EngineLoader.state(context, EngineLoader.Engine.KENJI) is EngineLoader.State.Ready
        val launches = engine == EngineLoader.Engine.EDEN ||
            (engine == EngineLoader.Engine.KENJI && kenjiReady)
        return JSONObject().put("ok", true).put("id", engine.id).put("label", engine.label)
            .put("launches", launches)
            .put(
                "message",
                if (engine == EngineLoader.Engine.KENJI)
                    "второе ядро · отдельный плеер. Если упадёт — закроется только он."
                else "основное ядро Symbiosis"
            )
            .toString()
    }
}
