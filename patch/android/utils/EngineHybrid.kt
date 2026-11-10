// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.security.MessageDigest
import java.util.Locale

/**
 * Два ядра в одном: как настройки Eden и Kenji-NX живут вместе.
 *
 * WHY THIS FILE EXISTS
 *   В приложении два ядра — Eden (собран в APK) и Kenji-NX (скачивается
 *   отдельным файлом). Игры на них идут разные: где одно падает, другое
 *   иногда едет. Но настройки были устроены как «или-или»: выбрать второе
 *   ядро значило потерять настройки первого, а сам Kenji запускался с
 *   числами, вписанными в код (`preparePlay`), — то есть его настройки не
 *   влияли ни на что.
 *
 *   Гибрид — это три правила, и все три живут здесь, в чистом Kotlin:
 *
 *     1. **общие** настройки (масштаб, док, кэш шейдеров, NCE, язык,
 *        регион) Kenji берёт из Eden, пока человек не тронул их руками;
 *     2. **свои** настройки Kenji (режим памяти, PPTC, macro HLE, время
 *        GPU, анизотропия, интернет, проверка целостности) у каждого ядра
 *        собственные и Eden их не перетирает;
 *     3. **ядро на игру** — не глобальный переключатель, а свойство игры:
 *        одна и та же библиотека запускается на двух ядрах по очереди, и
 *        ни один выбор не стирает другой.
 *
 * WHY NO ANDROID HERE
 *   Хранилище — SharedPreferences, чтение настроек Eden — рефлексия по его
 *   enum'ам; оба конца живут в [EngineStore]. Этот файл собирается обычным
 *   kotlinc и проверяется на хосте (`tests/EngineHybridTest.kt`), поэтому
 *   «какие настройки куда поедут» можно доказать, не собирая APK.
 */
object EngineHybrid {

    /**
     * Версия схемы хранения.
     *
     * Поднимается, если меняется смысл сохранённых значений. Старое
     * хранилище тогда читается как «ничего не сохранено», а не как мусор,
     * который тихо ломает запуск.
     */
    const val VERSION = 1

    /** Какое ядро запускает игру. */
    enum class Core(val id: String, val label: String, val short: String) {
        EDEN("eden", "Eden · основное ядро", "eden"),
        KENJI("kenji", "Kenji-NX · второе ядро", "kenji");

        companion object {
            fun of(id: String?): Core = values().firstOrNull { it.id == id } ?: EDEN
        }
    }

    /** Откуда взялось значение настройки второго ядра. */
    enum class Source {
        /** Подтянуто из Eden: общая настройка, пока не тронута руками. */
        EDEN,

        /** Своё значение второго ядра. */
        OWN
    }

    /** Строка «что куда поедет» для экрана ядер. */
    data class Row(
        val id: String,
        val label: String,
        val value: String,
        val source: Source
    )

    /**
     * Настройки второго ядра — ровно те аргументы, которые принимают
     * `nativeDeviceInit` и `nativeGraphicsInit` моста.
     *
     * Значения по умолчанию повторяют то, что раньше было вписано в код
     * (`KenjiBridge.preparePlay`): 4 ГБ, PPTC включён, NCE выключен,
     * масштаб 1x. Это не «правильные» настройки, а проверенные на этом
     * телефоне — менять их стоит по одной.
     */
    data class KenjiConfig(
        // deviceInitialize
        val memoryManagerMode: Int = 2,
        val useNce: Boolean = false,
        val memoryConfiguration: Int = 0,
        val systemLanguage: Int = 1,
        val regionCode: Int = 1,
        val vSyncMode: Int = 0,
        val enableDockedMode: Boolean = false,
        val enablePtc: Boolean = true,
        val enableLowPowerPtc: Boolean = false,
        val enableJitCacheEviction: Boolean = false,
        val enableInternetAccess: Boolean = false,
        val enableFsIntegrityChecks: Boolean = false,
        val fsGlobalAccessLogMode: Int = 0,
        val timeZone: String = "UTC",
        val ignoreMissingServices: Boolean = false,
        // graphicsInitialize
        val resScale: Float = 1f,
        val maxAnisotropy: Float = 0f,
        val fastGpuTime: Boolean = true,
        val fast2DCopy: Boolean = true,
        val enableMacroJit: Boolean = false,
        val enableMacroHLE: Boolean = true,
        val enableShaderCache: Boolean = true,
        val enableTextureRecompression: Boolean = false,
        val backendThreading: Int = 1
    )

