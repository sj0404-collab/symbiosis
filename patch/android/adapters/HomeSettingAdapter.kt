// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleOwner
import org.yuzu.yuzu_emu.databinding.CardHomeOptionBinding
import org.yuzu.yuzu_emu.fragments.MessageDialogFragment
import org.yuzu.yuzu_emu.model.HomeSetting
import org.yuzu.yuzu_emu.utils.SymbiosisTheme
import org.yuzu.yuzu_emu.utils.ViewUtils.marquee
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible
import org.yuzu.yuzu_emu.utils.collect
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder

class HomeSettingAdapter(
    private val activity: AppCompatActivity,
    private val viewLifecycle: LifecycleOwner,
    options: List<HomeSetting>
) : AbstractListAdapter<HomeSetting, HomeSettingAdapter.HomeOptionViewHolder>(options) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomeOptionViewHolder {
        CardHomeOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return HomeOptionViewHolder(it) }
    }

    inner class HomeOptionViewHolder(val binding: CardHomeOptionBinding) :
        AbstractViewHolder<HomeSetting>(binding) {
        override fun bind(model: HomeSetting) {
            binding.optionTitle.text = activity.resources.getString(model.titleId)
            binding.optionDescription.text = activity.resources.getString(model.descriptionId)
            binding.optionIcon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    activity.resources,
                    model.iconId,
                    activity.theme
                )
            )

            // Цвет пункта. Ключ — id строки, а не позиция: пункты
            // переставляются (новые ставятся первыми), и «Инструменты»
            // меняло бы цвет при каждом релизе, если бы ключом был индекс.
            //
            // Цвет здесь не для пестроты: шесть одинаковых серых карточек
            // заставляют читать каждую. Свой цвет у пункта — это тот же
            // якорь, что и цветная полоса в библиотеке.
            val accent = SymbiosisTheme.accent("tool:" + model.titleId)
            val density = binding.root.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            binding.optionIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            binding.optionIcon.setBackgroundDrawable(
                GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(SymbiosisTheme.soft(accent, 0.16f))
                }
            )
            binding.optionIcon.setPadding(dp(8), dp(8), dp(8), dp(8))
            binding.optionIcon.layoutParams = (binding.optionIcon.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                width = dp(40)
                height = dp(40)
                marginStart = dp(16)
            }
            binding.optionAccent.setBackgroundColor(accent)
            binding.root.strokeColor = SymbiosisTheme.soft(accent, 0.30f)
            binding.optionTitle.setTextColor(SymbiosisTheme.TEXT)

            if (!model.isEnabled.invoke()) {
                binding.optionTitle.alpha = 0.5f
                binding.optionDescription.alpha = 0.5f
                binding.optionIcon.alpha = 0.5f
            }

            model.details.collect(viewLifecycle) { updateOptionDetails(it) }
            binding.optionDetail.marquee()

            binding.root.setOnClickListener { onClick(model) }
        }

        private fun onClick(model: HomeSetting) {
            if (model.isEnabled.invoke()) {
                model.onClick.invoke()
            } else {
                MessageDialogFragment.newInstance(
                    activity,
                    titleId = model.disabledTitleId,
                    descriptionId = model.disabledMessageId
                ).show(activity.supportFragmentManager, MessageDialogFragment.TAG)
            }
        }

        private fun updateOptionDetails(detailString: String) {
            if (detailString.isNotEmpty()) {
                binding.optionDetail.text = detailString
                binding.optionDetail.setVisible(true)
            }
        }
    }
}
