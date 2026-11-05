// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context

/**
 * Хранилище гибрида: настройки второго ядра и выбор ядра для каждой игры.
 *
 * WHY A SEPARATE STORE
 *   Настройки Eden живут в его собственном конфиге через нативный код.
 *   Писать туда чужие ключи нельзя: Eden прочитает их как свои и либо
 *   проигнорирует, либо упадёт на разборе. Поэтому у второго ядра отдельный
 *   файл, а «общность» получается не общим хранилищем, а чтением: значения
 *   Eden подставляются в [EngineHybrid.merge] каждый раз при запуске.
 *
 *   Отсюда же следует главное свойство гибрида: переключение ядра ничего не
 *   стирает. У Eden свой конфиг, у Kenji свой, а выбор ядра для игры — это
 *   одна короткая запись, а не перезапись настроек.
 */
object EngineStore {

    private const val FILE = "symbiosis_engine_hybrid"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_PER_GAME = "game."
    /**
     * Тот же выбор, но по имени файла.
     *
     * Путь игры приходит из разных мест (библиотека, «недавние», папка через
     * внешнее хранилище) и может отличаться одной буквой — а ключ считается
     * от всего пути. Тогда выбор «эта игра идёт на Eden» пропадал бы молча.
     * Имя файла — второй, более грубый ключ; читается только если точного нет.
     */
    private const val KEY_PER_NAME = "gameby."
    private const val KEY_PINNED = "kenji_pin."

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Сохранённые значения второго ядра.
     *
     * Пустая карта при другой схеме — намеренно: старые ключи могут значить
     * другое, и «прочитать как есть» хуже, чем начать с значений Eden.
     */
    fun stored(context: Context): Map<String, String> {
        val p = prefs(context)
        if (p.getInt(KEY_SCHEMA, 0) != EngineHybrid.VERSION) return emptyMap()
        val out = LinkedHashMap<String, String>()
        p.all.forEach { (key, value) ->
            if (key.startsWith(EngineHybrid.PREFIX) && value is String) out[key] = value
        }
        return out
    }

    /** Настройки второго ядра, сведённые с Eden. */
    fun config(context: Context): EngineHybrid.KenjiConfig =
        EngineHybrid.merge(edenSnapshot(context), stored(context))

    /**
     * Конфиг с явно заданными значениями поверх Eden.
     *
     * Нужен процессу `:kenji`: он получает действующие значения строкой из
     * главного процесса, потому что общее хранилище между процессами не
     * синхронизируется.
     */
    fun configWith(context: Context, values: Map<String, String>): EngineHybrid.KenjiConfig {
        val overrides = values.mapKeys { EngineHybrid.PREFIX + it.key }
        return EngineHybrid.merge(edenSnapshot(context), overrides)
    }

    /** Действующий набор значений строкой — для передачи в :kenji. */
    fun effectiveText(context: Context): String =
        EngineHybrid.encode(EngineHybrid.effective(config(context)))

    fun rows(context: Context): List<EngineHybrid.Row> =
        EngineHybrid.rows(edenSnapshot(context), stored(context))

    fun summary(context: Context): String = EngineHybrid.summary(config(context))

    /**
     * Записать одно поле второго ядра.
     *
     * Возвращает сообщение для человека, а не Boolean: нажатие на строку
     * должно сказать, что стало, — экран не перерисовывается «сам собой».
     */
    fun setField(context: Context, id: String, raw: String): String {
        val field = EngineHybrid.FIELDS.firstOrNull { it.id == id }
            ?: return "нет настройки «$id»"
        val cfg = config(context)
        val next = EngineHybrid.apply(cfg, id, raw)
            ?: return "значение «$raw» не подошло"
        val p = prefs(context).edit()
        p.putInt(KEY_SCHEMA, EngineHybrid.VERSION)
        p.putString(EngineHybrid.PREFIX + id, raw)
        p.putBoolean(KEY_PINNED + id, true)
        p.apply()
        return "${field.label}: ${EngineHybrid.label(next, id)} · своё"
    }

    /** Провернуть поле на следующее значение. */
    fun cycleField(context: Context, id: String): String {
        val cfg = config(context)
        return setField(context, id, EngineHybrid.next(cfg, id))
    }

    /** Вернуть все настройки второго ядра к значениям Eden. */
    fun resetFields(context: Context): String {
        val p = prefs(context).edit()
        stored(context).keys.forEach { p.remove(it) }
        p.putInt(KEY_SCHEMA, EngineHybrid.VERSION)
        p.apply()
        return "настройки второго ядра снова повторяют Eden"
    }

