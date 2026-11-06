// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Нативный лаунчер: та же логика, что у страницы в WebView, но без WebView.
//
// Проверяется [NativeLibraryPresenter] - он и есть вся логика: порядок,
// бейджи, действия, строка сводки, склонения, тексты диалогов. Экран только
// рисует. Поэтому тест зовёт настоящий презентер, а не модель.
//
// Run: kotlinc tests/NativeLibraryPresenterTest.kt \
//        patch/android/utils/NativeLibraryPresenter.kt patch/android/utils/LibraryIndex.kt \
//        -include-runtime -d /tmp/np.jar && java -jar /tmp/np.jar

import java.io.File
import org.yuzu.yuzu_emu.utils.LibraryIndex
import org.yuzu.yuzu_emu.utils.Manual
import org.yuzu.yuzu_emu.utils.NativeLibraryPresenter
import org.yuzu.yuzu_emu.utils.NativeLibraryPresenter.Action

private var passed = 0
private var failed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("  ok    $name${if (detail.isEmpty()) "" else "   [$detail]"}")
    } else {
        failed++
        failures += name
        println("  FAIL  $name${if (detail.isEmpty()) "" else "   [$detail]"}")
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "получено $actual, ожидалось $expected")

fun main() {
    println("NativeLibraryPresenter — что показывает нативный лаунчер")

    val games = listOf(
        NativeLibraryPresenter.Row(
            title = "Sonic Mania", path = "content://a/Sonic Mania [01004E300BD5C000][v0].nsp",
            titleId = "01004E300BD5C000", developer = "Sega",
            fileName = "Sonic Mania [01004E300BD5C000][v0].nsp"
        ),
        NativeLibraryPresenter.Row(
            title = "Hollow Knight", path = "content://a/Hollow Knight [0100633007D48000][v0].nsp",
            titleId = "0100633007D48000", fileName = "Hollow Knight [0100633007D48000][v0].nsp",
            hasSave = true, saveSize = "3 МБ"
        ),
        NativeLibraryPresenter.Row(
            title = "Zelda", path = "content://a/Zelda [01007EF00011E000][v0].xci",
            titleId = "01007EF00011E000", fileName = "Zelda [01007EF00011E000][v0].xci"
        )
    )
    val side = listOf(
        NativeLibraryPresenter.Side(
            "Hollow Knight [0100633007D48000][v0].nsz", "content://a/x.nsz", "Hollow Knight",
            "сжатый образ", "compressed"
        ),
        NativeLibraryPresenter.Side(
            "Big [0100AAAAAAAAA000][v0].nsp.00", "content://a/y.00", "Big",
            "часть разрезанного образа", "split"
        ),
        NativeLibraryPresenter.Side(
            "Metroid Dread [010093801237C000][v0].xci", "content://a/z.xci", "Metroid Dread",
            "ядро не прочитало заголовок", "unreadable"
        )
    )

    val model = NativeLibraryPresenter.build(games, side, updates = 2, dlc = 1)

    // ── порядок и действия ──────────────────────────────────────────────
    eq("элементов", model.items.size, 6)
    eq("первым идёт игра", model.items[0].title, "Sonic Mania")
    eq("игра запускается", model.items[0].action, Action.LAUNCH)
    eq("сжатый идёт после игр", model.items[3].action, Action.CONVERT)
    eq("сжатый помечен", model.items[3].note, "сжатый")
    eq("часть — объяснение, а не распаковка", model.items[4].action, Action.EXPLAIN_SPLIT)
    eq("непрочитанный просит ключи", model.items[5].action, Action.KEYS)
    // Раньше здесь стояло «нет ключей» на любом нерaспознанном файле. На
    // телефоне ключи были на месте, и пометка была неправдой — её теперь
    // даёт разбор контейнера, а без разбора она пустая.
    eq("без разбора пометка не врёт про ключи", model.items[5].note, "")
    eq("причина стала подписью", model.items[5].subtitle, "ядро не прочитало заголовок")

    // ── бейджи: расширение, а не иероглиф ───────────────────────────────
    eq("бейдж nsp", model.items[0].badge, "nsp")
    eq("бейдж xci", model.items[5].badge, "xci")
    eq("бейдж nsz", model.items[3].badge, "nsz")
    eq("у .nsp.00 бейдж от имени без хвоста", model.items[4].badge, "nsp")
    eq("мусорный бейдж пустой", NativeLibraryPresenter.extensionOf("main"), "")
    eq("main без расширения не даёт бейдж", NativeLibraryPresenter.extensionOf("файл."), "")

    // ── сводка и склонения ──────────────────────────────────────────────
    eq(
        "сводка",
        model.summary,
        "3 игры · 2 обновления · 1 DLC · 1 сжатый → Конвертер · 2 не читаются"
    )
    eq("1 игра", NativeLibraryPresenter.summaryLine(1, 0, 0, 0, 0), "1 игра")
    eq("2 игры", NativeLibraryPresenter.summaryLine(2, 0, 0, 0, 0), "2 игры")
    eq("5 игр", NativeLibraryPresenter.summaryLine(5, 0, 0, 0, 0), "5 игр")
    eq("11 игр", NativeLibraryPresenter.summaryLine(11, 0, 0, 0, 0), "11 игр")
    eq("21 игра", NativeLibraryPresenter.summaryLine(21, 0, 0, 0, 0), "21 игра")
    eq(
        "сжатых не «1 сжатых»",
        NativeLibraryPresenter.summaryLine(0, 0, 0, 1, 0),
        "0 игр · 1 сжатый → Конвертер"
    )

    // ── пустой экран говорит почему ─────────────────────────────────────
    val onlyCompressed = NativeLibraryPresenter.build(
        emptyList(),
        listOf(
            NativeLibraryPresenter.Side("a.nsz", "p1", "A", "сжатый образ", "compressed"),
            NativeLibraryPresenter.Side("b.nsz", "p2", "B", "сжатый образ", "compressed"),
            NativeLibraryPresenter.Side("c.mp4", "p3", "C", "не образ", "other")
        )
    )
    check(
        "пустой экран объясняет распаковку",
        onlyCompressed.emptyReason.contains("2 файла сжаты"),
        onlyCompressed.emptyReason
    )
    check(
        "пустой экран зовёт Конвертер",
        onlyCompressed.emptyReason.contains("Конвертер")
    )
    val noFolder = NativeLibraryPresenter.build(emptyList(), emptyList())
    check("совсем пусто — про папку", noFolder.emptyReason.contains("Папка не выбрана"), noFolder.emptyReason)
    check("совсем пусто не врёт про сжатые", !noFolder.emptyReason.contains("сжаты"))

    // ── поиск и фильтры ─────────────────────────────────────────────────
    val search = NativeLibraryPresenter.build(games, side, search = "zelda")
    eq("поиск по названию", search.items.size, 1)
    eq("поиск нашёл Zelda", search.items[0].title, "Zelda")
    val searchByTid = NativeLibraryPresenter.build(games, side, search = "01004e30")
    eq("поиск по Title ID не зависит от регистра", searchByTid.items.size, 1)
    val dead = NativeLibraryPresenter.build(games, side, filter = NativeLibraryPresenter.FILTER_DEAD)
    eq("фильтр «не запускается»", dead.items.size, 3)
    check("в нём только нерабочие", dead.items.none { it.runnable })
    val runnable = NativeLibraryPresenter.build(games, side, filter = NativeLibraryPresenter.FILTER_RUNNABLE)
    eq("фильтр «запускается»", runnable.items.size, 3)
    check("в нём только рабочие", runnable.items.all { it.runnable })

    // ── тексты диалогов: причина, а не имя класса ───────────────────────
    val (title, body, button) = NativeLibraryPresenter.explain("compressed", "Hollow Knight", "сжатый образ")
    eq("диалог: заголовок", title, "Hollow Knight")
    check("диалог: про распаковку", body.contains("распаковывается"), body)
    eq("диалог: кнопка", button, "Распаковать")
    val keys = NativeLibraryPresenter.explain("unreadable", "Metroid Dread", "ядро не прочитало заголовок")
    check("диалог ключей зовёт prod.keys", keys.second.contains("prod.keys"), keys.second)
    // Причина из разбора контейнера должна доходить до диалога дословно:
    // раньше здесь стояла общая фраза про ключи, и она противоречила экрану
    // ключей, где всё было на месте (05.11).
    val nsz = NativeLibraryPresenter.explain(
        "unreadable", "Vault of Power",
        "Это сжатый образ (NSZ): ядро открывает только распакованные."
    )
    check("причина из разбора дошла до диалога", nsz.second.contains("NSZ"), nsz.second)
    eq("и кнопка стала «Распаковать»", nsz.third, "Распаковать")
    val known = NativeLibraryPresenter.explain(
        "unreadable", "Crafty Survivors",
        "Контейнер читается: игра 0100fe701475a000, частей 4, билет внутри есть."
    )
    check("про ключи не выдумывается, когда дело не в них",
        known.second.contains("0100fe701475a000"), known.second)
    check(
        "ни одного имени класса в текстах",
        listOf(body, keys.second).none { it.contains("Exception") || it.contains(".kt") }
    )
    // Пометка из разбора контейнера перебивает общую: «файл в порядке» вместо
    // «нет ключей», и кнопка ведёт к распаковке, когда дело в сжатии.
    val probed = NativeLibraryPresenter.build(
        games = emptyList(),
        side = listOf(
            NativeLibraryPresenter.Side(
                "Vault_of_Power.nsp", "/p/v.nsp", "Vault of Power",
                "Контейнер читается: игра 01004f302847e000, частей 4, билет внутри есть. " +
                    "Ключи на месте — значит ядро отказало не из-за них, и это наш дефект.",
                "unreadable", "файл в порядке"
            ),
            NativeLibraryPresenter.Side(
                "Packed.nsp", "/p/p.nsp", "Packed",
                "Это сжатый образ (NSZ): ядро открывает только распакованные.",
                "unreadable", "сжатый"
            )
        )
    )
    eq("пометка из разбора на плитке", probed.items[0].note, "файл в порядке")
    eq("и кнопка по разбору", probed.items[1].action, Action.CONVERT)

    val split = NativeLibraryPresenter.explain("split", "Big", "часть разрезанного образа")
    check("диалог части про целый файл", split.second.contains("целый файл"), split.second)

    // ── два ядра: пометка на плитке и счёт в сводке ─────────────────────
    val hybrid = NativeLibraryPresenter.build(
        games = games.mapIndexed { i, row -> if (i == 0) row.copy(core = "kenji") else row },
        side = side
    )
    eq("ядро видно на плитке", hybrid.items[0].core, "kenji")
    eq("у остальных игр пометки нет", hybrid.items[1].core, "")
    check("сводка считает игры на втором ядре", hybrid.summary.contains("1 на Kenji-NX"), hybrid.summary)
    eq(
        "без второго ядра счёт не появляется",
        NativeLibraryPresenter.build(games, side).summary.contains("Kenji-NX"),
        false
    )

    // ── связь с правилом «что такое игра» ───────────────────────────────
    val real = LibraryIndex.classify("Hollow Knight [0100633007D48000][v0].nsz")
    eq("NSZ остаётся сжатым", real.kind, LibraryIndex.Kind.COMPRESSED)
    eq("и его бейдж — nsz", NativeLibraryPresenter.extensionOf(real.name), "nsz")

    // ── исходники рядом: экран обязан звать презентер ───────────────────
    val root = File(".").absoluteFile
    val fragment = File(root, "patch/android/fragments/NativeLauncherFragment.kt")
    if (File(root, "patch/android").isDirectory) {
        check("экран существует", fragment.isFile)
        if (fragment.isFile) {
            val src = fragment.readText()
            check("экран не решает сам, что запускать", src.contains("NativeLibraryPresenter.build"))
            check("экран берёт причины из презентера", src.contains("NativeLibraryPresenter.explain"))
            check(
                "в экране нет WebView",
                !src.contains("android.webkit.WebView") && !src.contains("WebViewFragment")
            )
        }
        val nav = File(root, "patch/android/navigation/home_navigation.xml")
        if (nav.isFile) {
            val xml = nav.readText()
            check("лаунчер есть в навигации", xml.contains("nativeLauncherFragment"))
            check(
                "стартовый экран — нативный лаунчер",
                Regex("startDestination=\"@id/nativeLauncherFragment\"").containsMatchIn(xml)
            )
        }
    } else {
        println("  note  дерево patch/ рядом не найдено — проверка дрейфа пропущена")
    }

    // ── руководство: разделы и экраны, на которые они ссылаются ─────────
    check("в руководстве есть раздел «с чего начать»", Manual.sections.first().id == "start")
    check("разделов не меньше шести", Manual.sections.size >= 6, "${Manual.sections.size}")
    check("у каждого раздела есть шаги", Manual.sections.all { it.steps.isNotEmpty() })
    check(
        "ни один раздел не пустой",
        Manual.sections.all { it.title.isNotBlank() && it.intro.isNotBlank() }
    )
    check(
        "каждый шаг объясняет, а не только называет",
        Manual.sections.flatMap { it.steps }.all { it.text.length >= 30 },
        Manual.sections.flatMap { it.steps }.firstOrNull { it.text.length < 30 }?.title ?: ""
    )
    check(
        "поиск находит конвертер",
        Manual.search("конвертер").isNotEmpty(),
        "${Manual.search("конвертер").size}"
    )
    check("поиск находит ключи", Manual.search("prod.keys").isNotEmpty())
    check("поиск не находит чепуху", Manual.search("zzzzz").isEmpty())
    check("пустой запрос отдаёт всё", Manual.search("  ").size == Manual.sections.size)
    check(
        "кнопки руководства ведут на разные экраны",
        Manual.referencedScreens().size >= 6,
        "${Manual.referencedScreens()}"
    )
    check(
        "руководство ссылается на конвертер, ключи и папки",
        Manual.referencedScreens().containsAll(
            listOf(Manual.Go.CONVERT, Manual.Go.KEYS, Manual.Go.FOLDERS)
        )
    )
    check(
        "в текстах руководства нет имён классов",
        Manual.sections.none { s ->
            s.steps.any { it.text.contains("Exception") || it.text.contains(".kt") }
        }
    )

    if (File(root, "patch/android").isDirectory) {
        val manualScreen = File(root, "patch/android/fragments/ManualFragment.kt")
        check("экран руководства существует", manualScreen.isFile)
        if (manualScreen.isFile) {
            val src = manualScreen.readText()
            check("руководство берёт текст из Manual", src.contains("Manual.sections"))
            check("руководство умеет искать", src.contains("Manual.search"))
            check(
                "в руководстве нет WebView",
                !src.contains("android.webkit.WebView") && !src.contains("WebViewFragment")
            )
        }
        val navXml = File(root, "patch/android/navigation/home_navigation.xml")
        if (navXml.isFile) {
            val xml = navXml.readText()
            check("руководство есть в навигации", xml.contains("manualFragment"))
            check(
                "на руководство есть глобальное действие",
                xml.contains("action_global_manualFragment")
            )
            // Каждая кнопка раздела обязана вести на существующий экран.
            val actions = listOf(
                "action_global_symbiosisGameFoldersFragment",
                "action_global_keysFirmwareFragment",
                "action_global_toolsFragment",
                "action_global_enginesFragment",
                "action_global_fpsTuningFragment",
                "action_global_diagnosticsFragment",
                "action_global_guideFragment",
                "action_global_livePanelFragment"
            )
            val missing = actions.filterNot { xml.contains(it) }
            check("все экраны руководства есть в навигации", missing.isEmpty(), missing.joinToString())
        }
        val tools = File(root, "patch/android/fragments/ToolsFragment.kt")
        if (tools.isFile) {
            val src = tools.readText()
            check("в инструментах есть руководство", src.contains("action_global_manualFragment"))
            check(
                "старая панель названа расширенной",
                src.contains("live_panel_extended")
            )
        }
    }

    println()
    println("passed=$passed failed=$failed")
    if (failed > 0) {
        println("failing: ${failures.joinToString(", ")}")
        kotlin.system.exitProcess(1)
    }
}
