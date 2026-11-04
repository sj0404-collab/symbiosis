// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.CloudSettings
import org.yuzu.yuzu_emu.utils.CloudSync
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.SavesOverview
import org.yuzu.yuzu_emu.utils.SymbiosisTheme
import org.yuzu.yuzu_emu.utils.WebDavClient

/**
 * Облако: настройка доступа, проверка связи и список игр с их сейвами.
 *
 * ЧТО ЗДЕСЬ МОЖНО СДЕЛАТЬ
 *   * подключить WebDAV (Яндекс.Диск, Nextcloud, Box, свой сервер) —
 *     адрес, логин, пароль приложения;
 *   * проверить связь и увидеть, что именно ответил сервер;
 *   * включить авто-заливку сейвов после игры;
 *   * залить/скачать сейвы любой игры и сам образ игры.
 *
 * ЧЕГО ЗДЕСЬ НЕТ
 *   «Запуска из облака». Эмулятору нужен локальный файл: десятки тысяч
 *   случайных чтений по сети на гигабайтном образе — это не медленно, это
 *   невозможно. Схема: скачать → играть → сейвы уходят обратно.
 */
class CloudFragment : Fragment() {

    private lateinit var root: LinearLayout
    private var statusLine = ""
    private var busy = false

    private lateinit var urlField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var folderField: EditText

    private val games = ArrayList<Game>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(SymbiosisTheme.soft(SymbiosisTheme.VIOLET, 0.12f), SymbiosisTheme.BASE)
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
        loadGames()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun loadGames() {
        games.clear()
        runCatching { GameHelper.rememberedGames() }.getOrNull()?.let { games.addAll(it) }
    }

