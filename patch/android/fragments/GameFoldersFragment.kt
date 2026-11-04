// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.adapters.GameEntryAdapter
import org.yuzu.yuzu_emu.adapters.GameFolderAdapter
import org.yuzu.yuzu_emu.databinding.FragmentGameFoldersBinding
import org.yuzu.yuzu_emu.utils.GameFolderList
import org.yuzu.yuzu_emu.utils.GameFolderScanner
import org.yuzu.yuzu_emu.utils.GameFoldersStore
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.NativeSymbiosis

/**
 * Lists the folders that hold games, rather than the games themselves.
 *
 * The default screen shows a flat list of every title, which stops being
 * useful once there are more than a handful and says nothing about where they
 * live or what they cost in storage. This shows each folder with its game
 * count and total size, so "which of these is eating my SD card" is answerable
 * at a glance.
 *
 * Nothing is cached. The folder list in `config.ini` is read and the contents
 * counted on every visit: a cache is what made a deleted game keep appearing
 * and a newly copied one stay invisible, and the scan is cheap enough that
 * avoiding it was never worth the confusion.
 */
class GameFoldersFragment : Fragment() {
    private var _binding: FragmentGameFoldersBinding? = null

    /** Folder currently opened, or null while the folder list is showing. */
    private var openFolder: GameFolderScanner.Folder? = null

    /** Идущий обход. Отменяется перед новым, чтобы они не накладывались. */
    private var scanJob: kotlinx.coroutines.Job? = null
    private val binding get() = _binding!!

    /**
     * Добавление папки прямо с этого экрана.
     *
     * Раньше папку можно было добавить только в расширенной панели, и экран
     * «Папки с играми» не отвечал на самый вероятный вопрос: «а как добавить
     * ещё одну?». Здесь используется тот же путь, что и в панели
     * (MainActivity.processGamesDir): он берёт persistable-разрешение и
     * показывает диалог выбора глубины обхода. Один путь — одно поведение,
     * и разрешение не остаётся «временным».
     */
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val host = activity as? org.yuzu.yuzu_emu.ui.main.MainActivity
            val root = _binding?.root
            if (host == null || root == null) {
                if (root != null) {
                    Snackbar.make(root, R.string.folder_add_failed, Snackbar.LENGTH_LONG).show()
                }
                return@registerForActivityResult
            }
            host.processGamesDir(uri)
            Snackbar.make(root, R.string.folder_add_hint, Snackbar.LENGTH_LONG).show()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGameFoldersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarGameFolders.setNavigationOnClickListener {
            if (openFolder != null) backToFolders()
            else binding.root.findNavController().popBackStack()
        }

        binding.listFolders.layoutManager = LinearLayoutManager(requireContext())
        binding.buttonAddFolder.setOnClickListener { pickFolder.launch(null) }
        binding.swipeFolders.setOnRefreshListener {
            val open = openFolder
            if (open != null) showFolderContents(open) else rescan()
        }

