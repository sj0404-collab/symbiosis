// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.adapters.LauncherTileAdapter
import org.yuzu.yuzu_emu.databinding.FragmentNativeLauncherBinding
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.utils.Converter
import org.yuzu.yuzu_emu.utils.ConverterPicker
import org.yuzu.yuzu_emu.utils.CoreFallback
import org.yuzu.yuzu_emu.utils.EngineHybrid
import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.EngineStore
import org.yuzu.yuzu_emu.utils.ExitTrace
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.UiGuard
import org.yuzu.yuzu_emu.utils.GameIconUtils
import org.yuzu.yuzu_emu.utils.KenjiReport
import org.yuzu.yuzu_emu.utils.KernelLog
import org.yuzu.yuzu_emu.utils.NativeLibraryPresenter
import org.yuzu.yuzu_emu.utils.PluginEngine
import org.yuzu.yuzu_emu.utils.PluginStore
import org.yuzu.yuzu_emu.utils.Spaces
import org.yuzu.yuzu_emu.utils.SymbiosisTheme
import org.yuzu.yuzu_emu.utils.collect

/**
 * Стартовый экран: библиотека, запуск, всё остальное - одной кнопкой.
 *
 * Раньше стартовым был WebView с `library.html`. Логика разбора файлов и
 * решений «что предложить» жила в JavaScript, настройки - в Kotlin, вёрстка -
 * в HTML-строке, и на вопрос «где что находится» ответить было нельзя даже
 * по коду. Здесь экран не принимает ни одного решения: строки, пометки и
 * действие собирает [NativeLibraryPresenter], которому это можно доказать
 * тестом на хосте (`tests/NativeLibraryPresenterTest.kt`), а фрагмент только
 * подставляет результат в адаптер.
 *
 * Экран показывает **все** файлы из выбранных папок, а не только
 * запускаемые. Именно на этом ломалась старая библиотека: из сотен nsz, nsp
 * и xci она показывала одну игру, потому что остальные молча отбрасывались
 * как «невалидные». Теперь у каждого файла есть плитка, а у нерабочего -
 * причина словами и действие.
 */
class NativeLauncherFragment : Fragment() {
    private var _binding: FragmentNativeLauncherBinding? = null
    private val binding get() = _binding!!

    private val gamesViewModel: GamesViewModel by activityViewModels()

    private lateinit var adapter: LauncherTileAdapter

    /** Последний список из кэша или обхода; индексы совпадают с presenter. */
    private var games: List<Game> = emptyList()

    private var filter: String = NativeLibraryPresenter.FILTER_ALL
    private var query: String = ""

    /**
     * Что решили плагины по этому экрану. Считается один раз за отрисовку:
     * разбор файлов плагинов на каждой плитке — это чтение с диска в
     * прокрутке, то есть заметные рывки.
     */
    private var pluginDecisions = PluginEngine.Decisions()


    /**
     * Очередь распаковки. Создаётся в onCreate: регистрация результата
     * обязана произойти до STARTED, иначе менеджер активности бросит
     * исключение при первом нажатии.
     */
    private lateinit var converter: ConverterPicker

