// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Button
import android.widget.TextView
import org.yuzu.yuzu_emu.utils.SourceRangeServer
import org.yuzu.yuzu_emu.utils.SymbiosisTheme

/**
 * Плеер «по частям».
 *
 * ЗАЧЕМ СВОЙ, А НЕ ВНЕШНИЙ
 *   Человек нажал «По частям» — и ожидает, что фильм начнётся, а не что
 *   откроется чужое приложение и спросит, куда сохранить. Здесь SurfaceView
 *   и MediaPlayer из системы: ничего лишнего в сборку не тянется, а
 *   поддержка форматов — ровно та, что есть в телефоне.
 *
 * КАК СМОТРИТСЯ БЕЗ СКАЧИВАНИЯ
 *   Плееру отдаётся локальный адрес от SourceRangeServer. Сервер идёт к
 *   источнику с `Range: bytes=…` — то есть тянет только запрошенный кусок.
 *   Перемотка — новый запрос, а не новая загрузка с нуля.
 *
 * ЕСЛИ ФОРМАТ НЕ ПОДДЕРЖИВАЕТСЯ
 *   MediaPlayer скажет об этом ошибкой, а не «крутится вечно». Тогда на
 *   экране появляется причина и кнопка «Открыть в другом плеере»: у части
 *   телефонов нет кодека для mkv, и это ограничение телефона, а не ссылки.
 */
class SourcePlayerActivity : Activity() {

    private lateinit var surface: SurfaceView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var seek: SeekBar
    private lateinit var positionView: TextView
    private lateinit var playButton: Button
    private lateinit var errorBox: LinearLayout
    private lateinit var root: FrameLayout

