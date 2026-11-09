/* Disposable, self-authored libnx NRO for real HID IPC diagnostics.
 * No keys, firmware, assets, network or replacement service implementations.
 * The values intentionally vary guest IPC fields; failures are reported as-is.
 */
#include <switch.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>

static void record(const char *format, ...) {
    char buf[256];
    va_list args;
    va_start(args, format);
    int len = vsnprintf(buf, sizeof(buf), format, args);
    va_end(args);
    if (len < 0) return;
    size_t n = (size_t)len;
    if (n >= sizeof(buf)) n = sizeof(buf) - 1;
    svcOutputDebugString(buf, n);
    printf("%s\n", buf);
}

/* Exactly the 24-byte wire layout used by libnx for HID command 106. */
typedef struct {
    uint32_t id;
    uint32_t pad;
    uint64_t aruid;
    uint64_t event_ptr;
} AcquireInput;
_Static_assert(sizeof(AcquireInput) == 24, "unexpected HID IPC layout");

static void acquire_raw(unsigned index, uint32_t id, uint32_t pad, uint64_t aruid) {
    AcquireInput input = {id, pad, aruid, 0};
    Handle event = INVALID_HANDLE;
    Result rc = serviceDispatchIn(hidGetServiceSession(), 106, input,
        .in_send_pid = true,
        .out_handle_attrs = { SfOutHandleAttr_HipcCopy },
        .out_handles = &event,
    );
    record("HID_MATRIX index=%u id=0x%08x pad=0x%08x aruid=0x%016llx rc=0x%08x module=%u description=%u handle=%s",
        index, id, pad, (unsigned long long)aruid, rc,
        (unsigned)R_MODULE(rc), (unsigned)R_DESCRIPTION(rc),
        event != INVALID_HANDLE ? "returned" : "none");
    if (R_SUCCEEDED(rc) && event != INVALID_HANDLE) svcCloseHandle(event);
}

int main(void) {
    consoleInit(NULL);
    record("HID_MATRIX_START version=1 wire_bytes=24");
    u64 aruid = appletGetAppletResourceUserId();
    padConfigureInput(1, HidNpadStyleSet_NpadStandard);
    PadState pad;
    padInitializeDefault(&pad);
    padUpdate(&pad);
    Result style_rc = hidSetSupportedNpadStyleSet(HidNpadStyleSet_NpadStandard);
    HidNpadIdType ids[] = {HidNpadIdType_No1, HidNpadIdType_Handheld};
    Result id_rc = hidSetSupportedNpadIdType(ids, 2);
    record("HID_MATRIX_SETUP aruid=0x%016llx style_rc=0x%08x ids_rc=0x%08x",
        (unsigned long long)aruid, style_rc, id_rc);
    Event normal = {0};
    Result normal_rc = hidAcquireNpadStyleSetUpdateEventHandle(HidNpadIdType_No1, &normal, true);
    record("HID_MATRIX_LIBNX rc=0x%08x module=%u description=%u",
        normal_rc, (unsigned)R_MODULE(normal_rc), (unsigned)R_DESCRIPTION(normal_rc));
    if (R_SUCCEEDED(normal_rc)) eventClose(&normal);
    uint64_t values[] = { aruid, 0, 1, (uint64_t)1 << 32, UINT64_MAX };
    unsigned index = 0;
    for (unsigned p = 0; p < 2; ++p) {
        for (unsigned i = 0; i < sizeof(values) / sizeof(values[0]); ++i) {
            acquire_raw(index++, HidNpadIdType_No1, p, values[i]);
        }
    }
    acquire_raw(index++, HidNpadIdType_Handheld, 0, aruid);
    acquire_raw(index++, 0x12345678u, 0, aruid);
    record("HID_MATRIX_COMPLETE count=%u", index);
    while (appletMainLoop()) {
        padUpdate(&pad);
        consoleUpdate(NULL);
        svcSleepThread(16666667);
    }
    consoleExit(NULL);
    return 0;
}