    /**
     * Что мы умеем прочитать у Eden и передать второму ядру.
     *
     * Только то, что реально существует в настройках Eden: масштаб
     * (`RENDERER_RESOLUTION`), док (`USE_DOCKED_MODE`), кэш шейдеров
     * (`RENDERER_USE_DISK_SHADER_CACHE`), CPU-бэкенд (`CPU_BACKEND`).
     * Читаются рефлексией в [EngineStore]; сюда приходят уже числами, чтобы
     * этот файл оставался без Android.
     */
    data class EdenSnapshot(
        val resolutionScale: Float = 1f,
        val docked: Boolean = false,
        val shaderCache: Boolean = true,
        val nce: Boolean = false,
        val ramGb: Int = 8,
        val language: Int = 1,
        val region: Int = 1
    )

    /** Значения, которые задаёт Eden, пока человек не переопределил их у Kenji. */
    fun seed(eden: EdenSnapshot): KenjiConfig = KenjiConfig(
        resScale = clampScale(eden.resolutionScale),
        enableDockedMode = eden.docked,
        enableShaderCache = eden.shaderCache,
        useNce = eden.nce,
        // This is a memory-manager algorithm, not a RAM capacity. Mode 3
        // crashed the actual pinned core on Android during guest startup.
        memoryManagerMode = 2,
        systemLanguage = eden.language,
        regionCode = eden.region
    )

    /**
     * Свести настройки обоих ядер в один набор для запуска.
     *
     * `stored` — то, что человек изменил руками у второго ядра (ключи вида
     * `kenji.<id>`). Ничего не удаляется: каждое сохранённое поле
     * перекрывает только своё, остальные продолжают следовать Eden. Именно
     * поэтому переключение ядер больше ничего не стирает.
     */
    fun merge(eden: EdenSnapshot, stored: Map<String, String>): KenjiConfig {
        var cfg = seed(eden)
        stored.forEach { (key, raw) ->
            apply(cfg, key, raw)?.let { cfg = it }
        }
        return cfg
    }

    /**
     * Применить одно сохранённое значение.
     *
     * Возвращает null для неизвестного ключа и для мусора в значении:
     * испорченная запись должна оставить настройку в значении из Eden, а не
     * уронить запуск разбором строки.
     */
    fun apply(cfg: KenjiConfig, key: String, raw: String): KenjiConfig? {
        val id = key.removePrefix(PREFIX)
        val v = raw.trim()
        return when (id) {
            "memory" -> v.toIntOrNull()?.takeIf { it in 0..2 }?.let {
                cfg.copy(memoryManagerMode = it)
            }
            "nce" -> bool(v)?.let { cfg.copy(useNce = it) }
            "docked" -> bool(v)?.let { cfg.copy(enableDockedMode = it) }
            "shaderCache" -> bool(v)?.let { cfg.copy(enableShaderCache = it) }
            "ptc" -> bool(v)?.let { cfg.copy(enablePtc = it) }
            "lowPowerPtc" -> bool(v)?.let { cfg.copy(enableLowPowerPtc = it) }
            "macroHle" -> bool(v)?.let { cfg.copy(enableMacroHLE = it) }
            "macroJit" -> bool(v)?.let { cfg.copy(enableMacroJit = it) }
            "fastGpuTime" -> bool(v)?.let { cfg.copy(fastGpuTime = it) }
            "fast2DCopy" -> bool(v)?.let { cfg.copy(fast2DCopy = it) }
            "internet" -> bool(v)?.let { cfg.copy(enableInternetAccess = it) }
            "integrity" -> bool(v)?.let { cfg.copy(enableFsIntegrityChecks = it) }
            "services" -> bool(v)?.let { cfg.copy(ignoreMissingServices = it) }
            "vsync" -> v.toIntOrNull()?.takeIf { it in 0..2 }?.let {
                cfg.copy(vSyncMode = it)
            }
            "scale" -> v.toFloatOrNull()?.takeIf { it in 0.25f..4f }?.let {
                cfg.copy(resScale = clampScale(it))
            }
            "aniso" -> v.toFloatOrNull()?.takeIf { it in 0f..16f }?.let {
                cfg.copy(maxAnisotropy = it)
            }
            "threads" -> v.toIntOrNull()?.takeIf { it in 0..4 }?.let {
                cfg.copy(backendThreading = it)
            }
            else -> null
        }
    }

