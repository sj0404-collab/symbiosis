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

    /** Прошивка: `<корень>/bis/system/Contents/registered`. */
    fun firmwareDir(root: File): File =
        File(File(File(File(root, "bis"), "system"), "Contents"), "registered")

    /** Ключи: `<корень>/system`. */
    fun keysDir(root: File): File = File(root, "system")

    /** Сколько файлов прошивки лежит в корне. */
    fun firmwareCount(root: File): Int =
        runCatching { firmwareDir(root).listFiles()?.count { it.isFile } ?: 0 }.getOrDefault(0)

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