    /** Тронуто ли поле руками (значит, Eden его больше не перекрывает). */
    fun pinned(context: Context, id: String): Boolean =
        prefs(context).getBoolean(KEY_PINNED + id, false)

    // ── ядро на игру ────────────────────────────────────────────────────

    /** Ядро, которым запустится эта игра: своё значение важнее общего. */
    fun coreFor(context: Context, path: String): EngineHybrid.Core {
        val key = EngineHybrid.gameKey(path)
        val p = prefs(context)
        val perGame = when {
            key.isEmpty() -> null
            else -> p.getString(KEY_PER_GAME + key, null)
                ?: p.getString(KEY_PER_NAME + EngineHybrid.gameKeyByName(path), null)
        }
        return EngineHybrid.coreFor(globalCore(context).id, perGame)
    }

    fun perGame(context: Context, path: String): EngineHybrid.Core? {
        val key = EngineHybrid.gameKey(path)
        if (key.isEmpty()) return null
        val raw = prefs(context).getString(KEY_PER_GAME + key, null) ?: return null
        return EngineHybrid.Core.of(raw)
    }

    /**
     * Запомнить ядро за игрой. Возвращает true, только если запись реально
     * легла на диск.
     *
     * commit(), а не apply(): apply() пишет асинхронно, и если процесс
     * закроют сразу после (а приложение как раз в этот момент показывает
     * отчёт о падении), запись может не дойти. Обещать «переведена на Eden»
     * по несохранённой записи нельзя — уже обещали.
     */
    fun setCoreFor(context: Context, path: String, core: EngineHybrid.Core): Boolean {
        val key = EngineHybrid.gameKey(path)
        if (key.isEmpty()) return false
        val nameKey = EngineHybrid.gameKeyByName(path)
        val editor = prefs(context).edit().putString(KEY_PER_GAME + key, core.id)
        if (nameKey.isNotEmpty()) editor.putString(KEY_PER_NAME + nameKey, core.id)
        return runCatching { editor.commit() }.getOrDefault(false)
    }

    /** Убрать выбор ядра у игры: снова действует общее значение. */
    fun clearCoreFor(context: Context, path: String) {
        val key = EngineHybrid.gameKey(path)
        if (key.isEmpty()) return
        val editor = prefs(context).edit().remove(KEY_PER_GAME + key)
        val nameKey = EngineHybrid.gameKeyByName(path)
        if (nameKey.isNotEmpty()) editor.remove(KEY_PER_NAME + nameKey)
        editor.apply()
    }

    /** Ядро по умолчанию — то, что выбрано на экране ядер. */
    fun globalCore(context: Context): EngineHybrid.Core =
        EngineHybrid.Core.of(EnginePreference.selectedRaw(context).id)

    fun setGlobalCore(context: Context, core: EngineHybrid.Core) {
        val engine = EngineLoader.Engine.values().firstOrNull { it.id == core.id }
            ?: EngineLoader.Engine.EDEN
        EnginePreference.select(context, engine)
    }

    /**
     * Что у Eden можно прочитать и передать второму ядру.
     *
     * Чтение идёт рефлексией через [LauncherSettings] и не бросает: если
     * настройка не нашлась (другая версия ядра, ещё не загружен конфиг),
     * остаётся значение по умолчанию, а не падение на старте.
     */
    fun edenSnapshot(context: Context): EngineHybrid.EdenSnapshot =
        EngineHybrid.EdenSnapshot(
            resolutionScale = runCatching { LauncherSettings.edenResolutionScale() }.getOrDefault(1f),
            docked = LauncherSettings.edenBool("USE_DOCKED_MODE") ?: false,
            shaderCache = LauncherSettings.edenBool("RENDERER_USE_DISK_SHADER_CACHE") ?: true,
            nce = runCatching { LauncherSettings.cpuBackend() == 1 }.getOrDefault(false),
            ramGb = totalRamGb(),
            language = 1,
            region = 1
        )

    /** Сколько памяти у телефона — от этого зависит режим памяти Kenji. */
    fun totalRamGb(): Int {
        val kb = runCatching {
            java.io.File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal:") }
                    ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
            }
        }.getOrNull() ?: 0L
        if (kb <= 0L) return 8
        return (kb / 1024 / 1024).toInt().coerceAtLeast(1)
    }
}
