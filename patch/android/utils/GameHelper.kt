// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GameDir
import org.yuzu.yuzu_emu.model.MinimalDocumentFile
import androidx.core.content.edit
import androidx.core.net.toUri

object GameHelper {
    private const val KEY_OLD_GAME_PATH = "game_path"

    /** Потолок на одно чтение при разборе контейнера — 1 МиБ. */
    private const val MAX_PROBE_READ = 0x100000
    const val KEY_GAMES = "Games"

    var cachedGameList = mutableListOf<Game>()

    /**
     * Прошёл ли хоть один обход за этот запуск.
     *
     * Пустой cachedGameList не отличить от «ещё не смотрели»: и то и
     * другое даёт размер 0. Панель состояния принимала второе за первое
     * и шла сканировать диск, чтобы «проверить».
     */
    @Volatile
    var hasScanned = false
        private set

    /**
     * Настройки, доступные всегда.
     *
     * Было `lateinit var preferences` и присваивание только внутри
     * getGames(). `getGame()` зовётся ещё и из добавления игры по одной
     * файловой кнопкой - там lateinit бросал UninitializedPropertyAccess-
     * Exception, и добавление падало целиком.
     */
    private val prefs: SharedPreferences
        get() {
            val cached = prefsCache
            if (cached != null) return cached
            val fresh = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
            prefsCache = fresh
            return fresh
        }

    @Volatile
    private var prefsCache: SharedPreferences? = null

    private var preferences: SharedPreferences
        get() = prefs
        set(value) {
            prefsCache = value
        }

    /**
     * Файлы, которые библиотека видит, но запустить не может.
     *
     * Ими и объясняется «из сотен файлов видна одна»: сжатый NSZ ядро не
     * открывает, `getIsValid` возвращает false, и раньше запись просто не
     * создавалась. Теперь она есть - с причиной словами, - и панель может
     * предложить распаковку вместо пустого экрана.
     */
    data class Sidecar(
        val name: String,
        val path: String,
        val title: String,
        val reason: String,
        val kind: String,
        /** Короткая пометка на плитке; пусто — взять по виду отказа. */
        val note: String = "",
        /** «дополнение» / «обновление»; пусто — это игра. */
        val role: String = ""
    )

    @Volatile
    var sidecar: List<Sidecar> = emptyList()
        private set

    /** Итог последнего обхода. Показывается строкой «что нашлось и почему». */
    data class ScanStats(
        var folders: Int = 0,
        var files: Int = 0,
        var games: Int = 0,
        var updates: Int = 0,
        var dlc: Int = 0,
        var compressed: Int = 0,
        var failed: Int = 0,
        var millis: Long = 0
    ) {
        fun line(): String = buildString {
            append("$games ${if (games == 1) "игра" else "игр"}")
            if (updates > 0) append(" · $updates обновл.")
            if (dlc > 0) append(" · $dlc DLC")
            if (compressed > 0) append(" · $compressed сжатых")
            if (failed > 0) append(" · $failed не прочитано")
            append(" · $files файлов за ${millis} мс")
        }
    }

    @Volatile
    var scanStats: ScanStats = ScanStats()
        private set

    /**
     * The last list the emulator managed to read, from memory or from disk.
     *
     * Never walks a folder and never parses a ROM header: it is what the
     * status strip, the panel and a start without keys may show without
     * turning into a full scan. Returns an empty list only when there has
     * never been a successful one.
     */
    /**
     * Игры из последнего обхода этого запуска.
     *
     * Отличается от [rememberedGames] тем, что не возвращает кэш из
     * настроек: если обхода ещё не было, ответ пустой, и вызывающий сам
     * решает, чем его заменить. Нужен панели, чтобы она показывала тот же
     * список, что и нативный лаунчер, а не устаревшую копию.
     */
    fun currentGames(): List<Game> = if (hasScanned) cachedGameList.toList() else emptyList()

