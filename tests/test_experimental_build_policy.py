"""Experimental APK publication stays separate from the normal release."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
class ExperimentalBuildPolicy(unittest.TestCase):
    def test_opt_in_and_release_isolation(self):
        text = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertIn('experimental_test_only:', text)
        self.assertIn("github.event_name != 'workflow_dispatch' || inputs.build_type != 'debug'", text)
        self.assertIn('if: ${{ !inputs.experimental_test_only }}', text)
        self.assertIn('--experimental-test-only', text)
        self.assertIn('success() && !inputs.experimental_test_only', text)
        self.assertIn('--prerelease --latest=false', text)
        self.assertIn('symbiosis-test-${GITHUB_RUN_NUMBER}', text)
        self.assertIn('известны заглушки', text)
        self.assertIn('APK_TLS_SHA256', text)
        self.assertIn('diagnosticSummary configurationReport symbiosis_effective_config', text)
if __name__ == '__main__': unittest.main()
