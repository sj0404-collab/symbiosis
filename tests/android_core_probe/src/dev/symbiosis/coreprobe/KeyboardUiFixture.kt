package dev.symbiosis.coreprobe

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import java.util.concurrent.atomic.AtomicInteger
import org.kenjinx.android.KenjinxNative
import org.yuzu.yuzu_emu.activities.KenjiUiPresenter
import org.yuzu.yuzu_emu.utils.KenjiBridge
import org.yuzu.yuzu_emu.utils.KenjiUiSession
import org.yuzu.yuzu_emu.utils.KenjiUiRequest

/** Test-only wiring: actual candidate presenter/coordinator and real JNI.
 * Button input is automated. This object is never compiled into release. */
object KeyboardUiFixture {
    @Volatile private var coordinator: KenjiUiSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val overlapCallbacks = AtomicInteger()
    @Volatile private var overlapping = false
    private fun findEdit(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findEdit(view.getChildAt(i))?.let { return it }
        }
        return null
    }
    @JvmStatic fun configure(activity: Activity, bridge: KenjiBridge, stopOnFirst: Boolean, overlap: Boolean, onStop: Runnable?): Boolean {
        overlapping = overlap
        overlapCallbacks.set(0)
        lateinit var presenter: KenjiUiPresenter
        lateinit var active: KenjiUiSession
        val dialogs = AtomicInteger()
        presenter = KenjiUiPresenter(activity) { id, accepted, text -> active.answer(id, accepted, text) }
        active = KenjiUiSession(
            postMain = { task -> main.post { task() } },
            present = { id, request ->
                presenter.show(id, request)
                val number = dialogs.incrementAndGet()
                Log.i("SymEngineStage", "UI_REAL_COPIED title=${request.title} message=${request.message} watermark=${request.watermark} subtitle=${request.subtitle} initial=${request.initialText} type=${request.type} min=${request.min} max=${request.max} mode=${request.mode}")
                Log.i("SymEngineStage", "UI_REAL_DIALOG title=${request.title} initial=${request.initialText}")
                var waits = 0
                lateinit var task: Runnable
                task = Runnable {
                    if (overlap && number == 1 && overlapCallbacks.get() < 2 && waits++ < 24) {
                        main.postDelayed(task, 250)
                        return@Runnable
                    }
                    try {
                        val field = KenjiUiPresenter::class.java.getDeclaredField("dialog")
                        field.isAccessible = true
                        val dialog = field.get(presenter) as AlertDialog
                        check(dialog.isShowing)
                        val input = findEdit(dialog.window!!.decorView)
                        if (overlap && stopOnFirst) {
                            check(overlapCallbacks.get() == 2)
                            active.stop()
                            Log.i("SymEngineStage", "UI_OVERLAP_STOP callbacks=${overlapCallbacks.get()}")
                            onStop?.run()
                        } else if (overlap) {
                            val seed = if (request.type == 1) "MESSAGE" else checkNotNull(input).text.toString()
                            check(seed == "SeedA" || seed == "SeedB" || seed == "MESSAGE")
                            if (input != null) input.setText(if (seed == "SeedA") "AnswerA" else "AnswerB")
                            Log.i("SymEngineStage", "UI_OVERLAP_CLICK seed=$seed callbacks=${overlapCallbacks.get()}")
                            check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                        } else if (number == 1 && stopOnFirst) {
                            check(checkNotNull(input).text.toString() == "Seed")
                            active.stop()
                            Log.i("SymEngineStage", "UI_REAL_STOP_REQUESTED")
                            onStop?.run()
                        } else if (number == 1) {
                            check(checkNotNull(input).text.toString() == "Seed")
                            input!!.setText("")
                            check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                            check(input!!.error != null && dialog.isShowing)
                            Log.i("SymEngineStage", "UI_REAL_VALIDATION_OK")
                            input!!.setText("Привет🎮")
                            check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                            Log.i("SymEngineStage", "UI_REAL_CLICK_OK")
                        } else if (number == 2) {
                            check(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
                            Log.i("SymEngineStage", "UI_REAL_CANCEL_OK")
                        } else error("unexpected dialog number $number")
                    } catch (t: Throwable) {
                        Log.e("SymEngineStage", "UI_REAL_FAIL dialog", t)
                    }
                }
                main.postDelayed(task, if (overlap && number > 1) 2000 else 250)
            },
            dismiss = { presenter.dismiss() },
            activate = { bridge.nativeUiActivate() },
            install = { _ -> KeyboardFixture.configure(bridge, 0, true) },
            setup = { bridge.nativeUiSetup() },
            respond = { generation, accepted, bytes ->
                // Deterministically hold the cancellation worker while the
                // teardown worker tries to advance in the race scenario.
                if (stopOnFirst && onStop != null) Thread.sleep(400)
                val ok = bridge.nativeUiRespond(generation, accepted, bytes)
                Log.i("SymEngineStage", "UI_REAL_RESPONSE accepted=$accepted result=$ok")
                ok
            },
            onError = { Log.e("SymEngineStage", "UI_REAL_FAIL $it") },
        )
        coordinator = active
        return active.start()
    }
    @JvmStatic fun awaitPendingReplies() {
        check(checkNotNull(coordinator).awaitPendingReplies())
        Log.i("SymEngineStage", "UI_REAL_CANCEL_DRAINED")
    }
    @JvmStatic fun closeAfterDeviceStopped() {
        checkNotNull(coordinator).closeAfterGuestStopped()
        Log.i("SymEngineStage", "UI_REAL_REPLY_WORKER_STOPPED")
    }
    @JvmStatic fun request(title: Long, message: Long, watermark: Long, type: Int,
            min: Int, max: Int, mode: Int, subtitle: Long, initial: Long) {
        try {
            if (overlapping) Log.i("SymEngineStage", "UI_OVERLAP_CALLBACK count=${overlapCallbacks.incrementAndGet()}")
            // Production dispatcher synchronously copies all native pointers.
            checkNotNull(coordinator).onRequest(title, message, watermark, type,
                min, max, mode, subtitle, initial)
        } catch (t: Throwable) { Log.e("SymEngineStage", "UI_REAL_FAIL callback", t) }
    }
}
