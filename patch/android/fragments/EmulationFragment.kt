// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.BatteryManager.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.graphics.Typeface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.navArgs
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.activities.EmulationActivity
import org.yuzu.yuzu_emu.databinding.DialogOverlayAdjustBinding
import org.yuzu.yuzu_emu.databinding.FragmentEmulationBinding
import org.yuzu.yuzu_emu.dialogs.QuickSettings
import org.yuzu.yuzu_emu.features.input.NativeInput
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.ByteSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.features.settings.model.Settings
import org.yuzu.yuzu_emu.features.settings.model.Settings.EmulationOrientation
import org.yuzu.yuzu_emu.features.settings.model.Settings.EmulationVerticalAlignment
import org.yuzu.yuzu_emu.features.settings.model.ShortSetting
import org.yuzu.yuzu_emu.features.settings.utils.SettingsFile
import org.yuzu.yuzu_emu.model.DriverViewModel
import org.yuzu.yuzu_emu.model.EmulationViewModel
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.overlay.model.OverlayControl
import org.yuzu.yuzu_emu.overlay.model.OverlayLayout
import org.yuzu.yuzu_emu.utils.DirectoryInitialization
import org.yuzu.yuzu_emu.utils.FileUtil
import org.yuzu.yuzu_emu.utils.GameCardMeta
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.LastGame
import org.yuzu.yuzu_emu.utils.GameIconUtils
import org.yuzu.yuzu_emu.utils.GpuDriverHelper
import org.yuzu.yuzu_emu.utils.InputHandler
import org.yuzu.yuzu_emu.utils.CloudSync
import org.yuzu.yuzu_emu.utils.ExitTrace
import org.yuzu.yuzu_emu.utils.Log
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.NativeSymbiosis
import org.yuzu.yuzu_emu.utils.QuickPanel
import org.yuzu.yuzu_emu.utils.NativeFreedrenoConfig
import org.yuzu.yuzu_emu.utils.ViewUtils
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible
import org.yuzu.yuzu_emu.utils.collect
import org.yuzu.yuzu_emu.utils.CustomSettingsHandler
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.or

class EmulationFragment : Fragment(), SurfaceHolder.Callback {
    private lateinit var emulationState: EmulationState
    private var emulationActivity: EmulationActivity? = null

    private var perfStatsUpdater: (() -> Unit)? = null
    private var socUpdater: (() -> Unit)? = null

    val handler = Handler(Looper.getMainLooper())

    private var controllerInputReceived = false
    private var hasPhysicalControllerConnected = false
    private var overlayHiddenByPhysicalController = false

    private var _binding: FragmentEmulationBinding? = null

    private val binding get() = _binding!!

    private val args by navArgs<EmulationFragmentArgs>()

    private var game: Game? = null

    private val emulationViewModel: EmulationViewModel by activityViewModels()
    private val driverViewModel: DriverViewModel by activityViewModels()

    private var isInFoldableLayout = false
    private var emulationStarted = false

    private lateinit var gpuModel: String
    private lateinit var fwVersion: String
    private lateinit var buildId: String
    private lateinit var driverInUse: String

    private var intentGame: Game? = null
    private var isCustomSettingsIntent = false
    private var isStoppingForRomSwap = false
    private var deferGameSetupUntilStopCompletes = false

    private var perfStatsRunnable: Runnable? = null
    private var socRunnable: Runnable? = null
    private var isAmiiboPickerOpen = false
    private var amiiboLoadJob: Job? = null

    private var wasInputOverlayAutoHidden = false
    private var overlayTouchActive = false
    private var pausedFrameBitmap: Bitmap? = null

    var shouldUseCustom = false

    /**
     * Ключи, которые меняли в панели за этот запуск игры.
     *
     * Нужны, чтобы честно сказать «это применится после перезапуска» и
     * показать кнопку перезапуска только тогда, когда она действительно нужна.
     */
    private val quickChanges = mutableSetOf<String>()
    private var isQuickSettingsMenuOpen = false
    private val quickSettings = QuickSettings(this)

    /**
     * Обновляет подписи собранной панели на месте.
     *
     * Раньше на каждое изменение вызывался `addQuickSettings()`, то есть
     * `removeAllViews()`. Вызов приходил из обработчика элемента, который в
     * этот момент находился внутри удаляемого дерева: у радиокнопки — из её
     * собственного `setOnCheckedChangeListener`, у чипа буфера — из `onClick`.
     * Удалять виджет из-под выполняющегося обработчика нельзя, и на телефоне
     * это заканчивалось остановкой игры. Теперь панель не пересобирается:
     * меняются только тексты и видимость кнопки перезапуска.
     */
    private var quickPanelRefresh: (() -> Unit)? = null

    private val loadAmiiboLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            isAmiiboPickerOpen = false
            val binding = _binding ?: return@registerForActivityResult
            binding.inGameMenu.requestFocus()

            if (!isAdded || uri == null) {
                return@registerForActivityResult
            }

            if (!NativeLibrary.isRunning()) {
                showAmiiboDialog(R.string.amiibo_wrong_state)
                return@registerForActivityResult
            }

