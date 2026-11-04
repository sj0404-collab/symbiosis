// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.CloudSettings
import org.yuzu.yuzu_emu.utils.CloudSync
import org.yuzu.yuzu_emu.utils.ProgressMarks
import org.yuzu.yuzu_emu.utils.SavesOverview
import org.yuzu.yuzu_emu.utils.SymbiosisTheme

/**
 * Сейвы одной игры: все профили, даты, «сколько не заходили», «сколько без
 * сохранения» и отметка прохождения.
 *
 * ПОЧЕМУ ЭКРАН, А НЕ СТРОКА В СВОЙСТВАХ
 *   Панель в WebView показывала только «есть сейв · 1,2 МБ». Этого мало,
 *   чтобы принять решение «начинать заново или нет»: нужны дата последнего
 *   сохранения, сколько времени прошло и сколько наиграно после него.
 *   Файлов у игры бывает несколько десятков, а профилей — несколько.
 *
 * ПРО ПРОЦЕНТ
 *   Он ставится здесь вручную. Настоящий процент лежит внутри сейва в
 *   формате конкретной игры; вывести его из размера папки нельзя, а
 *   выдуманное число в этом месте опаснее отсутствующего. Поэтому экран
 *   честно говорит: «процент ставите вы», и даёт это сделать в два нажатия.
 */
class SavesFragment : Fragment() {

    private lateinit var root: LinearLayout
    private var statusLine = ""

