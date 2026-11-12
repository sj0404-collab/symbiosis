/* Disposable HID observation on a real pinned core; not a game or release. */
#include <switch.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdint.h>

static void record(const char *format, ...) {
    char buffer[256]; va_list args; va_start(args, format);
    int n = vsnprintf(buffer, sizeof(buffer), format, args); va_end(args);
    if (n > 0) svcOutputDebugString(buffer, (size_t)n < sizeof(buffer) ? (size_t)n : sizeof(buffer)-1);
}
int main(void) {
    consoleInit(NULL);
    record("HID_LONG_BEGIN");
    padConfigureInput(1, HidNpadStyleSet_NpadStandard);
    PadState pad;
    padInitializeDefault(&pad);
    for (unsigned second = 0; second <= 22; second++) {
        padUpdate(&pad);
        record("HID_LONG_SAMPLE second=%u connected=%u pad_style=0x%08x style0=0x%08x buttons=0x%016llx",
               second, (unsigned)padIsConnected(&pad), padGetStyleSet(&pad),
               hidGetNpadStyleSet(HidNpadIdType_No1), (unsigned long long)padGetButtons(&pad));
        if (second == 18) {
            /* These valid IDs mirror the late player-slot range visible in the
             * device log, but this is not the game's actual IPC or execution. */
            const HidNpadIdType ids[] = {HidNpadIdType_No4, HidNpadIdType_No5,
                HidNpadIdType_No6, HidNpadIdType_No7, HidNpadIdType_No8};
            for (unsigned i = 0; i < sizeof(ids) / sizeof(ids[0]); i++) {
                Event ev = {0};
                Result rc = hidAcquireNpadStyleSetUpdateEventHandle(ids[i], &ev, true);
                record("HID_LONG_ACQUIRE slot=%u rc=0x%08x module=%u description=%u",
                       i + 4, rc, (unsigned)R_MODULE(rc), (unsigned)R_DESCRIPTION(rc));
                if (R_SUCCEEDED(rc)) eventClose(&ev);
            }
        }
        if (second < 22) svcSleepThread(1000000000LL);
    }
    record("HID_LONG_COMPLETE");
    /* Keep the guest alive so the real player exit path can be exercised. */
    for (;;) { consoleUpdate(NULL); svcSleepThread(16666667LL); }
}
