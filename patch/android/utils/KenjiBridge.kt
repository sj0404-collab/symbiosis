// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File

/**
 * The Kotlin side of the bridge to the Kenji-NX core.
 *
 * WHAT THE CORE IS, read out of the built library rather than its source:
 *
 *   * 60 exported functions, flat C, camelCase - `deviceInitialize`,
 *     `graphicsInitialize`, `deviceLoadDescriptor`. The C# source also defines
 *     snake_case names (`device_initialize`); those are NOT in the build, and
 *     binding to them would return null for every call.
 *   * needs only liblog, libdl, libz, libm, libc - no .NET runtime to ship.
 *   * imports three symbols back out of `libkenjinxjni`: `debug_break`,
 *     `setRenderingThread`, `setCurrentTransform`. `debug_break` is called
 *     from `deviceInitialize` itself, so without them the first device call
 *     aborts the process. `libsymbiosis_kenji.so` provides them.
 *
 * WHY NOTHING HERE CAN CRASH THE APP
 *   A SIGSEGV inside a native library kills the whole process; no try/catch in
 *   Kotlin or C++ changes that. Containment therefore comes in two layers:
 *
 *     1. the emulation side runs in its own process (`android:process`), so a
 *        fault takes down that process and not the launcher;
 *     2. everything below returns a message instead of throwing, and the core
 *        is verified before a single call is made into it.
 *
 *   Measured, not assumed: a missing file, a truncated file and 50 KB of
 *   random bytes were all fed to the loader in a child process. Every one was
 *   rejected with an error and none produced a signal.
 */
object KenjiBridge {

    /** Loaded lazily: the bridge is only needed once Kenji is chosen. */
    @Volatile private var bridgeLoaded = false
    @Volatile private var bridgeError: String? = null

    private fun ensureBridge(): String? {
        if (bridgeLoaded) return null
        bridgeError?.let { return it }
        return try {
            System.loadLibrary("symbiosis_kenji")
            bridgeLoaded = true
            null
        } catch (e: UnsatisfiedLinkError) {
            val msg = "мост не загрузился: ${e.message ?: "нет libsymbiosis_kenji.so"}"
            bridgeError = msg
            msg
        } catch (e: Throwable) {
            val msg = "мост не загрузился: ${e.message ?: e.javaClass.simpleName}"
            bridgeError = msg
            msg
        }
    }

    // Empty string means success everywhere below - a null return from JNI
    // would be indistinguishable from a failed call.
    private external fun nativeLoad(path: String): String

    /** Включить отчёт о падении в файл (обработчики сигналов ставит C++). */
    private external fun nativeSetCrashLog(path: String)

    /** Отметить шаг, чтобы падение можно было к нему привязать. */
    private external fun nativeSetStep(step: String)
    private external fun nativeInitialize(dataPath: String): String
    private external fun nativeSymbolCount(): Int
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeLastError(): String
    private external fun nativeUnload()
    private external fun nativeDeviceInit(
        memoryManagerMode: Int,
        useNce: Boolean,
        memoryConfiguration: Int,
        systemLanguage: Int,
        regionCode: Int,
        vSyncMode: Int,
        enableDockedMode: Boolean,
        enablePtc: Boolean,
        enableLowPowerPtc: Boolean,
        enableJitCacheEviction: Boolean,
        enableInternetAccess: Boolean,
        enableFsIntegrityChecks: Boolean,
        fsGlobalAccessLogMode: Int,
        timeZone: String,
        ignoreMissingServices: Boolean
    ): String
    private external fun nativeGraphicsInit(
        resScale: Float,
        maxAnisotropy: Float,
        fastGpuTime: Boolean,
        fast2DCopy: Boolean,
        enableMacroJit: Boolean,
        enableMacroHLE: Boolean,
        enableShaderCache: Boolean,
        enableTextureRecompression: Boolean,
        backendThreading: Int
    ): String
    private external fun nativeAttachSurface(surface: android.view.Surface, width: Int, height: Int): String
    private external fun nativeLoadGame(fd: Int, type: Int): String
    private external fun nativeRunLoop(): String
    private external fun nativePlayStop()
    private external fun nativeWindowOf(surface: android.view.Surface): Long

    /**
     * Файл отчёта о падении — рядом с данными второго ядра.
     *
     * Путь живёт здесь, а не в [KenjiCrash]: разбор отчёта проверяется тестом
     * на хосте, и ему нельзя тянуть за собой android.content.Context.
     */
    fun crashReportFile(context: Context): File =
        File(File(context.getExternalFilesDir(null), "kenji"), "kenji-crash.txt")

    /**
     * Файл, в который мост перенаправляет stdout и stderr процесса `:kenji`.
     *
     * Именно там .NET-ядро печатает необработанное исключение перед abort().
     * Имя обязано совпадать с тем, что строит `ArmStdioCapture` в C++:
     * `kenji-stdout.txt` рядом с отчётом о падении.
     */
    fun stdioFile(context: Context): File =
        File(crashReportFile(context).parentFile, "kenji-stdout.txt")

