// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/**
 * Отчёт о падении второго ядра: разбор файла, который пишет мост.
 *
 * ПОЧЕМУ НЕ «МОСТ УПАЛ»
 *   Когда процесс `:kenji` умирает, Android не отдаёт приложению ни строчки
 *   причины: приходит только `onServiceDisconnected`. Раньше в этот момент
 *   человеку показывалось предположение — и предположение было неверным в
 *   самом важном: в нём утверждалось, что «официальный Kenji на этом
 *   устройстве уже играет». Это утверждение никто не проверял.
 *
 *   Теперь перед каждым вызовом в ядро мост записывает имя шага, а обработчик
 *   сигнала в `libsymbiosis_kenji.so` при падении дописывает номер сигнала,
 *   адрес и кадры стека с именами библиотек. Файл переживает смерть процесса,
 *   поэтому после падения есть факты: на каком шаге, какой сигнал, внутри
 *   какой библиотеки.
 *
 * ЧТО ЗДЕСЬ ПРОВЕРЯЕТСЯ ТЕСТОМ
 *   Разбор чужого текста на хосте: файл может быть пустым, обрезанным (процесс
 *   убили посреди записи), содержать мусор. Ни один из этих случаев не должен
 *   превращаться в исключение или в выдуманное «SIGSEGV».
 */
object KenjiCrash {

    data class Report(
        /** Шаг, на котором застали падение: dlopen, javaInitialize и т.д. */
        val step: String,
        /** Номер сигнала, 0 — если сигнала в отчёте нет. */
        val signal: Int,
        /** Человеческое имя сигнала, пусто — если неизвестно. */
        val signalName: String,
        /** Адрес, по которому упало; пусто — если его не записали. */
        val address: String,
        /** Кадры стека вида `libkenji.so+0x1a2b3`, сверху вниз. */
        val frames: List<String>,
        /** Коды debug_break, если ядро их звало перед падением. */
        val debugBreaks: List<Int>,
    ) {
        val isEmpty: Boolean get() = frames.isEmpty() && signal == 0 && debugBreaks.isEmpty()
    }

    /**
     * Разобрать отчёт.
     *
     * Возвращает null, если разбирать нечего — файла нет, он пуст или в нём ни
     * одной знакомой строки. Лучше честное «отчёта нет», чем отчёт из догадок.
     */
    fun parse(text: String): Report? {
        if (text.isBlank()) return null

        var step = ""
        var signal = 0
        var signalName = ""
        var address = ""
        val frames = mutableListOf<String>()
        val breaks = mutableListOf<Int>()

        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.startsWith("step=") ->
                    step = line.removePrefix("step=").trim()

                line.startsWith("debug_break=") ->
                    line.removePrefix("debug_break=").trim().toIntOrNull()?.let { breaks.add(it) }

                // crash signal=11 name=SIGSEGV addr=0x0 code=1 step=javaInitialize
                line.startsWith("crash ") -> {
                    tokens(line).forEach { (key, value) ->
                        when (key) {
                            "signal" -> signal = value.toIntOrNull() ?: 0
                            "name" -> signalName = value
                            "addr" -> address = value
                            // Шаг пишется и в crash-строке: если отдельная
                            // строка step= не дошла до файла, шаг всё равно есть.
                            "step" -> if (step.isEmpty()) step = value
                        }
                    }
                }

                line.startsWith("pc=") -> frames.add(line.removePrefix("pc=").trim())
            }
        }

        val report = Report(step, signal, signalName, address, frames, breaks)
        return if (report.isEmpty) null else report
    }

    /**
     * Текст для человека.
     *
     * Никаких «попробуйте ещё раз»: здесь ровно то, что известно, и что с этим
     * делать дальше. Шаг называется по имени функции, потому что именно он
     * отличает «файл не открылся» от «ядро отказалось стартовать».
     */
    fun format(report: Report?, stepNow: String = ""): String {
        if (report == null) {
            val where = if (stepNow.isNotBlank()) "на шаге «$stepNow»" else "во время запуска"
            return "Процесс второго ядра завершился $where, но отчёта о падении нет: " +
                "система остановила его раньше, чем он успел записать причину " +
                "(так бывает при нехватке памяти). Журнал ядра и «Диагностика» " +
                "в этом случае — единственный источник подробностей."
        }

        val builder = StringBuilder()
        builder.append("Отчёт о падении второго ядра:\n")
        builder.append("• шаг: ").append(stepLabel(report.step.ifBlank { stepNow })).append('\n')
        if (report.signal != 0) {
            builder.append("• сигнал: ").append(report.signal)
            if (report.signalName.isNotBlank()) builder.append(" (").append(report.signalName).append(')')
            builder.append('\n')
        }
        if (report.address.isNotBlank()) {
            builder.append("• адрес: ").append(report.address).append('\n')
        }
        if (report.frames.isNotEmpty()) {
            builder.append("• верхние кадры: ")
            builder.append(report.frames.take(3).joinToString(" → "))
            builder.append('\n')
        }
        if (report.debugBreaks.isNotEmpty()) {
            builder.append("• ядро звало debug_break с кодами: ")
            builder.append(report.debugBreaks.joinToString(", "))
            builder.append('\n')
        }
        builder.append('\n')
        builder.append(verdict(report))
        return builder.toString().trim()
    }

    /**
     * Что этот отчёт значит на практике.
     *
     * Выводов может быть два сразу, и они не спорят: падение внутри
     * libkenji.so и debug_break перед ним говорят об одном и том же по-
     * разному, а вот «виноват файл ядра» и «виноват мост» — разные ответы, и
     * их нельзя смешивать в один. Поэтому наблюдения складываются списком.
     */
    fun verdict(report: Report): String {
        val points = mutableListOf<String>()

        if (report.frames.any { it.startsWith("libkenji.so") }) {
            points.add(
                "Падение внутри libkenji.so — это файл ядра, а не оболочка приложения. " +
                    "Первое, что стоит исключить: скачанный файл повреждён."
            )
        } else if (report.frames.isNotEmpty()) {
            points.add(
                "Падение в нашей части моста — это наша ошибка, и в отчёте есть кадры, " +
                    "чтобы её найти."
            )
        } else {
            points.add("Сигнал есть, кадров нет: падение случилось до того, как стек стал читаемым.")
        }

        if (report.debugBreaks.isNotEmpty()) {
            points.add(
                "Перед падением ядро звало debug_break(" +
                    report.debugBreaks.joinToString(", ") + "): оно само сообщило об ошибке " +
                    "в своей инициализации — не приняло окружение (папки, ключи, версию системы)."
            )
        }

        points.add(
            "Что сделать: нажмите «Скопировать» в этом окне и приложите отчёт — по шагу " +
                "и кадрам видно, чинить файл ядра или мост."
        )
        return points.joinToString("\n\n")
    }

    private fun stepLabel(step: String): String = when (step) {
        "" -> "не записан"
        "dlopen" -> "dlopen — загрузка файла ядра"
        "javaInitialize" -> "javaInitialize — передача ядру пути к данным и JNI"
        "deviceInitialize" -> "deviceInitialize — настройка устройства"
        "graphicsInitialize" -> "graphicsInitialize — настройка графики"
        else -> step
    }

    private fun tokens(line: String): List<Pair<String, String>> =
        line.removePrefix("crash").trim()
            .split(' ')
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }
}
