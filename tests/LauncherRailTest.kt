// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// «Лаунчер» показывал одну игру и не давал открыть остальные.
//
// Reported: «одну игру видит а остальные не видит, а раз не видит то и не
// открывает; либо лаунчер не прокручивается влево-вправо».
//
// Причина не в прокрутке. Рельс брал список из visibleGames(), а та
// применяет строку поиска и чипы-фильтры вкладки «Список». Наберите в
// «Списке» что-нибудь или оставьте фильтр «Сейв» — и карусель лаунчера
// схлопывалась до одной плитки. Дальше всё, что замечено:
//   * остальные игры нельзя было запустить из лаунчера вообще;
//   * полосы прокрутки у рельса нет (скрыта), рядом видно ~1.5 плитки,
//     так что «прокручивается» выглядит как «их тут нет»;
//   * фильтр «Не играли» давал пустой рельс и надпись «нет игр —
//     откройте Список и добавьте папку» при полной библиотеке;
//   * строка поиска перерисовывает только render(), но не renderLauncher(),
//     поэтому рельс и список показывали разное.
//
// Логика ниже смоделирована, а в конце проверяется по настоящим файлам:
// страница едет в APK из patch/android/assets, и вторая копия обязана
// быть такой же.
//
// Run: kotlinc tests/LauncherRailTest.kt -include-runtime -d /tmp/t.jar && java -jar /tmp/t.jar

import java.io.File

// ── the logic under test, mirrored from library.html ─────────────────

data class Game(
    val title: String,
    val developer: String = "",
    val titleId: String = "",
    val hasSave: Boolean = false,
    val hasMod: Boolean = false,
    val playSeconds: Long = 0,
    val lastPlayed: Long = 0
)

var SORT = "name"
var FILTER = "all"

fun sortGames(list: MutableList<Game>): MutableList<Game> = list.apply {
    sortWith(Comparator { a, b ->
        val byTime = when (SORT) {
            "recent" -> b.lastPlayed.compareTo(a.lastPlayed)
            "play" -> b.playSeconds.compareTo(a.playSeconds)
            else -> 0
        }
        if (byTime != 0) byTime else a.title.compareTo(b.title)
    })
}

/** «Список»: поиск и фильтры применяются здесь. */
fun visibleGames(games: List<Game>, q: String): MutableList<Game> {
    val needle = q.trim().lowercase()
    var list = games.toMutableList()
    if (needle.isNotEmpty()) {
        list = list.filterTo(mutableListOf()) {
            it.title.lowercase().contains(needle) ||
                it.developer.lowercase().contains(needle) ||
                it.titleId.lowercase().contains(needle)
        }
    }
    when (FILTER) {
        "save" -> list = list.filterTo(mutableListOf()) { it.hasSave }
        "mod" -> list = list.filterTo(mutableListOf()) { it.hasMod }
        "fresh" -> list = list.filterTo(mutableListOf()) { it.playSeconds == 0L && it.lastPlayed == 0L }
    }
    return sortGames(list)
}

/** «Лаунчер»: вся библиотека, всегда. */
fun railGames(games: List<Game>): MutableList<Game> = sortGames(games.toMutableList())

var failed = 0
fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else { failed++; println("  FAIL $name" + if (detail.isNotEmpty()) "\n       $detail" else "") }
}

