import org.yuzu.yuzu_emu.utils.KenjiSurfaceStartGate

fun main() {
    val gate = KenjiSurfaceStartGate()
    check(!gate.changed(1920, 1080, true)) // no created callback yet
    gate.created()
    check(!gate.changed(0, 1080, true))
    check(!gate.changed(1920, 0, true))
    check(!gate.changed(-1, 1080, true))
    check(!gate.changed(1920, 1080, false))
    check(gate.changed(1920, 1080, true))
    check(!gate.changed(1080, 1920, true)) // resize isn't a second launch
    check(gate.destroyed()) // a running session needs to stop
    check(!gate.destroyed())
    gate.created()
    check(!gate.changed(1920, 1080, true)) // no restart on a stopped core

    val transient = KenjiSurfaceStartGate()
    transient.created()
    check(!transient.destroyed()) // no kernel was started
    check(!transient.changed(1920, 1080, true))
    transient.created()
    check(transient.changed(1920, 1080, true))

    val cancelled = KenjiSurfaceStartGate()
    cancelled.created()
    cancelled.stop()
    check(!cancelled.changed(1920, 1080, true))
    cancelled.created()
    check(!cancelled.changed(1920, 1080, true))
    check(!cancelled.destroyed())
    // The callback and a stop request may race across threads. No more than
    // one callback may win, and a completed stop prohibits every later start.
    repeat(100) {
        val concurrent = KenjiSurfaceStartGate()
        concurrent.created()
        val ready = java.util.concurrent.CountDownLatch(1)
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..8).map {
            Thread {
                ready.await()
                if (concurrent.changed(1280, 720, true)) winners.incrementAndGet()
            }.apply { start() }
        }
        ready.countDown()
        threads.forEach { it.join() }
        check(winners.get() == 1)
        concurrent.stop()
        check(!concurrent.changed(1280, 720, true))
    }
    println("KenjiSurfaceStartGateTest: 16 checks and 100 concurrent trials passed")
}
