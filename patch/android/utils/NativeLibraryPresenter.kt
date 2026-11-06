// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.util.Locale

/**
 * Что показывает нативный лаунчер и что делает нажатие.
 *
 * Раньше эту работу делала страница в WebView (`library.html`): она решала,
 * что попадёт в карусель, какие плитки нерабочие, какое действие предложить
 * и как это назвать. Из-за этого половина интерфейса была в JavaScript, а
 * половина — в Kotlin, и понять, где что лежит, было нельзя.
 *
 * Здесь всё, что можно решить без Android: набор элементов, их порядок,
 * бейджи, действия и строка сводки. Экран ([org.yuzu.yuzu_emu.fragments.
 * NativeLauncherFragment]) только рисует то, что вернул этот класс, поэтому
 * логику можно проверить на хосте (`tests/NativeLibraryPresenterTest.kt`),
 * не собирая APK.
 */
object NativeLibraryPresenter {

    /** Что делает нажатие на элемент. */
    enum class Action {
        /** Игра запускается. */
        LAUNCH,
        /** Сжатый образ: предложить распаковку. */
        CONVERT,
        /** Заголовок не прочитан: нужны ключи и прошивка. */
        KEYS,
        /** Часть разрезанного образа: склеивается с соседями. */
        EXPLAIN_SPLIT
    }

    /** Строка библиотеки. */
    data class Row(
        val title: String,
        val path: String,
        /** `0100…` в hex, пустая строка если нет. */
        val titleId: String = "",
        val developer: String = "",
        val fileName: String = "",
        val hasSave: Boolean = false,
        val saveSize: String = "",
        /**
         * Ядро, которым запустится игра: `kenji` или пусто (Eden).
         *
         * Библиотека одна, ядер два. Пометка нужна, чтобы выбор был виден
         * до нажатия: иначе «я переключил ядро» и «эта игра всё равно
         * запустилась не тем» выглядят одинаково.
         */
        val core: String = ""
    )

    /** Файл, который виден, но не запускается. */
    data class Side(
        val name: String,
        val path: String,
        val title: String,
        val reason: String,
        /** `compressed`, `unreadable`, `split`. */
        val kind: String
    )

    /** Один элемент на экране. */
    data class Item(
        val title: String,
        /** Строка под названием: разработчик, Title ID или причина. */
        val subtitle: String,
        /** Короткая метка на плитке: `nsp`, `xci`, `DLC`. */
        val badge: String,
        /** Пометка справа: «обновление», «сжатый», «нет ключей». */
        val note: String,
        val path: String,
        val action: Action,
        val runnable: Boolean,
        /** `kenji`, если игра пойдёт на втором ядре; пусто для Eden. */
        val core: String = "",
        /** Индекс в исходном списке игр, -1 для файлов из [Side]. */
        val gameIndex: Int = -1
    )

    /** Что нарисовать. */
    data class Model(
        val items: List<Item>,
        /** Строка над списком: «742 файла · 120 игр · 400 сжатых». */
        val summary: String,
        /** То же число словами, когда игр нет: это самый частый вопрос. */
        val emptyReason: String,
        val games: Int,
        val updates: Int,
        val dlc: Int,
        val compressed: Int,
        val unreadable: Int,
        /** Индекс выбранной плитки в карусели. */
        val selected: Int = 0
    )

    /**
     * Собирает экран.
     *
     * Порядок намеренный: сначала то, что запускается, потом обновления и
     * DLC, и только затем файлы, которые библиотека видит, но открыть не
     * может. Так «одна игра» из старых жалоб не прячется за четырьмястами
     * сжатыми образами, но и не исчезает: сжатые идут следом и объяснены.
     */
    fun build(
        games: List<Row>,
        side: List<Side> = emptyList(),
        updates: Int = 0,
        dlc: Int = 0,
        search: String = "",
        filter: String = FILTER_ALL
    ): Model {
        val query = search.trim().lowercase(Locale.ROOT)

        val gameItems = games.mapIndexed { index, row ->
            Item(
                title = row.title.ifBlank { row.fileName.substringBeforeLast('.') },
                subtitle = subtitleOf(row),
                badge = extensionOf(row.fileName.ifEmpty { row.path }),
                note = "",
                path = row.path,
                action = Action.LAUNCH,
                runnable = true,
                core = row.core,
                gameIndex = index
            )
        }

        val sideItems = side.map { s ->
            Item(
                title = s.title.ifBlank { s.name.substringBeforeLast('.') },
                subtitle = s.reason,
                badge = extensionOf(s.name.ifEmpty { s.path }),
                note = when (s.kind) {
                    "compressed" -> "сжатый"
                    "split" -> "часть"
                    "unreadable" -> "нет ключей"
                    else -> ""
                },
                path = s.path,
                action = when (s.kind) {
                    "compressed" -> Action.CONVERT
                    "split" -> Action.EXPLAIN_SPLIT
                    else -> Action.KEYS
                },
                runnable = false,
                gameIndex = -1
            )
        }

        val all = (gameItems + sideItems)
            .filter { matches(it, query) }
            .filter { matchesFilter(it, filter) }

        val compressed = side.count { it.kind == "compressed" }
        val unreadable = side.count { it.kind == "unreadable" }
        val split = side.count { it.kind == "split" }
        val gamesCount = games.size

        val onKenji = games.count { it.core == "kenji" }

        return Model(
            items = all,
            summary = summaryLine(gamesCount, updates, dlc, compressed, unreadable + split, onKenji),
            emptyReason = emptyReason(gamesCount, compressed, unreadable + split, games.size + side.size),
            games = gamesCount,
            updates = updates,
            dlc = dlc,
            compressed = compressed,
            unreadable = unreadable + split
        )
    }

