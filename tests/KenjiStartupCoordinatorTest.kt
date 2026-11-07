import org.yuzu.yuzu_emu.utils.KenjiStartupCoordinator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val cancelled = KenjiStartupCoordinator()
    cancelled.cancel()
    check(cancelled.isCancelled())
    check(!cancelled.beginLoop())
    check(cancelled.awaitDecision() == KenjiStartupCoordinator.Decision.ABORTED_BEFORE_LOOP)
    cancelled.initializationEnded()

    val earlyReturn = KenjiStartupCoordinator()
    earlyReturn.initializationEnded()
    check(earlyReturn.awaitDecision() == KenjiStartupCoordinator.Decision.ABORTED_BEFORE_LOOP)

    val waiting = KenjiStartupCoordinator()
    val waiterEntered = CountDownLatch(1)
    val waiterReturned = CountDownLatch(1)
    val stopper = Thread {
        waiterEntered.countDown()
        check(waiting.awaitDecision() == KenjiStartupCoordinator.Decision.ABORTED_BEFORE_LOOP)
        waiterReturned.countDown()
    }.apply { start() }
    check(waiterEntered.await(2, TimeUnit.SECONDS))
    waiting.cancel()
    check(!waiterReturned.await(50, TimeUnit.MILLISECONDS)) // initializer is still inside a step
    waiting.initializationEnded()
    check(waiterReturned.await(2, TimeUnit.SECONDS))
    stopper.join()

    // Simulate the host's sequencing around a blocked native init step.
    val beforeLoop = KenjiStartupCoordinator()
    val inInit = CountDownLatch(1)
    val allowInitReturn = CountDownLatch(1)
    val aborted = CountDownLatch(1)
    val initWorker = Thread {
        try {
            inInit.countDown()
            allowInitReturn.await()
            if (!beforeLoop.isCancelled()) beforeLoop.beginLoop()
        } finally { beforeLoop.initializationEnded() }
    }.apply { start() }
    check(inInit.await(2, TimeUnit.SECONDS))
    val earlyStop = Thread {
        beforeLoop.cancel()
        check(beforeLoop.awaitDecision() == KenjiStartupCoordinator.Decision.ABORTED_BEFORE_LOOP)
        aborted.countDown()
    }.apply { start() }
    check(!aborted.await(50, TimeUnit.MILLISECONDS))
    allowInitReturn.countDown()
    check(aborted.await(2, TimeUnit.SECONDS))
    initWorker.join(); earlyStop.join()

    // Signal may race loop entry. It must never be followed by close until the
    // worker itself returns, even when signal precedes the actual loop body.
    val inLoop = KenjiStartupCoordinator()
    val committed = CountDownLatch(1)
    val signal = CountDownLatch(1)
    val allowLoopReturn = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val loopWorker = Thread {
        try {
            check(inLoop.beginLoop())
            committed.countDown()
            signal.await()
            allowLoopReturn.await()
        } finally { inLoop.initializationEnded() }
    }.apply { start() }
    check(committed.await(2, TimeUnit.SECONDS))
    val loopStop = Thread {
        inLoop.cancel()
        check(inLoop.awaitDecision() == KenjiStartupCoordinator.Decision.ENTERING_LOOP)
        signal.countDown()
        loopWorker.join()
        closed.countDown()
    }.apply { start() }
    check(signal.await(2, TimeUnit.SECONDS))
    check(!closed.await(50, TimeUnit.MILLISECONDS))
    allowLoopReturn.countDown()
    check(closed.await(2, TimeUnit.SECONDS))
    loopWorker.join(); loopStop.join()

    repeat(200) {
        val coordinator = KenjiStartupCoordinator()
        val go = CountDownLatch(1)
        val result = AtomicInteger(-1)
        val init = Thread {
            go.await()
            result.set(if (coordinator.beginLoop()) 1 else 0)
            coordinator.initializationEnded()
        }
        val stop = Thread { go.await(); coordinator.cancel() }
        init.start(); stop.start(); go.countDown(); init.join(); stop.join()
        val decision = coordinator.awaitDecision()
        check(coordinator.isCancelled())
        check((result.get() == 1) == (decision == KenjiStartupCoordinator.Decision.ENTERING_LOOP))
    }
    println("KenjiStartupCoordinatorTest: cancellation, early exit and 200 race trials passed")
}
