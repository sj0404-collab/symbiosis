// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.net.Uri
import java.io.File
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.model.Game

/**
 * Синхронизация сейвов и образов игр с облаком.
 *
 * ЧТО СИНХРОНИЗИРУЕТСЯ САМО
 *   Только сейвы, и это осознанно: они весят килобайты, их потеря
 *   невосстановима, и залить их при выходе из игры — секунды. Образ игры
 *   весит гигабайты; заливать его автоматически по мобильной сети значило
 *   бы съесть трафик человека без его разрешения. Образы — по кнопке.
 *
 * ЧТО ЗДЕСЬ НЕ ОБЕЩАНО
 *   Запуска «прямо из облака» нет и быть не может: эмулятору нужен локальный
 *   файл и десятки тысяч произвольных чтений. Схема честная: скачать →
 *   запустить → при выходе сохранения уходят обратно.
 *
 * ГДЕ ЛЕЖАТ СЕЙВЫ
 *   `<корень данных>/nand/user/save/<профиль>/<TitleID>/`. Профилей бывает
 *   несколько, и у каждого свой прогресс — поэтому слоты перечисляются
 *   отдельно, а не сливаются в один «сейв игры».
 */
object CloudSync {

    /** Итог операции для экрана: без исключений, с числом отправленного. */
    data class Outcome(
        val ok: Boolean,
        val message: String,
        val slots: Int = 0,
        val bytes: Long = 0L
    )

    private fun nandSaveRoot(): File? = runCatching {
        val root = DirectoryInitialization.userDirectory ?: return@runCatching null
        File(root, "nand/user/save")
    }.getOrNull()

    private val skipProfiles = setOf("0000000000000000")

