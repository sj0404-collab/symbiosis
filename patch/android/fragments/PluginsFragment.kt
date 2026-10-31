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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import org.yuzu.yuzu_emu.utils.BuiltinPlugins
import org.yuzu.yuzu_emu.utils.PluginStore
import org.yuzu.yuzu_emu.utils.SymbiosisTheme

/**
 * Плагины: правки поведения без новой сборки.
 *
 * ЗАЧЕМ ЭТОТ ЭКРАН ВООБЩЕ НУЖЕН
 *   Если что-то работает плохо, есть два пути: ждать новую сборку или
 *   поправить правилом. Второй полезен только когда видно, что включено, что
 *   не разобралось и почему. Поэтому здесь не «список файлов», а состояния:
 *   включён / выключен / ошибка с причиной.
 *
 * ЧЕГО ЗДЕСЬ НАМЕРЕННО НЕТ
 *   Кнопки «загрузить из интернета». Плагин не исполняет код, но всё равно
 *   меняет настройки запуска, и тянуть его с неизвестного адреса — плохая
 *   идея. Человек кладёт файл сам в папку, путь к которой видно и можно
 *   скопировать.
 */
class PluginsFragment : Fragment() {

    private lateinit var root: LinearLayout
    private var statusLine = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(SymbiosisTheme.soft(SymbiosisTheme.VIOLET, 0.10f), SymbiosisTheme.BASE)
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