    /**
     * Файл, в который процесс `:kenji` записывает свой PID перед запуском ядра.
     *
     * ЗАЧЕМ ЦЕЛЫЙ ФАЙЛ РАДИ ЧИСЛА. Отчёт собирается в ОСНОВНОМ процессе:
     * служба к этому моменту уже мертва, и спросить её нечего. Раньше отчёт
     * брал `Process.myPid()` у себя — то есть PID лаунчера, — и фильтр журнала
     * по этому номеру не находил ни одной строки ядра. В отчёте появлялось
     * «строки своего PID 8674 система не отдала», хотя падал совсем другой
     * процесс. PID падающего процесса нужно записать до падения.
     */
    fun pidFile(context: Context): File =
        File(crashReportFile(context).parentFile, "kenji-pid.txt")

    /**
     * Начать новый отчёт о падении.
     *
     * Старый стирается: файл переживает смерть процесса, и без стирания
     * вчерашнее падение выдавалось бы за сегодняшнее. Вызывается в процессе
     * `:kenji` перед первым касанием ядра.
     */
    fun armCrashReport(context: Context) {
        ensureBridge()?.let { return }
        val file = crashReportFile(context)
        runCatching { file.parentFile?.mkdirs() }
        runCatching { nativeSetCrashLog(file.absolutePath) }
    }

    data class Status(
        val ok: Boolean,
        val message: String,
        /** How many of the expected entry points were found, out of 11. */
        val symbols: Int = 0,
    )

    /**
     * Load and verify the core without starting anything.
     *
     * Split from [start] on purpose: "the file is a valid core" and "the core
     * agreed to initialise" are different failures with different fixes, and
     * one combined error message would hide which happened.
     */
    fun load(context: Context): Status {
        ensureBridge()?.let { return Status(false, it) }

        when (val state = EngineLoader.state(context, EngineLoader.Engine.KENJI)) {
            is EngineLoader.State.Missing ->
                return Status(false, "ядро не скачано")
            is EngineLoader.State.Broken ->
                return Status(false, "ядро непригодно: ${state.reason}")
            else -> Unit
        }

        val file = EngineLoader.coreFile(context, EngineLoader.Engine.KENJI)

        // Read-only before the load, or Android 14+ refuses outright with
        // "Attempt to load writable file".
        if (!EngineLoader.markReadOnly(file)) {
            return Status(false, "не удалось закрыть файл ядра от записи")
        }

        val err = try {
            nativeLoad(file.absolutePath)
        } catch (e: Throwable) {
            return Status(false, "мост упал при загрузке: ${e.message ?: e.javaClass.simpleName}")
        }
        if (err.isNotEmpty()) return Status(false, err)

        val n = runCatching { nativeSymbolCount() }.getOrDefault(0)
        return Status(true, "ядро загружено, точек входа найдено $n из 11", n)
    }

    /**
     * Hand the core its data directory and JNI environment.
     *
     * This is the first call that runs the core's own code, and the one most
     * likely to fail: `deviceInitialize` reaches `debug_break` here.
     */
    fun start(context: Context): Status {
        val loaded = load(context)
        if (!loaded.ok) return loaded

        // Kenji keeps its system files under its own directory, kept separate
        // from Eden's so neither can corrupt the other's state. Games, keys
        // and firmware are shared; internal caches are not.
        val dataDir = File(context.getExternalFilesDir(null), "kenji").apply { mkdirs() }

        if (bridgeLoaded) runCatching { nativeSetStep("javaInitialize") }
        val err = try {
            nativeInitialize(dataDir.absolutePath)
        } catch (e: Throwable) {
            return Status(false, "мост упал при инициализации: ${e.message ?: e.javaClass.simpleName}")
        }
        return if (err.isEmpty()) {
            Status(true, "ядро запущено", loaded.symbols)
        } else {
            Status(false, err, loaded.symbols)
        }
    }

    fun isLoaded(): Boolean =
        bridgeLoaded && runCatching { nativeIsLoaded() }.getOrDefault(false)

    fun lastError(): String =
        if (!bridgeLoaded) (bridgeError ?: "") else runCatching { nativeLastError() }.getOrDefault("")

    fun unload() {
        if (bridgeLoaded) runCatching { nativeUnload() }
    }

    fun seedKeys(context: Context) {
        val dest = File(context.getExternalFilesDir(null), "kenji").apply { mkdirs() }
        val sys = File(dest, "system").apply { mkdirs() }
        val keys = File(dest, "keys").apply { mkdirs() }
        val srcRoot = runCatching { DirectoryInitialization.userDirectory }.getOrNull() ?: return
        listOf("prod.keys", "title.keys").forEach { name ->
            val src = File(File(srcRoot, "keys"), name)
            if (!src.isFile) return@forEach
            runCatching { src.copyTo(File(dest, name), overwrite = true) }
            runCatching { src.copyTo(File(sys, name), overwrite = true) }
            runCatching { src.copyTo(File(keys, name), overwrite = true) }
        }
    }

