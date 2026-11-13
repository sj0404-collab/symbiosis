# SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import base64
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
read = lambda p: (ROOT / p).read_text()

class CoreUpdateWiringTest(unittest.TestCase):
    def test_test_only_and_integrated(self):
        update = read('patch/android/utils/CoreTestUpdates.kt')
        loader = read('patch/android/utils/EngineLoader.kt')
        screen = read('patch/android/fragments/EnginesFragment.kt')
        store = read('patch/android/utils/CoreUpdateStore.kt')
        net = read('patch/android/utils/CoreUpdateNetwork.kt')
        self.assertIn('fun enabled(): Boolean = BuildConfig.DEBUG', update)
        self.assertIn('if (CoreTestUpdates.enabled())', loader)
        self.assertIn('CoreUpdateStore.active(context.filesDir', loader)
        self.assertIn('CoreUpdateStore.hasSignedVersion', loader)
        self.assertIn('CoreTestUpdates.install(ctx)', screen)
        self.assertIn('CoreTestUpdates.tail(ctx)', screen)
        self.assertIn('KernelLogFormat.tailOf', screen)
        self.assertIn('GameSessionLock.tryAcquire', store)
        self.assertIn('SignedCoreManifest.matches(candidate, file)', store)
        self.assertIn('CoreElfCheck.error(file, page)', update)
        self.assertIn('url.protocol == "https"', net)
        self.assertNotIn('openConnection()', update) # update log is never uploaded
        self.assertIn('Не отправляются автоматически'.lower(), read('patch/android/utils/Manual.kt').lower())
        public = re.search(r'private const val PUBLIC_KEY = "([A-Za-z0-9+/=]+)"', update).group(1)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'key.der';path.write_bytes(base64.b64decode(public,validate=True))
            result=subprocess.run(['openssl','pkey','-pubin','-inform','DER','-in',str(path),'-noout','-text'],capture_output=True,check=True,text=True)
            self.assertIn('3072 bit', result.stdout)

    def test_separate_publish_workflow(self):
        workflow = read('.github/workflows/publish-core-test.yml')
        self.assertIn('engine-test-$SEQUENCE', workflow)
        self.assertIn('docs/core-test/current.txt', workflow)
        self.assertIn('verify_core_manifest.py', workflow)
        self.assertIn('cmp /tmp/pinned-public.der /tmp/signing-public.der', workflow)
        self.assertIn('--prerelease', workflow)
        self.assertNotIn('symbiosis-latest', workflow)
        self.assertNotIn('release delete', workflow)

if __name__ == '__main__': unittest.main()
