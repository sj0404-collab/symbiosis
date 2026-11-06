// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File

/**
 * Где второе ядро держит свои данные — и почему это можно выбрать.
 *
 * КАК УСТРОЕНО У KENJI. Ядру передаётся ОДНА папка (`javaInitialize(path)`), и
 * внутри неё оно само заводит своё дерево:
 *
 * ```
 * <папка>/
 *   bis/system/Contents/registered/   ← прошивка
 *   system/                           ← prod.keys, title.keys и прочие ключи
 *   Games/  Profiles/  Logs/
 * ```
 *
 * Отсюда важное следствие: если указать ядру папку, в которой прошивка уже
 * лежит, — ничего переносить не надо. Она найдётся там, где стоит. Именно так
 * работает Kenji: человек выбирает папку один раз, а не «импортирует данные».
 *
 * ЧТО БЫЛО У НАС. Папка была жёстко приватной
 * (`Android/data/<пакет>/files/kenji`), и другого способа отдать ядру прошивку,
 * кроме копирования трёхсот мегабайт из Eden, не оставалось.
 *
 * ЧТО ТЕПЕРЬ. Папку можно выбрать — любую, куда приложение имеет доступ.
 * Если в ней уже есть `bis/system/Contents/registered`, копирования нет вовсе;
 * если папка пустая, ядро заведёт в ней своё дерево само и прошивку придётся
 * положить туда один раз (или разрешить перенести из Eden — один раз, не
 * каждой игрой).
 *
 * Раскладка здесь продублирована намеренно: она часть формата ядра, а не
 * наша выдумка, и её надо уметь проверить до запуска, не загружая 55 МБ ядра.
 */
object KenjiData {

    /** Корень данных ядра по умолчанию — приватная папка приложения. */
    fun defaultRoot(filesDir: File?): File =
        File(filesDir ?: File("/data/local/tmp"), "kenji")

    /**
     * Привести выбранную папку к корню данных ядра.
     *
     * ЧЕЛОВЕК ВЫБИРАЕТ ПАПКУ, КОТОРУЮ ВИДИТ, а видит он чаще всего не корень, а
     * `.../bis/system/Contents/registered` — то есть ту самую, где лежат файлы
     * прошивки. Требовать, чтобы он поднялся на три уровня вверх и догадался,
     * что «корень» — это папка без единого файла внутри, неправильно: экран
     * после этого честно писал «прошивки нет» про папку, в которой прошивка
     * лежит.
     *
     * Поэтому принимаем любой уровень: `registered`, `Contents`, `system`,
     * `bis` или сам корень — и поднимаемся до того, внутри которого дерево
     * ядра становится осмысленным.
     */
    fun normalizeRoot(chosen: File): File {
        var current = chosen
        repeat(5) {
            // Уже корень: прошивка или ключи на своём месте.
            if (hasFirmware(current) || hasKeys(current)) return current
            val parent = current.parentFile ?: return current
            // Поднялись на уровень выше и там всё нашлось — значит выбрали
            // внутреннюю папку дерева.
            if (hasFirmware(parent) || hasKeys(parent)) return parent
            val name = current.name.lowercase()
            if (name != "registered" && name != "contents" &&
                name != "system" && name != "bis"
            ) {
                return current
            }
            current = parent
        }
        return current
    }

    /** Как отдать второму ядру прошивку Eden, не копируя её. */
    enum class Share {
        /** Ссылка уже стоит на нужную папку — делать нечего. */
        ALREADY_LINKED,

        /** В папке ядра лежит ровно копия прошивки Eden — её можно заменить ссылкой. */
        REPLACE_COPY,

        /** В папке ядра своя прошивка, которой нет в Eden — не трогать. */
        KEEP_OWN,

        /** Ссылку сделать нельзя (внешний том) — остаётся копия. */
        COPY_ONLY
    }

