// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.NativeLibraryPresenter

/**
 * Список библиотеки: одна плитка на файл.
 *
 * Адаптер ничего не решает. Что показать, в каком порядке и что будет по
 * нажатию - это [NativeLibraryPresenter], который проверяется тестом на
 * хосте. Здесь только findViewById и подстановка строк, поэтому «плитка
 * исчезла» или «пометка пропала» можно починить, не трогая логику.
 */
class LauncherTileAdapter(
    private val onClick: (NativeLibraryPresenter.Item) -> Unit,
    private val onLongClick: (NativeLibraryPresenter.Item) -> Unit
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
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.subtitle.isVisible = item.subtitle.isNotBlank()

        holder.badge.text = item.badge
        holder.badge.isVisible = item.badge.isNotBlank()

        holder.note.text = item.note
        holder.note.isVisible = item.note.isNotBlank()
        // Красный только там, где человек ничего не исправит настройками:
        // «нет ключей» лечится ключами. Сжатый образ - рабочая ситуация,
        // поэтому он вторичного цвета, а не аварийного.
        holder.note.setTextColor(
            themedColor(
                holder.note,
                if (item.action == NativeLibraryPresenter.Action.KEYS) {
                    com.google.android.material.R.attr.colorError
                } else {
                    com.google.android.material.R.attr.colorTertiary
                }
            )
        )

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    /**
     * Цвет из темы, без хардкода.
     *
     * Материаловские `colorError` и `colorTertiary` берутся из темы
     * приложения: у светлой и тёмной темы они разные, а жёстко прописанный
     * красный на тёмном фоне читался бы как ошибка вёрстки.
     */
    private fun themedColor(view: TextView, attr: Int): Int {
        val value = TypedValue()
        return if (view.context.theme.resolveAttribute(attr, value, true)) {
            value.data
        } else {
            view.currentTextColor
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_tile_title)
        val subtitle: TextView = view.findViewById(R.id.text_tile_subtitle)
        val badge: TextView = view.findViewById(R.id.chip_tile_badge)
        val note: TextView = view.findViewById(R.id.text_tile_note)
    }
}
