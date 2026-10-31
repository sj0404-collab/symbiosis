// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.util.Locale

/**
 * Что за файл лежит в папке и можно ли его запустить.
 *
 * Раньше этот ответ давали три разных места - `GameFormats`, `GameHelper`
 * и `GameFolderScanner` - и каждое понимало его по-своему. Отсюда и
 * «из сотен файлов видит один»: файл, который сканер считал игрой,
 * импортёр молча выбрасывал, потому что заголовок у него не читался
 * (сжатый NSZ), а если заголовок не читался - `getGame()` возвращал null
 * и запись исчезала без единого слова.
 *
 * Здесь одно правило на всех и, что важнее, **ни один файл не исчезает
 * молча**: у каждого либо `Kind.GAME` и запуск, либо `Kind.*` и причина
 * словами. Список причин показывается в панели («Почему не видно»).
 *
 * Модуль намеренно без Android: его можно прогнать на хосте
 * (`tests/LibraryIndexTest.kt`), а не только на телефоне.
 */
object LibraryIndex {

    /** Что файл делает в библиотеке. */
    enum class Kind {
        /** Базовая игра: запускается. */
        GAME,
        /** Обновление `[v65536][UPD]` - запускается, но это не отдельная игра. */
        UPDATE,
        /** DLC - запускается, но это не отдельная игра. */
        DLC,
        /** Распакованная игра: папка с `main`. */
        UNPACKED,
        /** NSZ / XCZ / NCZ - ядро откроет только после распаковки. */
        COMPRESSED,
        /** Часть разрезанного образа (`.nsp.00`, `[00]`). */
        SPLIT_PART,
        /** Не игра вовсе: ключи, прошивка, картинки, архивы. */
        OTHER
    }

    /** Разобранное имя файла. */
    data class Entry(
        val name: String,
        val kind: Kind,
        /** Нормализованный Title ID (`0100…000`), пустой если его нет в имени. */
        val titleId: String = "",
        /** Версия из `[v65536]` в виде `1.0.0`, пустая если её нет. */
        val version: String = "",
        /** Имя без тегов - то, что человек назвал бы игрой. */
        val title: String = "",
        /**
         * Запускает ли это ядро прямо сейчас.
         *
         * Для [Kind.COMPRESSED] - нет, и это не баг: Eden не умеет zstd.
         * Для [Kind.SPLIT_PART] - нет, часть склеивается до запуска.
         */
        val runnable: Boolean = false,
        /** Причина словами, когда `runnable == false`. Никогда не имя класса. */
        val reason: String = ""
    )

    /** Расширения, которые ядро открывает (`loader.cpp` → GuessFromFilename). */
    private val LAUNCHABLE = setOf("xci", "nsp", "nca", "nro", "nso", "kip")

    /** Сжатые контейнеры: файл настоящий, запуск невозможен без распаковки. */
    private val COMPRESSED = setOf("nsz", "xcz", "ncz")

    /** Архивы: игра внутри, но ядро туда не смотрит. */
    private val ARCHIVES = setOf("zip", "7z", "rar", "tar", "gz")

    /** Хвосты разрезанных образов: `game.nsp.00`, `game.xci.part01`. */
    private val SPLIT_SUFFIX = Regex("""^(.*)\.(?:part)?(\d{1,3})$""", RegexOption.IGNORE_CASE)

    /** `[0100ABCD12340000]`, `[0100ABCD12340000][v65536]`, `[UPD]`, `[DLC]`. */
    private val TITLE_ID = Regex("""\[([0-9A-Fa-f]{16})\]""")
    private val VERSION = Regex("""\[[vV](\d+)\]""")
    private val TAG = Regex("""\[[^\]]*]""")

    /**
     * Последние 12 бит Title ID - это роль файла, а не его название.
     *
     * `…000` - базoвая игра, `…800` - обновление, `…001`-`…7FF` - DLC.
     * Правило из документации Nintendo SDK и из того, как эти ID реально
     * выглядят у eShop-дампов; именно оно позволяет сказать «12 игр,
     * 40 DLC» вместо «52 игры», где 40 из них - дополнения.
     */
    private const val ROLE_MASK = 0xFFF

