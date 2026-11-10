#!/usr/bin/env python3
"""Add stage breadcrumbs to the pinned upstream deko_basic diagnostic copy.

Only the throwaway CI source tree is modified; rendering commands and shaders
are unchanged. No upstream binary or debug guest is added to the release APK.
"""
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
changes = {
    '#include <deko3d.h>': '''#include <deko3d.h>
static void ciLog(const char *s) { svcOutputDebugString(s, strlen(s)); }''',
    'FILE* f = fopen(path, "rb");': '''FILE* f = fopen(path, "rb");
    if (!f) { ciLog("DEKO_CI_SHADER_FILE_MISSING"); exit(2); }
    ciLog("DEKO_CI_SHADER_FILE_OPEN");''',
    '    dkShaderInitialize(pShader, &shaderMaker);': '''    dkShaderInitialize(pShader, &shaderMaker);
    ciLog("DEKO_CI_SHADER_INITIALIZED");''',
    '    g_device = dkDeviceCreate(&deviceMaker);': '''    g_device = dkDeviceCreate(&deviceMaker);
    if (!g_device) { ciLog("DEKO_CI_DEVICE_FAILED"); exit(3); }
    ciLog("DEKO_CI_DEVICE_CREATED");''',
    '    g_renderQueue = dkQueueCreate(&queueMaker);': '''    g_renderQueue = dkQueueCreate(&queueMaker);
    if (!g_renderQueue) { ciLog("DEKO_CI_QUEUE_FAILED"); exit(4); }
    ciLog("DEKO_CI_QUEUE_CREATED");''',
    '    dkQueuePresentImage(g_renderQueue, g_swapchain, slot);': '''    dkQueuePresentImage(g_renderQueue, g_swapchain, slot);
    static int frames;
    if (++frames == 1) ciLog("DEKO_CI_FIRST_PRESENT_RETURNED");
    if (frames == 60) ciLog("DEKO_CI_60_PRESENTS_RETURNED");''',
    '    romfsInit();': '''    ciLog("DEKO_CI_START");
    Result romfs = romfsInit();
    if (R_FAILED(romfs)) { ciLog("DEKO_CI_ROMFS_FAILED"); return 5; }
    ciLog("DEKO_CI_ROMFS_OK");''',
}
for old, new in changes.items():
    if s.count(old) != 1:
        raise SystemExit('pinned source drift: ' + old[:80])
    s = s.replace(old, new, 1)
p.write_text(s)
