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
import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.KenjiBridge
import org.yuzu.yuzu_emu.utils.KenjiReport

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
        val appContext = applicationContext
        started = true
        loopThread = Thread({
            // Отчёт о падении включается ДО первого касания ядра: файл и
            // обработчики сигналов переживут смерть процесса, и в отчёте
            // останется шаг, на котором всё оборвалось. Раньше это делала
            // только «проверка ядра», поэтому падение игры не оставляло
            // ничего, кроме чёрного экрана.
            runCatching {
                KenjiBridge.pidFile(appContext).writeText("${android.os.Process.myPid()}")
            }
            KenjiBridge.armCrashReport(appContext)
            KenjiReport.markStart(appContext)

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
                startWatchdog(appContext)
            }

            KenjiBridge.noteStep("6/6 · цикл отрисовки")
            KenjiBridge.runLoop()
            KenjiBridge.noteStep("цикл завершён")
            runOnUiThread { leave() }
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
                val tail = coreLogTail(appContext, 3)
                status.text = buildString {
                    append("второе ядро · играет · ").append(seconds).append(" с")
                    append('\n').append(KenjiBridge.lastConfig)
                    if (tail.isNotEmpty()) append("\n").append(tail)
                }
                status.postDelayed(this, 3000)
            }
        }
        status.removeCallbacks(tick)
        status.postDelayed(tick, 3000)
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
