// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.yuzu.yuzu_emu.activities.KenjiPlayerActivity

/**
 * Уведомление «второе ядро: играет» — не украшение, а способ не быть убитым.
 *
 * ПОЧЕМУ ЭТО ВАЖНО. Процесс `:kenji` держит 55 МБ ядра и до 4 ГБ гостевой
 * памяти. Для системы это первый кандидат на выгрузку, как только память
 * заканчивается: плеер пропадает вместе с игрой, и человек видит возврат в
 * список игр без объяснения. Фоновый сервис поднимает приоритет процесса до
 * того уровня, на котором система сначала предупреждает, а не выгружает.
 *
 * Второе, зачем нужен сервис: игра идёт в отдельном процессе, и вернуться в
 * неё можно только через уведомление — иначе после случайного ухода остаётся
 * лишь «недавние приложения».
 *
 * Все вызовы обёрнуты: на Android 14+ тип сервиса обязателен, и если политика
 * системы его не примет, плеер обязан продолжить работу без уведомления, а не
 * упасть на старте.
 */
class KenjiPlayService : Service() {

    companion object {
        private const val CHANNEL = "symbiosis_kenji_play"
        private const val ID = 4211
        const val ACTION_STOP = "org.yuzu.yuzu_emu.KENJI_PLAY_STOP"

        /** Поднять процесс в приоритете. Молча, если система не позволила. */
        fun start(context: Context, title: String) {
            runCatching {
                val intent = Intent(context, KenjiPlayService::class.java)
                    .putExtra("title", title)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KenjiPlayService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra("title").orEmpty().ifBlank { "игра" }
        val ok = runCatching { startForeground(ID, buildNotification(title)) }.isSuccess
        if (!ok) {
            // Система не приняла тип сервиса — не навязываемся и не падаем.
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Второе ядро", NotificationManager.IMPORTANCE_LOW)
            )
        }

        // Возврат в игру — по нажатию. FLAG_ACTIVITY_SINGLE_TOP: уже открытый
        // плеер не пересоздаётся, иначе повторное нажатие убило бы игру.
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, KenjiPlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, KenjiPlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Второе ядро: $title")
            .setContentText("Игра идёт. Нажмите, чтобы вернуться.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stop)
            .setOngoing(true)
            .build()
    }
}
