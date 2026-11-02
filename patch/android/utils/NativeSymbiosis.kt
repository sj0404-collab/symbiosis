// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Kotlin bridge to the native Symbiosis layer.
 *
 * The native side encodes profiles as delimited strings rather than JNI
 * objects: it is dramatically cheaper to marshal and keeps the C++ free of
 * JNI class lookups.
 */
object NativeSymbiosis {
    private const val FIELD_SEP = '\u001f'
    private const val RECORD_SEP = '\u001e'

    // --- Diagnostics -----------------------------------------------------

    /**
     * Папка журнала ядра — по мнению самого ядра.
     *
     * Путь не выводится в Kotlin из `filesDir`: корень данных настраивается
     * (SharedDataDirectory), и после переноса в общую папку журнал лежит не
     * там, где его ждёт догадка.
     */
    external fun logDirectory(): String

    /**
     * Включить отчёт о нативном падении этого процесса.
     *
     * Основная эмуляция живёт в libyuzu-android.so, и её SIGSEGV не доходит до
     * Java-обработчика исключений: процесс умирает внутри нативной части.
     * Обработчик в C++ пишет в файл шапку и кадры стека, а следующий запуск
     * показывает их человеку ([UiGuard.pending]).
     */
    external fun armMainCrashLog(path: String)

    /**
     * Отметить текущее действие — оно попадёт в отчёт, если приложение упадёт.
     *
     * Зовётся на границах опасных операций (применение настроек, перезапуск
     * игры). Одна строка в отчёте отделяет «упало на настройках» от «упало при
     * запуске», и гадать потом не нужно.
     */
    external fun setMainStep(step: String)

    external fun getDriverTopology(): String
    external fun getMemoryState(): String
    external fun getMemoryJson(): String
    external fun previousSessionCrashed(): Boolean
    external fun getShimReport(): String
    external fun runSelfTest(): String
    private external fun runSelfTestCounts(): String
    external fun getDetectedGpu(): String

    /**
     * Why one ROM will not load, as "code|human sentence".
     *
     * Kotlin only ever saw a bare false from getIsValid, so the UI had to
     * guess and said "check keys and firmware" for every cause alike. This
     * asks the loader directly.
     */
    external fun diagnoseRom(path: String): String

    /**
     * Живые замеры звука: «просили|получили|каналы|буфер|ёмкость|порция|
     * опустошения|вызовы|драйвер|ошибка|объяснение».
     *
     * Нужны, чтобы отличить «настройка применилась и не помогла» от
     * «настройка не применилась вовсе». На слух эти два случая неразличимы.
     */
    external fun getAudioReport(): String

    /**
     * Подхватить дополнительные файлы ключей из папки keys.
     *
     * Ядро читает ровно prod.keys и title.keys. Ключ одной игры, положенный
     * отдельным файлом, раньше не видел никто — отсюда «ключ есть, а игра его
     * не видит». Возвращает отчёт словами; пусто — дополнительных файлов нет.
     */
    external fun loadExtraKeys(keysDir: String): String

    /** The sentence half of [diagnoseRom], or null when the file is fine. */
    fun romProblem(path: String): String? = runCatching {
        val raw = diagnoseRom(path)
        val code = raw.substringBefore('|')
        if (code == "ok") null else raw.substringAfter('|', raw)
    }.getOrNull()

    // --- Crash guard -----------------------------------------------------
    /** True when the layer switched itself off after an unclean shutdown. */
    external fun isSafeMode(): Boolean

    /** Re-enables the layer for the next launch. */
    external fun clearSafeMode()
    private external fun getThermalState(): String

    data class Thermal(
        val state: String,
        val tempCelsius: Int,
        val gpuClockPercent: Int,
        val summary: String,
        val advice: String
    ) {
        val isThrottling: Boolean get() = state == "Throttling" || state == "Critical"
        val hasSensors: Boolean get() = state != "Unknown"
    }

    fun thermal(): Thermal =
        runCatching {
            val p = getThermalState().split('|')
            Thermal(
                state = p.getOrElse(0) { "Unknown" },
                tempCelsius = p.getOrElse(1) { "-1" }.toIntOrNull() ?: -1,
                gpuClockPercent = p.getOrElse(2) { "-1" }.toIntOrNull() ?: -1,
                summary = p.getOrElse(3) { "" },
                advice = p.getOrElse(4) { "" }
            )
        }.getOrElse { Thermal("Unknown", -1, -1, "", "") }

