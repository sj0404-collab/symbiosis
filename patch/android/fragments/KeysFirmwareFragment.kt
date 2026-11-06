// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.utils.ExtraKeys
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.databinding.FragmentKeysFirmwareBinding
import org.yuzu.yuzu_emu.utils.DirectoryInitialization
import org.yuzu.yuzu_emu.utils.KenjiBridge
import org.yuzu.yuzu_emu.utils.KenjiData
import org.yuzu.yuzu_emu.utils.TitleKeys
import org.yuzu.yuzu_emu.utils.FileUtil
import org.yuzu.yuzu_emu.utils.NativeSymbiosis

/**
 * Dedicated screen for encryption keys and firmware.
 *
 * The stock flow buries key installation in a generic "installables" list and
 * gives no feedback beyond a toast. This screen shows live status for both
 * keys and firmware, verifies what is actually on disk, and explains failures.
 */
class KeysFirmwareFragment : Fragment() {
    private var _binding: FragmentKeysFirmwareBinding? = null
    private val binding get() = _binding!!

    /** Отчёт о дополнительных файлах ключей; пересчитывается при входе. */
    private var extraKeysReport: String = ""

    private val keysDir: File?
        get() = DirectoryInitialization.userDirectory?.let { File(it, "keys") }

    private val getProdKey =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { installKey(it) }
        }

    /** Выбор файла с ключами игр: `0100… = 32hex`, по строке на игру. */
    private val getTitleKeys =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { installTitleKeys(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeysFirmwareBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarKeys.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        binding.buttonInstallKeys.setOnClickListener {
            getProdKey.launch(arrayOf("*/*"))
        }

        // Firmware install lives in the existing installables flow, which
        // already handles the ZIP extraction and NCA registration correctly.
        binding.buttonInstallFirmware.setOnClickListener {
            binding.root.findNavController()
                .navigate(R.id.action_global_installableFragment)
        }

        binding.buttonInstallTitleKeys.setOnClickListener {
            getTitleKeys.launch(arrayOf("*/*"))
        }
        binding.buttonAddTitleKey.setOnClickListener { askTitleKey() }

        binding.buttonVerify.setOnClickListener { verify() }
        binding.buttonReloadKeys.setOnClickListener { reloadKeys() }

        refreshStatus()
        setInsets()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        // Подхватить всё, что лежит в папке ключей, а не только prod.keys и
        // title.keys: ключ одной игры, положенный отдельным файлом, иначе не
        // видит никто — и файл жалуется на MissingTitlekey при рабочих
        // остальных играх.
        extraKeysReport = runCatching {
            keysDir?.let { ExtraKeys.apply(it) { NativeLibrary.reloadKeys() } }.orEmpty()
        }.getOrDefault("")

        val keysPresent = runCatching { NativeLibrary.areKeysPresent() }.getOrDefault(false)
        binding.textKeysStatus.text = if (keysPresent) {
            getString(R.string.keys_installed_ok)
        } else {
            getString(R.string.keys_missing)
        }
        binding.iconKeysStatus.setImageResource(
            if (keysPresent) R.drawable.ic_check_circle else R.drawable.ic_key
        )

        val firmwareAvailable =
            runCatching { NativeLibrary.isFirmwareAvailable() }.getOrDefault(false)
        val version = if (firmwareAvailable) {
            runCatching { NativeLibrary.firmwareVersion() }.getOrDefault("")
        } else {
            ""
        }
        binding.textFirmwareStatus.text = when {
            firmwareAvailable && version.isNotBlank() ->
                getString(R.string.firmware_installed_version, version)
            firmwareAvailable -> getString(R.string.firmware_installed)
            else -> getString(R.string.firmware_missing)
        }
        binding.iconFirmwareStatus.setImageResource(
            if (firmwareAvailable) R.drawable.ic_check_circle else R.drawable.ic_firmware
        )
    }

    private fun installKey(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val context = YuzuApplication.appContext
                    val name = FileUtil.getFilename(uri)
                    val dir = keysDir ?: return@runCatching -1
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                    // Preserve the original name: the core looks for the exact
                    // files prod.keys / title.keys.
                    val target = File(dir, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (NativeLibrary.reloadKeys()) 1 else 0
                }.getOrDefault(-1)
            }

            val message = when (result) {
                1 -> getString(R.string.keys_installed_ok)
                0 -> getString(R.string.keys_invalid)
                else -> getString(R.string.keys_install_failed)
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            refreshStatus()
        }
    }

    /**
     * Установить ключи игр из выбранного файла.
     *
     * Не замена `title.keys`, а дополнение: у каждой игры свой ключ, и файл
     * от человека почти всегда содержит одну-две строки. Прежние записи
     * остаются на месте — иначе одна новая игра стёрла бы остальные.
     */
    private fun installTitleKeys(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = keysDir ?: return@runCatching Installed(-1, 0)
                    dir.mkdirs()
                    val bytes = YuzuApplication.appContext.contentResolver
                        .openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    // Кодировку разбираем сами: `title.keys` бывает в UTF-16
                    // и с BOM, и прочитать его как UTF-8 — значит увидеть
                    // нули между буквами и решить, что файл пустой.
                    val text = TitleKeys.decode(bytes)
                    val lines = TitleKeys.keyLines(text)
                    // Ничего не разобрали — показываем, что именно лежит в
                    // файле, а не «нет строк». Иначе человек ищет ошибку в
                    // ключах, которых у него в порядке.
                    if (lines.isEmpty()) return@runCatching Installed(-2, 0, 0, text)
                    // Складываем как дополнительный файл: ядро всё равно
                    // читает только title.keys, а сведение делает ExtraKeys.
                    val store = File(dir, "title-keys-${System.currentTimeMillis()}.txt")
                    store.writeText(lines.joinToString("\n", postfix = "\n"))
                    val before = TitleKeys.ids(
                        runCatching { File(dir, "title.keys").readLines() }.getOrDefault(emptyList())
                    )
                    ExtraKeys.apply(dir) { NativeLibrary.reloadKeys() }
                    val after = TitleKeys.ids(TitleKeys.readAll(dir))
                    Installed(0, after.size - before.size)
                }.getOrElse { Installed(-1, 0) }
            }
            val message = when {
                outcome.code == -1 -> getString(R.string.keys_install_failed)
                outcome.code == -2 -> {
                    val survey = TitleKeys.survey(outcome.sample.lineSequence().toList())
                    when {
                        survey.withSeparator == 0 ->
                            "в файле нет ключей игр: ни одной строки с «=» или «:»"
                        survey.rejected.isNotEmpty() ->
                            "в файле " + survey.withSeparator + " строк с «=», но ни одна не похожа " +
                                "на ключ игры. Первая: " + survey.rejected.first()
                        else -> "в файле нет ключей игр"
                    }
                }
                outcome.added > 0 -> "ключей игр добавлено: ${outcome.added}"
                else -> "новых ключей нет: эти уже установлены"
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            refreshStatus()
        }
    }

    private data class Installed(
        val code: Int,
        val added: Int,
        /** Сколько строк с «=» было в файле — для честного отказа. */
        val seen: Int = 0,
        /** Начало файла без значений ключей: только для объяснения. */
        val sample: String = ""
    )

    /**
     * Вписать один ключ игры руками.
     *
     * Два поля, потому что вставляют по-разному: кто-то строкой
     * «0100… = 32hex», кто-то двумя значениями из блокнота. Разбираем оба.
     */
    private fun askTitleKey() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val idField = android.widget.EditText(context).apply {
            hint = "0100… — идентификатор игры"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val keyField = android.widget.EditText(context).apply {
            hint = "ключ игры, 32 hex"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        container.addView(idField)
        container.addView(keyField)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.add_title_key)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                addTitleKey(idField.text.toString(), keyField.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addTitleKey(rawId: String, rawKey: String) {
        // Вставка одной строкой: «0100… = 32hex» в первое поле.
        val single = rawId.trim()
        val (id, key) = if (single.contains('=')) {
            single.substringBefore('=') to single.substringAfter('=')
        } else {
            single to rawKey
        }
        val line = TitleKeys.manualLine(id, key)
        if (line == null) {
            Snackbar.make(
                binding.root,
                "не похоже на ключ игры: нужно 16 hex-знаков идентификатора и 32 — ключа",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = keysDir ?: return@runCatching false
                    dir.mkdirs()
                    val file = File(dir, "title.keys")
                    val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                    file.writeText(TitleKeys.upsert(lines, line).joinToString("\n", postfix = "\n"))
                    NativeLibrary.reloadKeys()
                }.getOrDefault(false)
            }
            Snackbar.make(
                binding.root,
                if (ok) "ключ игры добавлен: " + line.substringBefore(' ')
                else "ключ записан, но ядро не подтвердило перечитывание — перезапустите игру",
                Snackbar.LENGTH_LONG
            ).show()
            refreshStatus()
        }
    }

    private fun reloadKeys() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val reloaded = runCatching { NativeLibrary.reloadKeys() }.getOrDefault(false)
                // И сразу передать их второму ядру: у него своя папка, и без
                // этого «ключи установлены» относилось только к Eden. Человек
                // справедливо ждал, что установка ключей — это для всего
                // приложения, а не для одного из двух ядер.
                runCatching {
                    val context = requireContext().applicationContext
                    // Только если второе ядро вообще заведено (его папка
                    // существует): создавать чужие каталоги из экрана ключей
                    // незачем.
                    if (KenjiBridge.dataDir(context).isDirectory) {
                        KenjiBridge.seedKeys(context)
                    }
                }
                reloaded
            }
            Snackbar.make(
                binding.root,
                if (ok) R.string.keys_installed_ok else R.string.keys_invalid,
                Snackbar.LENGTH_SHORT
            ).show()
            refreshStatus()
        }
    }

    /**
     * Reports exactly what is on disk. Users overwhelmingly hit "keys don't
     * work" because the file is empty, truncated, or named wrongly -- none of
     * which the stock UI surfaces.
     */
    private fun verify() {
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { buildVerificationReport() }
            binding.textVerifyResult.text = report
            binding.cardVerifyResult.isVisible = true
        }
    }

    private fun buildVerificationReport(): String {
        val sb = StringBuilder()
        val dir = keysDir

        if (dir == null || !dir.exists()) {
            sb.append(getString(R.string.keys_dir_missing)).append('\n')
            return sb.toString()
        }

        sb.append("keys dir: ").append(dir.absolutePath).append("\n\n")

        val expected = listOf("prod.keys", "title.keys", "key_retail.bin")
        for (name in expected) {
            val file = File(dir, name)
            when {
                !file.exists() -> sb.append("[--] ").append(name).append(": not present\n")
                file.length() == 0L ->
                    sb.append("[!!] ").append(name).append(": present but EMPTY\n")
                else -> {
                    val lines = runCatching {
                        file.readLines().count { it.contains('=') }
                    }.getOrDefault(0)
                    sb.append("[ok] ").append(name)
                        .append(": ").append(file.length()).append(" bytes")
                    if (name.endsWith(".keys")) {
                        sb.append(", ").append(lines).append(" key entries")
                    }
                    sb.append('\n')
                }
            }
        }

        if (extraKeysReport.isNotBlank()) {
            sb.append("\n").append(extraKeysReport)
        }

        // Anything else the user dropped in, so stray/misnamed files are visible.
        val others = dir.listFiles()?.filter { it.name !in expected }.orEmpty()
        if (others.isNotEmpty()) {
            sb.append("\nother files:\n")
            others.forEach { sb.append("  ").append(it.name).append('\n') }
        }

        // Ключи игр — отдельная строка отчёта. 06.11 человек видел «204 ключа,
        // ключи на месте» и не мог понять, почему игра не открылась: 204 —
        // это ключи консоли, а ключ игры живёт в title.keys, и его там может
        // не быть вовсе.
        runCatching {
            val dir = keysDir
            sb.append("\nключи игр (title.keys и дополнительные файлы)\n")
            TitleKeys.report(TitleKeys.readAll(dir)).lineSequence().forEach {
                sb.append("  ").append(it).append('\n')
            }
        }

        // Второе ядро: у него своя папка данных, и ключи в неё передаются
        // отдельно. Без этой строки экран отвечал только за Eden, а человек
        // читал его как «ключи есть везде».
        runCatching {
            val context = requireContext().applicationContext
            val root = KenjiBridge.dataDir(context)
            sb.append("\nвторое ядро (Kenji)\n")
            sb.append("  папка данных: ").append(root.absolutePath).append('\n')
            // Второе ядро заводит свою папку заново (она теперь приватная),
            // и ключей там ещё нет. Передаём их прямо здесь: это две копии по
            // несколько килобайт, а человек иначе видит «prod.keys: нет» и
            // думает, что установка не сработала.
            if (!KenjiData.hasKeys(root)) {
                runCatching { KenjiBridge.seedKeys(context) }
            }
            sb.append("  prod.keys: ")
                .append(if (KenjiData.hasKeys(root)) "есть" else "нет")
                .append('\n')
            // Прошивка: ссылка на папку Eden или копия — это разные ответы, и
            // раньше здесь молчали, а в хранилище лежал второй комплект.
            val fw = KenjiData.firmwareDir(root)
            val linked = runCatching { android.system.Os.readlink(fw.absolutePath) }.getOrNull()
            sb.append("  прошивка: ")
            when {
                linked != null -> sb.append("ссылка на ").append(linked)
                KenjiData.hasFirmware(root) ->
                    sb.append("копия, ").append(KenjiData.firmwareCount(root)).append(" файлов")
                else -> sb.append("нет")
            }
            sb.append('\n')
            sb.append("  передать заново: «Перезагрузить ключи»\n")
        }

        sb.append("\ncore reports keys present: ")
            .append(runCatching { NativeLibrary.areKeysPresent() }.getOrDefault(false))
            .append('\n')
        sb.append("firmware available: ")
            .append(runCatching { NativeLibrary.isFirmwareAvailable() }.getOrDefault(false))
            .append('\n')

        return sb.toString()
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.appbarKeys.updatePadding(
                left = bars.left + cutout.left,
                top = bars.top,
                right = bars.right + cutout.right
            )
            binding.scrollKeys.updatePadding(
                left = bars.left + cutout.left,
                right = bars.right + cutout.right,
                bottom = bars.bottom
            )
            insets
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