    /** Текущее значение поля в той же кодировке, в какой оно хранится. */
    fun raw(cfg: KenjiConfig, id: String): String = when (id) {
        "memory" -> cfg.memoryManagerMode.toString()
        "nce" -> cfg.useNce.toString()
        "docked" -> cfg.enableDockedMode.toString()
        "shaderCache" -> cfg.enableShaderCache.toString()
        "ptc" -> cfg.enablePtc.toString()
        "lowPowerPtc" -> cfg.enableLowPowerPtc.toString()
        "macroHle" -> cfg.enableMacroHLE.toString()
        "macroJit" -> cfg.enableMacroJit.toString()
        "fastGpuTime" -> cfg.fastGpuTime.toString()
        "fast2DCopy" -> cfg.fast2DCopy.toString()
        "internet" -> cfg.enableInternetAccess.toString()
        "integrity" -> cfg.enableFsIntegrityChecks.toString()
        "services" -> cfg.ignoreMissingServices.toString()
        "vsync" -> cfg.vSyncMode.toString()
        "scale" -> trimFloat(cfg.resScale)
        "aniso" -> trimFloat(cfg.maxAnisotropy)
        "threads" -> cfg.backendThreading.toString()
        else -> ""
    }

    /** Подпись значения для человека. */
    fun label(cfg: KenjiConfig, id: String): String {
        val value = raw(cfg, id)
        val field = FIELDS.firstOrNull { it.id == id }
        return field?.choices?.firstOrNull { it.value == value }?.label ?: value
    }

    /** Следующее значение поля — по кругу. Для строки-переключателя. */
    fun next(cfg: KenjiConfig, id: String): String {
        val choices = FIELDS.firstOrNull { it.id == id }?.choices ?: return raw(cfg, id)
        if (choices.isEmpty()) return raw(cfg, id)
        val current = choices.indexOfFirst { it.value == raw(cfg, id) }
        return choices[(current + 1).mod(choices.size)].value
    }

    /**
     * Что изменится у второго ядра, если поменять настройку Eden.
     *
     * Список — это и есть «гибрид» на экране: видно, какие строки общие,
     * а какие принадлежат только Kenji.
     */
    fun rows(eden: EdenSnapshot, stored: Map<String, String>): List<Row> {
        val cfg = merge(eden, stored)
        return FIELDS.map { field ->
            val own = stored.containsKey(PREFIX + field.id)
            Row(
                id = field.id,
                label = field.label,
                value = if (field.id == "memory" && stored[PREFIX + field.id] == "3")
                    label(cfg, field.id) + " · старое значение 3 отклонено"
                else label(cfg, field.id),
                source = when {
                    own -> Source.OWN
                    field.shared -> Source.EDEN
                    else -> Source.OWN
                }
            )
        }
    }

    /** Короткая строка для экрана игры и статуса плеера. */
    fun summary(cfg: KenjiConfig): String = buildString {
        append("масштаб ").append(trimFloat(cfg.resScale)).append('x')
        append(" · память ").append(memoryLabel(cfg.memoryManagerMode))
        append(" · NCE ").append(if (cfg.useNce) "вкл" else "выкл")
        append(" · док ").append(if (cfg.enableDockedMode) "вкл" else "выкл")
        if (cfg.maxAnisotropy > 0f) append(" · анизо ").append(trimFloat(cfg.maxAnisotropy))
    }