    /**
     * Решение о прошивке — чистой функцией, чтобы правила проверялись без
     * телефона и без вызова `Os.symlink`.
     *
     * @param linked        в папке ядра уже ссылка
     * @param sameTarget    и она указывает именно на папку прошивки Eden
     * @param plainCopy     файлы в папке ядра — это копия файлов Eden
     * @param linkPossible  система разрешает ссылки в этой папке
     */
    fun sharePlan(
        linked: Boolean,
        sameTarget: Boolean,
        plainCopy: Boolean,
        linkPossible: Boolean
    ): Share = when {
        linked && sameTarget -> Share.ALREADY_LINKED
        !linkPossible -> Share.COPY_ONLY
        plainCopy -> Share.REPLACE_COPY
        else -> Share.KEEP_OWN
    }

    /**
     * Годится ли папка в корень данных ядра.
     *
     * ЧТО БЫЛО НЕ ТАК. На телефоне в отчёте стояло:
     * `папка данных: …/Eden/bis/system/Contents/registered` — то есть корнем
     * данных ядра оказалась сама папка прошивки. Ядро завело бы внутри неё
     * `bis/`, `system/` и `Games/` — то есть прошивку внутри прошивки, а
     * ссылка на прошивку Eden вообще не туда.
     *
     * Такое получается двумя путями: человек выбрал папку с прошивкой (это
     * законно, но приводить надо к корню), либо в настройках остался путь от
     * прежней версии. Поэтому корень проверяется перед использованием, а
     * неподходящий отбрасывается в пользу приватной папки.
     *
     * @param edenRoot данные Eden: `<user>/nand`, `<user>/keys` и т.д.
     */
    fun usableRoot(root: File, edenRoot: File?): Boolean {
        val name = root.name.lowercase()
        // Внутри самого дерева прошивки или ключей корнем быть нельзя.
        if (name == "registered" || name == "contents" || name == "bis") return false
        if (edenRoot != null) {
            val path = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            val eden = runCatching { edenRoot.canonicalPath }.getOrDefault(edenRoot.absolutePath)
            // Ровно папка nand/system/Contents/registered или keys — не корень.
            if (path.startsWith(File(eden, "nand").absolutePath) &&
                root.name.lowercase() != "nand"
            ) {
                return false
            }
            if (path == File(eden, "keys").absolutePath) return false
        }
        return true
    }

    /** Прошивка: `<корень>/bis/system/Contents/registered`. */
    fun firmwareDir(root: File): File =
        File(File(File(File(root, "bis"), "system"), "Contents"), "registered")

    /** Ключи: `<корень>/system`. */
    fun keysDir(root: File): File = File(root, "system")

    /**
     * Сколько файлов прошивки лежит в папке ядра.
     *
     * Считаем и вложенные папки: установщик прошивки Kenji (Ryujinx) кладёт
     * NCA не плоским списком, а подпапками — `registered/<id>/…`. Старый счёт
     * «только файлы в корне» на такой раскладке давал ноль, и отчёт честно
     * писал «прошивка: нет», хотя она лежала рядом.
     *
     * Папка, откуда Kenji читает прошивку ПЛОСКИМ списком.
     *
     * РАЗНЫЕ РАСКЛАДКИ У ДВУХ ЯДЕР — вот почему прошивка «не видна».
     * В исходниках Kenji-NX (`ContentManager.LoadEntries`) перебираются:
     *   1) подпапки внутри `registered` — и из каждой берётся первый файл;
     *   2) файлы `.nca`, лежащие прямо в `Contents` (НЕ в `registered`).
     * Eden (yuzu) держит прошивку плоским списком внутри `registered`. То есть
     * даже когда папка прошивки Eden подставлена ссылкой, Kenji не находит в
     * ней ни одного NCA: он ждёт либо подпапки, либо файлы на уровень выше.
     *
     * Отсюда и `TimeZoneBinary system title not found` в его журнале.
     */
    fun firmwareContentsDir(root: File): File = File(File(File(root, "bis"), "system"), "Contents")