    /**
     * Слоты сохранений этой игры на устройстве.
     *
     * Порядок обхода фиксирован (профили по имени): список не должен
     * переставляться от запуска к запуску, иначе кнопка «залить» попадает
     * не туда, куда человек смотрел.
     */
    fun localSlots(game: Game): List<SavesOverview.Slot> {
        val titleId = titleIdOf(game)
        val out = ArrayList<SavesOverview.Slot>()
        val nand = nandSaveRoot()
        if (titleId.isNotEmpty() && nand != null && nand.isDirectory) {
            nand.listFiles()?.sortedBy { it.name }?.forEach { profile ->
                if (!profile.isDirectory || profile.name in skipProfiles) return@forEach
                val folder = File(profile, titleId)
                if (!folder.isDirectory) return@forEach
                val files = folder.listFiles()?.filter { it.isFile }.orEmpty()
                if (files.isEmpty()) return@forEach
                out.add(
                    SavesOverview.Slot(
                        profile = profile.name,
                        path = folder.absolutePath,
                        files = files.map {
                            SavesOverview.Stamp(it.name, it.length(), it.lastModified())
                        },
                        origin = SavesOverview.ORIGIN_LOCAL
                    )
                )
            }
        }
        // Сейв, принесённый из внешней папки (SaveSource), тоже показывается:
        // человек мог годами играть на другой установке.
        runCatching {
            val sd = game.saveDir
            if (sd.isNotBlank()) {
                val dir = File(sd)
                if (dir.isDirectory && out.none { it.path == dir.absolutePath }) {
                    val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
                    if (files.isNotEmpty()) {
                        out.add(
                            SavesOverview.Slot(
                                profile = "внешняя",
                                path = dir.absolutePath,
                                files = files.map {
                                    SavesOverview.Stamp(it.name, it.length(), it.lastModified())
                                },
                                origin = SavesOverview.ORIGIN_EXTERNAL
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    /** Сводка по локальным сейвам игры. */
    fun localSummary(game: Game, now: Long = System.currentTimeMillis()): SavesOverview.Summary =
        SavesOverview.summarise(localSlots(game), now)

    /** Папка игры в облаке. */
    private fun remoteGameRoot(): String {
        val base = WebDavParse.normaliseBase(CloudSettings.url)
        return WebDavParse.join(base, CloudSettings.root, "saves")
    }

    /**
     * TitleID игры: сначала из имени файла, потом из заголовка.
     *
     * Из имени — потому что это работает и для сейвов, у которых нет
     * открытого образа рядом; из заголовка — потому что имя может быть
     * переименовано.
     */
    fun titleIdOf(game: Game): String {
        val fromPath = runCatching { LibraryIndex.classify(game.path).titleId }
            .getOrDefault("")
        if (fromPath.isNotBlank()) return fromPath.uppercase()
        return runCatching { game.programIdHex }.getOrDefault("").uppercase()
    }

    // ── Сейвы: залить ──────────────────────────────────────────────────

    /**
     * Заливает все слоты игры.
     *
     * Каждый файл — отдельный PUT: докачивать «с середины» WebDAV не умеет, а
     * сейвы маленькие, так что цена проста. Отметки времени сервер ставит
     * свои; для выбора «что новее» используется последняя запись файла, а не
     * дата рождения — см. [SavesOverview.direction].
     */
    fun uploadSaves(
        game: Game,
        onProgress: ((String, Long) -> Unit)? = null
    ): Outcome {
        if (!CloudSettings.configured()) {
            return Outcome(false, "облако не настроено: ${CloudSettings.missing()}")
        }
        val titleId = titleIdOf(game)
        if (titleId.isBlank()) return Outcome(false, "у игры нет TitleID — некуда класть")

        val slots = localSlots(game)
        if (slots.isEmpty()) return Outcome(false, "у игры нет сохранений")

        var files = 0
        var bytes = 0L
        for (slot in slots) {
            for (stamp in slot.files) {
                val local = File(slot.path, stamp.name)
                val url = WebDavParse.savePath(remoteGameRoot(), titleId, slot.profile, stamp.name)
                val res = WebDavClient.upload(local, url) { sent -> onProgress?.invoke(stamp.name, sent) }
                if (!res.ok) {
                    return Outcome(
                        false,
                        "не залилось на «${stamp.name}»: ${res.message}",
                        slots.count { true },
                        bytes
                    )
                }
                files++
                bytes += local.length()
            }
        }
        return Outcome(
            true,
            "в облако ушло ${files} ${SavesOverview.plural(files, "файл", "файла", "файлов")} · " +
                SavesOverview.humanBytes(bytes),
            slots.size,
            bytes
        )
    }

    // ── Сейвы: скачать ─────────────────────────────────────────────────

    /** Что лежит в облаке по этой игре. Пустой список — там ничего нет. */
    fun remoteSlots(game: Game): List<SavesOverview.Slot> {
        if (!CloudSettings.configured()) return emptyList()
        val titleId = titleIdOf(game)
        if (titleId.isBlank()) return emptyList()
        val root = WebDavParse.join(remoteGameRoot(), titleId)
        val (res, entries) = WebDavClient.list(root)
        if (!res.ok) return emptyList()

        val out = ArrayList<SavesOverview.Slot>()
        entries.filter { it.isCollection && it.name != titleId }.sortedBy { it.name }.forEach { profile ->
            val dirUrl = WebDavParse.join(root, profile.name)
            val (dirRes, files) = WebDavClient.list(dirUrl)
            if (!dirRes.ok) return@forEach
            val stamps = files.filter { !it.isCollection && it.name.isNotBlank() }
                .map { SavesOverview.Stamp(it.name, it.length, it.modified) }
            if (stamps.isEmpty()) return@forEach
            out.add(
                SavesOverview.Slot(
                    profile = profile.name,
                    path = dirUrl,
                    files = stamps,
                    origin = SavesOverview.ORIGIN_CLOUD
                )
            )
        }
        return out
    }

    /** Сводка по облачным сейвам игры. */
    fun remoteSummary(game: Game, now: Long = System.currentTimeMillis()): SavesOverview.Summary =
        SavesOverview.summarise(remoteSlots(game), now)

    /**
     * Скачивает сейвы игры в NAND.
     *
     * Существующие файлы НЕ удаляются: сейв, которого нет в облаке, но есть
     * на телефоне, остаётся. Слияние «что новее» делается по каждому файлу —
     * именно поэтому сейвы и заливаются по файлам, а не архивом.
     */
    fun downloadSaves(
        game: Game,
        onProgress: ((String, Long) -> Unit)? = null,
        overwriteNewer: Boolean = false
    ): Outcome {
        if (!CloudSettings.configured()) {
            return Outcome(false, "облако не настроено: ${CloudSettings.missing()}")
        }
        val titleId = titleIdOf(game)
        val nand = nandSaveRoot()
            ?: return Outcome(false, "не найден каталог сохранений приложения")
        val slots = remoteSlots(game)
        if (slots.isEmpty()) return Outcome(false, "в облаке нет сохранений для этой игры")

        var files = 0
        var bytes = 0L
        for (slot in slots) {
            val dest = File(File(nand, slot.profile), titleId).apply { mkdirs() }
            for (stamp in slot.files) {
                val target = File(dest, stamp.name)
                if (target.isFile && !overwriteNewer && target.lastModified() >= stamp.modified) {
                    // Локальный не старее — не трогаем. Перезапись «просто
                    // потому что скачивали» уже стирала прогресс людям.
                    continue
                }
                val url = WebDavParse.savePath(remoteGameRoot(), titleId, slot.profile, stamp.name)
                val res = WebDavClient.download(url, target) { got -> onProgress?.invoke(stamp.name, got) }
                if (!res.ok) {
                    return Outcome(false, "не скачалось «${stamp.name}»: ${res.message}", slot.files.size, bytes)
                }
                files++
                bytes += target.length()
            }
        }
        return if (files == 0) {
            Outcome(true, "на устройстве уже свежее — скачивать нечего")
        } else {
            Outcome(
                true,
                "из облака пришло ${files} ${SavesOverview.plural(files, "файл", "файла", "файлов")} · " +
                    SavesOverview.humanBytes(bytes),
                slots.size,
                bytes
            )
        }
    }

    // ── Образы игр ─────────────────────────────────────────────────────

    /** Куда скачиваются образы: видимая папка в «Загрузках». */
    fun gamesDir(): File {
        val download = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        return File(download, "Symbiosis/Games").apply { mkdirs() }
    }

    /**
     * Заливает образ игры.
     *
     * Файл может быть и путём, и `content://` из SAF — во втором случае он
     * сначала копируется во временный файл, потому что загружать по потоку
     * без размера нельзя (серверу нужен `Content-Length`).
     */
    fun uploadGame(
        game: Game,
        onProgress: ((Long) -> Unit)? = null
    ): Outcome {
        if (!CloudSettings.configured()) {
            return Outcome(false, "облако не настроено: ${CloudSettings.missing()}")
        }
        val titleId = titleIdOf(game)
        val name = game.path.substringAfterLast('/').ifBlank { "game.nsp" }
        val url = WebDavParse.gamePath(
            WebDavParse.join(WebDavParse.normaliseBase(CloudSettings.url), CloudSettings.root),
            titleId,
            name
        )

        var temp: File? = null
        val local = if (game.path.startsWith("/")) {
            File(game.path)
        } else {
            val app = YuzuApplication.appContext
            val copy = File(app.cacheDir, "cloud-upload.bin")
            val ok = runCatching {
                app.contentResolver.openInputStream(Uri.parse(game.path))?.use { input ->
                    copy.outputStream().use { out -> input.copyTo(out) }
                    true
                } ?: false
            }.getOrDefault(false)
            if (!ok) return Outcome(false, "не удалось прочитать файл игры")
            temp = copy
            copy
        }

        val res = try {
            WebDavClient.upload(local, url, onProgress)
        } finally {
            temp?.delete()
        }
        return if (res.ok) {
            Outcome(true, "образ в облаке · ${game.title}", 0, local.length())
        } else {
            Outcome(false, "образ не залился: ${res.message}")
        }
    }

    /** Скачивает образ обратно в `Download/Symbiosis/Games`. */
    fun downloadGame(
        game: Game,
        onProgress: ((Long) -> Unit)? = null
    ): Outcome {
        if (!CloudSettings.configured()) {
            return Outcome(false, "облако не настроено: ${CloudSettings.missing()}")
        }
        val titleId = titleIdOf(game)
        val name = game.path.substringAfterLast('/').ifBlank { "game.nsp" }
        val root = WebDavParse.join(
            WebDavParse.normaliseBase(CloudSettings.url),
            CloudSettings.root,
            "games",
            titleId
        )
        val (res, entries) = WebDavClient.list(root)
        if (!res.ok) return Outcome(false, "не удалось прочитать папку игры: ${res.message}")
        val file = entries.firstOrNull { !it.isCollection && it.name.isNotBlank() }
            ?: return Outcome(false, "в облаке нет образа этой игры")

        val target = File(gamesDir(), file.name)
        val got = WebDavClient.download(WebDavParse.join(root, file.name), target, onProgress)
        return if (got.ok) {
            Outcome(true, "образ скачан: ${target.absolutePath}", 0, target.length())
        } else {
            Outcome(false, "образ не скачался: ${got.message}")
        }
    }

    /**
     * Тихо заливает сейвы после игры.
     *
     * Вызывается из фрагмента при выходе. Ничего не показывает и ничего не
     * блокирует: если сети нет, человек узнает об этом при следующем запуске,
     * а не в момент, когда закрыл игру и хочет на рабочий стол.
     */
    fun uploadSavesQuietly(game: Game) {
        if (!CloudSettings.enabled || !CloudSettings.configured()) return
        runCatching {
            Thread({
                val outcome = uploadSaves(game)
                Log.info("[Cloud] авто-заливка сейвов: ${outcome.message}")
            }, "cloud-autosync").start()
        }
    }
}
