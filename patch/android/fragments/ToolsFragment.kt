// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.transition.MaterialSharedAxis
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.adapters.HomeSettingAdapter
import org.yuzu.yuzu_emu.databinding.FragmentToolsBinding
import org.yuzu.yuzu_emu.model.HomeSetting

/**
 * Everything that is not a setting.
 *
 * The home settings list had grown to mix two unrelated kinds of entry:
 * genuine preferences (graphics, controls, themes) and tools that merely
 * happen to live behind the same gear icon - the launch report, the ROM
 * utilities, the compatibility list, the Mali diagnostics. Scrolling past six
 * tools to reach a setting, or the reverse, was the daily cost of that.
 *
 * Splitting them means each list answers one question. Settings change how the
 * emulator behaves; tools tell you something or do something once.
 */
class ToolsFragment : Fragment() {
    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarTools.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        val nav = { destination: Int ->
            binding.root.findNavController().navigate(destination)
        }

        // Ordered by how often they are actually needed, not alphabetically:
        // the launch report and FPS tuning are what a struggling device calls
        // for, and burying them under the ROM tools helps nobody.
        val tools = mutableListOf<HomeSetting>().apply {
            // Два ядра — первым: это не «ещё одна настройка», а второе ядро
            // эмулятора. Раньше этот экран был доступен только со старого
            // домашнего экрана, то есть после перевода библиотеки на нативную
            // вёрстку до него было не добраться.
            add(
                HomeSetting(
                    R.string.engine_choice,
                    R.string.engine_choice_description,
                    R.drawable.ic_verified,
                    { nav(R.id.action_global_enginesFragment) }
                )
            )
            // Плагины — сразу после ядер: это тот же класс инструмента
            // «починить без пересборки», и искать его в конце списка никто
            // не станет.
            add(
                HomeSetting(
                    R.string.plugins_title,
                    R.string.plugins_description,
                    R.drawable.ic_verified,
                    { nav(R.id.action_global_pluginsFragment) }
                )
            )
            // Облако — рядом с папками игр: это тоже «где лежат данные»,
            // но за пределами телефона.
            add(
                HomeSetting(
                    R.string.cloud_title,
                    R.string.cloud_description,
                    R.drawable.ic_folder_open,
                    { nav(R.id.action_global_cloudFragment) }
                )
            )
            // Журнал ядра — рядом с диагностикой: это тот же вопрос «почему
            // не работает», но про конкретный запуск, а не про устройство.
            add(
                HomeSetting(
                    R.string.kernel_log_title,
                    R.string.kernel_log_description,
                    R.drawable.ic_verified,
                    { nav(R.id.action_global_kernelLogFragment) }
                )
            )
            // Источники — рядом с журналом: это тоже «откуда взять»,
            // только не лог, а сами файлы: каталоги, прямые ссылки, торренты.
            add(
                HomeSetting(
                    R.string.sources_title,
                    R.string.sources_description,
                    R.drawable.ic_folder_open,
                    { nav(R.id.action_global_sourcesFragment) }
                )
            )
            add(
                HomeSetting(
                    R.string.fps_tuning,
                    R.string.fps_tuning_description,
                    R.drawable.ic_speed,
                    { nav(R.id.action_global_fpsTuningFragment) }
                )
            )
            add(
                HomeSetting(
                    R.string.diagnostics,
                    R.string.diagnostics_description,
                    R.drawable.ic_verified,
                    { nav(R.id.action_global_diagnosticsFragment) }
                )
            )
            add(
                HomeSetting(
                    R.string.game_folders,
                    R.string.game_folders_description,
                    R.drawable.ic_folder_open,
                    { nav(R.id.action_global_symbiosisGameFoldersFragment) }
                )
            )
            add(
                HomeSetting(
                    R.string.keys_and_firmware,
                    R.string.keys_and_firmware_description,
                    R.drawable.ic_key,
                    { nav(R.id.action_global_keysFirmwareFragment) }
                )
            )
            add(
                HomeSetting(
                    R.string.utilities,
                    R.string.utilities_description,
                    R.drawable.ic_tune,
                    { nav(R.id.action_global_utilitiesFragment) }
                )
            )
            add(
                HomeSetting(
                    R.string.launchers,
                    R.string.launchers_description,
                    R.drawable.ic_menu,
                    { nav(R.id.action_global_launchersFragment) }
                )
            )
            add(
                HomeSetting(
                    R.string.homebrew_and_compat,
                    R.string.homebrew_description,
                    R.drawable.ic_controller,
                    { nav(R.id.action_global_homebrewFragment) }
                )
            )
            add(
                HomeSetting(
                    R.string.settings_guide,
                    R.string.settings_guide_description,
                    R.drawable.ic_info_outline,
                    { nav(R.id.action_global_guideFragment) }
                )
            )
            // Руководство стоит выше панели и выше справочника настроек:
            // это единственный экран, который объясняет порядок действий
            // целиком, а не одно понятие.
            add(
                HomeSetting(
                    R.string.settings_manual,
                    R.string.settings_manual_description,
                    R.drawable.ic_info_outline,
                    { nav(R.id.action_global_manualFragment) }
                )
            )
            // Панель, содержимое которой обновляется без пересборки APK:
            // страница живёт на GitHub Pages, данные читает локально через
            // мост. Правка вёрстки видна сразу, скачивать 25 МБ не нужно.
            //
            // Названа «расширенной» не для красоты: после перевода
            // библиотеки на нативную вёрстку всё, что нужно каждый день,
            // есть на стартовом экране, а здесь остались редкие вещи -
            // пространства, выбор ядра, тонкие переключатели. Пусть это
            // будет видно из названия, а не выясняется опытным путём.
            add(
                HomeSetting(
                    R.string.live_panel_extended,
                    R.string.live_panel_extended_description,
                    R.drawable.ic_info_outline,
                    { nav(R.id.action_global_livePanelFragment) }
                )
            )
        }

        binding.listTools.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = HomeSettingAdapter(
                requireActivity() as androidx.appcompat.app.AppCompatActivity,
                viewLifecycleOwner,
                tools
            )
        }

        setInsets()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.listTools.updatePadding(
                left = bars.left + cutout.left,
                right = bars.right + cutout.right,
                bottom = bars.bottom
            )
            insets
        }
}
