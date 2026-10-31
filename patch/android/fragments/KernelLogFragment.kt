// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.utils.KernelLog
import org.yuzu.yuzu_emu.utils.NativeSymbiosis
import org.yuzu.yuzu_emu.utils.SymbiosisTheme

/**
 * Журнал ядра — то, чего не было нигде.
 *
 * ЗАЧЕМ
 *   Когда игра не запускается, вопрос всегда один: «что сказало ядро». До
 *   сих пор ответить было нечем: logcat приложению недоступен, кольцевой
 *   буфер Symbiosis хранит только решения прослойки, а отчёт о падении
 *   собирается уже после беды. Обычный неудачный запуск не оставлял следов.
 *
 * ЧТО ПОКАЗЫВАЕТСЯ
 *   * «Ядро» — файл, который пишет сам эмулятор (последние 256 КБ, чтобы
 *     телефон не задохнулся на журнале в сотни мегабайт);
 *   * «Symbiosis» — кольцевой буфер слоя: какие драйверы, режимы, бюджеты
 *     памяти были выбраны;
 *   * «Прошлый сеанс» — файл, который ядро отложило в сторону при старте.
 *
 * ЧТО МОЖНО СДЕЛАТЬ
 *   скопировать целиком, отправить в мессенджер или на почту, обновить
 *   после воспроизведения проблемы и очистить.
 */
class KernelLogFragment : Fragment() {

    private lateinit var root: LinearLayout
    private lateinit var body: TextView
    private lateinit var summary: TextView

