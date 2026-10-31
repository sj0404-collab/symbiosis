// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.utils.EngineDownloader
import org.yuzu.yuzu_emu.utils.EngineHybrid
import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.EngineStore
import org.yuzu.yuzu_emu.utils.KenjiProbeService
import org.yuzu.yuzu_emu.utils.SymbiosisTheme

/**
 * Два ядра в одном: состояния, настройки и связь между ними.
 *
 * WHY THIS SCREEN CHANGED
 *   Раньше это был выбор «или Eden, или Kenji». Выбранное ядро запускало все
 *   игры, а настройки второго лежали числами в коде: экран показывал ядра,
 *   но не то, с чем каждое из них играет. Отсюда и жалоба «одни настройки
 *   не работают с другими».
 *
 *   Теперь оба ядра присутствуют всегда, и у каждого свои настройки:
 *   * **общие** строки (масштаб, док, кэш шейдеров, NCE) идут за Eden, пока
 *     их не тронули здесь;
 *   * **свои** (память, PPTC, macro HLE, анизотропия, интернет, проверка
 *     целостности) принадлежат только второму ядру, и Eden их не трогает;
 *   * **ядро для игры** выбирается на плитке в библиотеке долгим нажатием,
 *     а здесь задаётся значение по умолчанию.
 *
 * ПОЧЕМУ ЭКРАН СОБРАН КОДОМ
 *   Строк становится больше, и каждая новая строка в разметке — ещё один
 *   файл, который надо не забыть скопировать в дерево сборки (в этом проекте
 *   это уже случалось дважды). Заодно цвет каждой строки считается из
 *   [SymbiosisTheme], а не дублируется в десяти XML.
 *
 * ПОЧЕМУ ЦВЕТА РАЗНЫЕ ПО СМЫСЛУ
 *   Eden — голубой, Kenji-NX — розовый, и эти два цвета повторяются везде:
 *   на карточке ядра, в точке у строки настройки, в пометке на плитке игры.
 *   Человек, который один раз увидел «розовое = второе ядро», дальше читает
 *   экран без подписей.
 */
class EnginesFragment : Fragment() {

