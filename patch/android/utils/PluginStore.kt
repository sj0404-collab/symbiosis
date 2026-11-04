// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File

/**
 * Где лежат плагины и какие из них включены.
 *
 * ХРАНЕНИЕ — ПАПКА, А НЕ БАЗА
 *   Плагин — это файл, который человек может открыть, сравнить, поправить и
 *   прислать другому. Папка `files/plugins` в приватном каталоге приложения
 *   выбрана по двум причинам: туда не нужен доступ к общей памяти (и,
 *   значит, никаких разрешений), и содержимое переживает обновление APK.
 *
 * ФАЙЛ ПЕРЕКРЫВАЕТ ВСТРОЕННЫЙ
 *   Если загруженный плагин называется как встроенный, побеждает файл. Это
 *   и есть способ починить встроенный набор, не выпуская новую сборку:
 *   положил новый `mali-safe.json` — и правило поменялось.
 *
 * ОШИБКА ОДНОГО ФАЙЛА НЕ ЛОМАЕТ ОСТАЛЬНЫЕ
 *   Битый плагин не применяется, но и не выбрасывается из списка: он
 *   остаётся на экране вместе с причиной. Иначе человек правит файл, а
 *   видит «плагинов нет» и не понимает, что не так.
 */
object PluginStore {

    private const val PREFS = "symbiosis_plugins"
    private const val PREFIX = "on."

    enum class Source { BUILTIN, FILE }

    /** Плагин в списке: либо разобранный, либо с причиной, почему нет. */
    data class Installed(
        val id: String,
        val name: String,
        val spec: PluginApi.Plugin?,
        val source: Source,
        val fileName: String,
        val error: String,
        val enabled: Boolean
    ) {
        val usable: Boolean get() = spec != null
    }

    fun dir(context: Context): File = File(context.filesDir, "plugins")

    /** Прочитать всё, что есть: встроенное и файлы. */
    fun installed(context: Context): List<Installed> {
        val list = ArrayList<Installed>()

        BuiltinPlugins.all.forEachIndexed { index, json ->
            val name = "builtin-${index + 1}.json"
            val parsed = runCatching { PluginApi.load(json, name) }.getOrNull()
            if (parsed == null) {
                // Сюда попасть можно только испортив встроенный набор в коде:
                // он проверяется тестом при сборке. Показываем, а не прячем.
                list.add(
                    Installed("builtin-${index + 1}", name, null, Source.BUILTIN, name,
                        "встроенный плагин не разобрался", false)
                )
                return@forEachIndexed
            }
            list.add(
                Installed(
                    id = parsed.id,
                    name = parsed.name,
                    spec = parsed,
                    source = Source.BUILTIN,
                    fileName = name,
                    error = "",
                    enabled = isEnabled(context, parsed.id, BuiltinPlugins.enabledByDefault.contains(parsed.id))
                )
            )
        }

        val folder = dir(context)
        if (folder.isDirectory) {
            folder.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { e ->
                        list.add(
                            Installed(file.name, file.name, null, Source.FILE, file.name,
                                "не прочитать файл: ${e.message ?: "нет доступа"}", false)
                        )
                        return@forEach
                    }
                    val loaded = runCatching { PluginApi.load(text, file.name) }
                    val spec = loaded.getOrNull()
                    if (spec == null) {
                        val why = (loaded.exceptionOrNull() as? PluginApi.LoadError)?.message
                            ?: loaded.exceptionOrNull()?.message
                            ?: "неизвестная ошибка разбора"
                        list.add(Installed(file.name, file.name, null, Source.FILE, file.name, why, false))
                    } else {
                        list.add(
                            Installed(
                                id = spec.id,
                                name = spec.name,
                                spec = spec,
                                source = Source.FILE,
                                fileName = file.name,
                                error = "",
                                enabled = isEnabled(context, spec.id, defaultValue = true)
                            )
                        )
                    }
                }
        }

        // Файл перекрывает встроенный с тем же id: это способ заменить
        // правило, не дожидаясь новой сборки.
        val byId = LinkedHashMap<String, Installed>()
        list.forEach { item ->
            val key = if (item.usable) item.id else item.fileName
            val previous = byId[key]
            if (previous == null || (previous.source == Source.BUILTIN && item.source == Source.FILE)) {
                byId[key] = item
            }
        }
        return byId.values.toList()
    }

    /** Включённые и разобранные — то, что реально влияет на запуск. */
    fun active(context: Context): List<PluginApi.Plugin> =
        installed(context)
            .filter { it.enabled && it.spec != null }
            .mapNotNull { it.spec }

    /** Решения по конкретной игре (или по экрану, если игра не указана). */
    fun decisions(
        context: Context,
        gameContext: PluginApi.GameContext
    ): PluginEngine.Decisions = runCatching {
        PluginEngine.decide(active(context), gameContext)
    }.getOrElse { PluginEngine.Decisions() }

    /** Контекст запуска из того, что известно приложению. Ошибки — не повод падать. */
    fun contextFor(
        context: Context,
        title: String = "",
        path: String = "",
        engine: String = "",
        core: String = ""
    ): PluginApi.GameContext {
        val gpu = runCatching { NativeSymbiosis.getDetectedGpu() }.getOrDefault("")
        val ramGb = runCatching { EngineStore.totalRamGb() }.getOrDefault(0)
        val ext = path.substringAfterLast('.', "").lowercase()
        return PluginApi.GameContext(
            title = title,
            path = path,
            ext = ext,
            titleId = path.substringAfterLast('/', "").substringBefore('.').takeIf {
                it.length == 16 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }
            }.orEmpty(),
            gpu = gpu,
            engine = engine,
            ramGb = ramGb,
            core = core
        )
    }

    fun isEnabled(context: Context, id: String, defaultValue: Boolean): Boolean =
        prefs(context).getBoolean(PREFIX + id, defaultValue)

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREFIX + id, enabled).apply()
    }

    /** Плагины с ошибкой — отдельно: о них надо сказать на экране. */
    fun broken(context: Context): List<Installed> = installed(context).filter { !it.usable }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