fun main() {
    println("\nкарусель лаунчера — полная библиотека")

    val games = listOf(
        Game("Zelda", developer="Nintendo", lastPlayed=10, playSeconds=100),
        Game("Blade", developer="Studio", hasSave=true, hasMod=true, lastPlayed=50, playSeconds=20),
        Game("Fresh")
    )

    // Сценарий пользователя: в «Списке» набрали запрос, в лаунчере игр нет.
    FILTER = "all"
    check("список ищет по названию",
        visibleGames(games, "zel").map { it.title } == listOf("Zelda"))
    check("лаунчер не ищет",
        railGames(games).map { it.title } == listOf("Blade", "Fresh", "Zelda"),
        "поиск из «Списка» уехал в лаунчер")

    // Сценарий: оставили фильтр «Сейв», в библиотеке такой сейв один.
    FILTER = "save"
    check("фильтр «Сейв» сужает список",
        visibleGames(games, "").map { it.title } == listOf("Blade"))
    check("фильтр «Сейв» не сужает лаунчер",
        railGames(games).size == 3,
        "в лаунчере осталось ${railGames(games).size} из 3 — остальные не открыть")

    // Сценарий: фильтр «Не играли» даёт пустой список при полной библиотеке.
    FILTER = "fresh"
    check("фильтр «Не играли» сужает список",
        visibleGames(games, "").map { it.title } == listOf("Fresh"))
    check("пустой фильтр не убивает лаунчер",
        railGames(games).size == 3,
        "лаунчер показал «нет игр» при ${games.size} играх в библиотеке")

    // Сортировка общая, иначе порядок в двух вкладках разъезжается.
    FILTER = "all"
    SORT = "recent"
    check("лаунчер сортирует по «Недавно»",
        railGames(games).first().title == "Blade")
    check("список сортируется так же",
        visibleGames(games, "").map { it.title } == railGames(games).map { it.title })
    SORT = "play"
    check("сортировка по времени", railGames(games).first().title == "Zelda")
    SORT = "name"

    // Мультифильтр: и запрос, и чип одновременно.
    FILTER = "save"
    check("запрос и фильтр вместе",
        visibleGames(games, "blade").map { it.title } == listOf("Blade"))
    check("лаунчер игнорирует и то, и другое", railGames(games).size == 3)

    // ── the mirror must match the real page ──────────────────────────
    println("\nстраница в репозитории")
    val docs = File("docs/library.html")
    val asset = File("patch/android/assets/library.html")
    check("docs/library.html на месте", docs.exists(), docs.path)
    check("patch/android/assets/library.html на месте", asset.exists(), asset.path)
    if (docs.exists() && asset.exists()) {
        val page = docs.readText()
        check("обе копии страницы идентичны",
            docs.readBytes().contentEquals(asset.readBytes()),
            "APK везёт patch/android/assets, а правим docs — они разошлись")

        val railFn = Regex("function railGames\\(\\)[\\s\\S]*?\\n}").find(page)
        check("railGames() есть", railFn != null,
            "лаунчер по-прежнему берёт список из visibleGames()")
        check("renderLauncher() зовёт railGames(), а не visibleGames()",
            Regex("RAIL_LIST\\s*=\\s*railGames\\(\\)").containsMatchIn(page) &&
                !Regex("RAIL_LIST\\s*=\\s*visibleGames\\(\\)").containsMatchIn(page))
        if (railFn != null) {
            check("railGames() не читает строку поиска",
                !railFn.value.contains("$('q')") && !railFn.value.contains("FILTER ==="),
                "фильтр снова попадает в карусель")
            check("railGames() отдаёт всю библиотеку",
                railFn.value.contains("GAMES.slice()"))
        }
        // Поиск и фильтры читает только список — иначе правка выше ничего не стоит.
        check("visibleGames() осталась единственным местом с поиском",
            page.split("qEl ? (qEl.value || '')").size == 2)
        check("док показывает, какая это игра по счёту",
            Regex("\\$\\{LAUNCH_I \\+ 1\\} / \\$\\{RAIL_LIST\\.length\\}").containsMatchIn(page),
            "нет счётчика — одна плитки читается как «одна игра»")
        check("счётчик подписан в стилях", page.contains(".dock .pos {"))

        // ── вторая половина жалобы: «влево-вправо не работает» ────────
        //
        // Свайп по карусели не сдвигал её по трём причинам, и все три
        // видны только в разметке: картинка обложки тянулась пальцем
        // сама (drag), позиция ставилась scrollIntoView, который
        // прокручивает всех предков подряд, и каждая пересборка
        // возвращала карусель на выбранную плитку - а пересборку
        // вызывал опрос findGames() каждые 700 мс, то есть прямо во
        // время свайпа.
        check("обложка не тянется пальцем", page.contains("draggable=\"false\""))
        check("картинка обложки не перехватывает жест",
            Regex("\\.slide img \\{[\\s\\S]*?-webkit-user-drag:none").containsMatchIn(page) &&
                Regex("\\.slide img \\{[\\s\\S]*?pointer-events:none").containsMatchIn(page),
            "WebView начнёт тянуть картинку раньше, чем дойдёт свайп")
        check("выделение плитки нельзя выделить текстом",
            Regex("\\.slide \\{[\\s\\S]*?user-select:none").containsMatchIn(page))
        check("доскакав до края, страница не уезжает следом",
            Regex("\\.rail \\{[\\s\\S]*?overscroll-behavior-x:contain").containsMatchIn(page),
            "WebView дотянет родительскую прокрутку и дёрнет экран")
        check("позиция ставится прокруткой самого рельса",
            Regex("function railCentre\\([\\s\\S]*?rail\\.scrollTo").containsMatchIn(page),
            "scrollIntoView уезжает вбок вместе со страницей")
        check("scrollIntoView на рельсе больше не остался",
            !Regex("(function renderLauncher|function snapTo|function keepLaunchFrame)" +
                "[\\s\\S]*?scrollIntoView\\(").containsMatchIn(page) ||
                page.split("scrollIntoView").size == 2,
            "один из вызовов прокручивает страницу, а не карусель")
        check("рельс не пересобирается, когда библиотека не менялась",
            Regex("RAIL_SIG").containsMatchIn(page) &&
                Regex("if \\(sig === RAIL_SIG && rail\\.children\\.length\\)").containsMatchIn(page),
            "опрос findGames() возвращает карусель назад каждые 700 мс")
        check("выбор переживает пересборку",
            Regex("const keepPath = RAIL_LIST\\[LAUNCH_I\\][\\s\\S]*?RAIL_LIST\\.findIndex")
                .containsMatchIn(page))
        check("своя прокрутка не выбирает плитку",
            page.contains("RAIL_BUSY_UNTIL") &&
                Regex("function nearestSlide\\(\\) \\{\\s*if \\(Date\\.now\\(\\) < RAIL_BUSY_UNTIL\\) return;")
                    .containsMatchIn(page),
            "плавная прокрутка ставит выделение на предыдущую плитку")
        check("стрелки влево-вправо двигают карусель",
            Regex("ev\\.key !== 'ArrowLeft' && ev\\.key !== 'ArrowRight'").containsMatchIn(page) &&
                Regex("snapTo\\(LAUNCH_I \\+ \\(ev\\.key === 'ArrowRight' \\? 1 : -1\\)\\)")
                    .containsMatchIn(page))

        // ── Жест забирает JS, а не браузер ──────────────────────────────────
        //
        // pan-x был поставлен в предыдущей правке и не помог: на этом
        // телефоне WebView отдавал свайп не рельсу, и страница дёргалась
        // вместо карусели. Проверяется именно то, чего больше нет.
        check("встроенная прокрутка больше не разбирает жест",
            Regex("touch-action\\s*:\\s*none").containsMatchIn(page) &&
                !Regex("touch-action\\s*:\\s*pan-x").containsMatchIn(page),
            "pan-x WebView на этом телефоне трактует иначе и отдаёт жест экрану")
        check("нативный snap убран: он дёргал карусель к плитке посреди свайпа",
            !page.contains("scroll-snap-type"),
            "плитка прилипает к пальцу и карусель не едет")
        check("свайп разбираем сами",
            Regex("function bindRailSwipe").containsMatchIn(page) &&
                Regex("rail\\.addEventListener\\('pointerdown'").containsMatchIn(page) &&
                Regex("rail\\.addEventListener\\('pointermove'").containsMatchIn(page) &&
                Regex("\\['pointerup', 'pointercancel'\\]\\.forEach").containsMatchIn(page),
            "жест по-прежнему разбирает браузер")
        check("движение гасится preventDefault, иначе страница уедет вбок",
            Regex("pointermove[\\s\\S]*?ev\\.preventDefault\\(\\)").containsMatchIn(page))
        check("позиция ставится в scrollLeft, а не доверяется браузеру",
            Regex("pointermove[\\s\\S]*?rail\\.scrollLeft = Math\\.min").containsMatchIn(page))
        check("отпускание останавливает карусель на плитке",
            Regex("function railRelease\\(\\)").containsMatchIn(page) &&
                Regex("snapTo\\(flick \\|\\| railNearestIndex\\(\\)\\)").containsMatchIn(page))
        check("бросок листает на плитку, а не на пиксель",
            Regex("RAIL_FLICK_SPEED").containsMatchIn(page) &&
                Regex("base - Math\\.sign\\(vx\\) \\* steps").containsMatchIn(page),
            "резкий свайп должен перескакивать плитку, а не ползти")
        check("вертикальный жест не считается свайпом карусели",
            Regex("railRelease\\(\\);\\s*//\\s*жест вертикальный").containsMatchIn(page) ||
                Regex("жест вертикальный").containsMatchIn(page),
            "палец по вертикали должен уметь листать страницу")
        check("после свайпа клик не запускает игру",
            Regex("RAIL_SUPPRESS_CLICK").containsMatchIn(page) &&
                Regex("addEventListener\\('click',[\\s\\S]*?RAIL_SUPPRESS_CLICK").containsMatchIn(page),
            "листали карусель - и тут же запустили игру")
        check("плавная прокрутка не остаётся единственным способом встать на плитку",
            Regex("function railCentre[\\s\\S]*?setTimeout[\\s\\S]*?rail\\.scrollLeft = left")
                .containsMatchIn(page),
            "smooth на 8 ГБ то едет, то молчит: выделение уезжает, карусель нет")
    }

    println()
    if (failed > 0) { println("$failed check(s) failed"); System.exit(1) }
    println("all checks passed")
}
