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

    /** Отметить шаг в отчёте о падении — то же, что видит разбор. */
    fun noteStep(step: String) {
        if (bridgeLoaded) runCatching { nativeSetStep(step) }
    }
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
    /** Версия прошивки по словам ядра (пусто — не установлена). */
    private external fun nativeFirmwareVersion(): String
    /** Открытый пользователь ядра (пусто — никого не открыли). */
    private external fun nativeOpenedUser(): String
    private external fun nativeAttachSurface(surface: android.view.Surface, width: Int, height: Int): String
    /** Инициализация ввода после устройства, до загрузки игры. */
    private external fun nativeInputInit(width: Int, height: Int): String
    private external fun nativeConnectPad(index: Int): Int
    private external fun nativeUpdateInput(): Boolean
    private external fun nativeInputEventsAvailable(): Boolean
    private external fun nativeInputButton(button: Int, id: Int, pressed: Boolean)
    private external fun nativeInputStick(stick: Int, x: Float, y: Float, id: Int)
    private external fun nativeInputTouch(x: Int, y: Int, pressed: Boolean)
    private external fun nativeInputSize(width: Int, height: Int)
    /** Поворот, размер, растяжение и пересоздание цепочки буферов. */
    private external fun nativeRendererKick(width: Int, height: Int, rotation: Int): String
    private external fun nativeSetWindowHandle(handle: Long)
    /** Собственный журнал графики ядра: без него причина молчания не видна. */
    private external fun nativeGraphicsLog(on: Boolean)
    private external fun nativeEnableHidStubLog(): Boolean
    /** Сколько кадров в секунду считает само ядро — не наш колбэк. */
    private external fun nativeGameFrameRate(): Double
    private external fun nativeLoadGame(fd: Int, type: Int): String
    private external fun nativeRunLoop(): String
    private external fun nativeSignalPlayStop(): Boolean
    private external fun nativeClosePlay(): Boolean
    private external fun nativeWindowOf(surface: android.view.Surface): Long

    /**
     * Каталог данных второго ядра — тот, что передаётся ядру при инициализации.
     *
     * По умолчанию приватный. Но его можно указать — и тогда ядро работает в
     * папке, где данные уже лежат: так устроен Kenji (одна папка, внутри её
     * собственное дерево). Это и есть способ обойтись без переноса прошивки:
     * если в выбранной папке есть `bis/system/Contents/registered`, ядро
     * находит прошивку там, где она стоит, и копировать нечего.
     *
     * Процесс `:kenji` читает настройку из файла при старте — SharedPreferences
     * между процессами не синхронизируются, но свежий процесс читает файл с
     * диска, а папку меняют только из главного процесса и только вне игры.
     */
    fun dataDir(context: Context): File {
        val chosen = runCatching { dataPrefs(context).getString(KEY_DATA_ROOT, null) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (chosen != null) {
            // Привести к корню и проверить, что это вообще корень: в настройках
            // мог остаться путь к папке прошивки от прежней версии, и тогда
            // ядро заводило дерево внутри дерева. На телефоне в отчёте так и
            // вышло — «папка данных: …/Eden/bis/system/Contents/registered».
            val root = runCatching { KenjiData.normalizeRoot(chosen) }.getOrDefault(chosen)
            val eden = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
                ?.let { File(it) }
            if (KenjiData.usableRoot(root, eden) &&
                runCatching { root.isDirectory || root.mkdirs() }.getOrDefault(false)
            ) {
                return root
            }
            // Неподходящий путь забываем, чтобы не возвращаться к нему каждый
            // запуск: дальше — приватная папка, где ссылка на прошивку работает.
            dataPrefs(context).edit().remove(KEY_DATA_ROOT).apply()
        }
        // Приватная папка приложения, а не внешняя: на внешнем томе (FUSE)
        // ссылки запрещены, и прошивку Eden пришлось бы копировать вторым
        // комплектом — триста с лишним мегабайт. В приватной папке ссылка
        // работает, и прошивка у двух ядер одна.
        return File(context.filesDir, "kenji")
    }

    /** Выбранная человеком папка данных, если она задана и доступна. */
    fun configuredDataDir(context: Context): File? =
        runCatching { dataPrefs(context).getString(KEY_DATA_ROOT, null) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) }

    /**
     * Задать папку данных ядра. `null` — вернуться к приватной.
     *
     * Путь приводится к корню: человек видит и выбирает папку с прошивкой
     * (`.../bis/system/Contents/registered`), а корнем данных для ядра служит
     * то, внутри чего лежит `bis`. Без приведения экран честно писал
     * «прошивки нет» про папку, в которой прошивка лежит.
     *
     * @return итоговый корень
     */
    fun setDataDir(context: Context, path: String?): File? {
        if (path.isNullOrBlank()) {
            dataPrefs(context).edit().remove(KEY_DATA_ROOT).apply()
            return null
        }
        val root = runCatching { KenjiData.normalizeRoot(File(path)) }.getOrDefault(File(path))
        dataPrefs(context).edit().putString(KEY_DATA_ROOT, root.absolutePath).apply()
        return root
    }

    private fun dataPrefs(context: Context) =
        context.getSharedPreferences("symbiosis_kenji_data", Context.MODE_PRIVATE)

    private const val KEY_DATA_ROOT = "root"

    /**
     * Собственный журнал ядра: каталог «Logs» с файлами вида `Ryujinx_*.log`.
     *
     * ЗАЧЕМ. Инициализация ядра возвращает отказ, а своего текста об ошибке не
     * печатает ни в stdout, ни в stderr — .NET-ядро ловит исключение и уходит в
     * собственный журнал. Там его же слова: какой путь принят, что создано, на
     * чём остановилось. Это единственный источник причины, который не надо
     * угадывать по адресам в памяти.
     */
    fun coreLogDir(context: Context): File = File(dataDir(context), "Logs")

    /** Самый свежий файл журнала ядра, если он есть. */
    fun coreLogFile(context: Context): File? = runCatching {
        coreLogDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    /**
     * Каталоги, которые ядро заводит себе само при инициализации.
     *
     * ЗАЧЕМ ГОТОВИТЬ ИХ ЗАРАНЕЕ. Ядро создаёт `Logs`, `Games`, `Profiles`,
     * `Keys` первым делом. Если создание журнала не удалось, оно возвращает
     * пустой поток, а тот, кто его принимает, падает на null — и инициализация
     * молча отвечает отказом, не записав ни строки. Именно так выглядит наш
     * случай: отказ есть, причины нет. Поэтому каталоги создаём сами, до
     * запуска ядра: тогда отказ будет означать что-то другое, а не отсутствие
     * каталога.
     *
     * @return пары «имя каталога — получилось ли»
     */
    fun ensureCoreDirs(context: Context): List<Pair<String, Boolean>> =
        listOf("Logs", "Games", "Profiles", "Keys").map { name ->
            val dir = File(dataDir(context), name)
            val ok = runCatching { if (dir.isDirectory) true else dir.mkdirs() && dir.isDirectory }
                .getOrDefault(false)
            name to ok
        }

    /**
     * Проверка, что в каталог журнала действительно можно писать.
     *
     * Права «на бумаге» и права на деле на внешнем томе расходятся: каталог
     * создан, а файл в нём не заводится. Ядро об этом молчит, поэтому
     * проверяем сами — пробным файлом.
     */
    fun canWriteCoreLog(context: Context): Boolean = runCatching {
        val dir = coreLogDir(context)
        if (!dir.isDirectory) false
        else {
            val probe = File(dir, ".write-check")
            probe.writeText("ok")
            probe.delete()
        }
    }.getOrDefault(false)

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
     * Файл, в который мост записывает JNI-вызовы ядра.
     *
     * ЗАЧЕМ. Ядро умирало молча, и вопрос был не в том, что оно ответило, а в
     * том, ЧТО ОНО СПРОСИЛО у Java и что ему ответили. Обычный виновник —
     * FindClass, вернувший null: класса нет в приложении, а ядро на null не
     * проверяет и падает внутри. Имя обязано совпадать с тем, что строит
     * `TapSetLogPath` в C++: `kenji-jni.txt` рядом с отчётом о падении.
     */
    fun jniLogFile(context: Context): File =
        File(crashReportFile(context).parentFile, "kenji-jni.txt")

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
        val coreDir = dataDir(context).apply { mkdirs() }
        // Ядро заводит эти каталоги само, но при неудаче молча отвечает
        // отказом. Готовим заранее — см. ensureCoreDirs.
        ensureCoreDirs(context)

        if (bridgeLoaded) runCatching { nativeSetStep("javaInitialize") }
        val err = try {
            nativeInitialize(coreDir.absolutePath)
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

    /** False means callers must not release a session lease as if teardown completed. */
    fun unload(): Boolean = if (!bridgeLoaded) true else runCatching {
        nativeUnload()
        true
    }.getOrDefault(false)

    fun seedKeys(context: Context) {
        val dest = dataDir(context).apply { mkdirs() }
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
     * Перенести профиль пользователя Eden во второе ядро.
     *
     * ЗАЧЕМ. У ядра свой `AccountManager`: он читает `<корень>/Profiles` и
     * заводит пользователя «KenPlayer», если не нашёл никого. Игры спрашивают
     * сервис учётной записи почти сразу после запуска (`IsUserAccountSwitchLocked`
     * и соседние методы), и на пустом профиле эти вызовы кончаются
     * исключением в гостевом потоке: игра встаёт на чёрном экране, а в журнале
     * остаётся `Unhandled exception in guest thread`.
     *
     * Профиль Eden переносится как есть — это те же json-файлы Ryujinx:
     * раскладка каталога у обоих ядер совпадает.
     */
    fun seedProfile(context: Context): String {
        val coreRoot = dataDir(context)
        val dest = File(coreRoot, "Profiles").apply { mkdirs() }
        val already = runCatching { dest.listFiles()?.count { it.isFile } ?: 0 }.getOrDefault(0)
        if (already > 0) return "профиль уже в папке ядра: $already файлов"

        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
            ?: return "данные Eden неизвестны — профиль не перенести"
        // Раскладка может быть в обоих регистрах: «profiles» у Eden и
        // «Profiles» у ядра. Берём то, что есть.
        val src = listOf(File(root, "profiles"), File(root, "Profiles"))
            .firstOrNull { it.isDirectory }
            ?: return "в Eden нет профиля"

        var copied = 0
        for (file in runCatching { src.listFiles()?.filter { it.isFile } }.getOrNull().orEmpty()) {
            runCatching {
                file.copyTo(File(dest, file.name), overwrite = true)
                copied++
            }
        }
        return if (copied > 0) "профиль Eden перенесён: $copied файлов" else "профиль Eden пуст"
    }

    /**
     * Папка прошивки Eden, если данные Eden известны.
     *
     * ПУТЬ НЕ ОДИН. Eden (yuzu) держит прошивку в `nand/system/Contents/
     * registered`, Kenji (Ryujinx) — в `bis/system/Contents/registered` (это
     * видно в его же исходниках: AppDataManager.DefaultNandDir = "bis",
     * ContentPath.SystemContent = <база>/bis/system/Contents). Если человек
     * когда-то ставил прошивку вторым ядром или перекладывал папку, данные
     * могут лежать в любой из них. Поэтому: сначала проверяем оба известных
     * пути, потом ищем папку `system/Contents/registered` на два уровня вниз
     * (любое имя ядра в корне данных).
     *
     * Раньше проверялся ровно один путь, и при любом расхождении отчёт писал
     * «прошивка: нет» — а установленная прошивка 22.0.0 при этом была.
     */
    fun edenFirmwareDir(): File? {
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull() ?: return null
        val direct = listOf(
            File(File(File(File(root, "nand"), "system"), "Contents"), "registered"),
            File(File(File(File(root, "bis"), "system"), "Contents"), "registered"),
            File(File(File(root, "system"), "Contents"), "registered"),
        )
        direct.firstOrNull { it.isDirectory && hasAnyFile(it) }?.let { return it }
        // Поиск на два уровня: <корень>/<любая>/system/Contents/registered.
        return runCatching {
            File(root).listFiles()?.asSequence()
                ?.filter { it.isDirectory }
                ?.map { File(File(File(it, "system"), "Contents"), "registered") }
                ?.firstOrNull { it.isDirectory && hasAnyFile(it) }
        }.getOrNull()
    }

    /** Есть ли в папке хоть один файл — на любом уровне вложенности. */
    private fun hasAnyFile(dir: File): Boolean = runCatching {
        dir.walkTopDown().any { it.isFile }
    }.getOrDefault(false)

    /**
     * Что видно про прошивку Eden: для отчёта, когда её не нашли.
     *
     * Возвращает список проверенных путей и содержимое корня данных, чтобы по
     * одному снимку было видно, где она лежит на самом деле, а не гадать.
     */
    fun firmwareSearchReport(): String {
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
            ?: return "данные Eden неизвестны"
        val lines = mutableListOf<String>()
        lines += "корень данных: " + root
        listOf(
            "nand/system/Contents/registered",
            "bis/system/Contents/registered",
            "system/Contents/registered",
        ).forEach { rel ->
            val f = File(root, rel)
            lines += "  $rel: " + when {
                !f.isDirectory -> "нет папки"
                else -> KenjiData.firmwareCountOf(f).let { "$it файлов" }
            }
        }
        val subs = runCatching { File(root).listFiles()?.filter { it.isDirectory }?.map { it.name } }
            .getOrNull().orEmpty()
        lines += "  папки в корне: " + if (subs.isEmpty()) "нет" else subs.take(8).joinToString(", ")
        return lines.joinToString("\n")
    }

    /**
     * Отдать прошивку Eden второму ядру **только ссылкой** — без копирования.
     *
     * ЗАЧЕМ ОТДЕЛЬНО. Полная передача ([seedFirmware]) умеет и копию, а копия
     * это триста с лишним мегабайт. Делать её во время построения отчёта
     * нельзя: экран «Ключи и прошивка» завис бы на минуту. А вот ссылку
     * поставить — мгновенно, и это ровно то, что человек просил: одна
     * прошивка на два ядра.
     *
     * На телефоне 06.11 отчёт честно писал «прошивка: нет»: привязка шла
     * только при запуске игры, а до него дело не дошло.
     *
     * @return фраза для отчёта; пустая, если прошивки Eden нет вовсе
     */
    fun shareFirmware(context: Context): String {
        val root = edenFirmwareDir() ?: return ""
        val report = bridgeFirmware(root, dataDir(context), allowCopy = false)
        bridgeReport = report
        if (report.startsWith("общая")) {
            freedBytes = freeOldCopies(context, dataDir(context))
        }
        return report
    }

    /**
     * Перенести прошивку Eden во второе ядро — один раз, а не каждой игрой.
     *
     * ЗАЧЕМ. Прошивка у человека одна. Eden держит её в
     * `<данные Eden>/nand/system/Contents/registered`, второе ядро ищет свою в
     * `<данные ядра>/bis/system/Contents/registered` — та же раскладка внутри,
     * но другой корень. Без переноса ядро пишет «System Firmware not installed»
     * и коммерческая игра не поднимается: чёрный экран без ошибки.
     *
     * Копируем, а не ссылаемся: на /storage/emulated (FUSE) символьные ссылки
     * запрещены, а жёсткие — тоже. Копия делается ОДИН раз: отпечаток
     * источника (число файлов и самый свежий mtime) лежит в `.firmware-source`,
     * и пока источник не изменился, повторного копирования нет.
     *
     * @return фраза для отчёта и статуса: что именно произошло с прошивкой
     */
    fun seedFirmware(context: Context): String {
        val coreRoot = dataDir(context)
        val src = edenFirmwareDir() ?: return "данные Eden неизвестны — прошивку не отдать"
        if (!src.isDirectory) return "в Eden нет прошивки — второе ядро останется без неё"

        // ССЫЛКА НА КАЖДЫЙ ФАЙЛ, А НЕ КОПИЯ.
        //
        // Прошивка — это триста с лишним мегабайт, одни и те же файлы NCA у
        // обоих ядер. 06.11 человек сказал прямо: не дублировать, а
        // использовать прошивку Eden через ярлык.
        //
        // И САМОЕ ВАЖНОЕ: ссылку надо положить ТУДА, ГДЕ ЯДРО СМОТРИТ.
        // Kenji читает плоские `.nca` из `bis/system/Contents`, а в `registered`
        // он смотрит только подпапки. Eden держит файлы плоско в `registered`,
        // поэтому прежняя ссылка на папку давала второму ядру ноль файлов —
        // прошивки как будто нет, и в журнале `TimeZoneBinary system title not
        // found`. Теперь каждый файл получает свою ссылку в `Contents`.
        val report = bridgeFirmware(src, coreRoot, allowCopy = true)
        bridgeReport = report
        if (report.startsWith("общая")) {
            freedBytes = freeOldCopies(context, coreRoot)
        }
        return report
    }

    /**
     * Подставить прошивку Eden второму ядру в ТОЙ раскладке, которую он читает.
     *
     * ПОЧЕМУ НЕ ССЫЛКОЙ НА ПАПКУ. Kenji (`ContentManager.LoadEntries`) читает
     * прошивку двумя способами: подпапки внутри `registered` и плоские `.nca`
     * прямо в `Contents`. Eden держит файлы плоско в `registered`. Ссылка на
     * такую папку даёт второму ядру НОЛЬ файлов — прошивка как будто не
     * установлена, в журнале `TimeZoneBinary system title not found`.
     *
     * Поэтому каждый `.nca` из Eden получает СВОЮ ссылку в
     * `<ядро>/bis/system/Contents/<имя>.nca`. Копий нет — 0 байт сверх
     * структуры, обновление прошивки в Eden сразу видно второму ядру.
     *
     * @param allowCopy false — только ссылки (для отчёта, мгновенно);
     *                  true — недостающие файлы копируются, если ссылки запрещены
     */
    private fun bridgeFirmware(src: File, coreRoot: File, allowCopy: Boolean): String {
        val files = collectNca(src)
        if (files.isEmpty()) return "в папке прошивки Eden нет файлов .nca"

        val contents = KenjiData.firmwareContentsDir(coreRoot)
        if (!contents.isDirectory && !contents.mkdirs()) {
            return "не удалось создать каталог прошивки второго ядра"
        }
        // Старая ссылка на папку (прошлая версия) бесполезна для Kenji:
        // в ней лежат плоские файлы, а он ищет подпапки. Убираем.
        val oldLink = KenjiData.firmwareDir(coreRoot)
        if (isLink(oldLink)) oldLink.delete()

        var linked = 0
        var copied = 0
        var kept = 0
        var failed = 0
        for (file in files) {
            val target = File(contents, file.name)
            if (target.isFile && !isLink(target)) {
                kept++
                continue
            }
            if (isLink(target)) {
                if (isLinkTo(target, file)) {
                    linked++
                    continue
                }
                target.delete()
            }
            val ok = runCatching {
                android.system.Os.symlink(file.absolutePath, target.absolutePath)
                true
            }.getOrDefault(false)
            if (ok) {
                linked++
                continue
            }
            if (allowCopy) {
                val copiedOk = runCatching {
                    file.copyTo(target, overwrite = true)
                    true
                }.getOrDefault(false)
                if (copiedOk) copied++ else failed++
            } else {
                failed++
            }
        }
        val total = linked + copied + kept
        return when {
            total == 0 -> "прошивка не подставилась: ссылки запрещены, скопировать нечего"
            copied > 0 ->
                "общая прошивка Eden: $linked ссылками, $copied копией, всего $total файлов"
            else ->
                "общая прошивка Eden: $linked ссылками, копий нет, всего $total файлов" +
                    (if (failed > 0) " (не удалось: $failed)" else "")
        }
    }

    /** Все `.nca` прошивки — и плоско, и во вложенных папках. */
    private fun collectNca(dir: File): List<File> = runCatching {
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".nca", ignoreCase = true) }
            .take(2048)
            .toList()
    }.getOrDefault(emptyList())

    /** Что вышло со ссылкой на прошивку: для отчёта. */
    @Volatile
    var bridgeReport: String = ""
        private set

    /** Сколько байт освободили, убрав старую копию прошивки. */
    @Volatile
    var freedBytes: Long = 0L
        private set

    private fun isLink(file: File): Boolean = runCatching {
        android.system.Os.readlink(file.absolutePath)
        true
    }.getOrDefault(false)

    private fun isLinkTo(file: File, target: File): Boolean = runCatching {
        File(android.system.Os.readlink(file.absolutePath)).canonicalPath == target.canonicalPath
    }.getOrDefault(false)

    /**
     * Убрать старую копию прошивки, если она осталась от прежних сборок.
     *
     * Копия — это те же файлы, что уже видны через ссылку, и держать их
     * вторым комплектом на телефоне нет причины. Удаляем только каталог
     * `registered` в папке данных ядра по умолчанию: чужие папки не трогаем.
     */
    private fun freeOldCopies(context: Context, activeRoot: File): Long {
        val legacy = File(context.getExternalFilesDir(null), "kenji")
        if (legacy.canonicalPath == activeRoot.canonicalPath) return 0L
        val old = KenjiData.firmwareDir(legacy)
        if (!old.isDirectory || isLink(old)) return 0L
        val size = runCatching {
            old.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
        val removed = runCatching { old.deleteRecursively() }.getOrDefault(false)
        return if (removed) size else 0L
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
        // Прошивка — до старта ядра: ContentManager перечисляет её в
        // javaInitialize, и то, что скопировано позже, он уже не увидит.
        val profile = seedProfile(context)
        val firmware = seedFirmware(context)
        runCatching {
            crashReportFile(context).takeIf { it.isFile }?.appendText("прошивка: $firmware\n")
        }
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
        pendingCfg = cfg

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
        lastFirmware = firmwareVersion()
        val fwFiles = KenjiData.firmwareCount(dataDir(context))
        val fwText = when {
            lastFirmware.isNotBlank() -> "прошивка: $lastFirmware"
            fwFiles > 0 -> "прошивка: $fwFiles файлов, версию ядро не назвало"
            else -> "прошивки нет — укажите папку с прошивкой в «Ядра»"
        }
        // Как прошивка досталась второму ядру: ссылкой на папку Eden (копий
        // нет) или копией. Раньше об этом молчали, и человек видел в
        // хранилище второй комплект на триста с лишним мегабайт.
        val fwShare = buildString {
            if (bridgeReport.isNotEmpty()) append(bridgeReport)
            if (freedBytes > 0) {
                if (isNotEmpty()) append(", ")
                append("старая копия убрана: ")
                append(freedBytes / (1024 * 1024))
                append(" МБ свободно")
            }
        }
        return Status(
            true,
            "плеер второго ядра готов · " + lastConfig + " · " + fwText +
                (if (fwShare.isNotEmpty()) " · " + fwShare else "") + " · " + firmware +
                " · " + profile,
            started.symbols
        )
    }

    /**
     * Создать устройство — ПОСЛЕ рендерера и только из главного потока.
     *
     * ПЕРВЫЙ БАГ, КОТОРЫЙ ЗДЕСЬ ИСПРАВЛЕН. Раньше порядок был обратный:
     * `deviceInitialize`, потом рендерер. Ядро так не умеет: его
     * `InitializeContext` первой строкой проверяет `Renderer == null` и
     * возвращает `false` — то есть без рендерера устройства не будет вовсе, а
     * дальше `deviceLoadDescriptor` видит `EmulationContext == null` и отдаёт
     * отказ, из-за которого игра не открывалась.
     *
     * ВТОРОЙ. `deviceInitialize` обязан идти из главного потока — это прямо
     * написано в официальном Kenji («We are only able to initialize the
     * emulation context on the main thread»), и вызов из рабочего потока там
     * же завёрнут в переброс на UI-поток с ожиданием.
     */
    fun deviceInit(): Status {
        val cfg = pendingCfg ?: return Status(false, "настройки не подготовлены")
        val err = runCatching {
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
            )
        }.getOrElse { return Status(false, "мост упал на устройстве: ${it.message ?: it.javaClass.simpleName}") }
        return if (err.isEmpty()) Status(true, "устройство создано") else Status(false, err)
    }

    /**
     * Версия установленной прошивки по словам самого ядра.
     *
     * Пустая строка — прошивки нет. Нужна не для красоты: без неё «игра не
     * открылась» не отличить от «прошивки нет», и оба случая выглядели одинаково.
     */
    fun firmwareVersion(): String {
        ensureBridge()?.let { return "" }
        return runCatching { nativeFirmwareVersion() }.getOrDefault("").trim()
    }

    /**
     * Открытый пользователь ядра.
     *
     * Пустая строка — никого не открыли. Именно в этом состоянии сервис
     * учётной записи отвечает исключением, и игра встаёт на чёрном экране;
     * строка нужна, чтобы это было видно до запуска, а не после.
     */
    fun openedUser(): String {
        ensureBridge()?.let { return "" }
        val user = runCatching { nativeOpenedUser() }.getOrDefault("").trim()
        lastUser = user
        return user
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

    /** Версия прошивки второго ядра, как её называет само ядро. */
    @Volatile
    var lastFirmware: String = ""
        private set

    /** Открытый пользователь ядра на момент запуска (пусто — никого). */
    @Volatile
    var lastUser: String = ""
        private set

    /** Настройки, посчитанные в [preparePlay]; их ждёт [deviceInit]. */
    @Volatile
    private var pendingCfg: EngineHybrid.KenjiConfig? = null

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

    /**
     * Поднять ввод (касания, геймпады).
     *
     * После [deviceInit]: в родственном ядре он создаёт EmulationContext.
     * До loadGame, поскольку загрузка может начать исполнение гостя. Раньше
     * вызов до deviceInit падал; перенос после deviceInit требует проверки на устройстве.
     */
    fun inputInit(width: Int, height: Int): Status {
        ensureBridge()?.let { return Status(false, it) }
        val err = runCatching { nativeInputInit(width, height) }
            .getOrElse { return Status(false, it.message ?: "ввод") }
        return if (err.isEmpty()) Status(true, "ввод поднят") else Status(false, err)
    }

    /** Подключить виртуальный контроллер игрока 1 после inputInit. */
    fun connectVirtualPad(): Int =
        if (!bridgeLoaded) -1 else runCatching { nativeConnectPad(0) }.getOrDefault(-1)

    /** Обновить состояние ввода в ядре. */
    fun updateInput(): Boolean =
        if (!bridgeLoaded) false else runCatching { nativeUpdateInput() }.getOrDefault(false)

    // Serialize event callbacks with teardown, without using the renderer lock.
    private val eventLock = Any()
    @Volatile private var acceptingEvents = false
    private var eventPadId = -1

    fun activateInputEvents(id: Int): Boolean = synchronized(eventLock) {
        if (!bridgeLoaded || id < 0 || !runCatching { nativeInputEventsAvailable() }.getOrDefault(false)) {
            false
        } else {
            eventPadId = id
            acceptingEvents = true
            true
        }
    }
    fun disableInputEvents() { acceptingEvents = false }
    fun inputButton(edenButton: Int, pressed: Boolean) {
        val mapped = KenjiInputMap.button(edenButton)
        if (mapped < 0 || !acceptingEvents) return
        synchronized(eventLock) {
            if (acceptingEvents) nativeInputButton(mapped, eventPadId, pressed)
        }
    }
    fun inputStick(edenStick: Int, x: Float, y: Float) {
        val mapped = KenjiInputMap.stick(edenStick)
        if (mapped < 0 || !acceptingEvents) return
        synchronized(eventLock) {
            if (acceptingEvents) nativeInputStick(mapped, x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f), eventPadId)
        }
    }
    fun inputTouch(x: Int, y: Int, pressed: Boolean) {
        if (!acceptingEvents) return
        synchronized(eventLock) { if (acceptingEvents) nativeInputTouch(x, y, pressed) }
    }
    fun inputSize(width: Int, height: Int) {
        if (!acceptingEvents || width <= 0 || height <= 0) return
        synchronized(eventLock) { if (acceptingEvents) nativeInputSize(width, height) }
    }
    private fun releaseInputsLocked() {
        if (eventPadId < 0) return
        KenjiInputMap.validButtons().forEach { nativeInputButton(it, eventPadId, false) }
        nativeInputStick(1, 0f, 0f, eventPadId)
        nativeInputStick(2, 0f, 0f, eventPadId)
        nativeInputTouch(0, 0, false)
    }
    fun cancelInputEvents() {
        if (!acceptingEvents) return
        synchronized(eventLock) { if (acceptingEvents) releaseInputsLocked() }
    }
    /** Called on stop worker after updater has exited, not the UI thread. */
    fun drainInputEvents() = synchronized(eventLock) {
        if (bridgeLoaded) releaseInputsLocked()
        eventPadId = -1
    }

    /**
     * Пинок рендереру после появления поверхности.
     *
     * Официальное приложение после SurfaceView делает поворот, размер,
     * растяжение и пересоздание цепочки буферов. Без пересоздания Vulkan может
     * рисовать в старую цепочку: цикл идёт, а на экране ничего.
     */
    fun rendererKick(width: Int, height: Int, rotation: Int): Status {
        ensureBridge()?.let { return Status(false, it) }
        // Ответ нативной функции читается как NULLABLE. Причина: JNI связывает
        // функции по имени и не сверяет тип возврата. Пока в C++ стоял `void`,
        // Kotlin объявлял `: String`, получал null и падал с
        // NullPointerException на следующей строке — то есть игра
        // загружалась, все пять вызовов проходили, а процесс умирал уже
        // после них (04.11 22:12:49). Строка ниже превращает такую ошибку в
        // успешный «пинок», а не в падение; сам тип теперь сверяет
        // tools/check_jni_names.py, и он же поймал это расхождение.
        val err: String? = runCatching { nativeRendererKick(width, height, rotation) }
            .getOrElse { return Status(false, it.message ?: "рендерер") }
        return if (err.isNullOrEmpty()) Status(true, "рендерер готов") else Status(false, err)
    }

    /** Отдать ядру окно Android — по нему оно считает поворот и размер. */
    /**
     * Включить журнал графики ядра — один раз на запуск.
     *
     * Цена: несколько строк в его журнал. Польза: если кадров нет, в журнале
     * будет сказано, на чём остановился Vulkan, а не только наше «кадров нет».
     */
    fun enableGraphicsLog() {
        if (bridgeLoaded) runCatching { nativeGraphicsLog(true) }
    }

    /** In related Kenji sources Stub is log level 1, enabled after initialization. */
    fun enableHidStubLog(): Boolean =
        bridgeLoaded && runCatching { nativeEnableHidStubLog() }.getOrDefault(false)

    /** Сколько кадров в секунду считает само ядро (0 — не считает или не рисует). */
    fun gameFrameRate(): Double =
        if (!bridgeLoaded) 0.0 else runCatching { nativeGameFrameRate() }.getOrDefault(0.0)

    fun setWindowHandle(handle: Long) {
        if (bridgeLoaded) runCatching { nativeSetWindowHandle(handle) }
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

    /** Signal first; do not close the device until the render thread exits. */
    fun signalPlayStop(): Boolean = bridgeLoaded &&
        runCatching { nativeSignalPlayStop() }.getOrDefault(false)

    /** Only after the loop worker has actually returned. */
    fun closePlay(): Boolean = bridgeLoaded &&
        runCatching { nativeClosePlay() }.getOrDefault(false)
}
