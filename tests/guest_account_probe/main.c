/* Synthetic CI-only account IPC test; no game, profile data, or fake response. */
#include <switch.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

static void log_event(const char *fmt, ...) {
    char buf[256]; va_list ap; va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
    if (n > 0) svcOutputDebugString(buf, (size_t)n < sizeof(buf) ? (size_t)n : sizeof(buf) - 1);
}
int main(void) {
    FILE *mode_file = fopen("sdmc:/ci-account-mode.txt", "r");
    char mode[20] = {0};
    if (!mode_file || !fgets(mode, sizeof(mode), mode_file)) {
        if (mode_file) fclose(mode_file);
        log_event("ACCOUNT_PROBE_ERROR stage=mode"); goto done;
    }
    fclose(mode_file);
    int send_pid = !strcmp(mode, "pid\n");
    if (!send_pid && strcmp(mode, "normal\n")) { log_event("ACCOUNT_PROBE_ERROR stage=mode"); goto done; }
    const char *label = send_pid ? "pid" : "normal";
    log_event("ACCOUNT_PROBE_BEGIN mode=%s", label);
    Service service = {0};
    Result rc = smGetService(&service, "acc:u0");
    log_event("ACCOUNT_PROBE_OPEN mode=%s rc=0x%08x", label, rc);
    if (R_FAILED(rc)) { log_event("ACCOUNT_PROBE_ERROR stage=open rc=0x%08x", rc); goto done; }
    s32 count = -1;
    rc = serviceDispatchOut(&service, 0, count);
    log_event("ACCOUNT_PROBE_USER_COUNT mode=%s rc=0x%08x count=%d", label, rc, count);
    if (R_FAILED(rc)) { log_event("ACCOUNT_PROBE_ERROR stage=user_count rc=0x%08x", rc); goto close; }
    u64 placeholder = 0;
    rc = serviceDispatchIn(&service, 140, placeholder, .in_send_pid = true);
    log_event("ACCOUNT_PROBE_INIT mode=%s rc=0x%08x", label, rc);
    if (R_FAILED(rc)) { log_event("ACCOUNT_PROBE_ERROR stage=init rc=0x%08x", rc); goto close; }
    for (int i=0; i<2; i++) {
        if (i) svcSleepThread(18000000000LL);
        const char *phase = i ? "late" : "early";
        u8 locked = 0xff;
        log_event("ACCOUNT_PROBE_CALL_BEGIN mode=%s phase=%s", label, phase);
        rc = send_pid ? serviceDispatchOut(&service, 150, locked, .in_send_pid = true)
                      : serviceDispatchOut(&service, 150, locked);
        log_event("ACCOUNT_PROBE_RESULT mode=%s phase=%s rc=0x%08x locked=%u", label, phase, rc, locked);
        if (R_FAILED(rc) || locked > 1) {
            log_event("ACCOUNT_PROBE_ERROR stage=operation mode=%s phase=%s rc=0x%08x", label, phase, rc); goto close;
        }
    }
    log_event("ACCOUNT_PROBE_COMPLETE mode=%s", label);
close:
    serviceClose(&service);
done:
    svcSleepThread(15000000000LL);
    return 0;
}
