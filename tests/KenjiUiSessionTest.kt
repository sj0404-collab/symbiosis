// Host model only. JNI pointer lifetimes and native shutdown require Android.
import org.yuzu.yuzu_emu.utils.KenjiUiSession
import org.kenjinx.android.KenjinxNative
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private fun session(activate: () -> Long, install: (KenjinxNative.UiCallback) -> Unit,
                    setup: () -> Boolean): KenjiUiSession = KenjiUiSession(
    postMain = { it() }, present = { _, _ -> error("unexpected dialog") }, dismiss = {},
    onError = { error(it) }, activate = activate, install = install, setup = setup,
    respond = { _, _, _ -> error("unexpected response") },
)
private fun wait(l: CountDownLatch) = check(l.await(3, TimeUnit.SECONDS))
fun main() {
    run {
        val calls = mutableListOf<String>()
        val s = session({ calls.add("activate"); 1 }, { calls.add("install") },
            { calls.add("setup"); true })
        s.stop()
        check(!s.start() && calls.isEmpty())
        s.closeAfterGuestStopped()
    }
    run {
        val calls = mutableListOf<String>()
        val s = session({ calls.add("activate"); 1 }, { calls.add("install") },
            { calls.add("setup"); true })
        check(s.start())
        check(!s.start())
        check(calls == listOf("activate", "install", "setup"))
        s.stop(); s.closeAfterGuestStopped()
    }
    run {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val installed = AtomicBoolean(false); val setup = AtomicBoolean(false)
        val s = session({ entered.countDown(); wait(release); 1 }, { installed.set(true) },
            { setup.set(true); true })
        var result = true
        val worker = Thread { result = s.start() }
        worker.start(); wait(entered); s.stop(); release.countDown(); worker.join(3000)
        check(!worker.isAlive && !result && installed.get() && !setup.get())
        s.closeAfterGuestStopped()
    }
    run {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val s = session({ 1 }, {}, { entered.countDown(); wait(release); true })
        var result = true
        val worker = Thread { result = s.start() }
        worker.start(); wait(entered); s.stop(); release.countDown(); worker.join(3000)
        check(!worker.isAlive && !result)
        s.closeAfterGuestStopped()
    }
    run {
        var installed = false
        val s = session({ 0 }, { installed = true }, { error("unexpected setup") })
        check(!s.start() && !installed)
        s.stop(); s.closeAfterGuestStopped()
    }
    println("KenjiUiSession: start/stop ordering and setup races passed (host model)")
}
