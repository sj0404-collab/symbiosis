// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import org.yuzu.yuzu_emu.BuildConfig
import java.io.File

/**
 * Loads and starts the Kenji core in a SEPARATE PROCESS.
 *
 * WHY A WHOLE PROCESS FOR THIS
 *   A SIGSEGV or an abort() inside a native library kills the process it runs
 *   in. There is no try/catch in Kotlin or C++ that changes that: by the time
 *   the signal arrives the runtime is already unwinding, and catching it
 *   leaves the .NET heap in a state where the next call fails differently
 *   every time. The only containment Android actually offers is process
 *   isolation.
 *
 *   So the first contact with a freshly downloaded core happens here, in
 *   `:kenji`. If the core aborts - wrong build, corrupt download, a bug in
 *   deviceInitialize - this process dies, [onServiceDisconnected] fires, and
 *   the launcher reports "ядро не запустилось" while carrying on. Without
 *   this the same abort would close the whole app with no message at all.
 *
 * WHAT IT DOES NOT DO
 *   It does not run a game. It answers one question - would this core start on
 *   this device - and reports back. Running the emulation itself in another
 *   process needs the surface, input and audio to be handed across too, which
 *   is a much larger change; this is the piece that makes the rest safe to
 *   attempt.
 */
class KenjiProbeService : Service() {

