/* Concurrent guest error applet and software keyboard; test-only NRO. */
#include <switch.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
static _Atomic int ready, go, done;
static void logline(const char *fmt, ...) {
    char buf[256]; va_list ap; va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
    if (n >= 0) svcOutputDebugString(buf, (size_t)n < sizeof(buf) ? (size_t)n : sizeof(buf)-1);
}
static void message(void *unused) {
    (void)unused;
    ErrorApplicationConfig config;
    Result rc = errorApplicationCreate(&config, "Concurrent diagnostic message", NULL);
    logline("MIX_MESSAGE_CREATE rc=0x%08x", rc);
    atomic_fetch_add(&ready, 1);
    while (!atomic_load(&go)) svcSleepThread(1000000);
    if (R_SUCCEEDED(rc)) {
        logline("MIX_MESSAGE_BEGIN");
        rc = errorApplicationShow(&config);
        logline("MIX_MESSAGE_RETURN rc=0x%08x", rc);
    }
    atomic_fetch_add(&done, 1);
}
static void keyboard(void *unused) {
    (void)unused;
    SwkbdConfig config; Result rc = swkbdCreate(&config, 0);
    logline("MIX_KEYBOARD_CREATE rc=0x%08x", rc);
    if (R_SUCCEEDED(rc)) {
        swkbdConfigMakePresetDefault(&config);
        swkbdConfigSetHeaderText(&config, "Concurrent input");
        swkbdConfigSetInitialText(&config, "SeedB");
        swkbdConfigSetStringLenMin(&config, 1); swkbdConfigSetStringLenMax(&config, 24);
    }
    atomic_fetch_add(&ready, 1);
    while (!atomic_load(&go)) svcSleepThread(1000000);
    // Give the message a head start so its UI can remain open when input arrives.
    svcSleepThread(500000000LL);
    if (R_SUCCEEDED(rc)) {
        char text[128] = {0};
        logline("MIX_KEYBOARD_BEGIN");
        rc = swkbdShow(&config, text, sizeof(text));
        logline("MIX_KEYBOARD_RETURN rc=0x%08x text=%s", rc, text);
        swkbdClose(&config);
    }
    atomic_fetch_add(&done, 1);
}
int main(void) {
    consoleInit(NULL); printf("Concurrent message and keyboard diagnostic\n"); consoleUpdate(NULL);
    svcSleepThread(2000000000LL);
    Thread a={0}, b={0}; Result rc = threadCreate(&a, message, NULL, NULL, 0x10000, 0x2c, -2);
    if (R_SUCCEEDED(rc)) rc = threadCreate(&b, keyboard, NULL, NULL, 0x10000, 0x2c, -2);
    if (R_SUCCEEDED(rc)) rc = threadStart(&a);
    if (R_SUCCEEDED(rc)) rc = threadStart(&b);
    if (R_FAILED(rc)) { logline("MIX_SETUP_FAILED rc=0x%08x", rc); goto end; }
    while (atomic_load(&ready) != 2) svcSleepThread(1000000);
    logline("MIX_BOTH_READY"); atomic_store(&go, 1);
    while (atomic_load(&done) != 2) { consoleUpdate(NULL); svcSleepThread(16666667); }
    threadWaitForExit(&a); threadWaitForExit(&b); threadClose(&a); threadClose(&b);
    logline("MIX_BOTH_DONE");
end:
    while (appletMainLoop()) { consoleUpdate(NULL); svcSleepThread(16666667); }
    consoleExit(NULL); return 0;
}
