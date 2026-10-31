// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentDiagnosticsBinding
import org.yuzu.yuzu_emu.utils.ExitTrace
import org.yuzu.yuzu_emu.utils.NativeSymbiosis
import org.yuzu.yuzu_emu.utils.SymbiosisStrings

/**
 * Built-in diagnostics.
 *
 * Replaces the need for a separate homebrew .nro: every check that a test ROM
 * would perform (driver state, memory headroom, capability coverage) runs
 * inside the app and reports in plain language.
 */
class DiagnosticsFragment : Fragment() {
    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!

    private var lastReport: String = ""

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
        _binding = FragmentDiagnosticsBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarDiagnostics.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        binding.buttonRunSelftest.setOnClickListener { runSelfTest() }

        binding.buttonDriverTopology.setOnClickListener {
            showSection(R.string.driver_topology) { NativeSymbiosis.getDriverTopology() }
        }
        binding.buttonMemoryState.setOnClickListener {
            showSection(R.string.memory_state) { NativeSymbiosis.getMemoryState() }
        }
        binding.buttonShimReport.setOnClickListener {
            showSection(R.string.symbol_borrowing) { NativeSymbiosis.getShimReport() }
        }
        binding.buttonLayerLog.setOnClickListener {
            // Everything the layer decided this session, newest last. This is
            // what makes a bug report actionable.
            showSection(R.string.layer_log) {
                NativeSymbiosis.logDump(null, NativeSymbiosis.LogLevel.Debug)
            }
        }
        binding.buttonClearLog.setOnClickListener {
            runCatching { NativeSymbiosis.clearLog() }
            Snackbar.make(binding.root, R.string.log_cleared, Snackbar.LENGTH_SHORT).show()
        }
        // Reachable after the game has gone. The overlay button dies with the
        // emulation activity, which is precisely the case that matters when a
        // launch fails within seconds.
        binding.buttonLaunchReport.setOnClickListener {
            LaunchAuditDialogFragment().show(
                childFragmentManager,
                LaunchAuditDialogFragment.TAG
            )
        }
        binding.buttonMaliReport.setOnClickListener {
            showSection(R.string.mali_report) {
                NativeSymbiosis.getMaliReport().ifBlank {
                    "This device does not use a Mali GPU, so Mali-specific tuning does not apply."
                }
            }
        }
        binding.buttonDriverOptions.setOnClickListener {
            showSection(R.string.driver_suggestions) {
                buildString {
                    NativeSymbiosis.driverSuggestions().forEach { d ->
                        append(if (d.isSystem) "[in use] " else "[option] ")
                        append(d.name).append('\n')
                        append("  ").append(d.description).append('\n')
                        append("  ").append(d.verdict).append('\n')
                        if (d.url.isNotBlank()) append("  ").append(d.url).append('\n')
                        append('\n')
                    }
                }
            }
        }
        binding.buttonFirmwareHelp.setOnClickListener {
            showSection(R.string.firmware_help) { NativeSymbiosis.getFirmwareAdvice() }
        }
        binding.buttonCopyReport.setOnClickListener { copyReport() }

        // Surface safe mode prominently: without this the user has no way to
        // know the layer switched itself off, or to turn it back on.
        val safeMode = runCatching { NativeSymbiosis.isSafeMode() }.getOrDefault(false)
        binding.cardSafeMode.isVisible = safeMode
        binding.buttonClearSafeMode.setOnClickListener {
            runCatching { NativeSymbiosis.clearSafeMode() }
            binding.cardSafeMode.isVisible = false
            Snackbar.make(binding.root, R.string.safe_mode_cleared, Snackbar.LENGTH_LONG).show()
        }

        // След последних действий показывается сразу при открытии, если он
        // есть: «краш при выходе из игры» без него — это симптом без адреса.
        runCatching {
            val trace = ExitTrace.report()
            if (trace.isNotBlank() && _binding != null) {
                lastReport = trace
                binding.textReport.text = getString(R.string.exit_trace_title) + "\n\n" + trace
                binding.cardReport.isVisible = true
            }
        }

        setInsets()
    }

    private fun runSelfTest() {
        binding.progressSelftest.isVisible = true
        binding.buttonRunSelftest.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            // Native checks read /proc and walk driver tables; keep them off
            // the main thread even though they are fast.
            val report = withContext(Dispatchers.IO) {
                runCatching { NativeSymbiosis.runSelfTest() }
                    .getOrElse { "Self-test unavailable: ${it.message}" }
            }
            val counts = withContext(Dispatchers.IO) { NativeSymbiosis.selfTestCounts() }

            lastReport = report
            binding.textReport.text = report
            binding.cardReport.isVisible = true

            binding.containerSummary.isVisible = true
            binding.chipPassed.text = getString(R.string.n_passed, counts.passed)
            binding.chipWarned.text = getString(R.string.n_warnings, counts.warned)
            binding.chipFailed.text = getString(R.string.n_failed, counts.failed)

            binding.progressSelftest.isVisible = false
            binding.buttonRunSelftest.isEnabled = true
        }
    }

    private fun showSection(titleId: Int, provider: () -> String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching(provider).getOrElse { "Unavailable: ${it.message}" }
            }
            // Reports are built natively from many fragments; translate the
            // labels a user scans for and leave technical values intact.
            val body = SymbiosisStrings.localiseReport(
                requireContext(),
                text.ifBlank { getString(R.string.no_data_yet) }
            )
            lastReport = body
            binding.textReport.text = "${getString(titleId)}\n\n$body"
            binding.cardReport.isVisible = true
        }
    }

    private fun copyReport() {
        if (lastReport.isBlank()) {
            Snackbar.make(binding.root, R.string.nothing_to_copy, Snackbar.LENGTH_SHORT).show()
            return
        }
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Eden diagnostics", lastReport))
        Snackbar.make(binding.root, R.string.report_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.appbarDiagnostics.updatePadding(
                left = bars.left + cutout.left,
                top = bars.top,
                right = bars.right + cutout.right
            )
            binding.scrollDiagnostics.updatePadding(
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
