// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import android.content.Context
import org.yuzu.yuzu_emu.BuildConfig
import java.io.File
import java.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Signed remote core channel is explicitly test/debug only. */
object CoreTestUpdates {
    private const val PUBLIC_KEY = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAxAzN8s7PB6bNLPzy2iT4oPdyK7a50cwtgw3JzcyAbDxflsk8gilfwnhH1AItUx0oh6G+iMGYUCWkbdaUXblmT/+/SRXGUKgN/s2yFJtyZwyBsXX/G+/AJeTmM7/zlHBOO3Jrcrjb7YDAyPEm9/kDeIeOiH6F3bL94SUrwTvchHwccH4pR/enx5EIpe4XVPHJSIn1p+oxkY+YLJlpSpC1J6ACsunreYZTw9yhAvAHbnJUsV1xclbixLvWLu5gzm3L5ZPb8SG2NEVaHWBCooP/io3wefV5NubMeHHP8feY9lNMTSV7REpRBPnz6i+s8734UxCFUj8Y88KLyWc1GaOJAeNphB/hIg4ehCr+9Af+iNjSxNPS0H9vWJWWmF+/VGHeEfk+wIPx10PwBEISQ/NY0maCEUpXryfhMgBTHdhfsS4n56Bct3p92Uawu7T6CWUO4hLkD+riaWd18Gtpu6yAnXUiBUj/dep1FaLkz6QlBz9oCWGKBrLChiVDnvLMbmSxAgMBAAE="
    fun enabled(): Boolean = BuildConfig.DEBUG
    fun key(): ByteArray = Base64.getDecoder().decode(PUBLIC_KEY)
    data class Result(val ok: Boolean, val message: String)
    private fun logFile(context: Context) = File(context.filesDir, "core-update-events.txt")

    @Synchronized fun note(context: Context, event: String) {
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            logFile(context).appendText("$time · $event\n")
        }
    }
    fun tail(context: Context): String = runCatching {
        val f = logFile(context)
        if (!f.isFile) return@runCatching "Журнал обновлений пока пуст."
        java.io.RandomAccessFile(f, "r").use { r ->
            val size = minOf(r.length(), 32_768).toInt()
            r.seek(r.length() - size)
            val data = ByteArray(size); r.readFully(data)
            data.toString(Charsets.UTF_8)
        }
    }.getOrDefault("Не удалось прочитать журнал обновлений.")

    fun install(context: Context): Result {
        if (!enabled()) return Result(false, "Канал доступен только в тестовой сборке.")
        if (!EngineLoader.deviceSupported()) return Result(false, "Устройство не поддерживает это ARM64-ядро.")
        val page = runCatching { DeviceCompat.auxvPageSize(File("/proc/self/auxv").readBytes()) }.getOrNull()
            ?: return Result(false, "Не удалось определить размер страниц памяти; ядро не установлено.")
        note(context, "Запрошена проверка тестового обновления")
        return try {
            val raw = CoreUpdateNetwork.manifest()
            val publicKey = key()
            val candidate = SignedCoreManifest.verify(raw, publicKey, 0)
                ?: return Result(false, "Подпись описания обновления не прошла проверку.").also {
                    note(context, "Описание отклонено: подпись или формат")
                }
            val current = CoreUpdateStore.active(context.filesDir, publicKey)
            if (current != null && candidate == current.core) {
                note(context, "Уже установлена версия ${candidate.sequence}, SHA-256 ${candidate.sha256}")
                return Result(true, "Версия ${candidate.sequence} уже установлена. Игровая совместимость не проверена.")
            }
            note(context, "Проверено описание версии ${candidate.sequence}, SHA-256 ${candidate.sha256}")
            val outcome = CoreUpdateNetwork.withAsset(candidate) { input ->
                CoreUpdateStore.install(context.filesDir, raw, publicKey, input) { file ->
                    CoreElfCheck.error(file, page)
                }
            }
            when (outcome) {
                is CoreUpdateStore.Outcome.Installed -> {
                    note(context, "Установлена версия ${outcome.sequence}, SHA-256 ${candidate.sha256}; запуск не проверен")
                    Result(true, "Версия ${outcome.sequence} установлена. Нажмите «Проверить»; запуск игры пока не подтверждён.")
                }
                is CoreUpdateStore.Outcome.Rejected -> {
                    note(context, "Обновление отклонено: ${outcome.reason}")
                    Result(false, outcome.reason + ". Предыдущее ядро сохранено.")
                }
            }
        } catch (e: IllegalStateException) {
            val msg = e.message ?: "сервер не ответил"
            note(context, "Обновление не получено: $msg")
            Result(false, "$msg. Предыдущее ядро сохранено.")
        } catch (_: Exception) {
            note(context, "Обновление не получено: ошибка сети или чтения")
            Result(false, "Не удалось получить обновление. Проверьте сеть; предыдущее ядро сохранено.")
        }
    }
}