    private lateinit var root: LinearLayout
    private var busy = false
    private var statusLine = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            // Фон экрана: то же свечение, что у библиотеки, но зеленоватое —
            // так этот экран отличается от списка игр, не теряя общей базы.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(SymbiosisTheme.soft(SymbiosisTheme.MINT, 0.10f), SymbiosisTheme.BASE)
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
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun render() {
        val ctx = requireContext()
        root.removeAllViews()

        // ── заголовок ───────────────────────────────────────────────────
        root.addView(
            TextView(ctx).apply {
                text = "Два ядра"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        root.addView(
            TextView(ctx).apply {
                text = "Eden и Kenji-NX в одном приложении"
                textSize = 14f
                setTextColor(SymbiosisTheme.MINT)
                setPadding(0, dp(2), 0, dp(14))
            }
        )

        if (statusLine.isNotEmpty()) {
            root.addView(
                TextView(ctx).apply {
                    text = statusLine
                    textSize = 14f
                    setTextColor(SymbiosisTheme.MINT)
                    background = rounded(SymbiosisTheme.soft(SymbiosisTheme.MINT, 0.14f), 12)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = dp(12)
                    layoutParams = lp
                }
            )
        }

        // ── сами ядра ───────────────────────────────────────────────────
        for (engine in EngineLoader.Engine.values()) {
            root.addView(engineCard(engine))
        }

        if (!EngineLoader.deviceSupported()) {
            root.addView(
                banner(
                    "Это устройство не 64-битное (arm64), поэтому скачиваемое ядро на нём " +
                        "не запустится. Доступен только Eden.",
                    SymbiosisTheme.AMBER
                )
            )
        }

        // ── связь настроек ──────────────────────────────────────────────
        root.addView(sectionTitle("Связь настроек", SymbiosisTheme.MINT))
        root.addView(
            TextView(ctx).apply {
                text = "Зелёная точка — значение взято у Eden. Розовая — своё у Kenji. " +
                    "Нажмите строку, чтобы переключить значение."
                textSize = 13f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, 0, 0, dp(10))
            }
        )

        val settingsCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SymbiosisTheme.SURFACE, 20, 1, SymbiosisTheme.OUTLINE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        EngineStore.rows(ctx).forEachIndexed { index, row ->
            settingsCard.addView(settingRow(row, index))
        }
        root.addView(settingsCard)

        // ── итог ────────────────────────────────────────────────────────
        root.addView(banner("Второе ядро сейчас: " + EngineStore.summary(ctx), SymbiosisTheme.PINK))
        root.addView(
            outlineButton("Вернуть значения Eden", SymbiosisTheme.AMBER) {
                statusLine = EngineStore.resetFields(ctx)
                render()
            }
        )

        // ── ядро по умолчанию ───────────────────────────────────────────
        root.addView(sectionTitle("Ядро по умолчанию", SymbiosisTheme.CYAN))
        root.addView(
            TextView(ctx).apply {
                text = "С ним запускаются игры, у которых не выбран свой ядро. Сейчас: " +
                    EngineStore.globalCore(ctx).label + "."
                textSize = 13f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, 0, 0, dp(10))
            }
        )
        val cores = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        cores.addView(
            choiceCard(
                "Eden",
                "встроен",
                SymbiosisTheme.sectionAccent("eden"),
                EngineStore.globalCore(ctx) == EngineHybrid.Core.EDEN
            ) {
                EngineStore.setGlobalCore(ctx, EngineHybrid.Core.EDEN)
                statusLine = "по умолчанию: Eden"
                render()
            }
        )
        cores.addView(
            choiceCard(
                "Kenji-NX",
                if (kenjiUsable(ctx)) "скачан" else "не скачан",
                SymbiosisTheme.PINK,
                EngineStore.globalCore(ctx) == EngineHybrid.Core.KENJI
            ) {
                if (!kenjiUsable(ctx)) {
                    statusLine = "сначала скачайте второе ядро — без него игры пойдут на Eden"
                    render()
                    return@choiceCard
                }
                EngineStore.setGlobalCore(ctx, EngineHybrid.Core.KENJI)
                statusLine = "по умолчанию: Kenji-NX"
                render()
            }
        )
        root.addView(cores)
    }