    fun rememberedGames(): List<Game> {
        val live = runCatching { cachedGameList }.getOrDefault(emptyList())
        if (live.isNotEmpty()) return live
        // Локальная переменная, а не поле preferences: этот метод зовёт
        // WebView с потока JavaScript, а getGames() - с фоновой корутины,
        // и оба писали бы одно и то же поле. Значение у них всё равно
        // одинаковое, но общий lateinit зачем-то трогать из двух потоков.
        val stored = runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(
                YuzuApplication.appContext
            )
            prefs.getStringSet(KEY_GAMES, emptySet()) ?: emptySet()
        }.getOrDefault(emptySet())
        val games = stored.mapNotNull { raw ->
            runCatching { Json.decodeFromString<Game>(raw) }.getOrNull()
        }
        if (games.isNotEmpty()) {
            cachedGameList = games.toMutableList()
        }
        return games
    }

    /**
     * Метка «обход идёт».
     *
     * Приложение падало при запуске, пока шёл обход, и от этого не оставалось
     * ничего: система закрывает процесс, файл отчёта пишется только если
     * обработчик уже поставлен. Метка позволяет на следующем запуске сказать
     * прямо: прошлый обход не закончился — приложение упало или было закрыто
     * во время сканирования. Это отличает падение в обходе от падения на
     * отрисовке, и без logcat другого способа нет.
     */
    private fun scanMarker(context: Context): File =
        File(File(context.getExternalFilesDir(null), "symbiosis"), "scan-in-progress.txt")

    /** Прошлый обход не дошёл до конца. */
    fun lastScanUnfinished(context: Context): Boolean =
        runCatching { scanMarker(context).isFile }.getOrDefault(false)

    private fun markScanStart(context: Context) = runCatching {
        val file = scanMarker(context)
        file.parentFile?.mkdirs()
        file.writeText(System.currentTimeMillis().toString())
        Unit
    }

    private fun markScanEnd(context: Context) = runCatching { scanMarker(context).delete() }

    fun getGames(): List<Game> {
        val games = mutableListOf<Game>()
        val context = YuzuApplication.appContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)

        val gameDirs = mutableListOf<GameDir>()

        // Папка игр «по умолчанию» больше не появляется сама.
        //
        // Здесь читался старый ключ game_path и МОЛЧА добавлялся как
        // папка игр, причём с deepScan = true - обход на три уровня
        // вглубь. Пользователь такую папку не выбирал и в списке папок
        // её не видел; если она указывала на корень данных или на
        // родителя игровой папки, обход шёл по слоям вложенных
        // каталогов (nand, load, cache, sdmc - их создаёт ensureLayout)
        // и мешал запуску.
        //
        // Единственный источник папок теперь - конфиг, куда они попадают
        // только явным выбором пользователя. Устаревший ключ вычищается,
        // чтобы не всплыл позже.
        if (!preferences.getString(KEY_OLD_GAME_PATH, "").isNullOrEmpty()) {
            preferences.edit() { remove(KEY_OLD_GAME_PATH) }
        }
        gameDirs.addAll(NativeConfig.getGameDirs())

        // Папки, которые пропали из конфига, возвращаются из зеркала.
        //
        // Здесь раньше стояло схлопывание «слоёв»: если одна папка была
        // вложена в другую, родительская удалялась из конфига. Для списка
        // папок это необратимо и неверно: игры, лежавшие в корне родителя,
        // оставались без папки, а пользователь видел, что «папки сами
        // почистились». Вложенность теперь учитывается только на время
        // обхода (GameFolderList.coveredByParent), конфиг не переписывается.
        //
        // Второй источник потерь — недописанный конфиг после сбоя: на диске
        // остаётся одна первая папка. Зеркало (GameFoldersStore) хранит их
        // отдельно и здесь же возвращает недостающие.
        runCatching {
            val known = gameDirs.map { GameFolderList.Entry(it.uriString, it.deepScan) }
            val lost = GameFoldersStore.missing(known)
            if (lost.isNotEmpty()) {
                lost.forEach { gameDirs.add(GameDir(it.uri, it.deepScan)) }
                NativeConfig.setGameDirs(gameDirs.toTypedArray())
                GameFoldersStore.save(gameDirs.map { GameFolderList.Entry(it.uriString, it.deepScan) })
                Log.info("[Folders] возвращено из зеркала: ${lost.size}")
            }
        }

        // Без ключей заголовок ROM не расшифруется: getIsValid врёт или
        // пустой title, список выглядит «найденным» без обложек. Не кладём
        // такие записи и не затираем прошлый кэш пустотой.
        NativeLibrary.reloadKeys()
        val keysOk = runCatching { NativeLibrary.areKeysPresent() }.getOrDefault(false)
        if (!keysOk) {
            hasScanned = true
            // Раньше здесь стояло `cachedGameList = mutableListOf()` и
            // `return emptyList()`. Это стирало ровно то, что нужно было
            // показать: игры, найденные прошлым обходом, у которых уже есть
            // название, путь и обложка. Ключи потом подкладывают за минуту,
            // а список до этого - пустой: «приложение не видит мои игры»,
            // хотя три запуска назад видело двадцать.
            //
            // Обход пропущен (ниже он и был бы), prefs не перезаписаны -
            // `return` до записи кэша. Отдаём то, что уже известно, и
            // оставляем полосу состояния говорить правду про ключи.
            return rememberedGames()
        }


        // Reset metadata so we don't use stale information
        GameMetadata.resetMetadata()
        // И обложки тоже. Нативный кэш чистился, а кэш Coil - нет, поэтому
        // иконка, прочитанная со старыми ключами, оставалась серым
        // квадратом навсегда: игра уже запускается, а картинку берут из
        // памяти и файл больше не читают.
        runCatching { GameIconUtils.clearCache() }

        // Remove previous filesystem provider information so we can get up to date version info
        NativeLibrary.clearFilesystemProvider()

        val mountedContainerUris = mutableSetOf<String>()
        mountExternalContentDirectories(mountedContainerUris)

        val stats = ScanStats()
        val startedAt = System.currentTimeMillis()
        markScanStart(YuzuApplication.appContext)
        val side = mutableListOf<Sidecar>()

        val badDirs = mutableListOf<Int>()
        gameDirs.forEachIndexed { index: Int, gameDir: GameDir ->
            val gameDirUri = gameDir.uriString.toUri()
            val isValid = FileUtil.isTreeUriValid(gameDirUri)
            if (isValid) {
                // Глубина берётся у сканера, а не вычисляется здесь.
                //
                // Здесь стояло `if (deepScan) 3 else 1`, у GameFolderScanner -
                // то же самое, написанное отдельно. Две копии одного числа
                // разошлись, и счётчик папки рапортовал об одной игре там,
                // где импортёр молчал о трёх. Теперь число одно.
                val scanDepth = GameFolderScanner.depthFor(gameDir.deepScan)

                addGamesRecursive(
                    games,
                    FileUtil.listFiles(gameDirUri),
                    scanDepth,
                    mountedContainerUris,
                    side,
                    stats
                )
            } else {
                badDirs.add(index)
            }
        }

        // Папки без доступа НЕ удаляются из конфига.
        //
        // Здесь стояло удаление: `gameDirs.removeAt(...)` и запись укороченного
        // списка. Один обход при отозванном разрешении — и папка исчезала
        // навсегда, вместе со всеми играми, которые в ней были. Разрешение
        // отзывается не только пользователем: система делает это сама, если
        // процесс упал, а приложение настроек «почистило» права. Именно так
        // выглядит «после падения осталась одна папка» — и восстановить
        // остальные без этого исправления нельзя даже вручную, потому что
        // путь уже нигде не записан.
        //
        // Теперь папка остаётся в списке и помечается «нет доступа», а
        // вернуть её можно одним нажатием на том же экране.
        val blocked = badDirs.map { gameDirs[it].uriString }
        GameFoldersStore.markNoAccess(blocked)
        if (blocked.isNotEmpty()) {
            Log.warning("[Folders] нет доступа: ${blocked.size} из ${gameDirs.size}")
        }
        GameFoldersStore.keep(gameDirs.map { GameFolderList.Entry(it.uriString, it.deepScan) })

        // Одна и та же игра может быть найдена дважды — если пользователь
        // добавил и родительскую папку, и вложенную.
        //
        // Отсекать по `path` было недостаточно: путь — это весь URI, а он
        // отличается частью `/tree/…` у каждой папки, через которую файл
        // нашли. Родитель даёт
        //   …/tree/primary%3AGames/document/primary%3AGames%2FА%2Fигра.nsp
        // а сама вложенная папка —
        //   …/tree/primary%3AGames%2FА/document/primary%3AGames%2FА%2Fигра.nsp
        // Две строки, один файл, две плитки. Поэтому ключ берётся по
        // идентификатору документа (GameFolderList.fileKey).
        val seenFiles = HashSet<String>()
        val unique = games.filter { seenFiles.add(GameFolderList.fileKey(it.path)) }

        // Cache list of games found on disk
        val serializedGames = mutableSetOf<String>()
        unique.forEach {
            serializedGames.add(Json.encodeToString(it))
        }
        preferences.edit() {
            remove(KEY_GAMES)
                .putStringSet(KEY_GAMES, serializedGames)
        }

        markScanEnd(YuzuApplication.appContext)
        cachedGameList = unique.toMutableList()
        sidecar = side.toList()
        stats.folders = gameDirs.size
        stats.millis = System.currentTimeMillis() - startedAt
        scanStats = stats
        hasScanned = true
        return unique
    }

    fun restoreContentForGame(game: Game) {
        NativeLibrary.reloadKeys()

        val mountedContainerUris = mutableSetOf<String>()
        mountExternalContentDirectories(mountedContainerUris)
        mountGameFolderContent(Uri.parse(game.path), mountedContainerUris)
        NativeLibrary.addFileToFilesystemProvider(game.path)
    }

    // File extensions considered as external content, buuut should
    // be done better imo.
    private val externalContentExtensions = GameFormats.CONTAINERS

    private fun scanContentContainersRecursive(
        files: Array<MinimalDocumentFile>,
        depth: Int,
        onContainerFound: (MinimalDocumentFile) -> Unit
    ) {
        if (depth <= 0) {
            return
        }

        files.forEach {
            if (it.isDirectory) {
                scanContentContainersRecursive(
                    FileUtil.listFiles(it.uri),
                    depth - 1,
                    onContainerFound
                )
            } else {
                val extension = FileUtil.getExtension(it.uri).lowercase()
                if (externalContentExtensions.contains(extension)) {
                    onContainerFound(it)
                }
            }
        }
    }

    private fun addGamesRecursive(
        games: MutableList<Game>,
        files: Array<MinimalDocumentFile>,
        depth: Int,
        mountedContainerUris: MutableSet<String>,
        side: MutableList<Sidecar>,
        stats: ScanStats
    ) {
        if (depth <= 0) {
            return
        }

        files.forEach {
            if (it.isDirectory) {
                // Каталоги раскладки - не библиотека, сколько бы уровней
                // обход в них ни зашёл. nand, load и cache создаёт сам
                // SharedDataDirectory.ensureLayout, и если папка игр
                // оказалась родителем корня данных, обход уходил в них и
                // либо вяз, либо насчитывал себе тысячи игр. Счётчик папок
                // их пропускал, импортёр - нет.
                if (!GameFolderScanner.isLayoutName(GameFolderScanner.displayNameOf(it.uri.toString()))) {
                    addGamesRecursive(
                        games,
                        FileUtil.listFiles(it.uri),
                        depth - 1,
                        mountedContainerUris,
                        side,
                        stats
                    )
                }
            } else {
                val extension = FileUtil.getExtension(it.uri).lowercase()
                val filePath = it.uri.toString()
                val filename = FileUtil.getFilename(it.uri)
                stats.files++

                if (externalContentExtensions.contains(extension) &&
                    mountedContainerUris.add(filePath)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(filePath)
                }

                // Одно правило видимости на всех: GameFormats и LibraryIndex.
                //
                // Здесь стояло `Game.extensions.contains(extension) ||
                // GameFormats.isLaunchable(filename)` - и nsz/xcz/ncz,
                // которых в папке большинство, не попадали ни в список, ни
                // в отчёт. Пользователь видел единственный nsp, который
                // случайно оказался рядом.
                if (!LibraryIndex.isWanted(filename)) {
                    return@forEach
                }

                val entry = LibraryIndex.classify(filename)
                val base = LibraryIndex.titleFrom(filename).ifBlank { filename }

                when (entry.kind) {
                    LibraryIndex.Kind.COMPRESSED -> {
                        stats.compressed++
                        side.add(
                            Sidecar(filename, filePath, base, entry.reason, "compressed")
                        )
                        return@forEach
                    }
                    LibraryIndex.Kind.SPLIT_PART -> {
                        side.add(
                            Sidecar(filename, filePath, base, entry.reason, "split")
                        )
                        return@forEach
                    }
                    LibraryIndex.Kind.OTHER -> return@forEach
                    else -> Unit
                }

                // getGame() разбирает заголовок через native. Один битый файл
                // ронял весь обход (исключение из getIsValid/getProgramId
                // шло наверх, а обход - в корутине, которая не ловила), и
                // библиотека оставалась той частью, что успела прочитаться.
                val game = runCatching { getGame(it.uri, true, false) }.getOrNull()
                if (game == null) {
                    stats.failed++
                    // Прежде чем списывать отказ на ключи, читаем сам
                    // контейнер: имя таблицы PFS0 и билет доступны без
                    // расшифровки. На телефоне ключи были на месте (204
                    // записи, прошивка 22.0.0), а плитки всё равно говорили
                    // «проверьте ключи» — то есть текст был догадкой, а не
                    // причиной.
                    val probe = runCatching { probeContainer(it.uri) }.getOrNull()
                    val keys = keysOkNow()
                    val titleKeys = runCatching { titleKeyLines() }.getOrDefault(emptyList())
                    val titleKey = probe?.takeIf { it.titleId.isNotEmpty() }
                        ?.let { TitleKeys.has(titleKeys, it.titleId) }
                    // Причину называем сами, по байтам контейнера.
                    //
                    // Здесь был вызов NativeSymbiosis.romProblem на каждый
                    // неоткрывшийся файл — и приложение стало падать ровно во
                    // время обхода при запуске. Неудивительно: эта функция
                    // проходит путь загрузки игры внутри ядра (сессия
                    // эмуляции, ключи, разбор CNMT), а обход идёт фоном сразу
                    // после старта, когда ядро ещё не поднято. Падение в
                    // нативной части не ловится ни try, ни runCatching —
                    // процесс просто умирает.
                    //
                    // Поэтому в обходе только чистое чтение контейнера, а
                    // ответ ядра остаётся там, где его вызывают нажатием:
                    // вкладка «Папки» и диалог плитки.
                    val why = if (probe == null) {
                        "ядро не прочитало заголовок · проверьте ключи или распакуйте файл"
                    } else {
                        NspProbe.verdict(probe, keys, titleKey)
                    }
                    val keysNote = if (probe == null) "" else NspProbe.label(probe, keys, titleKey)
                    // Дополнение и обновление — не игра.
                    //
                    // На телефоне DLC Blade Chimera стоял в библиотеке как
                    // игра и получал ту же ошибку, что и игра: человек читал
                    // «игра не открывается» про файл, который игрой не
                    // является. Роль известна уже из имени и идентификатора
                    // (хвост 800 и выше), так что молчать об этом нельзя.
                    val role = when {
                        entry.kind == LibraryIndex.Kind.DLC -> "дополнение"
                        entry.kind == LibraryIndex.Kind.UPDATE -> "обновление"
                        else -> ""
                    }
                    // Роль важнее пометки о ключе: «дополнение» — это про то,
                    // что за файл, а не про то, что с ним не так.
                    val note = if (role.isNotEmpty()) role else keysNote
                    val text = if (role.isEmpty()) why else "Это $role, а не отдельная игра. $why"
                    side.add(Sidecar(filename, filePath, base, text, "unreadable", note, role))
                    return@forEach
                }
                when (entry.kind) {
                    LibraryIndex.Kind.UPDATE -> stats.updates++
                    LibraryIndex.Kind.DLC -> stats.dlc++
                    else -> stats.games++
                }
                games.add(game)
            }
        }
    }

    /**
     * Прочитать контейнер по байтам, без ядра и без ключей.
     *
     * Через дескриптор, а не через InputStream: у образа в несколько
     * гигабайт нужны только первые килобайты, а таблица файлов — по
     * известному смещению. Копировать файл целиком никто не станет.
     */
    private fun probeContainer(uri: Uri): NspProbe.Result? = runCatching {
        val context = YuzuApplication.appContext
        val name = uri.lastPathSegment ?: uri.toString()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            // Поток не закрываем отдельно: дескриптор принадлежит pfd, и
            // двойное закрытие — это лишний повод для исключения на ровном
            // месте, ровно во время обхода.
            val stream = java.io.FileInputStream(pfd.fileDescriptor)
            run {
                val channel = stream.channel
                fun readAt(offset: Long, size: Int): ByteArray {
                    if (size <= 0) return ByteArray(0)
                    // Потолок на одно чтение: размеры приходят из самого
                    // файла, и битый заголовок не должен превращаться в
                    // попытку выделить гигабайт во время обхода.
                    val want = if (size > MAX_PROBE_READ) MAX_PROBE_READ else size
                    val buffer = java.nio.ByteBuffer.allocate(want)
                    var readTotal = 0
                    while (readTotal < want) {
                        val n = channel.read(buffer, offset + readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    return if (readTotal == 0) ByteArray(0) else buffer.array().copyOf(readTotal)
                }
                NspProbe.probe(NspProbe.Bytes { offset, size -> readAt(offset, size) }, name)
            }
        }
    }.getOrNull()

    /** Строки ключей игр со всех файлов в папке ключей. */
    private fun titleKeyLines(): List<String> {
        val context = YuzuApplication.appContext
        val keysDir = DirectoryInitialization.userDirectory?.let { File(it, "keys") }
        return TitleKeys.readAll(keysDir)
    }

    /** Состояние ключей прямо сейчас — без него вердикт был бы догадкой. */
    private fun keysOkNow(): Boolean =
        runCatching { NativeLibrary.areKeysPresent() }.getOrDefault(false)

    private fun mountExternalContentDirectories(mountedContainerUris: MutableSet<String>) {
        val uniqueExternalContentDirs = linkedSetOf<String>()
        NativeConfig.getExternalContentDirs().forEach { externalDir ->
            if (externalDir.isNotEmpty()) {
                uniqueExternalContentDirs.add(externalDir)
            }
        }

        for (externalDir in uniqueExternalContentDirs) {
            val externalDirUri = externalDir.toUri()
            if (FileUtil.isTreeUriValid(externalDirUri)) {
                scanContentContainersRecursive(FileUtil.listFiles(externalDirUri), 3) {
                    val containerUri = it.uri.toString()
                    if (mountedContainerUris.add(containerUri)) {
                        NativeLibrary.addFileToFilesystemProvider(containerUri)
                    }
                }
            }
        }
    }

    private fun mountGameFolderContent(gameUri: Uri, mountedContainerUris: MutableSet<String>) {
        if (gameUri.scheme == "content") {
            val parentUri = getParentDocumentUri(gameUri) ?: return
            scanContentContainersRecursive(FileUtil.listFiles(parentUri), 1) {
                val containerUri = it.uri.toString()
                if (mountedContainerUris.add(containerUri)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(containerUri)
                }
            }
            return
        }

        val gameFile = File(gameUri.path ?: gameUri.toString())
        val parentDir = gameFile.parentFile ?: return
        parentDir.listFiles()?.forEach { sibling ->
            if (!sibling.isFile) {
                return@forEach
            }

            val extension = sibling.extension.lowercase()
            if (externalContentExtensions.contains(extension)) {
                val containerUri = Uri.fromFile(sibling).toString()
                if (mountedContainerUris.add(containerUri)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(containerUri)
                }
            }
        }
    }

    private fun getParentDocumentUri(uri: Uri): Uri? {
        return try {
            val documentId = DocumentsContract.getDocumentId(uri)
            val separatorIndex = documentId.lastIndexOf('/')
            if (separatorIndex == -1) {
                null
            } else {
                val parentDocumentId = documentId.substring(0, separatorIndex)
                DocumentsContract.buildDocumentUriUsingTree(uri, parentDocumentId)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getGame(
        uri: Uri,
        addedToLibrary: Boolean,
        registerFilesystemProvider: Boolean = true
    ): Game? {
        val filePath = uri.toString()
        if (!GameMetadata.getIsValid(filePath)) {
            return null
        }

        if (registerFilesystemProvider) {
            // Needed to update installed content information
            NativeLibrary.addFileToFilesystemProvider(filePath)
        }

        var name = GameMetadata.getTitle(filePath)

        // If the game's title field is empty, use the filename.
        if (name.isEmpty()) {
            name = FileUtil.getFilename(uri)
        }
        var programId = runCatching { GameMetadata.getProgramId(filePath) }.getOrDefault("")

        // Имя файла без расширения, но без `substring(0, -1)`.
        //
        // Здесь стояло `name.substring(0, name.lastIndexOf("."))`. У файла
        // без точки - а `main` из распакованного дампа именно такой -
        // lastIndexOf возвращает -1, и substring(0, -1) бросает
        // StringIndexOutOfBoundsException прямо в середине обхода: список
        // обрывался на том файле, до которого дошёл. Это и есть «видит
        // одну игру», если «main» лежал рядом.
        if (programId.isEmpty()) {
            programId = name.substringBeforeLast('.', name).ifBlank { name }
        }

        val newGame = Game(
            name,
            filePath,
            programId,
            GameMetadata.getDeveloper(filePath),
            GameMetadata.getVersion(filePath, false),
            GameMetadata.getIsHomebrew(filePath)
        )

        if (addedToLibrary) {
            val addedTime = prefs.getLong(newGame.keyAddedToLibraryTime, 0L)
            if (addedTime == 0L) {
                prefs.edit()
                    .putLong(newGame.keyAddedToLibraryTime, System.currentTimeMillis())
                    .apply()
            }
        }

        return newGame
    }
}