    /** Сколько прошивочных .nca лежит плоско в `Contents`. */
    fun flatFirmwareCount(root: File): Int = runCatching {
        firmwareContentsDir(root).listFiles()
            ?.count { it.isFile && it.name.endsWith(".nca", ignoreCase = true) } ?: 0
    }.getOrDefault(0)

    fun firmwareCount(root: File): Int =
        firmwareCountOf(firmwareDir(root)) + flatFirmwareCount(root)

    /** Файлов в указанной папке прошивки — любой уровень вложенности. */
    fun firmwareCountOf(dir: File): Int = runCatching {
        if (!dir.isDirectory) return@runCatching 0
        var count = 0
        dir.walkTopDown().forEach { if (it.isFile) count++ }
        count
    }.getOrDefault(0)

    fun hasFirmware(root: File): Boolean = firmwareCount(root) > 0

    /** Есть ли в папке ключи, которые ядро прочитает. */
    fun hasKeys(root: File): Boolean =
        runCatching { File(keysDir(root), "prod.keys").isFile }.getOrDefault(false)

    /**
     * Можно ли писать в папку — проверка пробным файлом.
     *
     * Права «на бумаге» и права на деле на внешнем томе расходятся: каталог
     * создан, а файл в нём не заводится. Ядро об этом молчит.
     */
    fun writable(root: File): Boolean = runCatching {
        if (!root.isDirectory && !root.mkdirs()) return false
        val probe = File(root, ".symbiosis-write-check")
        probe.writeText("ok")
        probe.delete()
    }.getOrDefault(false)

    /**
     * Что нашлось в папке — одной строкой для экрана.
     *
     * Нужна, чтобы выбор папки не был гаданием: человек видит до запуска,
     * есть ли там прошивка, ключи и игры, а не узнаёт это по чёрному экрану.
     */
    fun describe(root: File): String {
        val fw = firmwareCount(root)
        val games = runCatching { File(root, "Games").listFiles()?.size ?: 0 }.getOrDefault(0)
        return buildList {
            add(
                when {
                    fw > 0 -> "прошивка: $fw файлов"
                    firmwareDir(root).isDirectory -> "прошивка: пусто"
                    else -> "прошивки нет"
                }
            )
            add(if (hasKeys(root)) "ключи: есть" else "ключи: нет")
            if (games > 0) add("игр: $games")
            if (!writable(root)) add("нет записи")
        }.joinToString(" · ")
    }

    /**
     * Похожа ли папка на данные ядра: есть дерево прошивки или ключи.
     *
     * Проверять `bis` целиком нельзя — пустая папка `bis` заводится при первом
     * же запуске, и «похоже на ядро» тогда означало бы «кто-то когда-то
     * запускал».
     */
    fun looksLikeCoreData(root: File): Boolean =
        hasFirmware(root) || hasKeys(root) ||
            runCatching { File(root, "Games").isDirectory }.getOrDefault(false)

    /**
     * Кандидаты среди уже существующих папок — чтобы не вписывать путь руками.
     *
     * Обход ограничен по глубине и по числу просмотренных каталогов: полный
     * обход /sdcard на телефоне с тысячами папок — это секунды ввода-вывода и
     * ANR, а нужен всего один ответ.
     */
    fun candidates(roots: List<File>, maxDepth: Int = 2, maxDirs: Int = 400): List<File> {
        val found = LinkedHashSet<File>()
        var seen = 0
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || seen >= maxDirs) return
            seen++
            val children = runCatching {
                dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            }.getOrNull().orEmpty()
            for (child in children) {
                if (seen >= maxDirs) return
                if (looksLikeCoreData(child) && hasFirmware(child)) found.add(child)
                // Вглубь идём только по «содержательным» папкам: Android/data,
                // Games, содержимое чужих деревьев.
                if (depth < maxDepth) walk(child, depth + 1)
            }
        }
        for (root in roots) {
            if (runCatching { root.isDirectory }.getOrDefault(false)) walk(root, 0)
        }
        return found.toList()
    }
}
