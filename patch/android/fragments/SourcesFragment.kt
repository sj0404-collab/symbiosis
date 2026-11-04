// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.activities.SourcePlayerActivity
import org.yuzu.yuzu_emu.utils.MagnetMeta
import org.yuzu.yuzu_emu.utils.SourceBrowser
import org.yuzu.yuzu_emu.utils.SourceIndex
import org.yuzu.yuzu_emu.utils.SourceRangeServer
import org.yuzu.yuzu_emu.utils.SymbiosisTheme
import org.yuzu.yuzu_emu.utils.TorrentDownload
import org.yuzu.yuzu_emu.utils.TorrentMeta
import org.yuzu.yuzu_emu.utils.TorrentPlan

/**
 * «Источники»: даёшь ссылку — видишь, что по ней лежит, и берёшь нужное.
 *
 * КАК ЭТО РАБОТАЕТ ДЛЯ ЧЕЛОВЕКА
 *   1. Вставил адрес (страница каталога, прямая ссылка на файл, `.torrent`
 *      или magnet) и нажал «Открыть».
 *   2. Появился список: имя, вид (видео, книга, игра, архив, торрент),
 *      размер. У папок — «Открыть», у файлов — «Скачать» и, если это видео
 *      или музыка, «По частям».
 *   3. «По частям» открывает встроенный плеер: он берёт с сервера только то,
 *      что успел показать, поэтому фильм на два часа не надо качать целиком.
 *   4. «Скачать» кладёт файл в `Download/Symbiosis/Источники` и показывает
 *      прогресс: проценты, мегабайты, скорость.
 *
 * ЧЕГО ЭКРАН НЕ ДЕЛАЕТ
 *   Не ищет торренты и не знает «пиратских сайтов»: адрес даёт человек.
 *   Если сайт не отдаёт список, экран пишет, почему именно (403, нужен вход,
 *   страница собирается скриптами), а не «ничего не найдено».
 */
class SourcesFragment : Fragment() {

    private lateinit var root: LinearLayout
    private lateinit var input: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var content: LinearLayout

