// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import org.yuzu.yuzu_emu.utils.CoreFallback
import org.yuzu.yuzu_emu.utils.EngineHybrid
import org.yuzu.yuzu_emu.utils.EngineStore
import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.KenjiBridge
import org.yuzu.yuzu_emu.utils.KenjiUiSession
import org.kenjinx.android.KenjinxNative
import org.yuzu.yuzu_emu.utils.KenjiOverlaySnapshot
import org.yuzu.yuzu_emu.utils.KenjiSurfaceStartGate
import org.yuzu.yuzu_emu.utils.KenjiStartupCoordinator
import org.yuzu.yuzu_emu.utils.GameSessionLock
import org.yuzu.yuzu_emu.overlay.InputOverlay
import org.yuzu.yuzu_emu.overlay.model.OverlayLayout
import org.yuzu.yuzu_emu.features.input.NativeInput
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.features.input.model.NativeAnalog
import org.yuzu.yuzu_emu.utils.KenjiCrash
import org.yuzu.yuzu_emu.utils.KenjiPlayService
import org.yuzu.yuzu_emu.utils.KenjiReport
import org.yuzu.yuzu_emu.utils.KernelLogFormat
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
        private const val EXTRA_OVERLAY = "kenji_eden_overlay"
        const val EXTRA_VERBOSE_LOG = "kenji_verbose_log"

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
                .putExtra(EXTRA_OVERLAY, KenjiOverlaySnapshot.capture())
                .putExtra(EXTRA_VERBOSE_LOG, org.yuzu.yuzu_emu.utils.KenjiLogSettings.verbose(context))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // Android 13+ predictive Back can bypass the deprecated onBackPressed().
    private var systemBackCallback: android.window.OnBackInvokedCallback? = null
    private var pfd: ParcelFileDescriptor? = null
    private var sessionLease: GameSessionLock? = null
    // One reference from ANativeWindow_fromSurface, owned until teardown.
    @Volatile private var ownedNativeWindow = -1L
    private var loopThread: Thread? = null
    @Volatile private var inputRunning = false
    private var inputThread: Thread? = null
    private var stopThread: Thread? = null
    // Owned by this isolated session until the process exits. We do not assume
    // native guest callbacks have stopped merely because the renderer joined.
    private var uiSession: KenjiUiSession? = null

    private val surfaceStart = KenjiSurfaceStartGate()
    private val startup = KenjiStartupCoordinator()
    private val watchdogLock = Any()
    private var watchdogThread: android.os.HandlerThread? = null
    private var watchdogHandler: Handler? = null
    @Volatile private var requireIsolatedProcessExit = false
    @Volatile private var runLoopReturnedSuccessfully = false
    /** Запуск оборвался с названной причиной — она должна остаться в отчёте. */
    @Volatile private var failed = false
    /** Кадров на прошлом тике: разница показывает текущую скорость. */
    private var lastFrames = 0L
    /** Размер поверхности — для повторного пинка рендерера из наблюдения. */
    @Volatile private var surfaceWidth = 1280
    @Volatile private var surfaceHeight = 720
    /** Повторный пинок уже сделан: делается один раз, а не каждые 3 секунды. */
    private var kickedLate = false

    /** Путь к игре: нужен, чтобы запомнить за ней Eden после отказа по кадрам. */
    private var romPath: String = ""

    /** Слова, по которым видно, на чём стоит отрисовка. */
    private val RENDER_NEEDLES = listOf(
        "Renderer", "Vulkan", "Swapchain", "SwapChain", "Present",
        "NvGpu", "Graphics", "Gpu", "Surface", "Shader",
        "{Vi}", "Display", "Framebuffer", "Present"
    )

    /** Предел ожидания первого кадра. Дальше честно сдаёмся. */
    private val noFramesLimitSeconds = 30

    /** Уже сдались по кадрам: второй раз сообщение не показываем. */
    private var noFramesWarned = false
    /** Сколько байт журнала ядра уже прочитано: ищем только новое. */
    private var coreLogOffset = 0L
    private var coreLogPath: String? = null
    private val hidHistory = java.util.ArrayDeque<String>()
    private var hidCalls = java.util.LinkedHashMap<String, String>()
    /** Log evidence alone cannot prove it belongs to this session. */
    @Volatile private var logObservation = ""
    @Volatile private var benchmarkHasFocus = false
    private val benchmarkFocusEpoch = java.util.concurrent.atomic.AtomicInteger(0)
    /** Путь игры: по нему основной процесс переведёт её на Eden. */
    private var gamePath = ""
    /** Когда начали: журнал старше этого времени — от прошлого запуска. */
    private var playStartedAt = 0L
    private lateinit var status: TextView
    private var statusDetails = ""
    private var detailsDialog: AlertDialog? = null
    private var detailsText: TextView? = null
    private var playerMenu: PopupWindow? = null
    private var showPlayerFps = true
    private var showTouchControls = true
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun darkButton(textLabel: String, action: () -> Unit): Button = Button(this).apply {
        text = textLabel
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(45, 48, 57))
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { action() }
    }
    private fun updateStatus(summary: String, details: String = summary) {
        if (failed) return
        status.text = summary
        statusDetails = details
        detailsText?.text = details
    }
    private fun showStatusDetails() {
        if (statusDetails.isBlank() || isFinishing) return
        val textView = TextView(this).apply {
            text = statusDetails
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(25, 27, 33))
            addView(textView)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Состояние второго ядра")
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .setNegativeButton("Выйти из игры") { _, _ -> confirmLeave() }
            .create()
        detailsDialog?.dismiss()
        detailsText = textView
        detailsDialog = dialog
        dialog.setOnDismissListener {
            if (detailsDialog === dialog) {
                detailsDialog = null
                detailsText = null
            }
        }
        dialog.show()
    }
    private fun confirmLeave() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Выйти из игры?")
            .setMessage("Сначала сохраните прогресс средствами самой игры. Меню не ставит игру на паузу.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выйти") { _, _ -> leave() }
            .show()
    }

    /** This menu never claims to pause the running native core. */
    private fun showPlayerMenu() {
        if (isFinishing || isDestroyed || failed || sessionLease == null) return
        if (playerMenu?.isShowing == true) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(25, 27, 33))
        }
        panel.addView(TextView(this).apply {
            text = "Меню игры"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })
        panel.addView(TextView(this).apply {
            text = "Игра продолжает работать — пауза ядра пока недоступна."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(10))
        })
        val popup = PopupWindow(this)
        fun addAction(label: String, action: () -> Unit) {
            panel.addView(darkButton(label) { popup.dismiss(); action() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                    bottomMargin = dp(8)
                })
        }
        addAction("Продолжить") { }
        // Keep exit reachable without scrolling in landscape as well.
        addAction("Выйти из игры") { confirmLeave() }
        addAction(if (showPlayerFps) "Скрыть FPS" else "Показать FPS") {
            showPlayerFps = !showPlayerFps
            if (!showPlayerFps) status.text = status.text.toString().substringBefore(" · ")
        }
        if (::overlay.isInitialized) {
            addAction(if (showTouchControls) "Скрыть управление" else "Показать управление") {
                overlaySink.cancel()
                showTouchControls = !showTouchControls
                overlay.visibility = if (showTouchControls) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        addAction("Состояние и журнал") { showStatusDetails() }
        addAction("Сохранение состояния") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Сохранение состояния недоступно")
                .setMessage("В установленном ядре нет подтверждённой функции сохранения состояния. Используйте обычное сохранение внутри игры.")
                .setPositiveButton("Понятно", null).show()
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        popup.contentView = scroll
        popup.width = minOf(dp(340), resources.displayMetrics.widthPixels - dp(24))
        popup.height = ViewGroup.LayoutParams.MATCH_PARENT
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.rgb(25, 27, 33)) })
        popup.elevation = dp(12).toFloat()
        popup.setOnDismissListener { if (playerMenu === popup) playerMenu = null }
        playerMenu = popup
        popup.showAtLocation(window.decorView, Gravity.END or Gravity.TOP, 0, 0)
    }

    private lateinit var overlay: InputOverlay
    // Kenji's current API exposes only one touchscreen pointer.
    private var touchPointer = -1
    private val overlaySink = object : InputOverlay.InputSink {
        override fun button(button: NativeButton, action: Int) {
            if (startup.isCancelled()) return
            KenjiBridge.inputButton(button.int, action == NativeInput.ButtonState.PRESSED)
        }
        override fun stick(stick: NativeAnalog, x: Float, y: Float) {
            if (startup.isCancelled()) return
            // Eden's realYAxis is already positive upward, as Kenji expects.
            KenjiBridge.inputStick(stick.int, x, y)
        }
        override fun touchDown(id: Int, x: Float, y: Float) {
            if (startup.isCancelled() || touchPointer != -1) return
            touchPointer = id
            KenjiBridge.inputTouch(x.toInt(), y.toInt(), true)
        }
        override fun touchMove(id: Int, x: Float, y: Float) {
            if (startup.isCancelled()) return
            if (touchPointer == id) KenjiBridge.inputTouch(x.toInt(), y.toInt(), true)
        }
        override fun touchUp(id: Int) {
            if (startup.isCancelled()) return
            if (touchPointer == id) {
                touchPointer = -1
                KenjiBridge.inputTouch(0, 0, false)
            }
        }
        override fun cancel() {
            touchPointer = -1
            if (!startup.isCancelled()) KenjiBridge.cancelInputEvents()
        }
    }
    private fun overlayLayout(): OverlayLayout =
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
            OverlayLayout.Portrait else OverlayLayout.Landscape

    private fun isIsolatedKenjiProcess(): Boolean {
        val name = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else File("/proc/self/cmdline").readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
        }.getOrNull()
        return name == "$packageName:kenji"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            systemBackCallback = android.window.OnBackInvokedCallback { handlePlayerBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it
                )
            }
        }
        // Never start a native session if the manifest did not isolate this
        // activity: its teardown deliberately exits the :kenji process.
        if (!isIsolatedKenjiProcess()) {
            Toast.makeText(this, "Не удалось безопасно открыть второе ядро.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        when (val result = GameSessionLock.tryAcquire(File(filesDir, GameSessionLock.FILE_NAME))) {
            is GameSessionLock.Result.Acquired -> sessionLease = result.lease
            else -> {
                // Do not call fail()/requestStop(): the busy session may be a
                // different Activity in this very process, using the same JNI.
                val message = if (result is GameSessionLock.Result.Busy)
                    "Другая игра ещё работает или завершает работу. Подождите и попробуйте снова."
                else "Не удалось защитить игровой сеанс. Проверьте доступ к данным приложения."
                val screen = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setBackgroundColor(Color.rgb(22, 24, 29))
                    addView(TextView(context).apply { text = message; textSize = 18f; setTextColor(Color.WHITE) })
                    addView(darkButton("Вернуться в библиотеку") { finish() })
                }
                setContentView(screen)
                return
            }
        }
        val uiMain = Handler(Looper.getMainLooper())
        lateinit var ui: KenjiUiSession
        val presenter = KenjiUiPresenter(this) { id, accepted, text -> ui.answer(id, accepted, text) }
        ui = KenjiUiSession(
            postMain = { action -> uiMain.post { action() } },
            present = { id, request -> presenter.show(id, request) },
            dismiss = { presenter.dismiss() },
            onError = { message -> if (!startup.isCancelled()) fail(message) },
            activate = { KenjiBridge.activateUi() },
            install = { listener -> KenjinxNative.uiCallback = listener },
            setup = { KenjiBridge.setupUi() },
            respond = { generation, accepted, bytes -> KenjiBridge.respondUi(generation, accepted, bytes) },
        )
        uiSession = ui
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "игра" }

        // Android 15 enforces edge-to-edge for newer targets: the navigation
        // bar can overlap the exit button and Eden overlay, especially at the
        // right edge in landscape. Own inset handling on API 30+ so the entire
        // game area, including touch controls, stays in the safe region.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
        // Status/exit have their own strip and do not cover the game controls.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(25, 27, 33))
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        status = TextView(this).apply {
            text = "Второе ядро · подготовка · подробнее"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { showStatusDetails() }
        }
        statusDetails = "Подготовка: $title"
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(darkButton("Меню") { showPlayerMenu() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        val gameArea = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        gameArea.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val overlayResult = runCatching {
            @Suppress("DEPRECATION")
            val snapshot = requireNotNull(intent.getBundleExtra(EXTRA_OVERLAY))
            KenjiOverlaySnapshot.restore(snapshot)
        }
        val overlayReady = overlayResult.isSuccess
        if (overlayReady) {
            overlay = InputOverlay(this, null).apply {
                inputSink = overlaySink
                layout = overlayLayout()
            }
            gameArea.addView(overlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(gameArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 30) root.requestApplyInsets()

        if (!overlayReady) {
            fail("Не удалось передать настройки управления Eden во второе ядро.")
            return
        }
        if (overlayResult.getOrDefault(emptyList()).isNotEmpty()) {
            Toast.makeText(this, "Home и Capture пока недоступны во втором ядре.", Toast.LENGTH_LONG).show()
        }
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
        // Android may not have supplied the final size yet. SymbEK's real
        // GameHost starts on surfaceChanged, not on this callback.
        surfaceStart.created()
    }

    @Synchronized
    private fun startGame(holder: SurfaceHolder, w: Int, h: Int) {
        if (stopThread != null || failed || isFinishing || isDestroyed) return
        val fd = pfd?.fd ?: return fail("нет дескриптора")
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
        gamePath = path
        playStartedAt = System.currentTimeMillis()
        val appContext = applicationContext
        loopThread = Thread({
          // Ошибка в коде самого плеера не должна убивать процесс: экран уже
          // занят игрой, и «Приложение остановлено» без причины — худший из
          // возможных ответов. Ловим всё и называем причину на экране и в
          // отчёте; ядро при этом уже загружено, поэтому выходим штатно.
          try {
            if (startup.isCancelled()) return@Thread
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
            runCatching {
                if (org.yuzu.yuzu_emu.utils.CoreTestUpdates.enabled()) {
                    val active = org.yuzu.yuzu_emu.utils.CoreUpdateStore.active(
                        appContext.filesDir, org.yuzu.yuzu_emu.utils.CoreTestUpdates.key())
                    val label = active?.let { "версия ${it.core.sequence}, SHA-256 ${it.core.sha256}" }
                        ?: "закреплённое исходное ядро"
                    org.yuzu.yuzu_emu.utils.CoreTestUpdates.note(appContext, "Запуск игрока: $label")
                }
            }
            org.kenjinx.android.KenjinxNative.resetStats()

            if (startup.isCancelled()) return@Thread
            val nw = KenjiBridge.nativeWindow(holder.surface)
            // ARM64 pointers can carry top-byte tags and appear negative as
            // Kotlin Long. Only the JNI error sentinel or null is invalid.
            if (nw == -1L || nw == 0L) {
                if (!startup.isCancelled()) runOnUiThread { fail("Не удалось получить поверхность игры.") }
                return@Thread
            }
            ownedNativeWindow = nw
            org.kenjinx.android.KenjinxNative.nativeSurface = nw
            org.kenjinx.android.KenjinxNative.nativeWindow = nw
            runOnUiThread { updateStatus("Второе ядро · подготовка · подробнее", "Готовлю ядро и прошивку") }
            KenjiBridge.noteStep("1/6 · загрузка ядра")
            val prep = KenjiBridge.preparePlay(appContext, config)
            if (startup.isCancelled()) return@Thread
            if (!prep.ok) {
                runOnUiThread { fail(prep.message) }
                return@Thread
            }

            // Requested policy is applied at the source, not merely hidden in
            // the UI. The isolated process receives a snapshot from the launcher.
            val verboseLog = intent.getBooleanExtra(EXTRA_VERBOSE_LOG, false)
            val logPolicy = KenjiBridge.setDiagnosticLogging(verboseLog)
            android.util.Log.i("SymbiosisKenji", "log_policy=${if (verboseLog) "verbose" else "errors"} api=$logPolicy")
            runCatching { KenjiReport.watch(appContext,
                if (logPolicy) "запрошен журнал: " + if (verboseLog) "подробный" else "ошибки"
                else "управление уровнями журнала ядра недоступно") }

            // Порядок как в официальном Kenji: конфигурация графики →
            // рендерер → устройство → игра. Устройство без рендерера ядро
            // создать отказывается (его InitializeContext проверяет
            // Renderer != null), а рендерер без поверхности создавать нечего.
            if (startup.isCancelled()) return@Thread
            KenjiBridge.noteStep("2/6 · рендерер")
            val surf = KenjiBridge.attachSurface(holder.surface, w, h)
            if (startup.isCancelled()) return@Thread
            if (!surf.ok) {
                runOnUiThread { fail(surf.message) }
                return@Thread
            }

            // The core may block waiting for UI during device/game startup.
            // Install the real listener and activate replies before setup, or
            // stop instead of running the guest with an unanswered callback.
            if (startup.isCancelled()) return@Thread
            val uiReady = uiSession?.start() == true
            if (startup.isCancelled()) return@Thread
            if (!uiReady) {
                runOnUiThread { fail("Не удалось подготовить диалоги второго ядра.") }
                return@Thread
            }

            KenjiBridge.noteStep("3/6 · устройство")
            val dev = onMainAndWait { KenjiBridge.deviceInit() }
            if (startup.isCancelled()) return@Thread
            if (!dev.ok) {
                runOnUiThread { fail(dev.message) }
                return@Thread
            }

            // Контекст уже создан deviceInit. Ставим ввод и первый Npad до
            // loadGame: в родственном ядре загрузка уже запускает гостя.
            // Бинарник может отличаться; порядок требует проверки на телефоне.
            KenjiBridge.noteStep("5/6 · ввод")
            KenjiBridge.setWindowHandle(nw)
            val input = onMainAndWait { KenjiBridge.inputInit(w, h) }
            if (startup.isCancelled()) return@Thread
            if (!input.ok) {
                runOnUiThread { fail(input.message) }
                return@Thread
            }

            // Сначала регистрируем виртуальный контроллер игрока 1, затем
            // обновляем ввод в отдельном потоке, как делает Android-хост Kenji.
            // По одному возврату API нельзя доказать, что гость его принял.
            val padId = KenjiBridge.connectVirtualPad()
            if (startup.isCancelled()) return@Thread
            runCatching { KenjiReport.watch(appContext, "виртуальный контроллер: " +
                if (padId >= 0) "зарегистрирован, id $padId" else "ядро не подключило") }
            if (padId < 0) {
                runOnUiThread { fail("Второе ядро не подключило виртуальный контроллер игрока 1.") }
                return@Thread
            }
            if (!startInputUpdates(padId)) {
                if (!isFinishing) runOnUiThread {
                    fail("Не удалось включить управление Eden во втором ядре.")
                }
                return@Thread
            }

            if (startup.isCancelled()) return@Thread
            runOnUiThread { updateStatus("Второе ядро · загрузка · подробнее", "Загружаю игру") }
            KenjiBridge.noteStep("4/6 · игра")
            val load = KenjiBridge.loadGame(fd, KenjiBridge.fileTypeOf(path))
            if (startup.isCancelled()) return@Thread
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

            // Строка настроек рядом со статусом: «ядро запустилось» без них
            // ничего не говорит о том, с чем именно оно запустилось.
            runOnUiThread {
                if (startup.isCancelled() || isFinishing || isDestroyed || failed) return@runOnUiThread
                updateStatus("Второе ядро · запущено · подробнее", prep.message)
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
            if (startup.isCancelled()) return@Thread
            KenjiBridge.noteStep("6/7 · рендерер")
            // Graphics logging follows the opt-in log policy set above.
            // СИНХРОНИЗАЦИЮ РЕНДЕРЕРА ЗДЕСЬ НЕ ЗОВЁМ — ЯДРО НА НЕЙ ПАДАЕТ.
            //
            // `graphicsRendererSetVsync` (в ядре `LibKenjinx.SetVsyncState`)
            // первым делом берёт объект устройства и на этом шаге его ещё нет:
            // на устройстве 07.11 это дало
            //   System.NullReferenceException at LibKenjinx.SetVsyncState(VSyncMode)
            // и abort() через SIGABRT. Режим синхронизации и так уходит в
            // `deviceInitialize` (параметр vSyncMode), этого достаточно.
            val kick = KenjiBridge.rendererKick(surfaceWidth, surfaceHeight, displayRotation())
            if (startup.isCancelled()) return@Thread
            if (!kick.ok) {
                runOnUiThread { fail(kick.message) }
                return@Thread
            }

            KenjiBridge.noteStep("7/7 · цикл отрисовки")
            if (!startup.beginLoop()) return@Thread
            val loop = KenjiBridge.runLoop()
            runLoopReturnedSuccessfully = loop.ok
            if (!loop.ok && !startup.isCancelled()) {
                runOnUiThread { fail(loop.message) }
                return@Thread
            }
            KenjiBridge.noteStep("цикл завершён")
            synchronized(watchdogLock) {
                if (!failed) checkGuestFailure(appContext)
            }
            val observed = logObservation
            runOnUiThread {
                if (!failed && !startup.isCancelled()) {
                    if (observed.isNotEmpty()) fail("Цикл второго ядра завершился. " + observed)
                    else leave()
                }
            }
          } catch (t: Throwable) {
            runCatching {
                KenjiBridge.crashReportFile(appContext).appendText(
                    "\nошибка плеера: " + t.javaClass.name + ": " + (t.message ?: "без сообщения") + "\n")
            }
            runOnUiThread {
                fail("Внутренняя ошибка плеера. Подробности сохранены в отчёте.")
            }
          } finally {
            startup.initializationEnded()
          }
        }, "kenji-loop").also {
            try { it.start() } catch (t: Throwable) {
                startup.initializationEnded()
                throw t
            }
        }
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
        val worker = android.os.HandlerThread("kenji-log-watch").apply { start() }
        val background = Handler(worker.looper)
        watchdogThread = worker
        watchdogHandler = background
        val tick = object : Runnable {
            private var benchmarkInitialized = false
            private var tickNumber = 0L
            private var lastLogTail = ""
            private var lastRateAt = beganAt
            private var benchmarkFocusSeen = benchmarkFocusEpoch.get()
            private var benchmark: org.yuzu.yuzu_emu.utils.CoreBenchmark.Recorder? = null
            override fun run() {
                synchronized(watchdogLock) { tickOnce() }
            }
            private fun tickOnce() {
                if (startup.isCancelled() || isFinishing || isDestroyed || failed) return
                val now = android.os.SystemClock.elapsedRealtime()
                tickNumber++
                val seconds = (now - beganAt) / 1000
                val frames = org.kenjinx.android.KenjinxNative.frames.get()
                val rate = frames - lastFrames
                val intervalMs = now - lastRateAt
                val callbackRate = if (intervalMs > 0 && rate >= 0) rate * 1000.0 / intervalMs else 0.0
                lastRateAt = now
                lastFrames = frames
                val verdict = when {
                    frames == 0L && org.kenjinx.android.KenjinxNative.surfaceAsked.get() == 0L ->
                        "цикл ещё не дошёл до отрисовки"
                    frames == 0L -> "ядро запросило поверхность; колбэков кадров нет"
                    else -> "callback кадров $frames (+$rate за ${intervalMs / 1000.0} с)"
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
                // Отсутствие колбэков не доказывает отсутствие кадров.
                // Показываем предупреждение, но не останавливаем игру и не
                // переключаем её на другое ядро по одному счётчику.
                if (!noFramesWarned && frames == 0L &&
                    org.kenjinx.android.KenjinxNative.surfaceAsked.get() > 0L &&
                    seconds >= noFramesLimitSeconds
                ) {
                    checkGuestFailure(appContext)
                    noFramesWarned = true
                    runCatching { KenjiReport.watch(appContext,
                        "30 с без callback кадров; это не доказывает отсутствие кадров") }
                }
                // Errors are still recorded by the core. Reading/parsing its log
                // is bounded, off the UI thread, and normally only every 15 s.
                // No log is cleared and an old line never stops the game.
                if (tickNumber == 1L || tickNumber % 5L == 0L) {
                    checkGuestFailure(appContext)
                    lastLogTail = coreLogTail(appContext, 2)
                }
                val tail = lastLogTail
                val coreFps = runCatching { KenjiBridge.gameFrameRate() }.getOrDefault(0.0)
                if (!benchmarkInitialized) {
                    benchmarkInitialized = true
                    benchmark = org.yuzu.yuzu_emu.utils.CoreBenchmarkStore.generation(appContext)
                        ?.let { org.yuzu.yuzu_emu.utils.CoreBenchmark.Recorder(it) }
                }
                val focusEpoch = benchmarkFocusEpoch.get()
                if (focusEpoch != benchmarkFocusSeen || !benchmarkHasFocus) {
                    benchmark?.reset()
                    benchmarkFocusSeen = focusEpoch
                }
                if (benchmarkHasFocus && logObservation.isEmpty()) {
                    val sample = benchmark?.observe(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis(), coreFps)
                    if (sample != null && benchmarkHasFocus && benchmarkFocusEpoch.get() == focusEpoch)
                        org.yuzu.yuzu_emu.utils.CoreBenchmark.save(
                            appContext.filesDir, gamePath, EngineHybrid.Core.KENJI, sample)
                }
                val details = buildString {
                    append("второе ядро · выполняется · ").append(seconds).append(" с")
                    append('\n').append(KenjiBridge.lastConfig)
                    append('\n').append(verdict)
                    if (logObservation.isNotEmpty()) append("\n").append(logObservation)
                    if (noFramesWarned && frames == 0L) append("\nНет callback кадров за 30 с; это не доказывает, что кадров нет. Игра не остановлена автоматически.")
                    // Счётчик самого ядра рядом с нашим: если он растёт, а наш
                    // стоит — картинка есть, а колбэк `frameEnded` до нас не
                    // доходит. Это разные поломки с разными причинами.
                    if (coreFps > 0.0) append("\nядро считает кадров/с: ").append((coreFps * 10).toInt() / 10.0)
                    if (KenjiBridge.lastUser.isEmpty()) {
                        append("\nпрофиль не открыт — сервис учётной записи может упасть")
                    }
                    if (frames > 0) append('\n').append("callback/с: ")
                        .append((callbackRate * 10).toInt() / 10.0)
                        .append(" (не обязательно FPS игры)")
                    if (tail.isNotEmpty()) append('\n').append(tail)
                    // Ноль кадров — показываем слова ядра про отрисовку: без
                    // них экран состоит из наших догадок, а причина лежит в
                    // журнале, которого человек не видит.
                    if (frames == 0L) {
                        runCatching { rendererTail(appContext, 1) }.getOrDefault(emptyList())
                            .firstOrNull { line -> !tail.contains(line) }
                            ?.let { line -> append("\nЧто ядро говорит про отрисовку:\n").append(line) }
                    }
                }
                val fpsText = if (coreFps.isFinite() && coreFps > 0.0 && coreFps < 400.0)
                    "${"%.1f".format(java.util.Locale.ROOT, coreFps)} FPS" else "FPS —"
                val elapsed = "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
                runOnUiThread {
                    if (!startup.isCancelled() && !isFinishing && !isDestroyed) {
                        // Only elapsed session time and actual core FPS are in
                        // the strip; callbacks and logs remain in details.
                        updateStatus(if (showPlayerFps) "$elapsed · $fpsText" else elapsed, details)
                    }
                }
                // Тот же текст — в отчёт. Человеку некогда делать скриншот на
                // чёрном экране, поэтому всё, что видно, записывается само.
                if (tickNumber == 1L || tickNumber % 5L == 0L) runCatching {
                    // Sparse background samples: process RSS is not GPU or
                    // free memory, and does not by itself establish an OOM.
                    val memory = runCatching {
                        File("/proc/self/status").useLines { lines ->
                            lines.filter { it.startsWith("VmRSS:") || it.startsWith("VmSwap:") }
                                .joinToString(" · ") { it.trim() }
                        }
                    }.getOrDefault("")
                    val fps = if (coreFps.isFinite() && coreFps > 0.0 && coreFps < 400.0)
                        " · FPS ядра ${"%.1f".format(java.util.Locale.ROOT, coreFps)}" else ""
                    KenjiReport.watch(appContext, "$seconds с · $verdict$fps" +
                        if (memory.isNotEmpty()) " · $memory" else "")
                }
                if (!startup.isCancelled()) background.postDelayed(this, 3000)
            }
        }
        background.postDelayed(tick, 3000)
    }

    /**
     * Показывает замеченные записи без остановки игры. Дата изменения файла
     * не доказывает, что строка принадлежит текущему сеансу. Чтение ограничено,
     * журнал и выбор ядра не меняются.
     */
    private fun checkGuestFailure(appContext: android.content.Context) {
        val file = runCatching { KenjiBridge.coreLogFile(appContext) }.getOrNull() ?: return
        if (!file.isFile) return
        // Ignore clearly old files; a recent mtime still does not prove that
        // every line in an appended file belongs to the current session.
        if (file.lastModified() < playStartedAt) return
        if (coreLogPath != file.absolutePath) {
            coreLogPath = file.absolutePath
            coreLogOffset = 0L
        }
        val length = file.length()
        // Журнал пересоздан (новый запуск) — читаем с начала.
        if (length < coreLogOffset) coreLogOffset = 0L
        val incremental = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(coreLogOffset)
                val size = (length - coreLogOffset).coerceAtMost(256L * 1024L).toInt()
                val buffer = ByteArray(size)
                val read = raf.read(buffer)
                if (read <= 0) return@use ""
                // СМЕЩЕНИЕ ДВИГАЕМ ТОЛЬКО ДО ПОСЛЕДНЕГО ПЕРЕВОДА СТРОКИ.
                //
                // Ядро пишет журнал прямо сейчас, поэтому кусок почти всегда
                // обрывается посреди последней строки. Пока смещение прыгало
                // на конец файла, строка `SvcBreak reason=0x0 …` разрезалась
                // между двумя чтениями («SvcBr» + «eak reason=…») и поиск по
                // «SvcBreak» не находил её вовсе. Именно поэтому 06.11 на
                // экране было старое «кадров нет», хотя паника лежала в
                // журнале. Теперь огрызок остаётся на следующее чтение.
                val (text, used) = KernelLogFormat.completeLines(buffer, read)
                coreLogOffset += used.toLong()
                text
            }
        }.getOrDefault("")
        // В интенсивном журнале 256 КБ за тик могут не поспевать за записью.
        // Параллельно проверяем актуальный ограниченный хвост, как экран.
        val recent = runCatching { KernelLogFormat.tailOf(file, KernelLogFormat.TAIL_BYTES) }
            .getOrDefault("")
        val fresh = incremental + "\n" + recent
        if (fresh.isBlank()) return
        // Сохраняем немного сведений о состоянии контроллера заранее: к моменту
        // паники их может вытеснить подробный журнал обновлений ввода.
        KenjiCrash.hidEvidence(fresh).forEach { line ->
            if (!hidHistory.contains(line)) {
                if (hidHistory.size >= 8) hidHistory.removeFirst()
                hidHistory.addLast(line)
            }
        }
        hidCalls = KenjiCrash.mergeHidOperations(hidCalls, fresh)
        // Evidence in this file is observational, not authority to stop.
        val guestThread = fresh.contains("Unhandled exception in guest thread")
        val panic = KenjiCrash.guestPanic(fresh)
        // Третья разновидность: ядро уронило САМО СЕБЯ (.NET-исключение в
        // его собственном пуле потоков → abort → SIGABRT). Так 07.11 упал
        // наш же вызов синхронизации рендерера. Молчать об этом нельзя:
        // снаружи это выглядит как «шаг 6/7 · рендерер» без виновника.
        val hostCrash = KenjiCrash.hostCrash(fresh)
        if (!guestThread && panic.isEmpty() && hostCrash.isEmpty()) return

        // A modified file may contain records from an older run. Never stop
        // the current guest or change its selected core based on text alone.
        // Keep a bounded diagnostic observation and let the user decide.
        if (logObservation.isNotEmpty()) return
        val kind = when {
            guestThread -> "исключение гостевого потока"
            panic.isNotEmpty() -> "SvcBreak"
            else -> "исключение ядра"
        }
        val observation = "В журнале найдена запись: $kind. Не подтверждено, " +
            "что она относится к этому запуску. Плеер не останавливал игру по одной записи; " +
            "подробности сохранены в отчёте."
        logObservation = observation
        android.util.Log.w("SymbiosisKenji", "LOG_OBSERVATION_NO_AUTO_STOP kind=$kind")
        runCatching { KenjiReport.watch(appContext, observation) }
        runCatching {
            val evidence = KenjiCrash.panicEvidence(fresh).takeLast(8)
            val details = KenjiCrash.diagnosticSummary(fresh)
            KenjiBridge.crashReportFile(appContext).appendText(
                "\nНеподтверждённая связь с сеансом: $kind\n$details\n" +
                    evidence.joinToString("\n") + "\n")
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
    /** Что ядро само говорит про отрисовку — слова для экрана и отчёта. */
    private fun rendererTail(context: android.content.Context, limit: Int): List<String> =
        runCatching {
            val file = KenjiBridge.coreLogFile(context) ?: return emptyList()
            val text = KernelLogFormat.tailOf(file, KernelLogFormat.TAIL_BYTES)
            KernelLogFormat.pickRendererLines(text, RENDER_NEEDLES, limit)
        }.getOrDefault(emptyList())

    private fun coreLogTail(context: android.content.Context, lines: Int): String =
        runCatching {
            val file = KenjiBridge.coreLogFile(context) ?: return ""
            // Called every 3 seconds on the UI thread: never read the entire
            // potentially hundreds-of-MB log while the player handles input.
            KernelLogFormat.statusTail(file, lines)
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
            try {
                result = if (startup.isCancelled()) KenjiBridge.Status(false, "запуск отменён")
                    else runCatching(block).getOrElse { KenjiBridge.Status(false, it.message ?: "шаг") }
            } finally {
                latch.countDown()
            }
        }
        // A timed-out task could still execute later, after native teardown.
        // Wait off the UI thread until it has actually run or been cancelled.
        latch.await()
        return result
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (sessionLease == null || startup.isCancelled()) return
        if (width > 0 && height > 0) {
            surfaceWidth = width
            surfaceHeight = height
            KenjiBridge.inputSize(width, height)
            if (surfaceStart.changed(width, height, holder.surface.isValid)) {
                startGame(holder, width, height)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) {
            overlaySink.cancel()
            overlay.layout = overlayLayout()
            overlay.requestLayout()
            overlay.post { overlay.refreshControls() }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        benchmarkHasFocus = hasFocus
        benchmarkFocusEpoch.incrementAndGet()
        if (!hasFocus && ::overlay.isInitialized) {
            overlaySink.cancel()
            overlay.refreshControls()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // A transient surface lost before startup is not a running session.
        if (surfaceStart.destroyed()) {
            requestStop()
            if (!failed && !isFinishing && !isDestroyed) {
                fail("Поверхность игры закрыта системой. Перезапустите игру из библиотеки.")
            }
        }
    }

    @Synchronized
    private fun startInputUpdates(padId: Int): Boolean {
        if (stopThread != null || !KenjiBridge.activateInputEvents(padId)) return false
        KenjiBridge.inputSize(surfaceWidth, surfaceHeight)
        inputRunning = true
        inputThread = Thread({
            while (inputRunning) {
                if (!KenjiBridge.updateInput()) break
                try { Thread.sleep(8) } catch (_: InterruptedException) { break }
            }
            inputRunning = false
        }, "kenji-input").also { it.start() }
        return true
    }

    /** Не закрываем ядро, пока поток ввода ещё внутри его API. */
    @Synchronized
    private fun requestStop(): Thread {
        surfaceStart.stop()
        startup.cancel()
        // Queue cancellation immediately, including when initialization or
        // game loading is itself waiting for a keyboard response.
        uiSession?.stop()
        stopThread?.let { return it }
        watchdogHandler?.removeCallbacksAndMessages(null)
        watchdogThread?.quitSafely()
        watchdogHandler = null
        watchdogThread = null
        inputRunning = false
        KenjiBridge.disableInputEvents()
        val updater = inputThread
        val worker = loopThread
        updater?.interrupt()
        return Thread({
            try {
                // Drain the current cancellation before awaiting startup: a
                // native load may be waiting for that exact response.
                if (uiSession?.awaitPendingReplies() == false) {
                    requireIsolatedProcessExit = true
                    return@Thread // keep the lease; cancellation was not delivered
                }
                // Never close or unload while an initialization step is active.
                val decision = worker?.let { startup.awaitDecision() }
                updater?.join()
                KenjiBridge.drainInputEvents()
                // A watchdog tick can call the core from the UI thread. Wait
                // for any tick already in progress before signal/close/unload.
                synchronized(watchdogLock) { }
                when (decision) {
                    KenjiStartupCoordinator.Decision.ABORTED_BEFORE_LOOP -> {
                        // The binary's partial-init cleanup contract is unknown.
                        // Keep the lock and terminate only this isolated process
                        // after the activity has gone and the worker has exited.
                        requireIsolatedProcessExit = true
                    }
                    KenjiStartupCoordinator.Decision.ENTERING_LOOP -> {
                        // Signal is separate from close: the latter must wait for
                        // the actual loop thread, even if stop raced its entry.
                        val signalled = KenjiBridge.signalPlayStop()
                        worker?.join()
                        // Drain callbacks queued during the signal/join window.
                        // This still cannot prove no later guest callbacks exist.
                        if (uiSession?.awaitPendingReplies() == false) {
                            requireIsolatedProcessExit = true
                            return@Thread
                        }
                        if (!signalled || !runLoopReturnedSuccessfully || !KenjiBridge.closePlay()) {
                            requireIsolatedProcessExit = true
                        }
                    }
                    null -> Unit // no native initialization was started
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                requireIsolatedProcessExit = true
            } catch (_: Throwable) {
                requireIsolatedProcessExit = true
            }
        }, "kenji-stop").also { stopThread = it; it.start() }
    }

    private fun handlePlayerBack() {
        if (sessionLease == null) finish() else showPlayerMenu()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handlePlayerBack()
    }

    /**
     * Снятие ядра — В ФОНЕ, а не в главном потоке.
     *
     * 06.11: человек нажал «Выйти», и приложение зависло на чёрном экране.
     * Здесь был прямой вызов `unload()` в главном потоке: он ждёт нативный
     * рендер, а на телефоне с Mali это ожидание уходит в бесконечность, если
     * цикл не отдал ни одного кадра. Экран при этом уже снят — снаружи это
     * выглядит как «вышел и всё зависло».
     *
     * Окно закрывается сразу, а остановка идёт в отдельном потоке.
     * Блокировку сеанса нельзя отпускать по таймеру: если ядро ещё работает,
     * следующую игру запускать небезопасно.
     */
    override fun onDestroy() {
        val lease = sessionLease
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            systemBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            systemBackCallback = null
        }
        if (lease == null) {
            super.onDestroy()
            return
        }
        sessionLease = null
        playerMenu?.dismiss()
        playerMenu = null
        detailsDialog?.dismiss()
        detailsDialog = null
        detailsText = null
        runCatching { KenjiPlayService.stop(applicationContext) }
        val thread = loopThread
        val descriptor = pfd
        pfd = null
        val stopper = requestStop()
        Thread({
            // Wait in the background for real completion, not a timeout. If a
            // native call hangs, keep the lease: allowing another game would
            // violate exclusivity. Process death releases the OS lock.
            try {
                stopper.join()
                thread?.join()
                // Do not dlclose a core after guest execution: Android 15 ARM
                // tests saw both real cores crash seconds after dlclose even
                // when deviceCloseEmulation and the render loop had returned.
                // This player has its own :kenji process. Keep the OS lease and
                // all native handles until the OS tears that process down; it
                // releases the lock atomically on process death. Never release
                // the lease early and permit a competing session in this process.
                runCatching { descriptor?.close() }
                if (isIsolatedKenjiProcess()) {
                    if (!failed && !requireIsolatedProcessExit) {
                        runCatching { KenjiReport.markClean(applicationContext) }
                    }
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                // If isolation cannot be established, keep the lease and native
                // resources. In particular, never kill the main app process.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "kenji-teardown").start()
        super.onDestroy()
    }

    private fun leave() {
        // Сначала сообщение: если снятие ядра задержится, человек увидит, что
        // нажатие принято, а не пустой экран.
        runCatching {
            status.text = "выход из второго ядра…"
        }
        // Сигнал остановки уходит в натив: на телефоне, где цикл стоит, этот вызов
        // ждёт ответа ядра. Поэтому он тоже вне главного потока — иначе окно
        // не закроется до его возврата.
        requestStop()
        // Окно закрывается сразу; выгрузка идёт в onDestroy своим потоком.
        finish()
    }

    private fun fail(msg: String) {
        if (failed || isFinishing || isDestroyed) return
        failed = true
        playerMenu?.dismiss()
        playerMenu = null
        detailsDialog?.dismiss()
        detailsDialog = null
        detailsText = null
        requestStop()
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(22, 24, 29))
        }
        val heading = TextView(this).apply {
            text = "Второе ядро остановилось"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        }
        val details = TextView(this).apply {
            text = msg
            textSize = 16f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(details) }
        screen.addView(heading)
        screen.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        screen.addView(darkButton("Скопировать описание") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Описание остановки", msg))
            Toast.makeText(this, "Описание скопировано", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        screen.addView(darkButton("Вернуться в библиотеку") { leave() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        setContentView(screen)
        // Причина падения должна остаться и после того, как экран закроется:
        // отчёт о падении читает лаунчер и показывает его при возврате.
        runCatching {
            KenjiBridge.crashReportFile(applicationContext)
                .appendText("\nплеер: $msg\n")
        }
        // The user decides when to leave; long details remain scrollable.
    }

    private fun openRom(path: String): ParcelFileDescriptor? = runCatching {
        if (path.startsWith("/")) {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            contentResolver.openFileDescriptor(Uri.parse(path), "r")
        }
    }.getOrNull()
}