    // --- Ровность кадров ---------------------------------------------------
    /**
     * Снимок того, что происходило с кадрами и звуком во время игры.
     *
     * Счётчики живут в нативной части и переживают выход из игры: поиграли,
     * вышли, открыли экран — данные на месте. Разбор — в [FrameMetricsText].
     */
    external fun frameMetrics(): String

    /** Начать новый замер. */
    external fun resetFrameMetrics()

    /** Снимок, уже разобранный. Пустой, если нативная часть недоступна. */
    fun frameSnapshot(): FrameMetricsText.Snapshot =
        FrameMetricsText.parse(runCatching { frameMetrics() }.getOrDefault(""))

    // --- Profiles --------------------------------------------------------
    private external fun getProfilesForDevice(): String
    private external fun getAllProfiles(): String
    external fun describeProfile(id: String): String

    /** Applies a profile's tweaks. Returns how many settings actually changed. */
    external fun applyProfile(id: String): Int

    data class Tweak(
        val key: String,
        val value: String,
        val reason: String
    )

    data class Profile(
        val id: String,
        val displayName: String,
        val summary: String,
        val expectedEffect: String,
        val worksOnStockDriver: Boolean,
        val tweaks: List<Tweak>
    )

    data class SelfTestCounts(val passed: Int, val warned: Int, val failed: Int)