    /** Был ли экран уже показан: первый onResume не считаем возвратом. */
    private var returnedToScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)

        // Отчёт о падении ставим сразу, а не при запуске игры.
        //
        // 06.11 приложение падало при запуске, пока шёл обход библиотеки, и
        // не оставалось ничего: обработчик ставился только в эмуляции, то
        // есть уже после того, как человек дошёл до игры. Теперь любое
        // падение главного процесса пишет `symbiosis/ui-crash.txt`, а
        // нативный сбой — туда же со стеком.
        runCatching { UiGuard.install(requireContext().applicationContext) }
        runCatching { UiGuard.armNative(requireContext().applicationContext) }

        converter = ConverterPicker(
            fragment = this,
            onQueued = { count ->
                if (_binding != null) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.launcher_convert_queued, count) + "\n" +
                            getString(R.string.converter_where, Converter.whereText()),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            },
            onFailed = {
                if (_binding != null) {
                    Snackbar.make(binding.root, R.string.launcher_convert_failed, Snackbar.LENGTH_LONG)
                        .show()
                }
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNativeLauncherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Стартовый экран: стрелки «назад» нет, выходить из него - значит
        // выходить из приложения.
        binding.toolbarLauncher.title = getString(R.string.launcher_title)
        binding.toolbarLauncher.subtitle = getString(R.string.launcher_subtitle)

        adapter = LauncherTileAdapter(::onItemClick, ::onItemLongClick, ::loadCover)
        binding.listLauncher.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@NativeLauncherFragment.adapter
            setHasFixedSize(false)
        }
        applyOrientationLayout()

        applyTheme()
        buildFilterChips()
        bindActions()

        binding.editLauncherSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
            }
        })

        binding.swipeLauncher.setOnRefreshListener {
            gamesViewModel.reloadGames(false)
        }

        gamesViewModel.isReloading.collect(viewLifecycleOwner) {
            binding.swipeLauncher.isRefreshing = it
        }
        // Удалённые файлы не должны висеть в библиотеке до перезапуска.
        //
        // Жалоба 06.11: часть файлов уже удалена, а в библиотеке они видны —
        // и появляются как раз после того, как зайдёшь в «Папки с играми»
        // (экран папок обновляет список, а плитки остаются от прежнего
        // построения).
        //
        // Возврат на экран помечается флагом, а обход делается в onResume:
        // запускать его из repeatOnLifecycle оказалось плохой идеей — блок
        // перезапускается на каждом входе в RESUMED, и при старте это давало
        // второй обход поверх первого, пока первый ещё шёл.
        gamesViewModel.games.collect(viewLifecycleOwner) {
            games = it
            render()
        }

        setInsets()

        // Прошлый обход не дошёл до конца — говорим об этом прямо: падение в
        // сканировании и падение на отрисовке чинятся по-разному.
        if (GameHelper.lastScanUnfinished(requireContext())) {
            Snackbar.make(
                binding.root,
                "прошлый обход библиотеки не закончился: приложение закрылось во время сканирования",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Цвет экрана.
     *
     * Шесть верхних кнопок делают разные вещи, и раньше все шесть выглядели
     * одинаково: чтобы найти «Конвертер», приходилось читать каждую. Теперь
     * у каждой свой цвет, и он же повторяется на её экране — цвет становится
     * подписью, а не украшением.
     *
     * Тона приглушённые (16 % акцента на тёмном), текст — сам акцент: так
     * кнопка остаётся читаемой на солнце и не превращает строку в радугу.
     */
    private fun applyTheme() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        fun tint(button: com.google.android.material.button.MaterialButton, accent: Int) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                SymbiosisTheme.soft(accent, 0.16f)
            )
            button.setTextColor(accent)
            button.strokeWidth = 0
            button.cornerRadius = dp(14)
        }

        tint(binding.buttonLauncherRescan, SymbiosisTheme.CYAN)
        tint(binding.buttonLauncherFolders, SymbiosisTheme.LIME)
        tint(binding.buttonLauncherConvert, SymbiosisTheme.AMBER)
        tint(binding.buttonLauncherKeys, SymbiosisTheme.VIOLET)
        tint(binding.buttonLauncherTools, SymbiosisTheme.PINK)
        tint(binding.buttonLauncherHelp, SymbiosisTheme.BLUE)

        binding.toolbarLauncher.title = getString(R.string.launcher_title)

        binding.layoutLauncherSearch.setBoxStrokeColor(SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.55f))
        binding.layoutLauncherSearch.setHintTextColor(
            android.content.res.ColorStateList.valueOf(SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.85f))
        )
        // Одно поле boxCornerRadius у TextInputLayout нет — есть радиусы по
        // углам. Все четыре одинаковые: скругление у карточки и у поиска
        // должно совпадать, иначе экран выглядит собранным из разных наборов.
        binding.layoutLauncherSearch.setBoxCornerRadii(
            dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat()
        )
        binding.layoutLauncherSearch.setBoxBackgroundColor(SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.05f))

        binding.textLauncherSummary.setTextColor(SymbiosisTheme.CYAN)
        binding.textLauncherEmpty.setTextColor(SymbiosisTheme.TEXT_DIM)
    }

    /**
     * Кнопки сверху.
     *
     * Специально не в меню «три точки»: жалоба была именно на то, что
     * нужное спрятано. Шесть кнопок в прокручиваемой строке видно сразу,
     * и они же названы в руководстве.
     */
    private fun bindActions() {
        binding.buttonLauncherRescan.setOnClickListener {
            gamesViewModel.reloadGames(false)
            Snackbar.make(binding.root, R.string.launcher_rescanning, Snackbar.LENGTH_SHORT).show()
        }
        binding.buttonLauncherFolders.setOnClickListener {
            navigate(R.id.action_global_symbiosisGameFoldersFragment)
        }
        binding.buttonLauncherConvert.setOnClickListener {
            converter.pick()
        }
        // В NSZ Converter рядом с «Выбрать файл» есть «Выбрать папку» —
        // человек не должен тыкать в каждый сжатый образ по одному.
        binding.buttonLauncherConvertFolder.setOnClickListener {
            converter.pickFolder()
        }
        binding.buttonLauncherKeys.setOnClickListener {
            navigate(R.id.action_global_keysFirmwareFragment)
        }
        binding.buttonLauncherTools.setOnClickListener {
            navigate(R.id.action_global_toolsFragment)
        }
        binding.buttonLauncherHelp.setOnClickListener {
            navigate(R.id.action_global_manualFragment)
        }
    }

    private fun buildFilterChips() {
        val chips = listOf(
            NativeLibraryPresenter.FILTER_ALL to R.string.launcher_filter_all,
            NativeLibraryPresenter.FILTER_RUNNABLE to R.string.launcher_filter_runnable,
            NativeLibraryPresenter.FILTER_DEAD to R.string.launcher_filter_dead
        )
        binding.chipsLauncherFilters.removeAllViews()
        chips.forEachIndexed { index, (id, label) ->
            val chip = Chip(requireContext()).apply {
                text = getString(label)
                isCheckable = true
                isChecked = index == 0
                // Выбранный фильтр — цветом библиотеки: строка «какой фильтр
                // включён» должна читаться одним взглядом, без прочтения.
                chipBackgroundColor = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.22f),
                        SymbiosisTheme.CARD
                    )
                )
                setTextColor(
                    android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(SymbiosisTheme.CYAN, SymbiosisTheme.TEXT_DIM)
                    )
                )
                chipStrokeWidth = 0f
                setOnClickListener {
                    filter = id
                    render()
                }
            }
            binding.chipsLauncherFilters.addView(chip)
        }
    }

    /**
     * Игры по пути — чтобы плитка могла загрузить обложку.
     *
     * Плитка получает строку списка, а обложку ядро отдаёт по пути к файлу.
     * Хранить здесь всю модель незачем: нужен только путь и название.
     */
    private val gamesByPath = HashMap<String, Game>()

    /**
     * Обложка плитки.
     *
     * Раньше нативная библиотека показывала только букву: адаптер вообще не
     * запрашивал картинку, хотя загрузчик в приложении был (им пользуется
     * штатный экран игр и ярлыки на рабочем столе). Отсюда и «в WebView
     * обложки есть, а тут квадратики».
     *
     * Обложка берётся из того же кэша Coil, что и в штатном списке, поэтому
     * второй раз файл не расшифровывается.
     */
    private fun loadCover(
        item: NativeLibraryPresenter.Item,
        image: android.widget.ImageView,
        monogram: android.widget.TextView
    ) {
        if (item.path.isBlank()) return
        val game = gamesByPath[item.path]
        if (game == null) {
            // Игры нет в карте (перерисовка во время заполнения) — показываем
            // букву и ничего не грузим. Метку НЕ трогаем: она уже стоит с
            // привязки ячейки, и запоздавший ответ прошлой игры отсеется.
            image.isVisible = false
            monogram.isVisible = true
            return
        }
        // Метка выставляется при привязке ячейки в адаптере; здесь только
        // читаем её в колбэке, чтобы отсеять ответ прошлой игры.
        GameIconUtils.loadGameIcon(game, image) { ok ->
            // Пришло не в тот момент — ячейку уже заняли другой игрой.
            val current = image.tag as? String
            if (current != null && current != item.path) return@loadGameIcon
            image.isVisible = ok
            monogram.isVisible = !ok
        }
    }

    /** Перерисовать список по текущему состоянию: игры, причины, фильтр, поиск. */
    private fun render() {
        if (_binding == null) return

        val ctx = requireContext().applicationContext
        // Карта для обложек: заполняется каждым render(), чтобы плитки не
        // держали ссылку на давно устаревший список игр.
        gamesByPath.clear()
        games.forEach { gamesByPath[it.path] = it }
        val rows = games.map { game ->
            val core = runCatching { EngineStore.coreFor(ctx, game.path) }
                .getOrDefault(EngineHybrid.Core.EDEN)
            NativeLibraryPresenter.Row(
                title = game.title,
                path = game.path,
                titleId = runCatching { game.programIdHex }.getOrDefault(""),
                developer = game.developer,
                fileName = fileNameOf(game.path),
                core = if (core == EngineHybrid.Core.KENJI) core.short else ""
            )
        }

        val side = runCatching { GameHelper.sidecar }.getOrDefault(emptyList())
            // Удалённые файлы не показываем вовсе. Список приходит из обхода
            // и живёт в памяти: файл могли удалить с диска, а запись о нём
            // осталась — так и появлялись плитки того, чего уже нет.
            .filter { s ->
                runCatching {
                    androidx.documentfile.provider.DocumentFile
                        .fromSingleUri(requireContext(), Uri.parse(s.path))?.exists() ?: true
                }.getOrDefault(true)
            }
            .map { s ->
                NativeLibraryPresenter.Side(
                    name = s.name,
                    path = s.path,
                    title = s.title,
                    reason = s.reason,
                    kind = s.kind,
                    note = s.note,
                    role = s.role
                )
            }

        val stats = runCatching { GameHelper.scanStats }.getOrNull()
        val model = NativeLibraryPresenter.build(
            games = rows,
            side = side,
            updates = stats?.updates ?: 0,
            dlc = stats?.dlc ?: 0,
            search = query,
            filter = filter
        )

        // Плагины: пометки на плитках и сообщения над списком. Решения
        // считаются по экрану целиком; конкретная игра уточняется при запуске.
        pluginDecisions = runCatching {
            PluginStore.decisions(ctx, PluginStore.contextFor(ctx))
        }.getOrDefault(PluginEngine.Decisions())

        adapter.submit(PluginEngine.decorate(model.items, pluginDecisions))

        // Сообщения плагинов — отдельной строкой, а не в дневной сводке:
        // сводка отвечает «сколько игр», а плашка — «что с ними не так».
        val banners = pluginDecisions.banners
        // В альбоме плашку не показываем вовсе (см. applyOrientationLayout):
        // иначе render() вернёт её обратно и снова съест строку списка.
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        binding.textLauncherPlugin.isVisible = banners.isNotEmpty() && !landscape
        if (banners.isNotEmpty()) {
            binding.textLauncherPlugin.text = banners.joinToString("\n") { it.text }
            binding.textLauncherPlugin.setTextColor(
                when (banners.maxByOrNull { it.severity.ordinal }?.severity) {
                    org.yuzu.yuzu_emu.utils.PluginApi.Severity.ERROR -> SymbiosisTheme.RED
                    org.yuzu.yuzu_emu.utils.PluginApi.Severity.WARN -> SymbiosisTheme.AMBER
                    else -> SymbiosisTheme.CYAN
                }
            )
        }

        // Сводка и пустота говорят разное и не одновременно: когда строк
        // нет, вместо «0 игр · 0 сжатых» показывается причина.
        binding.textLauncherSummary.isVisible = model.items.isNotEmpty()
        binding.textLauncherSummary.text = model.summary

        val empty = model.items.isEmpty()
        binding.textLauncherEmpty.isVisible = empty
        if (empty) binding.textLauncherEmpty.text = model.emptyReason
    }

    private fun onItemClick(item: NativeLibraryPresenter.Item) {
        when (item.action) {
            NativeLibraryPresenter.Action.LAUNCH -> {
                val game = games.getOrNull(item.gameIndex) ?: return
                val ctx = requireContext().applicationContext
                val core = runCatching { EngineStore.coreFor(ctx, game.path) }
                    .getOrDefault(EngineHybrid.Core.EDEN)
                launchOn(game, core)
            }
            NativeLibraryPresenter.Action.CONVERT -> askConvert(item)
            NativeLibraryPresenter.Action.KEYS -> explainUnreadable(item)
            NativeLibraryPresenter.Action.EXPLAIN_SPLIT -> explainSplit(item)
        }
    }

    /**
     * Долгое нажатие: каким ядром играть.
     *
     * Выбор запоминается за игрой, а не за приложением: одна библиотека
     * ходит на двух ядрах, и переключение одной игры не трогает остальные.
     */
    private fun onItemLongClick(item: NativeLibraryPresenter.Item) {
        val game = games.getOrNull(item.gameIndex) ?: return
        val ctx = requireContext().applicationContext
        val current = runCatching { EngineStore.coreFor(ctx, game.path) }
            .getOrDefault(EngineHybrid.Core.EDEN)
        val options = arrayOf(
            getString(R.string.launcher_core_eden),
            getString(R.string.launcher_core_kenji),
            getString(R.string.launcher_saves_menu),
            getString(R.string.launcher_properties)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(game.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runOnCore(game, EngineHybrid.Core.EDEN)
                    1 -> runOnCore(game, EngineHybrid.Core.KENJI)
                    2 -> openSaves(game)
                    else -> openProperties(game)
                }
            }
            .setMessage(
                run {
                    val now = getString(
                        R.string.launcher_core_now,
                        if (current == EngineHybrid.Core.KENJI) "Kenji-NX" else "Eden"
                    )
                    // Если за игрой есть записка, человек должен видеть это
                    // здесь: иначе непонятно, почему она идёт не тем ядром,
                    // которое он выбрал в прошлый раз.
                    val why = runCatching { CoreFallback.noteFor(ctx, game.path) }
                        .getOrDefault("")
                    if (why.isEmpty()) now
                    else "$now\n\nПрошлый запуск эта игра не пережила на втором ядре: " +
                        why.take(160) + "\n\nВыбор Kenji сотрёт эту пометку."
                }
            )
            .show()
    }

    /**
     * Сейвы и прогресс игры.
     *
     * Аргументами уходят только путь и название: экран сам читает сейвы с
     * диска и не зависит от того, насколько свеж список в памяти.
     */
    private fun openSaves(game: Game) {
        runCatching {
            findNavController().navigate(
                R.id.action_global_savesFragment,
                android.os.Bundle().apply {
                    putString("path", game.path)
                    putString("title", game.title)
                }
            )
        }.onFailure {
            Snackbar.make(binding.root, R.string.launcher_nav_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * Запомнить ядро за игрой и запустить её этим ядром.
     *
     * Явный выбор человека сильнее автоматики: если за игрой была записка
     * «ядро её роняло», она стирается. Иначе вышло бы, что приложение
     * спорит с владельцем телефона — он просит Kenji, а оно втихую
     * запускает Eden.
     */
    private fun runOnCore(game: Game, core: EngineHybrid.Core) {
        val ctx = requireContext().applicationContext
        val wasMarked = runCatching { CoreFallback.isMarked(ctx, game.path) }.getOrDefault(false)
        if (wasMarked && core == EngineHybrid.Core.KENJI) {
            runCatching { CoreFallback.clearFor(ctx, game.path) }
        }
        runCatching { EngineStore.setCoreFor(ctx, game.path, core) }
        render()
        if (wasMarked && core == EngineHybrid.Core.KENJI) {
            // Говорим прямо, что это его решение: иначе следующий отчёт о
            // падении будет выглядеть как «приложение опять не послушало».
            Snackbar.make(
                binding.root,
                "Запускаю на Kenji-NX по вашему выбору. Прошлый запуск эта игра " +
                    "уронила — если упадёт снова, вернутся записка и перевод на Eden.",
                Snackbar.LENGTH_LONG
            ).show()
        }
        launchOn(game, core)
    }

    private fun openProperties(game: Game) {
        runCatching {
            findNavController().navigate(
                HomeNavigationDirections.actionGlobalPerGamePropertiesFragment(game)
            )
        }.onFailure {
            Snackbar.make(binding.root, R.string.launcher_properties_failed, Snackbar.LENGTH_LONG)
                .show()
        }
    }

    /**
     * Запуск выбранным ядром.
     *
     * Если второе ядро не скачано или не прошло проверку, игра всё равно
     * запускается — на Eden, с честным сообщением. Молча ничего не
     * открывать хуже, чем открыть и сказать, почему не тем ядром.
     */
    private fun launchOn(game: Game, requested: EngineHybrid.Core) {
        val appCtx = requireContext().applicationContext
        // След для диагностики: если после этой метки приложение умрёт, в
        // «Диагностике» будет видно, на каком именно запуске это случилось.
        ExitTrace.mark("запуск: ${game.title}")

        // Журнал прошлого сеанса сохраняется ДО старта: ядро при
        // инициализации переименовывает свой файл и стирает позапрошлый
        // сеанс. Восстановить его после запуска уже нельзя — именно поэтому
        // «вчера работало» нечем было подтвердить.
        runCatching { KernelLog.archiveCurrent() }

        // Плагин может переопределить ядро — например, оставить игру на Eden,
        // если она на втором ядре падает. Это решение не молчаливое: если
        // ядро поменялось, человек увидит строку с причиной.
        val gameCtx = runCatching {
            PluginStore.contextFor(appCtx, title = game.title, path = game.path, core = requested.id)
        }.getOrNull()
        val decisions = if (gameCtx == null) PluginEngine.Decisions()
        else runCatching { PluginStore.decisions(appCtx, gameCtx) }
            .getOrDefault(PluginEngine.Decisions())

        val core = decisions.core ?: requested
        if (core != requested) {
            val reason = decisions.coreReason.ifEmpty { "так решил плагин" }
            Snackbar.make(
                binding.root,
                "Эта игра пойдёт на ${core.short}: $reason",
                Snackbar.LENGTH_LONG
            ).show()
        }

        // Записка «ядро эту игру уже роняло» сильнее сохранённой настройки.
        //
        // 05.11 вышло так: игру перевели на Eden, человек запустил её снова на
        // Kenji (проверить), выбор перезаписался на Kenji — и она снова упала.
        // Теперь решение принимается при каждом запуске по записке, а не по
        // одной записи, которую что угодно может перезаписать.
        if (core == EngineHybrid.Core.KENJI) {
            val marked = runCatching { CoreFallback.isMarked(appCtx, game.path) }
                .getOrDefault(false)
            if (marked) {
                val why = runCatching { CoreFallback.noteFor(appCtx, game.path) }
                    .getOrDefault("")
                runCatching { EngineStore.setCoreFor(appCtx, game.path, EngineHybrid.Core.EDEN) }
                Snackbar.make(
                    binding.root,
                    "Прошлый запуск этой игры второе ядро не пережило" +
                        (if (why.isNotEmpty()) " (${why.take(90)})" else "") +
                        " — запускаю на Eden. Вернуть Kenji: долгое нажатие → «Запустить на Kenji-NX».",
                    Snackbar.LENGTH_LONG
                ).show()
                navigateToGame(game)
                return
            }
        }

        if (core == EngineHybrid.Core.EDEN) {
            navigateToGame(game)
            return
        }
        val ctx = requireContext().applicationContext
        val state = runCatching { EngineLoader.state(ctx, EngineLoader.Engine.KENJI) }.getOrNull()
        val usable = state is EngineLoader.State.Ready || state is EngineLoader.State.Builtin
        if (usable) {
            val started = runCatching {
                startActivity(
                    org.yuzu.yuzu_emu.activities.KenjiPlayerActivity.intent(
                        requireContext(),
                        game.path,
                        game.title,
                        configFor(ctx, decisions)
                    )
                )
            }.isSuccess
            if (started) return
        }
        val why = when (state) {
            is EngineLoader.State.Missing -> getString(R.string.launcher_core_kenji_missing)
            is EngineLoader.State.Broken -> getString(R.string.launcher_core_kenji_broken, state.reason)
            else -> getString(R.string.launcher_core_kenji_failed)
        }

        // Своё ядро не готово — но на телефоне может стоять официальный Kenji,
        // и он там работает. Отдать игру ему честнее, чем молча запустить на
        // Eden и сделать вид, что так и задумано: человек выбрал Kenji.
        val official = runCatching { Spaces.official(ctx) }.getOrNull()
        if (official != null && official.installed) {
            val handed = runCatching { Spaces.openOfficial(ctx, game.path) }.getOrNull()
            val opened = handed != null && handed.contains("\"ok\":true")
            if (opened) {
                Snackbar.make(
                    binding.root,
                    "Открыл игру в «${official.label}»: своё ядро не готово ($why).",
                    Snackbar.LENGTH_LONG
                ).show()
                return
            }
        }
        Snackbar.make(binding.root, why, Snackbar.LENGTH_LONG).show()
        navigateToGame(game)
    }

    /**
     * Настройки второго ядра с поправками плагинов.
     *
     * Патч накладывается на действующий конфиг (Eden + своё у Kenji), а не
     * заменяет его: плагин правит одно поле, остальные остаются как выбрал
     * человек. Если конфиг не собрался, отдаём прежнюю строку — плагин не
     * имеет права оставить игру без настроек вовсе.
     */
    private fun configFor(
        ctx: android.content.Context,
        decisions: PluginEngine.Decisions
    ): String {
        if (decisions.settings.isEmpty()) {
            return runCatching { EngineStore.effectiveText(ctx) }.getOrDefault("")
        }
        return runCatching {
            val patched = PluginEngine.patch(EngineStore.config(ctx), decisions.settings)
            EngineHybrid.encode(EngineHybrid.effective(patched))
        }.getOrElse {
            runCatching { EngineStore.effectiveText(ctx) }.getOrDefault("")
        }
    }

    /**
     * В альбомной ориентации — две колонки и никакой лишней шапки.
     *
     * ЧТО БЫЛО. 06.11 человек написал: «в альбомном режиме игры видно только
     * по одной, и их неудобно листать». Причина не в плитке, а в шапке:
     * заголовок, кнопки, поиск, фильтры, сводка и плашка в портретных отступах
     * занимали почти всю высоту (в альбоме её около 360dp), и на список
     * оставалась полоса под одну строку.
     *
     * ЧТО СДЕЛАНО. Отступы шапки и плитки вынесены в ресурсы, в `values-land`
     * они ужаты (однострочная плитка), а список получает две колонки. Одна
     * разметка на обе ориентации — не надо правки в двух копиях.
     */
    private fun applyOrientationLayout() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val columns = if (landscape) 2 else 1
        val current = binding.listLauncher.layoutManager
        val wantGrid = columns > 1
        if (wantGrid && current !is androidx.recyclerview.widget.GridLayoutManager) {
            binding.listLauncher.layoutManager =
                androidx.recyclerview.widget.GridLayoutManager(requireContext(), columns)
        } else if (!wantGrid && current !is LinearLayoutManager) {
            binding.listLauncher.layoutManager = LinearLayoutManager(requireContext())
        }
        // Плашка плагина в альбоме уводится со экрана: это подсказка, а не
        // причина задержки, и место в альбоме дороже.
        if (landscape) {
            binding.textLauncherPlugin.visibility = android.view.View.GONE
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) applyOrientationLayout()
    }

    private fun navigate(destination: Int) {
        runCatching { findNavController().navigate(destination) }
            .onFailure {
                Snackbar.make(binding.root, R.string.launcher_nav_failed, Snackbar.LENGTH_LONG)
                    .show()
            }
    }

    private fun navigateToGame(game: Game) {
        runCatching {
            findNavController().navigate(HomeNavigationDirections.actionGlobalEmulationActivity(game))
        }.onFailure {
            Snackbar.make(binding.root, R.string.launcher_launch_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * Сжатый образ: сначала объяснение, потом действие.
     *
     * Диалог, а не сразу распаковка: распаковка занимает столько же места,
     * сколько сам образ, и на телефоне с 8 ГБ это решение, которое человек
     * должен принять сам.
     */
    private fun askConvert(item: NativeLibraryPresenter.Item) {
        val (title, text, _) = NativeLibraryPresenter.explain(
            kind = "compressed",
            name = item.title,
            reason = "файл упакован"
        )
        MessageDialogFragment.newInstance(
            requireActivity(),
            titleString = title,
            descriptionString = text,
            positiveButtonTitleId = R.string.launcher_convert_start,
            positiveAction = { converter.pick() },
            showNegativeButton = true,
            negativeButtonTitleId = android.R.string.cancel
        ).show(childFragmentManager, MessageDialogFragment.TAG)
    }

    private fun explainUnreadable(item: NativeLibraryPresenter.Item) {
        val (title, text, _) = NativeLibraryPresenter.explain(
            kind = "unreadable",
            name = item.title,
            reason = item.subtitle
        )
        // Если причина — отсутствие ключа игры, то идти за ключами в общий
        // экран мало: там надо ещё понять, что вписывать. Такой диалог сразу
        // открывает ввод ключа с готовым идентификатором из контейнера.
        val needsTitleKey = item.note == "нет ключа игры" ||
            item.subtitle.contains("нужен ключ") ||
            item.subtitle.contains("title.keys")
        if (needsTitleKey) {
            // Сначала пробуем взять ключ там, где он может лежать, — в билете
            // внутри самого файла. Это ровно то, о чём человек спросил: «раз
            // есть билет, значит ключ внутри?» Да, у обычного билета — внутри.
            val uri = runCatching { Uri.parse(item.path) }.getOrNull()
            val ticketKey = if (uri != null) {
                runCatching { GameHelper.ticketKeyFor(uri) }.getOrNull().orEmpty()
            } else {
                ""
            }
            if (ticketKey.startsWith("ключ игры") && ticketKey.contains("записан в title.keys")) {
                Snackbar.make(binding.root, ticketKey, Snackbar.LENGTH_LONG).show()
                gamesViewModel.reloadGames(false)
                return
            }
            MessageDialogFragment.newInstance(
                requireActivity(),
                titleString = title,
                // Причина из билета дописывается одной строкой: если ключ не
                // достался, человек должен знать, почему именно, а не гадать.
                descriptionString = if (ticketKey.isNotEmpty() && !ticketKey.startsWith("ключ игры")) {
                    text + "\n\nИз билета: " + ticketKey
                } else {
                    text
                },
                positiveButtonTitleId = R.string.keys_add_title_key,
                positiveAction = {
                    findNavController().navigate(
                        R.id.action_global_keysFirmwareFragment,
                        KeysFirmwareFragment.argsForTitleKey(titleIdOf(item))
                    )
                },
                showNegativeButton = true,
                negativeButtonTitleId = android.R.string.cancel
            ).show(childFragmentManager, MessageDialogFragment.TAG)
            return
        }
        MessageDialogFragment.newInstance(
            requireActivity(),
            titleString = title,
            descriptionString = text,
            positiveButtonTitleId = R.string.launcher_open_keys,
            positiveAction = { navigate(R.id.action_global_keysFirmwareFragment) },
            showNegativeButton = true,
            negativeButtonTitleId = android.R.string.cancel
        ).show(childFragmentManager, MessageDialogFragment.TAG)
    }

    /** Идентификатор игры из подписи плитки: там он идёт после «·». */
    private fun titleIdOf(item: NativeLibraryPresenter.Item): String =
        Regex("[0-9a-fA-F]{16}")
            .find(item.subtitle)
            ?.value
            ?.lowercase()
            .orEmpty()

    private fun explainSplit(item: NativeLibraryPresenter.Item) {
        val (title, text, _) = NativeLibraryPresenter.explain(
            kind = "split",
            name = item.title,
            reason = item.subtitle
        )
        MessageDialogFragment.newInstance(
            requireActivity(),
            titleString = title,
            descriptionString = text
        ).show(childFragmentManager, MessageDialogFragment.TAG)
    }

    private fun fileNameOf(path: String): String {
        if (path.isBlank()) return ""
        val segment = runCatching { Uri.parse(path).lastPathSegment }.getOrNull()
        val decoded = runCatching { Uri.decode(segment ?: "") }.getOrDefault("")
        return decoded.ifBlank { path.substringAfterLast('/') }
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.listLauncher.updatePadding(
                left = bars.left + cutout.left + 12.px,
                right = bars.right + cutout.right + 12.px,
                bottom = bars.bottom
            )
            insets
        }

    private val Int.px: Int
        get() = (this * resources.displayMetrics.density).toInt()

    /**
     * Возврат из игры.
     *
     * Второе ядро играет в отдельном процессе, и его падение закрывает только
     * себя: библиотека остаётся на экране, о причине — ни слова. Показываем её
     * здесь, при возврате, самим отчётом, который оставил умерший процесс.
     */
    override fun onResume() {
        super.onResume()
        runCatching { KenjiReport.showPendingIfAny(requireContext()) }

        // Удалённые файлы не должны висеть в библиотеке до перезапуска:
        // возврат на экран — повод перечитать список. Первый заход
        // пропускаем, список уже запрошен во viewModel; и не запускаем
        // обход поверх идущего.
        if (!returnedToScreen) {
            returnedToScreen = true
        } else if (isAdded && !isDetached) {
            gamesViewModel.reloadGames(false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.listLauncher.adapter = null
        _binding = null
    }
}
