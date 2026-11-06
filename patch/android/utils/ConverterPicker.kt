// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Кнопка «Конвертер»: выбрать файл и поставить его в очередь распаковки.
 *
 * Один и тот же поток нужен на двух экранах - в библиотеке и в руководстве, -
 * и раньше он был только внутри WebView-панели. Здесь он собран один раз:
 * адаптеру не надо знать ни про SAF, ни про то, как выглядит отказ.
 *
 * Регистрация результата обязана произойти до того, как фрагмент станет
 * STARTED, иначе ActivityResultRegistry бросит исключение. Поэтому объект
 * создаётся в `onCreate`, а не по первому нажатию.
 */
class ConverterPicker(
    private val fragment: Fragment,
    private val onQueued: (Int) -> Unit,
    private val onFailed: () -> Unit
) {
    private val picker: ActivityResultLauncher<Array<String>> =
        fragment.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val queued = runCatching {
                Converter.enqueue(fragment.requireContext().applicationContext, uris)
            }.isSuccess
            if (queued) onQueued(uris.size) else onFailed()
        }

    /**
     * Разрешение на уведомления спрашивается здесь, а не «где-то в
     * настройках».
     *
     * С Android 13 уведомление без разрешения просто не показывается — и
     * получается ровно то, на что жаловались: «пишет, что идёт распаковка,
     * а в шторке ничего». Спрашивать надо в момент, когда оно впервые нужно,
     * то есть при первой распаковке.
     */
    private val notifications: ActivityResultLauncher<String> =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    /**
     * Выбрать папку и поставить в очередь все сжатые образы из неё.
     *
     * Отдельный результат: у выбора папки и выбора файлов разные контракты,
     * и один launcher на оба не годится.
     */
    private val folderPicker: ActivityResultLauncher<Uri?> =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val context = fragment.requireContext().applicationContext
            // Разрешение на этот каталог живёт до перезапуска процесса; для
            // одной распаковки этого достаточно, а держать его постоянно
            // незачем.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val queued = runCatching { Converter.enqueueFolder(context, uri) }.getOrDefault(0)
            if (queued > 0) onQueued(queued) else onFailed()
        }

    /** Открыть системный выбор файлов. */
    fun pick() {
        ensureNotifications()
        runCatching { picker.launch(arrayOf("*/*")) }.onFailure { onFailed() }
    }

    /** Открыть выбор папки: распаковать всё, что внутри. */
    fun pickFolder() {
        ensureNotifications()
        runCatching { folderPicker.launch(null) }.onFailure { onFailed() }
    }
}
