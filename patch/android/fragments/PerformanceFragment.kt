// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.navigation.findNavController
import com.google.android.material.button.MaterialButton
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.FrameMetricsText
import org.yuzu.yuzu_emu.utils.FramePacing
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.NativeSymbiosis
import org.yuzu.yuzu_emu.utils.SymbiosisTheme

/**
 * «Ровность кадров»: почему картинка прыгает, а звук — нет (или наоборот).
 *
 * ПОЧЕМУ ЭТОТ ЭКРАН ВООБЩЕ ПОЯВИЛСЯ
 *   «Кадры прыгают 60 → 15 → 45 → 5» и «звук с тормозами» — это описание
 *   ощущения. По нему нельзя выбрать лечение: упор в синхронизацию экрана,
 *   сборка шейдеров на ходу, тепловой сброс частот и упор в один поток
 *   выглядят одинаково, а лечатся по-разному. Один из популярных советов —
 *   «включите турбо-режим» — при перегреве делает хуже.
 *
 *   Поэтому экран сначала показывает ФАКТЫ (сколько кадров, медиана, худшие,
 *   провалы звука, сборки шейдеров за сеанс), затем называет причину, и
 *   только потом — что менять, по одной настройке за раз.
 *
 * ОТКУДА ДАННЫЕ
 *   Нативная часть считает длительность каждого кадра, провалы звука и
 *   сборки шейдеров (см. common/symbiosis/frame_metrics.h). Счётчики
 *   переживают выход из игры, поэтому порядок такой: поиграть, выйти,
 *   открыть этот экран. Кнопка «Новый замер» обнуляет счётчики.
 *
 * ЧЕГО ЭКРАН НЕ ДЕЛАЕТ
 *   Не обещает, что после нажатия кнопки всё станет ровно. Он показывает,
 *   что происходит на самом деле, и предлагает следующий шаг.
 */
class PerformanceFragment : Fragment() {

