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

    private fun reloadKeys() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { NativeLibrary.reloadKeys() }.getOrDefault(false)
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
