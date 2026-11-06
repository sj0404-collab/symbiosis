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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.yuzu.yuzu_emu.R

/**
 * Распаковка NSZ/XCZ/NCZ как видимая работа, а не фоновый поток.
 *
 * Раньше очередь жила в обычном `Thread` внутри процесса. Следствия на
 * телефоне ровно те, на которые жаловались: уведомления в шторке нет,
 * прогресса не видно, а стоит уйти с экрана — система прибивает процесс и
 * распаковка обрывается на середине, оставив недописанный файл.
 *
 * Здесь работа объявлена системе: foreground-служба с уведомлением, в
 * котором видно, какой файл распаковывается и сколько осталось. Уйти из
 * приложения можно — работа продолжится, а уведомление останется на месте.
 *
 * Тип `specialUse` выбран не для красоты: у сборки уже есть разрешение
 * FOREGROUND_SERVICE_SPECIAL_USE, а `dataSync` на Android 14 требует
 * отдельного разрешения и режет время работы. У распаковки нет ни сети, ни
 * геолокации, и объявлять её чем-то ещё значило бы просить лишние права.
 */
class ConverterService : Service() {

    companion object {
        const val CHANNEL_ID = "symbiosis_converter"
        private const val NOTIFICATION_ID = 4211
        private const val ACTION_CANCEL = "org.yuzu.yuzu_emu.CONVERTER_CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, ConverterService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    @Volatile
    private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            Converter.requestStop()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(Converter.progressText(), 0, 0))

        Thread({
            try {
                Converter.runQueue(applicationContext) { index, total, name ->
                    if (cancelled) return@runQueue
                    val text = if (total > 1) "$name · $index из $total" else name
                    notify(buildNotification("распаковка: $text", index, total))
                }
            } finally {
                finish()
            }
        }, "converter-service").start()

        return START_NOT_STICKY
    }

    /**
     * Итог остаётся в шторке.
     *
     * Отдельное уведомление, а не «просто исчезло»: закончилось ли дело —
     * это тоже ответ, и пользователь не должен угадывать по пустому экрану.
     */
    private fun finish() {
        val done = Converter.lastResult
        val title = when {
            cancelled -> getString(R.string.converter_stopped)
            done.ok -> getString(R.string.converter_done, done.count)
            // Распаковали — значит дело сделано; не открывается по другой
            // причине, и заголовок не имеет права называть это провалом
            // распаковки.
            done.unpacked > 0 -> getString(R.string.converter_unpacked_not_launching)
            else -> getString(R.string.converter_done_with_problems)
        }
        // ЧТО БЫЛО НЕ ТАК. Раньше текст брался по одному признаку - «где лежат
        // файлы», - и при отказе в шторке оставалось «Распаковка закончилась
        // без результата. Файлы здесь: …». Причина (битый файл, архив вместо
        // образа, нет места) терялась ровно в тот момент, когда она нужна одна.
        //
        // Теперь при отказе на первом месте причина, а путь - второй строкой:
        // он всё ещё полезен, но он не ответ на вопрос «почему».
        val where = if (done.where.isNotBlank()) {
            getString(R.string.converter_done_where, done.where)
        } else {
            ""
        }
        val text = ConverterReasons.outcomeText(done.ok, done.unpacked, done.message, where)
        val builder = baseBuilder().apply {
            setContentTitle(title)
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setOngoing(false)
            setAutoCancel(true)
            setProgress(0, 0, false)
        }
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, builder.build())
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun notify(notification: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun progressText(): String = getString(R.string.converter_starting)

    private fun buildNotification(text: String, index: Int, total: Int): Notification {
        val builder = baseBuilder().apply {
            setContentTitle(getString(R.string.converter_title))
            setContentText(text)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(total, index, total <= 0)
            addAction(
                0,
                getString(R.string.converter_stop),
                PendingIntent.getService(
                    this@ConverterService,
                    1,
                    Intent(this@ConverterService, ConverterService::class.java)
                        .setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return builder.build()
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        val open = Intent().setClassName(packageName, "org.yuzu.yuzu_emu.ui.main.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(
            this,
            2,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification_logo)
            .setContentIntent(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.converter_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.converter_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
