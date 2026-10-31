// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Применение плагинов: единственное место, где правила превращаются в решения.
 *
 * ПОЧЕМУ ОТДЕЛЬНЫЙ ДВИЖОК, А НЕ «ПО ПРАВИЛУ В КАЖДОМ ЭКРАНЕ»
 *   Плагин трогает четыре разные вещи: настройки ядра, выбор ядра, пометки в
 *   списке игр и сообщения над списком. Если бы каждый экран разбирал
 *   правила сам, при первом же конфликте («два плагина сказали разное»)
 *   ответ зависел бы от порядка, в котором фрагменты успели отрисоваться.
 *   Здесь порядок задан явно и одинаков всегда.
 *
 * ПОРЯДОК ПРИМЕНЕНИЯ
 *   Сначала плагин с меньшим priority, затем по id. Более поздняя запись
 *   перекрывает более раннюю — то есть выигрывает больший priority, а при
 *   равенстве тот, чей id больше. Так «мой хотфикс поверх встроенного набора»
 *   работает предсказуемо, и одинаковый набор файлов даёт один результат на
 *   любом устройстве.
 *
 * ЧЕГО ЗДЕСЬ НЕТ
 *   Движок ничего не читает с диска и не знает про Android: на вход — уже
 *   разобранные плагины и признаки запуска, на выходе — решения. Поэтому его
 *   можно проверить на хосте целиком, включая конфликты.
 */
object PluginEngine {

    /** Сообщение над списком игр, добавленное плагином. */
    data class Banner(val text: String, val severity: PluginApi.Severity, val pluginId: String)

    /** Что плагин говорит про конкретный файл. */
    data class Verdict(
        val badge: String,
        val note: String,
        val kind: PluginApi.Verdict,
        val pluginId: String
    )

    /**
     * Итог по одному запуску/экрану.
     *
     * [trace] — не для красоты: когда «игра пошла не так», первый вопрос
     * всегда «кто это решил». Каждая запись называет плагин и правило.
     */
    data class Decisions(
        val settings: Map<String, String> = emptyMap(),
        val core: EngineHybrid.Core? = null,
        val coreReason: String = "",
        val banners: List<Banner> = emptyList(),
        val verdicts: Map<String, Verdict> = emptyMap(),
        val trace: List<String> = emptyList()
    ) {
        fun isEmpty(): Boolean =
            settings.isEmpty() && core == null && banners.isEmpty() && verdicts.isEmpty()
    }

    /** Свести решения по одному контексту. */
    fun decide(plugins: List<PluginApi.Plugin>, ctx: PluginApi.GameContext): Decisions {
        val ordered = plugins.sortedBy { it.order() }

        val settings = LinkedHashMap<String, String>()
        var core: EngineHybrid.Core? = null
        var coreReason = ""
        val banners = LinkedHashMap<String, Banner>()
        val verdicts = LinkedHashMap<String, Verdict>()
        val trace = ArrayList<String>()

        for (plugin in ordered) {
            for ((index, rule) in plugin.rules.withIndex()) {
                if (!rule.condition().matches(ctx)) continue
                val where = "${plugin.id} · правило ${index + 1}"
                when (rule) {
                    is PluginApi.Rule.Setting -> {
                        rule.values.forEach { (key, value) ->
                            settings[key] = value
                            trace.add("$where: $key=$value")
                        }
                    }

                    is PluginApi.Rule.Core -> {
                        core = rule.core
                        coreReason = rule.reason
                        trace.add("$where: ядро ${rule.core.label}")
                    }

                    is PluginApi.Rule.Banner -> {
                        // Один и тот же текст от двух плагинов — одна плашка:
                        // список сообщений не должен зависеть от того, сколько
                        // плагинов решили сказать одно и то же.
                        val key = rule.text.lowercase()
                        banners[key] = Banner(rule.text, rule.severity, plugin.id)
                        trace.add("$where: сообщение «${rule.text}»")
                    }

                    is PluginApi.Rule.File -> {
                        val key = ctx.path.ifEmpty { "*" }
                        verdicts[key] = Verdict(rule.badge, rule.note, rule.verdict, plugin.id)
                        trace.add("$where: пометка «${rule.note}»")
                    }
                }
            }
        }

        // Плашки: сначала важные, при равенстве — по тексту. Порядок не
        // зависит от того, какой плагин прочитался первым.
        val sortedBanners = banners.values.sortedWith(
            compareByDescending<Banner> { it.severity.ordinal }.thenBy { it.text }
        )

        return Decisions(
            settings = settings,
            core = core,
            coreReason = coreReason,
            banners = sortedBanners,
            verdicts = verdicts,
            trace = trace
        )
    }