            amiiboLoadJob?.cancel()
            val owner = viewLifecycleOwner
            amiiboLoadJob = owner.lifecycleScope.launch {
                val bytes = readAmiiboFile(uri)

                if (!isAdded) {
                    return@launch
                }

                if (bytes == null || bytes.isEmpty()) {
                    showAmiiboDialog(
                        if (bytes == null) R.string.amiibo_unknown_error else R.string.amiibo_not_valid
                    )
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    if (NativeLibrary.isRunning()) {
                        NativeLibrary.loadAmiibo(bytes)
                    } else {
                        AmiiboLoadResult.WrongDeviceState.value
                    }
                }

                if (!isAdded) {
                    return@launch
                }
                handleAmiiboLoadResult(result)
            }.also { job ->
                job.invokeOnCompletion {
                    if (amiiboLoadJob == job) {
                        amiiboLoadJob = null
                    }
                }
            }
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is EmulationActivity) {
            emulationActivity = context
            NativeLibrary.setEmulationActivity(context)
        } else {
            throw IllegalStateException("EmulationFragment must have EmulationActivity parent")
        }
    }

    /**
     * Initialize anything that doesn't depend on the layout / views in here.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateOrientation()

        if (args.overlayGamelessEditMode) {
            return
        }

        val intent = requireActivity().intent
        val intentUri: Uri? = intent.data
        intentGame = null
        isCustomSettingsIntent = false

        if (intent.action == CustomSettingsHandler.CUSTOM_CONFIG_ACTION) {
            handleEmuReadyIntent(intent)
            return
        } else if (intentUri != null) {
            intentGame = if (Game.extensions.contains(FileUtil.getExtension(intentUri))) {
                GameHelper.getGame(requireActivity().intent.data!!, false)
            } else {
                null
            }
        }

        if (emulationViewModel.isEmulationStopping.value) {
            deferGameSetupUntilStopCompletes = true
            if (game == null) {
                game = args.game ?: intentGame
            }
            return
        }

        finishGameSetup()
    }

    /**
     * Complete the game setup process (extracted for async custom settings handling)
     */
    private fun finishGameSetup() {
        try {
            // Третий источник игры — файл с путём последней запущенной.
            //
            // Первые два (аргументы навигации и data интента) не всегда
            // доезжают: при перезапуске экран переиспользуется, и объект игры
            // может не восстановиться. Без третьего источника экран закрывал
            // себя — снаружи это «перезапустил, а оно вышло из игры».
            // Путь пишется до запуска, файл на месте, и объект собирается по
            // нему заново тем же GameHelper.
            val gameToUse = args.game ?: intentGame ?: lastGameFromDisk()

            if (gameToUse == null) {
                Log.error("[EmulationFragment] No game found in arguments or intent")
                Toast.makeText(
                    requireContext(),
                    R.string.no_game_present,
                    Toast.LENGTH_SHORT
                ).show()
                requireActivity().finish()
                return
            }

            // Запомнить игру, чтобы следующий перезапуск её не потерял.
            runCatching {
                LastGame.save(lastGameFile(requireContext()), gameToUse.path)
            }

            game = gameToUse
            emulationActivity?.updateSessionGame(gameToUse)
        } catch (e: Exception) {
            Log.error("[EmulationFragment] Error during game setup: ${e.message}")
            Toast.makeText(
                requireContext(),
                "Setup error: ${e.message?.take(30) ?: "Unknown"}",
                Toast.LENGTH_SHORT
            ).show()
            requireActivity().finish()
            return
        }

        try {
            when {
                // Game launched via intent (check for existing custom config)
                intentGame != null -> {
                    game?.let { gameInstance ->
                        runCatching { GameHelper.restoreContentForGame(gameInstance) }
                            .onFailure {
                                Log.warning(
                                    "[EmulationFragment] Failed to restore content for intent launch: ${it.message}"
                                )
                            }

                        val customConfigFile = SettingsFile.getCustomSettingsFile(gameInstance)
                        if (customConfigFile.exists()) {
                            shouldUseCustom = true
                            Log.info(
                                "[EmulationFragment] Found existing custom settings for ${gameInstance.title}, loading them"
                            )
                            SettingsFile.loadCustomConfig(gameInstance)
                            NativeConfig.unloadPerGameConfig()
                        } else {
                            shouldUseCustom = false
                            Log.info(
                                "[EmulationFragment] No custom settings found for ${gameInstance.title}, using global settings"
                            )
                            NativeConfig.reloadGlobalConfig()
                        }
                    } ?: run {
                        Log.info("[EmulationFragment] No game available, using global settings")
                        NativeConfig.reloadGlobalConfig()
                    }
                }

                // Normal game launch from arguments
                else -> {
                    shouldUseCustom = game?.let { it == args.game && args.custom } ?: false

                    if (shouldUseCustom) {
                        SettingsFile.loadCustomConfig(game!!)
                        NativeConfig.unloadPerGameConfig()
                        Log.info("[EmulationFragment] Loading custom settings for ${game!!.title}")
                    } else {
                        Log.info("[EmulationFragment] Using global settings")
                        NativeConfig.reloadGlobalConfig()
                    }
                }
            }
        } catch (e: Exception) {
            Log.error("[EmulationFragment] Error loading configuration: ${e.message}")
            Log.info("[EmulationFragment] Falling back to global settings")
            try {
                NativeConfig.reloadGlobalConfig()
            } catch (fallbackException: Exception) {
                Log.error(
                    "[EmulationFragment] Critical error: could not load global config: ${fallbackException.message}"
                )
                throw fallbackException
            }
        }
        try {
            if (GpuDriverHelper.isAdrenoGpu()) {
                val programIdHex = game!!.programIdHex
                if (NativeFreedrenoConfig.loadPerGameConfigWithGlobalFallback(programIdHex)) {
                    Log.info("[EmulationFragment] Loaded per-game Freedreno config for $programIdHex")
                } else {
                    Log.info("[EmulationFragment] Using global Freedreno config for $programIdHex")
                }
            }
        } catch (e: Exception) {
            Log.warning("[EmulationFragment] Failed to load Freedreno config: ${e.message}")
        }

        emulationState = EmulationState(game!!.path) {
            return@EmulationState driverViewModel.isInteractionAllowed.value &&
                !isStoppingForRomSwap
        }
    }

    /**
     * Handle EmuReady intent for launching games with or without custom settings
     */
    private fun handleEmuReadyIntent(intent: Intent) {
        val titleId = intent.getStringExtra(CustomSettingsHandler.EXTRA_TITLE_ID)
        val customSettings = intent.getStringExtra(CustomSettingsHandler.EXTRA_CUSTOM_SETTINGS)

        if (titleId != null) {
            Log.info("[EmulationFragment] Received EmuReady intent for title: $titleId")

            lifecycleScope.launch {
                try {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.searching_for_game),
                        Toast.LENGTH_SHORT
                    ).show()
                    val foundGame = CustomSettingsHandler.findGameByTitleId(
                        titleId,
                        requireContext()
                    )
                    if (foundGame == null) {
                        Log.error("[EmulationFragment] Game not found for title ID: $titleId")
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.game_not_found_for_title_id, titleId),
                            Toast.LENGTH_LONG
                        ).show()
                        requireActivity().finish()
                        return@launch
                    }

                    val shouldLaunch = showLaunchConfirmationDialog(
                        foundGame.title,
                        customSettings != null
                    )
                    if (!shouldLaunch) {
                        Log.info("[EmulationFragment] User cancelled EmuReady launch")
                        requireActivity().finish()
                        return@launch
                    }

                    if (customSettings != null) {
                        intentGame = CustomSettingsHandler.applyCustomSettingsWithDriverCheck(
                            titleId,
                            customSettings,
                            requireContext(),
                            requireActivity(),
                            driverViewModel
                        )

                        if (intentGame == null) {
                            Log.error(
                                "[EmulationFragment] Custom settings processing failed for title ID: $titleId"
                            )
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.custom_settings_failed_title),
                                Toast.LENGTH_SHORT
                            ).show()

                            val launchWithDefault = askUserToLaunchWithDefaultSettings(
                                foundGame.title,
                                getString(R.string.custom_settings_failure_reasons)
                            )

                            if (launchWithDefault) {
                                Log.info(
                                    "[EmulationFragment] User chose to launch with default settings"
                                )
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.launch_with_default_settings),
                                    Toast.LENGTH_SHORT
                                ).show()
                                intentGame = foundGame
                                isCustomSettingsIntent = false
                            } else {
                                Log.info(
                                    "[EmulationFragment] User cancelled launch after custom settings failure"
                                )
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.launch_cancelled),
                                    Toast.LENGTH_SHORT
                                ).show()
                                requireActivity().finish()
                                return@launch
                            }
                        } else {
                            isCustomSettingsIntent = true
                        }
                    } else {
                        Log.info("[EmulationFragment] Launching game with default settings")

                        val customConfigFile = SettingsFile.getCustomSettingsFile(foundGame)
                        if (customConfigFile.exists()) {
                            Log.info(
                                "[EmulationFragment] Found existing custom settings for ${foundGame.title}, loading them"
                            )
                            SettingsFile.loadCustomConfig(foundGame)
                        } else {
                            Log.info(
                                "[EmulationFragment] No custom settings found for ${foundGame.title}, using global settings"
                            )
                        }

                        Toast.makeText(
                            requireContext(),
                            getString(R.string.launching_game, foundGame.title),
                            Toast.LENGTH_SHORT
                        ).show()
                        intentGame = foundGame
                        isCustomSettingsIntent = false
                    }

                    if (intentGame != null) {
                        withContext(Dispatchers.Main) {
                            try {
                                finishGameSetup()
                                Log.info(
                                    "[EmulationFragment] Game setup complete for intent launch"
                                )

                                if (_binding != null) {
                                    // Hide loading indicator immediately for intent launches
                                    binding.loadingIndicator.visibility = View.GONE
                                    binding.surfaceEmulation.visibility = View.VISIBLE

                                    completeViewSetup()

                                    // For intent launches, check if surface is ready and start emulation
                                    binding.root.post {
                                        if (binding.surfaceEmulation.holder.surface?.isValid == true && !emulationStarted) {
                                            emulationStarted = true
                                            emulationState.newSurface(
                                                binding.surfaceEmulation.holder.surface
                                            )
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.error(
                                    "[EmulationFragment] Error in finishGameSetup: ${e.message}"
                                )
                                requireActivity().finish()
                                return@withContext
                            }
                        }
                    } else {
                        Log.error("[EmulationFragment] No valid game found after processing intent")
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.failed_to_initialize_game),
                            Toast.LENGTH_SHORT
                        ).show()
                        requireActivity().finish()
                    }
                } catch (e: Exception) {
                    Log.error("[EmulationFragment] Error processing EmuReady intent: ${e.message}")
                    Toast.makeText(
                        requireContext(),
                        "Error: ${e.message?.take(50) ?: "Unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                    requireActivity().finish()
                }
            }
        } else {
            Log.error("[EmulationFragment] EmuReady intent missing title_id")
            Toast.makeText(
                requireContext(),
                "Invalid request: missing title ID",
                Toast.LENGTH_SHORT
            ).show()
            requireActivity().finish()
        }
    }

    /**
     * Show confirmation dialog for EmuReady game launches
     */
    private suspend fun showLaunchConfirmationDialog(gameTitle: String, hasCustomSettings: Boolean): Boolean {
        return suspendCoroutine { continuation ->
            requireActivity().runOnUiThread {
                val message = if (hasCustomSettings) {
                    getString(
                        R.string.custom_intent_launch_message_with_settings,
                        gameTitle
                    )
                } else {
                    getString(R.string.custom_intent_launch_message, gameTitle)
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.custom_intent_launch_title))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.launch)) { _, _ ->
                        continuation.resume(true)
                    }
                    .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                        continuation.resume(false)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /**
     * Ask user if they want to launch with default settings when custom settings fail
     */
    private suspend fun askUserToLaunchWithDefaultSettings(
        gameTitle: String,
        errorMessage: String
    ): Boolean {
        return suspendCoroutine { continuation ->
            requireActivity().runOnUiThread {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.custom_settings_failed_title))
                    .setMessage(
                        getString(R.string.custom_settings_failed_message, gameTitle, errorMessage)
                    )
                    .setPositiveButton(getString(R.string.launch_with_default_settings)) { _, _ ->
                        continuation.resume(true)
                    }
                    .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                        continuation.resume(false)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /**
     * Initialize the UI and start emulation in here.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmulationBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (requireActivity().isFinishing) {
            return
        }

        if (args.overlayGamelessEditMode) {
            setupOverlayGamelessEditMode()
            return
        }

        if (game == null) {
            Log.warning(
                "[EmulationFragment] Game not yet initialized in onViewCreated - will be set up by async intent handler"
            )
            return
        }

        completeViewSetup()
    }


    private fun setupOverlayGamelessEditMode() {
        binding.surfaceInputOverlay.post {
            binding.surfaceInputOverlay.refreshControls(gameless = true)
        }

        binding.doneControlConfig.setOnClickListener {
            finishOverlayGamelessEditMode()
        }

        binding.doneControlConfig.visibility = View.VISIBLE
        binding.surfaceInputOverlay.setIsInEditMode(true)
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.surfaceInputOverlay.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.GONE

        // in gameless edit mode, back = done
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishOverlayGamelessEditMode()
                }
            }
        )
    }

    private fun finishOverlayGamelessEditMode() {
        binding.surfaceInputOverlay.setIsInEditMode(false)
        NativeConfig.saveGlobalConfig()
        requireActivity().finish()
    }

    private fun completeViewSetup() {
        if (_binding == null || game == null) {
            return
        }
        Log.info("[EmulationFragment] Starting view setup for game: ${game?.title}")

        gpuModel = GpuDriverHelper.hookLibPath?.let { GpuDriverHelper.getGpuModel(hookLibPath = it).toString() } ?: "Unknown"
        fwVersion = NativeLibrary.firmwareVersion()

        val buildVersion = NativeLibrary.getBuildVersion()
        buildId = buildVersion.split("-").getOrNull(0) ?: ""
        driverInUse = driverViewModel.selectedDriverVersion.value

        updateQuickOverlayMenuEntry(BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean())
        onPhysicalControllerStateChanged(InputHandler.androidControllers.isNotEmpty())

        binding.surfaceEmulation.holder.addCallback(this)
        binding.doneControlConfig.setOnClickListener { stopConfiguringControls() }

        binding.drawerLayout.addDrawerListener(object : DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                binding.surfaceInputOverlay.dispatchTouchEvent(
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis() + 100,
                        MotionEvent.ACTION_UP,
                        0f,
                        0f,
                        0
                    )
                )
            }

            override fun onDrawerOpened(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                binding.inGameMenu.requestFocus()
                emulationViewModel.setDrawerOpen(true)
                updateQuickOverlayMenuEntry(BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean())
                if (drawerView == binding.inGameMenu) {
                    binding.drawerLayout.closeDrawer(binding.quickSettingsSheet)
                } else if (drawerView == binding.quickSettingsSheet) {
                    binding.drawerLayout.closeDrawer(binding.inGameMenu)
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(IntSetting.LOCK_DRAWER.getInt())
                emulationViewModel.setDrawerOpen(false)
            }

            override fun onDrawerStateChanged(newState: Int) {
                // No op
            }
        })
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        if (!BooleanSetting.ENABLE_QUICK_SETTINGS.getBoolean()) {
            binding.drawerLayout.setDrawerLockMode(
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
                binding.quickSettingsSheet
            )
        }

        updateGameTitle()

        binding.inGameMenu.menu.findItem(R.id.menu_quick_settings)?.isVisible =
            BooleanSetting.ENABLE_QUICK_SETTINGS.getBoolean()

        binding.pausedIcon.setOnClickListener {
            if (this::emulationState.isInitialized && emulationState.isPaused) {
                resumeEmulationFromUi()
            }
        }

        binding.inGameMenu.menu.findItem(R.id.menu_lock_drawer).apply {
            val lockMode = IntSetting.LOCK_DRAWER.getInt()
            val titleId = if (lockMode == DrawerLayout.LOCK_MODE_LOCKED_CLOSED) {
                R.string.unlock_drawer
            } else {
                R.string.lock_drawer
            }
            val iconId = if (lockMode == DrawerLayout.LOCK_MODE_UNLOCKED) {
                R.drawable.ic_unlock
            } else {
                R.drawable.ic_lock
            }

            title = getString(titleId)
            icon = ResourcesCompat.getDrawable(
                resources,
                iconId,
                requireContext().theme
            )
        }

        binding.inGameMenu.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.menu_pause_emulation -> {
                    if (emulationState.isPaused) {
                        resumeEmulationFromUi()
                    } else {
                        pauseEmulationAndCaptureFrame()
                    }
                    binding.inGameMenu.requestFocus()
                    true
                }

                R.id.menu_quick_overlay -> {
                    val newState = !BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()
                    toggleOverlay(newState)
                    updateQuickOverlayMenuEntry(BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean())
                    NativeConfig.saveGlobalConfig()
                    true
                }

                R.id.menu_settings -> {
                    val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                        null,
                        Settings.MenuTag.SECTION_ROOT
                    )
                    binding.inGameMenu.requestFocus()
                    binding.drawerLayout.closeDrawer(binding.quickSettingsSheet)
                    binding.root.findNavController().navigate(action)
                    true
                }

            if (BooleanSetting.ENABLE_QUICK_SETTINGS.getBoolean())
                R.id.menu_quick_settings else 0 -> {
                openQuickSettingsMenu()
                true
            }

                R.id.menu_settings_per_game -> {
                    val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                        args.game,
                        Settings.MenuTag.SECTION_ROOT
                    )
                    binding.inGameMenu.requestFocus()
                    binding.drawerLayout.closeDrawer(binding.quickSettingsSheet)
                    binding.root.findNavController().navigate(action)
                    true
                }

                R.id.menu_multiplayer -> {
                    emulationActivity?.displayMultiplayerDialog()
                    true
                }

                R.id.menu_load_amiibo -> handleLoadAmiiboSelection()

                R.id.menu_controls -> {
                    val action = HomeNavigationDirections.actionGlobalSettingsActivity(
                        null,
                        Settings.MenuTag.SECTION_INPUT
                    )
                    binding.root.findNavController().navigate(action)
                    true
                }

                R.id.menu_overlay_controls -> {
                    showOverlayOptions()
                    true
                }

                R.id.menu_lock_drawer -> {
                    when (IntSetting.LOCK_DRAWER.getInt()) {
                        DrawerLayout.LOCK_MODE_UNLOCKED -> {
                            IntSetting.LOCK_DRAWER.setInt(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                            it.title = resources.getString(R.string.unlock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_lock,
                                requireContext().theme
                            )
                        }

                        DrawerLayout.LOCK_MODE_LOCKED_CLOSED -> {
                            IntSetting.LOCK_DRAWER.setInt(DrawerLayout.LOCK_MODE_UNLOCKED)
                            it.title = resources.getString(R.string.lock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_unlock,
                                requireContext().theme
                            )
                        }
                    }
                    binding.inGameMenu.requestFocus()
                    NativeConfig.saveGlobalConfig()
                    true
                }

                R.id.menu_exit -> {
                    // Pause first, stop a moment later. reloadGlobalConfig()
                    // here reapplied Vulkan settings while the swapchain was
                    // dying and killed the process on Mali-G57.
                    clearPausedFrame()
                    runCatching { emulationState.pause() }
                    val leaving = game
                    Thread({
                        runCatching { GameCardMeta.captureCurrentFrame(leaving) }
                    }, "shot-on-exit").start()
                    emulationViewModel.setIsEmulationStopping(true)
                    binding.drawerLayout.close()
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching { emulationState.stop() }
                    }, 280)
                    true
                }

                else -> true
            }
        }

        addQuickSettings()

        binding.drawerLayout.addDrawerListener(object : DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // no op
            }

            override fun onDrawerOpened(drawerView: View) {
                if (drawerView == binding.quickSettingsSheet) {
                    isQuickSettingsMenuOpen = true
                    if (shouldUseCustom) {
                        SettingsFile.loadCustomConfig(game!!)
                    }
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                if (drawerView == binding.quickSettingsSheet) {
                    isQuickSettingsMenuOpen = false
                    if (shouldUseCustom) {
                        NativeConfig.unloadPerGameConfig()
                    }
                }
            }

            override fun onDrawerStateChanged(newState: Int) {
                // No op
            }
        })

        setInsets()

        requireActivity().onBackPressedDispatcher.addCallback(
            requireActivity(),
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!NativeLibrary.isRunning()) {
                        return
                    }
                    emulationViewModel.setDrawerOpen(!binding.drawerLayout.isOpen)
                }
            }
        )

        game?.let {
            GameIconUtils.loadGameIcon(it, binding.loadingImage)
            binding.loadingTitle.text = it.title
        } ?: run {
            binding.loadingTitle.text = ""
        }
        binding.loadingTitle.isSelected = true
        binding.loadingText.isSelected = true

        WindowInfoTracker.getOrCreate(requireContext())
            .windowLayoutInfo(requireActivity()).collect(viewLifecycleOwner) {
                updateFoldableLayout(requireActivity() as EmulationActivity, it)
            }
        emulationViewModel.shaderProgress.collect(viewLifecycleOwner) {
            if (it > 0 && it != emulationViewModel.totalShaders.value) {
                binding.loadingProgressIndicator.isIndeterminate = false

                if (it < binding.loadingProgressIndicator.max) {
                    binding.loadingProgressIndicator.progress = it
                }
            }

            if (it == emulationViewModel.totalShaders.value) {
                binding.loadingText.setText(R.string.loading)
                binding.loadingProgressIndicator.isIndeterminate = true
            }
        }
        emulationViewModel.totalShaders.collect(viewLifecycleOwner) {
            binding.loadingProgressIndicator.max = it
        }
        emulationViewModel.shaderMessage.collect(viewLifecycleOwner) {
            if (it.isNotEmpty()) {
                binding.loadingText.text = it
            }
        }

        emulationViewModel.emulationStarted.collect(viewLifecycleOwner) {
            if (it) {
                binding.drawerLayout.setDrawerLockMode(IntSetting.LOCK_DRAWER.getInt())
                ViewUtils.showView(binding.surfaceInputOverlay)
                ViewUtils.hideView(binding.loadingIndicator)

                emulationState.updateSurface()

                updateShowStatsOverlay()
                updateSocOverlay()

                initializeOverlayAutoHide()

                // Re update binding when the specs values get initialized properly
                binding.inGameMenu.getHeaderView(0).apply {
                    val titleView = findViewById<TextView>(R.id.text_game_title)
                    val cpuBackendLabel = findViewById<TextView>(R.id.cpu_backend)
                    val vendorLabel = findViewById<TextView>(R.id.gpu_vendor)

                    titleView.text = game?.title ?: ""
                    cpuBackendLabel.text = NativeLibrary.getCpuBackend()
                    vendorLabel.text = NativeLibrary.getGpuDriver()
                }

                val position = IntSetting.PERF_OVERLAY_POSITION.getInt()
                updateStatsPosition(position)

                val socPosition = IntSetting.SOC_OVERLAY_POSITION.getInt()
                updateSocPosition(socPosition)
            }
        }
        emulationViewModel.isEmulationStopping.collect(viewLifecycleOwner) {
            if (it) {
                binding.loadingText.setText(R.string.shutting_down)
                ViewUtils.showView(binding.loadingIndicator)
                ViewUtils.hideView(binding.inputContainer)
                ViewUtils.hideView(binding.showStatsOverlayText)
            } else if (deferGameSetupUntilStopCompletes) {
                if (!isAdded) {
                    return@collect
                }
                deferGameSetupUntilStopCompletes = false
                finishGameSetup()
            }
        }
        emulationViewModel.drawerOpen.collect(viewLifecycleOwner) {
            if (it) {
                binding.drawerLayout.open()
                binding.inGameMenu.requestFocus()
            } else {
                binding.drawerLayout.close()
            }
        }
        emulationViewModel.programChanged.collect(viewLifecycleOwner) {
            if (it != 0) {
                emulationViewModel.setEmulationStarted(false)
                binding.drawerLayout.close()
                binding.drawerLayout
                    .setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                ViewUtils.hideView(binding.surfaceInputOverlay)
                ViewUtils.showView(binding.loadingIndicator)
            }
        }

        emulationViewModel.emulationStopped.collect(viewLifecycleOwner) { stopped ->
            if (stopped && emulationViewModel.programChanged.value != -1) {
                perfStatsRunnable?.let { runnable ->
                    perfStatsUpdateHandler.removeCallbacks(
                        runnable
                    )
                }
                socRunnable?.let { runnable -> socUpdateHandler.removeCallbacks(runnable) }
                emulationState.changeProgram(emulationViewModel.programChanged.value)
                emulationViewModel.setProgramChanged(-1)
                emulationViewModel.setEmulationStopped(false)
            }
        }

        driverViewModel.isInteractionAllowed.collect(viewLifecycleOwner) {
            if (it &&
                !isStoppingForRomSwap &&
                !NativeLibrary.isRunning() &&
                !NativeLibrary.isPaused()
            ) {
                if (!DirectoryInitialization.areDirectoriesReady) {
                    DirectoryInitialization.start()
                }

                updateScreenLayout()

                emulationState.run(emulationActivity!!.isActivityRecreated)
            }
        }

        driverViewModel.onLaunchGame()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val b = _binding ?: return

        updateScreenLayout()
        val showInputOverlay = BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()
        if (emulationActivity?.isInPictureInPictureMode == true) {
            if (b.drawerLayout.isOpen) {
                b.drawerLayout.close()
            }
            if (showInputOverlay) {
                b.surfaceInputOverlay.setVisible(visible = false, gone = false)
            }
        } else {
            val shouldShowOverlay = if (args.overlayGamelessEditMode) {
                true
            } else {
                showInputOverlay && emulationViewModel.emulationStarted.value &&
                    !hasPhysicalControllerConnected
            }
            b.surfaceInputOverlay.setVisible(shouldShowOverlay)
            if (!isInFoldableLayout) {
                if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    b.surfaceInputOverlay.layout = OverlayLayout.Portrait
                } else {
                    b.surfaceInputOverlay.layout = OverlayLayout.Landscape
                }
            }
        }
    }

    private fun updateGameTitle() {
        game?.let {
            binding.inGameMenu.getHeaderView(0).apply {
                val titleView = findViewById<TextView>(R.id.text_game_title)
                titleView.text = it.title
            }
        }
    }

    private fun addQuickSettings() {
        binding.quickSettingsSheet.apply {
            val container = binding.quickSettingsSheet.findViewById<ViewGroup>(R.id.quick_settings_container)
            val isFsrSelected = isFsrScalingFilterSelected()

            container.removeAllViews()

            if (shouldUseCustom) {
                quickSettings.addPerGameConfigStatusIndicator(container)
            }

            lateinit var slowSpeed: MaterialSwitch
            lateinit var turboSpeed: MaterialSwitch

            turboSpeed = quickSettings.addCustomToggle(
                R.string.turbo_speed_limit,
                NativeLibrary.isTurboMode(),
                BooleanSetting.RENDERER_USE_SPEED_LIMIT.getBoolean(false),
                container
            ) { enabled ->
                if (enabled)
                    slowSpeed.isChecked = false
                NativeLibrary.setTurboSpeedLimit(enabled)
            }!!

            slowSpeed = quickSettings.addCustomToggle(
                R.string.slow_speed_limit,
                NativeLibrary.isSlowMode(),
                BooleanSetting.RENDERER_USE_SPEED_LIMIT.getBoolean(false),
                container
            ) { enabled ->
                if (enabled)
                    turboSpeed.isChecked = false
                NativeLibrary.setSlowSpeedLimit(enabled)
            }!!

            quickSettings.addCustomToggle(
                R.string.frame_limit_enable,
                BooleanSetting.RENDERER_USE_SPEED_LIMIT.getBoolean(false),
                true,
                container
            ) { enabled ->
                if (!enabled) {
                    turboSpeed.isChecked = false
                    slowSpeed.isChecked = false
                }

                turboSpeed.isEnabled = enabled
                slowSpeed.isEnabled = enabled

                NativeLibrary.setStandardSpeedLimit(enabled)
            }!!

            quickSettings.addSliderSetting(
                R.string.frame_limit_slider,
                container,
                ShortSetting.RENDERER_SPEED_LIMIT,
                minValue = 0,
                maxValue = 400,
                units = "%",
            )

            quickSettings.addBooleanSetting(
                R.string.use_docked_mode,
                container,
                BooleanSetting.USE_DOCKED_MODE,
            )

            quickSettings.addDivider(container)

            quickSettings.addIntSetting(
                R.string.renderer_accuracy,
                container,
                IntSetting.RENDERER_ACCURACY,
                R.array.rendererAccuracyNames,
                R.array.rendererAccuracyValues
            )


            quickSettings.addIntSetting(
                R.string.renderer_scaling_filter,
                container,
                IntSetting.RENDERER_SCALING_FILTER,
                R.array.rendererScalingFilterNames,
                R.array.rendererScalingFilterValues
            ) {
                addQuickSettings()
            }

            if (isFsrSelected) {
                quickSettings.addSliderSetting(
                    R.string.fsr_sharpness,
                    container,
                    IntSetting.FSR_SHARPENING_SLIDER,
                    minValue = 0,
                    maxValue = 100,
                    units = "%"
                )
            }

            quickSettings.addIntSetting(
                R.string.renderer_anti_aliasing,
                container,
                IntSetting.RENDERER_ANTI_ALIASING,
                R.array.rendererAntiAliasingNames,
                R.array.rendererAntiAliasingValues
            )

            // --- то, чего здесь не было -------------------------------
            //
            // Стандартная панель умеет десять пунктов, и в ней нет ни
            // разрешения, ни звука. Отсюда и «качество поменять не могу»:
            // настройка есть в общих настройках, но в игре её не достать.
            addSymbiosisQuickSettings(container)
        }
    }

    /**
     * Дополнительные пункты панели: изображение целиком и звук целиком.
     *
     * Каждый пункт подписан «сразу» или «после перезапуска» — по таблице из
     * [QuickPanel], собранной по коду ядра. Без подписи человек меняет
     * разрешение, не видит разницы и решает, что настройки не работают.
     */
    private fun addSymbiosisQuickSettings(container: ViewGroup) {
        // Ни один наш пункт не имеет права уронить игру. Панель — это экран
        // поверх работающей эмуляции: исключение здесь закрывает её целиком,
        // причём молча (в отчёте остаётся только «остановлено»). Поэтому всё
        // тело под защитой, а причина пишется в журнал, а не теряется.
        try {
            buildSymbiosisQuickSettings(container)
        } catch (error: RuntimeException) {
            Log.error("[Symbiosis] панель быстрых настроек: ${error.message}")
            runCatching {
                container.addView(TextView(requireContext()).apply {
                    text = "Дополнительные настройки не показаны: ${error.message?.take(120)}"
                    textSize = 12f
                    setTextColor(quickColor(android.R.attr.textColorSecondary))
                })
            }
        }
    }

    private fun buildSymbiosisQuickSettings(container: ViewGroup) {
        val ctx = requireContext()
        val dimColor = quickColor(android.R.attr.textColorSecondary)
        val primaryColor = quickColor(
            com.google.android.material.R.attr.colorPrimary,
            android.R.attr.colorAccent
        )
        val surfaceColor = quickColor(
            com.google.android.material.R.attr.colorSurfaceVariant,
            com.google.android.material.R.attr.colorSurface,
            android.R.attr.colorBackground
        )
        val spacing = resources.getDimensionPixelSize(R.dimen.spacing_small)

        fun header(text: String, top: Int = spacing): TextView = TextView(ctx).apply {
            this.text = text
            setPadding(0, top, 0, 0)
            setTextColor(primaryColor)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }

        fun badge(entry: QuickPanel.Entry): TextView = TextView(ctx).apply {
            text = QuickPanel.badge(entry)
            textSize = 11f
            setTextColor(if (entry.live) dimColor else 0xFFFF6B6B.toInt())
            setPadding(0, 0, 0, spacing)
        }

        fun hint(text: String): TextView = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(dimColor)
            setPadding(0, spacing / 2, 0, 0)
        }

        fun entryOf(key: String): QuickPanel.Entry =
            QuickPanel.entries.first { it.key == key }

        quickSettings.addDivider(container)
        container.addView(header("Изображение", top = 0))
        container.addView(badge(entryOf("resolution_setup")))

        quickSettings.addIntSetting(
            R.string.renderer_resolution,
            container,
            IntSetting.RENDERER_RESOLUTION,
            R.array.rendererResolutionNames,
            R.array.rendererResolutionValues
        ) {
            runCatching { NativeSymbiosis.setMainStep("настройки: разрешение") }
            markQuickChange("resolution_setup")
        }

        quickSettings.addIntSetting(
            R.string.renderer_vsync,
            container,
            IntSetting.RENDERER_VSYNC,
            R.array.rendererVSyncNames,
            R.array.rendererVSyncValues
        ) {
            runCatching { NativeSymbiosis.setMainStep("настройки: vsync") }
            markQuickChange("use_vsync")
        }

        quickSettings.addDivider(container)
        container.addView(header("Звук", top = 0))

        // Громкость — единственная звуковая настройка, которая меняется на
        // ходу, поэтому она первая: если человек просто хочет тише, ему не
        // нужно ничего перезапускать.
        container.addView(TextView(ctx).apply {
            text = "Громкость · ${QuickPanel.badge(entryOf("audio_volume"))}"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
            setPadding(0, spacing, 0, 0)
        })
        container.addView(
            com.google.android.material.slider.Slider(ctx).apply {
                valueFrom = 0f
                valueTo = 100f
                stepSize = 1f
                // Громкость — байтовая настройка (ByteSetting.AUDIO_VOLUME),
                // а не целочисленная: getInt по её ключу вернул бы 0, и
                // ползунок каждый раз показывал бы «тишину».
                value = runCatching { ByteSetting.AUDIO_VOLUME.getByte(needsGlobal = false) }
                    .getOrDefault(100).toInt().coerceIn(0, 100).toFloat()
                addOnChangeListener { _, value, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    // set_byte у Eden требует байт: значение 0..100 в него
                    // влезает, но тип приводим явно, иначе подпись не сойдётся.
                    applySetting("громкость") {
                        ByteSetting.AUDIO_VOLUME.setByte(value.toInt().toByte())
                    }
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(badge(entryOf("output_engine")))

        quickSettings.addIntSetting(
            R.string.audio_output_engine,
            container,
            IntSetting.AUDIO_OUTPUT_ENGINE,
            R.array.outputEngineEntries,
            R.array.outputEngineValues
        ) {
            runCatching { NativeSymbiosis.setMainStep("настройки: движок вывода") }
            markQuickChange("output_engine")
        }

        // Частота звука: три варианта, у каждого — следствие словами.
        //
        // Почему она перед буфером: при жалобе «шумы и хрип в музыке» первым
        // подозреваемым оказался пересчёт частоты, а не размер буфера. Человек
        // поднимает буфер, ничего не меняется — потому что дело было не в нём.
        container.addView(header("Частота звука"))
        val rateHint = hint(QuickPanel.rateHint(currentAudioRate()))
        container.addView(rateHint)
        val rateButtons = mutableListOf<Pair<Int, com.google.android.material.button.MaterialButton>>()
        fun paintRateChip(button: com.google.android.material.button.MaterialButton, selected: Boolean) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (selected) primaryColor else surfaceColor
            )
            button.setTextColor(if (selected) 0xFF0E1116.toInt() else dimColor)
        }
        val rateChips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, spacing, 0, 0)
        }
        QuickPanel.rateChoices.forEach { rate ->
            val button = com.google.android.material.button.MaterialButton(ctx).apply {
                text = if (rate == 0) "авто" else "$rate Гц"
                textSize = 13f
                isAllCaps = false
                cornerRadius = spacing
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                paintRateChip(this, currentAudioRate() == rate)
                setOnClickListener {
                    applySetting("частота звука") { NativeConfig.setInt(QuickPanel.RATE_KEY, rate) }
                    rateButtons.forEach { (chipRate, chip) -> paintRateChip(chip, chipRate == rate) }
                    rateHint.text = QuickPanel.rateHint(rate)
                    markQuickChange(QuickPanel.RATE_KEY)
                }
            }
            rateButtons.add(rate to button)
            rateChips.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = spacing }
            )
        }
        container.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(rateChips)
            }
        )
        container.addView(badge(entryOf("symbiosis_audio_rate")))

        // Замеры звука: цифры из работающего потока.
        //
        // Это ответ на «применил настройку — не помогло». Здесь видно, дошла
        // ли настройка до драйвера: какая частота пришла, сколько кадров в
        // буфере, сколько раз буфер кончился. Если цифры те же, что были, —
        // настройка не применилась; если изменились, а хрип остался — дело не
        // в ней.
        val audioMeasure = TextView(ctx).apply {
            textSize = 12f
            setTextColor(dimColor)
            setPadding(0, spacing, 0, 0)
        }
        runCatching { audioMeasure.text = audioMeasurementText() }
        container.addView(audioMeasure)

        // Буфер звука: чипы, а не поле ввода. Значений мало и они
        // осмысленные, а промахнуться пальцем по числу — легко.
        container.addView(header("Буфер звука"))
        val bufferHint = hint(QuickPanel.bufferHint(currentAudioBufferMs()))
        container.addView(bufferHint)

        // Подсветка выбранного чипа обновляется на месте: пересобирать панель
        // из обработчика нажатия нельзя (см. quickPanelRefresh).
        val bufferButtons = mutableListOf<Pair<Int, com.google.android.material.button.MaterialButton>>()
        fun paintBufferChip(button: com.google.android.material.button.MaterialButton, selected: Boolean) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (selected) primaryColor else surfaceColor
            )
            button.setTextColor(if (selected) 0xFF0E1116.toInt() else dimColor)
        }

        val chips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, spacing, 0, 0)
        }
        QuickPanel.bufferChoices.forEach { ms ->
            val button = com.google.android.material.button.MaterialButton(ctx).apply {
                text = "$ms мс"
                textSize = 13f
                isAllCaps = false
                // setCornerRadius в Material 1.12 принимает пиксели (int), а
                // не float: прогон 41329247719 упал именно на этом.
                cornerRadius = spacing
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                paintBufferChip(this, currentAudioBufferMs() == ms)
                setOnClickListener {
                    applySetting("буфер звука") { NativeConfig.setInt(QuickPanel.BUFFER_KEY, ms) }
                    // Только подсветка и подписи — без removeAllViews.
                    bufferButtons.forEach { (chipMs, chip) -> paintBufferChip(chip, chipMs == ms) }
                    bufferHint.text = QuickPanel.bufferHint(ms)
                    markQuickChange(QuickPanel.BUFFER_KEY)
                }
            }
            bufferButtons.add(ms to button)
            chips.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = spacing }
            )
        }
        container.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }
        )
        container.addView(badge(entryOf("symbiosis_audio_buffer_ms")))

        // Итог и кнопка перезапуска. Кнопка создаётся всегда и только
        // показывается по необходимости: добавить её позже значило бы снова
        // пересобрать панель, а это и было причиной остановки игры.
        val status = TextView(ctx).apply {
            textSize = 12f
            setTextColor(dimColor)
            setPadding(0, spacing, 0, 0)
        }
        container.addView(status)

        val restart = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "Перезапустить игру"
            textSize = 14f
            isAllCaps = false
            setOnClickListener { restartGameWithSettings() }
        }
        container.addView(
            restart,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = spacing }
        )

        fun refresh() {
            status.text = QuickPanel.statusLine(quickChanges).ifEmpty {
                "Громкость и предел кадров применяются сразу; разрешение, vsync и звук — после перезапуска."
            }
            restart.visibility =
                if (QuickPanel.needsRestart(quickChanges)) View.VISIBLE else View.GONE
        }
        refresh()
        quickPanelRefresh = { runCatching { refresh() } }
    }

    /**
     * Применить настройку и записать её на диск, отметив шаг для отчёта.
     *
     * ОДНО МЕСТО ВМЕСТО ПЯТИ. Раньше каждая кнопка панели сама звала
     * `saveGlobalConfig`/`savePerGameConfig`, и падение на любой из них
     * выглядело одинаково: «приложение остановлено», без единого указания,
     * какой пункт его уронил. Теперь шаг пишется в нативный отчёт до вызова,
     * а исключение не выходит наружу: пункт, который не смог примениться,
     * пишет причину в журнал и не забирает с собой игру.
     */
    private fun applySetting(label: String, change: () -> Unit) {
        runCatching { NativeSymbiosis.setMainStep("настройки: $label") }
        val result = runCatching { change() }
            .onFailure { Log.error("[Symbiosis] настройка «$label» не применилась: ${it.message}") }
        if (result.isFailure) {
            runCatching {
                Toast.makeText(
                    requireContext(),
                    "«$label» не применилось: ${result.exceptionOrNull()?.message ?: "неизвестная причина"}",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        runCatching { NativeConfig.saveGlobalConfig() }
        if (shouldUseCustom) runCatching { NativeConfig.savePerGameConfig() }
    }

    /**
     * Запомнить, что пункт меняли: от этого зависит и подпись, и кнопка.
     *
     * Панель при этом НЕ пересобирается — см. [quickPanelRefresh].
     */
    private fun markQuickChange(key: String) {
        quickChanges.add(key)
        runCatching { quickPanelRefresh?.invoke() }
    }

    /**
     * Цвет темы, но не падение, если атрибута нет.
     *
     * `MaterialColors.getColor` бросает исключение, когда атрибут не задан в
     * теме. Панель с пятью пунктами обращается к цветам чаще остальных экранов,
     * и одного отсутствующего атрибута хватало, чтобы игра закрылась. Поэтому
     * пробуем по порядку и в конце берём запасной оттенок текста.
     */
    private fun quickColor(vararg attrs: Int): Int {
        for (attr in attrs) {
            val color = runCatching { MaterialColors.getColor(binding.root, attr) }.getOrNull()
            if (color != null) return color
        }
        return 0xFFA6B2C4.toInt()
    }

    /** Частота звука, выбранная сейчас. 0 — «как решит драйвер», это и есть умолчание. */
    private fun currentAudioRate(): Int =
        runCatching { NativeConfig.getInt(QuickPanel.RATE_KEY, false) }.getOrDefault(0)

    /**
     * Замеры звука одной строкой для панели.
     *
     * Источник — работающий поток, а не настройки: именно поэтому по строке
     * видно, применилась ли настройка. Пусто, если поток ещё не открывался.
     */
    private fun audioMeasurementText(): String {
        val raw = runCatching { NativeSymbiosis.getAudioReport() }.getOrNull().orEmpty()
        if (raw.isBlank()) return ""
        val parts = raw.split('|')
        val summary = parts.lastOrNull().orEmpty()
        if (summary.isBlank()) return ""
        val requested = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val device = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val buffer = parts.getOrNull(3)?.toIntOrNull() ?: 0
        val xruns = parts.getOrNull(6)?.toIntOrNull() ?: 0
        val asked = if (requested == 0) "частоту выбирал драйвер" else "просили $requested Гц"
        val measured = "Сейчас: $asked, телефон дал $device Гц, буфер $buffer кадров, " +
            "опустошений $xruns."
        return measured + "\n" + summary
    }

    private fun currentAudioBufferMs(): Int =
        runCatching { NativeConfig.getInt(QuickPanel.BUFFER_KEY, false) }
            .getOrDefault(20).let { if (it <= 0) 20 else it }

    /**
     * Перезапуск игры с сохранёнными настройками.
     *
     * ЧЕГО ЗДЕСЬ БОЛЬШЕ НЕТ И ПОЧЕМУ.
     *
     * Раньше здесь было `NativeLibrary.stopEmulation()`, `activity.finish()`,
     * а затем `EmulationActivity.launch(...)`. На телефоне это выглядело так:
     * нажал «Перезапустить игру» — выскочила ошибка и приложение ушло на
     * рабочий стол или в лаунчер.
     *
     * Причина в том, что эти три вызова обходят штатную смену игры в Eden.
     * `EmulationActivity` объявлен `singleTop` и умеет ровно это: новый intent
     * на него приходит в `onNewIntent`, оттуда — в `handleSwapIntent`, который
     * ОСТАНАВЛИВАЕТ сессию правильно (`stopForRomSwap`), ДОЖИДАЕТСЯ остановки
     * потока эмуляции (`notifyWhenEmulationThreadStops`) и только потом
     * запускает игру заново. Мы же закрывали активити руками и просили старт
     * у уже закрывающейся — Android такой запуск выбрасывает, и человек
     * оставался в лаунчере. А `stopEmulation()` в обход фрагмента оставлял
     * поверхность и поток в состоянии, из которого следующий кадр ронял
     * процесс.
     *
     * Теперь: сохранить настройки и попросить тот же экран перезапуститься.
     * Всё остальное — забота Eden, у которого для этого уже есть код.
     */
    private fun restartGameWithSettings() {
        val target = game ?: return
        val activity = emulationActivity ?: return
        runCatching { NativeConfig.saveGlobalConfig() }
        if (shouldUseCustom) runCatching { NativeConfig.savePerGameConfig() }
        quickChanges.clear()
        runCatching { NativeSymbiosis.setMainStep("перезапуск игры") }
        // Меню закрываем до перезапуска: экран перезапускается, и открытая
        // панель поверх него — это лишний шанс на «Cannot perform this action
        // after onSaveInstanceState».
        runCatching { binding.drawerLayout.closeDrawer(binding.quickSettingsSheet) }
        runCatching { EmulationActivity.launch(activity, target) }
            .onFailure {
                Log.error("[Symbiosis] перезапуск игры не удался: ${it.message}")
                Toast.makeText(
                    activity,
                    "Не удалось перезапустить игру: ${it.message ?: "неизвестная причина"}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    /** Файл с путём последней запущенной игры. */
    private fun lastGameFile(context: android.content.Context) =
        java.io.File(java.io.File(context.getExternalFilesDir(null), "kenji"), "last-game.txt")

    /**
     * Собрать игру по записанному пути.
     *
     * Тихо возвращает null, если записи нет или файл уже удалён: подсказка не
     * должна превращаться в ошибку, которую человек не может исправить.
     */
    private fun lastGameFromDisk(): Game? = runCatching {
        val path = LastGame.load(lastGameFile(requireContext())) ?: return@runCatching null
        GameHelper.getGame(Uri.parse(path), false)
    }.getOrNull()

    private fun isFsrScalingFilterSelected(): Boolean {
        val fsrFilterValue = resolveFsrScalingFilterValue() ?: return false
        val selectedFilter = IntSetting.RENDERER_SCALING_FILTER.getInt(needsGlobal = false)
        return selectedFilter == fsrFilterValue
    }

    private fun resolveFsrScalingFilterValue(): Int? {
        val names = resources.getStringArray(R.array.rendererScalingFilterNames)
        val values = resources.getIntArray(R.array.rendererScalingFilterValues)
        val fsrIndex = names.indexOf(getString(R.string.scaling_filter_fsr))
        return if (fsrIndex in values.indices) values[fsrIndex] else null
    }

    private fun openQuickSettingsMenu() {
        binding.drawerLayout.closeDrawer(binding.inGameMenu)
        binding.drawerLayout.openDrawer(binding.quickSettingsSheet)
    }

    private fun updateQuickOverlayMenuEntry(isVisible: Boolean) {
        val b = _binding ?: return
        val item = b.inGameMenu.menu.findItem(R.id.menu_quick_overlay) ?: return

        if (isVisible) {
            item.title = getString(R.string.emulation_hide_overlay)
            item.icon = ResourcesCompat.getDrawable(
                resources,
                R.drawable.ic_controller_disconnected,
                requireContext().theme
            )
        } else {
            item.title = getString(R.string.emulation_show_overlay)
            item.icon = ResourcesCompat.getDrawable(
                resources,
                R.drawable.ic_controller,
                requireContext().theme
            )
        }
    }

    private fun updatePauseMenuEntry(isPaused: Boolean) {
        val b = _binding ?: return
        val pauseItem = b.inGameMenu.menu.findItem(R.id.menu_pause_emulation) ?: return
        if (isPaused) {
            pauseItem.title = getString(R.string.emulation_unpause)
            pauseItem.icon = ResourcesCompat.getDrawable(
                resources,
                R.drawable.ic_play,
                requireContext().theme
            )
        } else {
            pauseItem.title = getString(R.string.emulation_pause)
            pauseItem.icon = ResourcesCompat.getDrawable(
                resources,
                R.drawable.ic_pause,
                requireContext().theme
            )
        }
    }

    private fun pauseEmulationAndCaptureFrame() {
        emulationState.pause()
        updatePauseMenuEntry(true)
        capturePausedFrameFromCore()
        updatePausedFrameVisibility()
    }

    private fun capturePausedFrameFromCore() {
        lifecycleScope.launch(Dispatchers.Default) {
            val frameData = NativeLibrary.getAppletCaptureBuffer()
            val width = NativeLibrary.getAppletCaptureWidth()
            val height = NativeLibrary.getAppletCaptureHeight()
            if (frameData.isEmpty() || width <= 0 || height <= 0) {
                Log.warning(
                    "[EmulationFragment] Paused frame capture returned empty/invalid data. " +
                        "size=${frameData.size}, width=$width, height=$height"
                )
                return@launch
            }

            val expectedSize = width * height * 4
            if (frameData.size < expectedSize) {
                Log.warning(
                    "[EmulationFragment] Paused frame buffer smaller than expected. " +
                        "size=${frameData.size}, expected=$expectedSize"
                )
                return@launch
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frameData, 0, expectedSize))

            withContext(Dispatchers.Main) {
                pausedFrameBitmap?.recycle()
                pausedFrameBitmap = bitmap
                updatePausedFrameVisibility()
            }
        }
    }

    private fun updatePausedFrameVisibility() {
        val b = _binding ?: return
        val showPausedUi = this::emulationState.isInitialized && emulationState.isPaused
        b.pausedIcon.setVisible(showPausedUi)

        val bitmap = if (showPausedUi) pausedFrameBitmap else null
        b.pausedFrameImage.setImageBitmap(bitmap)
        b.pausedFrameImage.setVisible(bitmap != null)
    }

    private fun resumeEmulationFromUi() {
        clearPausedFrame()
        emulationState.resume()
        updatePauseMenuEntry(emulationState.isPaused)
        updatePausedFrameVisibility()
    }

    private fun clearPausedFrame() {
        val b = _binding
        b?.pausedFrameImage?.setVisible(false)
        b?.pausedFrameImage?.setImageDrawable(null)
        pausedFrameBitmap?.recycle()
        pausedFrameBitmap = null
    }

    private fun handleLoadAmiiboSelection(): Boolean {
        val binding = _binding ?: return true

        binding.inGameMenu.requestFocus()

        if (!NativeLibrary.isRunning()) {
            showAmiiboDialog(R.string.amiibo_wrong_state)
            return true
        }

        when (AmiiboState.fromValue(NativeLibrary.getVirtualAmiiboState())) {
            AmiiboState.TagNearby -> {
                amiiboLoadJob?.cancel()
                NativeInput.onRemoveNfcTag()
                showAmiiboDialog(R.string.amiibo_removed_message)
            }

            AmiiboState.WaitingForAmiibo -> {
                if (isAmiiboPickerOpen) {
                    return true
                }

                isAmiiboPickerOpen = true
                binding.drawerLayout.close()
                loadAmiiboLauncher.launch(AMIIBO_MIME_TYPES)
            }

            else -> showAmiiboDialog(R.string.amiibo_wrong_state)
        }

        return true
    }

    private fun handleAmiiboLoadResult(result: Int) {
        when (AmiiboLoadResult.fromValue(result)) {
            AmiiboLoadResult.Success -> {
                if (!isAdded) {
                    return
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.amiibo_load_success),
                    Toast.LENGTH_SHORT
                ).show()
            }

            AmiiboLoadResult.UnableToLoad -> showAmiiboDialog(R.string.amiibo_in_use)
            AmiiboLoadResult.NotAnAmiibo -> showAmiiboDialog(R.string.amiibo_not_valid)
            AmiiboLoadResult.WrongDeviceState -> showAmiiboDialog(R.string.amiibo_wrong_state)
            AmiiboLoadResult.Unknown -> showAmiiboDialog(R.string.amiibo_unknown_error)
        }
    }

    private fun showAmiiboDialog(@StringRes messageRes: Int) {
        if (!isAdded) {
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.amiibo_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private suspend fun readAmiiboFile(uri: Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            val resolver = context?.contentResolver ?: return@withContext null
            try {
                resolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = stream.read(chunk)
                        if (read == -1) {
                            break
                        }
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.error("[EmulationFragment] Failed to read amiibo: ${e.message}")
                null
            }
        }

    override fun onPause() {
        if (this::emulationState.isInitialized) {
            if (emulationState.isRunning && emulationActivity?.isInPictureInPictureMode != true) {
                pauseEmulationAndCaptureFrame()
            } else {
                updatePausedFrameVisibility()
            }
        }
        super.onPause()
    }

    override fun onDestroyView() {
        // Сейвы — наверх, пока приложение открыто и сеть есть. Тихо: если не
        // вышло, следующий запуск попробует снова, а человеку не нужен
        // диалог об облаке в момент, когда он закрыл игру.
        runCatching { game?.let { CloudSync.uploadSavesQuietly(it) } }
        super.onDestroyView()
        amiiboLoadJob?.cancel()
        amiiboLoadJob = null
        perfStatsRunnable?.let { perfStatsUpdateHandler.removeCallbacks(it) }
        socRunnable?.let { socUpdateHandler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        clearPausedFrame()
        _binding?.surfaceInputOverlay?.touchEventListener = null
        _binding = null
        isAmiiboPickerOpen = false
    }

    override fun onDetach() {
        if (!hasNewerEmulationFragment()) {
            NativeLibrary.clearEmulationActivity()
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        val b = _binding ?: return
        updateStatsPosition(IntSetting.PERF_OVERLAY_POSITION.getInt())
        updateSocPosition(IntSetting.SOC_OVERLAY_POSITION.getInt())

        if (this::emulationState.isInitialized) {
            b.inGameMenu.post {
                if (!this::emulationState.isInitialized || _binding == null) return@post
                updatePauseMenuEntry(emulationState.isPaused)
                updatePausedFrameVisibility()
            }
        }

        // if the overlay auto-hide setting is changed while paused,
        // we need to reinitialize the auto-hide timer
        initializeOverlayAutoHide()

        addQuickSettings()
    }

    private fun resetInputOverlay() {
        IntSetting.OVERLAY_SCALE.reset()
        IntSetting.OVERLAY_OPACITY.reset()
        binding.surfaceInputOverlay.post {
            binding.surfaceInputOverlay.resetLayoutVisibilityAndPlacement()
            binding.surfaceInputOverlay.resetIndividualControlScale()
        }
    }

    @SuppressLint("DefaultLocale")
    private fun updateShowStatsOverlay() {
        val showPerfOverlay = BooleanSetting.SHOW_PERFORMANCE_OVERLAY.getBoolean()
        binding.showStatsOverlayText.apply {
            setTextColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
        }
        binding.showStatsOverlayText.setVisible(showPerfOverlay)
        if (showPerfOverlay) {
            //val SYSTEM_FPS = 0
            val FPS = 1
            val FRAMETIME = 2
            //val SPEED = 3
            val sb = StringBuilder()
            perfStatsUpdater = {
                if (emulationViewModel.emulationStarted.value &&
                    !emulationViewModel.isEmulationStopping.value
                ) {
                    val needsGlobal = NativeConfig.isPerGameConfigLoaded()
                    sb.setLength(0)

                    val perfStats = NativeLibrary.getPerfStats()
                    val actualFps = perfStats[FPS]

                    if (BooleanSetting.SHOW_FPS.getBoolean(needsGlobal)) {
                        var fpsText = String.format("FPS: %.1f", actualFps)
                        sb.append(fpsText)
                    }

                    if (BooleanSetting.SHOW_FRAMETIME.getBoolean(needsGlobal)) {
                        if (sb.isNotEmpty()) sb.append(" | ")
                        sb.append(
                            String.format(
                                "FT: %.1fms",
                                (perfStats[FRAMETIME] * 1000.0f).toFloat()
                            )
                        )
                    }

                    if (BooleanSetting.SHOW_APP_RAM_USAGE.getBoolean(needsGlobal)) {
                        if (sb.isNotEmpty()) sb.append(" | ")
                        val appRamUsage =
                            File("/proc/self/statm").readLines()[0].split(' ')[1].toLong() * 4096 / 1000000
                        sb.append(getString(R.string.process_ram, appRamUsage))
                    }

                    if (BooleanSetting.SHOW_SYSTEM_RAM_USAGE.getBoolean(needsGlobal)) {
                        if (sb.isNotEmpty()) sb.append(" | ")
                        context?.let { ctx ->
                            val activityManager =
                                ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            val memInfo = ActivityManager.MemoryInfo()
                            activityManager.getMemoryInfo(memInfo)
                            val usedRamMB = (memInfo.totalMem - memInfo.availMem) / 1048576L
                            sb.append("RAM: $usedRamMB MB")
                        }
                    }

                    if (BooleanSetting.SHOW_BAT_TEMPERATURE.getBoolean(needsGlobal)) {
                        if (sb.isNotEmpty()) sb.append(" | ")

                        val batteryTemp = getBatteryTemperature()
                        when (IntSetting.BAT_TEMPERATURE_UNIT.getInt(needsGlobal)) {
                            0 -> sb.append(String.format("%.1f°C", batteryTemp))
                            1 -> sb.append(
                                String.format(
                                    "%.1f°F",
                                    celsiusToFahrenheit(batteryTemp)
                                )
                            )
                        }
                    }

                    if (BooleanSetting.SHOW_POWER_INFO.getBoolean(needsGlobal)) {
                        if (sb.isNotEmpty()) sb.append(" | ")

                        val battery: BatteryManager =
                            requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                        val batteryIntent = requireContext().registerReceiver(
                            null,
                            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                        )

                        val capacity = battery.getIntProperty(BATTERY_PROPERTY_CAPACITY)
                        val nowUAmps = battery.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)

                        sb.append(String.format("%.1fA (%d%%)", nowUAmps / 1000000.0, capacity))

                        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL

                        if (isCharging) {
                            sb.append(" ${getString(R.string.charging)}")
                        }
                    }

                    val shadersBuilding = NativeLibrary.getShadersBuilding()

                    if (BooleanSetting.SHOW_SHADERS_BUILDING.getBoolean(needsGlobal) && shadersBuilding != 0) {
                        if (sb.isNotEmpty()) sb.append(" | ")

                        val prefix = getString(R.string.shaders_prefix)
                        val suffix = getString(R.string.shaders_suffix)
                        sb.append(String.format("$prefix %d $suffix", shadersBuilding))
                    }

                    if (BooleanSetting.PERF_OVERLAY_BACKGROUND.getBoolean(needsGlobal)) {
                        binding.showStatsOverlayText.setBackgroundResource(
                            R.color.yuzu_transparent_black
                        )
                    } else {
                        binding.showStatsOverlayText.setBackgroundResource(0)
                    }

                    binding.showStatsOverlayText.text = sb.toString()
                }
                perfStatsUpdateHandler.postDelayed(perfStatsRunnable!!, 800)
            }
            perfStatsRunnable = Runnable { perfStatsUpdater?.invoke() }
            perfStatsUpdateHandler.post(perfStatsRunnable!!)
        } else {
            perfStatsRunnable?.let { perfStatsUpdateHandler.removeCallbacks(it) }
        }
    }

    private fun updateStatsPosition(position: Int) {
        updateOverlayPosition(binding.showStatsOverlayText, position)
    }

    private fun updateSocPosition(position: Int) {
        updateOverlayPosition(binding.showSocOverlayText, position)
    }

    private fun updateOverlayPosition(overlay: MaterialTextView, position: Int) {
        val params = overlay.layoutParams as FrameLayout.LayoutParams
        when (position) {
            0 -> {
                params.gravity = (Gravity.TOP or Gravity.START)
                params.setMargins(resources.getDimensionPixelSize(R.dimen.spacing_large), 0, 0, 0)
            }

            1 -> {
                params.gravity = (Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            }

            2 -> {
                params.gravity = (Gravity.TOP or Gravity.END)
                params.setMargins(0, 0, resources.getDimensionPixelSize(R.dimen.spacing_large), 0)
            }

            3 -> {
                params.gravity = (Gravity.BOTTOM or Gravity.START)
                params.setMargins(resources.getDimensionPixelSize(R.dimen.spacing_large), 0, 0, 0)
            }

            4 -> {
                params.gravity = (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            }

            5 -> {
                params.gravity = (Gravity.BOTTOM or Gravity.END)
                params.setMargins(0, 0, resources.getDimensionPixelSize(R.dimen.spacing_large), 0)
            }
        }
    }

    private fun getBatteryTemperature(): Float {
        try {
            val batteryIntent =
                requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            // Temperature in tenths of a degree Celsius
            val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            // Convert to degrees Celsius
            return temperature / 10.0f
        } catch (e: Exception) {
            return 0.0f
        }
    }

    private fun celsiusToFahrenheit(celsius: Float): Float {
        return (celsius * 9 / 5) + 32
    }

    private fun updateSocOverlay() {
        val showSOCOverlay = BooleanSetting.SHOW_SOC_OVERLAY.getBoolean()
        binding.showSocOverlayText.apply {
            setTextColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
        }
        binding.showSocOverlayText.setVisible(showSOCOverlay)

        if (showSOCOverlay) {
            val sb = StringBuilder()
            val appendWithPipe: (String) -> Unit = { text ->
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(" | ")
                    sb.append(text)
                }
            }
            socUpdater = {
                if (emulationViewModel.emulationStarted.value &&
                    !emulationViewModel.isEmulationStopping.value
                ) {
                    sb.setLength(0)

                    if (BooleanSetting.SHOW_BUILD_ID.getBoolean(
                            NativeConfig.isPerGameConfigLoaded()
                        )
                    ) {
                        appendWithPipe(buildId)
                    }

                    if (BooleanSetting.SHOW_DRIVER_VERSION.getBoolean(
                            NativeConfig.isPerGameConfigLoaded()
                        )
                    ) {
                        appendWithPipe(driverInUse)
                    }

                    if (BooleanSetting.SHOW_DEVICE_MODEL.getBoolean(
                            NativeConfig.isPerGameConfigLoaded()
                        )
                    ) {
                        appendWithPipe(Build.MODEL)
                    }

                    if (BooleanSetting.SHOW_GPU_MODEL.getBoolean(
                            NativeConfig.isPerGameConfigLoaded()
                        )
                    ) {
                        appendWithPipe(gpuModel)
                    }

                    if (Build.VERSION.SDK_INT >= 31) {
                        if (BooleanSetting.SHOW_SOC_MODEL.getBoolean(
                                NativeConfig.isPerGameConfigLoaded()
                            )
                        ) {
                            appendWithPipe(Build.SOC_MODEL)
                        }
                    }

                    if (BooleanSetting.SHOW_FW_VERSION.getBoolean(
                            NativeConfig.isPerGameConfigLoaded()
                        )
                    ) {
                        appendWithPipe(fwVersion)
                    }

                    binding.showSocOverlayText.text = sb.toString()

                    if (BooleanSetting.SOC_OVERLAY_BACKGROUND.getBoolean(
                            NativeConfig.isPerGameConfigLoaded()
                        )
                    ) {
                        binding.showSocOverlayText.setBackgroundResource(
                            R.color.yuzu_transparent_black
                        )
                    } else {
                        binding.showSocOverlayText.setBackgroundResource(0)
                    }
                }

                socUpdateHandler.postDelayed(socRunnable!!, 1000)
            }
            socRunnable = Runnable { socUpdater?.invoke() }
            socUpdateHandler.post(socRunnable!!)
        } else {
            socRunnable?.let { socUpdateHandler.removeCallbacks(it) }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun updateOrientation() {
        emulationActivity?.let {
            val orientationSetting =
                EmulationOrientation.from(IntSetting.RENDERER_SCREEN_LAYOUT.getInt())
            it.requestedOrientation = when (orientationSetting) {
                EmulationOrientation.Unspecified -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                EmulationOrientation.SensorLandscape ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                EmulationOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                EmulationOrientation.ReverseLandscape ->
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE

                EmulationOrientation.SensorPortrait ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

                EmulationOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                EmulationOrientation.ReversePortrait ->
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            }
        }
    }

    private fun updateScreenLayout() {
        val b = _binding ?: return
        val verticalAlignment =
            EmulationVerticalAlignment.from(IntSetting.VERTICAL_ALIGNMENT.getInt())
        val aspectRatio = when (IntSetting.RENDERER_ASPECT_RATIO.getInt()) {
            0 -> Rational(16, 9)
            1 -> Rational(4, 3)
            2 -> Rational(21, 9)
            3 -> Rational(16, 10)
            else -> null // Best fit
        }
        when (verticalAlignment) {
            EmulationVerticalAlignment.Top -> {
                b.surfaceEmulation.setAspectRatio(aspectRatio)
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                b.surfaceEmulation.layoutParams = params
            }

            EmulationVerticalAlignment.Center -> {
                b.surfaceEmulation.setAspectRatio(null)
                b.surfaceEmulation.updateLayoutParams {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }

            EmulationVerticalAlignment.Bottom -> {
                b.surfaceEmulation.setAspectRatio(aspectRatio)
                val params =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                b.surfaceEmulation.layoutParams = params
            }
        }
        if (this::emulationState.isInitialized) {
            emulationState.updateSurface()
        }
        emulationActivity?.buildPictureInPictureParams()
        updateOrientation()
    }

    private fun updateFoldableLayout(
        emulationActivity: EmulationActivity,
        newLayoutInfo: WindowLayoutInfo
    ) {
        val isFolding =
            (newLayoutInfo.displayFeatures.find { it is FoldingFeature } as? FoldingFeature)?.let {
                if (it.isSeparating) {
                    emulationActivity.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    if (it.orientation == FoldingFeature.Orientation.HORIZONTAL) {
                        // Restrict emulation and overlays to the top of the screen
                        binding.emulationContainer.layoutParams.height = it.bounds.top
                        // Restrict input and menu drawer to the bottom of the screen
                        binding.inputContainer.layoutParams.height = it.bounds.bottom
                        binding.inGameMenu.layoutParams.height = it.bounds.bottom

                        isInFoldableLayout = true
                        binding.surfaceInputOverlay.layout = OverlayLayout.Foldable
                    }
                }
                it.isSeparating
            } ?: false
        if (!isFolding) {
            binding.emulationContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.inputContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.inGameMenu.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            isInFoldableLayout = false
            updateOrientation()
            onConfigurationChanged(resources.configuration)
        }
        binding.emulationContainer.requestLayout()
        binding.inputContainer.requestLayout()
        binding.inGameMenu.requestLayout()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // We purposely don't do anything here.
        // All work is done in surfaceChanged, which we are guaranteed to get even for surface creation.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.debug("[EmulationFragment] Surface changed. Resolution: " + width + "x" + height)
        if (!emulationStarted) {
            emulationStarted = true

            // For intent launches, wait for driver initialization to complete
            if (isCustomSettingsIntent || intentGame != null) {
                if (!driverViewModel.isInteractionAllowed.value) {
                    Log.info("[EmulationFragment] Intent launch: waiting for driver initialization")
                    // Driver is still initializing, wait for it
                    lifecycleScope.launch {
                        driverViewModel.isInteractionAllowed.collect { allowed ->
                            if (allowed && holder.surface.isValid) {
                                emulationState.newSurface(holder.surface)
                            }
                        }
                    }
                    return
                }
            }

            emulationState.newSurface(holder.surface)
        } else {
            // Surface changed due to rotation/config change
            // Only update surface reference, don't trigger state changes
            emulationState.updateSurfaceReference(holder.surface)
        }
        updatePausedFrameVisibility()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runCatching {
            if (this::emulationState.isInitialized && !hasNewerEmulationFragment()) {
                emulationState.clearSurface()
            }
        }
        emulationStarted = false
    }

    private fun hasNewerEmulationFragment(): Boolean {
        val activity = emulationActivity ?: return false
        return try {
            val navHostFragment =
                activity.supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
                    ?: return false
            val currentFragment = navHostFragment.childFragmentManager.fragments
                .filterIsInstance<EmulationFragment>()
                .firstOrNull()
            currentFragment != null && currentFragment !== this
        } catch (_: Exception) {
            false
        }
    }

    // xbzk: called from EmulationActivity when a new game is loaded while this fragment is still active,
    // to wait for the emulation thread to stop before allowing the ROM swap to proceed
    fun notifyWhenEmulationThreadStops(onStopped: () -> Unit) {
        if (!this::emulationState.isInitialized) {
            onStopped()
            return
        }
        val emuThread = runCatching { emulationState.emulationThread }.getOrNull()
        if (emuThread == null || !emuThread.isAlive) {
            onStopped()
            return
        }
        Thread({
            runCatching { emuThread.join() }
            Handler(Looper.getMainLooper()).post {
                onStopped()
            }
        }, "RomSwapWait").start()
    }

    // xbzk: called from EmulationActivity when a new game is loaded while this
    // fragment is still active, to stop the current emulation before swapping the ROM
    fun stopForRomSwap() {
        if (isStoppingForRomSwap) {
            return
        }
        isStoppingForRomSwap = true
        clearPausedFrame()
        emulationViewModel.setIsEmulationStopping(true)
        _binding?.let {
            binding.loadingText.setText(R.string.shutting_down)
            ViewUtils.showView(binding.loadingIndicator)
            ViewUtils.hideView(binding.inputContainer)
            ViewUtils.hideView(binding.showStatsOverlayText)
        }
        if (this::emulationState.isInitialized) {
            emulationState.stop()
            if (NativeLibrary.isRunning() || NativeLibrary.isPaused()) {
                Log.warning("[EmulationFragment] ROM swap stop fallback: forcing native stop request.")
                NativeLibrary.stopEmulation()
            }
        } else {
            NativeLibrary.stopEmulation()
        }
        // Config reload waits until the activity is gone. Doing it here
        // races the Vulkan teardown on Mali.
    }

    private fun showOverlayOptions() {
        val anchor = binding.inGameMenu.findViewById<View>(R.id.menu_overlay_controls)
        val popup = PopupMenu(requireContext(), anchor)

        popup.menuInflater.inflate(R.menu.menu_overlay_options, popup.menu)

        popup.menu.apply {
            findItem(R.id.menu_show_stats_overlay).isChecked =
                BooleanSetting.SHOW_PERFORMANCE_OVERLAY.getBoolean()
            findItem(R.id.menu_show_soc_overlay).isChecked =
                BooleanSetting.SHOW_SOC_OVERLAY.getBoolean()
            findItem(R.id.menu_rel_stick_center).isChecked =
                BooleanSetting.JOYSTICK_REL_CENTER.getBoolean()
            findItem(R.id.menu_dpad_slide).isChecked = BooleanSetting.DPAD_SLIDE.getBoolean()
            findItem(R.id.menu_show_overlay).isChecked =
                BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()
            findItem(R.id.menu_snap_to_grid).isChecked =
                BooleanSetting.OVERLAY_SNAP_TO_GRID.getBoolean()
            findItem(R.id.menu_haptics).isChecked = BooleanSetting.HAPTIC_FEEDBACK.getBoolean()
            findItem(R.id.menu_touchscreen).isChecked = BooleanSetting.TOUCHSCREEN.getBoolean()
        }

        popup.setOnDismissListener { NativeConfig.saveGlobalConfig() }
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_show_stats_overlay -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.SHOW_PERFORMANCE_OVERLAY.setBoolean(it.isChecked)
                    updateShowStatsOverlay()
                    true
                }

                R.id.menu_show_soc_overlay -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.SHOW_SOC_OVERLAY.setBoolean(it.isChecked)
                    updateSocOverlay()
                    true
                }

                R.id.menu_edit_overlay -> {
                    binding.drawerLayout.close()
                    binding.surfaceInputOverlay.requestFocus()
                    startConfiguringControls()
                    true
                }

                R.id.menu_snap_to_grid -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.OVERLAY_SNAP_TO_GRID.setBoolean(it.isChecked)
                    binding.surfaceInputOverlay.invalidate()
                    true
                }

                R.id.menu_adjust_overlay -> {
                    adjustOverlay()
                    true
                }

                R.id.menu_toggle_controls -> {
                    val overlayControlData = NativeConfig.getOverlayControlData()
                    val optionsArray = BooleanArray(overlayControlData.size)
                    overlayControlData.forEachIndexed { i, _ ->
                        optionsArray[i] = overlayControlData.firstOrNull { data ->
                            OverlayControl.entries[i].id == data.id
                        }?.enabled == true
                    }

                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.emulation_toggle_controls)
                        .setMultiChoiceItems(
                            R.array.gamepadButtons,
                            optionsArray
                        ) { _, indexSelected, isChecked ->
                            overlayControlData.firstOrNull { data ->
                                OverlayControl.entries[indexSelected].id == data.id
                            }?.enabled = isChecked
                        }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            NativeConfig.setOverlayControlData(overlayControlData)
                            NativeConfig.saveGlobalConfig()
                            binding.surfaceInputOverlay.refreshControls()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.emulation_toggle_all) { _, _ -> }
                        .show()

                    // Override normal behaviour so the dialog doesn't close
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener {
                            val isChecked = !optionsArray[0]
                            overlayControlData.forEachIndexed { i, _ ->
                                optionsArray[i] = isChecked
                                dialog.listView.setItemChecked(i, isChecked)
                                overlayControlData[i].enabled = isChecked
                            }
                        }
                    true
                }

                R.id.menu_show_overlay -> {
                    it.isChecked = !it.isChecked
                    toggleOverlay(it.isChecked)
                    true
                }

                R.id.menu_rel_stick_center -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.JOYSTICK_REL_CENTER.setBoolean(it.isChecked)
                    true
                }

                R.id.menu_dpad_slide -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.DPAD_SLIDE.setBoolean(it.isChecked)
                    true
                }

                R.id.menu_haptics -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.HAPTIC_FEEDBACK.setBoolean(it.isChecked)
                    true
                }

                R.id.menu_touchscreen -> {
                    it.isChecked = !it.isChecked
                    BooleanSetting.TOUCHSCREEN.setBoolean(it.isChecked)
                    true
                }

                R.id.menu_reset_overlay -> {
                    binding.drawerLayout.close()
                    resetInputOverlay()
                    true
                }

                else -> true
            }
        }

        popup.show()
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun startConfiguringControls() {
        // Lock the current orientation to prevent editing inconsistencies
        if (IntSetting.RENDERER_SCREEN_LAYOUT.getInt() == EmulationOrientation.Unspecified.int) {
            emulationActivity?.let {
                it.requestedOrientation =
                    if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                    }
            }
        }
        binding.doneControlConfig.setVisible(true)
        binding.surfaceInputOverlay.setIsInEditMode(true)
    }

    private fun stopConfiguringControls() {
        binding.doneControlConfig.setVisible(false)
        binding.surfaceInputOverlay.setIsInEditMode(false)
        // Unlock the orientation if it was locked for editing
        if (IntSetting.RENDERER_SCREEN_LAYOUT.getInt() == EmulationOrientation.Unspecified.int) {
            emulationActivity?.let {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        NativeConfig.saveGlobalConfig()
    }

    @SuppressLint("SetTextI18n")
    private fun adjustOverlay() {
        val adjustBinding = DialogOverlayAdjustBinding.inflate(layoutInflater)
        adjustBinding.apply {
            inputScaleSlider.apply {
                valueTo = 150F
                value = IntSetting.OVERLAY_SCALE.getInt().toFloat()
                addOnChangeListener { _, value, _ ->
                    inputScaleValue.text = "${value.toInt()}%"
                    setControlScale(value.toInt())
                }
            }
            inputOpacitySlider.apply {
                valueTo = 100F
                value = IntSetting.OVERLAY_OPACITY.getInt().toFloat()
                addOnChangeListener { _, value, _ ->
                    inputOpacityValue.text = "${value.toInt()}%"
                    setControlOpacity(value.toInt())
                }
            }
            inputScaleValue.text = "${inputScaleSlider.value.toInt()}%"
            inputOpacityValue.text = "${inputOpacitySlider.value.toInt()}%"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_control_adjust)
            .setView(adjustBinding.root)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                NativeConfig.saveGlobalConfig()
            }
            .setNeutralButton(R.string.slider_default) { _: DialogInterface?, _: Int ->
                setControlScale(50)
                setControlOpacity(100)
                binding.surfaceInputOverlay.resetIndividualControlScale()
            }
            .show()
    }

    private fun setControlScale(scale: Int) {
        IntSetting.OVERLAY_SCALE.setInt(scale)
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun setControlOpacity(opacity: Int) {
        IntSetting.OVERLAY_OPACITY.setInt(opacity)
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.inGameMenu
        ) { v: View, windowInsets: WindowInsetsCompat ->
            val cutInsets: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            var left = 0
            var right = 0
            if (v.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
                left = cutInsets.left
            } else {
                right = cutInsets.right
            }

            v.setPadding(left, cutInsets.top, right, 0)

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.quickSettingsSheet) { v, insets ->
            val systemBarsInsets: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
           if (v.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
                v.setPadding(
                    systemBarsInsets.left,
                    systemBarsInsets.top,
                    0,
                    systemBarsInsets.bottom
                )
            } else {
                v.setPadding(
                    0,
                    systemBarsInsets.top,
                    systemBarsInsets.right,
                    systemBarsInsets.bottom
                )
            }
            insets
        }
    }

    private class EmulationState(
        private val gamePath: String,
        private val emulationCanStart: () -> Boolean
    ) {
        private var state: State
        private var surface: Surface? = null
        lateinit var emulationThread: Thread

        init {
            state = State.STOPPED
        }

        @get:Synchronized
        val isStopped: Boolean
            get() = state == State.STOPPED

        @get:Synchronized
        val isPaused: Boolean
            get() = state == State.PAUSED

        @get:Synchronized
        val isRunning: Boolean
            get() = state == State.RUNNING

        @Synchronized
        fun stop() {
            // Метка до и после остановки: сбой между ними — это разрушение
            // эмуляции, сбой после — уже возврат в библиотеку. Без двух
            // меток оба случая выглядят одинаково.
            ExitTrace.mark("остановка эмуляции")
            runCatching {
                if (state == State.RUNNING) {
                    NativeLibrary.pauseEmulation()
                }
                NativeLibrary.stopEmulation()
            }
            state = State.STOPPED
            ExitTrace.mark("эмуляция остановлена")
        }

        @Synchronized
        fun pause() {
            if (state != State.PAUSED) {
                Log.debug("[EmulationFragment] Pausing emulation.")

                NativeLibrary.pauseEmulation()
                NativeLibrary.playTimeManagerStop()

                state = State.PAUSED
            } else {
                Log.warning("[EmulationFragment] Pause called while already paused.")
            }
        }

        @Synchronized
        fun run(isActivityRecreated: Boolean, programIndex: Int = 0) {
            if (isActivityRecreated) {
                if (NativeLibrary.isRunning()) {
                    state = State.PAUSED
                }
            } else {
                Log.debug("[EmulationFragment] activity resumed or fresh start")
            }

            // If the surface is set, run now. Otherwise, wait for it to get set.
            if (surface != null) {
                runWithValidSurface(programIndex)
            }
        }

        @Synchronized
        fun resume() {
            if (state != State.PAUSED) {
                Log.warning("[EmulationFragment] Resume called while emulation is not paused.")
                return
            }
            if (!emulationCanStart.invoke()) {
                Log.warning("[EmulationFragment] Resume blocked by emulationCanStart check.")
                return
            }
            val currentSurface = surface
            if (currentSurface == null || !currentSurface.isValid) {
                Log.debug("[EmulationFragment] Resume requested with invalid surface.")
                return
            }

            NativeLibrary.surfaceChanged(currentSurface)
            Log.debug("[EmulationFragment] Resuming emulation.")
            NativeLibrary.unpauseEmulation()
            NativeLibrary.playTimeManagerStart()
            state = State.RUNNING
        }

        @Synchronized
        fun changeProgram(programIndex: Int) {
            emulationThread.join()
            emulationThread = Thread({
                Log.debug("[EmulationFragment] Starting emulation thread.")
                NativeLibrary.run(gamePath, programIndex, false)
            }, "NativeEmulation")
            emulationThread.start()
        }

        // Surface callbacks
        @Synchronized
        fun newSurface(surface: Surface?) {
            this.surface = surface
            if (this.surface != null) {
                runWithValidSurface()
            }
        }

        @Synchronized
        fun updateSurface() {
            if (surface != null && state == State.RUNNING) {
                NativeLibrary.surfaceChanged(surface)
            }
        }

        @Synchronized
        fun updateSurfaceReference(surface: Surface?) {
            this.surface = surface
            if (this.surface != null && state == State.RUNNING) {
                NativeLibrary.surfaceChanged(this.surface)
            }
        }

        @Synchronized
        fun clearSurface() {
            if (surface == null) {
                Log.debug("[EmulationFragment] clearSurface called, but surface already null.")
            } else {
                if (state == State.RUNNING) {
                    pause()
                }
                NativeLibrary.surfaceDestroyed()
                surface = null
                Log.debug("[EmulationFragment] Surface destroyed.")
                when (state) {
                    State.PAUSED -> Log.debug(
                        "[EmulationFragment] Surface cleared while emulation paused."
                    )

                    else -> Log.debug(
                        "[EmulationFragment] Surface cleared while emulation stopped."
                    )
                }
            }
        }

        private fun runWithValidSurface(programIndex: Int = 0) {
            if (!emulationCanStart.invoke()) {
                return
            }
            val currentSurface = surface
            if (currentSurface == null || !currentSurface.isValid) {
                Log.debug("[EmulationFragment] runWithValidSurface called with invalid surface.")
                return
            }

            when (state) {
                State.STOPPED -> {
                    NativeLibrary.surfaceChanged(currentSurface)
                    emulationThread = Thread({
                        Log.debug("[EmulationFragment] Starting emulation thread.")
                        NativeLibrary.run(gamePath, programIndex, true)
                    }, "NativeEmulation")
                    emulationThread.start()
                    state = State.RUNNING
                }

                State.PAUSED -> {
                    Log.debug(
                        "[EmulationFragment] Surface restored while emulation paused; " +
                            "waiting for explicit resume."
                    )
                }

                else -> Log.debug("[EmulationFragment] Bug, run called while already running.")
            }
        }

        private enum class State {
            STOPPED, RUNNING, PAUSED
        }
    }

    private enum class AmiiboState(val value: Int) {
        Disabled(0),
        Initialized(1),
        WaitingForAmiibo(2),
        TagNearby(3);

        companion object {
            fun fromValue(value: Int): AmiiboState =
                entries.firstOrNull { it.value == value } ?: Disabled
        }
    }

    private enum class AmiiboLoadResult(val value: Int) {
        Success(0),
        UnableToLoad(1),
        NotAnAmiibo(2),
        WrongDeviceState(3),
        Unknown(4);

        companion object {
            fun fromValue(value: Int): AmiiboLoadResult =
                entries.firstOrNull { it.value == value } ?: Unknown
        }
    }

    companion object {
        private val AMIIBO_MIME_TYPES =
            arrayOf("application/octet-stream", "application/x-binary", "*/*")
        private val perfStatsUpdateHandler = Handler(Looper.myLooper()!!)
        private val socUpdateHandler = Handler(Looper.myLooper()!!)
    }

    private fun startOverlayAutoHideTimer(seconds: Int) {
        handler.removeCallbacksAndMessages(null)
        val showInputOverlay = BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()

        handler.postDelayed({
            if (showInputOverlay && isAdded && _binding != null) {
                if (overlayTouchActive) {
                    startOverlayAutoHideTimer(seconds)
                } else {
                    autoHideOverlay()
                }
            }
        }, seconds * 1000L)
    }

    fun handleScreenTap(isLongTap: Boolean) {
        if (!isAdded || _binding == null) return
        if (binding.surfaceInputOverlay.isGamelessMode()) return
        if (!BooleanSetting.ENABLE_INPUT_OVERLAY_AUTO_HIDE.getBoolean()) return
        // failsafe
        val autoHideSeconds = IntSetting.INPUT_OVERLAY_AUTO_HIDE.getInt()
        if (autoHideSeconds == 0) {
            toggleOverlay(true)
        } else {
            val showInputOverlay = BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()
            if (!showInputOverlay && !isLongTap && wasInputOverlayAutoHidden) {
                toggleOverlay(true)
            }
            startOverlayAutoHideTimer(autoHideSeconds)
        }
    }

    private fun initializeOverlayAutoHide() {
        if (!isAdded || _binding == null) return
        if (binding.surfaceInputOverlay.isGamelessMode()) return

        val autoHideSeconds = IntSetting.INPUT_OVERLAY_AUTO_HIDE.getInt()
        val autoHideEnabled = BooleanSetting.ENABLE_INPUT_OVERLAY_AUTO_HIDE.getBoolean()
        val showInputOverlay = BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()
        if (autoHideEnabled && showInputOverlay) {
            toggleOverlay(true)
            startOverlayAutoHideTimer(autoHideSeconds)
        }

        binding.surfaceInputOverlay.touchEventListener = { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    overlayTouchActive = true
                    handler.removeCallbacksAndMessages(null)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    overlayTouchActive = event.pointerCount > 1
                    if (!overlayTouchActive) handleScreenTap(isLongTap = false)
                }
                MotionEvent.ACTION_CANCEL -> {
                    overlayTouchActive = false
                    handleScreenTap(isLongTap = false)
                }
                MotionEvent.ACTION_MOVE -> {
                    overlayTouchActive = true
                }
            }
        }
    }

    private fun autoHideOverlay() {
        toggleOverlay(false)
        wasInputOverlayAutoHidden = true
    }

    fun toggleOverlay(enable: Boolean) {
        if (!isAdded || _binding == null) return
        if (enable == !BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()) {
            // Reset controller input flag so controller can hide overlay again
            if (!enable) {
                controllerInputReceived = false
            }
            if (enable) {
                wasInputOverlayAutoHidden = false
            }
            BooleanSetting.SHOW_INPUT_OVERLAY.setBoolean(enable)
            updateQuickOverlayMenuEntry(enable)
            binding.surfaceInputOverlay.refreshControls()
        }
    }

    fun onControllerInputDetected() {
        if (!BooleanSetting.HIDE_OVERLAY_ON_CONTROLLER_INPUT.getBoolean()) return
        if (!BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()) return
        if (controllerInputReceived) return
        controllerInputReceived = true
        autoHideOverlay()
    }

    fun onControllerConnected() {
        onPhysicalControllerStateChanged(InputHandler.androidControllers.isNotEmpty())
    }

    fun onControllerDisconnected() {
        onPhysicalControllerStateChanged(InputHandler.androidControllers.isNotEmpty())
    }

    fun onPhysicalControllerStateChanged(hasConnectedControllers: Boolean) {
        hasPhysicalControllerConnected = hasConnectedControllers
        controllerInputReceived = false
        if (!isAdded || _binding == null) return
        if (binding.surfaceInputOverlay.isGamelessMode()) return

        if (hasConnectedControllers) {
            if (BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean() &&
                BooleanSetting.HIDE_OVERLAY_ON_CONTROLLER_INPUT.getBoolean()) {
                overlayHiddenByPhysicalController = true
                toggleOverlay(false)
            }
            return
        }

        if (overlayHiddenByPhysicalController) {
            overlayHiddenByPhysicalController = false
            toggleOverlay(true)
        }
    }
}