    /** Куда мы «вошли» и как вернуться назад. */
    private val history = ArrayList<String>()
    private var listing: SourceBrowser.Opened.Listing? = null
    private var torrent: TorrentMeta.Meta? = null
    private var statusLine: String = ""
    private var statusAccent: Int = SymbiosisTheme.CYAN
    private var downloadJob: Thread? = null
    private val cancelFlag = AtomicBoolean(false)
    private var activeServer: SourceRangeServer.Instance? = null
    private var downloaded: List<File> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.10f), SymbiosisTheme.BASE)
            )
        }
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(12), bottom = bars.bottom + dp(32))
            insets
        }
        render()
    }

    override fun onDestroyView() {
        cancelFlag.set(true)
        activeServer?.stop()
        activeServer = null
        downloadJob?.interrupt()
        super.onDestroyView()
    }

    // --- отрисовка ---------------------------------------------------------

    private fun render() {
        val ctx = requireContext()
        root.removeAllViews()

        root.addView(
            TextView(ctx).apply {
                text = "Источники"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(SymbiosisTheme.TEXT)
            }
        )
        root.addView(
            TextView(ctx).apply {
                text = "Ссылка на каталог, файл, торрент или magnet — и список того, что внутри"
                textSize = 14f
                setTextColor(SymbiosisTheme.CYAN)
                setPadding(0, dp(2), 0, dp(12))
            }
        )

        root.addView(inputRow())

        if (statusLine.isNotEmpty()) {
            root.addView(banner(statusLine, statusAccent))
        }

        content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        renderContent()

        root.addView(sectionTitle("Что можно вставить", SymbiosisTheme.CYAN))
        root.addView(
            note(
                "Адрес открытого каталога (например, зеркала дистрибутивов и архивов), прямую " +
                    "ссылку на видео или книгу, файл .torrent и magnet-ссылку. Магнит без " +
                    "трекеров не откроется: список файлов в нём хранится не в самой ссылке, а у " +
                    "пиров, и без трекера их не найти."
            )
        )
        if (downloaded.isNotEmpty()) {
            root.addView(sectionTitle("Скачано", SymbiosisTheme.MINT))
            downloaded.takeLast(6).forEach { file ->
                root.addView(
                    note("${file.name} · ${TorrentPlan.humanBytes(file.length())}\n${file.absolutePath}")
                )
            }
            root.addView(
                rowOf(
                    button("Папки игр", SymbiosisTheme.MINT) {
                        runCatching {
                            root.findNavController()
                                .navigate(R.id.action_global_symbiosisGameFoldersFragment)
                        }
                    },
                    button("Папка загрузок", SymbiosisTheme.BLUE) {
                        openFolderInPicker()
                    }
                )
            )
            root.addView(
                note(
                    "Если скачалась игра — добавьте папку загрузок в «Папки игр», и она " +
                        "появится в библиотеке. Это та же папка, что у конвертера: " +
                        "Download/Symbiosis."
                )
            )
        }
    }

    private fun inputRow(): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SymbiosisTheme.CARD, 18, 1, SymbiosisTheme.soft(SymbiosisTheme.CYAN, 0.35f))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        input = EditText(ctx).apply {
            hint = "https://… или magnet:?xt=urn:btih:…"
            textSize = 14f
            setTextColor(SymbiosisTheme.TEXT)
            setHintTextColor(SymbiosisTheme.TEXT_DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
            setText(history.lastOrNull().orEmpty())
        }
        box.addView(input)

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(SymbiosisTheme.CYAN)
        }
        progressText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(SymbiosisTheme.TEXT_DIM)
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(2))
        }
        box.addView(progressBar)
        box.addView(progressText)

        box.addView(
            rowOf(
                button("Открыть", SymbiosisTheme.CYAN) { openTyped() },
                button("Вставить", SymbiosisTheme.BLUE) { pasteFromClipboard() },
                button("Назад", SymbiosisTheme.OUTLINE) { goBack() }
            )
        )
        return box
    }

    private fun renderContent() {
        val ctx = requireContext()
        content.removeAllViews()

        val currentTorrent = torrent
        val currentListing = listing

        if (currentTorrent != null) {
            content.addView(
                card(SymbiosisTheme.MINT) {
                    text("${currentTorrent.name}", 18f, SymbiosisTheme.TEXT, bold = true)
                    text(currentTorrent.listing(), 13f, SymbiosisTheme.TEXT_DIM)
                    text(
                        "Трекеров: ${currentTorrent.announce.size}" +
                            if (currentTorrent.isPrivate) " · приватный" else "",
                        12f,
                        SymbiosisTheme.TEXT_DIM
                    )
                    contentRow(
                        button("Скачать всё", SymbiosisTheme.MINT) { downloadTorrent(null) },
                        button("Закрыть", SymbiosisTheme.OUTLINE) {
                            torrent = null
                            statusLine = ""
                            render()
                        }
                    )
                }
            )
            currentTorrent.files.forEachIndexed { index, file ->
                val kind = TorrentPlan.kindOf(file.extension)
                content.addView(
                    card(SymbiosisTheme.CARD) {
                        text(file.path, 15f, SymbiosisTheme.TEXT, bold = true)
                        text(
                            "$kind · ${TorrentPlan.humanBytes(file.length)}" +
                                if (file.length >= TORRENT_STREAM_MIN) " · можно смотреть" else "",
                            12f,
                            SymbiosisTheme.TEXT_DIM
                        )
                        contentRow(
                            button("Скачать", SymbiosisTheme.CYAN) { downloadTorrent(listOf(index)) },
                            if (file.length >= TORRENT_STREAM_MIN && kind == "видео") {
                                button("По частям", SymbiosisTheme.VIOLET) {
                                    downloadTorrent(listOf(index), playAfter = true)
                                }
                            } else null
                        )
                    }
                )
            }
            return
        }

        if (currentListing != null) {
            content.addView(
                TextView(ctx).apply {
                    text = currentListing.title
                    textSize = 15f
                    setTextColor(SymbiosisTheme.TEXT)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(2), 0, dp(2))
                }
            )
            content.addView(
                TextView(ctx).apply {
                    text = "Файлов и папок: ${currentListing.entries.size}" +
                        if (currentListing.note.isNotEmpty()) " · ${currentListing.note}" else ""
                    textSize = 12f
                    setTextColor(SymbiosisTheme.TEXT_DIM)
                    setPadding(0, 0, 0, dp(8))
                }
            )
            currentListing.entries.forEach { entry ->
                content.addView(entryCard(entry))
            }
            return
        }

        content.addView(
            note(
                "Пока ничего не открыто. Вставьте адрес сверху — здесь появится список " +
                    "файлов с размерами и кнопками."
            )
        )
    }

    /** Карточка одного файла или папки из каталога. */
    private fun entryCard(entry: SourceIndex.Entry): View {
        val accent = when {
            entry.isDirectory -> SymbiosisTheme.BLUE
            entry.kind == SourceIndex.Kind.VIDEO -> SymbiosisTheme.PINK
            entry.kind == SourceIndex.Kind.TORRENT -> SymbiosisTheme.MINT
            entry.kind == SourceIndex.Kind.BOOK -> SymbiosisTheme.AMBER
            entry.kind == SourceIndex.Kind.GAME -> SymbiosisTheme.LIME
            else -> SymbiosisTheme.VIOLET
        }
        return card(accent) {
            text(entry.name, 15f, SymbiosisTheme.TEXT, bold = true)
            text(
                entry.kind.label + (entry.sizeBytes?.let { " · ${TorrentPlan.humanBytes(it)}" } ?: ""),
                12f,
                SymbiosisTheme.TEXT_DIM
            )
            if (entry.isDirectory) {
                contentRow(
                    button("Открыть", SymbiosisTheme.BLUE) { openUrl(entry.url) }
                )
            } else {
                contentRow(
                    button("Скачать", accent) { downloadFile(entry) },
                    if (entry.isStreamable) {
                        button("По частям", SymbiosisTheme.VIOLET) { playParts(entry) }
                    } else null,
                    if (entry.isTorrent) {
                        button("Файлы", SymbiosisTheme.MINT) { openUrl(entry.url) }
                    } else null
                )
            }
        }
    }

    // --- действия ----------------------------------------------------------

    private fun openTyped() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            statusLine = "Сначала вставьте ссылку"
            statusAccent = SymbiosisTheme.AMBER
            render()
            return
        }
        openUrl(text)
    }

    private fun openUrl(url: String) {
        if (countBusy()) return
        statusLine = if (url.startsWith("magnet:", true)) "разбираю magnet-ссылку…" else "открываю ссылку…"
        statusAccent = SymbiosisTheme.CYAN
        torrent = null
        listing = null
        render()
        showProgress(-1, "")

        val thread = Thread {
            val result = runCatching {
                if (url.startsWith("magnet:", true)) {
                    openMagnet(url)
                } else {
                    SourceBrowser.open(url) { step -> report(status = step) }
                }
            }.getOrElse { SourceBrowser.Opened.Failed(reasonOf(it)) }
            activity?.runOnUiThread {
                applyOpened(url, result)
            }
        }
        thread.isDaemon = true
        downloadJob = thread
        thread.start()
    }

    /** @return null, если экран уже показывает результат. */
    private fun openMagnet(url: String): SourceBrowser.Opened {
        val magnet = TorrentMeta.parseMagnet(url).getOrElse {
            return SourceBrowser.Opened.Failed(it.message ?: "magnet-ссылка не разобрана")
        }
        val meta = MagnetMeta.fetch(magnet) { step -> report(status = step) }
            .getOrElse { return SourceBrowser.Opened.Failed(it.message ?: "список файлов не получен") }
        return SourceBrowser.Opened.File(
            url = url,
            name = meta.name,
            sizeBytes = meta.totalLength,
            kind = SourceIndex.Kind.TORRENT,
            torrent = meta
        )
    }

    private fun applyOpened(url: String, opened: SourceBrowser.Opened) {
        hideProgress()
        when (opened) {
            is SourceBrowser.Opened.Listing -> {
                listing = opened
                torrent = null
                if (history.none { it == url }) history.add(url)
                statusLine = "открыто: ${opened.entries.size} записей"
                statusAccent = SymbiosisTheme.CYAN
            }
            is SourceBrowser.Opened.File -> {
                if (opened.torrent != null) {
                    torrent = opened.torrent
                    listing = null
                    if (history.none { it == url }) history.add(url)
                    statusLine = "торрент разобран: ${opened.torrent.files.size} файлов"
                    statusAccent = SymbiosisTheme.MINT
                } else if (opened.magnet != null) {
                    statusLine = "magnet-ссылка без трекеров: список файлов получить негде"
                    statusAccent = SymbiosisTheme.AMBER
                } else {
                    // Прямая ссылка на файл: показываем её одной карточкой.
                    torrent = null
                    listing = SourceBrowser.Opened.Listing(
                        url = url,
                        title = opened.name,
                        entries = listOf(
                            SourceIndex.Entry(
                                name = opened.name,
                                url = opened.url,
                                kind = opened.kind,
                                sizeBytes = opened.sizeBytes
                            )
                        )
                    )
                    if (history.none { it == url }) history.add(url)
                    statusLine = "это файл, а не каталог"
                    statusAccent = SymbiosisTheme.CYAN
                }
            }
            is SourceBrowser.Opened.Failed -> {
                statusLine = opened.message
                statusAccent = SymbiosisTheme.RED
            }
        }
        render()
    }

    private fun goBack() {
        if (history.size <= 1) {
            history.clear()
            listing = null
            torrent = null
            statusLine = ""
            render()
            return
        }
        history.removeAt(history.size - 1)
        openUrl(history.last())
    }

    private fun pasteFromClipboard() {
        val manager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = manager?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
        if (text.isBlank()) {
            statusLine = "в буфере обмена нет текста"
            statusAccent = SymbiosisTheme.AMBER
            render()
            return
        }
        statusLine = "вставлено из буфера"
        statusAccent = SymbiosisTheme.CYAN
        render()
        input.setText(text.trim())
    }

    private fun openFolderInPicker() {
        runCatching {
            root.findNavController().navigate(R.id.action_global_symbiosisGameFoldersFragment)
        }.onFailure {
            statusLine = "экран «Папки игр» не открылся: ${reasonOf(it)}"
            statusAccent = SymbiosisTheme.AMBER
            render()
        }
    }

    // --- скачивание --------------------------------------------------------

    private fun downloadFile(entry: SourceIndex.Entry) {
        if (countBusy()) return
        val target = File(downloadDir(), safeName(entry.name))
        if (target.exists() && target.length() > 0) {
            statusLine = "файл уже есть: ${target.name} · ${TorrentPlan.humanBytes(target.length())}"
            statusAccent = SymbiosisTheme.MINT
            downloaded = (downloaded + target).distinct()
            render()
            return
        }
        cancelFlag.set(false)
        statusLine = "скачиваю ${entry.name}"
        statusAccent = SymbiosisTheme.CYAN
        render()
        showProgress(0, "0%")

        val thread = Thread {
            val result = SourceBrowser.download(
                url = entry.url,
                target = target,
                cancelled = cancelFlag
            ) { got, total ->
                val percent = if (total > 0) (got * 100 / total).toInt() else -1
                val text = buildString {
                    if (percent >= 0) append("$percent% · ")
                    append(TorrentPlan.humanBytes(got))
                    if (total > 0) append(" из ${TorrentPlan.humanBytes(total)}")
                }
                report(percent = percent, text = text)
            }
            activity?.runOnUiThread {
                hideProgress()
                result.onSuccess { done ->
                    downloaded = (downloaded + done.file).distinct()
                    statusLine = "скачано: ${done.file.name} · ${TorrentPlan.humanBytes(done.bytes)}" +
                        if (done.resumed) " (докачано)" else ""
                    statusAccent = SymbiosisTheme.MINT
                }.onFailure {
                    statusLine = "не скачалось: ${reasonOf(it)}"
                    statusAccent = SymbiosisTheme.RED
                }
                render()
            }
        }
        thread.isDaemon = true
        downloadJob = thread
        thread.start()
    }

    private fun downloadTorrent(indexes: List<Int>?, playAfter: Boolean = false) {
        val meta = torrent ?: return
        if (countBusy()) return
        val selected = indexes.orEmpty()
        val outDir = File(downloadDir(), safeName(meta.name).substringBeforeLast('.', safeName(meta.name)))
        cancelFlag.set(false)
        statusLine = "готовлю торрент: ${meta.name}"
        statusAccent = SymbiosisTheme.CYAN
        render()
        showProgress(0, "ищу пиров")

        val thread = Thread {
            val result = TorrentDownload(meta, selected, outDir) { state ->
                val text = buildString {
                    append("${state.status} · ${"%.0f".format(state.percent)}%")
                    append(" · ${TorrentPlan.humanBytes(state.doneBytes)}")
                    if (state.totalBytes > 0) append(" из ${TorrentPlan.humanBytes(state.totalBytes)}")
                    if (state.speedBytesPerSecond > 0) {
                        append(" · ${TorrentPlan.humanBytes(state.speedBytesPerSecond)}/с")
                    }
                    if (state.peers > 0) append(" · пиров: ${state.peers}")
                }
                report(percent = state.percent.toInt(), text = text)
            }.run()

            activity?.runOnUiThread {
                hideProgress()
                result.onSuccess { files ->
                    downloaded = (downloaded + files).distinct()
                    statusLine = "торрент скачан: ${files.size} файлов в ${outDir.name}"
                    statusAccent = SymbiosisTheme.MINT
                    if (playAfter) {
                        val video = files.firstOrNull {
                            SourceIndex.kindOf(it.name) == SourceIndex.Kind.VIDEO
                        }
                        if (video != null) playLocal(video)
                    }
                }.onFailure {
                    statusLine = reasonOf(it)
                    statusAccent = SymbiosisTheme.RED
                }
                render()
            }
        }
        thread.isDaemon = true
        downloadJob = thread
        thread.start()
    }

    // --- «по частям» -------------------------------------------------------

    /**
     * Открыть файл в плеере, не скачивая целиком.
     *
     * Плеер получает локальный адрес: наш сервер переливает байты с
     * источника по мере просмотра и добавляет `Referer` — часть зеркал без
     * него отдаёт 403, а из плеера этот заголовок не поставить.
     */
    private fun playParts(entry: SourceIndex.Entry) {
        activeServer?.stop()
        val referer = runCatching {
            val url = java.net.URL(entry.url)
            "${url.protocol}://${url.host}/"
        }.getOrDefault("")
        val server = SourceRangeServer.Instance(
            SourceRangeServer.Source.Remote(
                entry.url,
                if (referer.isEmpty()) emptyMap() else mapOf("Referer" to referer)
            ),
            entry.name,
            SourceRangeServer.contentTypeOf(entry.name)
        )
        server.start().onSuccess { localUrl ->
            activeServer = server
            // Запасной путь: если телефон не открыл локальный поток, плеер
            // попробует сам источник напрямую.
            startPlayer(localUrl, entry.name, entry.url)
        }.onFailure {
            statusLine = "не удалось включить просмотр по частям: ${reasonOf(it)}"
            statusAccent = SymbiosisTheme.RED
            render()
        }
    }

    private fun playLocal(file: File) {
        startPlayer(file.absolutePath, file.name)
    }

    private fun startPlayer(url: String, name: String, remote: String? = null) {
        runCatching {
            startActivity(
                Intent(requireContext(), SourcePlayerActivity::class.java)
                    .putExtra(SourcePlayerActivity.EXTRA_URL, url)
                    .putExtra(SourcePlayerActivity.EXTRA_NAME, name)
                    .putExtra(SourcePlayerActivity.EXTRA_REMOTE, remote.orEmpty())
            )
        }.onFailure {
            statusLine = "плеер не открылся: ${reasonOf(it)}"
            statusAccent = SymbiosisTheme.RED
            render()
        }
    }

    // --- мелочи ------------------------------------------------------------

    private fun countBusy(): Boolean {
        val running = downloadJob?.isAlive == true
        if (running) {
            statusLine = "дождитесь конца текущей загрузки или закройте экран"
            statusAccent = SymbiosisTheme.AMBER
            render()
        }
        return running
    }

    private fun downloadDir(): File =
        File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "Symbiosis/Источники"
        )

    private fun safeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').ifBlank { "файл" }

    private fun reasonOf(t: Throwable): String = when (t) {
        is java.io.IOException -> t.message ?: "сеть недоступна"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun report(percent: Int = -1, text: String = "", status: String = "") {
        activity?.runOnUiThread {
            if (status.isNotEmpty()) {
                statusLine = status
                statusAccent = SymbiosisTheme.CYAN
            }
            showProgress(percent, text)
        }
    }

    private fun showProgress(percent: Int, text: String) {
        if (!::progressBar.isInitialized) return
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = percent < 0
        if (percent >= 0) progressBar.progress = (percent * 10).coerceIn(0, 1000)
        progressText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        progressText.text = text
    }

    private fun hideProgress() {
        if (!::progressBar.isInitialized) return
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun banner(text: String, accent: Int): View =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(SymbiosisTheme.TEXT)
            background = rounded(SymbiosisTheme.mix(SymbiosisTheme.CARD, accent, 0.16f), 14, 1,
                SymbiosisTheme.soft(accent, 0.45f))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

    private fun note(text: String): View =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(SymbiosisTheme.TEXT_DIM)
            setPadding(0, dp(4), 0, dp(10))
        }

    private fun sectionTitle(text: String, accent: Int): View =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            setPadding(0, dp(12), 0, dp(6))
        }

    private fun button(text: String, accent: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (accent == SymbiosisTheme.OUTLINE) SymbiosisTheme.TEXT else SymbiosisTheme.BASE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (accent == SymbiosisTheme.OUTLINE) SymbiosisTheme.CARD else accent
            )
            cornerRadius = dp(14)
            minHeight = dp(38)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }

    private fun rowOf(vararg views: View): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(6), 0, dp(2))
            views.filterNotNull().forEach { view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                )
            }
        }

    /** Карточка: акцентная полоса слева и содержимое, собранное [build]. */
    private fun card(accent: Int, build: CardBody.() -> Unit): View {
        val ctx = requireContext()
        val body = CardBody(ctx, accent)
        build(body)
        body.root.setPadding(dp(13), dp(11), dp(13), dp(11))
        return body.root
    }

    private inner class CardBody(val ctx: Context, val accent: Int) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SymbiosisTheme.mix(SymbiosisTheme.CARD, accent, 0.10f), 16, 1,
                SymbiosisTheme.soft(accent, 0.35f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) }
        }

        fun text(value: String, size: Float, color: Int, bold: Boolean = false) {
            root.addView(
                TextView(ctx).apply {
                    text = value
                    textSize = size
                    setTextColor(color)
                    if (bold) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(1), 0, dp(1))
                }
            )
        }

        fun contentRow(vararg views: View?) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            views.filterNotNull().forEach { view ->
                row.addView(
                    view,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                )
            }
            root.addView(row)
        }
    }

    companion object {
        /**
         * Куски торрента идут по порядку, поэтому «смотреть по ходу» имеет
         * смысл для файлов, где это заметно: 50 МБ и больше.
         */
        private const val TORRENT_STREAM_MIN = 50L * 1024 * 1024
    }
}
