#!/usr/bin/env python3
"""Source wiring checks: Android UI/device behaviour still requires device tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = (ROOT / 'patch/android/fragments/EnginesFragment.kt').read_text()
STORE = (ROOT / 'patch/android/utils/EngineStore.kt').read_text()


class SettingsScreenContract(unittest.TestCase):
    def setUp(self):
        render = SCREEN.split('private fun render()', 1)[1].split(
            '\n    /**\n     * Выбор папки данных:', 1)[0]
        self.render = render
        self.assertIn('val selectedCore = EngineStore.globalCore(ctx)', render)
        branch = render.split('if (selectedCore == EngineHybrid.Core.KENJI) {', 1)[1]
        self.kenji, self.eden = branch.split('\n        } else {', 1)

    def test_only_kenji_branch_shows_kenji_settings_and_data(self):
        for part in ('EngineStore.rows(ctx)', 'EngineStore.summary(ctx)',
                     'EngineStore.resetFields(ctx)', 'showDataDirDialog()',
                     'KenjiBridge.setDataDir(ctx, null)'):
            self.assertIn(part, self.kenji)
            self.assertNotIn(part, self.eden)

    def test_only_eden_branch_opens_eden_settings(self):
        self.assertIn('HomeNavigationDirections.actionGlobalSettingsActivity(', self.eden)
        self.assertIn('Settings.MenuTag.SECTION_ROOT', self.eden)
        self.assertNotIn('actionGlobalSettingsActivity(', self.kenji)

    def test_core_selector_is_visible_before_branches(self):
        self.assertLess(self.render.index('root.addView(cores)'),
                        self.render.index('val selectedCore'))
        self.assertIn('EngineStore.setGlobalCore(ctx, EngineHybrid.Core.EDEN)', self.render)
        self.assertIn('EngineStore.setGlobalCore(ctx, EngineHybrid.Core.KENJI)', self.render)
        self.assertIn('if (!kenjiUsable(ctx))', self.render)

    def test_selection_is_not_a_reset_or_per_game_write(self):
        select = STORE.split('fun setGlobalCore(context:', 1)[1].split(
            '\n    /**', 1)[0]
        self.assertIn('EnginePreference.select(context, engine)', select)
        for destructive in ('resetFields', 'setCoreFor', 'clearCoreFor', '.clear('):
            self.assertNotIn(destructive, select)

    def test_screen_reuses_existing_engine_controls(self):
        self.assertIn('for (engine in EngineLoader.Engine.values())', self.render)
        self.assertIn('root.addView(engineCard(engine))', self.render)
        self.assertIn('render()', self.render.split('val cores =', 1)[1])
        self.assertIn('Ядро для отдельной игры', self.eden)


if __name__ == '__main__':
    unittest.main()
