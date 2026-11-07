// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * A non-blocking OS lock shared by the two Android processes. Never delete or
 * replace the lock file: all contenders must lock the same inode. Closing an
 * unrelated fd for the same file can release process-owned POSIX locks, so
 * duplicate acquisition in this process is rejected BEFORE opening another fd.
 * A lease belongs to the native session, not to an Activity or its screen.
 */
class GameSessionLock private constructor(
    private val key: String,
    private val file: RandomAccessFile,
    private val lock: FileLock
) : AutoCloseable {
    private var closed = false

    override fun close() {
        synchronized(GameSessionLock::class.java) {
            if (closed) return
            closed = true
            try {
                lock.release()
            } finally {
                try { file.close() } finally { held.remove(key) }
            }
        }
    }

    sealed class Result {
        class Acquired(val lease: GameSessionLock) : Result()
        object Busy : Result()
        class Failed(val cause: Throwable) : Result()
    }

    companion object {
        private val held = mutableMapOf<String, GameSessionLock>()
        const val FILE_NAME = "symbiosis-game-session.lock"

        @JvmStatic
        fun tryAcquire(path: File): Result = synchronized(GameSessionLock::class.java) {
            val key = try { path.canonicalPath } catch (t: Exception) { return@synchronized Result.Failed(t) }
            if (key in held) return@synchronized Result.Busy
            val file = try { RandomAccessFile(path, "rw") } catch (t: Exception) {
                return@synchronized Result.Failed(t)
            }
            try {
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) {
                    file.close()
                    Result.Busy
                } else {
                    val lease = GameSessionLock(key, file, lock)
                    // Retain the descriptor until explicit close, even if an
                    // Activity or a failed teardown loses its last reference.
                    held[key] = lease
                    Result.Acquired(lease)
                }
            } catch (t: Exception) {
                runCatching { file.close() }
                Result.Failed(t)
            }
        }
    }
}
