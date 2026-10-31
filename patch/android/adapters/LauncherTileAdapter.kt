// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.NativeLibraryPresenter
import org.yuzu.yuzu_emu.utils.SymbiosisTheme

/**
 * Список библиотеки: одна плитка на файл.
 *
 * Адаптер ничего не решает. Что показать, в каком порядке и что будет по
 * нажатию - это [NativeLibraryPresenter], который проверяется тестом на
 * хосте. Здесь только findViewById, подстановка строк и цвет.
 *
 * О ЦВЕТЕ
 *   Оттенок берётся из Title ID: одна и та же игра всегда одного цвета, а
 *   соседние в списке — разных. Это не украшение: в библиотеке на несколько
 *   сотен файлов цвет полосы и буква слева находятся быстрее, чем текст, а
 *   сжатый образ и обновление отличаются цветом ещё до прочтения подписи.
 *
 *   Все цвета приходят из [SymbiosisTheme], поэтому «плитка стала
 *   нечитаемой» — это проверка палитры, а не поиск по всему адаптеру.
 */
class LauncherTileAdapter(
    private val onClick: (NativeLibraryPresenter.Item) -> Unit,
    private val onLongClick: (NativeLibraryPresenter.Item) -> Unit,
    /**
     * Загрузчик обложки: item, картинка, монограмма (запасной вариант).
     *
     * Отдельной лямбдой, а не прямой загрузкой здесь, потому что адаптер не
     * знает модель игры: у него только строки списка. Кто знает — тот и
     * грузит; адаптер только освобождает место под картинку и возвращает
     * букву, если её нет.
     *
     * Необязательный параметр: адаптер используется и там, где обложки не
     * нужны (тесты, превью), и требовать его от всех значило бы ломать
     * вызывающих ради одной строки.
     */
    private val cover: ((NativeLibraryPresenter.Item, ImageView, TextView) -> Unit)? = null
) : RecyclerView.Adapter<LauncherTileAdapter.Holder>() {

    private val items = mutableListOf<NativeLibraryPresenter.Item>()

    fun submit(list: List<NativeLibraryPresenter.Item>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_launcher_tile, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val accent = SymbiosisTheme.accent(item.path.ifBlank { item.title })
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.subtitle.isVisible = item.subtitle.isNotBlank()

        // Сначала буква, потом картинка поверх неё. Порядок важен:
        // переиспользованная ячейка может нести обложку прошлой игры, и если
        // не сбросить, на новой строке на миг покажется чужая картинка.
        holder.cover.isVisible = false
        holder.monogram.isVisible = true

        // Буква-монограмма: обложки у файла может не быть вовсе, а пустой
        // квадрат слева читается как недогруженная картинка.
        holder.monogram.text = SymbiosisTheme.monogram(item.title)
        holder.monogram.setTextColor(accent)
        holder.monogram.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(SymbiosisTheme.soft(accent, 0.16f))
        }

        holder.accent.setBackgroundColor(accent)
        holder.card.setCardBackgroundColor(SymbiosisTheme.mix(SymbiosisTheme.CARD, accent, 0.10f))
        holder.card.strokeColor = SymbiosisTheme.soft(accent, 0.30f)

        val formatColor = SymbiosisTheme.formatColor(item.badge)
        tintChip(holder.badge, formatColor, dp(10))
        holder.badge.text = item.badge
        holder.badge.isVisible = item.badge.isNotBlank()

        // Каким ядром пойдёт игра. У Eden пометки нет: он по умолчанию,
        // и вторая метка на каждой плитке была бы шумом.
        tintChip(holder.core, SymbiosisTheme.PINK, dp(10))
        holder.core.text = item.core
        holder.core.isVisible = item.core.isNotBlank()

        holder.note.text = item.note
        holder.note.isVisible = item.note.isNotBlank()
        // Красный только там, где человек ничего не исправит настройками:
        // «нет ключей» лечится ключами. Сжатый образ - рабочая ситуация,
        // поэтому он в цвете конвертера, а не в аварийном.
        holder.note.setTextColor(
            if (item.action == NativeLibraryPresenter.Action.KEYS) {
                SymbiosisTheme.RED
            } else {
                SymbiosisTheme.AMBER
            }
        )

        cover?.let { load ->
            load(
                item,
                holder.cover,
                holder.monogram
            )
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    /** Чип в цвете своей метки: подложка прозрачная, текст яркий. */
    private fun tintChip(chip: Chip, color: Int, radius: Int) {
        chip.setTextColor(color)
        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
            SymbiosisTheme.soft(color, 0.16f)
        )
        chip.chipStrokeWidth = 0f
        chip.chipCornerRadius = radius.toFloat()
        chip.chipMinHeight = radius * 2.4f
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_launcher_tile)
        val accent: View = view.findViewById(R.id.view_tile_accent)
        val monogram: TextView = view.findViewById(R.id.text_tile_monogram)
        val cover: ImageView = view.findViewById(R.id.image_tile_cover)
        val title: TextView = view.findViewById(R.id.text_tile_title)
        val subtitle: TextView = view.findViewById(R.id.text_tile_subtitle)
        val badge: Chip = view.findViewById(R.id.chip_tile_badge)
        val core: Chip = view.findViewById(R.id.chip_tile_core)
        val note: TextView = view.findViewById(R.id.text_tile_note)
    }
}