    /** Что сейчас открыто: ядро, слой или прошлый сеанс. */
    private var source = SOURCE_KERNEL
    private var current: KernelLog.Chunk? = null
    private var layerText = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.12f),
                    SymbiosisTheme.BASE
                )
            )
        }
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(12), bottom = bars.bottom + dp(32))
            insets
        }
        buildStatic()
        // Открыли экран — сразу сохранили текущий журнал в архив: это
        // единственный момент, когда он ещё полон и ничего не потеряно.
        runCatching { KernelLog.archiveCurrent() }
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
        }

    /** Заголовок, переключатель источника и кнопки — один раз, не в render(). */
    private fun buildStatic() {
        val ctx = requireContext()
        root.addView(
            TextView(ctx).apply {
                text = "Журнал ядра"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        root.addView(
            TextView(ctx).apply {
                text = "что ядро говорило в этом и прошлом запуске"
                textSize = 14f
                setTextColor(SymbiosisTheme.CYAN)
                setPadding(0, dp(2), 0, dp(10))
            }
        )

        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        SOURCES.forEach { (key, label) ->
            tabs.addView(
                MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                    .apply {
                        text = label
                        textSize = 12f
                        setOnClickListener {
                            source = key
                            refresh()
                        }
                    }.also { btn ->
                        btn.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { rightMargin = dp(6) }
                        btn.tag = key
                    }
            )
        }
        root.addView(tabs)

        summary = TextView(ctx).apply {
            textSize = 13f
            setTextColor(SymbiosisTheme.TEXT_DIM)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(summary)

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        actions.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                text = "Обновить"
                textSize = 12f
                setOnClickListener { refresh() }
            }.also { it.layoutParams = rowParams() }
        )
        actions.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                .apply {
                    text = "Копировать"
                    textSize = 12f
                    setOnClickListener {
                        val text = visibleText()
                        if (text.isNotBlank()) {
                            val clipboard = requireContext()
                                .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("Журнал ядра", text)
                            )
                            summary.text = "скопировано: ${text.length} символов"
                        }
                    }
                }.also { it.layoutParams = rowParams() }
        )
        actions.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                .apply {
                    text = "Отправить"
                    textSize = 12f
                    setOnClickListener { KernelLog.share(requireContext(), current) }
                }.also { it.layoutParams = rowParams() }
        )
        // Кнопки «Очистить» здесь нет и не будет.
        //
        // Журнал — единственное, что остаётся от неудачного запуска, и
        // удалять его одним нажатием рядом с «Отправить» слишком легко
        // случайно. Вместо удаления копия уходит в «Загрузки»: так файл
        // переживёт и очистку данных приложения, и переустановку.
        actions.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                .apply {
                    text = "В «Загрузки»"
                    textSize = 12f
                    setOnClickListener {
                        val path = KernelLog.exportToDownloads(requireContext(), current)
                        summary.text = if (path != null) "сохранено: $path"
                        else "сохранять нечего — журнал пуст"
                    }
                }.also { it.layoutParams = rowParams() }
        )
        root.addView(actions)

        // Текст журнала: моноширинный, с прокруткой вбок. Строки ядра длинные
        // и с переносом превращаются в кашу.
        body = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(SymbiosisTheme.TEXT)
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(SymbiosisTheme.soft(SymbiosisTheme.BASE, 0.9f), 12)
        }
        val horizontal = HorizontalScrollView(ctx).apply { addView(body) }
        root.addView(horizontal)
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { rightMargin = dp(6) }

    private fun visibleText(): String =
        if (source == SOURCE_LAYER) layerText else current?.text.orEmpty()

    private fun refresh() {
        val ctx = requireContext().applicationContext
        summary.text = "читаю…"
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                if (source == SOURCE_LAYER) {
                    layerText = runCatching {
                        NativeSymbiosis.logDump(null, NativeSymbiosis.LogLevel.Debug)
                    }.getOrDefault("")
                    null
                } else if (source == SOURCE_SESSIONS) {
                    // Архив: самый свежий сохранённый сеанс. Список всех
                    // сеансов — по кнопке «Сеансы» внизу экрана.
                    KernelLog.sessions().firstOrNull()?.let { KernelLog.read(it) }
                } else {
                    val files = KernelLog.files()
                    // «Прошлый сеанс» — второй по свежести файл: первый ядро
                    // пишет сейчас.
                    val file = if (source == SOURCE_PREVIOUS) files.getOrNull(1)
                    else files.firstOrNull()
                    file?.let { KernelLog.read(it) }
                }
            }
            if (!isAdded) return@launch
            current = loaded
            val show = visibleText()
            body.text = show.ifBlank { emptyReason() }
            summary.text = describe(loaded, show)
        }
    }

    private fun describe(chunk: KernelLog.Chunk?, text: String): String {
        if (source == SOURCE_LAYER) {
            return if (text.isBlank()) "слой ничего не записал"
            else "решения слоя · ${text.lineSequence().count()} строк"
        }
        if (chunk == null) {
            return if (source == SOURCE_SESSIONS) {
                "сохранённых сеансов пока нет — они появятся перед следующим запуском игры"
            } else {
                "файла журнала нет"
            }
        }
        if (source == SOURCE_SESSIONS) {
            return "${KernelLogArchive.label(chunk.name, chunk.sizeBytes, System.currentTimeMillis())}" +
                " · ошибок: ${KernelLog.countProblems(text)}"
        }
        val kb = chunk.sizeBytes / 1024
        val problems = KernelLog.countProblems(text)
        return "${chunk.name} · ${kb} КБ · ошибок: $problems"
    }

    /**
     * Почему пусто — с указанием, где искать.
     *
     * Пустой экран без объяснения читается как «сломалось». На самом деле до
     * первого запуска игры журнала нет вовсе: ядро создаёт файл, когда
     * начинает писать.
     */
    private fun emptyReason(): String {
        val dir = KernelLog.directory()?.absolutePath ?: "папка журнала не определена"
        return "Журнал пуст.\n\n" +
            "Ядро пишет его, когда запускается игра. Запустите проблемную игру, " +
            "вернитесь сюда и нажмите «Обновить».\n\n" +
            "Файлы лежат в:\n$dir"
    }

    companion object {
        private const val SOURCE_KERNEL = "kernel"
        private const val SOURCE_SESSIONS = "sessions"
        private const val SOURCE_PREVIOUS = "previous"
        private const val SOURCE_LAYER = "layer"

        private val SOURCES = listOf(
            SOURCE_KERNEL to "ядро",
            SOURCE_PREVIOUS to "прошлый запуск",
            SOURCE_SESSIONS to "сеансы",
            SOURCE_LAYER to "Symbiosis"
        )
    }
}
