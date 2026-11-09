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
import org.yuzu.yuzu_emu.utils.KenjiUiCoordinator
import org.yuzu.yuzu_emu.utils.KenjiUiRequest
import org.yuzu.yuzu_emu.utils.KenjiUiText

/** Test-only wiring: actual candidate presenter/coordinator and real JNI.
 * Button input is automated. This object is never compiled into release. */
object KeyboardUiFixture {
    @Volatile private var coordinator: KenjiUiCoordinator? = null
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
    @JvmStatic fun configure(activity: Activity, bridge: KenjiBridge, generation: Long, stopOnFirst: Boolean, overlap: Boolean) {
        overlapping = overlap
        overlapCallbacks.set(0)
        lateinit var presenter: KenjiUiPresenter
        lateinit var active: KenjiUiCoordinator
        val dialogs = AtomicInteger()
        presenter = KenjiUiPresenter(activity) { id, accepted, text -> active.answer(id, accepted, text) }
        active = KenjiUiCoordinator(
            postMain = { task -> main.post { task() } },
            present = { id, request ->
                presenter.show(id, request)
                val number = dialogs.incrementAndGet()
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
                        if (overlap) {
                            val seed = if (request.type == 1) "MESSAGE" else checkNotNull(input).text.toString()
                            check(seed == "SeedA" || seed == "SeedB" || seed == "MESSAGE")
                            if (input != null) input.setText(if (seed == "SeedA") "AnswerA" else "AnswerB")
                            Log.i("SymEngineStage", "UI_OVERLAP_CLICK seed=$seed callbacks=${overlapCallbacks.get()}")
                            check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                        } else if (number == 1 && stopOnFirst) {
                            check(checkNotNull(input).text.toString() == "Seed")
                            active.stop()
                            Log.i("SymEngineStage", "UI_REAL_STOP_REQUESTED")
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
            reply = { accepted, bytes ->
                val ok = bridge.nativeUiRespond(generation, accepted, bytes)
                Log.i("SymEngineStage", "UI_REAL_RESPONSE accepted=$accepted result=$ok")
                ok
            },
            onError = { Log.e("SymEngineStage", "UI_REAL_FAIL $it") },
        )
        coordinator = active
    }
    @JvmStatic fun closeAfterDeviceStopped() {
        checkNotNull(coordinator).closeAfterGuestStopped()
        Log.i("SymEngineStage", "UI_REAL_REPLY_WORKER_STOPPED")
    }
    private fun copy(pointer: Long): String {
        val method = KenjinxNative::class.java.getMethod("nativeCopyUiText", java.lang.Long.TYPE)
        val bytes = method.invoke(null, pointer) as? ByteArray ?: error("native text copy failed")
        return KenjiUiText.decode(bytes) ?: error("text is not valid UTF-8")
    }
    @JvmStatic fun request(title: Long, message: Long, watermark: Long, type: Int,
            min: Int, max: Int, mode: Int, subtitle: Long, initial: Long) {
        try {
            // Copy synchronously; guest-owned pointers may expire on return.
            val request = KenjiUiRequest(copy(title), copy(message), copy(watermark), type,
                min, max, mode, copy(subtitle), copy(initial))
            if (overlapping) Log.i("SymEngineStage", "UI_OVERLAP_CALLBACK count=${overlapCallbacks.incrementAndGet()} seed=${request.initialText}")
            Log.i("SymEngineStage", "UI_REAL_COPIED title=${request.title} message=${request.message} watermark=${request.watermark} subtitle=${request.subtitle} initial=${request.initialText} type=$type min=$min max=$max mode=$mode")
            checkNotNull(coordinator).request(request)
        } catch (t: Throwable) { Log.e("SymEngineStage", "UI_REAL_FAIL callback copy", t) }
    }
}
