// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import org.yuzu.yuzu_emu.utils.CoreFallback
import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.KenjiBridge
import org.yuzu.yuzu_emu.utils.KenjiCrash
import org.yuzu.yuzu_emu.utils.KenjiPlayService
import org.yuzu.yuzu_emu.utils.KenjiReport
import org.yuzu.yuzu_emu.utils.UiGuard

/**
 * Second player. Lives in the :kenji process so a fault in Kenji cannot
 * close the launcher. Eden stays in EmulationActivity in the main process.
 */
class KenjiPlayerActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_PATH = "kenji_path"
        const val EXTRA_TITLE = "kenji_title"

        /**
         * Действующие настройки, посчитанные в главном процессе.
         *
         * SharedPreferences между процессами не синхронизируются, поэтому
         * `:kenji` не может доверять своему чтению: он получает готовый
         * набор строкой и применяет его как есть.
         */
        const val EXTRA_CONFIG = "kenji_config"

        fun intent(
            context: Context,
            path: String,
            title: String,
            config: String = ""
        ): Intent =
            Intent(context, KenjiPlayerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CONFIG, config)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private var pfd: ParcelFileDescriptor? = null
    private var loopThread: Thread? = null
    private var started = false
    /** Запуск оборвался с названной причиной — она должна остаться в отчёте. */
    private var failed = false
    /** Кадров на прошлом тике: разница показывает текущую скорость. */
    private var lastFrames = 0L
    /** Размер поверхности — для повторного пинка рендерера из наблюдения. */
    private var surfaceWidth = 1280
    private var surfaceHeight = 720
    /** Повторный пинок уже сделан: делается один раз, а не каждые 3 секунды. */
    private var kickedLate = false
    /** Сколько байт журнала ядра уже прочитано: ищем только новое. */
    private var coreLogOffset = 0L
    /** Игра уже встала внутри ядра — второй раз не сообщаем. */
    private var guestFailed = false
    /** Путь игры: по нему основной процесс переведёт её на Eden. */
    private var gamePath = ""
    /** Когда начали: журнал старше этого времени — от прошлого запуска. */
    private var playStartedAt = 0L
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "игра" }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        status = TextView(this).apply {
            text = "Symbiosis · второе ядро\n$title"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 48, 24, 16)
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val back = Button(this).apply {
            text = "Выйти"
            setOnClickListener { leave() }
        }
        root.addView(
            back,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(24, 24, 24, 48) }
        )
        setContentView(root)

        if (path.isBlank()) {
            fail("нет пути к игре")
            return
        }
        if (EngineLoader.state(this, EngineLoader.Engine.KENJI) !is EngineLoader.State.Ready &&
            EngineLoader.state(this, EngineLoader.Engine.KENJI) !is EngineLoader.State.Builtin
        ) {
            fail("второе ядро не скачано")
            return
        }
        pfd = openRom(path)
        if (pfd == null) {
            fail("не открылся файл игры")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (started) return
        val fd = pfd?.fd ?: return fail("нет дескриптора")
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
        val w = holder.surfaceFrame.width().coerceAtLeast(128)
        val h = holder.surfaceFrame.height().coerceAtLeast(128)
        surfaceWidth = w
        surfaceHeight = h
        gamePath = path
        playStartedAt = System.currentTimeMillis()
        val appContext = applicationContext
        started = true
        loopThread = Thread({
          // Ошибка в коде самого плеера не должна убивать процесс: экран уже
          // занят игрой, и «Приложение остановлено» без причины — худший из
          // возможных ответов. Ловим всё и называем причину на экране и в
          // отчёте; ядро при этом уже загружено, поэтому выходим штатно.
          try {
            // Отчёт о падении включается ДО первого касания ядра: файл и
            // обработчики сигналов переживут смерть процесса, и в отчёте
            // останется шаг, на котором всё оборвалось. Раньше это делала
            // только «проверка ядра», поэтому падение игры не оставляло
            // ничего, кроме чёрного экрана.
            runCatching {
                KenjiBridge.pidFile(appContext).writeText("${android.os.Process.myPid()}")
            }
            // Обработчик необработанных исключений Java. Без него любая
            // ошибка в нашем же коде убивает процесс молча: 04.11 22:12:49
            // причина ушла вместе с процессом, и её пришлось восстанавливать
            // по номерам строк из чужого журнала.
            runCatching { UiGuard.install(appContext) }
            KenjiBridge.armCrashReport(appContext)
            KenjiReport.markStart(appContext)
            org.kenjinx.android.KenjinxNative.resetStats()

            val nw = KenjiBridge.nativeWindow(holder.surface)
            org.kenjinx.android.KenjinxNative.nativeSurface = nw
            org.kenjinx.android.KenjinxNative.nativeWindow = nw

            runOnUiThread { status.text = "второе ядро · готовлю ядро и прошивку" }
            KenjiBridge.noteStep("1/6 · загрузка ядра")
            val prep = KenjiBridge.preparePlay(appContext, config)
            if (!prep.ok) {
                runOnUiThread { fail(prep.message) }
                return@Thread
            }

            // Порядок как в официальном Kenji: конфигурация графики →
            // рендерер → устройство → игра. Устройство без рендерера ядро
            // создать отказывается (его InitializeContext проверяет
            // Renderer != null), а рендерер без поверхности создавать нечего.
            KenjiBridge.noteStep("2/6 · рендерер")
            val surf = KenjiBridge.attachSurface(holder.surface, w, h)
            if (!surf.ok) {
                runOnUiThread { fail(surf.message) }
                return@Thread
            }

            KenjiBridge.noteStep("3/6 · устройство")
            val dev = onMainAndWait { KenjiBridge.deviceInit() }
            if (!dev.ok) {
                runOnUiThread { fail(dev.message) }
                return@Thread
            }

            runOnUiThread { status.text = "второе ядро · загружаю игру" }
            KenjiBridge.noteStep("4/6 · игра")
            val load = KenjiBridge.loadGame(fd, KenjiBridge.fileTypeOf(path))
            if (!load.ok) {
                runOnUiThread { fail(load.message) }
                return@Thread
            }

            // Кто открыт в ядре — до ввода и до цикла: если никого, сервис
            // учётной записи ответит исключением, и игра встанет на чёрном
            // экране. Тогда это видно сразу, а не через минуту гадания.
            // Не останавливаем запуск: пустой пользователь — сильное
            // подозрение, но не доказательство, а блокировать игру из-за
            // подозрения нельзя. Пишем в отчёт и в строку статуса.
            val user = KenjiBridge.openedUser()
            runCatching {
                KenjiReport.watch(
                    appContext,
                    if (user.isEmpty()) "пользователь ядра: не открыт"
                    else "пользователь ядра: $user"
                )
            }

            // Ввод — после игры, как в официальном Kenji (GameHost.start):
            // ядро передаёт EmulationContext в менеджеры ввода, и до
            // загрузки игры его нет. Окно отдаём тем же вызовом, что и оригинал.
            KenjiBridge.noteStep("5/6 · ввод")
            KenjiBridge.setWindowHandle(nw)
            val input = onMainAndWait { KenjiBridge.inputInit(w, h) }
            if (!input.ok) {
                runOnUiThread { fail(input.message) }
                return@Thread
            }

            // Строка настроек рядом со статусом: «ядро запустилось» без них
            // ничего не говорит о том, с чем именно оно запустилось.
            runOnUiThread {
                status.text = "второе ядро · играет\n" + prep.message
                // Уведомление поднимает приоритет процесса: без него система
                // выгружает :kenji первой, как только кончается память, и игра
                // пропадает вместе с плеером.
                // `title` здесь — свойство Activity (CharSequence), а не имя
                // игры: берём его из интента, иначе в уведомлении оказывается
                // заголовок окна.
                val gameTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                runCatching { KenjiPlayService.start(appContext, gameTitle) }
                startWatchdog(appContext)
            }

            // Пинок рендереру ровно как в официальном приложении: поворот,
            // размер, растяжение, пересоздание цепочки буферов, разрешение
            // показа. Без этого Vulkan может рисовать в старую цепочку —
            // цикл идёт, а на экране ничего.
            KenjiBridge.noteStep("6/7 · рендерер")
            val kick = KenjiBridge.rendererKick(w, h, displayRotation())
            if (!kick.ok) {
                runOnUiThread { fail(kick.message) }
                return@Thread
            }

            KenjiBridge.noteStep("7/7 · цикл отрисовки")
            KenjiBridge.runLoop()
            KenjiBridge.noteStep("цикл завершён")
            runOnUiThread { leave() }
          } catch (t: Throwable) {
            runOnUiThread {
                fail("внутренняя ошибка плеера: " + (t.message ?: t.javaClass.simpleName))
            }
          }
        }, "kenji-loop").also { it.start() }
    }

    /**
     * Живой счётчик вместо чёрного экрана.
     *
     * «Висит без ничего» — это не описание: чёрный экран одинаково выглядит у
     * работающего ядра, у ядра без прошивки и у цикла, который стоит на
     * первом кадре. Поэтому пока идёт игра, раз в три секунды обновляем
     * строку: сколько секунд прошло и что последнее записало само ядро в свой
     * журнал. Если счётчик идёт — процесс жив, и вопрос уже в кадрах; если
     * журнал пишет ошибку — она видна на экране, а не в чужом файле.
     */
    private fun startWatchdog(appContext: android.content.Context) {
        val beganAt = android.os.SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val seconds = (android.os.SystemClock.elapsedRealtime() - beganAt) / 1000
                val frames = org.kenjinx.android.KenjinxNative.frames.get()
                val rate = frames - lastFrames
                lastFrames = frames
                val verdict = when {
                    frames == 0L && org.kenjinx.android.KenjinxNative.surfaceAsked.get() == 0L ->
                        "цикл ещё не дошёл до отрисовки"
                    frames == 0L -> "поверхность создана, кадров нет"
                    else -> "кадров $frames (+$rate за 3 с)"
                }
                // Второй пинок рендерера.
                //
                // Первый делается сразу после появления поверхности, и в
                // журнале ядра он виден так: «deviceRecreateSwapchain:
                // Renderer.Window == null» — цепочку буферов пересоздавать
                // было ещё не на чем. Если через шесть секунд кадров нет,
                // повторяем: к этому времени рендерер уже создан, и это
                // ровно то же действие, что делает официальное приложение
                // при повороте экрана.
                if (!kickedLate && frames == 0L && seconds >= 6) {
                    kickedLate = true
                    runCatching {
                        val again = KenjiBridge.rendererKick(
                            surfaceWidth, surfaceHeight, displayRotation())
                        KenjiReport.watch(
                            appContext,
                            "повторный пинок рендерера: " + if (again.ok) "сделан" else again.message
                        )
                    }
                }
                if (!guestFailed) checkGuestFailure(appContext)
                // Игра встала внутри ядра: экран уже показывает причину, и
                // перебивать её счётчиком кадров нельзя — человек читает
                // именно объяснение.
                if (guestFailed) return
                val tail = coreLogTail(appContext, 2)
                status.text = buildString {
                    append("второе ядро · играет · ").append(seconds).append(" с")
                    append('\n').append(KenjiBridge.lastConfig)
                    append('\n').append(verdict)
                    if (KenjiBridge.lastUser.isEmpty()) {
                        append("\nпрофиль не открыт — сервис учётной записи может упасть")
                    }
                    if (rate > 0) append('\n').append("≈ ").append(rate / 3).append(" кадр/с")
                    if (tail.isNotEmpty()) append('\n').append(tail)
                }
                // Тот же текст — в отчёт. Человеку некогда делать скриншот на
                // чёрном экране, поэтому всё, что видно, записывается само.
                runCatching {
                    KenjiReport.watch(appContext, "$seconds с · $verdict")
                }
                status.postDelayed(this, 3000)
            }
        }
        status.removeCallbacks(tick)
        status.postDelayed(tick, 3000)
    }

    /**
     * Ядро уронило гостевой поток — сказать об этом сразу.
     *
     * ЗАЧЕМ. 05.11 игра встала через 20 секунд после старта: внутри ядра
     * возникло исключение на вызове сервиса учётной записи, кадры перестали
     * идти, а плеер ещё минуту показывал «поверхность создана, кадров нет» —
     * человек ждал, хотя всё уже было кончено. Теперь журнал ядра читается
     * каждые три секунды (только новые строки, чтобы не перечитывать файл
     * целиком), и первое же «Unhandled exception in guest thread» заканчивает
     * запуск с понятной причиной.
     *
     * Починить это наше ядро не может — это его внутренняя ошибка. Зато
     * приложение запоминает игру и в следующий раз запускает её на Eden.
     */
    private fun checkGuestFailure(appContext: android.content.Context) {
        val file = runCatching { KenjiBridge.coreLogFile(appContext) }.getOrNull() ?: return
        if (!file.isFile) return
        // Журнал от прошлого запуска: в нём уже есть чужое исключение, и
        // принять его за сегодняшнее — значит перевести здоровую игру на
        // Eden из-за вчерашней ошибки.
        if (file.lastModified() < playStartedAt) return
        val length = file.length()
        // Журнал пересоздан (новый запуск) — читаем с начала.
        if (length < coreLogOffset) coreLogOffset = 0L
        if (length == coreLogOffset) return
        val fresh = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(coreLogOffset)
                val size = (length - coreLogOffset).coerceAtMost(256L * 1024L).toInt()
                val buffer = ByteArray(size)
                val read = raf.read(buffer)
                coreLogOffset = length
                if (read <= 0) "" else String(buffer, 0, read)
            }
        }.getOrDefault("")
        if (!fresh.contains("Unhandled exception in guest thread")) return

        guestFailed = true
        val why = KenjiCrash.guestFailure(fresh)
        // Строки ДО исключения — единственное место, где ядро могло назвать
        // номер процесса, который оно не нашло. Без них следующий отчёт
        // опять будет «что-то внутри ядра».
        val around = runCatching {
            val all = fresh.lines()
            val at = all.indexOfFirst { it.contains("Unhandled exception in guest thread") }
            if (at < 0) emptyList()
            else all.subList((at - 8).coerceAtLeast(0), (at + 4).coerceAtMost(all.size))
        }.getOrDefault(emptyList())
        around.forEach { line ->
            if (line.isNotBlank()) runCatching { KenjiReport.watch(appContext, line.trim().take(150)) }
        }
        val service = KenjiCrash.guestService(fresh)
        runCatching { CoreFallback.note(appContext, gamePath, why) }
        runCatching { KenjiReport.watch(appContext, "ядро: " + why.take(160)) }
        // Разблокировать цикл отрисовки: он стоит на мёртвом гостевом потоке.
        runCatching { KenjiBridge.stopPlay() }
        runOnUiThread {
            // Короткая строка на экран: полное объяснение — в отчёте, а здесь
            // человек читает его, стоя с телефоном в руках.
            val short = if (service.isEmpty()) {
                "игра остановилась внутри второго ядра"
            } else {
                "игра остановилась внутри второго ядра на вызове сервиса «$service»"
            }
            fail(short + ".\n\nЭто ошибка самого ядра, а не файла игры. " +
                "Запомнил: эта игра будет запускаться на Eden.")
        }
    }

    /**
     * Поворот экрана в градусах — ядру нужно именно число.
     *
     * Официальное приложение передаёт его тем же вызовом при каждом появлении
     * поверхности: от него зависит преобразование буфера, а значит и то, в
     * какой ориентации кадр попадёт на экран.
     */
    private fun displayRotation(): Int {
        val rotation = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.rotation
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
        }.getOrNull() ?: 0
        return when (rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * Что ядро сказало последним — с началом исключения, а не хвостом стека.
     *
     * ПЕРВЫЙ ОПЫТ БЫЛ НЕУДАЧНЫМ. Показывались последние две строки журнала, а
     * у исключения последние строки — это `at ... + 0x6f0`, то есть адреса
     * методов без самой причины. На экране оказался хвост стека, по которому
     * нельзя ничего решить.
     *
     * Теперь ищем последнюю строку с `Exception`/`|E|` и показываем её вместе
     * со следующими: там написано, ЧТО случилось, а не где.
     */
    private fun coreLogTail(context: android.content.Context, lines: Int): String =
        runCatching {
            val file = KenjiBridge.coreLogFile(context) ?: return ""
            val all = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            if (all.isEmpty()) return ""
            val start = all.indexOfLast { line ->
                line.contains("Exception", ignoreCase = true) ||
                    line.contains("|E|") ||
                    line.contains("Unhandled", ignoreCase = true)
            }
            val slice = if (start >= 0) {
                all.subList(start, minOf(all.size, start + lines))
            } else {
                all.takeLast(lines)
            }
            slice.joinToString("\n") { it.take(200) }
        }.getOrDefault("")

    /**
     * Выполнить шаг из главного потока и дождаться ответа.
     *
     * `deviceInitialize` обязан идти из главного потока — это прямо написано
     * в официальном Kenji («We are only able to initialize the emulation
     * context on the main thread»), и там же вызов завёрнут в переброс на
     * UI-поток с ожиданием. Ввод поднимается оттуда же — его зовёт
     * GameHost.start, то есть колбэк поверхности.
     */
    private fun onMainAndWait(block: () -> KenjiBridge.Status): KenjiBridge.Status {
        var result = KenjiBridge.Status(false, "шаг не выполнился")
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            result = runCatching(block)
                .getOrElse { KenjiBridge.Status(false, it.message ?: "шаг") }
            latch.countDown()
        }
        latch.await(180, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        KenjiBridge.stopPlay()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        leave()
    }

    override fun onDestroy() {
        runCatching { KenjiPlayService.stop(applicationContext) }
        // Метка «вышли штатно» — до unload: если процесс умрёт ниже, отчёт
        // о падении должен остаться, а не превратиться в чистый выход.
        // А после названного отказа метку не меняем: причина должна дойти до
        // списка игр, иначе она живёт шесть секунд на чёрном экране.
        if (!failed) KenjiReport.markClean(applicationContext)
        KenjiBridge.stopPlay()
        loopThread?.join(1500)
        KenjiBridge.unload()
        runCatching { pfd?.close() }
        pfd = null
        super.onDestroy()
    }

    private fun leave() {
        KenjiBridge.stopPlay()
        finish()
    }

    private fun fail(msg: String) {
        failed = true
        status.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        // Причина падения должна остаться и после того, как экран закроется:
        // отчёт о падении читает лаунчер и показывает его при возврате.
        runCatching {
            KenjiBridge.crashReportFile(applicationContext)
                .appendText("\nплеер: $msg\n")
        }
        // Даём прочитать: человек сам решит, когда выйти.
        status.postDelayed({ finish() }, 6000)
    }

    private fun openRom(path: String): ParcelFileDescriptor? = runCatching {
        if (path.startsWith("/")) {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            contentResolver.openFileDescriptor(Uri.parse(path), "r")
        }
    }.getOrNull()
}
