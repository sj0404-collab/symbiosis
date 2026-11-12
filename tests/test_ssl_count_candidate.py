"""Contract tests for source-only SSL connection-count candidate, not binary evidence."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
class CountCandidateTest(unittest.TestCase):
    def test_research_patch_tracks_publication_and_once_only_disposal(self):
        patch = (ROOT / 'tests/source_core_probe/ssl-connection-count-research.patch').read_text()
        self.assertIn('+            Interlocked.Increment(ref _connectionCount);', patch)
        self.assertIn('+                MakeObject(context, connection);', patch)
        self.assertIn('+                connection.Dispose();', patch)
        self.assertIn('+            context.ResponseData.Write((uint)Volatile.Read(ref _connectionCount));', patch)
        self.assertIn('+            Interlocked.Decrement(ref _connectionCount);', patch)
        self.assertIn('+            if (Interlocked.Exchange(ref _disposed, 1) != 0)', patch)
        self.assertIn('+                _onDisposed();', patch)
        self.assertLess(patch.index('+            Interlocked.Increment'), patch.index('+                MakeObject'))
        self.assertLess(patch.index('+            if (Interlocked.Exchange'), patch.index('+                _onDisposed();'))
    def test_candidate_is_opt_in_and_separate_from_pinned_cores(self):
        workflow = (ROOT / '.github/workflows/kenji-player-runtime.yml').read_text()
        self.assertIn('SSL_COUNT_REQUIRES_SOURCE_TLS_AND_COUNT_PROBE', workflow)
        self.assertIn('ssl-connection-count-research.patch', workflow)
        self.assertIn('EXPERIMENTAL_SSL_COUNT_PATCH_APPLIED_NOT_RELEASE', workflow)
        guard = (ROOT / 'tools/check_kenji_release_ready.py').read_text()
        self.assertIn('SSL-служба обоих закреплённых ядер', guard)
if __name__ == '__main__': unittest.main()