        root.addView(
            TextView(ctx).apply {
                text = "Плагины"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        root.addView(
            TextView(ctx).apply {
                text = "Правки поведения без новой сборки"
                textSize = 14f
                setTextColor(SymbiosisTheme.VIOLET)
                setPadding(0, dp(2), 0, dp(12))
            }
        )

        if (statusLine.isNotEmpty()) {
            root.addView(banner(statusLine, SymbiosisTheme.VIOLET))
        }

        root.addView(
            note(
                "Плагин — это набор правил: «если у игры такой признак — такая настройка, " +
                    "такое ядро или такая пометка». Код он не исполняет, поэтому испортить " +
                    "приложение не может: худшее — неверная настройка, и это видно в списке."
            )
        )

        val installed = runCatching { PluginStore.installed(ctx) }.getOrDefault(emptyList())
        val broken = installed.count { !it.usable }
        val on = installed.count { it.enabled && it.usable }

        root.addView(
            TextView(ctx).apply {
                text = "Включено $on из ${installed.size}" +
                    if (broken > 0) " · с ошибкой: $broken" else ""
                textSize = 13f
                setTextColor(if (broken > 0) SymbiosisTheme.AMBER else SymbiosisTheme.MINT)
                setPadding(0, dp(4), 0, dp(10))
            }
        )

        installed.forEach { item -> root.addView(pluginCard(item)) }

        root.addView(sectionTitle("Папка для своих правил", SymbiosisTheme.VIOLET))
        val folder = runCatching { PluginStore.dir(ctx).absolutePath }.getOrDefault("(нет доступа)")
        root.addView(
            TextView(ctx).apply {
                text = folder
                textSize = 12f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                typeface = Typeface.MONOSPACE
                background = rounded(SymbiosisTheme.SURFACE, 10, 1, SymbiosisTheme.OUTLINE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
        )
        root.addView(
            note(
                "Положите туда .json в том же формате, что у образца «${BuiltinPlugins.TEMPLATE}», " +
                    "и нажмите «Перечитать». Файл с тем же id, что у встроенного, заменяет его — " +
                    "так чинится встроенное правило без ожидания сборки."
            )
        )

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(6))
        }
        row.addView(button("Перечитать", SymbiosisTheme.VIOLET) {
            statusLine = "перечитано"
            render()
        })
        row.addView(button("Скопировать путь", SymbiosisTheme.BLUE) { copyPath(folder) })
        root.addView(row)

        root.addView(sectionTitle("Что можно в условии", SymbiosisTheme.CYAN))
        root.addView(
            note(
                "title, path, ext, titleId, gpu, engine, ramGb. Сравнение — шаблон со звёздочкой " +
                    "(«*mali*») или числа: меньше, не больше, больше, не меньше. Несколько полей в " +
                    "одном условии — «и». Массив — «любое из»."
            )
        )
        root.addView(sectionTitle("Что можно сделать", SymbiosisTheme.CYAN))
        root.addView(
            note(
                "setting — выставить настройки ядра (только те, что есть на экране «Два ядра»); " +
                    "core — запускать этой игрой другое ядро; file — пометка на плитке игры; " +
                    "banner — сообщение над списком."
            )
        )
    }

    /** Карточка плагина: имя, источник, правила и переключатель. */
    private fun pluginCard(item: PluginStore.Installed): View {
        val ctx = requireContext()
        val accent = when {
            !item.usable -> SymbiosisTheme.RED
            item.enabled -> SymbiosisTheme.VIOLET
            else -> SymbiosisTheme.OUTLINE
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SymbiosisTheme.mix(SymbiosisTheme.CARD, accent, 0.10f), 18, 1,
                SymbiosisTheme.soft(accent, 0.35f))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(ctx).apply {
                text = item.name
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        head.addView(
            TextView(ctx).apply {
                text = if (item.usable) {
                    if (item.enabled) "включён" else "выключен"
                } else {
                    "ошибка"
                }
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
                background = rounded(SymbiosisTheme.soft(accent, 0.16f), 10)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
        )
        box.addView(head)

        val source = when (item.source) {
            PluginStore.Source.BUILTIN -> "встроенный"
            PluginStore.Source.FILE -> "файл ${item.fileName}"
        }
        box.addView(
            TextView(ctx).apply {
                text = "${item.id} · $source" + (item.spec?.let { " · приоритет ${it.priority}" } ?: "")
                textSize = 11f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, dp(3), 0, 0)
            }
        )

        if (!item.usable) {
            box.addView(
                TextView(ctx).apply {
                    text = item.error
                    textSize = 12.5f
                    setTextColor(SymbiosisTheme.RED)
                    setPadding(0, dp(8), 0, 0)
                }
            )
            return box
        }

        val spec = item.spec!!
        if (spec.description.isNotEmpty()) {
            box.addView(
                TextView(ctx).apply {
                    text = spec.description
                    textSize = 12.5f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }

        // Правила показываем списком: именно они — «что плагин делает», и
        // по ним видно, почему что-то сработало не так, как ждали.
        spec.rules.take(4).forEach { rule ->
            box.addView(
                TextView(ctx).apply {
                    text = "• " + rule.describe()
                    textSize = 12f
                    setTextColor(if (item.enabled) SymbiosisTheme.TEXT else SymbiosisTheme.TEXT_DIM)
                    setPadding(0, dp(5), 0, 0)
                }
            )
        }
        if (spec.rules.size > 4) {
            box.addView(
                TextView(ctx).apply {
                    text = "…и ещё ${spec.rules.size - 4}"
                    textSize = 11f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }

        val act = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        act.addView(
            button(if (item.enabled) "Выключить" else "Включить", accent) {
                runCatching { PluginStore.setEnabled(ctx, item.id, !item.enabled) }
                statusLine = if (item.enabled) "«${item.name}» выключен" else "«${item.name}» включён"
                render()
            }
        )
        box.addView(act)
        return box
    }

    private fun copyPath(path: String) {
        val ok = runCatching {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("plugins", path))
        }.isSuccess
        statusLine = if (ok) "путь скопирован" else "не удалось скопировать путь"
        render()
    }

    private fun sectionTitle(text: String, accent: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun note(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(SymbiosisTheme.TEXT_DIM)
        setPadding(0, 0, 0, dp(6))
    }

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

    private fun button(text: String, accent: Int, onClick: () -> Unit) =
        MaterialButton(requireContext()).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(accent)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                SymbiosisTheme.soft(accent, 0.14f)
            )
            strokeWidth = 0
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(42)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }
}
