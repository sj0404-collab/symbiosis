// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Вторая копия списка папок с играми, независимая от конфига ядра.
 *
 * Почему это понадобилось. Конфиг хранит папки внутри `AndroidSettings`, и
 * записывается он не мгновенно: между «пользователь добавил папку» и «байты
 * легли на диск» есть окно. Если в это окно приложение падает — а падение
 * при выходе из игры как раз и видел пользователь, — на диске остаётся
 * недописанный файл, и читается из него одна первая папка. Остальные
 * исчезают не потому, что их удалили, а потому, что их не успели записать.
 *
 * Восстановить это можно только из копии. Копия маленькая (строка на папку),
 * пишется в SharedPreferences тем же вызовом, что и конфиг, и переживает
 * сбой, потому что пишется ДО него — при каждом успешном чтении списка.
 *
 * Вторая задача — честно показывать папки без доступа. Раньше папка, у
 * которой отозвали разрешение, вычищалась из конфига при первом же обходе:
 * «нет доступа» превращалось в «нет папки», и вернуть её можно было только
 * вспомнив путь. Теперь она остаётся в списке и помечается.
 */
object GameFoldersStore {

    private const val KEY_MIRROR = "symbiosis_game_folders_mirror"
    private const val KEY_NO_ACCESS = "symbiosis_game_folders_no_access"

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    /** Что помнит зеркало. */
    fun mirrored(): List<GameFolderList.Entry> =
        GameFolderList.fromText(prefs.getString(KEY_MIRROR, null))

    /** Явная запись: пользователь добавил или убрал папку сам. */
    fun save(list: List<GameFolderList.Entry>) {
        runCatching {
            prefs.edit().putString(KEY_MIRROR, GameFolderList.toText(list)).apply()
        }
    }

    /**
     * Дополняет зеркало текущим списком, ничего не забывая.
     *
     * Вызывается после каждого обхода: так зеркало догоняет конфиг, но не
     * сокращается вместе с ним, когда конфиг пришёл коротким.
     */
    fun keep(list: List<GameFolderList.Entry>) {
        val merged = GameFolderList.union(list, mirrored())
        if (GameFolderList.toText(merged) != GameFolderList.toText(mirrored())) save(merged)
    }

    /**
     * Папки, которые пропали из конфига, но есть в зеркале.
     *
     * Возвращаются вызывающему, а не пишутся здесь: решение «дописать в
     * конфиг» требует нативной записи и делается там, где уже есть доступ
     * к `NativeConfig`.
     */
    fun missing(config: List<GameFolderList.Entry>): List<GameFolderList.Entry> =
        GameFolderList.missingFrom(config, mirrored())

    /** Папки, к которым сейчас нет доступа. */
    fun markNoAccess(uris: List<String>) {
        runCatching {
            val text = uris.filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
            prefs.edit().putString(KEY_NO_ACCESS, text).apply()
        }
    }

    fun noAccess(): Set<String> =
        prefs.getString(KEY_NO_ACCESS, null)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    fun hasAccess(uri: String): Boolean =
        GameFolderList.normalise(uri) !in noAccess().map { GameFolderList.normalise(it) }
}
