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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.utils.EngineDownloader
import org.yuzu.yuzu_emu.utils.EngineHybrid
import org.yuzu.yuzu_emu.utils.DeviceCompat
import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.EngineStore
import org.yuzu.yuzu_emu.utils.KenjiProbeService
import org.yuzu.yuzu_emu.utils.Spaces
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
    /**
     * Текущая плашка прогресса.
     *
     * Нужна, чтобы отличить «моя плашка ещё на экране» от «экран уже
     * перерисован, и писать в старую вьюху нельзя».
     */
    private var _status: TextView? = null
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
                // У LayoutParams нет marginVertical (это свойство появилось
                // позже и не во всех версиях): верх и низ ставим отдельно.
                layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT)
                    .apply {
                        topMargin = dp(12)
                        bottomMargin = dp(12)
                    }
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
            // Результат проверки показывается ВНУТРИ карточки, прямо под
            // кнопкой.
            //
            // Раньше `probe()` добавлял строку состояния в самый низ экрана,
            // а экран длиннее диагонали телефона: нажатие выглядело как
            // «кнопка не работает» — человек оставался на карточке, а ответ
            // появлялся за три экрана ниже. Теперь статус здесь и итог
            // диалогом, который невозможно не заметить.
            buttons.addView(filledButton("Проверить", accent) { probe(engine, body) })
        }
        if (state is EngineLoader.State.Missing || state is EngineLoader.State.Broken) {
            // Кнопка показывается ВСЕГДА.
            //
            // Здесь стояло условие «если устройство поддерживается» — и на
            // телефоне, который проверку не проходил, карточка оставалась
            // без единой кнопки и без объяснения. Это и выглядело как
            // «кнопка не нажимается»: нажимать было нечего.
            //
            // Не подходит устройство — кнопка неактивна и рядом написано,
            // почему и что делать вместо этого.
            val compat = runCatching { EngineLoader.verdict(ctx, engine) }
                .getOrNull()
            val allowed = compat?.supported ?: true
            val mb = (EngineLoader.KNOWN_SIZE[engine] ?: 0L) / 1048576
            val label = if (allowed) "Скачать ($mb МБ)" else "Скачать нельзя"
            buttons.addView(
                filledButton(label, accent) {
                    if (allowed) fetch(engine) else statusLine = compat?.advice.orEmpty()
                }.apply { isEnabled = true }
            )
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
        if (!isEden) body.addView(compatBlock())
        return box
    }

    /**
     * Что именно проверено на этом телефоне, и что делать, если не подходит.
     *
     * Блок стоит под карточкой второго ядра, потому что вопрос «почему у
     * других работает, а у меня нет» возникает именно здесь. Показываются
     * факты, по которым это можно решить без догадок: архитектура, версия
     * системы, размер страниц памяти, выравнивание скачанного файла.
     */
    private fun compatBlock(): View {
        val ctx = requireContext()
        val verdict = runCatching { EngineLoader.verdict(ctx, EngineLoader.Engine.KENJI) }
            .getOrNull()
        val official = runCatching { Spaces.official(ctx) }.getOrNull()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(14))
        }
        column.addView(
            TextView(ctx).apply {
                text = "Проверено на этом устройстве"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT_DIM)
            }
        )
        verdict?.details?.forEach { line ->
            column.addView(
                TextView(ctx).apply {
                    text = "· $line"
                    textSize = 12f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }
        if (verdict != null && !verdict.supported) {
            column.addView(
                TextView(ctx).apply {
                    text = verdict.headline
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(SymbiosisTheme.AMBER)
                    setPadding(0, dp(8), 0, 0)
                }
            )
            if (verdict.advice.isNotEmpty()) {
                column.addView(
                    TextView(ctx).apply {
                        text = verdict.advice
                        textSize = 12f
                        setTextColor(SymbiosisTheme.TEXT_DIM)
                        setPadding(0, dp(4), 0, 0)
                    }
                )
            }
        }

        // Официальный APK Kenji на этом устройстве работает — значит его и
        // предлагаем, вместо «ядро несовместимо, до свидания».
        if (official != null && official.installed) {
            column.addView(
                TextView(ctx).apply {
                    text = "Установлен «${official.label}» — им можно играть так же, " +
                        "как оригинальным приложением."
                    textSize = 12f
                    setTextColor(SymbiosisTheme.MINT)
                    setPadding(0, dp(10), 0, dp(6))
                }
            )
            column.addView(
                outlineButton("Играть через «${official.label}»", SymbiosisTheme.MINT) {
                    val opened = runCatching {
                        Spaces.openOfficial(requireContext().applicationContext, "")
                    }.getOrNull()
                    val ok = opened != null && opened.contains("\"ok\":true")
                    statusLine = if (ok) "открыл «${official.label}»" else
                        "не удалось открыть «${official.label}»"
                    render()
                }
            )
        }
        return column
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
                // Значение вроде «выкл · Dynarmic» не должно выдавливать
                // подпись за край на узком экране: уступает половину строки.
                maxWidth = (resources.displayMetrics.widthPixels * 0.46f).toInt()
                maxLines = 2
            }
        )
        line.addView(top)

        line.addView(
            TextView(ctx).apply {
                text = if (fromEden) "из Eden · общий с Eden" else "своё у Kenji · Eden не трогает"
                textSize = 11f
                setTextColor(if (fromEden) SymbiosisTheme.soft(SymbiosisTheme.MINT, 0.85f)
                else SymbiosisTheme.soft(SymbiosisTheme.PINK, 0.85f))
                setPadding(dp(18), dp(3), 0, 0)
            }
        )
        // Пояснение — отдельной строкой и приглушённо: оно нужно один раз,
        // а прижать его к пометке источника значило бы спорить с цветом.
        if (field != null && field.hint.isNotEmpty()) {
            line.addView(
                TextView(ctx).apply {
                    text = field.hint
                    textSize = 11f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                    setPadding(dp(18), dp(2), 0, dp(2))
                }
            )
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (index > 0) lp.topMargin = dp(2)
        line.layoutParams = lp
        return line
    }

    // ── действия ───────────────────────────────────────────────────────

    /**
     * Скачивание второго ядра.
     *
     * Три вещи, из-за которых эта кнопка раньше «не нажималась»:
     *
     *  1. Флаг [busy] снимался после загрузки, но не снимался при отмене:
     *     стоило уйти с экрана во время скачивания — и корутина отменялась,
     *     строка `busy = false` не выполнялась, и все последующие нажатия
     *     молча игнорировались до пересоздания экрана. Теперь `finally`.
     *  2. Плашка прогресса добавлялась в конец прокрутки, то есть ниже
     *     видимой части: человек нажимал кнопку и не видел ничего. Теперь
     *     она первая сверху — рядом с кнопкой, которую нажали.
     *  3. Любое исключение, кроме сетевого, роняло корутину без сообщения.
     *     Теперь ловится всё, и текст попадает в ту же плашку.
     */
    private fun fetch(engine: EngineLoader.Engine) {
        if (busy) {
            statusLine = "скачивание уже идёт — подождите"
            render()
            return
        }
        busy = true

        val ctx = requireContext()
        val accent = SymbiosisTheme.sectionAccent(
            if (engine == EngineLoader.Engine.EDEN) "eden" else "kenji"
        )
        val status = banner("Скачиваю… 0 МБ", accent)
        // Нулевая позиция: плашка должна быть там, где на неё смотрят.
        root.addView(status, 0)

        fun settle(text: String, color: Int) {
            if (_status == status) _status = null
            if (status.parent == null) return
            status.text = text
            status.setTextColor(color)
            status.background = rounded(SymbiosisTheme.soft(color, 0.14f), 12)
        }

        _status = status
        viewLifecycleOwner.lifecycleScope.launch {
            try {
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
                        status.post { if (status.parent != null) status.text = text }
                    }
                }
                if (!result.ok) {
                    settle(result.message, SymbiosisTheme.RED)
                    return@launch
                }

                // Скачано — проверяем, загрузится ли именно на этом
                // телефоне. «Скачалось, а не запускается» должно быть
                // названо здесь, а не выясняться при первом запуске игры.
                val verdict = withContext(Dispatchers.IO) {
                    EngineLoader.verdict(ctx, engine)
                }
                if (!verdict.supported) {
                    val text = "Скачано, но это устройство ядро не запустит: " +
                        verdict.headline + ". " + verdict.advice
                    // Перерисовка стирает плашку, поэтому итог остаётся
                    // строкой состояния — она живёт до следующего действия.
                    statusLine = text
                    settle(text, SymbiosisTheme.AMBER)
                    render()
                    return@launch
                }

                // Load it straight away rather than at the next launch: if the
                // core is going to be refused, the person who just waited for
                // 57 MB should find out now.
                val err = withContext(Dispatchers.IO) { EngineLoader.load(ctx, engine) }
                if (err != null) {
                    val text = "Скачано, но не загрузилось: $err"
                    statusLine = text
                    settle(text, SymbiosisTheme.AMBER)
                    render()
                    return@launch
                }
                statusLine = "второе ядро скачано · настройки у него свои"
                render()
            } catch (t: Throwable) {
                // Отмена корутины — не ошибка и не повод для красной плашки,
                // но флаг снять обязан `finally`.
                if (t is CancellationException) return@launch
                settle(
                    "скачивание прервалось: ${t.message ?: t.javaClass.simpleName}",
                    SymbiosisTheme.RED
                )
            } finally {
                busy = false
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
    /**
     * Проверить второе ядро.
     *
     * ЧТО ИСПРАВЛЕНО
     *   Кнопка была, сервис изолированного процесса был, а выглядело это как
     *   неработающая кнопка: ответ печатался в конец длинного экрана, кнопка
     *   не блокировалась, а при упоре в таймаут на экране не менялось вообще
     *   ничего. Человек нажимал второй раз, третий — и оставался без ответа.
     *
     *   Теперь: строка «проверяю» появляется ПОД кнопкой, через неё же видно
     *   секунды, итог приходит диалогом с причиной и кнопкой «Скопировать»,
     *   а флаг «идёт проверка» снимается в finally, чем бы дело ни кончилось.
     *
     * ЗАЧЕМ ПРЕ-ПРОВЕРКА
     *   Часть отказов видна без запуска ядра: файла нет, он обрезан, ELF не
     *   читается, выравнивание не то. Такие ответы мгновенные и точные, и
     *   ждать 30 секунд изолированного процесса незачем. В изолированный
     *   процесс идём только когда файл формально пригоден.
     */
    private fun probe(engine: EngineLoader.Engine, host: LinearLayout) {
        if (busy) return
        busy = true
        val ctx = requireContext()
        val accent = SymbiosisTheme.sectionAccent("kenji")

        val status = banner("Проверяю ядро в отдельном процессе…", accent)
        host.addView(status)

        val started = System.currentTimeMillis()
        val ticker = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val sec = (System.currentTimeMillis() - started) / 1000
                status.text = "Проверяю ядро в отдельном процессе… ${sec} с"
                if (busy) ticker.postDelayed(this, 1000)
            }
        }
        ticker.postDelayed(tick, 1000)

        val finish = { ok: Boolean, message: String ->
            busy = false
            ticker.removeCallbacks(tick)
            status.text = if (ok) "Ядро запускается: $message" else "Не запустилось: $message"
            status.setTextColor(if (ok) SymbiosisTheme.MINT else SymbiosisTheme.RED)
            status.background = rounded(
                SymbiosisTheme.soft(if (ok) SymbiosisTheme.MINT else SymbiosisTheme.RED, 0.14f),
                12
            )
            showProbeReport(ctx, ok, message)
        }

        // --- что известно до запуска ------------------------------------
        val verdict = runCatching { EngineLoader.verdict(ctx, engine) }.getOrNull()
        val file = runCatching { EngineLoader.coreFile(ctx, engine) }.getOrNull()
        val sizeMb = (file?.length() ?: 0L) / 1048576
        if (file == null || !file.isFile) {
            finish(false, "файла ядра нет — сначала нажмите «Скачать»")
            return
        }
        if (verdict != null && !verdict.supported) {
            finish(false, verdict.headline + ". " + verdict.advice)
            return
        }
        if (sizeMb <= 0) {
            finish(false, "файл ядра пустой — скачивание не состоялось")
            return
        }

        runCatching {
            KenjiProbeService.probe(ctx) { ok, message -> finish(ok, message) }
        }.onFailure {
            finish(false, "проверка не запустилась: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    /**
     * Итог проверки диалогом.
     *
     * Строка в карточке — чтобы видеть, что кнопка сработала, а диалог —
     * чтобы ответ нельзя было пропустить: результат читают один раз, и
     * пересказывать его в чат поддержки будут копированием отсюда.
     */
    private fun showProbeReport(ctx: android.content.Context, ok: Boolean, message: String) {
        val facts = runCatching { DeviceCompat.describe(EngineLoader.device()) }.getOrDefault(emptyList())
        val text = buildString {
            append(if (ok) "Ядро запускается." else "Ядро не запустилось.")
            append("\n\n")
            append(message)
            if (facts.isNotEmpty()) {
                append("\n\nУстройство:\n")
                facts.forEach { append("• ").append(it).append('\n') }
            }
            val align = runCatching { EngineLoader.coreAlign(ctx, EngineLoader.Engine.KENJI) }.getOrNull()
            if (align != null) {
                append("\nЯдро: выравнивание ").append(align / 1024).append(" КБ")
            }
        }.trim()

        if (!isAdded) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(if (ok) "Проверка ядра" else "Почему не запустилось")
            .setMessage(text)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Скопировать") { _, _ ->
                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Проверка ядра", text))
            }
            .show()
    }

    /**
     * Скачано ли второе ядро, и можно ли на нём запускать.
     *
     * Вынесено отдельной функцией, потому что этот вопрос задают два разных
     * места экрана — карточка ядра и «ядро по умолчанию». Две копии одной
     * проверки разошлись бы при первом же изменении условия.
     */
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
