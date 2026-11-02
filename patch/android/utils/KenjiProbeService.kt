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
            // PID процесса :kenji — он же PID упавшего ядра. Нужен в тексте,
            // чтобы строки журнала можно было сверить с отчётом глазами.
            val ownPid = android.os.Process.myPid()
            val file = runCatching { KenjiBridge.crashReportFile(context) }.getOrNull()
            val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
            val report = KenjiCrash.parse(text)

            val body = StringBuilder(KenjiCrash.format(report))

            // В отчёте о сигнале есть адреса, но нет текста. Текст исключения
            // .NET печатается в stderr ядра и перенаправлен в файл — это
            // буквально сообщение рантайма, поэтому оно идёт первым.
            val saidFile = runCatching { KenjiBridge.stdioFile(context) }.getOrNull()
            val said = runCatching {
                saidFile?.takeIf { it.isFile }?.readText()
            }.getOrNull().orEmpty()
            val exception = KenjiCrash.exceptionLines(said)
            if (exception.isNotEmpty()) {
                body.append("\n\nЧто сказало ядро перед остановкой:\n")
                exception.forEach { body.append("• ").append(it.take(200)).append('\n') }
            } else if (saidFile != null && saidFile.isFile) {
                // Файл есть, а строк нет: значит ядро в stderr ничего не
                // написало. Это факт, а не сбой чтения, и его надо назвать —
                // иначе выглядит так, будто мы не посмотрели.
                body.append("\n\nЯдро ничего не написало в свой вывод: файл ")
                    .append(saidFile.name).append(" создан, но пуст.")
            }

            val own = captureLogcat()
            val lines = KenjiCrash.fatalLines(own)
            if (lines.isNotEmpty()) {
                body.append("\n\nИз журнала системы (только свой процесс, PID $ownPid):\n")
                lines.forEach { body.append("• ").append(it.take(160)).append('\n') }
            } else if (exception.isEmpty()) {
                // Молчание хуже строки: человек думает, что отчёт неполный,
                // и не знает, что журнала именно этого процесса нет.
                body.append("\n\nЖурнала этого процесса нет: строки своего PID $ownPid")
                    .append(" система не отдала. Это не ошибка приложения, но и не ")
                    .append("подтверждение, что ядро промолчало: .NET-ядра печатают ")
                    .append("исключение в журнал с меткой DOTNET.")
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
        private fun captureLogcat(): String = runCatching {
            val pid = android.os.Process.myPid()
            val crash = read(*KenjiCrash.logcatArgs(buffer = "crash", lines = 400).toTypedArray())
            val main = read(*KenjiCrash.logcatArgs().toTypedArray())
            val filtered = KenjiCrash.filterByPid(crash + "\n" + main, pid)
            if (filtered.isNotEmpty()) return@runCatching filtered

            // Второй заход: буферы могли смениться между вызовами, а на части
            // прошивок `-b crash` закрыт и возвращает пустоту с ошибкой.
            val again = read("logcat", "-d", "-v", "threadtime", "-t", "4000")
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
