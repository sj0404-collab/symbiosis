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
    run {
        // A real Android/core test demonstrated that an error-message callback
        // is already complete; replying to it can wake an unrelated keyboard.
        val h = Harness()
        h.coordinator.request(request().copy(type = 1, min = 0, max = 0))
        h.coordinator.request(request("waiting-keyboard"))
        h.pump()
        val message = checkNotNull(h.shown.poll())
        check(h.coordinator.answer(message.first, true, ""))
        h.pump()
        val keyboard = checkNotNull(h.shown.poll())
        check(keyboard.second.initialText == "waiting-keyboard")
        check(h.answers.isEmpty())
        check(h.coordinator.answer(keyboard.first, true, "correct"))
        val result = h.takeAnswer()
        check(result.ok && result.text == "correct")
        h.finish()
        check(h.answers.isEmpty())
    }
    run {
        // Stopping must not answer either an active or queued notification.
        val h = Harness()
        h.coordinator.request(request().copy(type = 1, min = 0, max = 0))
        h.coordinator.request(request().copy(type = 1, min = 0, max = 0))
        h.coordinator.request(request())
        h.coordinator.stop()
        h.coordinator.request(request().copy(type = 1, min = 0, max = 0))
        h.coordinator.closeAfterGuestStopped()
        h.pump()
        check(!h.takeAnswer().ok && h.answers.isEmpty())
    }
    run {
        // Two teardown callers must both wait for an in-flight native reply.
        // This models executor draining only, not real guest-thread shutdown.
        val main = LinkedBlockingQueue<() -> Unit>()
        val shown = LinkedBlockingQueue<Long>()
        val enteredReply = CountDownLatch(1)
        val releaseReply = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val coordinator = KenjiUiCoordinator(
            postMain = { main.put(it) },
            present = { id, _ -> shown.put(id) }, dismiss = {},
            reply = { _, _ -> enteredReply.countDown(); releaseReply.await(); true },
            onError = { error(it) },
        )
        coordinator.request(request())
        checkNotNull(main.poll()).invoke()
        check(coordinator.answer(checkNotNull(shown.poll()), true, "ok"))
        check(enteredReply.await(3, TimeUnit.SECONDS))
        coordinator.stop()
        val first = Thread { coordinator.closeAfterGuestStopped(); firstDone.countDown() }
        val second = Thread { coordinator.closeAfterGuestStopped(); secondDone.countDown() }
        try {
            first.start()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (first.state != Thread.State.TIMED_WAITING && System.nanoTime() < until) Thread.yield()
            check(first.state == Thread.State.TIMED_WAITING)
            second.start()
            check(!firstDone.await(200, TimeUnit.MILLISECONDS))
            check(!secondDone.await(200, TimeUnit.MILLISECONDS))
        } finally {
            releaseReply.countDown()
            first.join(3000)
            if (second.state != Thread.State.NEW) second.join(3000)
        }
        check(firstDone.count == 0L && secondDone.count == 0L)
    }
    run {
        // A signal/close worker must wait for the queued cancellation; otherwise
        // nativeClosePlay can disable the gate before the worker sends it.
        val enteredReply = CountDownLatch(1)
        val releaseReply = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val coordinator = KenjiUiCoordinator(
            postMain = {}, present = { _, _ -> }, dismiss = {},
            reply = { _, _ -> enteredReply.countDown(); releaseReply.await(); true },
            onError = { error(it) },
        )
        coordinator.request(request()); coordinator.stop()
        check(enteredReply.await(3, TimeUnit.SECONDS))
        val thread = Thread { coordinator.awaitPendingReplies(); drained.countDown() }
        try {
            thread.start()
            check(!drained.await(200, TimeUnit.MILLISECONDS))
        } finally {
            releaseReply.countDown(); thread.join(3000)
        }
        check(drained.count == 0L)
        coordinator.closeAfterGuestStopped()
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
