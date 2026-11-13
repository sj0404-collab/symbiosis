// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Fetches and loads the second emulator core at runtime.
 *
 * WHY THE CORE IS NOT IN THE APK
 *   Measured, not guessed. Built in our own CI (kenji-probe.yml) with
 *   `dotnet publish -r linux-bionic-arm64` and weighed:
 *
 *     LibKenjinx.so      54.7 MB raw -> 19.6 MB compressed in an APK
 *     libyuzu-android.so 34.4 MB raw -> 12.3 MB compressed
 *
 *   Both engines in one APK come to about 47 MB. The ceiling is 25 MB, which
 *   fits exactly one engine. So Eden ships inside the APK and Kenji is
 *   downloaded once, on demand, into the app's own data directory.
 *
 * THE PART THAT ALMOST BROKE THIS
 *   Since Android 14 a shared object may only be dlopen()ed if the file is
 *   READ-ONLY. A downloaded .so left writable throws
 *
 *     UnsatisfiedLinkError: Attempt to load writable file
 *
 *   and on targetSdk 37 that is a hard error, not a warning. Eden targets 36
 *   today, so this would currently pass with a logged warning and then break
 *   the day the target is raised. The file is therefore marked read-only
 *   before it is ever loaded - see [markReadOnly].
 *
 *   W^X (Android 10) forbids *exec()* on files in the data directory, not
 *   dlopen() of a library. Loading a downloaded .so is still allowed; running
 *   a downloaded binary is not. This code only ever loads.
 */
object EngineLoader {

    /** Engines this build knows about. Eden is always present. */
    enum class Engine(val id: String, val label: String) {
        EDEN("eden", "Symbiosis"),
        KENJI("kenji", "Второе ядро"),
    }

    /** What has to be true before an engine can be selected. */
    sealed class State {
        /** Compiled into the APK; nothing to do. */
        object Builtin : State()

        /** Downloaded, verified and ready. */
        data class Ready(val path: String, val bytes: Long) : State()

        /** Not here yet. [bytes] is the download size, for an honest prompt. */
        data class Missing(val bytes: Long) : State()

        /** Present but unusable, with the reason spelled out. */
        data class Broken(val reason: String) : State()
    }

    // Where a downloaded core lives. getFilesDir(), not the cache directory:
    // the cache can be cleared by the system at any moment, and a core that
    // vanishes mid-session looks like a crash.
    private fun coreDir(context: Context) =
        File(context.filesDir, "engines").apply { mkdirs() }

    private fun legacyCoreFile(context: Context, engine: Engine) =
        File(coreDir(context), "lib${engine.id}.so")

    fun coreFile(context: Context, engine: Engine): File {
        if (engine == Engine.KENJI && CoreTestUpdates.enabled()) {
            CoreUpdateStore.active(context.filesDir, CoreTestUpdates.key())?.let { return it.file }
        }
        return legacyCoreFile(context, engine)
    }

    /**
     * The core's SHA-256, pinned at build time.
     *
     * Not decoration. The core is fetched over the network into a directory
     * this app can write to, so "the file exists" is not the same as "the file
     * is the one we built". A truncated download produces a library that
     * dlopen() will happily map and then crash inside.
     */
    private val EXPECTED_SHA = mapOf(
        // Of the exact file published as the engine-kenji release asset,
        // verified by downloading it back over the public URL rather than
        // trusting the copy that was uploaded.
        Engine.KENJI to "969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8",
    )

    fun state(context: Context, engine: Engine): State {
        if (engine == Engine.EDEN) return State.Builtin

        if (CoreTestUpdates.enabled()) {
            val key = CoreTestUpdates.key()
            if (CoreUpdateStore.hasSignedVersion(context.filesDir, key)) {
                val active = CoreUpdateStore.active(context.filesDir, key)
                    ?: return State.Broken("подписанная версия отсутствует или повреждена; восстановите её через обновление")
                return State.Ready(active.file.absolutePath, active.file.length())
            }
        }
        val file = legacyCoreFile(context, engine)
        if (!file.exists()) return State.Missing(KNOWN_SIZE[engine] ?: 0L)
        if (file.length() < 1_000_000L) {
            return State.Broken("файл ядра обрезан (${file.length()} Б)")
        }

        val want = EXPECTED_SHA[engine].orEmpty()
        if (want.isNotEmpty()) {
            val got = sha256(file)
            if (!got.equals(want, ignoreCase = true)) {
                return State.Broken("контрольная сумма не совпала")
            }
        }
        return State.Ready(file.absolutePath, file.length())
    }

    /** Download sizes, so the prompt can say what it will cost before asking. */
    val KNOWN_SIZE = mapOf(
        Engine.KENJI to 57_321_040L,
    )

