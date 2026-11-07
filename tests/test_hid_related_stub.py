#!/usr/bin/env python3
"""Source-level test of a pinned RELATED HID service, not the user's binary.

RELATED_HID_SOURCE must point to IHidServer.cs from the pinned mirror. This
can constrain hypotheses, not establish which code the shipped core executes.
"""
import hashlib
import os
from pathlib import Path
import re
import unittest

EXPECTED_SHA = '5a39f03375fffb4a35cfb93ed7bbac5a404181029ea068e9290843b5e8423df2'


@unittest.skipUnless(os.environ.get('RELATED_HID_SOURCE'), 'set RELATED_HID_SOURCE')
class RelatedHidStubTest(unittest.TestCase):
    def test_command_106_reads_but_does_not_use_two_logged_arguments(self):
        data = Path(os.environ['RELATED_HID_SOURCE']).read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), EXPECTED_SHA)
        source = data.decode('utf-8')
        body = source.split('public ResultCode AcquireNpadStyleSetUpdateEventHandle(ServiceCtx context)', 1)[1]
        body = body.split('[CommandCmif(107)]', 1)[0]
        self.assertIn('ReadInt32()', body)
        self.assertIn('long appletResourceUserId = context.RequestData.ReadInt64();', body)
        self.assertIn('long npadStyleSet = context.RequestData.ReadInt64();', body)
        self.assertIn('GetStyleSetUpdateEvent(npadId)', body)
        self.assertIn('evnt.ReadableEvent.Signal()', body)
        self.assertIn('return ResultCode.Success;', body)
        # Ensure the two strange logged values are not used elsewhere in this
        # exact method, not merely that the log statement exists.
        without_log = re.sub(r'Logger\.Stub\?\.PrintStub\([^;]+;', '', body)
        self.assertEqual(without_log.count('appletResourceUserId'), 1)
        self.assertEqual(without_log.count('npadStyleSet'), 1)
        self.assertIn('throw new InvalidOperationException("Out of handles!")', body)


if __name__ == '__main__':
    unittest.main()
