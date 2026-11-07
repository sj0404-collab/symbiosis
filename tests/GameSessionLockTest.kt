import org.yuzu.yuzu_emu.utils.GameSessionLock
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private fun child(mode: String, path: File): Process = ProcessBuilder(
    File(System.getProperty("java.home"), "bin/java").path,
    "-cp", System.getProperty("java.class.path"), "GameSessionLockTestKt", mode, path.path
).redirectErrorStream(true).start()

private fun childResult(mode: String, path: File): String {
    val process = child(mode, path)
    check(process.waitFor(10, TimeUnit.SECONDS)) { "child timed out" }
    check(process.exitValue() == 0) { "child failed: ${process.inputStream.bufferedReader().readText()}" }
    return process.inputStream.bufferedReader().readText().trim()
}

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        when (val result = GameSessionLock.tryAcquire(File(args[1]))) {
            is GameSessionLock.Result.Acquired -> {
                println("ACQUIRED")
                System.out.flush()
                if (args[0] == "hold") Thread.sleep(60_000)
                result.lease.close()
            }
            GameSessionLock.Result.Busy -> println("BUSY")
            is GameSessionLock.Result.Failed -> error("lock failed")
        }
        return
    }

    val dir = Files.createTempDirectory("symbiosis-lock-test").toFile()
    try {
        val path = File(dir, GameSessionLock.FILE_NAME)
        val a = (GameSessionLock.tryAcquire(path) as GameSessionLock.Result.Acquired).lease
        check(GameSessionLock.tryAcquire(path) is GameSessionLock.Result.Busy)
        // A failed same-process attempt must not accidentally release the OS lock.
        check(childResult("try", path) == "BUSY")
        a.close()
        a.close() // idempotent
        check(childResult("try", path) == "ACQUIRED")

        val holder = child("hold", path)
        try {
            check(holder.inputStream.bufferedReader().readLine() == "ACQUIRED")
            check(GameSessionLock.tryAcquire(path) is GameSessionLock.Result.Busy)
            check(childResult("try", path) == "BUSY")
        } finally {
            holder.destroyForcibly()
            check(holder.waitFor(10, TimeUnit.SECONDS))
        }
        val afterCrash = (GameSessionLock.tryAcquire(path) as GameSessionLock.Result.Acquired).lease
        check(childResult("try", path) == "BUSY")
        afterCrash.close()
        check(childResult("try", path) == "ACQUIRED")
        check(GameSessionLock.tryAcquire(dir) is GameSessionLock.Result.Failed)

        repeat(50) {
            val go = java.util.concurrent.CountDownLatch(1)
            val attempted = java.util.concurrent.CountDownLatch(8)
            val winners = java.util.concurrent.atomic.AtomicInteger()
            val threads = (1..8).map {
                Thread {
                    go.await()
                    val result = GameSessionLock.tryAcquire(path)
                    if (result is GameSessionLock.Result.Acquired) winners.incrementAndGet()
                    attempted.countDown()
                    if (result is GameSessionLock.Result.Acquired) {
                        attempted.await()
                        result.lease.close()
                    }
                }.apply { start() }
            }
            go.countDown()
            threads.forEach { it.join() }
            check(winners.get() == 1)
        }
        println("GameSessionLockTest: cross-process, crash-release and 50 concurrent trials passed")
    } finally {
        dir.deleteRecursively()
    }
}