    private fun render() {
        val ctx = requireContext()
        root.removeAllViews()

        root.addView(
            TextView(ctx).apply {
                text = "Облако"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        root.addView(
            TextView(ctx).apply {
                text = "сейвы в облаке · образы по кнопке"
                textSize = 14f
                setTextColor(SymbiosisTheme.VIOLET)
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

        // ── Подключение ─────────────────────────────────────────────────
        root.addView(sectionTitle("Подключение", SymbiosisTheme.VIOLET))
        root.addView(
            TextView(ctx).apply {
                text = "Нужен доступ по WebDAV. Это есть у Яндекс.Диска, Nextcloud, Box, " +
                    "Облака Mail.ru и домашнего сервера. Пароль — «пароль приложения», " +
                    "а не пароль от аккаунта."
                textSize = 12f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, 0, 0, dp(8))
            }
        )

        val presets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        CloudSettings.PRESETS.forEach { preset ->
            presets.addView(chip(preset.label, SymbiosisTheme.VIOLET) {
                if (preset.url.isNotBlank()) {
                    CloudSettings.url = preset.url
                    urlField.setText(preset.url)
                }
                statusLine = preset.hint
                render()
            })
        }
        root.addView(presets)

        urlField = field("адрес сервера", CloudSettings.url)
        userField = field("логин", CloudSettings.user)
        passField = field("пароль приложения", CloudSettings.password, password = true)
        folderField = field("папка в хранилище", CloudSettings.root)
        root.addView(urlField)
        root.addView(userField)
        root.addView(passField)
        root.addView(folderField)

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        actions.addView(filledButton("Сохранить и проверить", SymbiosisTheme.VIOLET) { saveAndTest() })
        actions.addView(outlineButton("Забыть пароль", SymbiosisTheme.RED) {
            CloudSettings.password = ""
            passField.setText("")
            statusLine = "пароль удалён с устройства"
            render()
        })
        root.addView(actions)

        val auto = SwitchCompat(ctx).apply {
            text = "Заливать сейвы автоматически после игры"
            textSize = 14f
            isChecked = CloudSettings.enabled
            setTextColor(SymbiosisTheme.TEXT)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, on ->
                CloudSettings.enabled = on
                statusLine = if (on) {
                    "авто-заливка включена: после выхода из игры сейвы уходят в облако"
                } else {
                    "авто-заливка выключена"
                }
            }
        }
        root.addView(auto)

        root.addView(
            TextView(ctx).apply {
                text = "Сейчас: " + CloudSettings.whereText() +
                    if (CloudSettings.configured()) "" else " · " + CloudSettings.missing()
                textSize = 11f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, dp(2), 0, dp(10))
            }
        )

        // ── Игры ────────────────────────────────────────────────────────
        root.addView(sectionTitle("Игры и их сейвы", SymbiosisTheme.CYAN))
        if (games.isEmpty()) {
            root.addView(
                TextView(ctx).apply {
                    text = "Библиотека ещё не собрана — сначала откройте «Игры»."
                    textSize = 13f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                }
            )
            return
        }

        // Список считается в фоне: у каждой игры читается папка сейвов.
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                games.map { it to runCatching { CloudSync.localSummary(it) }.getOrNull() }
            }
            if (root.parent == null) return@launch
            rows.forEach { (game, summary) ->
                if (summary == null || !summary.hasSaves) return@forEach
                root.addView(gameRow(game, summary))
            }
        }
    }

    private fun gameRow(game: Game, summary: SavesOverview.Summary): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(SymbiosisTheme.SURFACE, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        box.addView(
            TextView(ctx).apply {
                text = game.title.ifBlank { game.path.substringAfterLast('/') }
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        box.addView(
            TextView(ctx).apply {
                text = "${summary.savesCount} " +
                    SavesOverview.plural(summary.savesCount, "слот", "слота", "слотов") +
                    " · ${summary.sizeText} · не заходили: " + SavesOverview.ago(summary.daysSincePlay)
                textSize = 12f
                setTextColor(SymbiosisTheme.TEXT_DIM)
                setPadding(0, dp(2), 0, dp(6))
            }
        )
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(chip("Сейвы ↑", SymbiosisTheme.MINT) { syncSaves(game, up = true) })
        buttons.addView(chip("Сейвы ↓", SymbiosisTheme.CYAN) { syncSaves(game, up = false) })
        buttons.addView(chip("Образ ↑", SymbiosisTheme.VIOLET) { syncGame(game, up = true) })
        buttons.addView(chip("Образ ↓", SymbiosisTheme.AMBER) { syncGame(game, up = false) })
        box.addView(buttons)
        return box
    }

    private fun syncSaves(game: Game, up: Boolean) = runSync(if (up) "заливаю сейвы" else "скачиваю сейвы") {
        if (up) CloudSync.uploadSaves(game) else CloudSync.downloadSaves(game)
    }

    private fun syncGame(game: Game, up: Boolean) {
        val size = runCatching { java.io.File(game.path).length() }.getOrDefault(0L)
        // Образ — это гигабайты. Предупреждаем до, а не после: по мобильной
        // сети такая заливка может стоить денег.
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (up) "Залить образ в облако?" else "Скачать образ из облака?")
            .setMessage(
                if (up) "Файл: ${SavesOverview.humanBytes(size)}. Это займёт время и трафик. " +
                    "Продолжать можно, только пока приложение открыто."
                else "Образ будет скачан в Download/Symbiosis/Games и запустится оттуда."
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runSync(if (up) "заливаю образ" else "скачиваю образ") {
                    if (up) CloudSync.uploadGame(game) else CloudSync.downloadGame(game)
                }
            }
            .show()
    }

    private fun runSync(what: String, block: () -> CloudSync.Outcome) {
        if (busy) {
            statusLine = "уже идёт другая передача — дождитесь её"
            render()
            return
        }
        busy = true
        statusLine = "$what…"
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { runCatching(block) }
                    .getOrElse { CloudSync.Outcome(false, it.message ?: "не получилось") }
                statusLine = outcome.message
            } finally {
                busy = false
            }
            render()
        }
    }

    private fun saveAndTest() {
        CloudSettings.url = urlField.text.toString()
        CloudSettings.user = userField.text.toString()
        CloudSettings.password = passField.text.toString()
        CloudSettings.root = folderField.text.toString()
        if (!CloudSettings.configured()) {
            statusLine = "не хватает данных: ${CloudSettings.missing()}"
            render()
            return
        }
        runSync("проверяю связь") {
            val check = WebDavClient.test()
            CloudSync.Outcome(check.ok, check.message)
        }
    }

    // ── кирпичи ────────────────────────────────────────────────────────

    private fun field(hint: String, value: String, password: Boolean = false): EditText =
        EditText(requireContext()).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            setTextColor(SymbiosisTheme.TEXT)
            setHintTextColor(SymbiosisTheme.TEXT_DIM)
            background = rounded(SymbiosisTheme.SURFACE, 12, 1, SymbiosisTheme.OUTLINE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

    private fun sectionTitle(text: String, accent: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun chip(text: String, accent: Int, onClick: () -> Unit) =
        MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setTextColor(accent)
            strokeColor = android.content.res.ColorStateList.valueOf(SymbiosisTheme.soft(accent, 0.6f))
            strokeWidth = dp(1)
            backgroundTintList = android.content.res.ColorStateList.valueOf(SymbiosisTheme.SURFACE)
            cornerRadius = dp(12)
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minHeight = dp(38)
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
            gravity = Gravity.CENTER
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
}
