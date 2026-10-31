// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentManualBinding
import org.yuzu.yuzu_emu.utils.Converter
import org.yuzu.yuzu_emu.utils.ConverterPicker
import org.yuzu.yuzu_emu.utils.Manual
import org.yuzu.yuzu_emu.utils.SymbiosisTheme

/**
 * Инструкция пользования.
 *
 * Содержание - в [Manual], здесь только вёрстка. Так текст можно
 * проверить тестом (каждый раздел обязан ссылаться на существующий экран)
 * и так его видно в `docs/GUIDE.md`: оба места пишутся из одного списка.
 *
 * Кнопки под разделами не декоративные: они ведут туда, о чём раздел.
 * Человек читает «нажмите Ключи» и нажимает, а не ищет экран по памяти.
 */
class ManualFragment : Fragment() {
    private var _binding: FragmentManualBinding? = null
    private val binding get() = _binding!!

    private lateinit var converter: ConverterPicker
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)

        converter = ConverterPicker(
            fragment = this,
            onQueued = { count ->
                snack(
                    getString(R.string.launcher_convert_queued, count) + "\n" +
                        getString(R.string.converter_where, Converter.whereText())
                )
            },
            onFailed = { snack(getString(R.string.launcher_convert_failed)) }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManualBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarManual.setNavigationOnClickListener {
            runCatching { findNavController().popBackStack() }
        }

        binding.editManualSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render(Manual.search(query))
            }
        })

        render(Manual.sections)
        setInsets()
    }

    /** Перерисовать руководство: раздел за разделом, шаг за шагом. */
    private fun render(sections: List<Manual.Section>) {
        if (_binding == null) return
        val container = binding.containerManual
        container.removeAllViews()

        if (sections.isEmpty()) {
            // Пустой результат поиска говорит словами, а не пустым экраном:
            // «ничего не нашлось» и «руководство не загрузилось» - разные
            // вещи, и человеку важно понимать, какая из них случилась.
            val row = layoutInflater.inflate(R.layout.item_manual_step, container, false)
            row.findViewById<TextView>(R.id.text_manual_step_number).apply {
                text = "?"
                setTextColor(SymbiosisTheme.TEXT_DIM)
            }
            row.findViewById<TextView>(R.id.text_manual_step_title)
                .setText(R.string.manual_search_empty_title)
            row.findViewById<TextView>(R.id.text_manual_step_text)
                .setText(R.string.manual_search_empty)
            container.addView(row)
            return
        }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        sections.forEach { section ->
            val card = layoutInflater.inflate(R.layout.card_manual_section, container, false)

            // Цвет раздела: ключ — id раздела, а не позиция. Разделы
            // переставляются при правках текста, и цвет «Двух ядер» не должен
            // уезжать на другое место от того, что выше вставили абзац.
            val accent = SymbiosisTheme.guideAccent(section.id)
            card.findViewById<TextView>(R.id.text_manual_section_title).apply {
                text = section.title
                setTextColor(accent)
            }
            card.findViewById<TextView>(R.id.text_manual_section_intro).text = section.intro
            card.findViewById<View>(R.id.view_manual_accent).setBackgroundColor(accent)
            (card as com.google.android.material.card.MaterialCardView).strokeColor =
                SymbiosisTheme.soft(accent, 0.30f)

            val stepsContainer = card.findViewById<LinearLayout>(R.id.container_manual_steps)
            section.steps.forEachIndexed { index, step ->
                val row = layoutInflater.inflate(R.layout.item_manual_step, stepsContainer, false)
                row.findViewById<TextView>(R.id.text_manual_step_number).apply {
                    text = (index + 1).toString()
                    setTextColor(accent)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(13).toFloat()
                        setColor(SymbiosisTheme.soft(accent, 0.16f))
                    }
                }
                row.findViewById<TextView>(R.id.text_manual_step_title).text = step.title
                row.findViewById<TextView>(R.id.text_manual_step_text).text = step.text
                stepsContainer.addView(row)
            }

            val actionsContainer = card.findViewById<LinearLayout>(R.id.container_manual_actions)
            actionsContainer.isVisible = section.actions.isNotEmpty()
            section.actions.forEachIndexed { index, action ->
                val button = layoutInflater
                    .inflate(R.layout.button_manual_action, actionsContainer, false) as MaterialButton
                button.text = action.label
                // Кнопка в цвете своего раздела: «Открыть два ядра» розовая,
                // потому что розовое — это второе ядро и на других экранах.
                button.setTextColor(accent)
                button.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(SymbiosisTheme.soft(accent, 0.16f))
                button.setOnClickListener { go(action.go) }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (index > 0) params.marginStart = 8.px
                actionsContainer.addView(button, params)
            }

            container.addView(card)
        }
    }

    /**
     * Кнопка раздела → экран.
     *
     * Отдельная функция, потому что разделы ссылаются на экраны по
     * константе [Manual.Go], а не по `R.id`: так `Manual` остаётся чистым
     * Kotlin и проверяется на хосте. Здесь эта константа и превращается в
     * действие; если в навигации экрана нет, человек получает сообщение, а
     * не вылет.
     */
    private fun go(target: Manual.Go) {
        if (target == Manual.Go.CONVERT) {
            converter.pick()
            return
        }
        val action: Int = when (target) {
            Manual.Go.FOLDERS -> R.id.action_global_symbiosisGameFoldersFragment
            Manual.Go.KEYS -> R.id.action_global_keysFirmwareFragment
            Manual.Go.TOOLS -> R.id.action_global_toolsFragment
            Manual.Go.ENGINES -> R.id.action_global_enginesFragment
            Manual.Go.FPS -> R.id.action_global_fpsTuningFragment
            Manual.Go.DIAGNOSTICS -> R.id.action_global_diagnosticsFragment
            Manual.Go.SOURCES -> R.id.action_global_sourcesFragment
            Manual.Go.SETTINGS_GUIDE -> R.id.action_global_guideFragment
            Manual.Go.PANEL -> R.id.action_global_livePanelFragment
            Manual.Go.CONVERT -> 0
        }
        if (action == 0) return
        runCatching { findNavController().navigate(action) }
            .onFailure { snack(getString(R.string.manual_nav_failed)) }
    }

    private fun snack(message: String) {
        if (_binding == null) return
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private val Int.px: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.scrollManual.updatePadding(
                left = bars.left + cutout.left + 16.px,
                right = bars.right + cutout.right + 16.px,
                bottom = bars.bottom
            )
            insets
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
