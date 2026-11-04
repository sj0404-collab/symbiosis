// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Заглушка журнала — ТОЛЬКО для тестов на хосте.
 *
 * Настоящий Log живёт в приложении и пишет в logcat. Тестам он не нужен, но
 * код, который они проверяют (TorrentDownload), о него опирается. Оставить
 * тест без сборки из-за одной строки логирования — значит не проверить
 * главное: рукопожатие, куски и запись файла.
 *
 * Файл лежит в подкаталоге, поэтому общий цикл по папке tests его не подхватит и
 * не попытается запустить как тест.
 */
object Log {
    fun info(message: String) = Unit
    fun warning(message: String) = Unit
    fun error(message: String) = Unit
    fun debug(message: String) = Unit
}
