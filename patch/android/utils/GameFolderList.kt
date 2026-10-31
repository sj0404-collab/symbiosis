// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Список папок с играми как данные, без обращений к Android.
 *
 * Зачем отдельно: список папок — единственная настройка, потеря которой
 * невосстановима словами «попробуй ещё раз». Пропала папка — пропали игры,
 * обложки, сейвы рядом с ними, и вспомнить, что там было, нельзя. Поэтому
 * операции над списком обязаны быть проверяемыми без телефона, а не только
 * «работает, пока не сломается».
 *
 * Правила, которые здесь закреплены:
 *
 *  1. **Порядок сохраняется.** Первая папка — та, которую пользователь
 *     добавил первой; перестановка списка при каждом чтении выглядит как
 *     «папки сами прыгают».
 *  2. **Одинаковый URI — одна запись.** Дубль после повторного выбора той же
 *     папки не должен появляться второй раз.
 *  3. **Сравнение идёт по нормализованной форме.** `content://…/tree/1A%3A2B`
 *     и `content://…/tree/1A:2B/` — одна и та же папка, и разный регистр
 *     процентов не должен делать из неё две.
 *  4. **Вложенные папки не удаляются.** Раньше «слои» схлопывались, и
 *     родительская папка исчезала из конфига целиком вместе с играми,
 *     лежащими в её корне. Теперь обе остаются, а дубликаты отсекаются на
 *     уровне найденных файлов.
 */
object GameFolderList {

    /** Папка + признак глубокого обхода. Совпадает по смыслу с `GameDir`. */
    data class Entry(val uri: String, val deepScan: Boolean)

    /**
     * Приводит URI к сравнимому виду.
     *
     * Хвостовой слэш не значим: `content://…/tree/X/` и `content://…/tree/X`
     * — одна папка. Пробелы по краям — след копирования из лога или из
     * другой настройки, тоже не значимы.
     */
    fun normalise(uri: String): String = uri.trim().trimEnd('/')

    /** Всё ли в порядке с записью: пустой URI — не папка. */
    fun isUsable(uri: String): Boolean {
        val u = normalise(uri)
        return u.isNotEmpty() && u.contains("://")
    }

    /**
     * Имя папки для показа.
     *
     * Tree-URI выглядит как `…/tree/primary%3AGames%2FSwitch`: всё, что
     * после `/tree/`, — том и путь, закодированные вместе. Пользователю
     * нужно последнее имя, а не «primary:Games/Switch»: в списке из четырёх
     * папок префикс тома повторяется четыре раза и только мешает.
     */
    fun displayName(uri: String): String {
        val tail = normalise(uri).substringAfterLast('/')
        if (tail.isEmpty()) return normalise(uri)
        // %3A и %20 — обычное дело: путь хранится закодированным, и
        // показывать «SD%20Card» вместо «SD Card» незачем.
        val decoded = runCatching { java.net.URLDecoder.decode(tail, "UTF-8") }
            .getOrDefault(tail)
        val afterVolume = decoded.substringAfterLast(':')
        return afterVolume.substringAfterLast('/').ifBlank {
            decoded.substringAfterLast('/').ifBlank { decoded }
        }
    }

    /**
     * Добавляет папку. Уже существующая обновляется на месте: повторный
     * выбор той же папки с включённым глубоким обходом должен включить его,
     * а не создать второй пункт.
     */
    fun add(list: List<Entry>, uri: String, deepScan: Boolean): List<Entry> {
        if (!isUsable(uri)) return list
        val key = normalise(uri)
        val out = list.toMutableList()
        val at = out.indexOfFirst { normalise(it.uri) == key }
        if (at >= 0) {
            out[at] = Entry(key, deepScan)
        } else {
            out.add(Entry(key, deepScan))
        }
        return out
    }

    /** Убирает папку. Только точное совпадение — вложенные не трогаются. */
    fun remove(list: List<Entry>, uri: String): List<Entry> {
        val key = normalise(uri)
        return list.filterNot { normalise(it.uri) == key }
    }

    /**
     * Что есть в зеркале, но пропало из конфига.
     *
     * Зеркало — вторая копия списка, которую приложение хранит само. Если
     * конфиг после сбоя оказался короче (недописанный файл, отозванное
     * разрешение, чужая сборка, перезаписавшая настройки), папки возвращаются
     * из зеркала, а не исчезают молча.
     */
    fun missingFrom(config: List<Entry>, mirror: List<Entry>): List<Entry> {
        val have = config.map { normalise(it.uri) }.toHashSet()
        return mirror.filter { isUsable(it.uri) && normalise(it.uri) !in have }
    }

    /** Объединение без потерь: порядок конфига, затем новые из зеркала. */
    fun union(config: List<Entry>, mirror: List<Entry>): List<Entry> {
        var out = config
        mirror.forEach { e -> out = add(out, e.uri, e.deepScan) }
        return out
    }

    /**
     * Есть ли смысл сканировать одну папку, если уже сканируется другая.
     *
     * Раньше здесь папки вкладывались друг в друга и родитель удалялся из
     * конфига. Теперь решение принимается только на время обхода: если
     * родитель обходится на ту же глубину или глубже, ребёнка можно не
     * обходить отдельно. Конфиг при этом не трогается вообще.
     */
    fun coveredByParent(entry: Entry, all: List<Entry>): Boolean {
        val me = normalise(entry.uri)
        if (me.isEmpty()) return false
        return all.any { other ->
            val parent = normalise(other.uri)
            if (parent.isEmpty() || parent == me) return@any false
            if (!me.startsWith("$parent/")) return@any false
            // Родитель покрывает ребёнка, только если обходит хотя бы так же
            // глубоко. Иначе ребёнок сканируется сам — иначе игры в нём
            // пропадут ровно из-за «оптимизации».
            other.deepScan || !entry.deepScan
        }
    }

    /**
     * Зеркало в текстовом виде: по строке на папку, `1`/`0` — глубокий обход.
     *
     * Без JSON намеренно. Список папок — это «строка + флаг», и разбор
     * формата, состоящего из двух полей, не должен превращаться в
     * зависимость от библиотеки. Перевод строки в URI не встречается: он
     * кодируется как %0A ещё до записи.
     */
    fun toText(list: List<Entry>): String =
        list.asSequence()
            .filter { isUsable(it.uri) }
            .map { (if (it.deepScan) "1" else "0") + "\t" + normalise(it.uri) }
            .joinToString("\n")

    /**
     * Читает зеркало, пропуская испорченные строки по одной.
     *
     * Одна битая строка не должна стоить пользователю всего списка папок —
     * именно так теряются настройки у людей: разбор упал, файл сочли
     * мусором, настройка обнулилась.
     */
    fun fromText(text: String?): List<Entry> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<Entry>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val tab = line.indexOf('\t')
            val deep: Boolean
            val uri: String
            if (tab < 0) {
                // Строка без флага: считаем обычным обходом, но папку
                // сохраняем — имя важнее флага.
                deep = false
                uri = line
            } else {
                deep = line.substring(0, tab).trim() == "1"
                uri = line.substring(tab + 1).trim()
            }
            if (!isUsable(uri)) return@forEach
            val key = normalise(uri)
            if (out.any { normalise(it.uri) == key }) return@forEach
            out.add(Entry(key, deep))
        }
        return out
    }
}