    /** `main` - распакованная игра, `00` - первая часть разбитого NCA. */
    private val NAKED_NAMES = setOf("main", "00")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /**
     * Имя файла, у которого отрезан хвост разрезанного образа.
     *
     * `Game [0100…][v0].nsp.00` → `Game [0100…][v0].nsp`, чтобы обе части
     * склеились в одну запись, а не выглядели двумя играми.
     */
    fun withoutSplitSuffix(name: String): Pair<String, Boolean> {
        val m = SPLIT_SUFFIX.matchEntire(name) ?: return name to false
        val head = m.groupValues[1]
        val ext = extensionOf(head)
        // Хвост из цифр отрезаем только там, где за ним стоит настоящее
        // расширение игры: `[00]` в имени - тег, а не часть.
        if (ext.isEmpty() || head.length < 4) return name to false
        return head to true
    }

    /** Разбирает имя файла. Чистая функция: ни диска, ни Android. */
    fun classify(fileName: String, sizeBytes: Long = 0L): Entry {
        val bare = fileName.substringAfterLast('/')
        val (base, splitPart) = withoutSplitSuffix(bare)
        val ext = extensionOf(base)
        val lowerWhole = bare.lowercase(Locale.ROOT)

        val titleId = TITLE_ID.find(base)?.groupValues?.get(1)?.uppercase(Locale.ROOT).orEmpty()
        val version = VERSION.find(base)?.groupValues?.get(1)?.toLongOrNull()?.let(::versionText)
            .orEmpty()
        val title = titleFrom(base)
        val hasUpdTag = base.contains("[UPD]", ignoreCase = true) ||
            base.contains("[UPDATED]", ignoreCase = true)
        val hasDlcTag = base.contains("[DLC]", ignoreCase = true)

        if (lowerWhole in NAKED_NAMES) {
            return Entry(
                name = fileName, kind = Kind.UNPACKED, titleId = titleId,
                version = version, title = title.ifBlank { fileName },
                runnable = true
            )
        }

        if (splitPart && ext !in COMPRESSED) {
            // Части склеиваются перед запуском; отдельная запись на каждую
            // часть - это «сотни игр», которых на самом деле три.
            return Entry(
                name = fileName,
                kind = if (hasUpdTag) Kind.UPDATE else if (hasDlcTag) Kind.DLC else Kind.SPLIT_PART,
                titleId = titleId, version = version, title = title,
                runnable = false,
                reason = "часть разрезанного образа - склеивается с соседними"
            )
        }

        if (ext in COMPRESSED) {
            return Entry(
                name = fileName, kind = Kind.COMPRESSED, titleId = titleId,
                version = version, title = title, runnable = false,
                reason = "сжатый образ · сначала «Распаковать» во вкладке Конвертер"
            )
        }

        if (ext in ARCHIVES) {
            return Entry(
                name = fileName, kind = Kind.OTHER, titleId = titleId,
                version = version, title = title, runnable = false,
                reason = "архив · внутри может быть игра, но ядро читает только nsp/xci/nca"
            )
        }

        if (ext in LAUNCHABLE) {
            val kind = when {
                hasDlcTag -> Kind.DLC
                hasUpdTag -> Kind.UPDATE
                !titleId.isEmpty() && roleOf(titleId) == Role.DLC -> Kind.DLC
                !titleId.isEmpty() && roleOf(titleId) == Role.UPDATE -> Kind.UPDATE
                else -> Kind.GAME
            }
            return Entry(
                name = fileName, kind = kind, titleId = titleId,
                version = version, title = title, runnable = true
            )
        }

        return Entry(
            name = fileName, kind = Kind.OTHER, titleId = titleId,
            version = version, title = title, runnable = false,
            reason = "не образ игры"
        )
    }

    /** Роль Title ID: база, обновление или DLC. */
    enum class Role { BASE, UPDATE, DLC }

    fun roleOf(titleId: String): Role {
        if (titleId.length < 16) return Role.BASE
        val low = titleId.takeLast(3).toIntOrNull(16) ?: return Role.BASE
        return when {
            low == 0 -> Role.BASE
            low == 0x800 -> Role.UPDATE
            else -> Role.DLC
        }
    }

    /**
     * Title ID базовой игры для любого файла этого тайтла.
     *
     * Именно по нему обновление и DLC прилипают к своей игре, а не висят
     * в списке отдельными «играми».
     */
    fun baseTitleId(titleId: String): String {
        if (titleId.length < 16) return ""
        return titleId.dropLast(3) + "000"
    }