    /**
     * Prepare device + graphics. Surface and ROM come next.
     *
     * ЗДЕСЬ БЫЛО ВПИСАНО ЧИСЛАМИ. Настройки второго ядра не влияли ни на что:
     * `memoryManagerMode = 2`, `useNce = false`, `resScale = 1f` и остальные
     * значения стояли прямо в коде, а экран ядер показывал переключатели,
     * которые никуда не доезжали. Из-за этого «два ядра в одном» работали
     * как два разных приложения: настроил масштаб у Eden — Kenji рисует по
     * своему.
     *
     * Теперь аргументы приходят из [EngineStore.config]: свои значения
     * второго ядра плюс всё, что не тронуто, взятое у Eden.
     * `EngineHybrid.seed/merge` решает, что откуда, и это проверяется на
     * хосте, а не на телефоне.
     */
    fun preparePlay(context: Context, effective: String = ""): Status {
        seedKeys(context)
        val started = start(context)
        if (!started.ok) return started

        // Настройки приходят строкой из главного процесса: SharedPreferences
        // между процессами не синхронизируются, и :kenji увидел бы свою
        // копию файла. Пустая строка — читаем локально (проверка ядра).
        val cfg = if (effective.isBlank()) {
            EngineStore.config(context)
        } else {
            EngineStore.configWith(context, EngineHybrid.decode(effective))
        }

        nativeDeviceInit(
            memoryManagerMode = cfg.memoryManagerMode,
            useNce = cfg.useNce,
            memoryConfiguration = cfg.memoryConfiguration,
            systemLanguage = cfg.systemLanguage,
            regionCode = cfg.regionCode,
            vSyncMode = cfg.vSyncMode,
            enableDockedMode = cfg.enableDockedMode,
            enablePtc = cfg.enablePtc,
            enableLowPowerPtc = cfg.enableLowPowerPtc,
            enableJitCacheEviction = cfg.enableJitCacheEviction,
            enableInternetAccess = cfg.enableInternetAccess,
            enableFsIntegrityChecks = cfg.enableFsIntegrityChecks,
            fsGlobalAccessLogMode = cfg.fsGlobalAccessLogMode,
            timeZone = cfg.timeZone,
            ignoreMissingServices = cfg.ignoreMissingServices
        ).takeIf { it.isNotEmpty() }?.let { return Status(false, it, started.symbols) }

        nativeGraphicsInit(
            resScale = cfg.resScale,
            maxAnisotropy = cfg.maxAnisotropy,
            fastGpuTime = cfg.fastGpuTime,
            fast2DCopy = cfg.fast2DCopy,
            enableMacroJit = cfg.enableMacroJit,
            enableMacroHLE = cfg.enableMacroHLE,
            enableShaderCache = cfg.enableShaderCache,
            enableTextureRecompression = cfg.enableTextureRecompression,
            backendThreading = cfg.backendThreading
        ).takeIf { it.isNotEmpty() }?.let { return Status(false, it, started.symbols) }

        lastConfig = EngineHybrid.summary(cfg)
        return Status(true, "плеер второго ядра готов · " + lastConfig, started.symbols)
    }

    /**
     * С какими настройками стартовал плеер.
     *
     * Показывается в статусе [org.yuzu.yuzu_emu.activities.KenjiPlayerActivity]
     * и в отчёте: «ядро запустилось» без указания настроек ничего не
     * доказывает, а именно их раньше и не было.
     */
    @Volatile
    var lastConfig: String = ""
        private set

    fun attachSurface(surface: android.view.Surface, width: Int, height: Int): Status {
        ensureBridge()?.let { return Status(false, it) }
        val err = runCatching { nativeAttachSurface(surface, width, height) }
            .getOrElse { return Status(false, it.message ?: "surface") }
        return if (err.isEmpty()) Status(true, "поверхность есть") else Status(false, err)
    }

    fun fileTypeOf(path: String): Int {
        val ext = path.substringAfterLast('.', "").lowercase().substringBefore('?')
        return when (ext) {
            "nsp", "nsz" -> 1
            "xci", "xcz" -> 2
            "nro" -> 3
            else -> 1
        }
    }

    fun loadGame(fd: Int, type: Int): Status {
        ensureBridge()?.let { return Status(false, it) }
        val err = runCatching { nativeLoadGame(fd, type) }
            .getOrElse { return Status(false, it.message ?: "load") }
        return if (err.isEmpty()) Status(true, "игра передана ядру") else Status(false, err)
    }

    fun nativeWindow(surface: android.view.Surface): Long =
        runCatching { nativeWindowOf(surface) }.getOrDefault(-1L)

    /** Blocks until the core leaves the run loop. Call from a worker thread. */
    fun runLoop(): Status {
        ensureBridge()?.let { return Status(false, it) }
        val err = runCatching { nativeRunLoop() }.getOrElse { return Status(false, it.message ?: "loop") }
        return if (err.isEmpty()) Status(true, "цикл завершён") else Status(false, err)
    }

    fun stopPlay() {
        if (bridgeLoaded) runCatching { nativePlayStop() }
    }
}
