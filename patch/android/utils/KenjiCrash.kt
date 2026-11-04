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
        /** Регистры аргументов в момент падения, как их записал обработчик. */
        val registers: String = "",
        /**
         * Кто позвал abort() — адрес возврата из перехватчика abort.
         *
         * Это единственный кадр, который называет ВИНОВНИКА, а не механизм:
         * обработчик сигнала видит уже libc, а перехватчик получает
         * управление раньше, на целом стеке.
         */
        val abortCaller: String = "",
        /** Кадры, снятые от перехватчика abort, — выше виновника. */
        val abortFrames: List<String> = emptyList(),
        /**
         * Взведён ли перехват к моменту падения. Пишет сам мост:
         * `abort interposer: активен/не активен` и
         * `got patch: abort — подменено слотов: N`.
         *
         * Без этих строк отчёт «виновника нет» нельзя отличить от отчёта
         * старой сборки, где перехватчика не было вовсе, — ровно на этом и
         * потерялся один круг.
         */
        val trapState: List<String> = emptyList(),
        /** Ответ настоящей инициализации ядра: 0 — прошла, иначе — код отказа. */
        val initResult: String = "",
        /** Какие функции ядро просило у загрузчика и не нашло. */
        val loaderMisses: List<String> = emptyList(),
        /** Смещение в файле ядра у функции инициализации, которая отказала. */
        val initCallbackAt: String = "",
        /** Что мы передали ядру: путь, существует ли он, и указатель на JNIEnv. */
        val initArgs: String = "",
        /** Сколько функций ядро спросило у загрузчика всего. */
        val loaderCalls: Int = 0,
        /**
         * Ответы окружения, которые ядро проверяет первым делом:
         * размер страницы, число процессоров, mmap, mlock, мьютекс.
         *
         * ЗАЧЕМ. Отказ инициализации ядро не печатает, но рождается он здесь:
         * в файле ядра функция 0x173db04 заводит нулевую страницу и считает
         * любой отказ mlock отказом всей инициализации. Эти строки — ответ на
         * вопрос «что именно не так», своими словами.
         */
        val palLines: List<String> = emptyList(),
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
        var registers = ""
        var abortCaller = ""
        var initResult = ""
        var initCallbackAt = ""
        var initArgs = ""
        val abortFrames = mutableListOf<String>()
        val trapState = mutableListOf<String>()
        val palLines = mutableListOf<String>()
        val loaderMisses = mutableListOf<String>()
        var loaderCalls = 0
        val frames = mutableListOf<String>()
        val breaks = mutableListOf<Int>()

        // ФАЙЛ ДОПИСЫВАЕТСЯ, А НЕ ПЕРЕЗАПИСЫВАЕТСЯ. Каждый запуск ядра
        // добавляет свои строки в конец, поэтому старый разбор «все строки
        // подряд» показывал ПЕРВОЕ падение за всю историю файла: на телефоне
        // окно снова и снова показывало отчёт от прежней сборки, и отличить
        // его от нового было нельзя. Разбираем последний прогон.
        val all = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        // Начало последнего прогона — его собственная строка step=. Перед ней
        // стоят строки состояния перехвата того же прогона, поэтому поднимаемся
        // выше, пока они идут подряд; кадры предыдущего прогона (pc=) так
        // остаются позади и в отчёт не попадают.
        var from = all.indexOfLast { it.startsWith("step=") }
        if (from < 0) {
            from = all.indexOfLast { it.startsWith("crash ") }
        }
        if (from < 0) {
            from = 0
        }
        while (from > 0 && (all[from - 1].startsWith("abort interposer:") ||
                    all[from - 1].startsWith("got patch:") ||
                    all[from - 1].startsWith("init guard:") ||
                    all[from - 1].startsWith("init-callback=") ||
                    all[from - 1].startsWith("init-callback-fn=") ||
                    all[from - 1].startsWith("loader tap:") ||
                    all[from - 1].startsWith("init-args:") ||
                    all[from - 1].startsWith("pal:") ||
                    all[from - 1].startsWith("call:") ||
                    all[from - 1].startsWith("dlreq "))
        ) {
            --from
        }
        all.drop(from).forEach { line ->
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

                // regs x0=… x1=… — регистры аргументов в момент падения.
                // Не кадры, поэтому отдельным полем: по ним видно, с чем
                // именно ядро не справилось.
                line.startsWith("regs ") -> registers = line.removePrefix("regs ").trim()

                // abort-caller=libkenji.so+0x173c214 — кто потребовал
                // остановку. Пишет перехватчик abort, до входа в libc, поэтому
                // стек ещё цел и адрес возврата настоящий.
                line.startsWith("abort-caller=") ->
                    abortCaller = line.removePrefix("abort-caller=").trim()

                // dlsym <имя> NULL — ядро просило функцию и не нашло её. Именно
                // такие строки объясняют отказ инициализации без текста.
                // dlreq <вид> <имя> ok|NULL — каждый запрос ядра к загрузчику.
                // NULL означает «не нашло», и это самая точная причина отказа.
                line.startsWith("dlreq ") -> {
                    ++loaderCalls
                    val parts = line.split(' ')
                    if (line.endsWith(" NULL") && parts.size >= 3) {
                        loaderMisses.add(parts[1] + " " + parts[2])
                    }
                }

                line.startsWith("init-args:") ->
                    initArgs = line.removePrefix("init-args:").trim()

                // pal: page(40)=4096 cpus(96)=8 — ответы на проверки окружения,
                // которые ядро делает до всего остального.
                line.startsWith("pal:") ->
                    palLines.add(line.removePrefix("pal:").trim())

                // call: mmap(4096) = ok — настоящий вызов самого ядра: что
                // спросило и что получило. По последней такой строке видно,
                // на чём ядро остановилось.
                line.startsWith("call:") ->
                    palLines.add("→ " + line.removePrefix("call:").trim())

                line.startsWith("init-callback-fn=") ->
                    initCallbackAt = line.removePrefix("init-callback-fn=").trim()

                line.startsWith("init-callback=") ->
                    initResult = line.removePrefix("init-callback=").trim()

                line.startsWith("abort interposer:") || line.startsWith("got patch:") ||
                    line.startsWith("init guard:") || line.startsWith("loader tap:") ->
                    trapState.add(line.trim())

                line.startsWith("abort-pc=") ->
                    abortFrames.add(line.removePrefix("abort-pc=").trim())
            }
        }

        val report = Report(step, signal, signalName, address, frames, breaks, registers,
            abortCaller, abortFrames, trapState, initResult, loaderMisses, initCallbackAt,
            initArgs, loaderCalls, palLines)
        return if (report.isEmpty) null else report
    }

    /**
     * Текст для человека.
     *
     * Никаких «попробуйте ещё раз»: здесь ровно то, что известно, и что с этим
     * делать дальше. Шаг называется по имени функции, потому что именно он
     * отличает «файл не открылся» от «ядро отказалось стартовать».
     */
    fun format(report: Report?, stepNow: String = "", buildStamp: String = ""): String {
        if (report == null) {
            val where = if (stepNow.isNotBlank()) "на шаге «$stepNow»" else "во время запуска"
            return "Процесс второго ядра завершился $where, но отчёта о падении нет: " +
                "система остановила его раньше, чем он успел записать причину " +
                "(так бывает при нехватке памяти). Журнал ядра и «Диагностика» " +
                "в этом случае — единственный источник подробностей."
        }

        val builder = StringBuilder()
        builder.append("Отчёт о падении второго ядра:\n")
        // Какая сборка это написала. Без этой строки отчёт с телефона нельзя
        // привязать к версии, и один круг уже потерялся именно на этом.
        if (buildStamp.isNotBlank()) {
            builder.append("• сборка: ").append(buildStamp).append('\n')
        }
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
            // Больше трёх: виновник часто четвёртый-пятый, а первые три —
            // libc и точка остановки. Показывали три, и вызывающего не было
            // видно ни разу.
            builder.append("• кадры: ")
            builder.append(report.frames.take(8).joinToString(" → "))
            if (report.frames.size > 8) {
                builder.append(" → … ещё ").append(report.frames.size - 8)
            }
            builder.append('\n')
        }
        if (report.initArgs.isNotBlank()) {
            builder.append("• передано ядру: ").append(report.initArgs).append('\n')
        }
        if (report.loaderCalls > 0) {
            builder.append("• запросов к загрузчику: ").append(report.loaderCalls)
            builder.append(", не найдено: ").append(report.loaderMisses.distinct().size).append('\n')
        }
        if (report.loaderMisses.isNotEmpty()) {
            builder.append("• ядро не нашло функций: ")
            builder.append(report.loaderMisses.distinct().take(10).joinToString(", "))
            if (report.loaderMisses.distinct().size > 10) {
                builder.append(" и ещё ").append(report.loaderMisses.distinct().size - 10)
            }
            builder.append('\n')
        }
        if (report.initResult.isNotBlank()) {
            builder.append("• инициализация ядра ответила: ").append(report.initResult)
            if (report.initCallbackAt.isNotBlank()) {
                builder.append(" [").append(report.initCallbackAt).append(']')
            }
            if (report.initResult.trim() != "0") {
                builder.append(" (ненулевой ответ означает отказ; ядро обёрнуто и продолжает работу)")
            }
            builder.append('\n')
        }
        if (report.palLines.isNotEmpty()) {
            builder.append("• окружение, которое проверяет ядро: ")
                .append(report.palLines.joinToString("; ")).append('\n')
        }
        if (report.trapState.isNotEmpty()) {
            builder.append("• перехват abort: ").append(report.trapState.joinToString("; ")).append('\n')
        }
        if (report.abortCaller.isNotBlank()) {
            builder.append("• abort() позвал кадр: ").append(report.abortCaller).append('\n')
            if (report.abortFrames.isNotEmpty()) {
                builder.append("• выше него: ")
                builder.append(report.abortFrames.take(6).joinToString(" → "))
                builder.append('\n')
            }
        }
        if (report.registers.isNotBlank()) {
            builder.append("• регистры: ").append(report.registers).append('\n')
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
     * ВЕРХНИЙ КАДР, А НЕ ЛЮБОЙ. В первом отчёте с устройства сверху стоял сам
     * обработчик сигнала (наш же .so), а ниже — настоящий виновник. Судить по
     * «есть ли где-то libkenji.so» значило обвинить ядро по кадру, который
     * всего лишь был в стеке. Ответ даёт первый настоящий кадр.
     */
    fun verdict(report: Report): String {
        // Если перехватчик abort сработал — виновник назван адресом возврата.
        // Это точнее любого рассуждения по кадрам сигнала, поэтому идёт первым.
        if (report.abortCaller.isNotBlank()) {
            val where = if (report.abortCaller.contains("libkenji")) {
                "внутри файла ядра"
            } else {
                "в кадре ${report.abortCaller}"
            }
            // Нулевой кадр цепочки повторяет сам адрес возврата, а следующий —
            // место, откуда позвали функцию, вызвавшую abort(). Именно он и
            // отвечает на вопрос «где в ядре»: первый адрес почти всегда внутри
            // крошечной общей функции, через которую проходят все фатальные
            // ошибки ядра, и сам по себе ничего не говорит.
            val above = report.abortFrames.drop(1).firstOrNull { it.isNotBlank() }
            return "Остановку потребовал код $where (адрес возврата " +
                report.abortCaller + ")." +
                (if (above != null) " Место вызова — $above." else "") +
                " Это не сбой памяти и не нехватка памяти: ядро само решило " +
                "прекратить работу.\n\n" +
                "Что сделать: приложите этот отчёт — по адресу видно, какая " +
                "проверка в ядре не прошла, и её можно обойти на нашей стороне."
        }

        // Перехват не взведён, и это отдельный ответ: такой отчёт ничего не
        // говорит о причине, его нельзя сравнивать с отчётом, где перехват был.
        // «не активен» содержит «активен» как подстроку — на этом проверка
        // «взведён ли перехват» однажды уже соврала в обратную сторону.
        val armedByGot = report.trapState.any { it.contains("подменено") }
        val interposerActive = report.trapState.any { it.contains("interposer: активен") }
        val interposerOff = report.trapState.any { it.contains("interposer: не активен") }
        if (report.trapState.isNotEmpty() && !armedByGot && (interposerOff || !interposerActive)) {
            return "Перехват abort в этой сборке не взведён (" +
                report.trapState.joinToString("; ") + "), поэтому виновник не назван. " +
                "Это отчёт о сработавшей защите, а не о причине: приложите его — " +
                "по этим строкам видно, на каком шаге перехват не встал.\n\n" +
                "Что сделать: сообщите эти строки, не меняя сборку."
        }

        // libc и [vdso] — это не виновник, а механизм: abort() живёт в libc и
        // вызывает raise(), поэтому при SIGABRT два верхних кадра всегда
        // системные. Считать виновником первый попавшийся кадр значило бы
        // каждый раз отвечать «виновата система».
        val top = report.frames.firstOrNull { frame ->
            !frame.startsWith("[") && SYSTEM_LIBS.none { frame.startsWith(it) }
        } ?: report.frames.firstOrNull { !it.startsWith("[") }
        val points = mutableListOf<String>()

        when {
            top == null ->
                points.add("Сигнал есть, кадров нет: падение случилось до того, как стек стал читаемым.")

            top.startsWith("libkenji.so") ->
                points.add(
                    if (report.signal == 6) {
                        "abort() вызван изнутри libkenji.so: ядро загрузилось, но само " +
                            "остановилось на шаге «${stepLabel(report.step)}». Это не сбой " +
                            "загрузки и не нехватка памяти — так заканчивается необработанное " +
                            "исключение в самом ядре."
                    } else {
                        "Падение внутри libkenji.so — это файл ядра. Первое, что стоит " +
                            "исключить: скачанный файл повреждён."
                    }
                )

            top.startsWith("libsymbiosis_kenji.so") ->
                points.add(
                    "Падение в нашей части моста — это наша ошибка, и в отчёте есть кадры, " +
                        "чтобы её найти."
                )

            else -> points.add(
                "Падение вне обеих наших библиотек — верхний кадр $top. Обычно это система " +
                    "или сам .NET-рантайм ядра: файл открылся, но запускаться отказался."
            )
        }

        // Сигнал 6 — это не «память побилась», а «кто-то потребовал остановиться».
        // Так выглядит необработанное исключение .NET, и его текст есть в журнале.
        if (report.signal == 6) {
            points.add(
                "SIGABRT — остановка по требованию, а не сбой памяти: программа сама вызвала " +
                    "abort(). У .NET-ядер так заканчивается необработанное исключение, и его " +
                    "сообщение обычно печатается в журнал — он приложен ниже, если система " +
                    "разрешила его прочитать."
            )
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

    /**
     * Аргументы logcat.
     *
     * БЕЗ `--pid`, И ЭТО ВАЖНО. Флаг добавили, чтобы отсечь чужие процессы,
     * но получилось наоборот: на телефоне logcat этот флаг не принял, напечатал
     * ошибку в поток ошибок, мы её склеили с выводом и отфильтровали как «не
     * нашу строку» — в отчёте осталось «журнала этого процесса нет», хотя
     * журнал был. Поэтому фильтрация целиком у нас: формат `threadtime`
     * (в нём есть колонка PID), а лишнее отсекает [filterByPid].
     *
     * `-t` просим с запасом: строк своего процесса будет немного, а между ними
     * в общем буфере лежат чужие.
     */
    fun logcatArgs(buffer: String? = null, lines: Int = 1500): List<String> =
        buildList {
            add("logcat")
            if (buffer != null) {
                add("-b")
                add(buffer)
            }
            add("-d")
            add("-v")
            add("threadtime")
            add("-t")
            add(lines.toString())
        }

    /**
     * Оставить в тексте журнала только строки одного процесса.
     *
     * Понимает оба формата, потому что logcat на разных прошивках отвечает
     * по-разному:
     *
     *  - threadtime: `05-01 19:04:11.001  4628  4650 I Tag: текст`;
     *  - brief:      `I/SymbiosisKenji( 4628): текст`.
     *
     * Строки без метки времени (продолжение многострочного сообщения, стек)
     * относятся к предыдущей записи, поэтому решение запоминается. PID
     * погибшего процесса не переиспользуется мгновенно, так что чужой запуск
     * не подмешивается.
     */
    fun filterByPid(text: String, pid: Int): String {
        val threadtime = Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+(\d+)\s""")
        val brief = Regex("""^[VDIWEFA]/.*?\(\s*(\d+)\):""")
        val kept = StringBuilder()
        var keep = false
        for (line in text.lineSequence()) {
            val match = threadtime.find(line) ?: brief.find(line)
            if (match != null) keep = match.groupValues[1].toIntOrNull() == pid
            if (keep && line.isNotBlank()) kept.append(line).append('\n')
        }
        return kept.toString().trim()
    }

    /**
     * Короткая выжимка записи о падении — для диалога.
     *
     * Показывать весь стек нельзя: его не прочитать. Но и «приложение
     * остановилось» бесполезно. Поэтому: первая строка (сигнал для нативного
     * сбоя, тип и текст для Java) и до [limit] кадров.
     */
    fun uiSummary(text: String, limit: Int = 4): String {
        val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return ""
        // Первая строка записи — не всегда причина: у Java-падения там стоит
        // время и имя потока, а тип ошибки на следующей. Поэтому причиной
        // считаем первую строку, которая ею выглядит.
        val cause = Regex("""(Exception|Error|Throwable)""")
        val head = lines.firstOrNull {
            it.contains("crash signal=") || cause.containsMatchIn(it)
        } ?: lines.first()
        val frames = lines.filter { it !== head }.filter {
            it.startsWith("pc=") || it.trimStart().startsWith("at ")
        }
        val shown = frames.take(limit)
        return buildString {
            append(head.trim())
            if (shown.isNotEmpty()) {
                append('\n')
                shown.forEach { append("  ").append(it.trim()).append('\n') }
            }
            if (frames.size > shown.size) append("  … ещё ${frames.size - shown.size}")
        }.trim()
    }

    /**
     * Как записать необработанное исключение приложения, чтобы его потом
     * прочитали глазами, а не искали в logcat.
     */
    fun describeThrowable(threadName: String, error: Throwable, stamp: String): String {
        val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }
        return "$stamp поток=$threadName\n$trace"
    }

    /**
     * Всё, что ядро успело сказать в свой вывод — без потерь.
     *
     * ПОЧЕМУ НЕ ОДИН [exceptionLines]. Отбор по знакомым словам спасает от
     * шума, но молча теряет сообщение, если рантайм сформулировал его иначе.
     * С устройства пришёл ровно такой случай: причины не нашлось ни в
     * журнале, ни в отборе, и окно сказало «ядро ничего не написало», хотя
     * файл вывода существовал. Причина падения осталась неизвестной из-за
     * фильтра, а не из-за отсутствия текста.
     *
     * Поэтому: если отбор пуст, показываются последние строки файла как есть.
     * Пустой список возможен только для пустого файла — это факт, а не сбой.
     */
    fun consoleLines(dump: String, limit: Int = 12): List<String> {
        val picked = exceptionLines(dump, limit)
        if (picked.isNotEmpty()) return picked
        return tailLines(dump, limit)
    }

    /**
     * Последние непустые строки текста, как есть.
     *
     * Нужны там, где отбор по словам неуместен: например, когда смотреть надо
     * на самый конец журнала процесса — причина всегда последняя, а знакомых
     * слов в ней может не быть вовсе.
     */
    /**
     * Что ядро спрашивало у Java — самое важное в файле перехвата.
     *
     * ПОЧЕМУ НЕ ВЕСЬ ФАЙЛ. Там сотни строк вроде `NewGlobalRef` и
     * `CallObjectMethod`, по которым ничего не видно. Значение имеют запросы
     * имён: `FindClass`, `GetMethodID`, `GetStaticMethodID`, `ThrowNew` — и
     * отметки о том, что в очереди осталось исключение. Если ядро упало после
     * `FindClass(org/kenji/...)`, причина названа: класса нет.
     */
    fun jniLines(dump: String, limit: Int = 14): List<String> {
        if (dump.isBlank()) return emptyList()
        val interesting = listOf(
            "FindClass", "GetMethodID", "GetStaticMethodID", "GetFieldID",
            "GetStaticFieldID", "RegisterNatives", "ThrowNew", "ИСКЛЮЧЕНИЕ"
        )
        val kept = dump.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .filter { line -> line.isNotEmpty() && interesting.any { line.contains(it) } }
            .distinct()
            .map { it.take(200) }
            .toList()
        // Хвост, а не начало: важно то, что было прямо перед падением.
        return kept.takeLast(limit)
    }

    fun tailLines(text: String, limit: Int = 12): List<String> = text.lineSequence()
        .map { it.trim().trimEnd('\r') }
        .filter { it.isNotEmpty() }
        .map { it.take(300) }
        .toList()
        .takeLast(limit)

    /**
     * Строки системного журнала, написанные под одним ярлыком.
     *
     * ЗАЧЕМ. Инициализация ядра ведёт себя молча: файла своего журнала ещё
     * нет, в нашем выводе — ни строки. Но подпись к системе оно ставит раньше,
     * чем заводит файл: ярлык `RyujinxLog`, и всё, что ядро говорит в этот
     * момент, лежит в системном журнале под ним. Отбор по номеру процесса
     * этого не заменяет: если номер не записан или система его не отдала,
     * нужные строки пропадают вместе с ним.
     *
     * Понимает оба формата, которые встречаются на прошивках:
     * `… I RyujinxLog: текст` (threadtime) и `I/RyujinxLog( 1234): текст`
     * (brief). Продолжения многострочных сообщений не берём: без ярлыка они
     * не проверяемы.
     */
    fun linesByTag(logcat: String, tag: String, limit: Int = 12): List<String> {
        if (logcat.isEmpty() || tag.isEmpty()) return emptyList()
        val mark = Regex("[VDIWEFA][ /]\\s*" + Regex.escape(tag) + "\\s*[:(]")
        return logcat.lineSequence()
            .map { it.trimEnd().trimEnd('\r') }
            .filter { mark.containsMatchIn(it) }
            .map { it.take(260) }
            .toList()
            .takeLast(limit)
    }

    /**
     * Строки снимка аварийного завершения, который ведёт сама система.
     *
     * ЧЕГО НЕТ В НАШИХ ФАЙЛАХ. Наш обработчик сигнала пишет номер, адрес и
     * кадры, но не текст, с которым процесс сам себя остановил. Его пишет
     * libc в «Abort message», и система складывает это в tombstone, который
     * приложению отдаётся через ApplicationExitInfo. Там же кадры уже
     * сопоставлены с функциями библиотек — по ним видно, ЧТО вызвало abort().
     *
     * Начинаем с «Abort message», если он есть: остальное — уже следствие.
     */
    fun tombstoneLines(tombstone: String, limit: Int = 14): List<String> {
        val lines = tombstone.lineSequence()
            .map { it.trimEnd().trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return emptyList()

        // Заголовок tombstone (*** *** *** и Cmdline) занимает место и ничего
        // не объясняет — начинаем с первого содержательного места.
        val start = lines.indexOfFirst { line ->
            val low = line.lowercase()
            low.contains("abort message") || low.contains("backtrace:")
        }.takeIf { it >= 0 } ?: 0

        return lines.drop(start)
            .filterNot { it.contains("*** *** ***") || it.startsWith("Cmdline:") }
            .map { it.take(220) }
            .take(limit)
    }

    /**
     * Текст, который рантайм ядра сказал в stderr перед abort().
     *
     * Берём не весь файл — там бывает шум самого ядра. Оставляем строки про
     * исключение и кадры стека («at …»), в том порядке, в каком их печатал
     * рантайм: по нему видно, где начало сообщения, а где уже стек.
     */
    fun exceptionLines(stderrDump: String, limit: Int = 10): List<String> {
        val marks = listOf(
            "unhandled exception", "exception:", "system.", "failfast",
            "--- end of", "abort",
            // Так формулирует остановку сам рантайм .NET (NativeAOT): «Process
            // terminated. …», «Fatal error. Invalid Program: …». Без этих слов
            // его сообщение не попадало в отбор, и причина падения оставалась
            // неизвестной, хотя текст был написан.
            "process terminated", "fatal error", "invalid program", "assertion"
        )
        val kept = stderrDump.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .filter { line ->
                line.isNotEmpty() &&
                    (line.startsWith("at ") || marks.any { line.contains(it, ignoreCase = true) })
            }
            .map { it.take(300) }
            .toList()
        if (kept.isEmpty()) return emptyList()
        // Хвост, а не начало: если файл большой, причина всегда в конце.
        return kept.takeLast(limit)
    }

    /**
     * Строки журнала системы, которые стоит показать рядом с отчётом.
     *
     * ЗАЧЕМ. SIGABRT в .NET-ядре — это почти всегда необработанное исключение,
     * и его текст печатает сам рантайм: «Unhandled exception. System.XxxException:
     * …». В отчёте о сигнале этого текста нет, а без него видно только «код
     * остановился».
     *
     * Читается только свой UID: Android с 4.1 не отдаёт приложению чужие
     * журналы, а оба наших процесса — один UID. Если система отказала,
     * возвращается пустой список, и окно честно молчит про журнал.
     */
    fun fatalLines(logcat: String, limit: Int = 8): List<String> {
        val interesting = listOf(
            // Сначала то, что прямо называет причину.
            "abort message", "unhandled exception", "unhandled exception:", "failfast",
            "fatal exception", "signal 6", "exception:", "system.",
            // .NET на Android печатает необработанное исключение не в stderr, а
            // в журнал с меткой DOTNET: у него Console/stderr перенаправлены в
            // logd. Поэтому «ядро молчит в stderr» и «в журнале ничего нет» —
            // разные вещи, и искать надо здесь.
            "dotnet",
            // Затем наши собственные отметки: по ним видно, докуда дошли.
            "kenji", "libkenji", "symbiosiskenji"
        )
        return logcat.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() && interesting.any { line.contains(it, ignoreCase = true) }
            }
            // Повторы одного и того же сообщения (их бывает по три-четыре)
            // занимают место в окне и ничего не добавляют.
            .distinct()
            .toList()
            .takeLast(limit)
    }

    /** Библиотеки, которые только передают сигнал, а не вызывают его. */
    private val SYSTEM_LIBS = listOf("libc.so", "libc++", "libdl.so", "libm.so", "[vdso]")

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