    /** Ядро: состояние, цвет и действия. Оба видны всегда — это и есть «в одном». */
    private fun engineCard(engine: EngineLoader.Engine): View {
        val ctx = requireContext()
        val isEden = engine == EngineLoader.Engine.EDEN
        val accent = SymbiosisTheme.sectionAccent(if (isEden) "eden" else "kenji")
        val state = EngineLoader.state(ctx, engine)
        val isDefault = EngineStore.globalCore(ctx) == EngineHybrid.Core.of(engine.id)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(SymbiosisTheme.mix(SymbiosisTheme.CARD, accent, 0.12f), 22, 1,
                SymbiosisTheme.soft(accent, 0.35f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }

        // Цветная полоса слева — тот же приём, что у плитки игры.
        box.addView(
            View(ctx).apply {
                setBackgroundColor(accent)
                layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT)
                    .apply { marginVertical = dp(12) }
            }
        )

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        box.addView(body)

        // Строка: буква, название, статус справа.
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(ctx).apply {
                text = if (isEden) "E" else "K"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
                gravity = Gravity.CENTER
                background = rounded(SymbiosisTheme.soft(accent, 0.16f), 14)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
        )
        head.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(
                    TextView(ctx).apply {
                        text = engine.label
                        textSize = 18f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(SymbiosisTheme.TEXT)
                    }
                )
                addView(
                    TextView(ctx).apply {
                        text = if (isEden) "встроен в приложение" else "отдельный процесс :kenji"
                        textSize = 12f
                        setTextColor(SymbiosisTheme.TEXT_DIM)
                    }
                )
            }
        )
        head.addView(statusPill(state))
        body.addView(head)

        if (isDefault) {
            body.addView(
                TextView(ctx).apply {
                    text = "✓ по умолчанию"
                    textSize = 12f
                    setTextColor(accent)
                    setPadding(0, dp(8), 0, 0)
                }
            )
        }

        body.addView(
            TextView(ctx).apply {
                text = describe(state)
                textSize = 13f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, dp(8), 0, 0)
            }
        )

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val usable = state is EngineLoader.State.Builtin || state is EngineLoader.State.Ready

        if (usable && engine == EngineLoader.Engine.KENJI) {
            buttons.addView(filledButton("Проверить", accent) { probe(engine) })
        }
        if (state is EngineLoader.State.Missing || state is EngineLoader.State.Broken) {
            if (EngineLoader.deviceSupported()) {
                val mb = (EngineLoader.KNOWN_SIZE[engine] ?: 0L) / 1048576
                buttons.addView(filledButton("Скачать ($mb МБ)", accent) { fetch(engine) })
            }
        }
        if (state is EngineLoader.State.Ready) {
            buttons.addView(outlineButton("Удалить", SymbiosisTheme.RED) {
                if (EngineStore.globalCore(ctx) == EngineHybrid.Core.KENJI) {
                    EngineStore.setGlobalCore(ctx, EngineHybrid.Core.EDEN)
                }
                EngineLoader.remove(ctx, engine)
                statusLine = "второе ядро удалено · игры пойдут на Eden"
                render()
            })
        }
        if (buttons.childCount > 0) body.addView(buttons)
        return box
    }

    /** Состояние словами. Не числами и не именами исключений. */
    private fun describe(state: EngineLoader.State): String = when (state) {
        is EngineLoader.State.Builtin -> "Всегда доступен, ничего скачивать не нужно."
        is EngineLoader.State.Ready -> "Скачан, ${state.bytes / 1048576} МБ на устройстве."
        is EngineLoader.State.Missing ->
            "Не скачан. Нужно ${state.bytes / 1048576} МБ один раз: игры, сейвы и ключи общие."
        is EngineLoader.State.Broken -> "Не готов: ${state.reason}"
    }

    private fun statusPill(state: EngineLoader.State): View {
        val ctx = requireContext()
        val (label, color) = when (state) {
            is EngineLoader.State.Builtin -> "встроен" to SymbiosisTheme.MINT
            is EngineLoader.State.Ready -> "готов" to SymbiosisTheme.MINT
            is EngineLoader.State.Missing -> "не скачан" to SymbiosisTheme.AMBER
            is EngineLoader.State.Broken -> "непригоден" to SymbiosisTheme.RED
        }
        return TextView(ctx).apply {
            text = label
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            background = rounded(SymbiosisTheme.soft(color, 0.16f), 10)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
    }

    /** Строка настройки: точка источника, подпись, значение. Вся строка нажимается. */
    private fun settingRow(row: EngineHybrid.Row, index: Int): View {
        val ctx = requireContext()
        val field = EngineHybrid.FIELDS.firstOrNull { it.id == row.id }
        val fromEden = row.source == EngineHybrid.Source.EDEN
        val dot = if (fromEden) SymbiosisTheme.MINT else SymbiosisTheme.PINK

        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = rounded(SymbiosisTheme.SURFACE, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                statusLine = EngineStore.cycleField(ctx, row.id)
                render()
            }
        }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            View(ctx).apply {
                background = rounded(dot, 4)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
            }
        )
        top.addView(
            TextView(ctx).apply {
                text = row.label
                textSize = 15f
                setTextColor(SymbiosisTheme.TEXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        top.addView(
            TextView(ctx).apply {
                text = row.value
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (fromEden) SymbiosisTheme.MINT else SymbiosisTheme.PINK)
                setPadding(dp(8), 0, 0, 0)
            }
        )
        line.addView(top)

        val sub = listOfNotNull(
            if (fromEden) "из Eden" else "своё у Kenji",
            field?.hint?.takeIf { it.isNotEmpty() }
        ).joinToString(" · ")
        line.addView(
            TextView(ctx).apply {
                text = sub
                textSize = 11f
                setTextColor(if (fromEden) SymbiosisTheme.soft(SymbiosisTheme.MINT, 0.85f)
                else SymbiosisTheme.soft(SymbiosisTheme.PINK, 0.85f))
                setPadding(dp(18), dp(3), 0, 0)
            }
        )

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (index > 0) lp.topMargin = dp(2)
        line.layoutParams = lp
        return line
    }

    // ── действия ───────────────────────────────────────────────────────

    private fun fetch(engine: EngineLoader.Engine) {
        if (busy) return
        busy = true

        val ctx = requireContext()
        val accent = SymbiosisTheme.sectionAccent(if (engine == EngineLoader.Engine.EDEN) "eden" else "kenji")
        val status = banner("Скачиваю…", accent)
        root.addView(status)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                EngineDownloader.download(ctx, engine) { done, total ->
                    // Progress arrives on the IO thread; the view must be
                    // touched on the main one. Posting to the view's own
                    // handler keeps this correct without another coroutine
                    // per chunk.
                    val text = if (total > 0) {
                        "Скачиваю… ${done / 1048576} из ${total / 1048576} МБ"
                    } else {
                        "Скачиваю… ${done / 1048576} МБ"
                    }
                    status.post { status.text = text }
                }
            }
            busy = false
            if (!result.ok) {
                status.text = result.message
                status.setTextColor(SymbiosisTheme.RED)
                status.background = rounded(SymbiosisTheme.soft(SymbiosisTheme.RED, 0.14f), 12)
            } else {
                // Load it straight away rather than at the next launch: if the
                // core is going to be refused, the person who just waited for
                // 55 MB should find out now.
                val err = withContext(Dispatchers.IO) { EngineLoader.load(ctx, engine) }
                if (err != null) {
                    status.text = "Скачано, но не загрузилось: $err"
                    status.setTextColor(SymbiosisTheme.AMBER)
                    return@launch
                }
                statusLine = "второе ядро скачано · настройки у него свои"
                render()
            }
        }
    }

    /**
     * Start the core in the isolated :kenji process and report back.
     *
     * The only test that means anything: a core can download intact, verify
     * against its hash and still abort on this particular device. Because it
     * runs in another process, an abort is reported here as a sentence instead
     * of closing the app.
     */
    private fun probe(engine: EngineLoader.Engine) {
        if (busy) return
        busy = true
        val accent = SymbiosisTheme.sectionAccent("kenji")
        val status = banner("Проверяю ядро в отдельном процессе…", accent)
        root.addView(status)

        KenjiProbeService.probe(requireContext()) { ok, message ->
            busy = false
            status.text = if (ok) "Ядро запускается: $message" else "Не запустилось: $message"
            status.setTextColor(if (ok) SymbiosisTheme.MINT else SymbiosisTheme.RED)
            status.background = rounded(
                SymbiosisTheme.soft(if (ok) SymbiosisTheme.MINT else SymbiosisTheme.RED, 0.14f), 12
            )
        }
    }

    private fun kenjiUsable(ctx: android.content.Context): Boolean {
        val state = EngineLoader.state(ctx, EngineLoader.Engine.KENJI)
        return state is EngineLoader.State.Ready || state is EngineLoader.State.Builtin
    }

    // ── мелкие кирпичи ─────────────────────────────────────────────────

    private fun sectionTitle(text: String, accent: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
        setPadding(0, dp(14), 0, dp(6))
    }

    /** Плашка-сообщение: используется и для прогресса, и для итога. */
    private fun banner(text: String, accent: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(accent)
        background = rounded(SymbiosisTheme.soft(accent, 0.12f), 12)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        layoutParams = lp
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
        MaterialButton(requireContext()).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(accent)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                SymbiosisTheme.soft(accent, 0.12f)
            )
            strokeWidth = 0
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(44)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    /** Крупная карточка выбора: два ядра рядом, выбранное подсвечено. */
    private fun choiceCard(
        title: String,
        subtitle: String,
        accent: Int,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(
                if (selected) SymbiosisTheme.soft(accent, 0.16f) else SymbiosisTheme.SURFACE,
                18,
                if (selected) 2 else 1,
                if (selected) accent else SymbiosisTheme.OUTLINE
            )
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }
        box.addView(
            TextView(ctx).apply {
                text = if (selected) "● $title" else "○ $title"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) accent else SymbiosisTheme.TEXT)
            }
        )
        box.addView(
            TextView(ctx).apply {
                text = subtitle
                textSize = 11f
                setTextColor(SymbiosisTheme.TEXT_DIM)
            }
        )
        return box
    }
}
