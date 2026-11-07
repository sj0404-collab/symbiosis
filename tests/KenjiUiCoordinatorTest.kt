// Host-only coordinator tests. The core, Android UI and JNI are synthetic here.
import org.yuzu.yuzu_emu.utils.KenjiUiCoordinator
import org.yuzu.yuzu_emu.utils.KenjiUiRequest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

private fun request(text: String = "initial") = KenjiUiRequest(
    title = "Title", message = "Header", watermark = "Hint", type = 2,
    min = 1, max = 8, mode = 0, subtitle = "Subtitle", initialText = text
)
private data class Answer(val ok: Boolean, val text: String, val thread: String)
private class Harness {
    val main = LinkedBlockingQueue<() -> Unit>()
    val shown = LinkedBlockingQueue<Pair<Long, KenjiUiRequest>>()
    val answers = LinkedBlockingQueue<Answer>()
    val errors = LinkedBlockingQueue<String>()
    var dismissed = 0
    val coordinator = KenjiUiCoordinator(
        postMain = { main.put(it) },
        present = { id, req -> shown.put(id to req) },
        dismiss = { dismissed++ },
        reply = { ok, bytes -> answers.put(Answer(ok, String(bytes, Charsets.UTF_8), Thread.currentThread().name)); true },
        onError = { errors.put(it) },
    )
    fun pump() { while (true) (main.poll() ?: break).invoke() }
    fun takeAnswer() = checkNotNull(answers.poll(3, TimeUnit.SECONDS))
    fun finish() { coordinator.stop(); coordinator.closeAfterGuestStopped(); pump() }
}
fun main() {
    run {
        val h = Harness()
        h.coordinator.request(request())
        h.pump()
        val first = checkNotNull(h.shown.poll())
        check(first.second.initialText == "initial")
        check(!h.coordinator.answer(first.first, true, ""))
        check(!h.coordinator.answer(first.first, true, "a\u0000b"))
        check(!h.coordinator.answer(first.first + 1, true, "ok"))
        check(h.coordinator.answer(first.first, true, "Привет😀"))
        check(!h.coordinator.answer(first.first, true, "double"))
        val result = h.takeAnswer()
        check(result.ok && result.text == "Привет😀" && result.thread == "kenji-ui-reply")
        h.finish()
        check(h.answers.isEmpty() && h.dismissed == 1)
    }
    run {
        val h = Harness()
        h.coordinator.request(request("one")); h.coordinator.request(request("two"))
        h.pump()
        val first = checkNotNull(h.shown.poll())
        check(h.shown.isEmpty())
        check(h.coordinator.answer(first.first, false, "unused"))
        check(!h.takeAnswer().ok)
        val work = checkNotNull(h.main.poll(3, TimeUnit.SECONDS)); work()
        val second = checkNotNull(h.shown.poll())
        check(second.second.initialText == "two" && second.first != first.first)
        h.finish()
        check(!h.takeAnswer().ok && h.answers.isEmpty())
    }
    run {
        val h = Harness()
        h.coordinator.request(request())
        h.coordinator.stop() // before posted main-thread presentation runs
        h.coordinator.closeAfterGuestStopped()
        h.pump()
        check(h.shown.isEmpty() && h.dismissed == 1)
        check(!h.takeAnswer().ok && h.answers.isEmpty())
    }
    run {
        val h = Harness()
        h.coordinator.request(request().copy(type = 9))
        check(!h.takeAnswer().ok)
        h.pump(); check(h.errors.isNotEmpty())
        h.finish()
    }
    repeat(100) {
        val h = Harness()
        h.coordinator.request(request()); h.pump()
        val id = checkNotNull(h.shown.poll()).first
        val start = CountDownLatch(1)
        val answer = Thread { start.await(); h.coordinator.answer(id, true, "ok") }
        val stop = Thread { start.await(); h.coordinator.stop() }
        answer.start(); stop.start(); start.countDown(); answer.join(); stop.join()
        h.coordinator.closeAfterGuestStopped(); h.pump()
        val result = h.takeAnswer()
        check((result.ok && result.text == "ok") || (!result.ok && result.text.isEmpty()))
        check(h.answers.isEmpty())
    }
    println("KenjiUiCoordinator: queue, cancellation and 100 races passed")
}
