# SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import unittest
R = Path(__file__).resolve().parents[1]
class BenchmarkWiring(unittest.TestCase):
    def test_long_press_uses_a_visible_custom_view_not_a_message_and_list(self):
        s = (R/'patch/android/fragments/NativeLauncherFragment.kt').read_text()
        m = s.split('private fun onItemLongClick(',1)[1].split('private fun openSaves(',1)[0]
        for text in ['"Eden"', '"Авто"', '"Kenji"', 'setView(scroll)',
                     'EngineStore.setAuto', 'EngineStore.setCoreFor', '"Запустить"', 'openSaves(game)', 'openProperties(game)']:
            self.assertIn(text,m)
        self.assertNotIn('.setItems(',m)
        self.assertNotIn('.setMessage(',m)
        self.assertIn('нет замеров обоих ядер',m)
        self.assertIn('group.check(savedId)',m)
        tile=(R/'patch/android/adapters/LauncherTileAdapter.kt').read_text()
        self.assertIn('holder.cover.setOnLongClickListener(openGameActions)', tile)
        self.assertIn('holder.cover.setOnClickListener { onClick(item) }', tile)
    def test_both_player_sessions_are_sampled_and_neither_launches_extra_games(self):
        eden=(R/'patch/android/fragments/EmulationFragment.kt').read_text()
        kenji=(R/'patch/android/activities/KenjiPlayerActivity.kt').read_text()
        self.assertIn('startPassiveBenchmark()', eden)
        self.assertIn('NativeLibrary.getPerfStats().getOrNull(1)',eden)
        self.assertIn('Core.EDEN, sample',eden)
        self.assertIn('Core.KENJI, sample',kenji)
        self.assertIn('benchmarkHasFocus',kenji)
        self.assertIn('benchmarkFocusEpoch.incrementAndGet()', kenji)
        self.assertIn('benchmark?.reset()', kenji)
        self.assertIn('activity?.hasWindowFocus() == true', eden)
        self.assertIn('recorder?.reset()', eden)
        algorithm=(R/'patch/android/utils/CoreBenchmark.kt').read_text()
        self.assertIn('if (eden == null || kenji == null) return null',algorithm)
        self.assertIn('WARMUP_MS = 30_000L',algorithm)
        self.assertIn('MIN_SAMPLES = 10',algorithm)
        self.assertNotIn('startActivity(', algorithm)
    def test_auto_mode_is_per_game_and_no_data_uses_global(self):
        store=(R/'patch/android/utils/EngineStore.kt').read_text()
        self.assertIn('if (perGame == "auto")',store)
        self.assertIn('.choice ?: default',store)
        self.assertIn('fun setAuto(context: Context, path: String)',store)
        self.assertIn('fun isAuto(context: Context, path: String)',store)
    def test_fallback_preserves_auto_and_never_hands_game_to_external_app(self):
        fallback=(R/'patch/android/utils/CoreFallback.kt').read_text()
        self.assertIn('if (EngineStore.isAuto(context, path))', fallback)
        launcher=(R/'patch/android/fragments/NativeLauncherFragment.kt').read_text()
        launch=launcher.split('private fun launchOn(',1)[1].split('private fun configFor(',1)[0]
        self.assertIn('if (!EngineStore.isAuto(appCtx, game.path))', launch)
        self.assertNotIn('Spaces.openOfficial', launch)
        player=(R/'patch/android/activities/KenjiPlayerActivity.kt').read_text()
        self.assertNotIn('EngineStore.setCoreFor(appContext, romPath', player)
if __name__ == '__main__': unittest.main()