        rescan()
    }

    /** Re-reads the folders from disk. Deliberately not memoised. */
    private fun rescan() {
        openFolder = null
        binding.swipeFolders.isRefreshing = true
        // Один обход за раз. Экран перечитывает папки в onViewCreated, в
        // onResume и по жесту «потянуть вниз»; потянуть дважды подряд
        // означало два обхода одного дерева одновременно, и оба писали в
        // один и тот же адаптер.
        scanJob?.cancel()
        // Контекст приложения, а не фрагмента: обход длится секунды и
        // переживает поворот экрана, удерживая активность.
        val ctx = requireContext().applicationContext
        scanJob = viewLifecycleOwner.lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) {
                runCatching { GameFolderScanner.scan(ctx) { isActive } }
                    .getOrDefault(emptyList())
            }

            if (_binding == null) return@launch

            binding.swipeFolders.isRefreshing = false
            binding.buttonAddFolder.isVisible = true
            binding.textNoFolders.isVisible = folders.isEmpty()
            binding.listFolders.isVisible = folders.isNotEmpty()

            val games = folders.sumOf { it.gameCount }
            val bytes = folders.sumOf { it.totalBytes }
            binding.textFoldersSummary.text = getString(
                R.string.folders_summary,
                folders.size,
                games,
                GameFolderScanner.humanSize(bytes)
            )

            binding.listFolders.adapter = GameFolderAdapter(
                requireActivity() as androidx.appcompat.app.AppCompatActivity,
                folders,
                { folder ->
                    // Папка без доступа: не «0 игр», а «верните доступ».
                    // Выбор той же папки заново и есть возврат доступа —
                    // система выдаёт разрешение на дерево, а не на путь.
                    if (folder.unreadable) {
                        Snackbar.make(
                            binding.root,
                            R.string.folder_no_access_hint,
                            Snackbar.LENGTH_LONG
                        ).show()
                        pickFolder.launch(null)
                    } else {
                        // Tapping a folder re-reads it and shows what is inside,
                        // so a game copied in a moment ago appears without a
                        // restart.
                        showFolderContents(folder)
                    }
                },
                { folder -> showFolderActions(folder) }
            )
        }
    }

    /**
     * Opens a folder: the games inside it, as a real list.
     *
     * This used to be a dialog containing a wall of text - filenames and sizes
     * as a single string, with nothing to tap. Seeing "there is a game here"
     * and being unable to start it is worse than not showing the folder at
     * all, and it is why the screen felt like a maze: a folder, inside it a
     * folder, inside that a sentence.
     *
     * Now the folder opens into its contents and a game starts from there,
     * through the same navigation the main library uses.
     */
    private fun showFolderContents(folder: GameFolderScanner.Folder) {
        scanJob?.cancel()
        val ctx = requireContext().applicationContext
        scanJob = viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    GameFolderScanner.listGames(ctx, folder.uriString, folder.depth) { isActive }
                }.getOrDefault(emptyList())
            }
            if (_binding == null) return@launch

            openFolder = folder
            binding.toolbarGameFolders.title = folder.displayName
            binding.toolbarGameFolders.subtitle = resources.getQuantityString(
                R.plurals.folder_game_count, entries.size, entries.size
            )

            binding.buttonAddFolder.isVisible = false
            binding.textNoFolders.isVisible = entries.isEmpty()
            if (entries.isEmpty()) {
                binding.textNoFolders.text = getString(R.string.folder_empty)
            }
            binding.listFolders.isVisible = entries.isNotEmpty()
            binding.textFoldersSummary.text = getString(
                R.string.folders_summary,
                1, entries.size,
                GameFolderScanner.humanSize(entries.sumOf { it.bytes })
            )

            binding.listFolders.adapter = GameEntryAdapter(
                requireActivity() as androidx.appcompat.app.AppCompatActivity,
                entries
            ) { entry -> launchGame(folder, entry) }
        }
    }

    /**
     * Starts a game from the folder screen.
     *
     * The library's metadata may not be loaded for this file - the folder view
     * deliberately reads the disk rather than the cache - so ask GameHelper for
     * a proper Game first. When it cannot produce one, say why instead of doing
     * nothing: that is the case where the keys are missing or the dump is bad,
     * and a button that silently ignores a tap is the worst possible answer.
     */
    private fun launchGame(folder: GameFolderScanner.Folder, entry: GameFolderScanner.Entry) {
        viewLifecycleOwner.lifecycleScope.launch {
            val uri = GameFolderScanner.childUri(folder.uriString, entry)
            if (uri == null) {
                Toast.makeText(requireContext(), R.string.folder_launch_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val game = withContext(Dispatchers.IO) {
                runCatching {
                    NativeLibrary.reloadKeys()
                    GameHelper.getGame(uri, addedToLibrary = true, registerFilesystemProvider = true)
                }.getOrNull()
            }
            if (_binding == null) return@launch
            if (game == null) {
                // Ask the loader why rather than repeating a guess. "check keys
                // and firmware" was wrong whenever the real cause was an update
                // without its base game, which is exactly the [v393216] file in
                // this folder.
                val why = withContext(Dispatchers.IO) {
                    runCatching { NativeSymbiosis.romProblem(uri.toString()) }.getOrNull()
                }
                MessageDialogFragment.newInstance(
                    requireActivity(),
                    titleString = entry.name.substringBeforeLast('.'),
                    descriptionString = why ?: getString(R.string.folder_launch_unreadable)
                ).show(childFragmentManager, MessageDialogFragment.TAG)
                return@launch
            }
            binding.root.findNavController().navigate(
                HomeNavigationDirections.actionGlobalEmulationActivity(game, true)
            )
        }
    }

    /**
     * Долгое нажатие на папку: пересканировать или убрать.
     *
     * Убрать — значит убрать из списка приложения, а не удалить с диска:
     * игры остаются на месте, и это сказано в диалоге прямым текстом.
     * Раньше единственным способом избавиться от папки, к которой больше
     * нет доступа, была очистка данных приложения.
     */
    private fun showFolderActions(folder: GameFolderScanner.Folder) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(folder.displayName)
            .setItems(
                arrayOf(
                    getString(R.string.folder_rescan),
                    getString(R.string.folder_remove)
                )
            ) { _, which ->
                when (which) {
                    0 -> showFolderContents(folder)
                    1 -> confirmRemoveFolder(folder)
                }
            }
            .show()
    }

    private fun confirmRemoveFolder(folder: GameFolderScanner.Folder) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(folder.displayName)
            .setMessage(R.string.folder_remove_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_remove) { _, _ -> removeFolder(folder) }
            .show()
    }

    private fun removeFolder(folder: GameFolderScanner.Folder) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching {
                    val list = NativeConfig.getGameDirs().toMutableList()
                    list.removeAll { it.uriString == folder.uriString }
                    NativeConfig.setGameDirs(list.toTypedArray())
                    // Явное удаление должно дойти и до зеркала: иначе
                    // восстановление «пропавших» папок вернуло бы её назад
                    // при следующем запуске.
                    GameFoldersStore.save(
                        list.map { GameFolderList.Entry(it.uriString, it.deepScan) }
                    )
                    true
                }.getOrDefault(false)
            }
            if (_binding == null) return@launch
            if (!removed) {
                Snackbar.make(binding.root, R.string.folder_remove_failed, Snackbar.LENGTH_LONG)
                    .show()
            }
            rescan()
        }
    }

    /** Back inside the folder returns to the folder list, not out of the screen. */
    private fun backToFolders() {
        openFolder = null
        binding.toolbarGameFolders.title = getString(R.string.folders_open_list)
        binding.toolbarGameFolders.subtitle = null
        rescan()
    }

    override fun onResume() {
        super.onResume()
        // A folder's contents can change while the app is in the background -
        // a game copied over USB, for instance - so re-read rather than trust
        // what was on screen a minute ago.
        if (_binding == null) return
        val open = openFolder
        if (open != null) showFolderContents(open) else rescan()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanJob = null
        // Адаптер держит активность; RecyclerView живёт дольше вида.
        binding.listFolders.adapter = null
        _binding = null
    }
}
