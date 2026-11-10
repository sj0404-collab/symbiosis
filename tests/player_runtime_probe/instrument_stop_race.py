#!/usr/bin/env python3
"""Add observable stop triggers only to a disposable debug player tree.

The real stop, response and close code remain production code. The receiver is
never packaged in release; optional delay stresses cancellation ordering.
"""
from pathlib import Path
import sys

path = Path(sys.argv[1])
s = path.read_text()
changes = {
    '    private var uiSession: KenjiUiSession? = null\n': '''    private var uiSession: KenjiUiSession? = null
    private var ciReceiverRegistered = false
    private val ciStopReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            android.util.Log.i("PlayerStopCI", "CI_STOP_TRIGGERED")
            leave()
        }
    }
''',
    '        uiSession = ui\n': '''        uiSession = ui
        if (intent.getBooleanExtra("ci_test_stop_probe", false)) {
            val filter = android.content.IntentFilter("dev.symbiosis.CI_STOP_PENDING_UI")
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(ciStopReceiver, filter, RECEIVER_EXPORTED)
            else { @Suppress("DEPRECATION") registerReceiver(ciStopReceiver, filter) }
            ciReceiverRegistered = true
            android.util.Log.i("PlayerStopCI", "CI_STOP_RECEIVER_READY")
        }
''',
    '            present = { id, request -> presenter.show(id, request) },\n': '''            present = { id, request ->
                presenter.show(id, request)
                android.util.Log.i("PlayerStopCI", "CI_UI_PRESENTED type=${request.type}")
            },
''',
    '            respond = { generation, accepted, bytes -> KenjiBridge.respondUi(generation, accepted, bytes) },\n': '''            respond = { generation, accepted, bytes ->
                if (!accepted && intent.getBooleanExtra("ci_delay_cancel", false)) {
                    android.util.Log.i("PlayerStopCI", "CI_CANCEL_DELAY_BEGIN")
                    Thread.sleep(4000)
                }
                KenjiBridge.respondUi(generation, accepted, bytes).also {
                    android.util.Log.i("PlayerStopCI", "CI_REPLY_RETURNED accepted=$accepted ok=$it")
                }
            },
''',
    '        uiSession?.stop()\n        stopThread?.let { return it }': '''        android.util.Log.i("PlayerStopCI", "CI_STOP_REQUESTED")
        uiSession?.stop()
        stopThread?.let { return it }''',
    '                        val signalled = KenjiBridge.signalPlayStop()\n': '''                        android.util.Log.i("PlayerStopCI", "CI_SIGNAL_BEGIN")
                        val signalled = KenjiBridge.signalPlayStop()
''',
    '                        worker?.join()\n                        // Drain callbacks': '''                        worker?.join()
                        android.util.Log.i("PlayerStopCI", "CI_RENDER_JOINED")
                        // Drain callbacks''',
    '!KenjiBridge.closePlay())': '''!KenjiBridge.closePlay().also { android.util.Log.i("PlayerStopCI", "CI_CLOSE_RETURNED ok=$it") })''',
    '    override fun onDestroy() {\n        val lease = sessionLease': '''    override fun onDestroy() {
        if (ciReceiverRegistered) { unregisterReceiver(ciStopReceiver); ciReceiverRegistered = false }
        val lease = sessionLease''',
    '                    android.os.Process.killProcess(android.os.Process.myPid())': '''                    android.util.Log.i("PlayerStopCI", "CI_ISOLATED_PROCESS_EXIT")
                    android.os.Process.killProcess(android.os.Process.myPid())''',
}
for old, new in changes.items():
    if s.count(old) != 1:
        raise SystemExit('unexpected player layout, refusing to instrument: ' + old[:100])
    s = s.replace(old, new, 1)
path.write_text(s)