    fun selfTestCounts(): SelfTestCounts =
        runCatching {
            val parts = runSelfTestCounts().split('|')
            SelfTestCounts(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }.getOrElse { SelfTestCounts(0, 0, 0) }

    // --- Settings guide --------------------------------------------------
    private external fun getGuideEntries(section: Int, all: Boolean): String
    private external fun getGuideForKey(key: String): String

    data class GuideEntry(
        val key: String,
        val title: String,
        val section: String,
        val risk: String,
        val what: String,
        val cost: String,
        val advice: String
    )

    private fun decodeGuide(raw: String): List<GuideEntry> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val f = record.split(FIELD_SEP)
            if (f.size < 7) return@mapNotNull null
            GuideEntry(f[0], f[1], f[2], f[3], f[4], f[5], f[6])
        }
    }

    fun guideEntries(section: Int = -1): List<GuideEntry> =
        decodeGuide(safeCall { getGuideEntries(section, section < 0) })

    fun guideForKey(key: String): GuideEntry? =
        decodeGuide(runCatching { getGuideForKey(key) }.getOrDefault("")).firstOrNull()

    // --- Utilities: firmware vault ---------------------------------------
    private external fun analyseFirmware(dir: String): String
    external fun pruneFirmware(dir: String, keepApplets: Boolean, keepFonts: Boolean, keepLanguages: Boolean): Long
    external fun estimateFirmware(dir: String, keepApplets: Boolean, keepFonts: Boolean, keepLanguages: Boolean): Long
    external fun getCompressionNote(): String

    data class FirmwareInfo(
        val totalBytes: Long,
        val essentialBytes: Long,
        val fontBytes: Long,
        val appletBytes: Long,
        val languageBytes: Long,
        val entryCount: Int,
        val prunableBytes: Long
    ) {
        val isEmpty: Boolean get() = entryCount == 0
    }

    fun firmwareInfo(dir: String): FirmwareInfo =
        runCatching {
            val p = analyseFirmware(dir).split('|')
            FirmwareInfo(
                p.getOrElse(0) { "0" }.toLongOrNull() ?: 0L,
                p.getOrElse(1) { "0" }.toLongOrNull() ?: 0L,
                p.getOrElse(2) { "0" }.toLongOrNull() ?: 0L,
                p.getOrElse(3) { "0" }.toLongOrNull() ?: 0L,
                p.getOrElse(4) { "0" }.toLongOrNull() ?: 0L,
                p.getOrElse(5) { "0" }.toIntOrNull() ?: 0,
                p.getOrElse(6) { "0" }.toLongOrNull() ?: 0L
            )
        }.getOrElse { FirmwareInfo(0, 0, 0, 0, 0, 0, 0) }

    // --- Utilities: ROM tools --------------------------------------------
    private external fun inspectDump(path: String): String
    external fun trimXci(path: String): Long
    private external fun convertXciToNsp(source: String, destination: String): String
    external fun getRomCompressionNote(): String

    data class DumpInfo(
        val filename: String,
        val format: String,
        val health: String,
        val sizeBytes: Long,
        val validEnd: Long,
        val reclaimable: Long,
        val summary: String,
        val advice: String
    ) {
        val isHealthy: Boolean get() = health == "Good" || health == "Trimmed"
        val canTrim: Boolean get() = health == "Has padding"
    }

    fun inspect(path: String): DumpInfo? {
        val raw = runCatching { inspectDump(path) }.getOrDefault("")
        if (raw.isEmpty()) return null
        val f = raw.split(FIELD_SEP)
        if (f.size < 8) return null
        return DumpInfo(
            f[0], f[1], f[2],
            f[3].toLongOrNull() ?: 0L,
            f[4].toLongOrNull() ?: 0L,
            f[5].toLongOrNull() ?: 0L,
            f[6], f[7]
        )
    }

    /** Returns bytes written, or 0 with the reason in [errorOut]. */
    fun xciToNsp(source: String, destination: String): Pair<Long, String> {
        val raw = runCatching { convertXciToNsp(source, destination) }.getOrDefault("0|failed")
        val parts = raw.split('|', limit = 2)
        return Pair(parts.getOrElse(0) { "0" }.toLongOrNull() ?: 0L, parts.getOrElse(1) { "" })
    }

    private external fun decompressDump(source: String, destination: String): String

    /**
     * Куда распаковке класть временные части (кэш приложения).
     *
     * Без этого вызова нативный код зовёт std::filesystem::temp_directory_path(),
     * который на Android бросает исключение: TMPDIR не выставлен, а /tmp нет.
     * Исключение в JNI - это abort, то есть падение приложения на ровном месте.
     */
    external fun setRomWorkDir(dir: String)

    fun decompress(source: String, destination: String): Pair<Long, String> {
        val raw = runCatching { decompressDump(source, destination) }.getOrDefault("0|failed")
        val parts = raw.split('|', limit = 2)
        return Pair(parts.getOrElse(0) { "0" }.toLongOrNull() ?: 0L, parts.getOrElse(1) { "" })
    }

    // --- Utilities: save vault -------------------------------------------
    external fun configureVault(dir: String, keep: Int)
    external fun backupSaves(saveDir: String, titleId: String, label: String): Long
    private external fun listBackups(): String
    private external fun restoreBackup(path: String, saveDir: String): String
    external fun getVaultStatus(): String

    data class Backup(
        val path: String,
        val titleId: String,
        val label: String,
        val sizeBytes: Long,
        val timestamp: Long,
        val fileCount: Int
    )

    fun backups(): List<Backup> {
        val raw = safeCall { listBackups() }
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val f = record.split(FIELD_SEP)
            if (f.size < 6) return@mapNotNull null
            Backup(
                f[0], f[1], f[2],
                f[3].toLongOrNull() ?: 0L,
                f[4].toLongOrNull() ?: 0L,
                f[5].toIntOrNull() ?: 0
            )
        }
    }

    /** Empty string means success. */
    fun restore(backup: Backup, saveDir: String): String =
        runCatching { restoreBackup(backup.path, saveDir) }.getOrElse { it.message ?: "failed" }

    // --- Utilities: crash analyst ----------------------------------------
    external fun analyseCrash(): String
    external fun crashFindingCount(): Int

    // --- Mali tuning -----------------------------------------------------
    /** Human-readable Mali report, empty when this is not a Mali GPU. */
    external fun getMaliReport(): String
    private external fun getDriverSuggestions(): String
    external fun getFirmwareAdvice(): String

    data class DriverSuggestion(
        val name: String,
        val description: String,
        val url: String,
        val isSystem: Boolean,
        val verdict: String
    )

    fun driverSuggestions(): List<DriverSuggestion> {
        val raw = safeCall { getDriverSuggestions() }
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val f = record.split(FIELD_SEP)
            if (f.size < 5) return@mapNotNull null
            DriverSuggestion(f[0], f[1], f[2], f[3] == "1", f[4])
        }
    }

    // --- Catalogue -------------------------------------------------------
    private external fun getHomebrew(): String
    private external fun getCompatList(): String
    private external fun lookupCompat(title: String): String

    data class Homebrew(
        val name: String,
        val author: String,
        val description: String,
        val url: String,
        val license: String,
        val isTestTool: Boolean,
        val usesGpu: Boolean
    )

    data class Compat(
        val title: String,
        val rating: String,
        val recommendedMode: Int,
        val note: String,
        val memoryHeavy: Boolean
    )

    fun homebrew(): List<Homebrew> {
        val raw = safeCall { getHomebrew() }
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val f = record.split(FIELD_SEP)
            if (f.size < 7) return@mapNotNull null
            Homebrew(f[0], f[1], f[2], f[3], f[4], f[5] == "1", f[6] == "1")
        }
    }

    fun compatList(): List<Compat> {
        val raw = safeCall { getCompatList() }
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val f = record.split(FIELD_SEP)
            if (f.size < 5) return@mapNotNull null
            Compat(f[0], f[1], f[2].toIntOrNull() ?: 1, f[3], f[4] == "1")
        }
    }

    /** Advice for one title, or null when it is not in the database. */
    fun compatFor(title: String): Compat? {
        val raw = runCatching { lookupCompat(title) }.getOrDefault("")
        if (raw.isEmpty()) return null
        val f = raw.split(FIELD_SEP)
        if (f.size < 5) return null
        return Compat(f[0], f[1], f[2].toIntOrNull() ?: 1, f[3], f[4] == "1")
    }

    // --- Auto modes ------------------------------------------------------
    private external fun getAutoModes(): String
    external fun applyAutoMode(mode: Int): Int
    external fun getCurrentAutoMode(): Int

    data class AutoModeInfo(
        val key: String,
        val displayName: String,
        val summary: String,
        val detail: String,
        val tempCeiling: Int,
        val enumValue: Int,
        val tweaks: List<Tweak>
    )

    fun autoModes(): List<AutoModeInfo> {
        val raw = safeCall { getAutoModes() }
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val f = record.split(FIELD_SEP)
            if (f.size < 7) return@mapNotNull null
            val count = f[6].toIntOrNull() ?: 0
            val tweaks = ArrayList<Tweak>(count)
            for (i in 0 until count) {
                val base = 7 + i * 3
                if (base + 2 >= f.size) break
                tweaks.add(Tweak(f[base], f[base + 1], f[base + 2]))
            }
            AutoModeInfo(
                key = f[0],
                displayName = f[1],
                summary = f[2],
                detail = f[3],
                tempCeiling = f[4].toIntOrNull() ?: 0,
                enumValue = f[5].toIntOrNull() ?: 1,
                tweaks = tweaks
            )
        }
    }

    // --- Launchers -------------------------------------------------------
    private external fun getLaunchers(): String
    external fun getActiveLauncher(): Int
    external fun setActiveLauncher(index: Int): Int

    data class Launcher(
        val key: String,
        val displayName: String,
        val description: String,
        val performanceNote: String,
        val accentArgb: Int,
        val backgroundArgb: Int,
        val cardRadiusDp: Int,
        val gridColumns: Int,
        val wideCards: Boolean,
        val virtualWidth: Int,
        val virtualHeight: Int,
        val colorLevels: Int,
        val totalColors: Long
    ) {
        val isRetro: Boolean get() = virtualWidth > 0 || colorLevels > 0
        val isCustom: Boolean get() = key == "custom"
    }

    fun launchers(): List<Launcher> {
        val raw = safeCall { getLaunchers() }
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val f = record.split(FIELD_SEP)
            if (f.size < 13) return@mapNotNull null
            Launcher(
                key = f[0],
                displayName = f[1],
                description = f[2],
                performanceNote = f[3],
                // Values cross JNI as unsigned decimal strings; parse wide then
                // narrow so 0xFF... does not overflow a signed Int.
                accentArgb = (f[4].toLongOrNull() ?: 0L).toInt(),
                backgroundArgb = (f[5].toLongOrNull() ?: 0L).toInt(),
                cardRadiusDp = f[6].toIntOrNull() ?: 16,
                gridColumns = f[7].toIntOrNull() ?: 2,
                wideCards = f[8] == "1",
                virtualWidth = f[9].toIntOrNull() ?: 0,
                virtualHeight = f[10].toIntOrNull() ?: 0,
                colorLevels = f[11].toIntOrNull() ?: 0,
                totalColors = f[12].toLongOrNull() ?: 0L
            )
        }
    }

    // --- Settings audit ---------------------------------------------------
    // Answers "did this setting actually do anything", which the settings
    // screen cannot: several settings are read by the renderer and then
    // discarded behind a hardware check the UI knows nothing about.
    external fun getSettingsAudit(): String
    private external fun getAuditEntries(): String
    private external fun getAuditSummary(): String
    external fun auditHasData(): Boolean
    external fun auditAutoFix(): Int
    external fun previewAuditAutoFix(): String

    enum class AuditVerdict { Applied, Substituted, Ignored, Unsupported, Unknown }
    enum class AuditRemedy { None, Suggest, AutoFix }

    data class AuditEntry(
        val key: String,
        val requested: String,
        val effective: String,
        val verdict: AuditVerdict,
        val remedy: AuditRemedy,
        val reason: String,
        val evidence: String,
        val suggestedValue: String
    )

    data class AuditSummary(
        val applied: Int,
        val substituted: Int,
        val ignored: Int,
        val unsupported: Int,
        val fixable: Int
    ) {
        /// True when something is worth the user's attention.
        val hasProblems: Boolean get() = substituted + ignored + unsupported > 0
    }

    fun auditEntries(): List<AuditEntry> =
        runCatching {
            val raw = getAuditEntries()
            if (raw.isEmpty()) return emptyList()
            raw.split(RECORD_SEP).mapNotNull { record ->
                val f = record.split(FIELD_SEP)
                if (f.size < 8) return@mapNotNull null
                AuditEntry(
                    key = f[0],
                    requested = f[1],
                    effective = f[2],
                    verdict = AuditVerdict.entries.getOrElse(f[3].toIntOrNull() ?: 4) {
                        AuditVerdict.Unknown
                    },
                    remedy = AuditRemedy.entries.getOrElse(f[4].toIntOrNull() ?: 0) {
                        AuditRemedy.None
                    },
                    reason = f[5],
                    evidence = f[6],
                    suggestedValue = f[7]
                )
            }
        }.getOrElse { emptyList() }

    fun auditSummary(): AuditSummary =
        runCatching {
            val f = getAuditSummary().split(FIELD_SEP)
            AuditSummary(
                applied = f.getOrElse(0) { "0" }.toIntOrNull() ?: 0,
                substituted = f.getOrElse(1) { "0" }.toIntOrNull() ?: 0,
                ignored = f.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
                unsupported = f.getOrElse(3) { "0" }.toIntOrNull() ?: 0,
                fixable = f.getOrElse(4) { "0" }.toIntOrNull() ?: 0
            )
        }.getOrElse { AuditSummary(0, 0, 0, 0, 0) }

    // --- Logs ------------------------------------------------------------
    private external fun getLogDump(area: Int, allAreas: Boolean, minLevel: Int): String
    external fun clearLog()
    external fun getLogCount(): Int
    private external fun getLogProblemCounts(): String

    enum class LogArea { Driver, Memory, Thermal, Profile, Render, Device, General }
    enum class LogLevel { Debug, Info, Warning, Error }

    fun logDump(area: LogArea?, minLevel: LogLevel = LogLevel.Debug): String =
        runCatching {
            getLogDump(area?.ordinal ?: 0, area == null, minLevel.ordinal)
        }.getOrElse { "Log unavailable: ${it.message}" }

    fun logProblemCounts(): IntArray =
        runCatching {
            getLogProblemCounts().split('|').map { it.toIntOrNull() ?: 0 }.toIntArray()
        }.getOrElse { IntArray(LogArea.entries.size) }

    // --- Thermal policy --------------------------------------------------
    private external fun getThermalAdvice(): String

    data class ThermalAdvice(
        val shouldWarn: Boolean,
        val overCeiling: Boolean,
        val restMinutes: Int,
        val title: String,
        val body: String
    )

    fun thermalAdvice(): ThermalAdvice =
        runCatching {
            val p = getThermalAdvice().split('|')
            ThermalAdvice(
                shouldWarn = p.getOrElse(0) { "0" } == "1",
                overCeiling = p.getOrElse(1) { "0" } == "1",
                restMinutes = p.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
                title = p.getOrElse(3) { "" },
                body = p.getOrElse(4) { "" }
            )
        }.getOrElse { ThermalAdvice(false, false, 0, "", "") }

    fun profilesForDevice(): List<Profile> = decode(safeCall { getProfilesForDevice() })

    fun allProfiles(): List<Profile> = decode(safeCall { getAllProfiles() })

    private inline fun safeCall(block: () -> String): String =
        runCatching(block).getOrDefault("")

    private fun decode(raw: String): List<Profile> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val f = record.split(FIELD_SEP)
            // id, name, summary, effect, stockFlag, tweakCount, then 3 fields each
            if (f.size < 6) return@mapNotNull null
            val count = f[5].toIntOrNull() ?: 0
            val tweaks = ArrayList<Tweak>(count)
            for (i in 0 until count) {
                val base = 6 + i * 3
                if (base + 2 >= f.size) break
                tweaks.add(Tweak(f[base], f[base + 1], f[base + 2]))
            }
            Profile(
                id = f[0],
                displayName = f[1],
                summary = f[2],
                expectedEffect = f[3],
                worksOnStockDriver = f[4] == "1",
                tweaks = tweaks
            )
        }
    }
}
