/* Test-only concurrent libnx applets. Real guest and cores; no shipped code. */
#include <switch.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>

static _Atomic int ready, go, done;
typedef struct {
    int id;
    SwkbdConfig config;
    Result rc;
    char text[128];
} Worker;
static void logline(const char *fmt, ...) {
    char buf[256]; va_list ap; va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
    if (n >= 0) svcOutputDebugString(buf, (size_t)n < sizeof(buf) ? (size_t)n : sizeof(buf)-1);
}
static void run(void *arg) {
    Worker *w = arg;
    atomic_fetch_add(&ready, 1);
    while (!atomic_load(&go)) svcSleepThread(1000000);
    logline("OVERLAP_SHOW_BEGIN id=%d", w->id);
    w->rc = swkbdShow(&w->config, w->text, sizeof(w->text));
    logline("OVERLAP_SHOW_RETURN id=%d rc=0x%08x text=%s", w->id, w->rc, w->text);
    atomic_fetch_add(&done, 1);
}
int main(void) {
    consoleInit(NULL);
    printf("Concurrent software keyboard diagnostic\n"); consoleUpdate(NULL);
    svcSleepThread(2000000000LL);
    Worker workers[2] = {0}; Thread threads[2] = {0};
    for (int i=0; i<2; i++) {
        Worker *w = &workers[i]; w->id = i;
        Result rc = swkbdCreate(&w->config, 0);
        logline("OVERLAP_CREATE id=%d rc=0x%08x", i, rc);
        if (R_FAILED(rc)) goto fail;
        swkbdConfigMakePresetDefault(&w->config);
        swkbdConfigSetHeaderText(&w->config, i == 0 ? "Concurrent A" : "Concurrent B");
        swkbdConfigSetInitialText(&w->config, i == 0 ? "SeedA" : "SeedB");
        swkbdConfigSetStringLenMin(&w->config, 1);
        swkbdConfigSetStringLenMax(&w->config, 24);
        rc = threadCreate(&threads[i], run, w, NULL, 0x10000, 0x2c, -2);
        logline("OVERLAP_THREAD_CREATE id=%d rc=0x%08x", i, rc);
        if (R_FAILED(rc)) goto fail;
        rc = threadStart(&threads[i]);
        logline("OVERLAP_THREAD_START id=%d rc=0x%08x", i, rc);
        if (R_FAILED(rc)) goto fail;
    }
    while (atomic_load(&ready) != 2) svcSleepThread(1000000);
    logline("OVERLAP_BOTH_READY"); atomic_store(&go, 1);
    while (atomic_load(&done) != 2) { consoleUpdate(NULL); svcSleepThread(16666667); }
    for (int i=0; i<2; i++) { threadWaitForExit(&threads[i]); threadClose(&threads[i]); swkbdClose(&workers[i].config); }
    logline("OVERLAP_BOTH_DONE");
    while (appletMainLoop()) { consoleUpdate(NULL); svcSleepThread(16666667); }
    consoleExit(NULL); return 0;
fail:
    logline("OVERLAP_SETUP_FAILED");
    while (appletMainLoop()) { consoleUpdate(NULL); svcSleepThread(16666667); }
    consoleExit(NULL); return 1;
}
