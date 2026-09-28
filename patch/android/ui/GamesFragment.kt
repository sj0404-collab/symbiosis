// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.fragments.MessageDialogFragment
import org.yuzu.yuzu_emu.utils.GameFolderScanner
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.SetupStatus
import org.yuzu.yuzu_emu.utils.SharedDataDirectory
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.adapters.GameAdapter
import org.yuzu.yuzu_emu.adapters.GameFolderAdapter
import org.yuzu.yuzu_emu.databinding.FragmentGamesBinding
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.model.AppletInfo
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible
import org.yuzu.yuzu_emu.utils.collect
import info.debatty.java.stringsimilarity.Jaccard
import info.debatty.java.stringsimilarity.JaroWinkler
import java.util.Locale
import androidx.core.content.edit
import androidx.core.view.doOnNextLayout

class GamesFragment : Fragment() {
    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!

    private var originalHeaderTopMargin: Int? = null
    private var originalHeaderBottomMargin: Int? = null
    private var originalHeaderRightMargin: Int? = null
    private var originalHeaderLeftMargin: Int? = null

    private var lastViewType: Int = GameAdapter.VIEW_TYPE_GRID
    private var fallbackBottomInset: Int = 0

    companion object {

        private const val SEARCH_TEXT = "SearchText"
        private const val PREF_SORT_TYPE = "GamesSortType"
    }

    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()
    private lateinit var gameAdapter: GameAdapter

    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    private lateinit var mainActivity: MainActivity
    private val getGamesDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result != null) {
                mainActivity.processGamesDir(result, true)
            }
        }

    /**
     * Picks the data root: firmware, keys, saves and shader cache.
     *
     * "Eden Debug" was hard-coded as the only visible default, which is wrong
     * for anyone whose existing install sits in "Eden", "eden-emu" or a folder
     * of their own naming - they were told to use a folder they do not have.
     * The chosen path is validated before it is committed, and what was found
     * inside is reported, because "I picked a folder and nothing happened" is
     * the failure mode worth avoiding.
     */
    private val getDataDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = context ?: return@registerForActivityResult
            // Папку «Eden» приводим к её настоящему корню «Eden/files».
            val path = SharedDataDirectory.resolveTreePath(uri)
                ?.let { SharedDataDirectory.normaliseRoot(it) }
            if (path == null) {
                Toast.makeText(
                    ctx,
                    getString(R.string.status_folder_failed, uri.toString()),
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            val check = SharedDataDirectory.inspect(ctx, path)
            if (!check.ok) {
                Toast.makeText(
                    ctx,
                    getString(R.string.status_folder_failed, check.verdict.name),
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            SharedDataDirectory.configuredPath = path
            // Apply it to the running process, so the strip below answers about
            // the folder just chosen rather than the previous one. Falls back to
            // "restart the app" only when the redirect itself fails.
            val applied = SharedDataDirectory.redirectNow(path)
            Toast.makeText(
                ctx,
                if (applied) path else getString(R.string.status_folder_changed, path),
                Toast.LENGTH_LONG
            ).show()
            refreshStatusStrip()
            refreshFolderCards()
        }

    private fun getCurrentViewType(): Int {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (isLandscape) CarouselRecyclerView.CAROUSEL_VIEW_TYPE_LANDSCAPE else CarouselRecyclerView.CAROUSEL_VIEW_TYPE_PORTRAIT
        val fallback = if (isLandscape) GameAdapter.VIEW_TYPE_CAROUSEL else GameAdapter.VIEW_TYPE_GRID
        return preferences.getInt(key, fallback)
    }

    private fun setCurrentViewType(type: Int) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (isLandscape) CarouselRecyclerView.CAROUSEL_VIEW_TYPE_LANDSCAPE else CarouselRecyclerView.CAROUSEL_VIEW_TYPE_PORTRAIT
        preferences.edit { putInt(key, type) }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamesBinding.inflate(inflater)
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(true)
        mainActivity = requireActivity() as MainActivity

        // Ask for storage access on first run.
        //
        // Removing the setup wizard removed the only place this was ever
        // requested. The permission is declared in the manifest, but
        // MANAGE_EXTERNAL_STORAGE is not granted by a dialog - the user has to
        // toggle it in system settings - so without asking, Android silently
        // refuses every read outside the app's own directory. A folder could
        // be picked and counted through the document provider while the
        // emulator, which opens the file directly, saw nothing: a game listed
        // with a name and no image behind it, which is exactly the report.
        maybeAskForStorage()

        if (savedInstanceState != null) {
            binding.searchText.setText(savedInstanceState.getString(SEARCH_TEXT))
        }

        gameAdapter = GameAdapter(
            requireActivity() as AppCompatActivity
        )

        applyGridGamesBinding()

        binding.swipeRefresh.apply {
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setOnRefreshListener {
                // Единственный автоматический обход, который остался:
                // человек сам потянул список. После него карточки
                // перечитываются из уже готового кэша, без второго прохода.
                gamesViewModel.reloadGames(false)
            }
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setProgressBackgroundColorSchemeColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setColorSchemeColors(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorOnPrimary
                )
            )
            post {
                if (_binding == null) {
                    return@post
                }
                (binding.swipeRefresh as? SwipeRefreshLayout)?.isRefreshing = gamesViewModel.isReloading.value
            }
        }

        gamesViewModel.isReloading.collect(viewLifecycleOwner) {
            (binding.swipeRefresh as? SwipeRefreshLayout)?.isRefreshing = it
            updateEmptyNotice()
        }
        gamesViewModel.games.collect(viewLifecycleOwner) {
            // Раньше здесь стояло `if (it.isNotEmpty())`, и пустой результат
            // обхода оставлял на экране ПРЕЖНИЙ список: надпись «игр нет»
            // рисовалась поверх игр, которые на диске уже удалены или
            // недоступны, и выглядело это как «приложение не обновляет
            // список». Список и надпись обязаны показывать одно и то же.
            setAdapter(it)
            updateEmptyNotice()
        }
        gamesViewModel.shouldSwapData.collect(
            viewLifecycleOwner,
            resetState = { gamesViewModel.setShouldSwapData(false) }
        ) {
            if (it) {
                setAdapter(gamesViewModel.games.value)
            }
        }
        gamesViewModel.shouldScrollToTop.collect(
            viewLifecycleOwner,
            resetState = { gamesViewModel.setShouldScrollToTop(false) }
        ) { if (it) scrollToTop() }

        gamesViewModel.shouldScrollAfterReload.collect(viewLifecycleOwner) { shouldScroll ->
            if (shouldScroll) {
                binding.gridGames.post {
                    (binding.gridGames as? CarouselRecyclerView)?.pendingScrollAfterReload = true
                    gameAdapter.notifyDataSetChanged()
                }
                gamesViewModel.setShouldScrollAfterReload(false)
            }
        }

        setupTopView()

        updateButtonsVisibility()

        // Tools sits on the game screen itself, next to the filter and the gear.
        // It was previously reachable only from inside Settings, which is the
        // one place it does not belong: none of these are preferences.
        // Safe call, matching how upstream treats launchQlaunch: view binding
        // types a view as nullable when it is absent from any layout variant,
        // and there are five here (default, land, ldrtl, w600dp, w1000dp).
        // Requiring it to be non-null broke the build the moment one variant
        // lacked the button.
        // Status strip. Refreshed in onResume too, because keys and firmware are
        // usually installed from another screen and the answer changes while
        // this one is in the background.
        // Tapping the strip refreshes it - or, when storage access is what is
        // missing, opens the screen that grants it. All Files Access is a
        // "special app access", so it does not appear on the normal
        // Permissions page and cannot be granted by a runtime prompt; a user
        // looking there finds nothing and concludes the app never asked.
        binding.statusStrip?.setOnClickListener {
            if (SharedDataDirectory.needsAllFilesAccess() &&
                !SharedDataDirectory.hasAllFilesAccess()
            ) {
                openAllFilesAccessSettings()
            } else {
                refreshStatusStrip()
            }
        }

        // A plain button. The folder list used to be reachable only by long
        // pressing "+" or by digging through the Tools tab, which is to say:
        // not discoverable at all.
        binding.foldersButton?.setOnClickListener {
            findNavController().navigate(R.id.action_global_symbiosisGameFoldersFragment)
        }

        binding.dataRootButton?.setOnClickListener {
            getDataDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
        }

        refreshStatusStrip()
        refreshFolderCards()

        binding.toolsButton?.setOnClickListener {
            findNavController().navigate(R.id.action_global_toolsFragment)
        }

        binding.addDirectory.setOnClickListener {
            getGamesDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
        }

        // Long press opens the folder view: which folders are configured, how
        // many games each holds and what they weigh. A flat list of titles
        // answers none of those, and storage is the scarce resource here.
        binding.addDirectory.setOnLongClickListener {
            findNavController().navigate(R.id.action_global_symbiosisGameFoldersFragment)
            true
        }

        binding.launchQlaunch?.setOnClickListener {
            launchQLaunch()
        }

        setInsets()
    }

    val applyGridGamesBinding = {
        (binding.gridGames as? RecyclerView)?.apply {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val currentViewType = getCurrentViewType()
            val savedViewType = if (isLandscape || currentViewType != GameAdapter.VIEW_TYPE_CAROUSEL) currentViewType else GameAdapter.VIEW_TYPE_GRID

            //This prevents Grid/List views from reusing scaled or otherwise modified ViewHolders left over from the carousel.
            adapter = null
            recycledViewPool.clear()

            gameAdapter.setViewType(savedViewType)
            currentFilter = preferences.getInt(PREF_SORT_TYPE, View.NO_ID)

            // Set the correct layout manager
            layoutManager = when (savedViewType) {
                GameAdapter.VIEW_TYPE_GRID -> {
                    val columns = resources.getInteger(R.integer.game_columns_grid)
                    GridLayoutManager(context, columns)
                }
                GameAdapter.VIEW_TYPE_GRID_COMPACT -> {
                    val columns = resources.getInteger(R.integer.game_columns_grid)
                    GridLayoutManager(context, columns)
                }
                GameAdapter.VIEW_TYPE_LIST -> {
                    val columns = resources.getInteger(R.integer.game_columns_list)
                    GridLayoutManager(context, columns)
                }
                GameAdapter.VIEW_TYPE_CAROUSEL -> {
                    LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                }
                else -> throw IllegalArgumentException("Invalid view type: $savedViewType")
            }
            if (savedViewType == GameAdapter.VIEW_TYPE_CAROUSEL) {
                (binding.gridGames as? View)?.let { it -> ViewCompat.requestApplyInsets(it)}
                doOnNextLayout { //Carousel: important to avoid overlap issues
                    (this as? CarouselRecyclerView)?.notifyLaidOut(fallbackBottomInset)
                }
            } else {
                (this as? CarouselRecyclerView)?.setupCarousel(false)
            }
            adapter = gameAdapter
            lastViewType = savedViewType
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            outState.putString(SEARCH_TEXT, binding.searchText.text.toString())
        }
    }

    override fun onPause() {
        super.onPause()
        if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) {
            gamesViewModel.lastScrollPosition = (binding.gridGames as? CarouselRecyclerView)?.getClosestChildPosition() ?: 0
        }
    }

    override fun onResume() {
        super.onResume()
        // Никакого обхода при возврате на экран.
        //
        // Раньше отсюда звались refreshStatusStrip() и refreshFolderCards(),
        // а те шли в GameFolderScanner.scan / diagnoseRom. Возврат из
        // игры, из настроек, перезапуск APK — и снова полное дерево.
        // На 8 ГБ это вылет «когда ищет игры».
        //
        // Строка состояния и карточки уже построены из кэша в
        // onViewCreated. Обновить их можно потянув список вниз.
        if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) {
            (binding.gridGames as? CarouselRecyclerView)?.setupCarousel(true)
            (binding.gridGames as? CarouselRecyclerView)?.restoreScrollState(gamesViewModel.lastScrollPosition)
        }
    }

    private var lastSearchText: String = ""
    private var lastFilter: Int = preferences.getInt(PREF_SORT_TYPE, View.NO_ID)

    /** The "no games" notice follows the list, not the spinner. */
    private fun updateEmptyNotice() {
        binding.noticeText.setVisible(
            visible = gamesViewModel.games.value.isEmpty() &&
                !gamesViewModel.isReloading.value,
            gone = false
        )
    }

    private fun setAdapter(games: List<Game>) {
        val currentSearchText = binding.searchText.text.toString()
        val currentFilter = binding.filterButton.id

        val searchChanged = currentSearchText != lastSearchText
        val filterChanged = currentFilter != lastFilter

        if (searchChanged || filterChanged) {
            filterAndSearch(games)
            lastSearchText = currentSearchText
            lastFilter = currentFilter
        } else {
            ((binding.gridGames as? RecyclerView)?.adapter as? GameAdapter)?.submitList(games)
            gamesViewModel.setFilteredGames(games)
        }
    }

    private fun setupTopView() {
        binding.searchText.doOnTextChanged() { text: CharSequence?, _: Int, _: Int, _: Int ->
            if (text.toString().isNotEmpty()) {
                binding.clearButton.visibility = View.VISIBLE
            } else {
                binding.clearButton.visibility = View.INVISIBLE
            }
            filterAndSearch()
        }

        binding.clearButton.setOnClickListener { binding.searchText.setText("") }
        binding.searchBackground.setOnClickListener { focusSearch() }

        // Setup view button
        binding.viewButton.setOnClickListener { showViewMenu(it) }

        // Setup filter button
        binding.filterButton.setOnClickListener { view ->
            showFilterMenu(view)
        }

        // Setup settings button
        binding.settingsButton.setOnClickListener { navigateToSettings() }
    }

    private fun navigateToSettings() {
        val navController = findNavController()
        navController.navigate(R.id.action_gamesFragment_to_homeSettingsFragment)
    }

    private fun showViewMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_game_views, popup.menu)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) {
            popup.menu.findItem(R.id.view_carousel)?.isVisible = false
        }

        val currentViewType = getCurrentViewType()
        when (currentViewType) {
            GameAdapter.VIEW_TYPE_LIST -> popup.menu.findItem(R.id.view_list).isChecked = true
            GameAdapter.VIEW_TYPE_GRID_COMPACT -> popup.menu.findItem(R.id.view_grid_compact).isChecked = true
            GameAdapter.VIEW_TYPE_GRID -> popup.menu.findItem(R.id.view_grid).isChecked = true
            GameAdapter.VIEW_TYPE_CAROUSEL -> popup.menu.findItem(R.id.view_carousel).isChecked = true
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.view_grid -> {
                    if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) onPause()
                    setCurrentViewType(GameAdapter.VIEW_TYPE_GRID)
                    applyGridGamesBinding()
                    item.isChecked = true
                    true
                }

                R.id.view_grid_compact -> {
                    if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) onPause()
                    setCurrentViewType(GameAdapter.VIEW_TYPE_GRID_COMPACT)
                    applyGridGamesBinding()
                    item.isChecked = true
                    true
                }

                R.id.view_list -> {
                    if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) onPause()
                    setCurrentViewType(GameAdapter.VIEW_TYPE_LIST)
                    applyGridGamesBinding()
                    item.isChecked = true
                    true
                }

                R.id.view_carousel -> {
                    if (!item.isChecked || getCurrentViewType() != GameAdapter.VIEW_TYPE_CAROUSEL) {
                        setCurrentViewType(GameAdapter.VIEW_TYPE_CAROUSEL)
                        applyGridGamesBinding()
                        item.isChecked = true
                        onResume()
                    }
                    true
                }

                else -> false
            }
        }

        popup.show()
    }

    private fun showFilterMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_game_filters, popup.menu)

        // Set checked state based on current filter
        when (currentFilter) {
            R.id.alphabetical -> popup.menu.findItem(R.id.alphabetical).isChecked = true
            R.id.filter_recently_played -> popup.menu.findItem(R.id.filter_recently_played).isChecked =
                true

            R.id.filter_recently_added -> popup.menu.findItem(R.id.filter_recently_added).isChecked =
                true
        }

        popup.setOnMenuItemClickListener { item ->
            currentFilter = item.itemId
            preferences.edit { putInt(PREF_SORT_TYPE, currentFilter) }
            filterAndSearch()
            true
        }

        popup.show()
    }

    // Track current filter
    private var currentFilter = View.NO_ID

    private fun filterAndSearch(baseList: List<Game> = gamesViewModel.games.value) {
        val filteredList: List<Game> = when (currentFilter) {
            R.id.alphabetical -> baseList.sortedBy { it.title }
            R.id.filter_recently_played -> {
                baseList.filter {
                    val lastPlayedTime = preferences.getLong(it.keyLastPlayedTime, 0L)
                    lastPlayedTime > (System.currentTimeMillis() - 24 * 60 * 60 * 1000)
                }.sortedByDescending { preferences.getLong(it.keyLastPlayedTime, 0L) }
            }
            R.id.filter_recently_added -> {
                baseList.filter {
                    val addedTime = preferences.getLong(it.keyAddedToLibraryTime, 0L)
                    addedTime > (System.currentTimeMillis() - 24 * 60 * 60 * 1000)
                }.sortedByDescending { preferences.getLong(it.keyAddedToLibraryTime, 0L) }
            }
            else -> baseList
        }

        val searchTerm = binding.searchText.text.toString().lowercase(Locale.getDefault())
        if (searchTerm.isEmpty()) {
            ((binding.gridGames as? RecyclerView)?.adapter as? GameAdapter)?.submitList(
                filteredList
            )
            gamesViewModel.setFilteredGames(filteredList)
            return
        }

        val searchAlgorithm = if (searchTerm.length > 1) Jaccard(2) else JaroWinkler()
        val sortedList = filteredList.mapNotNull { game ->
            val title = game.title.lowercase(Locale.getDefault())
            val score = searchAlgorithm.similarity(searchTerm, title)
            if (score > 0.03) {
                ScoredGame(score, game)
            } else {
                null
            }
        }.sortedByDescending { it.score }.map { it.item }

        ((binding.gridGames as? RecyclerView)?.adapter as? GameAdapter)?.submitList(sortedList)
        gamesViewModel.setFilteredGames(sortedList)
    }

    private inner class ScoredGame(val score: Double, val item: Game)

    private fun focusSearch() {
        binding.searchText.requestFocus()
        val imm = requireActivity()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        imm?.showSoftInput(binding.searchText, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Shows what is installed and where, replacing the setup wizard.
     *
     * Read fresh every time: the wizard's failure was reporting a remembered
     * verdict rather than the current one.
     */
    private fun refreshStatusStrip() {
        val line = binding.statusLine ?: return
        // То же самое, что и с карточками папок: строка состояния тоже
        // обходит все папки, и наложение двух обходов удваивает работу.
        statusJob?.cancel()
        statusJob = viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val items = withContext(Dispatchers.IO) {
                runCatching { SetupStatus.all(ctx) }.getOrNull()
            } ?: return@launch
            if (_binding == null) return@launch

            // Summary line: name, tick and size. A bare tick told the user
            // that four unnamed things had a state, which is not information.
            line.text = items.joinToString(" · ") { item ->
                val size = item.bytes?.takeIf { it > 0 }
                    ?.let { " " + GameFolderScanner.humanSize(it) } ?: ""
                getString(item.labelRes) + (if (item.present) " ✓" else " ✕") + size
            }

            // Detail block, always visible: what each item is, where it lives
            // and what it weighs.
            binding.statusPaths?.text = buildString {
                for (item in items) {
                    append(getString(item.labelRes)).append(": ")
                        .append(item.detail)
                    item.bytes?.takeIf { it > 0 }?.let {
                        append(" · ").append(GameFolderScanner.humanSize(it))
                    }
                    append('\n')
                }
                append(getString(R.string.status_data_root)).append(": ")
                    .append(SetupStatus.dataRoot())
            }
        }
    }

    /**
     * One prompt, once, when All Files Access is missing.
     *
     * Deliberately not a loop: a user who declines on purpose should not be
     * nagged on every launch. The status strip keeps saying what is missing,
     * and Tools has the same button for later.
     *
     * NOT MessageDialogFragment. That helper keeps its button action in an
     * activity-scoped MessageDialogViewModel, and every dialog built without
     * an action - MainActivity.checkKeys() is one, and it fires on the same
     * startup whenever prod.keys is missing - calls clear() / sets
     * positiveAction = null on that same shared view model. Whichever dialog
     * is constructed last wins, so the storage prompt's "Open settings"
     * frequently ran `null?.invoke()`: the dialog closed and nothing happened,
     * which is exactly what the user reported. The same prompt in Tools works
     * because it is a plain AlertDialog holding its own lambda. So is this one.
     */
    private fun maybeAskForStorage() {
        if (!SharedDataDirectory.needsAllFilesAccess()) return
        if (SharedDataDirectory.hasAllFilesAccess()) return

        // Ask again while the permission is still missing.
        //
        // The flag used to be set before the dialog was even shown, so
        // "ask once" became "never ask again": dismissing it, or the dialog
        // failing to appear at all, permanently silenced the only prompt the
        // app has. Since the emulator cannot read a single ROM without this,
        // being asked on each launch until it is granted is the correct
        // trade - and it stops the moment the permission is there. Only a
        // deliberate "not now" quietens it for the rest of the session.
        if (askedForStorageThisSession) return
        askedForStorageThisSession = true

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.status_storage_needed)
            .setMessage(R.string.need_all_files_access)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                openAllFilesAccessSettings()
            }
            .show()
    }

    /** True once the prompt has been shown in this run of the app. */
    private var askedForStorageThisSession = false

    /** Идущие обходы папок, чтобы не запускать второй поверх первого. */
    private var folderScanJob: kotlinx.coroutines.Job? = null
    private var statusJob: kotlinx.coroutines.Job? = null





    /**
     * Opens the All Files Access screen for this app.
     *
     * Falls back to the global list, because some vendor builds - MIUI among
     * them - do not implement the per-app intent and would otherwise throw.
     */
    private fun openAllFilesAccessSettings() {
        val ok = runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${requireContext().packageName}")
                )
            )
        }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            }.onFailure {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.status_storage_manual),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Folder cards on the games screen itself.
     *
     * The dedicated screen still exists and shows the same data, but reaching
     * it meant a long press on "+" or a trip through the Tools tab - neither
     * of which anyone finds. Storage is the scarce resource on this device, so
     * "which folders, how many games, how many gigabytes" belongs where the
     * games are.
     */
    private fun refreshFolderCards() {
        val list = binding.folderCards ?: return
        // Карточки из конфига и кэша. Диск не читается.
        //
        // Раньше здесь был GameFolderScanner.scan — полный обход каждой
        // папки через ContentResolver. На старте и на каждом onResume.
        // Теперь имя папки берётся из URI, число игр — из списка, который
        // уже лежит в памяти. Потянуть вниз обновит и тот список, и эти
        // цифры.
        folderScanJob?.cancel()
        folderScanJob = viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val dirs = withContext(Dispatchers.IO) {
                runCatching { NativeConfig.getGameDirs().toList() }.getOrDefault(emptyList())
            }
            if (_binding == null) return@launch
            if (dirs.isEmpty()) {
                list.isVisible = false
                return@launch
            }
            val cached = GameHelper.cachedGameList
            val folders = dirs.map { dir ->
                val name = GameFolderScanner.displayNameOf(dir.uriString)
                val prefix = GameFolderScanner.pathOf(dir.uriString)
                val inThis = cached.count {
                    val p = GameFolderScanner.pathOf(it.path)
                    p == prefix || p.startsWith("$prefix/")
                }
                GameFolderScanner.Folder(
                    uriString = dir.uriString,
                    displayName = name,
                    gameCount = inThis,
                    totalBytes = 0L,
                    depth = GameFolderScanner.depthFor(dir.deepScan)
                )
            }
            list.isVisible = true
            if (list.layoutManager == null) {
                list.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            }
            list.adapter = GameFolderAdapter(
                requireActivity() as androidx.appcompat.app.AppCompatActivity,
                folders
            ) {
                findNavController().navigate(R.id.action_global_symbiosisGameFoldersFragment)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Область корутин фрагмента отменяется сама, но адаптер держит
        // ссылку на активность через AppCompatActivity в конструкторе -
        // а RecyclerView переживает onDestroyView в кэше фрагмента.
        folderScanJob = null
        statusJob = null
        binding.folderCards?.adapter = null
        _binding = null
    }

    private fun scrollToTop() {
        if (_binding != null) {
            (binding.gridGames as? CarouselRecyclerView)?.smoothScrollToPosition(0)
        }
    }

    private fun launchQLaunch() {
        try {
            val appletPath = NativeLibrary.getAppletLaunchPath(AppletInfo.QLaunch.entryId)
            if (appletPath.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.applets_error_applet,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            NativeLibrary.setCurrentAppletId(AppletInfo.QLaunch.appletId)

            val qlaunchGame = Game(
                title = getString(R.string.qlaunch_applet),
                path = appletPath
            )

            val action = HomeNavigationDirections.actionGlobalEmulationActivity(qlaunchGame)
            findNavController().navigate(action)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to launch QLaunch: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateButtonsVisibility() {
        val showQLaunch = BooleanSetting.ENABLE_QLAUNCH_BUTTON.getBoolean()
        val showFolder = BooleanSetting.ENABLE_FOLDER_BUTTON.getBoolean()
        val isFirmwareAvailable = NativeLibrary.isFirmwareAvailable()

        val shouldShowQLaunch = showQLaunch && isFirmwareAvailable
        binding.launchQlaunch.visibility = if (shouldShowQLaunch) View.VISIBLE else View.GONE

        binding.addDirectory.visibility = if (showFolder) View.VISIBLE else View.GONE
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val spacingNavigation = resources.getDimensionPixelSize(R.dimen.spacing_navigation)
            resources.getDimensionPixelSize(R.dimen.spacing_navigation_rail)

            (binding.swipeRefresh as? SwipeRefreshLayout)?.setProgressViewEndTarget(
                false,
                barInsets.top + resources.getDimensionPixelSize(R.dimen.spacing_refresh_end)
            )

            val leftInset = barInsets.left + cutoutInsets.left
            val rightInset = barInsets.right + cutoutInsets.right
            val topInset = maxOf(barInsets.top, cutoutInsets.top)

            val mlpSwipe = binding.swipeRefresh.layoutParams as ViewGroup.MarginLayoutParams
            mlpSwipe.leftMargin = leftInset
            mlpSwipe.rightMargin = rightInset
            binding.swipeRefresh.layoutParams = mlpSwipe

            val mlpHeader = binding.header.layoutParams as ViewGroup.MarginLayoutParams

            // Store original margins only once
            if (originalHeaderTopMargin == null) {
                originalHeaderTopMargin = mlpHeader.topMargin
                originalHeaderRightMargin = mlpHeader.rightMargin
                originalHeaderLeftMargin = mlpHeader.leftMargin
            }

            // Always set margin as original + insets
            mlpHeader.leftMargin = (originalHeaderLeftMargin ?: 0) + leftInset
            mlpHeader.rightMargin = (originalHeaderRightMargin ?: 0) + rightInset
            mlpHeader.topMargin = (originalHeaderTopMargin ?: 0) + topInset + resources.getDimensionPixelSize(
                R.dimen.spacing_med
            )
            binding.header.layoutParams = mlpHeader

            binding.noticeText.updatePadding(bottom = spacingNavigation)

            binding.gridGames.updatePadding(
                top = resources.getDimensionPixelSize(R.dimen.spacing_med)
            )

            val mlpFab = binding.addDirectory.layoutParams as ViewGroup.MarginLayoutParams
            val fabPadding = resources.getDimensionPixelSize(R.dimen.spacing_large)
            mlpFab.leftMargin = leftInset + fabPadding
            mlpFab.bottomMargin = barInsets.bottom + fabPadding
            mlpFab.rightMargin = rightInset + fabPadding
            binding.addDirectory.layoutParams = mlpFab

            binding.launchQlaunch?.let { qlaunchButton ->
                val mlpQLaunch = qlaunchButton.layoutParams as ViewGroup.MarginLayoutParams
                mlpQLaunch.leftMargin = leftInset + fabPadding
                mlpQLaunch.bottomMargin = barInsets.bottom + fabPadding
                qlaunchButton.layoutParams = mlpQLaunch
            }

            val navInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val gestureInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemGestures())
            val bottomInset = maxOf(navInsets.bottom, gestureInsets.bottom, cutoutInsets.bottom)
            fallbackBottomInset = bottomInset
            (binding.gridGames as? CarouselRecyclerView)?.notifyInsetsReady(bottomInset)
            windowInsets
        }
}