    private lateinit var root: LinearLayout
    private var statusLine: String = ""
    private var snapshot: FrameMetricsText.Snapshot = FrameMetricsText.Snapshot()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(SymbiosisTheme.soft(SymbiosisTheme.PINK, 0.12f), SymbiosisTheme.BASE)
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
        readSnapshot()
        render()
    }

    private fun readSnapshot() {
        snapshot = runCatching { NativeSymbiosis.frameSnapshot() }
            .getOrDefault(FrameMetricsText.Snapshot())
    }

    // --- отрисовка ---------------------------------------------------------

    private fun render() {
        val ctx = requireContext()
        root.removeAllViews()

        root.addView(
            TextView(ctx).apply {
                text = "Ровность кадров"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        root.addView(
            TextView(ctx).apply {
                text = "Что на самом деле происходило с картинкой и звуком"
                textSize = 14f
                setTextColor(SymbiosisTheme.PINK)
                setPadding(0, dp(2), 0, dp(12))
            }
        )

        if (statusLine.isNotEmpty()) {
            root.addView(banner(statusLine, SymbiosisTheme.MINT))
        }

        root.addView(
            rowOf(
                button("Обновить", SymbiosisTheme.PINK) { readSnapshot(); render() },
                button("Новый замер", SymbiosisTheme.AMBER) { startNewRun() },
                button("Скопировать", SymbiosisTheme.BLUE) { copyReport() }
            )
        )

        root.addView(sectionTitle("Что записано", SymbiosisTheme.PINK))
        root.addView(note(FrameMetricsText.headline(snapshot)))

        if (snapshot.isEmpty) {
            root.addView(
                note(
                    "Порядок такой: запустите игру, поиграйте минуту-две в том месте, где " +
                        "прыгает, выйдите из игры и вернитесь сюда. Счётчики не обнуляются при " +
                        "выходе — это и позволяет разбирать уже прошедший запуск."
                )
            )
            root.addView(
                note(
                    "Экран считает только то, что видит ядро. Если игра не запускалась ни разу " +
                        "после установки, здесь будет пусто."
                )
            )
            renderAudioSection(null)
            return
        }

        val report = FramePacing.analyse(snapshot.frameMs)
        numbersCard(report)
        causesCard(report)
        adviceCard(report)
        renderAudioSection(report)
        renderWhereToGo()
    }

    /** Крупные числа: это первое, что человек сравнивает «на глаз». */
    private fun numbersCard(report: FramePacing.Report) {
        val ctx = requireContext()
        val card = card(SymbiosisTheme.PINK)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        fun cell(label: String, value: String, accent: Int) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            box.addView(
                TextView(ctx).apply {
                    text = value
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(accent)
                }
            )
            box.addView(
                TextView(ctx).apply {
                    text = label
                    textSize = 11f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                }
            )
            row.addView(box)
        }
        cell("медиана", FramePacing.formatFps(report.medianFps), SymbiosisTheme.MINT)
        cell("90% кадров", FramePacing.formatFps(report.p90Fps), SymbiosisTheme.CYAN)
        cell("худшие 1%", FramePacing.formatFps(report.p1Fps), SymbiosisTheme.AMBER)
        cell("худший кадр", "${Math.round(report.worstMs)} мс", SymbiosisTheme.RED)
        card.addView(row)
        card.addView(
            TextView(ctx).apply {
                text = "средняя ${FramePacing.formatFps(report.averageFps)} · разброс " +
                    "${Math.round(report.jitter * 100)}%"
                textSize = 12f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, dp(6), 0, 0)
            }
        )
        root.addView(card)
    }

    private fun causesCard(report: FramePacing.Report) {
        root.addView(sectionTitle("Вероятная причина", SymbiosisTheme.AMBER))
        val ctx = requireContext()
        val card = card(SymbiosisTheme.AMBER)
        card.addView(
            TextView(ctx).apply {
                text = report.causes.joinToString(" · ") { it.label }
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        report.facts.forEach { fact ->
            card.addView(
                TextView(ctx).apply {
                    text = fact
                    textSize = 12.5f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }
        root.addView(card)
    }

    private fun adviceCard(report: FramePacing.Report) {
        if (report.advice.isEmpty()) return
        root.addView(sectionTitle("Что попробовать", SymbiosisTheme.MINT))
        report.advice.forEachIndexed { index, advice ->
            val card = card(SymbiosisTheme.MINT)
            val ctx = requireContext()
            card.addView(
                TextView(ctx).apply {
                    text = "${index + 1}. $advice"
                    textSize = 13f
                    setTextColor(SymbiosisTheme.TEXT)
                }
            )
            root.addView(card)
        }
    }

    /**
     * Звук: отдельный раздел, потому что у него своя причина.
     *
     * Буфер звука в Eden — 10 мс жёстко. Под нагрузкой его не хватает, и в
     * динамик уходит повтор последнего кадра: это и есть треск и заикание
     * музыки при совершенно ровной картинке. Настройка меняет размер; чтобы
     * она подействовала, игру нужно запустить заново.
     */
    private fun renderAudioSection(report: FramePacing.Report?) {
        val ctx = requireContext()
        root.addView(sectionTitle("Звук", SymbiosisTheme.CYAN))

        val stable = report != null && report.jitter < 0.35
        root.addView(
            note(
                if (report == null) {
                    "Провалов звука пока не сосчитано — они считаются вместе с кадрами."
                } else {
                    FramePacing.audioVerdict(
                        frameStable = stable,
                        underruns = snapshot.audioUnderruns.toInt(),
                        bufferMs = currentBufferMs()
                    )
                }
            )
        )

        val card = card(SymbiosisTheme.CYAN)
        card.addView(
            TextView(ctx).apply {
                text = "Буфер звука: ${currentBufferMs()} мс"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        card.addView(
            TextView(ctx).apply {
                text = "10 мс — как было в Eden. Больше — меньше треска под нагрузкой, " +
                    "чуть больше задержка. Действует после перезапуска игры."
                textSize = 12f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, dp(4), 0, dp(8))
            }
        )
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(10, 20, 30, 50).forEach { ms ->
            val selected = currentBufferMs() == ms
            val chip = button("$ms мс", if (selected) SymbiosisTheme.CYAN else SymbiosisTheme.OUTLINE) {
                setBufferMs(ms)
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6) }
            )
        }
        card.addView(hscroll(row))
        root.addView(card)
    }

    private fun renderWhereToGo() {
        root.addView(sectionTitle("Куда идти дальше", SymbiosisTheme.BLUE))
        root.addView(
            rowOf(
                button("Подбор FPS", SymbiosisTheme.AMBER) { navigate(R.id.action_global_fpsTuningFragment) },
                button("Два ядра", SymbiosisTheme.PINK) { navigate(R.id.action_global_enginesFragment) }
            )
        )
        root.addView(
            rowOf(
                button("Журнал ядра", SymbiosisTheme.CYAN) { navigate(R.id.action_global_kernelLogFragment) },
                button("Диагностика", SymbiosisTheme.BLUE) { navigate(R.id.action_global_diagnosticsFragment) }
            )
        )
        root.addView(
            note(
                "«Подбор FPS» меняет несколько настроек сразу под это устройство, «Журнал ядра» " +
                    "показывает, что говорило ядро, «Диагностика» — отчёт о запуске. Если после " +
                    "изменения настроек стало хуже, верните прежнее значение: экран не хранит " +
                    "историю, но журнал — да."
            )
        )
    }

    // --- действия ----------------------------------------------------------

    private fun startNewRun() {
        runCatching { NativeSymbiosis.resetFrameMetrics() }
        snapshot = FrameMetricsText.Snapshot()
        statusLine = "счётчики обнулены — запускайте игру и играйте"
        render()
    }

    private fun copyReport() {
        val text = buildReport()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager
        if (clipboard == null) {
            statusLine = "буфер обмена недоступен"
            render()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Ровность кадров", text))
        statusLine = "отчёт скопирован"
        render()
    }

    private fun buildReport(): String {
        val builder = StringBuilder()
        builder.append("Symbiosis · ровность кадров\n")
        builder.append(FrameMetricsText.headline(snapshot)).append('\n')
        builder.append("буфер звука: ").append(currentBufferMs()).append(" мс\n")
        if (!snapshot.isEmpty) {
            val report = FramePacing.analyse(snapshot.frameMs)
            builder.append("\nПричина: ")
            builder.append(report.causes.joinToString(", ") { it.label }).append('\n')
            report.facts.forEach { builder.append("· ").append(it).append('\n') }
            if (report.advice.isNotEmpty()) {
                builder.append("\nЧто попробовать:\n")
                report.advice.forEachIndexed { i, a -> builder.append(i + 1).append(". ").append(a).append('\n') }
            }
            builder.append("\nЗвук: ")
            builder.append(
                FramePacing.audioVerdict(
                    frameStable = report.jitter < 0.35,
                    underruns = snapshot.audioUnderruns.toInt(),
                    bufferMs = currentBufferMs()
                )
            ).append('\n')
        }
        val thermal = runCatching { NativeSymbiosis.thermal() }.getOrNull()
        if (thermal != null && thermal.hasSensors) {
            builder.append("\nТепло: ").append(thermal.temperatureOrDash()).append('\n')
            if (thermal.isThrottling) builder.append("Сейчас устройство ограничивает частоты.\n")
        }
        return builder.toString()
    }

    private fun NativeSymbiosis.Thermal.temperatureOrDash(): String =
        if (tempCelsius > 0) "$tempCelsius °C · частота GPU $gpuClockPercent%" else "датчик недоступен"

    private fun currentBufferMs(): Int =
        runCatching { NativeConfig.getInt(KEY_AUDIO_BUFFER, false) }.getOrDefault(20)
            .let { if (it <= 0) 20 else it }

    private fun setBufferMs(value: Int) {
        val applied = runCatching { NativeConfig.setInt(KEY_AUDIO_BUFFER, value) }.isSuccess
        statusLine = if (applied) {
            "буфер звука: $value мс — подействует после перезапуска игры"
        } else {
            "не удалось сохранить размер буфера"
        }
        render()
    }

    private fun navigate(action: Int) {
        runCatching { root.findNavController().navigate(action) }
    }

    // --- кирпичи -----------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun card(accent: Int): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SymbiosisTheme.mix(SymbiosisTheme.CARD, accent, 0.10f), 16, 1,
                SymbiosisTheme.soft(accent, 0.35f))
            setPadding(dp(13), dp(11), dp(13), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) }
        }

    private fun banner(text: String, accent: Int): View =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(SymbiosisTheme.TEXT)
            background = rounded(SymbiosisTheme.mix(SymbiosisTheme.CARD, accent, 0.16f), 14, 1,
                SymbiosisTheme.soft(accent, 0.45f))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

    private fun note(text: String): View =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(SymbiosisTheme.TEXT_DIM)
            setPadding(0, dp(4), 0, dp(10))
        }

    private fun sectionTitle(text: String, accent: Int): View =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            setPadding(0, dp(12), 0, dp(6))
        }

    private fun button(text: String, accent: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(requireContext()).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(if (accent == SymbiosisTheme.OUTLINE) SymbiosisTheme.TEXT else SymbiosisTheme.BASE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (accent == SymbiosisTheme.OUTLINE) SymbiosisTheme.CARD else accent
            )
            cornerRadius = dp(14)
            minHeight = dp(38)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }

    /**
     * Ряд кнопок, который можно прокрутить вбок.
     *
     * Три-четыре кнопки с русскими подписями в строку на узком экране не
     * влезают: LinearLayout молча сжимает последнюю, и подпись превращается в
     * «Скопир…». Лучше прокрутка, чем обрезанный текст.
     */
    private fun rowOf(vararg views: View): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                )
            }
        }
        return hscroll(row)
    }

    private fun hscroll(inner: View): View =
        HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(2))
            addView(inner)
        }

    companion object {
        /** Ключ настройки размера буфера звука (см. common/settings.h). */
        private const val KEY_AUDIO_BUFFER = "symbiosis_audio_buffer_ms"
    }
}
