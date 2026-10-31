// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.utils.EngineDownloader
import org.yuzu.yuzu_emu.utils.EngineHybrid
import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.EngineStore
import org.yuzu.yuzu_emu.utils.KenjiProbeService

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
 * Экран собран кодом, а не XML: строк становится больше, и каждая новая
 * строка в разметке — ещё один файл, который надо не забыть скопировать в
 * дерево сборки (в этом проекте это уже случалось дважды).
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
        val scroll = android.widget.ScrollView(requireContext())
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
            v.updatePadding(top = bars.top + dp(16), bottom = bars.bottom + dp(32))
            insets
        }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val ctx = requireContext()
        root.removeAllViews()

        root.addView(heading("Два ядра в одном"))
        root.addView(
            note(
                "Eden встроен в приложение, Kenji-NX (~55 МБ) скачивается один раз и играет " +
                    "в процессе :kenji: если упадёт, лаунчер останется. Ядро выбирается не на " +
                    "всё приложение, а на игру — долгое нажатие на плитке в библиотеке."
            )
        )
        if (statusLine.isNotEmpty()) {
            root.addView(
                TextView(ctx).apply {
                    text = statusLine
                    textSize = 14f
                    setTextColor(accent())
                    setPadding(0, 0, 0, dp(8))
                }
            )
        }

        for (engine in EngineLoader.Engine.values()) {
            root.addView(engineRow(engine))
        }

        if (!EngineLoader.deviceSupported()) {
            root.addView(
                note(
                    "Это устройство не 64-битное (arm64), поэтому скачиваемое ядро на нём " +
                        "не запустится. Доступен только Eden."
                )
            )
        }

        root.addView(heading("Связь настроек"))
        root.addView(
            note(
                "«из Eden» — значение берётся у основного ядра, пока вы не измените его здесь. " +
                    "«своё» — принадлежит второму ядру, Eden его не перетирает. Нажмите строку, " +
                    "чтобы переключить."
            )
        )
        EngineStore.rows(ctx).forEach { row -> root.addView(settingRow(row)) }
        root.addView(
            note(
                "Второе ядро сейчас: " + EngineStore.summary(ctx)
            )
        )
        root.addView(
            button("Вернуть значения Eden") {
                statusLine = EngineStore.resetFields(ctx)
                render()
            }
        )

        root.addView(heading("Ядро по умолчанию"))
        root.addView(
            note(
                "С ним запускаются игры, у которых не выбран свой ядро. Сейчас: " +
                    EngineStore.globalCore(ctx).label + "."
            )
        )
        val cores = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        cores.addView(button("Eden") {
            EngineStore.setGlobalCore(ctx, EngineHybrid.Core.EDEN)
            statusLine = "по умолчанию: Eden"
            render()
        })
        cores.addView(button("Kenji-NX") {
            val state = EngineLoader.state(ctx, EngineLoader.Engine.KENJI)
            val usable = state is EngineLoader.State.Ready || state is EngineLoader.State.Builtin
            if (!usable) {
                statusLine = "сначала скачайте второе ядро — без него игры пойдут на Eden"
                render()
                return@button
            }
            EngineStore.setGlobalCore(ctx, EngineHybrid.Core.KENJI)
            statusLine = "по умолчанию: Kenji-NX"
            render()
        })
        root.addView(cores)
    }

    /** Ядро: состояние и действия. Оба видны всегда — это и есть «в одном». */
    private fun engineRow(engine: EngineLoader.Engine): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp
            background = cardBackground()
        }

        val isDefault = EngineStore.globalCore(ctx) == EngineHybrid.Core.of(engine.id)
        val state = EngineLoader.state(ctx, engine)

        box.addView(
            TextView(ctx).apply {
                text = if (isDefault) "${engine.label}  ✓ по умолчанию" else engine.label
                textSize = 17f
            }
        )

        val describe = when (state) {
            is EngineLoader.State.Builtin ->
                "встроен в приложение · всегда доступен · свой конфиг Eden"
            is EngineLoader.State.Ready ->
                "скачан · ${state.bytes / 1048576} МБ на устройстве · свой конфиг Kenji"
            is EngineLoader.State.Missing ->
                "не скачан · потребуется ${state.bytes / 1048576} МБ загрузки"
            is EngineLoader.State.Broken ->
                "не готов: ${state.reason}"
        }
        box.addView(note(describe))

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val usable = state is EngineLoader.State.Builtin || state is EngineLoader.State.Ready

        if (usable && engine == EngineLoader.Engine.KENJI) {
            buttons.addView(button("Проверить") { probe(engine) })
        }
        if (state is EngineLoader.State.Missing || state is EngineLoader.State.Broken) {
            if (EngineLoader.deviceSupported()) {
                val mb = (EngineLoader.KNOWN_SIZE[engine] ?: 0L) / 1048576
                buttons.addView(button("Скачать ($mb МБ)") { fetch(engine) })
            }
        }
        if (state is EngineLoader.State.Ready) {
            buttons.addView(button("Удалить") {
                if (EngineStore.globalCore(ctx) == EngineHybrid.Core.KENJI) {
                    EngineStore.setGlobalCore(ctx, EngineHybrid.Core.EDEN)
                }
                EngineLoader.remove(ctx, engine)
                statusLine = "второе ядро удалено · игры пойдут на Eden"
                render()
            })
        }

        if (buttons.childCount > 0) box.addView(buttons)
        return box
    }

    /** Строка настройки: нажатие переключает значение. */
    private fun settingRow(row: EngineHybrid.Row): View {
        val ctx = requireContext()
        val field = EngineHybrid.FIELDS.firstOrNull { it.id == row.id }
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                statusLine = EngineStore.cycleField(ctx, row.id)
                render()
            }
        }
        line.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                addView(
                    TextView(ctx).apply {
                        text = row.label
                        textSize = 15f
                    }
                )
                if (field != null && field.hint.isNotEmpty()) {
                    addView(note(field.hint))
                }
            }
        )
        line.addView(
            TextView(ctx).apply {
                text = row.value
                textSize = 15f
                setTextColor(accent())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = dp(8)
                layoutParams = lp
            }
        )
        line.addView(
            TextView(ctx).apply {
                text = when (row.source) {
                    EngineHybrid.Source.EDEN -> "из Eden"
                    EngineHybrid.Source.OWN -> "своё"
                }
                textSize = 12f
                alpha = 0.7f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = dp(8)
                layoutParams = lp
            }
        )
        return line
    }

    private fun fetch(engine: EngineLoader.Engine) {
        if (busy) return
        busy = true

        val ctx = requireContext()
        val status = note("Скачиваю…")
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
            } else {
                // Load it straight away rather than at the next launch: if the
                // core is going to be refused, the person who just waited for
                // 55 MB should find out now.
                val err = withContext(Dispatchers.IO) { EngineLoader.load(ctx, engine) }
                if (err != null) {
                    status.text = "Скачано, но не загрузилось: $err"
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
        val status = note("Проверяю ядро в отдельном процессе…")
        root.addView(status)

        KenjiProbeService.probe(requireContext()) { ok, message ->
            busy = false
            status.text = if (ok) "Ядро запускается: $message" else "Не запустилось: $message"
        }
    }

    private fun accent(): Int = resolveAttr(com.google.android.material.R.attr.colorPrimary)

    private fun cardBackground(): android.graphics.drawable.Drawable? {
        val view = TextView(requireContext())
        val value = android.util.TypedValue()
        val theme = view.context.theme
        val ok = theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, value, true)
        return if (ok) {
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(value.data)
            }
        } else {
            null
        }
    }

    private fun resolveAttr(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(attr, value, true)) {
            value.data
        } else {
            ContextCompat.getColor(requireContext(), android.R.color.primary_text_dark)
        }
    }

    private fun heading(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 20f
        setPadding(0, dp(12), 0, dp(8))
    }

    private fun note(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        alpha = 0.75f
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(requireContext()).apply {
        this.text = text
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.rightMargin = dp(8)
        layoutParams = lp
        setOnClickListener { onClick() }
    }
}