    private val gamePath: String get() = requireArguments().getString(ARG_PATH).orEmpty()
    private val gameTitle: String get() = requireArguments().getString(ARG_TITLE).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.10f), SymbiosisTheme.BASE)
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
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun game(): Game = Game(title = gameTitle, path = gamePath)

    private fun render() {
        val ctx = requireContext()
        root.removeAllViews()

        root.addView(
            TextView(ctx).apply {
                text = gameTitle.ifBlank { "Сейвы" }
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        root.addView(
            TextView(ctx).apply {
                text = "сохранения, даты и прогресс"
                textSize = 14f
                setTextColor(SymbiosisTheme.CYAN)
                setPadding(0, dp(2), 0, dp(12))
            }
        )

        if (statusLine.isNotEmpty()) {
            root.addView(
                TextView(ctx).apply {
                    text = statusLine
                    textSize = 13f
                    setTextColor(SymbiosisTheme.MINT)
                    background = rounded(SymbiosisTheme.soft(SymbiosisTheme.MINT, 0.14f), 12)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }
            )
        }

        // Сводка считается на IO: обход папок сейвов — это чтение диска, а
        // на экран должно попасть уже готовое.
        val now = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { CloudSync.localSummary(game(), now) }
                    .getOrDefault(SavesOverview.summarise(emptyList(), now))
            }
            if (_bindingAlive()) fill(summary, now)
        }
    }

    /** Экран перерисован — старые корутины не должны дописывать в него. */
    private fun _bindingAlive(): Boolean = root.parent != null

    private fun fill(summary: SavesOverview.Summary, now: Long) {
        val ctx = requireContext()
        val accent = SymbiosisTheme.CYAN

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(
                SymbiosisTheme.mix(SymbiosisTheme.CARD, accent, 0.10f), 20, 1,
                SymbiosisTheme.soft(accent, 0.30f)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        fun fact(label: String, value: String, color: Int = SymbiosisTheme.TEXT) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(
                TextView(ctx).apply {
                    text = label
                    textSize = 13f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(
                TextView(ctx).apply {
                    text = value
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color)
                    gravity = Gravity.END
                }
            )
            card.addView(row)
        }

        fact(
            "сохранений",
            if (summary.hasSaves) {
                "${summary.savesCount} ${SavesOverview.plural(summary.savesCount, "слот", "слота", "слотов")}" +
                    " · ${summary.sizeText}"
            } else {
                "нет"
            },
            if (summary.hasSaves) SymbiosisTheme.MINT else SymbiosisTheme.AMBER
        )
        fact("создан", if (summary.created > 0) SavesOverview.dateText(summary.created) else "—")
        fact("последнее сохранение", SavesOverview.dateText(summary.lastSaved))
        fact(
            "не заходили",
            SavesOverview.ago(summary.daysSincePlay),
            if ((summary.daysSincePlay ?: 0) >= 30) SymbiosisTheme.AMBER else SymbiosisTheme.TEXT
        )
        // Главный ответ на «играю десять часов и ничего не добился»: видно,
        // что игра не сохранялась очень давно.
        fact(
            "с последнего сохранения",
            SavesOverview.sinceSaveText(summary.hoursSinceSave),
            if ((summary.hoursSinceSave ?: 0) >= 5) SymbiosisTheme.AMBER else SymbiosisTheme.TEXT
        )

        root.addView(card)

        // ── Процент прохождения ─────────────────────────────────────────
        root.addView(sectionTitle("Пройдено", SymbiosisTheme.AMBER))
        val titleId = CloudSync.titleIdOf(game())
        val current = ProgressMarks.percent(titleId)

        root.addView(
            TextView(ctx).apply {
                text = if (current == null) {
                    "не отмечено. Процент ставите вы: в сейве он не хранится отдельным " +
                        "полем — ни у одной игры одинаково его не прочитать."
                } else {
                    "отмечено: $current%"
                }
                textSize = 12f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, 0, 0, dp(8))
            }
        )

        val steps = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        ProgressMarks.STEPS.forEach { step ->
            val label = if (step == 100) "100%" else "$step"
            steps.addView(
                chip(
                    label,
                    selected = current == step,
                    accent = if (current == step) SymbiosisTheme.AMBER else SymbiosisTheme.TEXT_DIM
                ) {
                    ProgressMarks.set(titleId, step)
                    statusLine = "прогресс: $step%"
                    render()
                }
            )
        }
        root.addView(steps)

        val custom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        custom.addView(
            outlineButton("Своё число", SymbiosisTheme.AMBER) { askCustomPercent(titleId) }
        )
        if (current != null) {
            custom.addView(outlineButton("Снять отметку", SymbiosisTheme.RED) {
                ProgressMarks.clear(titleId)
                statusLine = "отметка снята"
                render()
            })
        }
        root.addView(custom)

        // ── Слоты ───────────────────────────────────────────────────────
        root.addView(sectionTitle("Где лежат", SymbiosisTheme.CYAN))
        if (summary.slots.isEmpty()) {
            root.addView(
                TextView(ctx).apply {
                    text = "Сохранений нет: игра ещё не создавала сейв или они " +
                        "хранятся в другой папке данных."
                    textSize = 13f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                }
            )
        } else {
            summary.slots.forEach { slot ->
                root.addView(
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = rounded(SymbiosisTheme.SURFACE, 12)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(4) }
                        addView(
                            TextView(ctx).apply {
                                text = "профиль ${slot.profile}"
                                textSize = 14f
                                setTypeface(typeface, Typeface.BOLD)
                                setTextColor(SymbiosisTheme.TEXT)
                            }
                        )
                        addView(
                            TextView(ctx).apply {
                                text = SavesOverview.describeSlot(slot, now)
                                textSize = 12f
                                setTextColor(SymbiosisTheme.TEXT_DIM)
                                setPadding(0, dp(2), 0, 0)
                            }
                        )
                        // Путь — отдельной строкой и мелко: он нужен, когда
                        // сейв надо найти руками, а не для чтения каждый раз.
                        addView(
                            TextView(ctx).apply {
                                text = slot.path
                                textSize = 10f
                                setTextColor(SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.85f))
                                setPadding(0, dp(4), 0, 0)
                            }
                        )
                    }
                )
            }
        }

        // ── Облако ──────────────────────────────────────────────────────
        root.addView(sectionTitle("Облако", SymbiosisTheme.VIOLET))
        if (!CloudSettings.configured()) {
            root.addView(
                TextView(ctx).apply {
                    text = "Не настроено: ${CloudSettings.missing()}. Настроить — " +
                        "«Инструменты» → «Облако»."
                    textSize = 12f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                    setPadding(0, 0, 0, dp(8))
                }
            )
        } else {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                filledButton("В облако", SymbiosisTheme.VIOLET) { sync(up = true) }
            )
            row.addView(
                outlineButton("Из облака", SymbiosisTheme.VIOLET) { sync(up = false) }
            )
            root.addView(row)
            root.addView(
                TextView(ctx).apply {
                    text = CloudSettings.whereText()
                    textSize = 11f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }
    }

    private fun sync(up: Boolean) {
        if (statusLine.startsWith("идёт")) return
        statusLine = if (up) "идёт заливка…" else "идёт скачивание…"
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            val game = game()
            val outcome = withContext(Dispatchers.IO) {
                if (up) CloudSync.uploadSaves(game) else CloudSync.downloadSaves(game)
            }
            statusLine = outcome.message
            render()
        }
    }

    private fun askCustomPercent(titleId: String) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "0–100"
            setText(ProgressMarks.percent(titleId)?.toString().orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Сколько пройдено")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in 0..100) {
                    statusLine = "нужно число от 0 до 100"
                } else {
                    ProgressMarks.set(titleId, value)
                    statusLine = "прогресс: $value%"
                }
                render()
            }
            .show()
    }

    // ── кирпичи ────────────────────────────────────────────────────────

    private fun sectionTitle(text: String, accent: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun chip(text: String, selected: Boolean, accent: Int, onClick: () -> Unit) =
        MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(accent)
            cornerRadius = dp(12)
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minHeight = dp(40)
            strokeWidth = if (selected) dp(2) else dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(SymbiosisTheme.soft(accent, 0.6f))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (selected) SymbiosisTheme.soft(accent, 0.20f) else SymbiosisTheme.SURFACE
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun filledButton(text: String, accent: Int, onClick: () -> Unit) =
        MaterialButton(requireContext()).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(SymbiosisTheme.inkOn(accent))
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(44)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun outlineButton(text: String, accent: Int, onClick: () -> Unit) =
        MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(accent)
            strokeColor = android.content.res.ColorStateList.valueOf(SymbiosisTheme.soft(accent, 0.6f))
            strokeWidth = dp(1)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(44)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    companion object {
        const val ARG_PATH = "path"
        const val ARG_TITLE = "title"
        const val TAG = "SavesFragment"

        fun newInstance(path: String, title: String): SavesFragment = SavesFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PATH, path)
                putString(ARG_TITLE, title)
            }
        }
    }
}