    /** Каким ядром запускать игру: своё значение важнее общего. */
    fun coreFor(defaultEngineId: String, perGameEngineId: String?): Core =
        if (!perGameEngineId.isNullOrBlank()) Core.of(perGameEngineId) else Core.of(defaultEngineId)

    /**
     * Ключ игры для хранения выбора ядра.
     *
     * Путь бывает content-URI с пробелами и кириллицей, поэтому ключ — это
     * SHA-256, обрезанный до 16 hex-символов: коллизий для одной библиотеки
     * нет, а SharedPreferences не получает ключи длиной в URI.
     */
    fun gameKey(path: String): String {
        if (path.isEmpty()) return ""
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Ключ игры по имени файла — второй, более грубый.
     *
     * Нужен, когда путь одной и той же игры приходит записанным иначе
     * (другой корень, другой способ открытия папки). Тогда точный ключ не
     * совпадёт, а имя файла — совпадёт.
     */
    fun gameKeyByName(path: String): String {
        if (path.isEmpty()) return ""
        val name = path.substringAfterLast('/').substringAfterLast(':').trim().lowercase()
        if (name.isEmpty()) return ""
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(name.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun memoryLabel(mode: Int): String = when (mode) {
        0 -> "режим 0"
        1 -> "режим 1"
        2 -> "режим 2"
        else -> "режим $mode"
    }

    /** Ограничение масштаба: ниже 0.25 рисуется в никуда, выше 4 — слайд-шоу. */
    fun clampScale(value: Float): Float = when {
        value.isNaN() -> 1f
        value < 0.25f -> 0.25f
        value > 4f -> 4f
        else -> value
    }

    /** `1.0` → `1`, `0.75` → `0.75`: человеку не нужен хвост нулей. */
    fun trimFloat(value: Float): String {
        val text = String.format(Locale.ROOT, "%.2f", value)
        return text.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    /**
     * Свернуть набор значений в строку для передачи в другой процесс.
     *
     * ЗАЧЕМ: второй плеер живёт в процессе `:kenji`, а SharedPreferences
     * между процессами не синхронизируются — фоновой процесс читает свою
     * копию файла и может увидеть старые настройки. Поэтому действующий
     * набор считает тот процесс, где человек нажал «Запустить» (главный), и
     * передаёт его строкой в Intent.
     *
     * Формат простой (`id=значение` через `;`) и намеренно без JSON: строка
     * читается глазами в логе, а разбор не может бросить исключение.
     */
    fun encode(values: Map<String, String>): String =
        values.entries
            .filter { it.value.isNotEmpty() }
            .joinToString(";") { "${it.key}=${it.value}" }

    /** Разобрать то, что вернул [encode]. Мусор пропускается, а не роняет запуск. */
    fun decode(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        text.split(';').forEach { pair ->
            val cut = pair.indexOf('=')
            if (cut <= 0) return@forEach
            val key = pair.substring(0, cut).trim()
            val value = pair.substring(cut + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    /** Действующие значения всех полей — то, что реально уйдёт в ядро. */
    fun effective(cfg: KenjiConfig): Map<String, String> =
        FIELDS.associate { it.id to raw(cfg, it.id) }

    const val PREFIX = "kenji."

    /** Одно редактируемое поле второго ядра. */
    data class Choice(val value: String, val label: String)

    data class Field(
        val id: String,
        val label: String,
        val hint: String,
        /** true — значение по умолчанию берётся из Eden. */
        val shared: Boolean,
        val choices: List<Choice>
    )

    private fun bool(raw: String): Boolean? = when (raw.lowercase(Locale.ROOT)) {
        "true", "1", "on", "вкл" -> true
        "false", "0", "off", "выкл" -> false
        else -> null
    }

    /**
     * Поля экрана «Ядра».
     *
     * Порядок — по частоте: память и масштаб трогают чаще, чем анизотропию.
     * `shared = true` значит «пока не тронуто, повторяет Eden»; такие поля
     * помечены на экране, чтобы было видно связь.
     */
    val FIELDS: List<Field> = listOf(
        Field(
            "memory", "Режим управления памятью",
            "Это способ управления памятью, а не размер в ГБ. Режимы 0–2 прошли короткую Android-пробу; совместимость игр не подтверждена. Старое значение 3 недопустимо.",
            false,
            listOf(
                Choice("2", "режим 2"),
                Choice("0", "режим 0"),
                Choice("1", "режим 1")
            )
        ),
        Field(
            "scale", "Масштаб картинки",
            "Общий с Eden: пока не тронуто, второй ядро рисует в том же масштабе.",
            true,
            listOf(
                Choice("0.5", "0.5x"),
                Choice("0.75", "0.75x"),
                Choice("1", "1x"),
                Choice("1.5", "1.5x")
            )
        ),
        Field(
            "nce", "NCE (нативный код)",
            "Общее с Eden: берётся его CPU-бэкенд. На Mali NCE быстрее, но чаще падает.",
            true,
            listOf(Choice("false", "выкл · Dynarmic"), Choice("true", "вкл · NCE"))
        ),
        Field(
            "docked", "Режим док",
            "Общий с Eden: док — больше пикселей и памяти, на 8 ГБ обычно медленнее.",
            true,
            listOf(Choice("false", "выкл"), Choice("true", "вкл"))
        ),
        Field(
            "shaderCache", "Кэш шейдеров",
            "Общий с Eden: второй запуск игры не компилирует пайплайны заново.",
            true,
            listOf(Choice("true", "вкл"), Choice("false", "выкл"))
        ),
        Field(
            "ptc", "PPTC (профиль кода)",
            "Только у Kenji: сохраняет переведённый код между запусками.",
            false,
            listOf(Choice("true", "вкл"), Choice("false", "выкл"))
        ),
        Field(
            "macroHle", "Macro HLE",
            "Только у Kenji: заменяет частые вызовы системы своей реализацией.",
            false,
            listOf(Choice("true", "вкл"), Choice("false", "выкл"))
        ),
        Field(
            "fastGpuTime", "Быстрое время GPU",
            "Только у Kenji: меньше синхронизаций, возможны артефакты в редких играх.",
            false,
            listOf(Choice("true", "вкл"), Choice("false", "выкл"))
        ),
        Field(
            "aniso", "Анизотропия",
            "Только у Kenji. 0 — выключена: на Mali каждая ступень стоит кадров.",
            false,
            listOf(
                Choice("0", "выкл"),
                Choice("4", "4x"),
                Choice("8", "8x"),
                Choice("16", "16x")
            )
        ),
        Field(
            "vsync", "Вертикальная синхронизация",
            "Только у Kenji. 0 — без ограничения кадров.",
            false,
            listOf(Choice("0", "выкл"), Choice("1", "вкл"))
        ),
        Field(
            "internet", "Интернет в играх",
            "Только у Kenji: без него онлайн-функции не работают, но и телеметрия молчит.",
            false,
            listOf(Choice("false", "выкл"), Choice("true", "вкл"))
        ),
        Field(
            "integrity", "Проверка целостности",
            "Только у Kenji. Выключена — быстрее запуск, как в проверенных настройках.",
            false,
            listOf(Choice("false", "выкл"), Choice("true", "вкл"))
        ),
        Field(
            "services", "Терпеть отсутствующие службы",
            "Только у Kenji. Игра просит системную службу, которой у ядра нет: " +
                "с этой строкой ядро отвечает заглушкой вместо остановки игры. " +
                "В Kenji Space она выключена по умолчанию — включать точечно, " +
                "если игра падает сразу после заставки.",
            false,
            listOf(Choice("false", "выкл"), Choice("true", "вкл"))
        )
    )
}