    const val FILTER_ALL = "all"
    const val FILTER_RUNNABLE = "runnable"
    const val FILTER_DEAD = "dead"

    private fun matches(item: Item, query: String): Boolean {
        if (query.isEmpty()) return true
        return item.title.lowercase(Locale.ROOT).contains(query) ||
            item.subtitle.lowercase(Locale.ROOT).contains(query)
    }

    private fun matchesFilter(item: Item, filter: String): Boolean = when (filter) {
        FILTER_RUNNABLE -> item.runnable
        FILTER_DEAD -> !item.runnable
        else -> true
    }

    private fun subtitleOf(row: Row): String {
        val bits = mutableListOf<String>()
        if (row.developer.isNotBlank()) bits.add(row.developer)
        if (row.titleId.isNotBlank()) bits.add(row.titleId)
        if (row.hasSave) bits.add("сейв " + row.saveSize.ifBlank { "есть" })
        return bits.joinToString(" · ")
    }

    /**
     * `Game [0100…][v0].nsp` → `nsp`; пусто, если расширения нет.
     *
     * Хвост разрезанного образа отрезается: у `Big.nsp.00` на плитке должно
     * стоять `nsp`, а не `00` — иначе бейдж ничего не говорит о файле.
     */
    fun extensionOf(name: String): String {
        val base = LibraryIndex.withoutSplitSuffix(name.substringAfterLast('/')).first
        val ext = base.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ext else ""
    }

    /**
     * Строка сводки.
     *
     * Считает слова правильно: «1 игра», «2 игры», «5 игр». Раньше панель
     * писала «1 сжатых» и «3 файлов», и это читалось как ошибка.
     */
    fun summaryLine(
        games: Int,
        updates: Int,
        dlc: Int,
        compressed: Int,
        broken: Int,
        onSecondCore: Int = 0
    ): String {
        val parts = mutableListOf<String>()
        parts.add("$games ${plural(games, "игра", "игры", "игр")}")
        if (onSecondCore > 0) {
            // Сколько игр уйдёт на второе ядро — до нажатия, а не после.
            parts.add("$onSecondCore ${plural(onSecondCore, "на Kenji-NX", "на Kenji-NX", "на Kenji-NX")}")
        }
        if (updates > 0) parts.add("$updates ${plural(updates, "обновление", "обновления", "обновлений")}")
        if (dlc > 0) parts.add("$dlc DLC")
        if (compressed > 0) {
            parts.add("$compressed ${plural(compressed, "сжатый", "сжатых", "сжатых")} → Конвертер")
        }
        if (broken > 0) parts.add("$broken ${plural(broken, "не читается", "не читаются", "не читаются")}")
        return parts.joinToString(" · ")
    }

    /** Почему список пуст — словами, а не «нет игр». */
    fun emptyReason(games: Int, compressed: Int, broken: Int, files: Int): String = when {
        files == 0 -> "Папка не выбрана или пуста: «Папки» → добавить папку с играми."
        games > 0 -> "Ничего не найдено по этому фильтру."
        compressed > 0 && broken > 0 ->
            "Игр нет: $compressed ${plural(compressed, "файл", "файла", "файлов")} сжаты, " +
                "$broken не читаются. Сжатые распаковывает Конвертер, для остальных нужны ключи."
        compressed > 0 ->
            "Игр нет: $compressed ${plural(compressed, "файл сжат", "файла сжаты", "файлов сжаты")}. " +
                "Откройте «Конвертер» → «Распаковать»."
        broken > 0 ->
            "Игр нет: $broken ${plural(broken, "файл не читается", "файла не читаются", "файлов не читаются")}. " +
                "Нужны ключи консоли (prod.keys) и прошивка."
        else -> "Игр нет — в выбранных папках лежат только посторонние файлы."
    }

    /** Русское склонение: 1 игра, 2 игры, 5 игр. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = Math.abs(n) % 100
        val mod10 = Math.abs(n) % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    /**
     * Что написать в диалоге для файла, который не запускается.
     *
     * Тексты здесь, а не в экране, чтобы их можно было проверить тестом и
     * чтобы в них не просочилось имя класса: пользователь должен читать
     * причину, а не `java.lang.IllegalStateException`.
     */
    fun explain(kind: String, name: String, reason: String = ""): Triple<String, String, String> =
        when (kind) {
            "compressed" -> Triple(
                name,
                "Это сжатый образ ($reason). Ядро открывает только nsp/xci/nca, " +
                    "поэтому файл сначала распаковывается. Распаковка идёт на телефоне " +
                    "и занимает столько же места, сколько сам образ.",
                "Распаковать"
            )
            "split" -> Triple(
                name,
                "Это часть разрезанного образа. Запускается только целый файл: " +
                    "положите все части рядом, и библиотека увидит одну игру.",
                "Понятно"
            )
            else -> Triple(
                name,
                // Причина приходит из разбора контейнера (NspProbe) и уже
                // учитывает, установлены ли ключи. Раньше здесь стояла одна
                // фраза про ключи на все случаи — и на телефоне она
                // противоречила экрану ключей, где всё было на месте.
                reason.ifBlank {
                    "Файл на месте, но ядро не прочитало заголовок. Точную причину " +
                        "можно узнать, нажав「Проверить, что установлено」в разделе ключей."
                },
                if (reason.contains("NSZ") || reason.contains("Распаковать")) "Распаковать"
                else "Где ключи"
            )
        }
}
