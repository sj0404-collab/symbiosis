// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import org.json.JSONObject
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.utils.Converter
import org.yuzu.yuzu_emu.utils.UserPresets
import org.yuzu.yuzu_emu.utils.CrashReport
import org.yuzu.yuzu_emu.utils.GameAddons
import org.yuzu.yuzu_emu.utils.LauncherSettings
import org.yuzu.yuzu_emu.utils.LivePanel
import org.yuzu.yuzu_emu.utils.PluginPack
import org.yuzu.yuzu_emu.utils.SaveSource
import org.yuzu.yuzu_emu.utils.SharedDataDirectory
import org.yuzu.yuzu_emu.utils.Spaces

/**
 * Весь интерфейс — страница. APK запускает игру.
 *
 * Краш при выходе из игры часто убивал список игр (RecyclerView + иконки +
 * скан). WebView остаётся живым, страница не пересобирается, диск не
 * читается. Правка HTML на GitHub Pages видна без новой сборки.
 */
class LivePanelFragment : Fragment() {

    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private val gamesViewModel: GamesViewModel by activityViewModels()

    private val pickGamesFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            (activity as? MainActivity)?.processGamesDir(uri, true)
            main.postDelayed({
                web?.evaluateJavascript("try{if(typeof onFolderAdded==='function')onFolderAdded()}catch(e){}", null)
            }, 800)
        }

    private val pickDataFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = context ?: return@registerForActivityResult
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val path = SharedDataDirectory.resolveTreePath(uri)
                ?.let { SharedDataDirectory.normaliseRoot(it) }
                ?: return@registerForActivityResult
            SharedDataDirectory.configuredPath = path
            SharedDataDirectory.redirectNow(path)
            main.postDelayed({ reloadPageData() }, 400)
        }

    private val pickSavesFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = context ?: return@registerForActivityResult
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val path = SharedDataDirectory.resolveTreePath(uri)
                ?.let { SaveSource.normalise(it) }
            if (path.isNullOrBlank()) {
                main.post {
                    web?.evaluateJavascript(
                        "try{if(typeof onSavesPicked==='function')onSavesPicked(" +
                            JSONObject().put("ok", false)
                                .put("reason", "не удалось прочитать путь к папке")
                                .toString() +
                            ")}catch(e){}",
                        null
                    )
                }
                return@registerForActivityResult
            }
            SaveSource.configuredPath = path
            main.postDelayed({
                web?.evaluateJavascript(
                    "try{if(typeof onSavesPicked==='function')onSavesPicked(" +
                        SaveSource.statusJson() +
                        ")}catch(e){}",
                    null
                )
                reloadPageData()
            }, 200)
        }

    private val pickPluginFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            Thread {
                val last = uris.map { uri ->
                    runCatching { PluginPack.install(ctx, uri) }.getOrDefault(
                        org.json.JSONObject().put("ok", false).put("message", "сбой установки").toString()
                    )
                }.lastOrNull() ?: "{}"
                main.post {
                    web?.evaluateJavascript(
                        "try{if(typeof onPluginsChanged==='function')onPluginsChanged(" +
                            last +
                            ")}catch(e){}",
                        null
                    )
                }
            }.start()
        }

    private val pickConvertFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            Converter.enqueue(ctx, uris)
            fun tick() {
                web?.evaluateJavascript(
                    "try{if(typeof onConvertQueue==='function')onConvertQueue(" +
                        Converter.queueJson() + ")}catch(e){}",
                    null
                )
                if (Converter.busy) main.postDelayed({ tick() }, 700)
            }
            main.post { tick() }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        web?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val view = WebView(requireContext())
        web = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = true
        view.settings.allowContentAccess = false
        view.addJavascriptInterface(Bridge(), "Symbiosis")
        // Отступы делает сама страница (padding-top 48px). Двойной inset
        // прятал заголовок и кнопки под статус-бар на одних прошивках
        // и оставлял дыру на других.
        view.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                v: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    v?.loadUrl(LivePanel.OFFLINE_URL)
                }
            }
        }
        // Сразу assets: Pages без сети мигал пустым экраном.
        view.loadUrl(LivePanel.OFFLINE_URL)
        return view
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        // Не перерисовываем страницу: список уже на экране.
        // Перезагрузка после выхода из игры как раз обнуляла кэш в UI.
    }

    override fun onDestroyView() {
        (web?.parent as? ViewGroup)?.removeView(web)
        // A detached WebView still receives the configuration change when the
        // user rotates on another screen and native-crashes on Mali-G57.
        if (activity?.isChangingConfigurations != true) {
            web?.apply {
                stopLoading()
                removeJavascriptInterface("Symbiosis")
                loadUrl("about:blank")
                destroy()
            }
            web = null
        }
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (activity?.isChangingConfigurations != true) {
            web?.apply {
                stopLoading()
                removeJavascriptInterface("Symbiosis")
                loadUrl("about:blank")
                destroy()
            }
            web = null
        }
        super.onDestroy()
    }

    private fun reloadPageData() {
        web?.evaluateJavascript(
            "try{if(typeof loadGames==='function')loadGames();if(typeof loadStatus==='function')loadStatus();}catch(e){}",
            null
        )
    }

    inner class Bridge {

        @JavascriptInterface
        fun bridgeVersion(): Int = LivePanel.BRIDGE_VERSION

        @JavascriptInterface
        fun status(): String = runCatching {
            LivePanel.statusJson(requireContext().applicationContext)
        }.getOrDefault("""{"error":"недоступно"}""")

        @JavascriptInterface
        fun folders(): String = runCatching {
            LivePanel.foldersJson(requireContext().applicationContext)
        }.getOrDefault("""{"folders":[]}""")

        @JavascriptInterface
        fun games(): String = runCatching {
            LivePanel.gamesJson()
        }.getOrDefault("""{"games":[]}""")

        @JavascriptInterface
        fun files(uriString: String): String = runCatching {
            LivePanel.filesJson(requireContext().applicationContext, uriString)
        }.getOrDefault("""{"files":[]}""")

        @JavascriptInterface
        fun dataRoot(): String = runCatching {
            LivePanel.dataRootJson(requireContext().applicationContext)
        }.getOrDefault("""{"path":""}""")

        @JavascriptInterface
        fun mods(): String = runCatching { LivePanel.modsJson() }
            .getOrDefault("""{"items":[],"emptyReason":"ошибка чтения"}""")

        @JavascriptInterface
        fun saves(): String = runCatching { LivePanel.savesJson() }
            .getOrDefault("""{"items":[],"emptyReason":"ошибка чтения"}""")

        @JavascriptInterface
        fun suggestRoots(): String = runCatching {
            LivePanel.suggestRootsJson(requireContext().applicationContext)
        }.getOrDefault("""{"roots":[]}""")

        @JavascriptInterface
        fun pickFolder() {
            main.post {
                runCatching {
                    pickGamesFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                }
            }
        }

        @JavascriptInterface
        fun pickDataRoot() {
            main.post {
                runCatching {
                    pickDataFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                }
            }
        }

        @JavascriptInterface
        fun pickSavesFolder() {
            main.post {
                runCatching {
                    pickSavesFolderLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                }
            }
        }

        @JavascriptInterface
        fun saveSource(): String = runCatching { SaveSource.statusJson() }
            .getOrDefault("""{"path":"","titles":0}""")

        @JavascriptInterface
        fun clearSavesFolder() {
            SaveSource.configuredPath = null
        }

        @JavascriptInterface
        fun adoptSave(path: String, title: String): String = runCatching {
            val cached = org.yuzu.yuzu_emu.utils.GameHelper.cachedGameList
                .firstOrNull { it.path == path }
                ?: LivePanel.gameFrom(path, title)
            SaveSource.adoptFor(cached)
        }.getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun readText(path: String): String = runCatching {
            GameAddons.readText(path)
        }.getOrDefault("""{"ok":false,"reason":"ошибка чтения"}""")

        @JavascriptInterface
        fun prepareShaders(): String = runCatching {
            LivePanel.prepareShaders()
        }.getOrDefault("""{"ok":false,"note":"недоступно"}""")

        @JavascriptInterface
        fun plugins(): String = runCatching { PluginPack.listJson() }
            .getOrDefault("""{"items":[],"logs":[]}""")

        @JavascriptInterface
        fun pluginPayload(): String = runCatching { PluginPack.payloadJson() }
            .getOrDefault("""{"css":"","hide":[],"html":[]}""")

        @JavascriptInterface
        fun pickPlugin() {
            main.post {
                runCatching { pickPluginFiles.launch(arrayOf("*/*")) }
            }
        }

        @JavascriptInterface
        fun removePlugin(id: String): String = runCatching {
            PluginPack.remove(id)
        }.getOrDefault("""{"ok":false,"message":"не удалился"}""")

        @JavascriptInterface
        fun enablePlugin(id: String, on: Boolean): String = runCatching {
            PluginPack.setEnabled(id, on)
        }.getOrDefault("""{"ok":false,"message":"не переключился"}""")

        @JavascriptInterface
        fun converterItems(): String = runCatching { Converter.listJson() }
            .getOrDefault("""{"items":[]}""")

        @JavascriptInterface
        fun pickConvert() {
            main.post {
                runCatching { pickConvertFiles.launch(arrayOf("*/*")) }
            }
        }

        @JavascriptInterface
        fun deleteConverted(path: String): String = runCatching {
            Converter.delete(path)
        }.getOrDefault("""{"ok":false}""")

        /**
         * Распаковать файл прямо из библиотеки.
         *
         * Раньше «сжатый образ» был тупиком: строки в списке нет, а
         * Конвертер просил выбрать файл заново через системный пикер.
         * Теперь кнопка на карточке отдаёт тот самый документ, который
         * библиотека уже нашла.
         */
        @JavascriptInterface
        fun convertPath(path: String): String = runCatching {
            val uri = Uri.parse(path)
            Converter.enqueue(requireContext().applicationContext, listOf(uri))
        }.getOrDefault("""{"busy":false,"pending":0,"error":"не удалось поставить в очередь"}""")

        /** Почему из сотни файлов видна одна: цифры и причины словами. */
        @JavascriptInterface
        fun libraryReport(): String = runCatching {
            LivePanel.libraryReport(requireContext().applicationContext)
        }.getOrDefault("""{"stats":{"known":false},"headline":"нет данных","files":[]}""")

        @JavascriptInterface
        fun canOpen(path: String): Boolean = runCatching {
            Converter.canOpen(path)
        }.getOrDefault(false)

        @JavascriptInterface
        fun applyAaaMode(): String = runCatching {
            val modes = org.yuzu.yuzu_emu.utils.NativeSymbiosis.autoModes()
            val m = modes.firstOrNull { it.key == "aaa" }
                ?: return@runCatching org.json.JSONObject()
                    .put("ok", false).put("message", "режим AAA нет в этой сборке").toString()
            val n = org.yuzu.yuzu_emu.utils.NativeSymbiosis.applyAutoMode(m.enumValue)
            runCatching { org.yuzu.yuzu_emu.utils.NativeConfig.saveGlobalConfig() }
            org.json.JSONObject().put("ok", n > 0).put("applied", n)
                .put("message",
                    "Поздравляю: AAA минимум · $n настроек. 0.25x, без лишней RAM. " +
                        "Не обещает, что открытый мир поедет.")
                .put("where", "настройки эмулятора · resolution 0.25x")
                .toString()
        }.getOrDefault("""{"ok":false,"message":"не применилось"}""")

        @JavascriptInterface
        fun crashReport(): String = runCatching { CrashReport.buildJson() }
            .getOrDefault("""{"ok":false,"title":"отчёт не собрался"}""")

        @JavascriptInterface
        fun memory(): String = runCatching { LivePanel.memoryJson() }
            .getOrDefault("""{"leftMb":0,"warn":false}""")

        @JavascriptInterface
        fun keysOk(): Boolean = LivePanel.keysPresent()

        @JavascriptInterface
        fun presets(): String = runCatching { UserPresets.listJson() }
            .getOrDefault("""{"items":[]}""")

        @JavascriptInterface
        fun savePreset(name: String): String = runCatching { UserPresets.snapshot(name) }
            .getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun applyPreset(name: String): String = runCatching { UserPresets.apply(name) }
            .getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun removePreset(name: String): String = runCatching { UserPresets.remove(name) }
            .getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun convertQueue(): String = runCatching { Converter.queueJson() }
            .getOrDefault("""{"busy":false,"pending":0}""")

        @JavascriptInterface
        fun reloadInterface() {
            main.post {
                runCatching {
                    web?.loadUrl(LivePanel.panelUrl() + "&r=" + System.currentTimeMillis())
                }
            }
        }

        @JavascriptInterface
        fun rescan() {
            main.post {
                runCatching { gamesViewModel.reloadGames(false) }
            }
        }

        @JavascriptInterface
        fun icon(path: String): String = runCatching {
            LivePanel.iconJpeg(path)
        }.getOrDefault("")

        @JavascriptInterface
        fun shot(path: String): String = runCatching {
            LivePanel.shotJpeg(path)
        }.getOrDefault("")

        @JavascriptInterface
        fun cover(path: String): String = runCatching {
            LivePanel.coverJpeg(path)
        }.getOrDefault("")

        @JavascriptInterface
        fun openTools() {
            main.post {
                runCatching {
                    findNavController().navigate(org.yuzu.yuzu_emu.R.id.action_global_toolsFragment)
                }
            }
        }

        @JavascriptInterface
        fun openUtilities() {
            main.post {
                runCatching {
                    findNavController().navigate(org.yuzu.yuzu_emu.R.id.action_global_utilitiesFragment)
                }
            }
        }

        @JavascriptInterface
        fun openGameMenu(path: String) {
            if (path.isBlank()) return
            main.post {
                val cached = org.yuzu.yuzu_emu.utils.GameHelper.cachedGameList
                    .firstOrNull { it.path == path }
                    ?: LivePanel.gameFrom(path, "")
                runCatching {
                    findNavController().navigate(
                        HomeNavigationDirections.actionGlobalPerGamePropertiesFragment(cached)
                    )
                }
            }
        }

        @JavascriptInterface
        fun openSettings() {
            main.post {
                runCatching {
                    findNavController().navigate(
                        HomeNavigationDirections.actionGlobalSettingsActivity(
                            null,
                            org.yuzu.yuzu_emu.features.settings.model.Settings.MenuTag.SECTION_ROOT
                        )
                    )
                }
            }
        }


        @JavascriptInterface
        fun settings(): String = runCatching { LauncherSettings.json() }
            .getOrDefault("""{"ok":false,"toggles":[]}""")

        @JavascriptInterface
        fun setBool(key: String, on: Boolean): String = runCatching {
            LauncherSettings.setBool(key, on)
        }.getOrDefault("""{"ok":false,"message":"не записалось"}""")

        @JavascriptInterface
        fun setResolution(index: Int): String = runCatching {
            LauncherSettings.setResolution(index)
        }.getOrDefault("""{"ok":false,"message":"масштаб не записался"}""")

        @JavascriptInterface
        fun removeFolder(uri: String): String = runCatching {
            LivePanel.removeFolder(uri)
        }.getOrDefault("""{"ok":false,"message":"папка не убралась"}""")

        @JavascriptInterface
        fun shots(path: String, title: String): String = runCatching {
            LivePanel.shotsJson(path, title)
        }.getOrDefault("""{"items":[]}""")

        @JavascriptInterface
        fun spaces(): String = runCatching {
            Spaces.json(requireContext().applicationContext)
        }.getOrDefault("""{"current":"symbiosis","items":[]}""")

        @JavascriptInterface
        fun selectSpace(id: String): String = runCatching {
            Spaces.select(requireContext().applicationContext, id)
        }.getOrDefault("""{"ok":false,"message":"пространство не переключилось"}""")

        @JavascriptInterface
        fun installKenjiShell(): String = runCatching {
            Spaces.ensureKenjiPlugin(requireContext().applicationContext)
        }.getOrDefault("""{"ok":false,"message":"оболочка не встроилась"}""")

        @JavascriptInterface
        fun setPreferExternal(on: Boolean): String = runCatching {
            val ctx = requireContext().applicationContext
            Spaces.setPreferExternal(ctx, on)
            JSONObject().put("ok", true).put("preferExternal", on)
                .put("message", if (on) "игры Kenji уходят в их APK, если он стоит" else "игры Kenji идут в наш плеер")
                .toString()
        }.getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun openOfficialKenji(path: String): String = runCatching {
            Spaces.openOfficial(requireContext().applicationContext, path)
        }.getOrDefault("""{"ok":false,"message":"их APK не открылся"}""")

        @JavascriptInterface
        fun engines(): String = runCatching {
            LivePanel.enginesJson(requireContext().applicationContext)
        }.getOrDefault("""{"current":"eden","launch":"eden","items":[]}""")

        @JavascriptInterface
        fun selectEngine(id: String): String = runCatching {
            LivePanel.selectEngine(requireContext().applicationContext, id)
        }.getOrDefault("""{"ok":false,"message":"ядро не переключилось"}""")

        @JavascriptInterface
        fun cycleCpu(): String = runCatching {
            LauncherSettings.cycleCpuBackend()
        }.getOrDefault("""{"ok":false,"message":"CPU не переключился"}""")

        @JavascriptInterface
        fun openEngines() {
            main.post {
                runCatching {
                    findNavController().navigate(org.yuzu.yuzu_emu.R.id.action_global_enginesFragment)
                }
            }
        }

        @JavascriptInterface
        fun downloadEngine(id: String) {
            val ctx = context?.applicationContext ?: return
            val engine = org.yuzu.yuzu_emu.utils.EngineLoader.Engine.values()
                .firstOrNull { it.id == id } ?: return
            Thread({
                val result = org.yuzu.yuzu_emu.utils.EngineDownloader.download(ctx, engine) { done, total ->
                    val payload = JSONObject()
                        .put("id", id)
                        .put("done", done)
                        .put("total", total)
                        .toString()
                    main.post {
                        web?.evaluateJavascript(
                            "try{if(typeof onEngineProgress==='function')onEngineProgress($payload)}catch(e){}",
                            null
                        )
                    }
                }
                val payload = JSONObject()
                    .put("id", id)
                    .put("ok", result.ok)
                    .put("message", result.message)
                    .toString()
                main.post {
                    web?.evaluateJavascript(
                        "try{if(typeof onEngineDone==='function')onEngineDone($payload)}catch(e){}",
                        null
                    )
                }
            }, "kenji-dl").start()
        }

        @JavascriptInterface
        fun probeEngine(id: String) {
            val ctx = context?.applicationContext ?: return
            if (id != org.yuzu.yuzu_emu.utils.EngineLoader.Engine.KENJI.id) {
                val payload = JSONObject()
                    .put("id", id)
                    .put("ok", true)
                    .put("message", "основное ядро уже в приложении")
                    .toString()
                main.post {
                    web?.evaluateJavascript(
                        "try{if(typeof onEngineProbe==='function')onEngineProbe($payload)}catch(e){}",
                        null
                    )
                }
                return
            }
            org.yuzu.yuzu_emu.utils.KenjiProbeService.probe(ctx) { ok, message ->
                val payload = JSONObject()
                    .put("id", id)
                    .put("ok", ok)
                    .put("message", message)
                    .toString()
                main.post {
                    web?.evaluateJavascript(
                        "try{if(typeof onEngineProbe==='function')onEngineProbe($payload)}catch(e){}",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun removeEngine(id: String): String = runCatching {
            val ctx = requireContext().applicationContext
            val engine = org.yuzu.yuzu_emu.utils.EngineLoader.Engine.values()
                .firstOrNull { it.id == id }
                ?: return@runCatching JSONObject().put("ok", false).put("message", "нет такого ядра").toString()
            if (engine == org.yuzu.yuzu_emu.utils.EngineLoader.Engine.EDEN) {
                return@runCatching JSONObject().put("ok", false).put("message", "основное ядро встроено, его нельзя удалить").toString()
            }
            if (org.yuzu.yuzu_emu.utils.EnginePreference.selectedRaw(ctx) == engine) {
                org.yuzu.yuzu_emu.utils.EnginePreference.select(ctx, org.yuzu.yuzu_emu.utils.EngineLoader.Engine.EDEN)
            }
            val gone = org.yuzu.yuzu_emu.utils.EngineLoader.remove(ctx, engine)
            JSONObject().put("ok", gone)
                .put("message", if (gone) "второе ядро удалено с устройства" else "не удалось удалить файл ядра")
                .toString()
        }.getOrDefault("""{"ok":false,"message":"не удалилось"}""")

        @JavascriptInterface
        fun launch(path: String, title: String) {
            if (path.isBlank()) return
            main.post {
                val act = activity ?: return@post
                val cached = org.yuzu.yuzu_emu.utils.GameHelper.cachedGameList
                    .firstOrNull { it.path == path }
                val game = cached ?: LivePanel.gameFrom(path, title)
                // Сейв из выбранной папки кладём в NAND до запуска,
                // иначе игра рисует NEW GAME.
                runCatching { SaveSource.adoptFor(game) }
                runCatching { LivePanel.markPlayed(game.path) }
                if (Spaces.shouldHandOff(act)) {
                    val handed = Spaces.openOfficial(act, game.path)
                    val ok = runCatching { JSONObject(handed).optBoolean("ok") }.getOrDefault(false)
                    if (ok) return@post
                    android.widget.Toast.makeText(
                        act,
                        runCatching { JSONObject(handed).optString("message") }.getOrDefault("их APK не открылся"),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                val useKenji = LivePanel.shouldLaunchKenji(act)
                if (useKenji) {
                    runCatching {
                        act.startActivity(
                            org.yuzu.yuzu_emu.activities.KenjiPlayerActivity.intent(
                                act,
                                game.path,
                                game.title,
                                // Настройки считает этот процесс: :kenji не видит
                                // наше хранилище, prefs между процессами не общие.
                                runCatching {
                                    org.yuzu.yuzu_emu.utils.EngineStore.effectiveText(act)
                                }.getOrDefault("")
                            )
                        )
                    }.onFailure {
                        android.widget.Toast.makeText(
                            act,
                            "второй плеер не открылся: ${it.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@post
                }
                // Тот же путь, что у списка игр: отдельная Activity + extra.
                // navigate() из WebView-фрагмента молча не открывал игру.
                val launched = runCatching {
                    val intent = android.content.Intent(
                        act,
                        org.yuzu.yuzu_emu.activities.EmulationActivity::class.java
                    ).apply {
                        action = android.content.Intent.ACTION_VIEW
                        data = if (game.path.startsWith("/"))
                            android.net.Uri.fromFile(java.io.File(game.path))
                        else
                            android.net.Uri.parse(game.path)
                        putExtra("SelectedGame", game)
                        putExtra("game", game)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    act.startActivity(intent)
                }.isSuccess
                if (!launched) {
                    runCatching {
                        findNavController().navigate(
                            HomeNavigationDirections.actionGlobalEmulationActivity(game)
                        )
                    }
                }
            }
        }
    }
}