    /**
     * Load a downloaded core.
     *
     * Returns null on success, or a message fit to show a person - never a
     * stack trace, and never a silent false.
     */
    fun load(context: Context, engine: Engine): String? {
        if (engine == Engine.EDEN) return null   // already in the APK

        when (val s = state(context, engine)) {
            is State.Missing -> return "ядро ${engine.label} не скачано"
            is State.Broken  -> return "ядро ${engine.label}: ${s.reason}"
            else -> Unit
        }

        val file = coreFile(context, engine)

        // Read-only BEFORE the load, or Android 14+ refuses it outright.
        val ro = markReadOnly(file)
        if (!ro) {
            return "не удалось сделать файл ядра доступным только для чтения; " +
                "Android откажется его загрузить"
        }

        return try {
            System.load(file.absolutePath)
            null
        } catch (e: UnsatisfiedLinkError) {
            // The two failures worth telling apart, because the fix differs.
            val msg = e.message.orEmpty()
            when {
                msg.contains("writable", ignoreCase = true) ->
                    "Android отклонил ядро: файл доступен для записи. " +
                        "Удалите и скачайте заново."
                msg.contains("is 32-bit") || msg.contains("64-bit") ->
                    "ядро собрано под другую архитектуру процессора"
                else -> "ядро не загрузилось: $msg"
            }
        } catch (e: Throwable) {
            "ядро не загрузилось: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Clear every write bit, and confirm.
     *
     * setReadOnly() returns false on some filesystems without changing
     * anything, so the result is checked with canWrite() rather than trusted -
     * a library that is still writable fails at load time with a message that
     * points nowhere near the cause.
     */
    fun markReadOnly(file: File): Boolean {
        file.setWritable(false, false)
        file.setReadOnly()
        return !file.canWrite()
    }

    /** Delete a downloaded core. Must undo read-only first, or delete fails. */
    fun remove(context: Context, engine: Engine): Boolean {
        val file = legacyCoreFile(context, engine)
        if (engine == Engine.KENJI && CoreTestUpdates.enabled()) {
            return CoreUpdateStore.remove(context.filesDir, CoreTestUpdates.key(), file)
        }
        if (!file.exists()) return true
        file.setWritable(true, true)
        return file.delete()
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Что известно об этом устройстве.
     *
     * Размер страницы читается из ядра, а не предполагается: с Android 15
     * часть устройств работает со страницами 16 КБ, и на них библиотека,
     * выровненная на 4 КБ, не загружается вообще. По версии системы это не
     * различить — на одном и том же Android 15 бывает и так, и так.
     */
    fun device(): DeviceCompat.Device {
        val page = runCatching {
            val bytes = File("/proc/self/auxv").readBytes()
            DeviceCompat.auxvPageSize(bytes)
        }.getOrNull() ?: 4096L
        return DeviceCompat.Device(
            abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            sdkInt = Build.VERSION.SDK_INT,
            pageSizeBytes = page,
            model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            // Process.is64Bit() есть с Android 8; ниже него 64-битных
            // процессов всё равно не бывает.
            is64Process = if (Build.VERSION.SDK_INT >= 23) {
                android.os.Process.is64Bit()
            } else {
                false
            }
        )
    }

    /**
     * Выравнивание уже скачанного ядра, или null.
     *
     * Читаются только заголовки: файл 57 МБ целиком в память не тянется —
     * достаточно начала, где лежит таблица сегментов.
     */
    fun coreAlign(context: Context, engine: Engine): Long? = runCatching {
        val file = coreFile(context, engine)
        if (!file.isFile) return@runCatching null
        file.inputStream().use { input ->
            val head = ByteArray(1 shl 16)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n <= 0) break
                read += n
            }
            DeviceCompat.elfMaxAlign(if (read == head.size) head else head.copyOf(read))
        }
    }.getOrNull()

    /** Итог проверки совместимости — до скачивания или после. */
    fun verdict(context: Context, engine: Engine): DeviceCompat.Verdict {
        val dev = device()
        if (engine == Engine.EDEN) {
            return DeviceCompat.Verdict(true, "встроено в приложение", DeviceCompat.describe(dev))
        }
        val align = coreAlign(context, engine)
        return if (align == null) {
            DeviceCompat.checkBeforeDownload(dev)
        } else {
            DeviceCompat.checkCore(dev, align)
        }
    }

    /**
     * Whether this device can run the downloaded core at all.
     *
     * Kept as a thin wrapper over [verdict] so there is one rule, not two. A
     * 32-bit device would otherwise download 57 MB and only then be told the
     * library "is 32-bit instead of 64-bit".
     */
    fun deviceSupported(): Boolean =
        DeviceCompat.checkBeforeDownload(device()).supported
}
