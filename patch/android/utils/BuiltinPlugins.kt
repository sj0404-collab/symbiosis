// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Встроенные плагины — обычный JSON, тот же формат, что у загружаемых.
 *
 * Почему строками в коде, а не файлами в assets: в assets они попадали бы в
 * APK и обновлялись только вместе с ним (то есть не обновлялись), но при
 * этом добавляли бы ещё один путь «файл не скопировался — плагина нет».
 * Строкой их видно целиком, а тест разбирает каждую тем же загрузчиком, что
 * и пользовательские.
 *
 * Что здесь есть и зачем:
 *
 *   * `mali-safe` — включён. Отключает «быстрое время GPU» на Mali: самый
 *     частый источник артефактов на этих драйверах, и первое, что стоит
 *     попробовать, если картинка мигает. Заодно подсказывает, что на 4 ГБ
 *     памяти второму ядру тесно.
 *   * `lowram-eden` — выключен. На устройстве меньше 6 ГБ принудительно
 *     оставляет игры на Eden. Это спорное решение (второе ядро там иногда
 *     всё же играет), поэтому по умолчанию не навязывается.
 *   * `template` — выключен и не срабатывает никогда: образец для тех, кто
 *     захочет написать свой. Скопировать, поменять `id` и условие.
 */
object BuiltinPlugins {

    const val MALI_SAFE = "mali-safe"
    const val LOWRAM_EDEN = "lowram-eden"
    const val TEMPLATE = "template"

    /** Плагины, которые включены сразу после установки. */
    val enabledByDefault: Set<String> = setOf(MALI_SAFE)

    private val MALI_SAFE_JSON = """
    {
      "schema": 1,
      "id": "mali-safe",
      "name": "Mali: безопасная картинка",
      "version": "1.0",
      "author": "symbiosis",
      "priority": 100,
      "description": "На драйверах Mali отключает быстрое время GPU и анизотропию — они дают артефакты и мигание чаще, чем что-либо ещё. Если картинка пошла рябью, сверните этот плагин первым делом.",
      "rules": [
        {
          "type": "setting",
          "when": { "gpu": "*mali*" },
          "set": { "fastGpuTime": "false", "aniso": "0" }
        },
        {
          "type": "banner",
          "when": { "ramGb": { "lt": 6 } },
          "text": "Памяти меньше 6 ГБ: второму ядру может не хватить места рядом с игрой. Если вылетает — запускайте на Eden.",
          "severity": "warn"
        }
      ]
    }
    """

    private val LOWRAM_EDEN_JSON = """
    {
      "schema": 1,
      "id": "lowram-eden",
      "name": "Малышовая память: только Eden",
      "version": "1.0",
      "author": "symbiosis",
      "priority": 50,
      "description": "На устройствах с памятью меньше 6 ГБ всегда запускает игры на Eden: второй процесс проще выгрузить системе посреди игры. Выключен по умолчанию — на некоторых телефонах второе ядро всё же играет.",
      "rules": [
        {
          "type": "core",
          "when": { "ramGb": { "lt": 6 } },
          "core": "eden",
          "reason": "меньше 6 ГБ памяти: второе ядро система может выгрузить посреди игры"
        }
      ]
    }
    """

    /** Список объявлен после строк: в Kotlin значение поля не может ссылаться
     *  на поле, объявленное ниже, — инициализация идёт сверху вниз. */
    val all: List<String> get() = listOf(MALI_SAFE_JSON, LOWRAM_EDEN_JSON, TEMPLATE_JSON)

    private val TEMPLATE_JSON = """
    {
      "schema": 1,
      "id": "template",
      "name": "Образец плагина",
      "version": "1.0",
      "author": "symbiosis",
      "priority": 0,
      "description": "Не срабатывает никогда: условие заведомо ложное. Скопируйте файл, поменяйте id и условие — получится свой хотфикс. Формат: type = setting | core | file | banner, when — условия по title, path, ext, titleId, gpu, engine, ramGb.",
      "rules": [
        {
          "type": "banner",
          "when": { "title": "🎮 образец — это условие никогда не совпадёт" },
          "text": "Если вы видите это сообщение, образец всё-таки сработал.",
          "severity": "info"
        }
      ]
    }
    """
}
