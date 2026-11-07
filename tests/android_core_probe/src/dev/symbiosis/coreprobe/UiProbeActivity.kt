package dev.symbiosis.coreprobe

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.yuzu.yuzu_emu.activities.KenjiUiPresenter
import org.yuzu.yuzu_emu.utils.KenjiUiCoordinator
import org.yuzu.yuzu_emu.utils.KenjiUiRequest
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** Disposable test app only. This is not a game, core or release screen. */
class UiProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var presenter: KenjiUiPresenter
    private lateinit var coordinator: KenjiUiCoordinator
    private val replies = AtomicInteger()
    private fun markFail(message: String) { Log.e("SymUiProbe", "UI_PROBE_FAIL $message") }
    private fun request() = KenjiUiRequest("Тест", "Сообщение", "Подсказка", 2,
        1, 12, 0, "Подзаголовок", "Имя")
    private fun inputIn(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            inputIn(view.getChildAt(i))?.let { return it }
        }
        return null
    }
    private fun currentDialog(): AlertDialog {
        // Test-only reflection of our own field, not an Android hidden API.
        val field = KenjiUiPresenter::class.java.getDeclaredField("dialog")
        field.isAccessible = true
        return field.get(presenter) as AlertDialog
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presenter = KenjiUiPresenter(this) { id, accepted, value -> coordinator.answer(id, accepted, value) }
        coordinator = KenjiUiCoordinator(
            postMain = { task -> main.post(task) },
            present = { id, item ->
                presenter.show(id, item)
                val number = replies.get() + 1
                Log.i("SymUiProbe", "UI_PROBE_DIALOG number=$number")
                // performClick exercises the actual button listeners, without
                // claiming physical touch or IME input was tested.
                main.postDelayed({
                    try {
                        val dialog = currentDialog()
                        check(dialog.isShowing)
                        if (number == 1) {
                            val input = checkNotNull(inputIn(dialog.window!!.decorView))
                            input.setText("")
                            check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                            check(input.error != null && dialog.isShowing && replies.get() == 0)
                            input.setText("Привет🎮")
                            check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                        } else if (number == 2) {
                            check(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
                        } else if (number == 3) {
                            coordinator.stop()
                        } else error("unexpected dialog count")
                    } catch (_: Throwable) {
                        markFail("dialog/button assertion failed at $number")
                    }
                }, 250)
            },
            dismiss = { presenter.dismiss() },
            reply = { accepted, bytes ->
                val number = replies.incrementAndGet()
                val text = String(bytes, StandardCharsets.UTF_8)
                when {
                    number == 1 && accepted && text == "Привет🎮" -> main.post { coordinator.request(request()) }
                    number == 2 && !accepted && text.isEmpty() -> main.post { coordinator.request(request()) }
                    number == 3 && !accepted && text.isEmpty() -> main.post {
                        Log.i("SymUiProbe", "UI_PROBE_OK dialogs=3 buttons=true unicode=true stop=true")
                        Thread { coordinator.closeAfterGuestStopped() }.start()
                        finish()
                    }
                    else -> markFail("unexpected response $number")
                }
                true
            },
            onError = { markFail(it) },
        )
        coordinator.request(request())
    }
}
