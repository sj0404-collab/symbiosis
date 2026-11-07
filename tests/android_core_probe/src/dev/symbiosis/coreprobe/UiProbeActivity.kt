package dev.symbiosis.coreprobe

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presenter = KenjiUiPresenter(this) { id, accepted, value -> coordinator.answer(id, accepted, value) }
        coordinator = KenjiUiCoordinator(
            postMain = { task -> main.post(task) },
            present = { id, item ->
                presenter.show(id, item)
                Log.i("SymUiProbe", "UI_PROBE_DIALOG number=${replies.get() + 1}")
                // Calls the coordinator directly: this checks dialog creation,
                // not touchscreen delivery or real guest keyboard behaviour.
                main.postDelayed({
                    if (replies.get() == 0) {
                        if (!coordinator.answer(id, true, "Привет🎮")) markFail("answer rejected")
                        presenter.dismiss()
                    } else {
                        coordinator.stop()
                    }
                }, 250)
            },
            dismiss = { presenter.dismiss() },
            reply = { accepted, bytes ->
                val number = replies.incrementAndGet()
                val text = String(bytes, StandardCharsets.UTF_8)
                if (number == 1 && accepted && text == "Привет🎮") {
                    main.post { coordinator.request(request()) }
                } else if (number == 2 && !accepted && text.isEmpty()) {
                    main.post {
                        Log.i("SymUiProbe", "UI_PROBE_OK dialogs=2 response=unicode cancel=true")
                        Thread { coordinator.closeAfterGuestStopped() }.start()
                        finish()
                    }
                } else {
                    markFail("unexpected response $number")
                }
                true
            },
            onError = { markFail(it) },
        )
        coordinator.request(request())
    }
}
