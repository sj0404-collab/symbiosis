// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.CardGameFolderBinding
import org.yuzu.yuzu_emu.utils.GameFolderScanner
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder

/**
 * One row per game folder: its name, how many games it holds and what they
 * weigh.
 */
class GameFolderAdapter(
    private val activity: AppCompatActivity,
    folders: List<GameFolderScanner.Folder>,
    private val onClick: (GameFolderScanner.Folder) -> Unit,
    /** Долгое нажатие — убрать папку. Необязательно: в списке игр внутри
     *  папки этот адаптер не используется, и там обработчик не нужен. */
    private val onLongClick: ((GameFolderScanner.Folder) -> Unit)? = null
) : AbstractListAdapter<GameFolderScanner.Folder, GameFolderAdapter.FolderViewHolder>(folders) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder =
        FolderViewHolder(
            CardGameFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    inner class FolderViewHolder(val binding: CardGameFolderBinding) :
        AbstractViewHolder<GameFolderScanner.Folder>(binding) {

        override fun bind(model: GameFolderScanner.Folder) {
            binding.textFolderName.text = model.displayName

            if (model.unreadable) {
                // Say so plainly. An unreadable folder reported as "0 games"
                // looks like an empty one, and the user goes looking for the
                // wrong problem - usually a revoked storage permission.
                binding.textFolderStats.text =
                    activity.getString(R.string.folder_unreadable)
                binding.textFolderSize.isVisible = false
            } else {
                binding.textFolderStats.text = activity.resources.getQuantityString(
                    R.plurals.folder_game_count, model.gameCount, model.gameCount
                )
                binding.textFolderSize.isVisible = true
                binding.textFolderSize.text = GameFolderScanner.humanSize(model.totalBytes)
            }

            binding.root.setOnClickListener { onClick(model) }
            binding.root.setOnLongClickListener {
                val handler = onLongClick
                if (handler == null) {
                    false
                } else {
                    handler(model)
                    true
                }
            }
        }
    }
}