    companion object {
        const val MSG_PROBE = 1
        const val MSG_RESULT = 2
        const val KEY_OK = "ok"
        const val KEY_MESSAGE = "message"
        const val KEY_SYMBOLS = "symbols"

        /**
         * Ask the isolated process to try the core.
         *
         * [onResult] is always called exactly once - on success, on failure,
         * and on the process dying, which is the case that matters. A callback
         * that never fires would leave a spinner on screen forever, which is
         * how "no crashes" turns into "appears frozen".
         */
        fun probe(context: Context, timeoutMs: Long = 30_000, onResult: (Boolean, String) -> Unit) {
            val app = context.applicationContext
            var finished = false
            val main = Handler(Looper.getMainLooper())

            // Declared before the connection so both can reference each other.
            var connection: ServiceConnection? = null

            val finish = { ok: Boolean, message: String ->
                if (!finished) {
                    finished = true
                    connection?.let { runCatching { app.unbindService(it) } }
                    main.post { onResult(ok, message) }
                }
            }

            val replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (msg.what == MSG_RESULT) {
                        val data = msg.data
                        finish(
                            data.getBoolean(KEY_OK),
                            data.getString(KEY_MESSAGE) ?: "нет ответа"
                        )
                    }
                }
            })

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val outgoing = Messenger(binder)
                    val msg = Message.obtain(null, MSG_PROBE)
                    msg.replyTo = replyTo
                    runCatching { outgoing.send(msg) }
                        .onFailure { finish(false, "не удалось спросить ядро: ${it.message}") }
                }

                // This is the whole point. The isolated process crashing lands
                // here instead of taking the app with it.
                override fun onServiceDisconnected(name: ComponentName?) {
                    // ЧТО ЗДЕСЬ БЫЛО НЕ ТАК. Раньше отсюда выдавался готовый
                    // ответ: «наш мост упал, это не телефон не тянет,
                    // официальный Kenji уже играет». Первая половина была
                    // догадкой, вторая - утверждением, которое никто не
                    // проверял: отчёт с устройства её не подтвердил, зато
                    // отправил человека искать проблему не там.
                    //
                    // Теперь причина берётся из файла, который процесс успел
                    // записать перед смертью: шаг, сигнал, кадры стека.
                    finish(false, crashMessage(app))
                }
            }

            val intent = Intent(app, KenjiProbeService::class.java)
            val bound = runCatching {
                app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)

            if (!bound) {
                finish(false, "не удалось запустить процесс проверки ядра")
                return
            }

            // A core that hangs instead of crashing would otherwise leave the
            // caller waiting forever.
            main.postDelayed({
                finish(false, "ядро не ответило за ${timeoutMs / 1000} с")
            }, timeoutMs)
        }

        /**
         * Текст о падении по файлу отчёта.
         *
         * Отдельной функцией, потому что её проверяют тестом на хосте: разбор
         * чужого файла не должен зависеть от того, что процесс успел умереть
         * в удобный момент.
         */
        fun crashMessage(context: Context): String {
            // PID УПАВШЕГО процесса, а не своего.
            //
            // Отчёт собирается в основном процессе — служба :kenji к этому
            // моменту мертва. Раньше здесь стоял Process.myPid(), то есть номер
            // лаунчера: журнал фильтровался по нему и, разумеется, пустел.
            // Число записано в файл тем процессом, который потом упал.
            val fallenPid = runCatching {
                KenjiBridge.pidFile(context).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            }.getOrNull()
            val file = runCatching { KenjiBridge.crashReportFile(context) }.getOrNull()
            val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
            val report = KenjiCrash.parse(text)

            // Штамп сборки и время файла: по ним видно, к какой версии
            // относится отчёт и не показан ли вчерашний. Один круг уже
            // потерялся на том, что отчёт старой сборки выглядел как новый.
            val stamp = runCatching {
                val version = BuildConfig.VERSION_NAME
                val when_ = file?.takeIf { it.isFile }
                    ?.let { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT)
                        .format(java.util.Date(it.lastModified())) }
                // Когда установлен сам APK. Имя версии у нас — чужое (от Eden),
                // и в двух наших сборках оно одинаковое: по нему новый APK от
                // старого не отличить. А время установки отличает всегда.
                val installed = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                }.getOrNull()?.let {
                    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT)
                        .format(java.util.Date(it))
                }
                buildString {
                    append(version)
                    append(" (").append(BuildConfig.VERSION_CODE).append(')')
                    if (installed != null) append(", APK установлен ").append(installed)
                    if (when_ != null) append(", файл отчёта изменён ").append(when_)
                }
            }.getOrDefault("")
            // «Во время запуска» или «через N секунд после загрузки игры»:
            // решает журнал ядра, а не наша догадка.
            val coreTextForTiming = runCatching {
                KenjiBridge.coreLogFile(context)?.takeIf { it.isFile }?.readText()
            }.getOrNull().orEmpty()
            val body = StringBuilder(
                KenjiCrash.format(
                    report,
                    buildStamp = stamp,
                    ranFor = KenjiCrash.elapsedText(coreTextForTiming),
                    started = KenjiCrash.gameStarted(coreTextForTiming),
                )
            )

            // Необработанное исключение Java из процесса ядра.
            //
            // Отчёт моста пишет только нативный обработчик сигналов: обычное
            // исключение Java до него не доходит, процесс завершает система, и
            // отчёт честно говорит «причины нет». Но причина есть — её кладёт
            // в файл обработчик приложения (04.11 22:12:49 так и потерялся
            // NullPointerException: его не было ни в одном отчёте, и строку
            // пришлось искать по номерам из чужого журнала).
            val javaCrash = runCatching {
                val f = org.yuzu.yuzu_emu.utils.UiGuard.file(context)
                f.takeIf { it.isFile && it.length() > 0 }?.let { clue ->
                    val when_ = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.ROOT)
                        .format(java.util.Date(clue.lastModified()))
                    val lines = clue.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                    // Время обязательно: файл переживает запуски, и без него
                    // вчерашнее падение читается как сегодняшнее.
                    listOf("(записано $when_)") + lines.takeLast(14)
                }
            }.getOrNull().orEmpty()
            if (javaCrash.isNotEmpty()) {
                body.append("\n\nНеобработанная ошибка Java в процессе ядра:\n")
                javaCrash.forEach { body.append("• ").append(it.take(200)).append('\n') }
            }

            // Собственный журнал ядра. Отказ инициализации .NET-ядро не печатает
            // ни в stdout, ни в stderr — оно ловит исключение и пишет в свой
            // журнал. Значит причина, если она вообще названа, лежит там.
            // Если журнала нет, это тоже ответ: каталог не создан (ядро
            // остановилось раньше) или файл не заводится (нет прав на запись).
            val logDir = runCatching { KenjiBridge.coreLogDir(context) }.getOrNull()
            val coreLog = runCatching { KenjiBridge.coreLogFile(context) }.getOrNull()
            val coreTail = runCatching {
                coreLog?.takeIf { it.isFile }?.let { file ->
                    val all = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                    // Начало исключения, а не хвост стека: последние строки
                    // журнала — это `at ... + 0x6f0`, по ним причину не назвать.
                    val start = all.indexOfLast { line ->
                        line.contains("Exception", ignoreCase = true) ||
                            line.contains("Unhandled", ignoreCase = true)
                    }
                    if (start >= 0) all.subList(start, minOf(all.size, start + 12))
                    else all.takeLast(12)
                }
            }.getOrNull().orEmpty()

            if (coreTail.isNotEmpty()) {
                body.append("\n\nЧто ядро записало в свой журнал (")
                    .append(coreLog?.name ?: "?").append("):\n")
                coreTail.forEach { body.append("• ").append(it.take(300)).append('\n') }
            } else if (logDir == null || !logDir.isDirectory) {
                body.append("\n\nСобственного журнала ядра нет: каталог ")
                    .append(logDir?.name ?: "Logs")
                    .append(" не создан — ядро остановилось раньше, чем начало вести журнал.")
            } else {
                val writable = runCatching { KenjiBridge.canWriteCoreLog(context) }
                    .getOrDefault(false)
                body.append("\n\nСобственного журнала ядра нет: каталог ")
                    .append(logDir.name).append(" есть")
                body.append(if (writable) ", запись в него работает" else ", но записать в него файл не удалось")
                body.append(". Значит ядро остановилось до того, как открыло журнал.")
            }

            // Ядро подписывает свои сообщения системе ярлыком RyujinxLog ещё до
            // того, как заведёт файл журнала. Значит отказ, случившийся раньше
            // файла, назван здесь — и только здесь.
            if (coreTail.isEmpty()) {
                val tagName = "RyujinxLog"
                val byTag = runCatching {
                    KenjiCrash.linesByTag(
                        read("logcat", "-b", "main", "-d", "-v", "threadtime", "-t", "3000"),
                        tagName
                    )
                }.getOrNull().orEmpty()
                if (byTag.isNotEmpty()) {
                    body.append("\n\nЧто ядро успело сказать системе (ярлык ")
                        .append(tagName).append("):\n")
                    byTag.forEach { body.append("• ").append(it).append('\n') }
                } else {
                    body.append("\n\nПод ярлыком ").append(tagName)
                        .append(" система тоже ничего не сохранила.")
                }
            }

            // Каталоги, которые ядро обязано было создать само. Проверяются они
            // не из любопытства: неудача создания журнала инициализация
            // возвращает как отказ, не записав ни строки.
            val dirs = runCatching { KenjiBridge.ensureCoreDirs(context) }.getOrNull().orEmpty()
            if (dirs.isNotEmpty()) {
                val missing = dirs.filter { !it.second }.map { it.first }
                if (missing.isNotEmpty()) {
                    body.append("\n\nКаталоги ядра не создаются: ")
                        .append(missing.joinToString(", ")).append('.')
                }
            }

            // В отчёте о сигнале есть адреса, но нет текста. Текст исключения
            // .NET печатается в stderr ядра и перенаправлен в файл — это
            // буквально сообщение рантайма, поэтому оно идёт первым.
            val saidFile = runCatching { KenjiBridge.stdioFile(context) }.getOrNull()
            val said = runCatching {
                saidFile?.takeIf { it.isFile }?.readText()
            }.getOrNull().orEmpty()
            // consoleLines, а не exceptionLines: отбор по знакомым словам
            // выбросил сообщение, не похожее на «Unhandled exception», и окно
            // сказало «ядро молчит» при непустом файле. Теперь если отбор пуст,
            // показываются последние строки как есть.
            val exception = KenjiCrash.consoleLines(said)
            if (exception.isNotEmpty()) {
                body.append("\n\nЧто сказало ядро перед остановкой:\n")
                exception.forEach { body.append("• ").append(it).append('\n') }
            } else if (saidFile != null && saidFile.isFile) {
                // Файл есть, а строк нет: значит ядро в stderr ничего не
                // написало. Это факт, а не сбой чтения, и его надо назвать —
                // иначе выглядит так, будто мы не посмотрели.
                body.append("\n\nЯдро ничего не написало в свой вывод: файл ")
                    .append(saidFile.name).append(" создан, но пуст.")
            } else {
                body.append("\n\nФайла вывода ядра нет: перенаправление не сработало.")
            }

            // Третий источник: что ядро спрашивало у Java.
            //
            // Ядро умирало молча и без текста. Между тем почти все такие
            // смерти — это ответ на собственный вопрос: FindClass вернул null,
            // а ядро на null не проверяет. Файл перехвата называет вопрос
            // дословно, поэтому он идёт сразу после вывода самого ядра.
            val jniFile = runCatching { KenjiBridge.jniLogFile(context) }.getOrNull()
            val jniText = runCatching {
                jniFile?.takeIf { it.isFile }?.readText()
            }.getOrNull().orEmpty()
            val asked = KenjiCrash.jniLines(jniText)
            // Показываем этот блок, ТОЛЬКО когда что-то не нашлось.
            //
            // Раньше он печатался всегда, и в каждом отчёте висел список из
            // восьми «ok» с припиской «если выше есть FindClass на класс,
            // которого нет…». Человек читает это как подозрение и ищет
            // ошибку там, где её нет: мост к падению из ядра отношения не
            // имеет, в стеке нет ни одного его кадра. Отчёт должен говорить
            // о причине, а не отчитываться о здоровой части.
            val jniBroken = asked.any { it.contains("НЕ НАЙДЕН") || it.contains("ИСКЛЮЧЕНИЕ") }
            if (jniBroken) {
                body.append("\n\nЧто не нашлось в Java за ядро:\n")
                asked.filter { it.contains("НЕ НАЙДЕН") || it.contains("ИСКЛЮЧЕНИЕ") }
                    .forEach { body.append("• ").append(it).append('\n') }
            }

            // Второй, независимый источник: снимок, который ведёт система.
            // Именно там лежит «Abort message» — текст, с которым процесс сам
            // себя остановил, и кадры, уже сопоставленные с функциями.
            val exit = runCatching { SysExit.read(context) }.getOrNull()
            val exitText = SysExit.describe(exit)
            if (exitText.isNotEmpty()) {
                body.append("\n\n").append(exitText)
                // Файл рядом с отчётом: снимок системы живёт в памяти
                // недолго, а разбирать его удобнее с компьютера.
                if (exit != null && exit.tombstone.isNotBlank()) {
                    runCatching {
                        KenjiBridge.crashReportFile(context).parentFile
                            ?.let { File(it, "kenji-exit.txt").writeText(exit.tombstone) }
                    }
                }
            }

            val own = captureLogcat(fallenPid)
            val lines = KenjiCrash.fatalLines(own)
            if (lines.isNotEmpty()) {
                body.append("\n\nИз журнала системы (только процесс ядра, PID $fallenPid):\n")
                lines.forEach { body.append("• ").append(it.take(160)).append('\n') }
            } else if (exception.isEmpty()) {
                // Отбор не нашёл знакомых слов, но журнал этого процесса есть.
                // Показываем его хвост как есть: причина падения всегда
                // последняя, и она может быть сформулирована незнакомо.
                // Молчание в этом случае — потеря единственной улики.
                val tail = KenjiCrash.tailLines(own, limit = 12)
                if (tail.isNotEmpty()) {
                    body.append("\n\nХвост журнала процесса $fallenPid (без отбора):\n")
                    tail.forEach { body.append("• ").append(it).append('\n') }
                } else if (fallenPid == null) {
                    body.append("\n\nPID упавшего процесса не записан: журнал не с чем сверить.")
                        .append(" Это ошибка нашей сборки, а не устройства.")
                } else {
                    body.append("\n\nЖурнала процесса $fallenPid система не отдала.")
                        .append(" На этой прошивке чтение журнала своего приложения, видимо,")
                        .append(" закрыто. Тогда единственный источник — буфер крашеи, и он пуст.")
                }
            }

            // Перевод на человеческий: исключение гостевого потока — это
            // внутренняя ошибка ядра, и по .NET-стеку это не видно.
            val guest = KenjiCrash.guestFailure(own + "\n" + exception.joinToString("\n"))
            if (guest.isNotEmpty()) {
                body.append("\n\nЧто это значит:\n• ").append(guest).append('\n')
            }
            return body.toString().trim()
        }

        /**
         * Последние строки журнала ЭТОГО процесса.
         *
         * Порядок попыток важен:
         *
         *  1. буфер крашеи (`crash`) — там лежит «Abort message» и строки
         *     libc о причине остановки;
         *  2. общий буфер — там ядро и рантайм печатают своё.
         *
         * Фильтр по PID — у нас, а не флагом `--pid`: на телефоне logcat этот
         * флаг не принял, и вместо журнала в отчёт попадало «строк нет».
         *
         * Если после фильтра пусто — это не повод молчать: в отчёт идёт
         * прямой строкой, что журнала своего процесса система не отдала.
         */
        private fun captureLogcat(pid: Int?): String = runCatching {
            if (pid == null) return@runCatching ""
            val crash = read(*KenjiCrash.logcatArgs(buffer = "crash", lines = 600).toTypedArray())
            val main = read(*KenjiCrash.logcatArgs(lines = 3000).toTypedArray())
            val filtered = KenjiCrash.filterByPid(crash + "\n" + main, pid)
            if (filtered.isNotEmpty()) return@runCatching filtered

            // Второй заход: `-b crash` на части прошивок закрыт и возвращает
            // пустоту вместе с ошибкой, а общий буфер за это время мог
            // прокрутиться.
            val again = read("logcat", "-d", "-v", "threadtime", "-t", "8000")
            KenjiCrash.filterByPid(again, pid)
        }.getOrDefault("")

        /** Один вызов logcat. Ошибку не бросаем: журнала может не быть. */
        private fun read(vararg command: String): String = runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        }.getOrDefault("")
    }

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_PROBE) return
            val reply = msg.replyTo ?: return

            // Everything below runs in :kenji. If it dies, it dies alone.
            // Отчёт включается ДО первого касания ядра: файл переживёт смерть
            // процесса, и в нём останется шаг, на котором всё оборвалось.
            //
            // И PID — тоже до. Отчёт потом собирает основной процесс, а этот к
            // тому времени мёртв; без записи журнал фильтровался по номеру
            // лаунчера и не находил ни одной строки ядра.
            runCatching {
                KenjiBridge.pidFile(this@KenjiProbeService).writeText("${Process.myPid()}")
            }
            KenjiBridge.armCrashReport(this@KenjiProbeService)
            val status = runCatching { KenjiBridge.start(this@KenjiProbeService) }
                .getOrElse { KenjiBridge.Status(false, "исключение: ${it.message ?: it.javaClass.simpleName}") }

            if (status.ok) {
                runCatching { KenjiBridge.crashReportFile(this@KenjiProbeService).delete() }
            }

            val out = Message.obtain(null, MSG_RESULT)
            out.data.putBoolean(KEY_OK, status.ok)
            out.data.putString(KEY_MESSAGE, status.message)
            out.data.putInt(KEY_SYMBOLS, status.symbols)
            runCatching { reply.send(out) }

            // Unload before the process is reused: leaving a core mapped means
            // a second probe would report the stale result of the first.
            KenjiBridge.unload()
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        KenjiBridge.unload()
        super.onDestroy()
        // The process exists only to hold the core. Letting it linger keeps
        // 55 MB mapped for nothing.
        Process.killProcess(Process.myPid())
    }
}