    private var player: MediaPlayer? = null
    private var prepared = false
    private val handler = Handler(Looper.getMainLooper())
    private var sourceUrl = ""
    /** Прямой адрес источника: нужен, если локальный поток не подошёл. */
    private var fallbackUrl = ""
    private var displayName = ""
    private var surfaceReady = false
    private var localMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sourceUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        fallbackUrl = intent.getStringExtra(EXTRA_REMOTE).orEmpty()
        displayName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "видео" }
        buildUi()
        if (sourceUrl.isEmpty()) {
            fail("не передан адрес файла")
            return
        }
        status("готовлю поток…")
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(SymbiosisTheme.BASE) }

        surface = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                    startPlayback()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                }
            })
        }
        root.addView(
            surface,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC0E1116.toInt(), 0x000E1116)
            )
        }
        titleView = TextView(this).apply {
            text = displayName
            textSize = 15f
            setTextColor(SymbiosisTheme.TEXT)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
        }
        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(SymbiosisTheme.TEXT_DIM)
            setPadding(0, dp(2), 0, 0)
        }
        top.addView(titleView)
        top.addView(statusView)
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xDD0E1116.toInt(), 0x000E1116)
            )
        }
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionView = TextView(this).apply {
            text = "0:00 / 0:00"
            textSize = 12f
            setTextColor(SymbiosisTheme.TEXT_DIM)
        }
        seek = SeekBar(this).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(SymbiosisTheme.CYAN)
            thumbTintList = android.content.res.ColorStateList.valueOf(SymbiosisTheme.CYAN)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) {
                    val media = player ?: return
                    if (!prepared) return
                    val duration = runCatching { media.duration }.getOrDefault(0)
                    if (duration > 0) {
                        media.seekTo((duration * bar.progress / 1000.0).toInt())
                        status("перемотка · ${time((duration * bar.progress / 1000.0).toInt())}")
                    }
                }
            })
        }
        times.addView(positionView)
        times.addView(seek)
        bottom.addView(times)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        playButton = Button(this).apply {
            text = "Пауза"
            textSize = 13f
            setTextColor(SymbiosisTheme.BASE)
            background = pill(SymbiosisTheme.CYAN)
            setOnClickListener { togglePlay() }
        }
        row.addView(playButton)
        row.addView(spacer())
        row.addView(
            Button(this).apply {
                text = "Закрыть"
                textSize = 13f
                setTextColor(SymbiosisTheme.TEXT)
                background = pill(SymbiosisTheme.CARD)
                setOnClickListener { finish() }
            }
        )
        bottom.addView(row)
        root.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(SymbiosisTheme.CARD)
                setStroke(dp(1), SymbiosisTheme.RED)
            }
            visibility = View.GONE
        }
        root.addView(errorBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        setContentView(root)
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    // --- воспроизведение ---------------------------------------------------

    private fun startPlayback() {
        if (!surfaceReady || player != null) return
        status("открываю поток…")
        val media = MediaPlayer()
        player = media
        media.setOnPreparedListener {
            prepared = true
            val width = it.videoWidth
            val height = it.videoHeight
            if (width > 0 && height > 0) {
                status("${width}×$height · ${time(it.duration)}")
            } else {
                status("${time(it.duration)}")
            }
            it.start()
            playButton.text = "Пауза"
            tick()
        }
        media.setOnCompletionListener {
            status("доиграно")
            playButton.text = "Смотреть"
            prepared = false
        }
        media.setOnErrorListener { _, what, extra ->
            // MediaPlayer перестаёт работать после ошибки: пробуем ещё раз
            // напрямую с источника, если шли через локальный сервер. У части
            // телефонов он не любит короткие ответы прокси.
            if (localMode && fallbackUrl.isNotEmpty()) {
                localMode = false
                runCatching { media.reset() }
                runCatching {
                    media.setDisplay(surface.holder)
                    media.setDataSource(this, Uri.parse(fallbackUrl))
                    media.prepareAsync()
                    status("локальный поток не подошёл — открываю напрямую с источника")
                }.onFailure {
                    fail("этот телефон не открыл файл: ${describe(what, extra)}")
                }
                return@setOnErrorListener true
            }
            fail("этот телефон не открыл файл: ${describe(what, extra)}")
            true
        }
        media.setOnBufferingUpdateListener { _, percent ->
            if (percent in 1..99) status("буферизация $percent%")
        }
        runCatching {
            media.setDisplay(surface.holder)
            media.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
            if (sourceUrl.startsWith("/")) {
                media.setDataSource(sourceUrl)
            } else {
                media.setDataSource(this, Uri.parse(sourceUrl))
            }
            media.prepareAsync()
        }.onFailure {
            fail("поток не открылся: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun togglePlay() {
        val media = player ?: return
        if (!prepared) {
            runCatching { media.prepareAsync() }
            return
        }
        if (media.isPlaying) {
            media.pause()
            playButton.text = "Смотреть"
            status("пауза")
        } else {
            media.start()
            playButton.text = "Пауза"
            tick()
        }
    }

    private fun tick() {
        handler.postDelayed({
            val media = player ?: return@postDelayed
            if (!prepared) return@postDelayed
            val duration = runCatching { media.duration }.getOrDefault(0)
            val position = runCatching { media.currentPosition }.getOrDefault(0)
            positionView.text = "${time(position)} / ${time(duration)}"
            if (duration > 0) seek.progress = (position * 1000L / duration).toInt()
            if (media.isPlaying) tick()
        }, 500)
    }

    private fun time(ms: Int): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    private fun describe(what: Int, extra: Int): String = when (what) {
        MediaPlayer.MEDIA_ERROR_UNKNOWN ->
            "формат не поддерживается или файл недокачан (код $extra)"
        MediaPlayer.MEDIA_ERROR_SERVER_DIED ->
            "системный декодер занят другим приложением — закройте его и нажмите «Смотреть»"
        else -> "ошибка декодера $what/$extra"
    }

    private fun status(text: String) {
        statusView.text = text
    }

    private fun fail(reason: String) {
        status(reason)
        errorBox.removeAllViews()
        errorBox.addView(
            TextView(this).apply {
                text = "Не удалось открыть"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
                gravity = Gravity.CENTER
            }
        )
        errorBox.addView(
            TextView(this).apply {
                text = reason
                textSize = 13f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(12))
            }
        )
        errorBox.addView(
            Button(this).apply {
                text = "Открыть в другом плеере"
                textSize = 13f
                setTextColor(SymbiosisTheme.BASE)
                background = pill(SymbiosisTheme.AMBER)
                setOnClickListener { openExternally() }
            }
        )
        errorBox.addView(
            Button(this).apply {
                text = "Закрыть"
                textSize = 13f
                setTextColor(SymbiosisTheme.TEXT)
                background = pill(SymbiosisTheme.CARD)
                setOnClickListener { finish() }
            }
        )
        errorBox.visibility = View.VISIBLE
        playButton.isEnabled = false
    }

    private fun openExternally() {
        val uri = if (sourceUrl.startsWith("/")) Uri.fromFile(java.io.File(sourceUrl))
        else Uri.parse(sourceUrl)
        val type = SourceRangeServer.contentTypeOf(displayName)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Открыть в")) }
            .onFailure { status("других плееров на телефоне нет") }
    }

    // --- жизненный цикл ----------------------------------------------------

    override fun onPause() {
        super.onPause()
        runCatching { player?.takeIf { prepared && it.isPlaying }?.pause() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Закруглённая заливка: MaterialButton здесь нельзя — тема системная. */
    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(color)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        /** Прямой адрес источника — запасной путь, если локальный не подошёл. */
        const val EXTRA_REMOTE = "remote"
    }
}
