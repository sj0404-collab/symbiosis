// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.utils

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

    private lateinit var preferences: SharedPreferences

    /**
     * The last list the emulator managed to read, from memory or from disk.
     *
     * Never walks a folder and never parses a ROM header: it is what the
     * status strip, the panel and a start without keys may show without
     * turning into a full scan. Returns an empty list only when there has
     * never been a successful one.
     */
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

        // Слои: родитель + его ребёнок в одном списке. Оставляем только
        // более узкую папку, иначе обход идёт по одному дереву дважды и
        // заходит в nand/load/cache корня данных.
        val collapsed = GameFolderScanner.collapseLayers(gameDirs.map { it.uriString }).toSet()
        if (collapsed.size != gameDirs.size) {
            val kept = gameDirs.filter { it.uriString in collapsed }
            gameDirs.clear()
            gameDirs.addAll(kept)
            runCatching { NativeConfig.setGameDirs(gameDirs.toTypedArray()) }
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
                    mountedContainerUris
                )
            } else {
                badDirs.add(index)
            }
        }

        // Remove all game dirs with insufficient permissions from config
        if (badDirs.isNotEmpty()) {
            var offset = 0
            badDirs.forEach {
                gameDirs.removeAt(it - offset)
                offset++
            }
        }
        NativeConfig.setGameDirs(gameDirs.toTypedArray())

        // Cache list of games found on disk
        val serializedGames = mutableSetOf<String>()
        games.forEach {
            serializedGames.add(Json.encodeToString(it))
        }
        preferences.edit() {
            remove(KEY_GAMES)
                .putStringSet(KEY_GAMES, serializedGames)
        }

        cachedGameList = games.toMutableList()
        hasScanned = true
        return games.toList()
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
        mountedContainerUris: MutableSet<String>
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
                        mountedContainerUris
                    )
                }
            } else {
                val extension = FileUtil.getExtension(it.uri).lowercase()
                val filePath = it.uri.toString()

                if (externalContentExtensions.contains(extension) &&
                    mountedContainerUris.add(filePath)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(filePath)
                }

                // Game.extensions — урезанный список. Ядро открывает
                // ещё nso/kip и файлы main/00 (loader.cpp).
                val filename = FileUtil.getFilename(it.uri)
                if (Game.extensions.contains(extension) || GameFormats.isLaunchable(filename)) {
                    val game = getGame(it.uri, true, false)
                    if (game != null) {
                        games.add(game)
                    }
                }
            }
        }
    }

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
        var programId = GameMetadata.getProgramId(filePath)

        // If the game's ID field is empty, use the filename without extension.
        if (programId.isEmpty()) {
            programId = name.substring(0, name.lastIndexOf("."))
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
            val addedTime = preferences.getLong(newGame.keyAddedToLibraryTime, 0L)
            if (addedTime == 0L) {
                preferences.edit()
                    .putLong(newGame.keyAddedToLibraryTime, System.currentTimeMillis())
                    .apply()
            }
        }

        return newGame
    }
}
