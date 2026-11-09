/* Disposable libnx software-keyboard diagnostic, never shipped in the APK.
 * Does not fabricate applet responses or require keys and firmware. */
#include <switch.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>

static void logline(const char *fmt, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0) return;
    svcOutputDebugString(buf, (size_t)n < sizeof(buf) ? (size_t)n : sizeof(buf)-1);
}
int main(void) {
    consoleInit(NULL);
    printf("Software keyboard diagnostic\n");
    consoleUpdate(NULL);
    svcSleepThread(2000000000LL);
    SwkbdConfig config;
    Result rc = swkbdCreate(&config, 0);
    logline("SWKBD_CREATE rc=0x%08x module=%u description=%u", rc,
            (unsigned)R_MODULE(rc), (unsigned)R_DESCRIPTION(rc));
    if (R_SUCCEEDED(rc)) {
        swkbdConfigMakePresetDefault(&config);
        swkbdConfigSetHeaderText(&config, "Diagnostic keyboard");
        swkbdConfigSetGuideText(&config, "Enter diagnostic text");
        swkbdConfigSetInitialText(&config, "Seed");
        swkbdConfigSetStringLenMin(&config, 1);
        swkbdConfigSetStringLenMax(&config, 24);
        char text[128] = {0};
        logline("SWKBD_SHOW_BEGIN");
        rc = swkbdShow(&config, text, sizeof(text));
        logline("SWKBD_SHOW_RETURN rc=0x%08x module=%u description=%u text=%s", rc,
                (unsigned)R_MODULE(rc), (unsigned)R_DESCRIPTION(rc), text);
        // OutputDebugString may render non-ASCII as question marks. Report
        // actual returned bytes in hex, not an inferred Unicode string.
        char hex[sizeof(text) * 2 + 1];
        size_t length = strnlen(text, sizeof(text));
        static const char digits[] = "0123456789abcdef";
        for (size_t i = 0; i < length; ++i) {
            unsigned char byte = (unsigned char)text[i];
            hex[2*i] = digits[byte >> 4]; hex[2*i+1] = digits[byte & 15];
        }
        hex[2*length] = 0;
        logline("SWKBD_HEX len=%u value=%s", (unsigned)length, hex);
        if (R_SUCCEEDED(rc)) {
            char second[128] = {0};
            logline("SWKBD_SECOND_BEGIN");
            Result second_rc = swkbdShow(&config, second, sizeof(second));
            Result expected_cancel = MAKERESULT(Module_Libnx, LibnxError_LibAppletBadExit);
            logline("SWKBD_SECOND_RETURN rc=0x%08x expected_cancel=0x%08x cancel_match=%u text=%s",
                second_rc, expected_cancel, (unsigned)(second_rc == expected_cancel), second);
        }
        swkbdClose(&config);
    }
    logline("SWKBD_GUEST_DONE");
    while (appletMainLoop()) {
        consoleUpdate(NULL);
        svcSleepThread(16666667);
    }
    consoleExit(NULL);
    return 0;
}