    /** Имя без `[тегов]`, лишних пробелов и расширения. */
    fun titleFrom(fileName: String): String {
        val base = fileName.substringAfterLast('/')
        val noExt = base.substringBeforeLast('.', base)
        val stripped = TAG.replace(noExt, " ")
        return stripped.replace(Regex("""\s+"""), " ").trim().trim('-', '_', '.')
    }

    /** `65536` → `1.0.0`: старшие 16 бит - major, затем minor и patch. */
    fun versionText(raw: Long): String {
        if (raw <= 0) return ""
        val major = raw shr 16
        val minor = (raw shr 8) and 0xFF
        val patch = raw and 0xFF
        return "$major.$minor.$patch"
    }

    /** Файл вообще стоит показывать в библиотеке. */
    fun isWanted(fileName: String): Boolean {
        val (base, split) = withoutSplitSuffix(fileName.substringAfterLast('/'))
        val ext = extensionOf(base)
        if (split && ext in LAUNCHABLE) return true
        if (fileName.lowercase(Locale.ROOT).substringAfterLast('/') in NAKED_NAMES) return true
        return ext in LAUNCHABLE || ext in COMPRESSED
    }

    /** Файл запускается прямо сейчас. */
    fun isRunnable(fileName: String): Boolean = classify(fileName).runnable

    /**
     * Сводит список записей в то, что человек назовёт библиотекой.
     *
     * Одинаковые записи (одна папка выбрана дважды, часть и её основа)
     * схлопываются; обновления и DLC считаются отдельно от игр, но **не
     * исчезают**: `LibraryStats` говорит, сколько их, и панель их рисует.
     */
    fun summarise(names: List<String>): Stats {
        val seen = HashSet<String>()
        var games = 0
        var updates = 0
        var dlc = 0
        var compressed = 0
        var other = 0
        val reasons = LinkedHashMap<String, Int>()
        names.forEach { raw ->
            val e = classify(raw)
            val key = baseKey(raw, e)
            if (!seen.add(key)) return@forEach
            when (e.kind) {
                Kind.GAME, Kind.UNPACKED -> games++
                Kind.UPDATE -> updates++
                Kind.DLC -> dlc++
                Kind.COMPRESSED -> {
                    compressed++
                    if (e.reason.isNotEmpty()) reasons.merge(e.reason, 1, Int::plus)
                }
                Kind.SPLIT_PART -> updates += 0
                Kind.OTHER -> {
                    other++
                    if (e.reason.isNotEmpty()) reasons.merge(e.reason, 1, Int::plus)
                }
            }
        }
        return Stats(names.size, games, updates, dlc, compressed, other, reasons)
    }

    /** Ключ, по которому записи считаются одной и той же. */
    fun baseKey(fileName: String, entry: Entry = classify(fileName)): String {
        val (base, _) = withoutSplitSuffix(fileName.substringAfterLast('/'))
        if (entry.titleId.isNotEmpty()) {
            // baseTitleId намеренно НЕ используется: обновление и DLC должны
            // остаться отдельными строками, иначе «40 DLC» превратятся в
            // одну строку и снова покажется «видит одну игру».
            return "${entry.titleId}|${extensionOf(base)}|${entry.kind}"
        }
        return base.lowercase(Locale.ROOT)
    }

    /** Итог по папке: то, что панель печатает одной строкой. */
    data class Stats(
        val files: Int,
        val games: Int,
        val updates: Int,
        val dlc: Int,
        val compressed: Int,
        val other: Int,
        val reasons: Map<String, Int> = emptyMap()
    ) {
        /** Строка для человека. Никаких «0 игр» там, где есть 200 сжатых. */
        fun line(): String {
            val parts = mutableListOf<String>()
            parts.add("$files файлов")
            parts.add("$games ${plural(games, "игра", "игры", "игр")}")
            if (updates > 0) parts.add("$updates обновл.")
            if (dlc > 0) parts.add("$dlc DLC")
            if (compressed > 0) {
                val word = plural(compressed, "сжатый", "сжатых", "сжатых")
                parts.add("$compressed $word → Конвертер")
            }
            if (other > 0) parts.add("$other прочих")
            return parts.joinToString(" · ")
        }

        private fun plural(n: Int, one: String, few: String, many: String): String {
            val mod100 = n % 100
            val mod10 = n % 10
            return when {
                mod100 in 11..14 -> many
                mod10 == 1 -> one
                mod10 in 2..4 -> few
                else -> many
            }
        }
    }
}
