// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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

    /** Открыть системный выбор файлов. */
    fun pick() {
        runCatching { picker.launch(arrayOf("*/*")) }.onFailure { onFailed() }
    }
}