    /**
     * Наложить настройки плагинов на готовый конфиг ядра.
     *
     * Значения уже проверены при загрузке плагина, поэтому здесь нет разбора
     * мусора — только применение. Если всё же что-то не применилось (движок
     * поменялся, плагин старый), настройка остаётся как была: плагин не имеет
     * права уронить запуск.
     */
    fun patch(config: EngineHybrid.KenjiConfig, settings: Map<String, String>): EngineHybrid.KenjiConfig {
        var out = config
        settings.forEach { (key, value) ->
            EngineHybrid.apply(out, key, value)?.let { out = it }
        }
        return out
    }

    /**
     * Пометки плагинов на плитке игры.
     *
     * Экран ничего не решает сам: [NativeLibraryPresenter] собрал плитку,
     * плагин может добавить к ней причину. Действие (запуск, конвертер,
     * ключи) плагин не меняет — это решение презентера, и подменять его
     * правилом из файла значит получить «нажал запуск — открылся конвертер».
     */
    fun decorate(item: NativeLibraryPresenter.Item, decisions: Decisions): NativeLibraryPresenter.Item {
        val verdict = decisions.verdicts[item.path] ?: decisions.verdicts["*"] ?: return item
        // Уже нанесено — второй раз не добавляем: экран перерисовывается на
        // каждую букву поиска, и повторное «· нужна распаковка · нужна
        // распаковка» накапливалось бы прямо во время набора.
        if (verdict.note.isNotEmpty() && item.note.contains(verdict.note)) return item
        val note = when {
            item.note.isEmpty() -> verdict.note
            verdict.note.isEmpty() -> item.note
            else -> "${item.note} · ${verdict.note}"
        }
        return item.copy(
            badge = verdict.badge.ifEmpty { item.badge },
            note = note
        )
    }

    fun decorate(
        items: List<NativeLibraryPresenter.Item>,
        decisions: Decisions
    ): List<NativeLibraryPresenter.Item> = items.map { decorate(it, decisions) }

    /**
     * Одна строка «почему так» для экрана и для сообщения при запуске.
     *
     * Пустая строка вместо «плагины ничего не сказали»: если решений нет,
     * человеку не нужно об этом знать.
     */
    fun summary(decisions: Decisions): String {
        if (decisions.isEmpty()) return ""
        val parts = ArrayList<String>()
        if (decisions.core != null) {
            val why = if (decisions.coreReason.isEmpty()) "" else " — ${decisions.coreReason}"
            parts.add("ядро выбрано плагином: ${decisions.core.label}$why")
        }
        if (decisions.settings.isNotEmpty()) {
            parts.add(
                "настройки плагинов: " +
                    decisions.settings.entries.joinToString(", ") { "${it.key}=${it.value}" }
            )
        }
        if (decisions.banners.isNotEmpty()) {
            parts.add("сообщений: ${decisions.banners.size}")
        }
        return parts.joinToString(" · ")
    }

    /**
     * Проверка перед запуском: не противоречат ли плагины друг другу.
     *
     * Не запрет, а предупреждение: два плагина могут законно спорить (общий
     * набор + личный хотфикс), и запрещать это значило бы запрещать сам
     * способ починки. Но когда «ядро Eden» и «ядро Kenji-NX» приходят от
     * разных плагинов, это стоит показать в журнале.
     */
    fun conflicts(plugins: List<PluginApi.Plugin>, ctx: PluginApi.GameContext): List<String> {
        val ordered = plugins.sortedBy { it.order() }
        val cores = ordered.mapNotNull { plugin ->
            plugin.rules.filterIsInstance<PluginApi.Rule.Core>()
                .firstOrNull { it.whenIt.matches(ctx) }
                ?.let { plugin.id to it.core }
        }
        val out = ArrayList<String>()
        if (cores.map { it.second }.toSet().size > 1) {
            out.add(
                "разные ядра от разных плагинов: " +
                    cores.joinToString(", ") { "${it.first} → ${it.second.label}" } +
                    ". Победит ${cores.last().first} (приоритет/имя)."
            )
        }

        val settingsByKey = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        ordered.forEach { plugin ->
            plugin.rules.filterIsInstance<PluginApi.Rule.Setting>()
                .filter { it.whenIt.matches(ctx) }
                .forEach { rule ->
                    rule.values.forEach { (key, value) ->
                        settingsByKey.getOrPut(key) { ArrayList() }.add(plugin.id to value)
                    }
                }
        }
        settingsByKey.forEach { (key, list) ->
            val values = list.map { it.second }.toSet()
            if (values.size > 1) {
                out.add(
                    "«$key» задают по-разному: " +
                        list.joinToString(", ") { "${it.first}=${it.second}" } +
                        ". Победит ${list.last().first}."
                )
            }
        }
        return out
    }
}
